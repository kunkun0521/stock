package com.li.stock.prob.task;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 概率引擎只读查询层（文档 §10.3）。
 *
 * <p>Spring Boot 只负责接口与任务调度，统计计算全在 Python 引擎；这里不写业务算法。
 * 性能约束：所有列表都走 prob_prediction 的 (asof_date,horizon,pred_type,p_up) 索引，
 * ORDER BY 只排所选列、不追加多余排序键（避免 MySQL filesort 全表扫描），分页上限 200。</p>
 *
 * <p>返回值统一做类型归一：Date→yyyy-MM-dd 字符串、Decimal→double、MySQL JSON 列→Map/List，
 * 前端不用再处理时间戳或字符串化的 JSON。</p>
 */
@Repository
public class ProbRepository {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> SORT_COLUMNS = Set.of(
            "p_up", "exp_ret", "exp_ret/vol_h", "risk_level", "reliability_grade",
            "prob_drop_5", "prob_drop_10", "vol_h", "cvar95", "var95", "q95", "code");

    private final JdbcTemplate jdbc;
    private final String configPath;
    private final String featuresPath;
    private final String sourceTable;

    public ProbRepository(JdbcTemplate jdbc,
                          @Value("${stock.prob.config-path:D:/code/PythonProject/stock-probability-engine/config.yaml}") String configPath,
                          @Value("${stock.prob.features-path:D:/code/PythonProject/stock-probability-engine/features.yaml}") String featuresPath,
                          @Value("${stock.prob.source-table:stock_daily}") String sourceTable) {
        this.jdbc = jdbc;
        this.configPath = configPath;
        this.featuresPath = featuresPath;
        this.sourceTable = sourceTable;
    }

    // ---------------------------------------------------------------- 通用工具

    private static Object val(Object v) {
        if (v == null) return null;
        if (v instanceof Date d) return d.toLocalDate().toString();
        if (v instanceof Timestamp t) return t.toLocalDateTime().toString();
        if (v instanceof BigDecimal b) return b.doubleValue();
        if (v instanceof byte[] bytes) return new String(bytes, StandardCharsets.UTF_8);
        return v;
    }

    private static List<Map<String, Object>> rows(List<Map<String, Object>> src, String... jsonCols) {
        List<Map<String, Object>> out = new ArrayList<>(src.size());
        for (Map<String, Object> r : src) {
            Map<String, Object> m = new LinkedHashMap<>();
            r.forEach((k, v) -> m.put(k, val(v)));
            for (String c : jsonCols) {
                Object raw = m.get(c);
                if (raw instanceof String s && !s.isBlank()) {
                    try {
                        m.put(c, JSON.readValue(s, Object.class));
                    } catch (Exception ignore) {
                        m.put(c, s);
                    }
                }
            }
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> one(List<Map<String, Object>> src) {
        return src.isEmpty() ? Map.of() : rows(src).get(0);
    }

    private long scalarLong(String sql, Object... args) {
        Long v = jdbc.queryForObject(sql, Long.class, args);
        return v == null ? 0L : v;
    }

    private Double scalarDouble(String sql, Object... args) {
        Double v = jdbc.queryForObject(sql, Double.class, args);
        return v;
    }

    private String scalarString(String sql, Object... args) {
        List<String> list = jdbc.queryForList(sql, String.class, args);
        return list.isEmpty() ? null : list.get(0);
    }

    // ---------------------------------------------------------------- 榜单

    public String latestAsOf() {
        return scalarString("SELECT MAX(asof_date) FROM prob_prediction WHERE pred_type='LIVE'");
    }

    public String latestModelVersion(String asof) {
        if (asof == null) return null;
        return scalarString("SELECT MAX(model_version) FROM prob_prediction "
                + "WHERE pred_type='LIVE' AND asof_date=?", asof);
    }

    public Map<String, Object> predictions(String asof, int horizon, String sort, String order,
                                           Integer riskLevel, String minGrade, String keyword,
                                           boolean excludeHighVol, int page, int size) {
        String a = asof == null || asof.isBlank() ? latestAsOf() : asof;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asof", a);
        out.put("horizon", horizon);
        out.put("modelVersion", latestModelVersion(a));
        if (a == null || out.get("modelVersion") == null) {
            out.put("items", List.of());
            out.put("total", 0L);
            out.put("page", page);
            out.put("size", size);
            out.put("pages", 0);
            out.put("message", "尚无 LIVE 预测，请在「任务与参数」页运行一键日常任务");
            return out;
        }
        String ver = String.valueOf(out.get("modelVersion"));
        String col = SORT_COLUMNS.contains(sort) ? sort : "p_up";
        String expr = col.equals("exp_ret/vol_h") ? "p.exp_ret/NULLIF(p.vol_h,0)" : "p." + col;
        String dir = "asc".equalsIgnoreCase(order) ? "ASC" : "DESC";

        StringBuilder w = new StringBuilder(
                " WHERE p.asof_date=? AND p.horizon=? AND p.pred_type='LIVE' AND p.model_version=? ");
        List<Object> args = new ArrayList<>(List.of(a, horizon, ver));
        if (riskLevel != null) {
            w.append(" AND p.risk_level=? ");
            args.add(riskLevel);
        }
        if (minGrade != null && !minGrade.isBlank()) {
            w.append(" AND p.reliability_grade<=? ");
            args.add(minGrade.substring(0, 1).toUpperCase());
        }
        if (keyword != null && !keyword.isBlank()) {
            w.append(" AND p.code LIKE ? ");
            args.add("%" + keyword.trim() + "%");
        }
        if (excludeHighVol) {
            w.append(" AND p.risk_level<=2 ");
        }
        long total = scalarLong("SELECT COUNT(*) FROM prob_prediction p" + w, args.toArray());
        int safeSize = Math.max(1, Math.min(size, 200));
        int safePage = Math.max(1, page);
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add((safePage - 1) * safeSize);
        List<Map<String, Object>> items = rows(jdbc.queryForList(
                "SELECT p.* FROM prob_prediction p" + w
                        + " ORDER BY " + expr + " " + dir + " LIMIT ? OFFSET ?",
                pageArgs.toArray()), "components_json");
        out.put("items", items);
        out.put("total", total);
        out.put("page", safePage);
        out.put("size", safeSize);
        out.put("pages", (int) ((total + safeSize - 1) / safeSize));
        return out;
    }

    // ---------------------------------------------------------------- 总览

    /** 总览结果的小 TTL 缓存：聚合查询要扫当日全市场，10 秒内重复打开页面直接复用。 */
    private final java.util.concurrent.ConcurrentHashMap<String, Object[]> overviewCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long OVERVIEW_TTL_MS = 10_000L;

    public void invalidateOverview() {
        overviewCache.clear();
    }

    public Map<String, Object> overview() {
        Object[] hit = overviewCache.get("overview");
        if (hit != null && System.currentTimeMillis() - (Long) hit[0] < OVERVIEW_TTL_MS) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cached = (Map<String, Object>) hit[1];
            return cached;
        }
        Map<String, Object> m = buildOverview();
        overviewCache.put("overview", new Object[]{System.currentTimeMillis(), m});
        return m;
    }

    private Map<String, Object> buildOverview() {
        Map<String, Object> m = new LinkedHashMap<>();
        String asof = latestAsOf();
        String ver = latestModelVersion(asof);
        m.put("asof", asof);
        m.put("modelVersion", ver);
        m.put("horizons", jdbc.queryForList(
                "SELECT DISTINCT horizon FROM prob_prediction WHERE pred_type='LIVE' "
                        + "ORDER BY horizon", Integer.class));
        if (asof == null || ver == null) {
            m.put("nPredictions", 0L);
            m.put("nUniverse", 0L);
            m.put("ready", false);
            m.put("message", "尚无预测数据，请在「任务与参数」页运行一键日常任务");
        } else {
            m.put("ready", true);
            // 一次扫描用覆盖索引算出所有分布：之前按 risk_level / reliability_grade 各做一次
            // GROUP BY 会回表读取 components_json（JSON 列很大），实测各要 12 秒。
            Map<String, Object> agg = jdbc.queryForMap(
                    "SELECT COUNT(*) n, COUNT(DISTINCT code) nUniverse, AVG(p_up) avgPUp, "
                            + "COALESCE(SUM(p_up>0.5),0) nBullish, "
                            + "COALESCE(SUM(risk_level=1),0) r1, COALESCE(SUM(risk_level=2),0) r2, "
                            + "COALESCE(SUM(risk_level=3),0) r3, COALESCE(SUM(risk_level=4),0) r4, "
                            + "COALESCE(SUM(reliability_grade='A'),0) gA, "
                            + "COALESCE(SUM(reliability_grade='B'),0) gB, "
                            + "COALESCE(SUM(reliability_grade='C'),0) gC, "
                            + "COALESCE(SUM(reliability_grade='D'),0) gD "
                            + "FROM prob_prediction WHERE asof_date=? AND pred_type='LIVE'", asof);
            m.put("nPredictions", val(agg.get("n")));
            m.put("nUniverse", val(agg.get("nUniverse")));
            m.put("avgPUp", val(agg.get("avgPUp")));
            m.put("nBullish", val(agg.get("nBullish")));
            List<Map<String, Object>> risks = new ArrayList<>();
            for (int i = 1; i <= 4; i++) {
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("risk_level", i);
                b.put("n", val(agg.get("r" + i)));
                risks.add(b);
            }
            m.put("riskBuckets", risks);
            List<Map<String, Object>> grades = new ArrayList<>();
            for (String g : List.of("A", "B", "C", "D")) {
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("reliability_grade", g);
                b.put("n", val(agg.get("g" + g)));
                grades.add(b);
            }
            m.put("gradeBuckets", grades);
            m.put("garchRate", scalarDouble(
                    "SELECT AVG(vol_model='GARCH') FROM prob_stock_model_state WHERE asof_date=?", asof));
            Map<String, Object> totals = jdbc.queryForMap(
                    "SELECT COALESCE(SUM(pred_type='OOS'),0) nOos, "
                            + "COALESCE(SUM(status='SETTLED'),0) nSettled FROM prob_prediction");
            m.put("nSettled", val(totals.get("nSettled")));
            m.put("nOos", val(totals.get("nOos")));
        }
        m.put("runs", rows(jdbc.queryForList(
                "SELECT run_id,run_type,asof_date,status,n_universe,n_ok,n_fail,"
                        + "started_at,finished_at,message FROM prob_run ORDER BY run_id DESC LIMIT 8")));
        m.put("latestEvalDate", scalarString("SELECT MAX(eval_date) FROM prob_eval_metric"));
        m.put("disclaimer", com.li.stock.prob.api.ProbResponse.DISCLAIMER);
        return m;
    }

    // ---------------------------------------------------------------- 个股

    public Map<String, Object> stock(String market, String code) {
        Map<String, Object> m = new LinkedHashMap<>();
        String asof = latestAsOf();
        String ver = latestModelVersion(asof);
        m.put("code", code);
        m.put("market", market);
        m.put("asof", asof);
        if (asof == null || ver == null) return m;
        m.put("predictions", rows(jdbc.queryForList(
                "SELECT * FROM prob_prediction WHERE pred_type='LIVE' AND asof_date=? "
                        + "AND model_version=? AND code=? AND market=? ORDER BY horizon",
                asof, ver, code, market), "components_json"));
        m.put("modelState", one(jdbc.queryForList(
                "SELECT * FROM prob_stock_model_state WHERE asof_date=? AND code=? AND market=?",
                asof, code, market)));
        // 近 60 个交易日前复权收盘价 + 20 日已实现波动（页面扇形图与波动对比）
        List<Map<String, Object>> price = rows(jdbc.queryForList(
                "SELECT trade_date,open,high,low,close,volume,pct_chg FROM `" + sourceTable
                        + "` WHERE adjustflag=2 AND code=? AND market=? AND trade_date<=? "
                        + "AND close>0 ORDER BY trade_date DESC LIMIT 80",
                code, market, asof));
        java.util.Collections.reverse(price);
        List<Map<String, Object>> series = new ArrayList<>();
        java.util.LinkedList<Double> window = new java.util.LinkedList<>();
        double prev = Double.NaN;
        for (Map<String, Object> p : price) {
            double c = ((Number) p.get("close")).doubleValue();
            double ret = Double.isNaN(prev) || prev <= 0 ? 0 : Math.log(c / prev);
            window.add(ret);
            if (window.size() > 20) window.removeFirst();
            double mean = window.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double var = window.stream().mapToDouble(x -> (x - mean) * (x - mean)).average().orElse(0);
            Map<String, Object> pt = new LinkedHashMap<>(p);
            pt.put("realizedVol20", Math.sqrt(var) * Math.sqrt(244));
            series.add(pt);
            prev = c;
        }
        m.put("priceSeries", series);
        return m;
    }

    public Map<String, Object> history(String market, String code, int horizon,
                                       String from, String to) {
        StringBuilder sql = new StringBuilder(
                "SELECT asof_date,pred_type,horizon,p_up,exp_ret,q05,q95,vol_h,var95,"
                        + "realized_ret,realized_up,hit_dir,below_q05,above_q95,breach_var95,"
                        + "status,reliability_grade,risk_level,settle_date "
                        + "FROM prob_prediction WHERE code=? AND market=? AND horizon=? ");
        List<Object> args = new ArrayList<>(List.of(code, market, horizon));
        if (from != null && !from.isBlank()) {
            sql.append(" AND asof_date>=? ");
            args.add(from);
        }
        if (to != null && !to.isBlank()) {
            sql.append(" AND asof_date<=? ");
            args.add(to);
        }
        sql.append(" ORDER BY asof_date DESC, pred_type LIMIT 300");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", rows(jdbc.queryForList(sql.toString(), args.toArray())));
        m.put("total", m.get("items") instanceof List<?> l ? l.size() : 0);
        return m;
    }

    public Map<String, Object> signals(String market, String code, int horizon) {
        Map<String, Object> m = new LinkedHashMap<>();
        String end = scalarString("SELECT MAX(train_end_date) FROM prob_signal_stat");
        m.put("trainEndDate", end);
        m.put("horizon", horizon);
        if (end != null) {
            m.put("items", rows(jdbc.queryForList(
                    "SELECT * FROM prob_signal_stat WHERE train_end_date=? AND horizon=? "
                            + "ORDER BY n_eff DESC LIMIT 100", end, horizon)));
        } else {
            m.put("items", List.of());
        }
        String asof = latestAsOf();
        Map<String, Object> state = one(jdbc.queryForList(
                "SELECT markov_state,markov_matrix_json,markov_pvalue,vol_model,nu,"
                        + "sigma_1d_next,mdd_250,sharpe_250,ann_vol_250 "
                        + "FROM prob_stock_model_state WHERE asof_date=? AND code=? AND market=?",
                asof, code, market));
        if (state.containsKey("markov_matrix_json")) {
            Object raw = state.get("markov_matrix_json");
            if (raw instanceof String s && !s.isBlank()) {
                try {
                    state.put("markovMatrix", JSON.readValue(s, Object.class));
                } catch (Exception ignore) {
                    state.put("markovMatrix", raw);
                }
            }
            state.remove("markov_matrix_json");
        }
        m.put("modelState", state);
        return m;
    }

    // ---------------------------------------------------------------- 评估

    public Map<String, Object> metrics(Integer horizon, String scope, String model) {
        String evalDate = scalarString("SELECT MAX(eval_date) FROM prob_eval_metric");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("evalDate", evalDate);
        if (evalDate == null) {
            m.put("items", List.of());
            return m;
        }
        StringBuilder sql = new StringBuilder("SELECT * FROM prob_eval_metric WHERE eval_date=? ");
        List<Object> args = new ArrayList<>(List.of(evalDate));
        if (horizon != null) {
            sql.append(" AND horizon=? ");
            args.add(horizon);
        }
        if (scope != null && !scope.isBlank()) {
            sql.append(" AND scope=? ");
            args.add(scope);
        }
        if (model != null && !model.isBlank()) {
            sql.append(" AND model_name=? ");
            args.add(model);
        }
        sql.append(" ORDER BY horizon,scope,metric_name LIMIT 1000");
        m.put("items", rows(jdbc.queryForList(sql.toString(), args.toArray())));
        return m;
    }

    public Map<String, Object> metricSeries(String metric, Integer horizon) {
        StringBuilder sql = new StringBuilder(
                "SELECT eval_date,horizon,scope,model_name,value,n_samples "
                        + "FROM prob_eval_metric WHERE metric_name=? ");
        List<Object> args = new ArrayList<>(List.of(metric == null ? "Brier" : metric));
        if (horizon != null) {
            sql.append(" AND horizon=? ");
            args.add(horizon);
        }
        sql.append(" ORDER BY eval_date,horizon LIMIT 1000");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", rows(jdbc.queryForList(sql.toString(), args.toArray())));
        return m;
    }

    public Map<String, Object> calibration(Integer horizon, String model) {
        String evalDate = scalarString("SELECT MAX(eval_date) FROM prob_calibration_bin");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("evalDate", evalDate);
        if (evalDate == null) {
            m.put("items", List.of());
            return m;
        }
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM prob_calibration_bin WHERE eval_date=? ");
        List<Object> args = new ArrayList<>(List.of(evalDate));
        if (horizon != null) {
            sql.append(" AND horizon=? ");
            args.add(horizon);
        }
        if (model != null && !model.isBlank()) {
            sql.append(" AND model_name=? ");
            args.add(model);
        }
        sql.append(" ORDER BY horizon,bin_no LIMIT 200");
        m.put("items", rows(jdbc.queryForList(sql.toString(), args.toArray())));
        return m;
    }

    public Map<String, Object> methods(Integer horizon) {
        String evalDate = scalarString("SELECT MAX(eval_date) FROM prob_method_choice");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("evalDate", evalDate);
        if (evalDate == null) {
            m.put("items", List.of());
            return m;
        }
        String sql = "SELECT * FROM prob_method_choice WHERE eval_date=? "
                + (horizon == null ? "" : " AND horizon=? ") + " ORDER BY horizon,output_name LIMIT 200";
        List<Object> args = horizon == null ? List.of(evalDate) : List.of(evalDate, horizon);
        m.put("items", rows(jdbc.queryForList(sql, args.toArray()), "candidates_json"));
        return m;
    }

    // ---------------------------------------------------------------- 回测

    public Map<String, Object> backtests(int page, int size) {
        int s = Math.max(1, Math.min(size, 100));
        int p = Math.max(1, page);
        long total = scalarLong("SELECT COUNT(*) FROM prob_backtest_summary");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", rows(jdbc.queryForList(
                "SELECT * FROM prob_backtest_summary ORDER BY backtest_id DESC LIMIT ? OFFSET ?",
                s, (p - 1) * s), "params_json"));
        m.put("total", total);
        m.put("page", p);
        m.put("size", s);
        m.put("pages", (int) ((total + s - 1) / s));
        return m;
    }

    public Map<String, Object> backtest(long id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("summary", one(jdbc.queryForList(
                "SELECT * FROM prob_backtest_summary WHERE backtest_id=?", id)));
        m.put("nav", rows(jdbc.queryForList(
                "SELECT trade_date,nav,bench_nav,drawdown FROM prob_backtest_nav "
                        + "WHERE backtest_id=? ORDER BY trade_date", id)));
        m.put("trades", rows(jdbc.queryForList(
                "SELECT * FROM prob_backtest_trade WHERE backtest_id=? "
                        + "ORDER BY signal_date DESC LIMIT 200", id)));
        return m;
    }

    // ---------------------------------------------------------------- 任务与配置

    public Map<String, Object> runs(int page, int size) {
        int s = Math.max(1, Math.min(size, 100));
        int p = Math.max(1, page);
        long total = scalarLong("SELECT COUNT(*) FROM prob_run");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", rows(jdbc.queryForList(
                "SELECT * FROM prob_run ORDER BY run_id DESC LIMIT ? OFFSET ?",
                s, (p - 1) * s), "config_json", "excluded_json"));
        m.put("total", total);
        m.put("page", p);
        m.put("size", s);
        m.put("pages", (int) ((total + s - 1) / s));
        return m;
    }

    public Map<String, Object> run(long id) {
        return one(jdbc.queryForList("SELECT * FROM prob_run WHERE run_id=?", id));
    }

    /** 与 Python 端 config.py 同一口径：允许 // 与 # 行注释（JSON 语法的 YAML 1.2）。 */
    private static String stripComments(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\\R")) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#") || s.startsWith("//")) continue;
            int p = line.indexOf("//");
            if (p >= 0) {
                String before = line.substring(0, p);
                // 两个保护：① // 落在字符串里（例如 mysql+pymysql://...）不能截断；
                // ② // 前是冒号说明是 URL 协议分隔符，也不能截断。
                boolean inString = before.chars().filter(ch -> ch == '"').count() % 2 == 1;
                boolean protocol = before.endsWith(":");
                if (!inString && !protocol && !s.startsWith("http")) line = before;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    public Map<String, Object> config() {
        try {
            String text = stripComments(Files.readString(Path.of(configPath), StandardCharsets.UTF_8));
            return JSON.readValue(text, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            return Map.of("error", "读取 config.yaml 失败：" + e.getMessage(), "path", configPath);
        }
    }

    public Map<String, Object> features() {
        try {
            String text = stripComments(Files.readString(Path.of(featuresPath), StandardCharsets.UTF_8));
            return JSON.readValue(text, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            return Map.of("error", "读取 features.yaml 失败：" + e.getMessage(), "path", featuresPath);
        }
    }

    public Map<String, Object> modelRegistry() {
        return Map.of("items", rows(jdbc.queryForList(
                "SELECT model_name,horizon,train_end_date,artifact_path,params_json,metrics_json "
                        + "FROM prob_model_registry ORDER BY train_end_date DESC,model_name LIMIT 100"),
                "params_json", "metrics_json"));
    }
}
