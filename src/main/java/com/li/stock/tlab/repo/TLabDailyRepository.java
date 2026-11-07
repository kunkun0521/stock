package com.li.stock.tlab.repo;

import com.li.stock.domain.Bar;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

/**
 * 规格第三节的数据层。
 * <p><b>表名替换说明</b>：规格里的单表 {@code stock_daily} 在本项目中是
 * {@code stock_daily_all} 视图，即 {@code stock_daily_00 / 30 / 60} 三张物理分表之和。
 * 这里沿用项目 {@code StockDailyRepository.tableForCode} 的白名单路由规则，
 * 按代码前缀直接查对应物理分表，命中 {@code idx_daily_resume} 索引，避免视图全量 UNION。</p>
 *
 * <p>完全独立：不新增 MyBatis mapper、不修改任何现有 SQL 或配置文件，仅使用项目已有的
 * {@code spring-boot-starter-jdbc} 提供的 JdbcTemplate。</p>
 */
@Repository
public class TLabDailyRepository {

    private static final String COLUMNS = "`code`, `market`, `trade_date`, `open`, `high`, `low`, `close`, "
            + "`preclose`, `volume`, `amount`, `amplitude`, `pct_chg`, `turnover`, `tradestatus`, `is_st`";

    private static final RowMapper<Bar> MAPPER = (rs, rowNum) -> new Bar(
            rs.getString("code"),
            rs.getString("market"),
            rs.getDate("trade_date").toLocalDate(),
            d(rs, "open"), d(rs, "high"), d(rs, "low"), d(rs, "close"), d(rs, "preclose"),
            d(rs, "volume"), d(rs, "amount"), d(rs, "amplitude"), d(rs, "pct_chg"),
            d(rs, "turnover"),
            i(rs, "tradestatus"), i(rs, "is_st"));

    private final JdbcTemplate jdbc;

    public TLabDailyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 区间全量（时间升序），与规格第三节的查询一致。
     *
     * @param adjustFlag 复权口径，默认 2（前复权）
     */
    public List<Bar> listAsc(String code, LocalDate from, LocalDate to, int adjustFlag) {
        String table = tableForCode(code);
        // 日期条件动态拼接，避免 MySQL 对 NULL 参数的歧义
        StringBuilder sb = new StringBuilder("SELECT ").append(COLUMNS).append(" FROM `").append(table)
                .append("` WHERE `code` = ? AND `adjustflag` = ?");
        if (from != null) sb.append(" AND `trade_date` >= ?");
        if (to != null) sb.append(" AND `trade_date` <= ?");
        sb.append(" ORDER BY `trade_date` ASC");

        Object[] args;
        if (from != null && to != null) args = new Object[]{code, adjustFlag, java.sql.Date.valueOf(from), java.sql.Date.valueOf(to)};
        else if (from != null) args = new Object[]{code, adjustFlag, java.sql.Date.valueOf(from)};
        else if (to != null) args = new Object[]{code, adjustFlag, java.sql.Date.valueOf(to)};
        else args = new Object[]{code, adjustFlag};
        return jdbc.query(sb.toString(), MAPPER, args);
    }

    /** 该代码在指定复权口径下是否有数据，用于「code 必须有数据」的校验。 */
    public boolean exists(String code, int adjustFlag) {
        String sql = "SELECT 1 FROM `" + tableForCode(code) + "` WHERE `code` = ? AND `adjustflag` = ? LIMIT 1";
        List<Integer> rows = jdbc.query(sql, (rs, n) -> 1, code, adjustFlag);
        return !rows.isEmpty();
    }

    /** 最新交易日，用于 asOfDate 默认值。 */
    public LocalDate latestDate(String code, int adjustFlag) {
        String sql = "SELECT MAX(`trade_date`) FROM `" + tableForCode(code) + "` WHERE `code` = ? AND `adjustflag` = ?";
        java.sql.Date date = jdbc.queryForObject(sql, java.sql.Date.class, code, adjustFlag);
        return date == null ? null : date.toLocalDate();
    }

    /** 股票中文名，仅用于结果展示；表缺失或缺列时返回空串。 */
    public String nameOf(String code) {
        try {
            List<String> names = jdbc.queryForList("SELECT `name` FROM `stock_info` WHERE `code` = ? LIMIT 1",
                    String.class, code);
            return names.isEmpty() || names.get(0) == null ? "" : names.get(0);
        } catch (Exception ex) {
            return "";
        }
    }

    /**
     * 按 A 股代码前缀路由到唯一物理分表。返回值只来自白名单，
     * 与项目现有 StockDailyRepository 的规则完全一致（00 / 30 / 60）。
     */
    public static String tableForCode(String code) {
        if (code != null && code.startsWith("00")) return "stock_daily_00";
        if (code != null && code.startsWith("30")) return "stock_daily_30";
        if (code != null && code.startsWith("60")) return "stock_daily_60";
        throw new IllegalArgumentException("不支持的股票代码分表前缀：" + code + "，仅支持 00、30、60");
    }

    private static double d(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? 0d : v;
    }

    private static int i(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? 0 : v;
    }
}
