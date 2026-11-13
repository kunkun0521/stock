package com.li.stock.ga.metric;

import com.li.stock.ga.GaMath;
import com.li.stock.ga.config.GaConfig;
import com.li.stock.ga.repository.GaDataRepository;
import com.li.stock.ga.repository.GaStoreRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 看板与分析查询（规格 11 / 12 / 10.6）。
 *
 * <p>全部走 ga_metric_daily 与带索引的 ga_prediction，SQL 用别名直接产出 camelCase 字段，
 * 日期用 DATE_FORMAT 转成 yyyy-MM-dd 字符串，避免前端拿到时间戳。</p>
 */
@Service
public class GaQueryService {

    private final JdbcTemplate jdbc;
    private final GaConfig config;
    private final GaStoreRepository store;
    private final GaDataRepository data;

    public GaQueryService(JdbcTemplate jdbc, GaConfig config, GaStoreRepository store, GaDataRepository data) {
        this.jdbc = jdbc; this.config = config; this.store = store; this.data = data;
    }

    private static final String VERIFIED = " `status`='VERIFIED' AND `model_role`='CHAMPION' AND `in_pool`=1 ";

    // ------------------------------------------------------------------ 总览

    public Map<String, Object> overview(Integer horizon, LocalDate from, LocalDate to,
                                        List<String> boards, boolean strict, boolean includeReplay) {
        StringBuilder w = new StringBuilder(" WHERE " + VERIFIED);
        List<Object> args = new ArrayList<>();
        if (horizon != null && horizon > 0) { w.append(" AND `horizon`=?"); args.add(horizon); }
        w.append(" AND `trade_date` BETWEEN ? AND ?"); args.add(Date.valueOf(from)); args.add(Date.valueOf(to));
        // boards 是板块 → 代码前缀（主板 00/60 是两个前缀），由 Controller 统一归一化
        if (boards != null && !boards.isEmpty()) {
            w.append(" AND (");
            for (int i = 0; i < boards.size(); i++) {
                if (i > 0) w.append(" OR ");
                w.append("`code` LIKE ?");
                args.add(boards.get(i) + "%");
            }
            w.append(")");
        }
        if (!includeReplay) w.append(" AND `is_replay`=0");
        if (strict) w.append(" AND `is_effective`=1");
        Map<String, Object> m = jdbc.queryForMap("SELECT COUNT(*) n, "
                + "SUM(CASE WHEN `is_correct`=1 THEN 1 ELSE 0 END) c, "
                + "SUM(CASE WHEN `pred_direction`=1 AND `is_correct`=1 THEN 1 ELSE 0 END) uc, "
                + "SUM(CASE WHEN `pred_direction`=1 THEN 1 ELSE 0 END) un, "
                + "SUM(CASE WHEN `pred_direction`=-1 AND `is_correct`=1 THEN 1 ELSE 0 END) dc, "
                + "SUM(CASE WHEN `pred_direction`=-1 THEN 1 ELSE 0 END) dn, "
                + "AVG(`confidence`) conf FROM `ga_prediction`" + w, args.toArray());
        int n = num(m.get("n")), c = num(m.get("c"));
        Map<String, Object> kpi = new LinkedHashMap<>();
        kpi.put("hitRate", GaMath.div(c, n, 0));
        kpi.put("upHitRate", GaMath.div(num(m.get("uc")), num(m.get("un")), 0));
        kpi.put("downHitRate", GaMath.div(num(m.get("dc")), num(m.get("dn")), 0));
        kpi.put("coverage", coverage(from, to, horizon, includeReplay));
        kpi.put("avgIc", avgIc(from, to, horizon));
        kpi.put("nVerified", n);
        kpi.put("championVersion", horizon == null || horizon <= 0 ? "" : store.currentVersionTag(horizon));
        kpi.put("effectiveFrom", currentEffectiveFrom());
        kpi.put("eliminatedCount", eliminatedCount());

        List<Map<String, Object>> alerts = new ArrayList<>();
        String price = data.priceWarning();
        if (!price.isEmpty()) alerts.add(Map.of("level", "warn", "text", price));
        List<String> quality = new java.util.ArrayList<>();
        try { quality.addAll(new FeatureCheck(data, config).check(LocalDate.now())); } catch (Exception ignored) {}
        for (String s : quality) alerts.add(Map.of("level", "warn", "text", s));
        double alertRate = config.globalDouble("alert_hit_rate");
        if (n >= 200 && GaMath.div(c, n, 0) < alertRate) {
            alerts.add(Map.of("level", "error", "text",
                    String.format("近区间命中率 %.2f%% 低于 %.0f%%，模型可能退化，建议检查进化监控",
                            GaMath.div(c, n, 0) * 100, alertRate * 100)));
        }
        if (n == 0) alerts.add(Map.of("level", "info", "text", "尚无已验证预测，先运行 FeatureJob → EvolveJob → PredictJob → VerifyJob"));
        return Map.of("kpi", kpi, "alerts", alerts);
    }

    /** 轻量质检引用，避免 GaQueryService 依赖 FeatureService 造成循环。 */
    private record FeatureCheck(GaDataRepository data, GaConfig config) {
        List<String> check(LocalDate today) {
            List<String> out = new ArrayList<>();
            int flag = config.globalInt("adjust_flag");
            // 以行情库最新交易日为基准，而不是日历今天（收盘前/非交易日今天尚无行情属正常）
            LocalDate latest = data.latestDate(flag);
            if (latest != null && data.countFeatureDate(latest, flag) == 0) {
                out.add("最新交易日尚无特征缓存，看板数据可能滞后，请运行 FeatureJob");
            }
            return out;
        }
    }

    private double coverage(LocalDate from, LocalDate to, Integer h, boolean replay) {
        StringBuilder w = new StringBuilder(" WHERE `trade_date` BETWEEN ? AND ?");
        List<Object> a = new ArrayList<>(List.of(Date.valueOf(from), Date.valueOf(to)));
        if (h != null && h > 0) { w.append(" AND `horizon`=?"); a.add(h); }
        if (!replay) w.append(" AND `is_replay`=0");
        Map<String, Object> m = jdbc.queryForMap("SELECT COUNT(*) n, "
                + "SUM(CASE WHEN `pred_direction`<>0 THEN 1 ELSE 0 END) c FROM `ga_prediction`" + w, a.toArray());
        return GaMath.div(num(m.get("c")), num(m.get("n")), 0);
    }

    private double avgIc(LocalDate from, LocalDate to, Integer h) {
        List<Object> a = new ArrayList<>(List.of(Date.valueOf(from), Date.valueOf(to)));
        String extra = h != null && h > 0 ? " AND `horizon`=? " : "";
        if (h != null && h > 0) a.add(h);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT `ic` FROM `ga_metric_daily` "
                + "WHERE `scope_type`='ALL' AND `window_days`=20 AND `as_of_date` BETWEEN ? AND ?" + extra, a.toArray());
        return rows.stream().mapToDouble(r -> r.get("ic") instanceof Number n ? n.doubleValue() : 0)
                .average().orElse(0);
    }

    private Object currentEffectiveFrom() {
        List<Map<String, Object>> r = jdbc.queryForList("SELECT DATE_FORMAT(`effective_from`,'%Y-%m-%d') d "
                + "FROM `ga_champion_set` WHERE `is_current`=1 ORDER BY `id` DESC LIMIT 1");
        return r.isEmpty() ? "" : r.get(0).get("d");
    }

    private int eliminatedCount() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM `ga_stock_pool` WHERE `pool_status`='ELIMINATED'", Integer.class);
        return n == null ? 0 : n;
    }

    public Map<String, Object> trend(Integer horizon, int window, LocalDate from, LocalDate to) {
        List<Map<String, Object>> rows = trendRows(horizon, window, from, to, 0);
        // 新上线系统还没有实盘指标时，退回展示回放指标，避免图表空白
        if (rows.isEmpty()) rows = trendRows(horizon, window, from, to, 1);
        List<String> dates = rows.stream().map(r -> String.valueOf(r.get("d"))).distinct().toList();
        Map<Integer, Map<String, Double>> byH = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            int h = num(r.get("horizon"));
            double rate = r.get("hit_rate") instanceof Number n ? n.doubleValue() : 0.0;
            byH.computeIfAbsent(h, k -> new LinkedHashMap<>()).put(String.valueOf(r.get("d")), rate);
        }
        List<Map<String, Object>> series = new ArrayList<>();
        byH.forEach((h, map) -> series.add(Map.of("horizon", h,
                "values", dates.stream().map(d -> map.getOrDefault(d, 0.0)).toList())));
        return Map.of("dates", dates, "series", series);
    }

    private List<Map<String, Object>> trendRows(Integer horizon, int window, LocalDate from,
                                                LocalDate to, int replay) {
        return jdbc.queryForList("SELECT DATE_FORMAT(`as_of_date`,'%Y-%m-%d') d, "
                        + "`horizon`, `hit_rate` FROM `ga_metric_daily` WHERE `scope_type`='ALL' "
                        + "AND `window_days`=? AND `is_replay`=? AND `as_of_date` BETWEEN ? AND ? "
                        + "AND (`horizon`=? OR ?=0) ORDER BY `as_of_date`",
                window, replay, Date.valueOf(from), Date.valueOf(to),
                horizon == null ? 0 : horizon, horizon == null ? 0 : horizon);
    }

    public List<Map<String, Object>> calendar(Integer horizon, String month) {
        LocalDate from = LocalDate.parse((month == null || month.isBlank()
                ? LocalDate.now().withDayOfMonth(1).toString() : month).substring(0, 7) + "-01");
        LocalDate to = from.withDayOfMonth(from.lengthOfMonth());
        List<Map<String, Object>> rows = calendarRows(horizon, from, to, 0);
        if (rows.isEmpty()) rows = calendarRows(horizon, from, to, 1);
        return rows;
    }

    private List<Map<String, Object>> calendarRows(Integer horizon, LocalDate from, LocalDate to,
                                                   int replay) {
        return jdbc.queryForList("SELECT DATE_FORMAT(`as_of_date`,'%Y-%m-%d') `date`, "
                        + "`hit_rate` `hitRate`, `n_verified` n FROM `ga_metric_daily` "
                        + "WHERE `scope_type`='ALL' AND `window_days`=1 AND `is_replay`=? "
                        + "AND (`horizon`=? OR ?=0) AND `as_of_date` BETWEEN ? AND ? ORDER BY `as_of_date`",
                replay, horizon == null ? 0 : horizon, horizon == null ? 0 : horizon,
                Date.valueOf(from), Date.valueOf(to));
    }

    // ------------------------------------------------------------------ 个股详情

    public Map<String, Object> stockDetail(String market, String code, Integer horizon,
                                           LocalDate from, LocalDate to) {
        int flag = config.globalInt("adjust_flag");
        List<Map<String, Object>> bars = jdbc.queryForList("SELECT DATE_FORMAT(`trade_date`,'%Y-%m-%d') `tradeDate`, "
                        + "`open`,`close`,`high`,`low`,`volume` FROM `stock_daily_all` "
                        + "WHERE `code`=? AND `market`=? AND `adjustflag`=? AND `trade_date` BETWEEN ? AND ? "
                        + "ORDER BY `trade_date`", code, market, flag, Date.valueOf(from), Date.valueOf(to));
        List<Object> args = new ArrayList<>(List.of(code, market, Date.valueOf(from), Date.valueOf(to)));
        String hextra = "";
        if (horizon != null && horizon > 0) { hextra = " AND `horizon`=? "; args.add(horizon); }
        List<Map<String, Object>> preds = jdbc.queryForList("SELECT DATE_FORMAT(`trade_date`,'%Y-%m-%d') `tradeDate`, "
                + "`horizon`,`pred_direction` `predDirection`,`prob_up` `probUp`,`confidence`,`is_correct` `isCorrect`,"
                + "`actual_return` `actualReturn`,`status`,`explain_json` `explainJson`,`model_role` `modelRole` "
                + "FROM `ga_prediction` WHERE `code`=? AND `market`=? AND `trade_date` BETWEEN ? AND ?" + hextra
                + " ORDER BY `trade_date` DESC,`horizon` LIMIT 500", args.toArray());
        List<Map<String, Object>> marks = new ArrayList<>(), latest = new ArrayList<>();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (Map<String, Object> p : preds) {
            Map<String, Object> mark = new LinkedHashMap<>(p);
            mark.remove("explainJson");
            marks.add(mark);
            int h = num(p.get("horizon"));
            if ("CHAMPION".equals(String.valueOf(p.get("modelRole"))) && seen.add(h)) {
                Map<String, Object> card = new LinkedHashMap<>(p);
                card.put("explain", List.of());
                latest.add(card);
            }
        }
        List<Map<String, Object>> hitRate = jdbc.queryForList("SELECT `horizon`, COUNT(*) n, "
                + "AVG(`is_correct`) `hitRate` FROM `ga_prediction` WHERE `code`=? AND `market`=? "
                + "AND `status`='VERIFIED' AND `pred_direction`<>0 GROUP BY `horizon`", code, market);
        List<Map<String, Object>> timeline = jdbc.queryForList("SELECT DATE_FORMAT(`event_date`,'%Y-%m-%d') `eventDate`,"
                + "`event_type` `eventType`,`reason_code` `reasonCode`,`reason_detail` `reasonDetail` "
                + "FROM `ga_stock_elimination_log` WHERE `code`=? AND `market`=? ORDER BY `id` DESC LIMIT 100",
                code, market);
        return Map.of("bars", bars, "marks", marks, "latest", latest, "hitRate", hitRate, "timeline", timeline);
    }

    // ------------------------------------------------------------------ 错误分析

    public List<Map<String, Object>> buckets(int h, String dim, LocalDate from, LocalDate to) {
        String expr = switch (dim == null ? "vol" : dim) {
            case "amount" -> "CASE WHEN `amount_ma_20` IS NULL THEN '未知' WHEN `amount_ma_20`<2e7 THEN 'Q1' "
                    + "WHEN `amount_ma_20`<5e7 THEN 'Q2' WHEN `amount_ma_20`<1e8 THEN 'Q3' "
                    + "WHEN `amount_ma_20`<3e8 THEN 'Q4' ELSE 'Q5' END";
            case "pe" -> "CASE WHEN `pe_ttm` IS NULL OR `pe_ttm`<=0 THEN '亏损/缺失' "
                    + "WHEN `pe_ttm`<20 THEN '<20' WHEN `pe_ttm`<50 THEN '20-50' ELSE '>50' END";
            case "board" -> "LEFT(`code`,2)";
            case "regime" -> "CAST(`regime` AS CHAR)";
            case "prob" -> "CASE WHEN `prob_up`<0.3 THEN '0.0-0.3' WHEN `prob_up`<0.4 THEN '0.3-0.4' "
                    + "WHEN `prob_up`<0.5 THEN '0.4-0.5' WHEN `prob_up`<0.6 THEN '0.5-0.6' "
                    + "WHEN `prob_up`<0.7 THEN '0.6-0.7' ELSE '0.7-1.0' END";
            case "limit" -> "CAST(`limit_flag` AS CHAR)";
            default -> "CASE WHEN `vol_20` IS NULL THEN '未知' WHEN `vol_20`<0.01 THEN 'Q1' "
                    + "WHEN `vol_20`<0.02 THEN 'Q2' WHEN `vol_20`<0.03 THEN 'Q3' "
                    + "WHEN `vol_20`<0.05 THEN 'Q4' ELSE 'Q5' END";
        };
        return jdbc.queryForList("SELECT " + expr + " `bucket`, COUNT(*) n, AVG(`is_correct`) `hitRate` "
                + "FROM `ga_prediction` WHERE `status`='VERIFIED' AND `pred_direction`<>0 AND `horizon`=? "
                + "AND `trade_date` BETWEEN ? AND ? GROUP BY `bucket` ORDER BY `bucket`",
                h, Date.valueOf(from), Date.valueOf(to));
    }

    public Map<String, Object> reliability(int h, LocalDate from, LocalDate to) {
        int bins = Math.max(5, config.globalInt("ece_bins"));
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT `prob_up` p, "
                + "CASE WHEN `actual_return`>0 THEN 1 ELSE 0 END y FROM `ga_prediction` "
                + "WHERE `status`='VERIFIED' AND `horizon`=? AND `trade_date` BETWEEN ? AND ?",
                h, Date.valueOf(from), Date.valueOf(to));
        double[] sumP = new double[bins], sumY = new double[bins];
        int[] n = new int[bins];
        for (Map<String, Object> r : rows) {
            double p = r.get("p") instanceof Number x ? x.doubleValue() : 0.5;
            int b = Math.min(bins - 1, Math.max(0, (int) Math.floor(p * bins)));
            sumP[b] += p; sumY[b] += r.get("y") instanceof Number x ? x.doubleValue() : 0; n[b]++;
        }
        List<Map<String, Object>> points = new ArrayList<>();
        double ece = 0;
        for (int i = 0; i < bins; i++) {
            double avgP = n[i] == 0 ? (i + 0.5) / bins : sumP[i] / n[i];
            double actual = n[i] == 0 ? avgP : sumY[i] / n[i];
            points.add(Map.of("bin", (i + 0.5) / bins, "avgProb", avgP, "actualRate", actual, "n", n[i]));
            ece += ((double) n[i] / Math.max(1, rows.size())) * Math.abs(avgP - actual);
        }
        return Map.of("points", points, "ece", ece);
    }

    public Map<String, Object> confusion(int h, LocalDate from, LocalDate to) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT `pred_direction` d, "
                + "CASE WHEN `actual_return`>0 THEN 1 WHEN `actual_return`<0 THEN -1 ELSE 0 END a, "
                + "COUNT(*) n FROM `ga_prediction` WHERE `status`='VERIFIED' AND `horizon`=? "
                + "AND `trade_date` BETWEEN ? AND ? GROUP BY d,a", h, Date.valueOf(from), Date.valueOf(to));
        long[][] mat = new long[3][3];
        for (Map<String, Object> r : rows) {
            int di = idx(num(r.get("d"))), ai = idx(num(r.get("a")));
            mat[di][ai] += num(r.get("n"));
        }
        List<List<Long>> matrix = new ArrayList<>();
        for (long[] row : mat) matrix.add(Arrays.stream(row).boxed().toList());
        return Map.of("matrix", matrix, "labels", List.of("看跌", "观望", "看涨"));
    }

    private static int idx(int v) { return v == 1 ? 2 : v == -1 ? 0 : 1; }

    public List<Map<String, Object>> topErrors(int h, int topN) {
        return jdbc.queryForList("SELECT `code`,DATE_FORMAT(`trade_date`,'%Y-%m-%d') `tradeDate`,"
                + "`horizon`,`prob_up` `probUp`,`confidence`,`actual_return` `actualReturn`,"
                + "`explain_json` `explainJson` FROM `ga_prediction` WHERE `status`='VERIFIED' "
                + "AND `is_correct`=0 AND `confidence`>=0.3 AND `horizon`=? "
                + "ORDER BY ABS(`actual_return`) DESC LIMIT ?", h, Math.max(1, Math.min(100, topN)));
    }

    public Map<String, Object> quantile(int h, LocalDate from, LocalDate to) {
        int groups = Math.max(2, config.globalInt("quantile_groups"));
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT `trade_date`,`code`,`prob_up`,`actual_return` "
                + "FROM `ga_prediction` WHERE `status`='VERIFIED' AND `horizon`=? "
                + "AND `trade_date` BETWEEN ? AND ? ORDER BY `trade_date`,`prob_up`",
                h, Date.valueOf(from), Date.valueOf(to));
        Map<String, List<Map<String, Object>>> byDate = new LinkedHashMap<>();
        rows.forEach(r -> byDate.computeIfAbsent(String.valueOf(r.get("trade_date")), k -> new ArrayList<>()).add(r));
        double[] sumRet = new double[groups], sumUp = new double[groups];
        int[] n = new int[groups];
        for (List<Map<String, Object>> list : byDate.values()) {
            if (list.size() < groups) continue;
            for (int i = 0; i < list.size(); i++) {
                int g = Math.min(groups - 1, i * groups / list.size());
                double ret = list.get(i).get("actual_return") instanceof Number x ? x.doubleValue() : 0;
                sumRet[g] += ret; sumUp[g] += ret > 0 ? 1 : 0; n[g]++;
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        double[] means = new double[groups];
        for (int g = 0; g < groups; g++) {
            double avg = n[g] == 0 ? 0 : sumRet[g] / n[g];
            means[g] = avg;
            out.add(Map.of("group", "Q" + (g + 1), "avgRet", avg,
                    "upRate", GaMath.div(sumUp[g], n[g], 0)));
        }
        double[] seq = new double[groups];
        for (int g = 0; g < groups; g++) seq[g] = g;
        return Map.of("groups", out, "monotonicity", GaMath.spearman(seq, means),
                "spread", means[groups - 1] - means[0]);
    }

    // ------------------------------------------------------------------ 因子体检 / 选股

    public List<Map<String, Object>> factorsIc(int h, int days) {
        return jdbc.queryForList("SELECT `feature_name` `featureName`,`ic_20` `ic20`,`ic_60` `ic60`,"
                + "`icir_60` `icir60`,`decay_flag` `decayFlag` FROM `ga_factor_ic_daily` "
                + "WHERE `horizon`=? AND `as_of_date`>=DATE_SUB(CURDATE(), INTERVAL ? DAY) "
                + "ORDER BY `feature_name`", h, Math.max(1, days));
    }

    /**
     * 每日选股榜（规格 10.5）。方向用数值 1 / -1；minConsensus 是共振绝对值下限。
     *
     * <p>GROUP_CONCAT 的 detail 在 Java 侧展开成前端约定的 directions / probUps 两个
     * {周期: 值} 对象，避免前端解析字符串；同时补上名称、市场与近 60 日命中率。</p>
     */
    public List<Map<String, Object>> picks(LocalDate date, int topN, int direction, double minConsensus) {
        int want = direction < 0 ? -1 : 1;
        double threshold = want > 0 ? minConsensus : -minConsensus;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT `code`,`market`, DATE_FORMAT(`trade_date`,'%Y-%m-%d') `tradeDate`,"
                        + "SUM(`pred_direction`) `consensus`, AVG(`confidence`) `avgConf`,"
                        + "MAX(`limit_flag`) `limitFlag`,"
                        + "GROUP_CONCAT(CONCAT(`horizon`,':',`pred_direction`,':',`prob_up`) "
                        + "ORDER BY `horizon` SEPARATOR ',') `detail` "
                        + "FROM `ga_prediction` WHERE `trade_date`=? AND `model_role`='CHAMPION' "
                        + "AND `in_pool`=1 AND `pred_direction`<>0 AND `is_replay`=0 "
                        + "GROUP BY `code`,`market`,`trade_date` HAVING `consensus`"
                        + (want > 0 ? ">=?" : "<=?")
                        + " ORDER BY ABS(`consensus`) DESC,`avgConf` DESC LIMIT ?",
                Date.valueOf(date), threshold, Math.max(1, Math.min(200, topN)));
        Map<String, String> names = data.stockNames();
        Map<String, Object> pools = store.poolStates();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            String code = String.valueOf(r.get("code"));
            String market = String.valueOf(r.get("market"));
            m.put("code", code);
            m.put("market", market);
            m.put("name", names.getOrDefault(code + "|" + market, names.getOrDefault(code, "")));
            m.put("tradeDate", String.valueOf(r.get("tradeDate")));
            m.put("consensus", num(r.get("consensus")));
            m.put("avgConf", dbl(r.get("avgConf")));
            // 只有涨停（1）才提示「追高风险」，跌停（-1）提示卖出风险，见 10.9
            m.put("limitFlag", num(r.get("limitFlag")) == 1 ? 1 : 0);
            Map<String, Object> dirs = new LinkedHashMap<>(), probs = new LinkedHashMap<>();
            String detail = r.get("detail") == null ? "" : String.valueOf(r.get("detail"));
            for (String part : detail.split(",")) {
                String[] kv = part.split(":");
                if (kv.length != 3) continue;
                try {
                    dirs.put(kv[0], Integer.parseInt(kv[1]));
                    probs.put(kv[0], Double.parseDouble(kv[2]));
                } catch (NumberFormatException ignored) {
                    // 异常明细不影响整行展示
                }
            }
            m.put("directions", dirs);
            m.put("probUps", probs);
            Object state = pools.get(code + "|" + market);
            m.put("hitRate60", state instanceof Map<?, ?> s ? s.get("hitRate60") : null);
            out.add(m);
        }
        return out;
    }

    private static int num(Object o) { return o instanceof Number n ? n.intValue() : 0; }
    private static double dbl(Object o) { return o instanceof Number n ? n.doubleValue() : 0; }
}
