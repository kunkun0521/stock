package com.li.stock.ga.pool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.li.stock.ga.GaMath;
import com.li.stock.ga.GaModel.Bar;
import com.li.stock.ga.config.GaConfig;
import com.li.stock.ga.repository.GaDataRepository;
import com.li.stock.ga.repository.GaStoreRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 股票淘汰与复活（规格 7）。
 *
 * <p>「淘汰」只把股票移出主预测榜进入淘汰池，不删除任何数据；每次状态变化写
 * ga_stock_elimination_log（含原因码、说明与指标快照），可追溯、可复活。
 * 白名单不受自动规则影响（停牌/数据缺失除外）；同一股票 20 个交易日内最多自动淘汰、复活各一次。</p>
 */
@Service
public class PoolService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> SHADOW_REASONS = List.of(
            "POOR_PREDICTABILITY", "EXTREME_VOL", "LOW_LIQUIDITY");

    private final GaConfig config;
    private final GaDataRepository data;
    private final GaStoreRepository store;

    public PoolService(GaConfig config, GaDataRepository data, GaStoreRepository store) {
        this.config = config; this.data = data; this.store = store;
    }

    public String evaluate(LocalDate asOf, boolean weekly) {
        int flag = config.globalInt("adjust_flag");
        // 加载窗口必须覆盖 NEW_LISTING 的最少有效交易日，否则所有股票都会被误判为次新股
        long lookbackDays = Math.max(400L, config.globalInt("new_listing_days") * 2L);
        LocalDate from = asOf.minusDays(lookbackDays);
        Map<String, List<Bar>> bars = data.loadBarsWithScores(flag, from, asOf);
        Map<String, Object> states = store.poolStates();
        Map<String, HitRate> hits = hitRates(asOf);
        // vol_20 截面分位：旧实现写死 0.0，导致 EXTREME_VOL 永不淘汰、复活恒通过
        Map<String, Double> volQuantiles = volQuantiles(bars);
        int eliminated = 0, restored = 0;
        Map<String, Integer> reasons = new LinkedHashMap<>();

        for (Map.Entry<String, List<Bar>> e : bars.entrySet()) {
            String key = e.getKey();
            List<Bar> series = e.getValue();
            if (series.isEmpty()) continue;
            Bar last = series.get(series.size() - 1);
            String[] parts = key.split("\\|");
            String code = parts[0], market = parts.length > 1 ? parts[1] : "SZ";

            @SuppressWarnings("unchecked")
            Map<String, Object> state = (Map<String, Object>) states.get(key);
            String status = state == null ? "ACTIVE" : String.valueOf(state.getOrDefault("poolStatus", "ACTIVE"));
            String currentReason = state == null ? "" : String.valueOf(state.getOrDefault("reasonCode", ""));
            LocalDate since = state == null || state.get("sinceDate") == null ? asOf
                    : LocalDate.parse(String.valueOf(state.get("sinceDate")).substring(0, 10));

            HitRate hit = hits.getOrDefault(key, new HitRate(0, 0, 0, 0));
            Metrics m = metrics(series, config, volQuantiles.getOrDefault(key, 0.0));
            boolean whitelisted = "WHITELIST".equals(status);

            if ("ACTIVE".equals(status) || whitelisted) {
                String reason = null;
                String detail = null;
                Map<String, Object> snapshot = new LinkedHashMap<>();
                if (m.missingRatio > config.globalDouble("data_missing_ratio")) {
                    reason = "DATA_MISSING"; detail = "近 5 日关键字段缺失率 " + pct(m.missingRatio);
                    snapshot.put("missingRatio", m.missingRatio);
                } else if (m.suspendRatio > config.globalDouble("pool_suspend_ratio")) {
                    reason = "SUSPENDED"; detail = "近 20 日停牌占比 " + pct(m.suspendRatio);
                    snapshot.put("suspendRatio", m.suspendRatio);
                } else if (!whitelisted) {
                    if (config.globalBool("exclude_st") && last.isSt() == 1) {
                        reason = "ST"; detail = "最新标记 is_st=1";
                    } else if (series.size() < config.globalInt("new_listing_days")) {
                        reason = "NEW_LISTING"; detail = "有效交易日 " + series.size();
                        snapshot.put("days", series.size());
                    } else if (m.amountMa20 > 0 && m.amountMa20 < config.globalDouble("low_liquidity")) {
                        reason = "LOW_LIQUIDITY"; detail = "20 日均额 " + m.amountMa20;
                        snapshot.put("amountMa20", m.amountMa20);
                    } else if (weekly && m.volQuantile > config.globalDouble("extreme_vol_q")) {
                        reason = "EXTREME_VOL"; detail = "vol_20 截面分位 " + m.volQuantile;
                        snapshot.put("volQuantile", m.volQuantile);
                    } else if (weekly && hit.n60 >= config.globalInt("pool_poor_min_samples")
                            && hit.hit60 < config.globalDouble("pool_poor_hit")) {
                        reason = "POOR_PREDICTABILITY";
                        detail = String.format("近 60 基准日命中率 %.3f（n=%d）", hit.hit60, hit.n60);
                        snapshot.put("hitRate60", hit.hit60); snapshot.put("n60", hit.n60);
                    }
                }
                if (reason != null && coolingOk(key, "ELIMINATE", asOf)) {
                    apply(code, market, flag, "ELIMINATED", reason, detail, asOf, hit, snapshot, "SYSTEM");
                    eliminated++; reasons.merge(reason, 1, Integer::sum);
                } else if (state == null) {
                    store.upsertPool(code, market, flag, status, null, null, asOf,
                            hit.n60 == 0 ? null : hit.hit60, hit.n60,
                            hit.nShadow == 0 ? null : hit.hitShadow, hit.nShadow, asOf);
                }
                continue;
            }

            // 已淘汰：冷却期后按对应规则复活
            boolean restoredNow = false;
            String restoreDetail = null;
            if (cooldownPassed(asOf, since)) {
                restoredNow = switch (currentReason) {
                    case "SUSPENDED" -> m.suspendRatio < config.globalDouble("pool_suspend_restore");
                    case "ST" -> last.isSt() == 0;
                    case "NEW_LISTING" -> series.size() >= config.globalInt("new_listing_days");
                    case "LOW_LIQUIDITY" -> m.amountMa20 >= config.globalDouble("low_liquidity_restore");
                    case "EXTREME_VOL" -> m.volQuantile < config.globalDouble("extreme_vol_restore_q");
                    case "DATA_MISSING" -> m.missingRatio <= 0;
                    case "POOR_PREDICTABILITY" -> hit.nShadow >= config.globalInt("pool_restore_samples")
                            && hit.hitShadow >= config.globalDouble("pool_restore_hit");
                    default -> false;
                };
                restoreDetail = "满足复活条件（" + currentReason + "）";
            }
            if (restoredNow && coolingOk(key, "RESTORE", asOf)) {
                apply(code, market, flag, "ACTIVE", "", restoreDetail, asOf, hit,
                        Map.of("reason", currentReason), "SYSTEM");
                restored++;
            }
        }

        // 任务日志统一由 GaJobRunner.submitNamed 写入，这里不再重复写一条 SUCCESS
        // （旧实现会覆盖掉外层记录的 processed，看板显示 0）
        return String.format("本次淘汰 %d 只、复活 %d 只，原因分布 %s",
                eliminated, restored, reasons);
    }

    private void apply(String code, String market, int flag, String status, String reason,
                       String detail, LocalDate asOf, HitRate hit, Map<String, Object> snapshot,
                       String operator) {
        String event = "ELIMINATED".equals(status) ? "ELIMINATE" : "RESTORE";
        store.upsertPool(code, market, flag, status, reason.isEmpty() ? null : reason, detail, asOf,
                hit.n60 == 0 ? null : hit.hit60, hit.n60,
                hit.nShadow == 0 ? null : hit.hitShadow, hit.nShadow, asOf);
        String metricJson;
        try { metricJson = JSON.writeValueAsString(snapshot); } catch (Exception e) { metricJson = "{}"; }
        store.insertPoolLog(code, market, flag, event, asOf, reason.isEmpty() ? null : reason,
                detail, metricJson, operator);
    }

    /** 手动淘汰 / 复活 / 白名单（必须填备注）。 */
    public String override(String code, String market, String action, String remark, LocalDate asOf) {
        if (remark == null || remark.isBlank()) throw new IllegalArgumentException("操作备注不能为空");
        int flag = config.globalInt("adjust_flag");
        String status = switch (action.toUpperCase()) {
            case "ELIMINATE" -> "ELIMINATED";
            case "RESTORE" -> "ACTIVE";
            case "WHITELIST" -> "WHITELIST";
            default -> throw new IllegalArgumentException("不支持的操作：" + action);
        };
        // 旧实现判断 "MANUAL_ELIMINATE".equals(action) 恒为 false，手动淘汰会被记成「复活」事件，
        // 审计日志与个股时间线显示反向操作。按最终状态映射才不会错。
        String event = switch (status) {
            case "ELIMINATED" -> "MANUAL_ELIMINATE";
            case "WHITELIST" -> "WHITELIST";
            default -> "MANUAL_RESTORE";
        };
        String reason = "MANUAL";
        store.upsertPool(code, market, flag, status, "ELIMINATED".equals(status) ? reason : null,
                remark, asOf, null, null, null, null, asOf);
        try {
            store.insertPoolLog(code, market, flag, event, asOf, reason, remark,
                    JSON.writeValueAsString(Map.of("remark", remark)), "USER");
        } catch (Exception ignored) {
            store.insertPoolLog(code, market, flag, event, asOf, reason, remark, "{}", "USER");
        }
        return action + " 成功";
    }

    /** 近 60 个基准日（主榜）与淘汰后影子预测近 20 日的命中率，SQL 侧直接聚合。 */
    private Map<String, HitRate> hitRates(LocalDate asOf) {
        Map<String, HitRate> out = new HashMap<>();
        // 用真实交易日历换算「基准日」而不是自然日：60 个交易日 ≈ 85 自然日，
        // 20 个交易日 ≈ 30 自然日；旧实现固定 150 自然日且受 LIMIT 截断成最早一个月。
        int mainDays = Math.max(1, config.globalInt("pool_poor_window"));
        int shadowDays = Math.max(1, config.globalInt("pool_restore_window"));
        LocalDate mainFrom = tradingDayBack(asOf, mainDays);
        LocalDate shadowFrom = tradingDayBack(asOf, shadowDays);
        for (Map<String, Object> r : store.poolHitRates(mainFrom, shadowFrom, asOf)) {
            String key = r.get("code") + "|" + r.get("market");
            int mn = intVal(r.get("mainN")), mc = intVal(r.get("mainC"));
            int sn = intVal(r.get("shadowN")), sc = intVal(r.get("shadowC"));
            out.put(key, new HitRate(GaMath.div(mc, mn, 0), mn, GaMath.div(sc, sn, 0), sn));
        }
        return out;
    }

    /** asOf 往前第 n 个交易日（含 asOf）；行情日历不足时退化为自然日估算。 */
    private LocalDate tradingDayBack(LocalDate asOf, int n) {
        int flag = config.globalInt("adjust_flag");
        List<LocalDate> dates = data.tradeDates(flag, asOf.minusDays(Math.max(60, n * 4L)), asOf);
        return dates.size() >= n ? dates.get(dates.size() - n)
                : asOf.minusDays(Math.round(n * 7L / 5.0));
    }

    /** 冷却期按真实交易日计数（配置 pool_cooldown_days 的口径是交易日，不是自然日）。 */
    private boolean cooldownPassed(LocalDate asOf, LocalDate since) {
        return tradingDaysBetween(since, asOf) >= config.globalInt("pool_cooldown_days");
    }

    /** 防抖：同一股票 N 个交易日内最多自动淘汰一次、复活一次。 */
    private boolean coolingOk(String key, String eventType, LocalDate asOf) {
        List<Map<String, Object>> last = store.lastPoolEvent(key.split("\\|")[0],
                key.split("\\|").length > 1 ? key.split("\\|")[1] : "SZ", eventType);
        if (last.isEmpty()) return true;
        Object d = last.get(0).get("eventDate");
        if (d == null) return true;
        LocalDate prev = LocalDate.parse(String.valueOf(d).substring(0, 10));
        return tradingDaysBetween(prev, asOf) >= config.globalInt("pool_cooldown_days");
    }

    private int tradingDaysBetween(LocalDate from, LocalDate to) {
        if (!to.isAfter(from)) return 0;
        return data.tradeDates(config.globalInt("adjust_flag"), from,
                to.plusDays(Math.max(7, config.globalInt("pool_cooldown_days") * 3L))).stream()
                .filter(d -> !d.isBefore(from) && !d.isAfter(to)).toList().size();
    }

    private record HitRate(double hit60, int n60, double hitShadow, int nShadow) {}

    private static Metrics metrics(List<Bar> series, GaConfig cfg, double volQuantile) {
        int w = Math.min(cfg.globalInt("pool_suspend_window"), series.size());
        int suspended = 0, missing = 0;
        double amount = 0;
        for (int i = series.size() - w; i < series.size(); i++) {
            Bar b = series.get(i);
            if (b.tradeStatus() == 0) suspended++;
            if (!Double.isFinite(b.close()) || b.close() <= 0 || b.volume() <= 0 || b.amount() <= 0) missing++;
            amount += Double.isFinite(b.amount()) ? b.amount() : 0;
        }
        int w5 = Math.min(cfg.globalInt("data_missing_window"), series.size());
        int miss5 = 0;
        for (int i = series.size() - w5; i < series.size(); i++) {
            Bar b = series.get(i);
            if (!Double.isFinite(b.close()) || b.volume() <= 0 || b.amount() <= 0) miss5++;
        }
        return new Metrics((double) suspended / Math.max(1, w), amount / Math.max(1, w),
                (double) miss5 / Math.max(1, w5), volQuantile);
    }

    /**
     * 每只股票近 20 个交易日收益率的样本标准差在全池的截面分位（0~1）。
     * 与 FeatureService 的 vol_20 同源口径（pct_chg / 100），仅用于 EXTREME_VOL 规则。
     */
    private static Map<String, Double> volQuantiles(Map<String, List<Bar>> bars) {
        List<Map.Entry<String, Double>> vols = new ArrayList<>();
        for (Map.Entry<String, List<Bar>> e : bars.entrySet()) {
            List<Bar> s = e.getValue();
            int w = Math.min(20, s.size());
            if (w < 2) continue;
            double[] r = new double[w];
            for (int i = 0; i < w; i++) {
                Bar b = s.get(s.size() - w + i);
                double pct = Double.isFinite(b.pctChg()) ? b.pctChg() / 100.0
                        : (b.preclose() > 0 ? b.close() / b.preclose() - 1 : 0);
                r[i] = Double.isFinite(pct) ? pct : 0;
            }
            vols.add(Map.entry(e.getKey(), GaMath.stdSample(r)));
        }
        vols.sort(Map.Entry.comparingByValue());
        Map<String, Double> out = new HashMap<>();
        int n = vols.size();
        for (int i = 0; i < n; i++) out.put(vols.get(i).getKey(), n <= 1 ? 0.0 : (double) i / (n - 1));
        return out;
    }

    private record Metrics(double suspendRatio, double amountMa20, double missingRatio, double volQuantile) {}

    private static String pct(double v) { return String.format("%.1f%%", v * 100); }
    private static int intVal(Object o) { return o instanceof Number n ? n.intValue() : 0; }
}
