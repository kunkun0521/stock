package com.li.stock.ga.repository;

import com.li.stock.ga.GaModel;
import com.li.stock.ga.GaModel.Bar;
import com.li.stock.ga.GaModel.FeatureRow;
import com.li.stock.ga.GaModel.MarketPoint;
import com.li.stock.ga.config.GaConfig;
import com.li.stock.ga.feature.FeatureKeys;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 行情/特征/市场数据访问：只读现有表 + 只写两张新缓存表。
 *
 * <p>沿用项目现有的 {@code spring-boot-starter-jdbc} / JdbcTemplate 风格，
 * 不新增 MyBatis Mapper（启动类 {@code @MapperScan} 只扫 com.li.stock.mapper），
 * 也不修改任何现有 SQL。行情统一查 {@code stock_daily_all} 视图（三张物理分表之和）。</p>
 */
@Repository
public class GaDataRepository {

    private static final String BAR_COLUMNS = "`code`,`market`,`trade_date`,`open`,`high`,`low`,`close`,`preclose`,"
            + "`volume`,`amount`,`amplitude`,`pct_chg`,`turnover`,`tradestatus`,`is_st`,"
            + "`pe_ttm`,`pb_mrq`,`ps_ttm`,`pcf_ncf_ttm`";

    private static final String FEATURE_COLS = String.join(",",
            FeatureKeys.ALL.stream().map(k -> "`" + k + "`").toList());

    private final JdbcTemplate jdbc;
    private final GaConfig config;

    /** 价格口径告警：启动时抽样比对，供看板顶部显示。 */
    private volatile String priceWarning = "";

    public GaDataRepository(JdbcTemplate jdbc, GaConfig config) {
        this.jdbc = jdbc;
        this.config = config;
    }

    public String priceWarning() { return priceWarning; }

    // ------------------------------------------------------------------ 交易日

    public LocalDate latestDate(int flag) {
        return jdbc.query("SELECT MAX(`trade_date`) FROM `stock_daily_all` WHERE `adjustflag` = ?",
                (ResultSet rs) -> rs.next() ? localDate(rs.getDate(1)) : null, flag);
    }

    /** 有行情记录的交易日（升序）；用于有标签日、折切分、回放断点。 */
    public List<LocalDate> tradeDates(int flag, LocalDate from, LocalDate to) {
        return jdbc.queryForList("SELECT DISTINCT `trade_date` FROM `stock_daily_all` "
                        + "WHERE `adjustflag` = ? AND `trade_date` BETWEEN ? AND ? ORDER BY `trade_date`",
                LocalDate.class, flag, Date.valueOf(from), Date.valueOf(to));
    }

    /** 每只股票的可交易日（tradestatus=1）序列，用于到期日精确定位。 */
    public Map<String, List<LocalDate>> tradableDatesByStock(int flag, LocalDate from, LocalDate to) {
        Map<String, List<LocalDate>> out = new HashMap<>();
        jdbc.query("SELECT `code`,`market`,`trade_date` FROM `stock_daily_all` "
                        + "WHERE `adjustflag` = ? AND `trade_date` BETWEEN ? AND ? AND `tradestatus` = 1 "
                        + "ORDER BY `code`,`market`,`trade_date`",
                (RowCallbackHandler) rs -> out.computeIfAbsent(key(rs.getString("code"), rs.getString("market")),
                        k -> new ArrayList<>()).add(localDate(rs.getDate("trade_date"))),
                flag, Date.valueOf(from), Date.valueOf(to));
        return out;
    }

    /** 基准日 → 到期日 的收盘价（回填必须用实时重读的价格，不能用 base_close）。 */
    public Map<String, Double> closeMap(int flag, LocalDate from, LocalDate to) {
        Map<String, Double> out = new HashMap<>();
        jdbc.query("SELECT `code`,`market`,`trade_date`,`close` FROM `stock_daily_all` "
                        + "WHERE `adjustflag` = ? AND `trade_date` BETWEEN ? AND ? AND `close` IS NOT NULL",
                (RowCallbackHandler) rs -> out.put(key(rs.getString("code"), rs.getString("market"))
                                + "|" + localDate(rs.getDate("trade_date")), rs.getDouble("close")),
                flag, Date.valueOf(from), Date.valueOf(to));
        return out;
    }

    // ------------------------------------------------------------------ 日线与评分

    /** 一次性按股票分组加载区间日线（时间升序），特征计算需要 252 日预热。
     *  评分表只有近期数据；有则覆盖同源指标，缺失时由 FeatureCalculator 从日线自算。 */
    public Map<String, List<Bar>> loadBarsWithScores(int flag, LocalDate from, LocalDate to) {
        Map<String, List<Bar>> out = new LinkedHashMap<>();
        jdbc.query("SELECT " + BAR_COLUMNS + " FROM `stock_daily_all` "
                        + "WHERE `adjustflag` = ? AND `trade_date` BETWEEN ? AND ? "
                        + "ORDER BY `code`,`market`,`trade_date`",
                (RowCallbackHandler) rs -> out.computeIfAbsent(key(rs.getString("code"), rs.getString("market")),
                        k -> new ArrayList<>()).add(readBar(rs)),
                flag, Date.valueOf(from), Date.valueOf(to));

        Map<String, double[]> scores = loadScores(flag, from, to);
        if (scores.isEmpty()) return out;
        for (Map.Entry<String, List<Bar>> e : out.entrySet()) {
            List<Bar> bars = e.getValue();
            for (int i = 0; i < bars.size(); i++) {
                Bar b = bars.get(i);
                double[] s = scores.get(b.code() + "|" + b.market() + "|" + b.date());
                if (s != null) bars.set(i, withScore(b, s));
            }
        }
        return out;
    }

    private Map<String, double[]> loadScores(int flag, LocalDate from, LocalDate to) {
        Map<String, double[]> out = new HashMap<>();
        try {
            jdbc.query("SELECT `code`,`market`,`trade_date`,`total_score`,`value_score`,`momentum_score`,"
                            + "`trend_score`,`risk_score`,`liquidity_score`,`ret_21`,`ret_63`,`ret_126`,`ret_252`,"
                            + "`vol_60`,`drawdown_252`,`amount_ratio_20`,`turnover_20` "
                            + "FROM `stock_score_daily` WHERE `adjustflag` = ? AND `trade_date` BETWEEN ? AND ?",
                    (RowCallbackHandler) rs -> {
                        double[] s = new double[GaModel.SCORE_N];
                        String[] cols = {"total_score", "value_score", "momentum_score", "trend_score", "risk_score",
                                "liquidity_score", "ret_21", "ret_63", "ret_126", "ret_252",
                                "vol_60", "drawdown_252", "amount_ratio_20", "turnover_20"};
                        for (int i = 0; i < cols.length; i++) {
                            Object v = rs.getObject(cols[i]);
                            s[i] = v == null ? Double.NaN : ((Number) v).doubleValue();
                        }
                        out.put(rs.getString("code") + "|" + rs.getString("market") + "|"
                                + localDate(rs.getDate("trade_date")), s);
                    }, flag, Date.valueOf(from), Date.valueOf(to));
        } catch (Exception ignored) {
            // 评分表不存在或列不兼容时全部走自算分支
        }
        return out;
    }

    private Bar readBar(ResultSet rs) throws SQLException {
        return new Bar(rs.getString("code"), rs.getString("market"), localDate(rs.getDate("trade_date")),
                d(rs, "open"), d(rs, "high"), d(rs, "low"), d(rs, "close"), d(rs, "preclose"),
                d(rs, "volume"), d(rs, "amount"), d(rs, "amplitude"), d(rs, "pct_chg"), d(rs, "turnover"),
                i(rs, "tradestatus"), i(rs, "is_st"),
                nullable(rs, "pe_ttm"), nullable(rs, "pb_mrq"), nullable(rs, "ps_ttm"), nullable(rs, "pcf_ncf_ttm"),
                null);
    }

    private Bar withScore(Bar b, double[] s) {
        return new Bar(b.code(), b.market(), b.date(), b.open(), b.high(), b.low(), b.close(), b.preclose(),
                b.volume(), b.amount(), b.amplitude(), b.pctChg(), b.turnover(), b.tradeStatus(), b.isSt(),
                b.peTtm(), b.pbMrq(), b.psTtm(), b.pcfNcfTtm(), s);
    }

    /** 规格 0：抽样 200 行比较两表 close，偏差超阈值写告警日志。 */
    public String checkPriceConsistency() {
        int flag = config.globalInt("adjust_flag");
        double tol = config.globalDouble("price_check_tolerance");
        int samples = config.globalInt("price_check_samples");
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT d.`code`, d.`market`, d.`trade_date`, d.`close` dc, s.`close` sc "
                            + "FROM `stock_score_daily` s JOIN `stock_daily_all` d "
                            + "ON d.`code`=s.`code` AND d.`market`=s.`market` AND d.`trade_date`=s.`trade_date` "
                            + "AND d.`adjustflag`=s.`adjustflag` WHERE s.`adjustflag`=? LIMIT ?", flag, samples);
            long bad = rows.stream().filter(m -> {
                double a = num(m.get("dc")), b = num(m.get("sc"));
                return a > 0 && Math.abs(a - b) / a > tol;
            }).count();
            priceWarning = rows.isEmpty() ? "" : (bad > rows.size() * 0.2
                    ? "价格口径可能不一致：抽样 " + rows.size() + " 行中有 " + bad + " 行两表 close 偏差超过 "
                    + (tol * 100) + "%，请检查 adjustflag 注释" : "");
            if (!priceWarning.isEmpty()) System.err.println("[GA][WARN] " + priceWarning);
            return priceWarning;
        } catch (Exception e) {
            priceWarning = "";
            return "";
        }
    }

    // ------------------------------------------------------------------ 特征缓存

    private static final String UPSERT_FEATURE_SQL = buildFeatureUpsert();

    private static String buildFeatureUpsert() {
        StringBuilder sb = new StringBuilder("INSERT INTO `stock_feature_daily` (`code`,`market`,`trade_date`,"
                + "`adjustflag`,`feature_set_ver`,");
        for (String k : FeatureKeys.ALL) sb.append('`').append(k).append("`,");
        sb.append("`valid_flag`,`limit_flag`) VALUES (?,?,?,?,?,");
        for (int i = 0; i < FeatureKeys.N; i++) sb.append("?,");
        sb.append("?,?) ON DUPLICATE KEY UPDATE `feature_set_ver`=VALUES(`feature_set_ver`),");
        for (String k : FeatureKeys.ALL) sb.append('`').append(k).append("`=VALUES(`").append(k).append("`),");
        sb.append("`valid_flag`=VALUES(`valid_flag`),`limit_flag`=VALUES(`limit_flag`)");
        return sb.toString();
    }

    /** 批量写入原始特征（幂等）。 */
    public int upsertFeatures(List<FeatureRow> rows, int flag) {
        if (rows.isEmpty()) return 0;
        int batch = Math.max(100, config.globalInt("batch_size"));
        int total = 0;
        for (int from = 0; from < rows.size(); from += batch) {
            List<FeatureRow> part = rows.subList(from, Math.min(rows.size(), from + batch));
            List<Object[]> args = new ArrayList<>(part.size());
            for (FeatureRow r : part) {
                Object[] a = new Object[5 + FeatureKeys.N + 2];
                a[0] = r.code(); a[1] = r.market(); a[2] = Date.valueOf(r.date()); a[3] = flag;
                a[4] = FeatureKeys.VERSION;
                for (int j = 0; j < FeatureKeys.N; j++) {
                    float v = j < r.values().length ? r.values()[j] : 0f;
                    a[5 + j] = Double.isFinite(v) ? (double) v : null;
                }
                a[5 + FeatureKeys.N] = r.valid() ? 1 : 0;
                a[6 + FeatureKeys.N] = r.limitFlag();
                args.add(a);
            }
            jdbc.batchUpdate(UPSERT_FEATURE_SQL, args);
            total += part.size();
        }
        return total;
    }

    /** 读回原始特征，按日期分组。 */
    public Map<LocalDate, List<FeatureRow>> loadFeatures(int flag, LocalDate from, LocalDate to) {
        Map<LocalDate, List<FeatureRow>> out = new LinkedHashMap<>();
        jdbc.query("SELECT `code`,`market`,`trade_date`,`valid_flag`,`limit_flag`," + FEATURE_COLS
                        + " FROM `stock_feature_daily` WHERE `adjustflag`=? AND `trade_date` BETWEEN ? AND ? "
                        + "ORDER BY `trade_date`,`code`",
                (RowCallbackHandler) rs -> {
                    float[] v = new float[FeatureKeys.N];
                    for (int j = 0; j < FeatureKeys.N; j++) v[j] = rs.getFloat(6 + j);
                    LocalDate d = localDate(rs.getDate("trade_date"));
                    out.computeIfAbsent(d, k -> new ArrayList<>()).add(new FeatureRow(
                            rs.getString("code"), rs.getString("market"), d, v,
                            rs.getInt("valid_flag") == 1, rs.getInt("limit_flag")));
                }, flag, Date.valueOf(from), Date.valueOf(to));
        return out;
    }

    public int countFeatureDate(LocalDate date, int flag) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM `stock_feature_daily` WHERE `trade_date`=? AND `adjustflag`=?",
                Integer.class, Date.valueOf(date), flag);
        return n == null ? 0 : n;
    }

    /** 某日某特征原始值截面，用于因子 IC 体检。 */
    public Map<String, double[]> featureCrossSection(LocalDate date, int flag, Set<String> keys) {
        if (keys.isEmpty()) return Map.of();
        String cols = String.join(",", keys.stream().map(k -> "`" + k + "`").toList());
        Map<String, double[]> out = new HashMap<>();
        jdbc.query("SELECT `code`,`market`," + cols + " FROM `stock_feature_daily` "
                        + "WHERE `trade_date`=? AND `adjustflag`=? AND `valid_flag`=1",
                (RowCallbackHandler) rs -> {
                    double[] v = new double[keys.size()];
                    int i = 0;
                    for (String k : keys) { v[i] = rs.getDouble(3 + i); if (rs.wasNull()) v[i] = Double.NaN; i++; }
                    out.put(key(rs.getString("code"), rs.getString("market")), v);
                }, Date.valueOf(date), flag);
        return out;
    }

    // ------------------------------------------------------------------ 市场状态

    public int upsertMarket(List<MarketPoint> points, int flag) {
        if (points.isEmpty()) return 0;
        List<Object[]> args = new ArrayList<>(points.size());
        for (MarketPoint p : points) {
            args.add(new Object[]{Date.valueOf(p.date()), flag, p.stockCnt(), p.ret1(), p.ret5(), p.ret20(),
                    p.vol20(), p.breadthUp(), p.avgTurnover(), p.index(), p.regime()});
        }
        jdbc.batchUpdate("INSERT INTO `ga_market_daily` (`trade_date`,`adjustflag`,`stock_cnt`,`mkt_ret_1`,"
                + "`mkt_ret_5`,`mkt_ret_20`,`mkt_vol_20`,`breadth_up`,`avg_turnover`,`mkt_index`,`regime`) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE `stock_cnt`=VALUES(`stock_cnt`),"
                + "`mkt_ret_1`=VALUES(`mkt_ret_1`),`mkt_ret_5`=VALUES(`mkt_ret_5`),"
                + "`mkt_ret_20`=VALUES(`mkt_ret_20`),`mkt_vol_20`=VALUES(`mkt_vol_20`),"
                + "`breadth_up`=VALUES(`breadth_up`),`avg_turnover`=VALUES(`avg_turnover`),"
                + "`mkt_index`=VALUES(`mkt_index`),`regime`=VALUES(`regime`)", args);
        return points.size();
    }

    public Map<LocalDate, MarketPoint> loadMarket(int flag, LocalDate from, LocalDate to) {
        Map<LocalDate, MarketPoint> out = new LinkedHashMap<>();
        try {
            jdbc.query("SELECT * FROM `ga_market_daily` WHERE `adjustflag`=? AND `trade_date` BETWEEN ? AND ? "
                            + "ORDER BY `trade_date`",
                    (RowCallbackHandler) rs -> {
                        LocalDate d = localDate(rs.getDate("trade_date"));
                        out.put(d, new MarketPoint(d, rs.getInt("stock_cnt"), d(rs, "mkt_ret_1"),
                                d(rs, "mkt_ret_5"), d(rs, "mkt_ret_20"), d(rs, "mkt_vol_20"),
                                d(rs, "breadth_up"), d(rs, "avg_turnover"), d(rs, "mkt_index"), rs.getInt("regime")));
                    }, flag, Date.valueOf(from), Date.valueOf(to));
        } catch (Exception ignored) {}
        return out;
    }

    // ------------------------------------------------------------------ 股票信息

    public Map<String, String> stockNames() {
        Map<String, String> out = new HashMap<>();
        jdbc.query("SELECT `code`,`name`,`exchange` FROM `stock_info`", (RowCallbackHandler) rs -> {
            String ex = rs.getString("exchange");
            out.put(rs.getString("code"), rs.getString("name"));
            if (ex != null) out.put(rs.getString("code") + "|" + ex, rs.getString("name"));
        });
        return out;
    }

    public static String key(String code, String market) { return code + "|" + market; }

    private static LocalDate localDate(java.sql.Date d) { return d == null ? null : d.toLocalDate(); }

    private static double d(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? Double.NaN : v;
    }

    private static int i(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? 0 : v;
    }

    private static Double nullable(ResultSet rs, String col) throws SQLException {
        Object v = rs.getObject(col);
        return v == null ? null : ((Number) v).doubleValue();
    }

    private static double num(Object o) { return o instanceof Number n ? n.doubleValue() : 0; }
}
