package com.li.stock.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.li.stock.domain.DailyPoint;
import com.li.stock.domain.Params;
import com.li.stock.domain.TradeRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规格第十节 t_sim_task / t_sim_daily / t_sim_trade / t_scan_result 的读写。
 * <p>DDL 由独立的 {@code t_lab_schema.sql} 提供，<b>不加入</b> application.yaml 的
 * schema-locations，也不修改任何现有初始化脚本；表尚未创建时全部写操作静默降级并追加
 * warning，接口照常返回结果，绝不抛 500（符合「数据不足返回明确 warnings」）。</p>
 */
@Repository
public class TLabTaskRepository {

    private static final Logger log = LoggerFactory.getLogger(TLabTaskRepository.class);

    private final JdbcTemplate jdbc;
    /** 项目使用 spring-boot-starter-webmvc，容器未注册 ObjectMapper bean；与 CrawlTaskService 一致自行持有。 */
    private final ObjectMapper json = new ObjectMapper();

    public TLabTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 创建任务并返回自增 id；表缺失时返回 null。
     */
    public Long createTask(String mode, String code, String market, LocalDate buyDate,
                           Double buyPrice, Integer initQty, BigDecimal totalCapital,
                           BigDecimal availableCash, Params params, Object summary) {
        try {
            String paramsJson = json.writeValueAsString(jsonSafe(params.toMap()));
            String summaryJson = summary == null ? null : json.writeValueAsString(jsonSafe(summary));
            KeyHolder keys = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO `t_sim_task` (`mode`,`code`,`market`,`buy_date`,`buy_price`,`init_qty`,"
                                + "`total_capital`,`available_cash`,`params_json`,`summary_json`)"
                                + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, mode);
                ps.setString(2, code);
                ps.setString(3, market);
                ps.setDate(4, date(buyDate));
                if (buyPrice == null) ps.setNull(5, java.sql.Types.DECIMAL);
                else ps.setBigDecimal(5, BigDecimal.valueOf(buyPrice).setScale(4, java.math.RoundingMode.HALF_UP));
                if (initQty == null) ps.setNull(6, java.sql.Types.INTEGER);
                else ps.setInt(6, initQty);
                ps.setBigDecimal(7, totalCapital);
                ps.setBigDecimal(8, availableCash);
                ps.setString(9, paramsJson);
                ps.setString(10, summaryJson);
                return ps;
            }, keys);
            Number key = keys.getKey();
            return key == null ? null : key.longValue();
        } catch (Exception ex) {
            log.warn("t_sim_* 表未就绪，历史任务不落库：{}", ex.getMessage());
            return null;
        }
    }

    /**
     * 项目未注册 Jackson JavaTimeModule，而 summary / indicators 里含 LocalDate。
     * 递归转成 ISO 字符串，避免新增依赖，同时保证 JSON 列写入成功。
     */
    private static Object jsonSafe(Object value) {
        if (value == null) return null;
        if (value instanceof Temporal t) return t.toString();
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), jsonSafe(v)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object v : list) out.add(jsonSafe(v));
            return out;
        }
        return value;
    }

    public void saveDaily(long taskId, List<DailyPoint> rows) {
        if (rows == null || rows.isEmpty()) return;
        try {
            jdbc.batchUpdate("INSERT INTO `t_sim_daily` (`task_id`,`trade_date`,`close`,`qty`,`sellable_qty`,"
                            + "`cash`,`equity`,`diluted_cost`,`total_pnl`,`events`) VALUES (?,?,?,?,?,?,?,?,?,?) "
                            + "ON DUPLICATE KEY UPDATE `close`=VALUES(`close`),`qty`=VALUES(`qty`)",
                    rows, 200, (ps, row) -> {
                        ps.setLong(1, taskId);
                        ps.setDate(2, date(row.tradeDate()));
                        ps.setBigDecimal(3, dec(row.close(), 4));
                        ps.setInt(4, row.qty());
                        ps.setInt(5, row.sellableQty());
                        ps.setBigDecimal(6, dec(row.cash(), 2));
                        ps.setBigDecimal(7, dec(row.equity(), 2));
                        ps.setBigDecimal(8, dec(row.dilutedCost(), 4));
                        ps.setBigDecimal(9, dec(row.totalPnl(), 2));
                        ps.setString(10, row.events());
                    });
        } catch (Exception ex) {
            log.warn("t_sim_daily 写入失败：{}", ex.getMessage());
        }
    }

    public void saveTrades(long taskId, List<TradeRecord> rows) {
        if (rows == null || rows.isEmpty()) return;
        try {
            jdbc.batchUpdate("INSERT INTO `t_sim_trade` (`task_id`,`trade_date`,`action`,`price`,`qty`,"
                            + "`amount`,`fee`,`realized_pnl`,`note`) VALUES (?,?,?,?,?,?,?,?,?)",
                    rows, 200, (ps, row) -> {
                        ps.setLong(1, taskId);
                        ps.setDate(2, date(row.tradeDate()));
                        ps.setString(3, row.action());
                        ps.setBigDecimal(4, dec(row.price(), 4));
                        ps.setInt(5, row.qty());
                        ps.setBigDecimal(6, dec(row.amount(), 2));
                        ps.setBigDecimal(7, dec(row.fee(), 2));
                        if (row.realizedPnl() == null) ps.setNull(8, java.sql.Types.DECIMAL);
                        else ps.setBigDecimal(8, dec(row.realizedPnl(), 2));
                        ps.setString(9, row.note());
                    });
        } catch (Exception ex) {
            log.warn("t_sim_trade 写入失败：{}", ex.getMessage());
        }
    }

    /** 扫描结果批量落库。 */
    public void saveScanRows(long taskId, List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return;
        try {
            jdbc.batchUpdate("INSERT INTO `t_scan_result` (`task_id`,`buy_date`,`final_pnl_pct`,`buy_hold_pct`,"
                            + "`only_add_pct`,`max_drawdown`,`recover_days`,`t_count`,`t_net_profit`,`features_json`) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE `final_pnl_pct`=VALUES(`final_pnl_pct`)",
                    rows, 200, (ps, row) -> {
                        ps.setLong(1, taskId);
                        ps.setDate(2, date((LocalDate) row.get("buyDate")));
                        ps.setBigDecimal(3, big(row.get("finalPnlPct")));
                        ps.setBigDecimal(4, big(row.get("buyHoldPct")));
                        ps.setBigDecimal(5, big(row.get("onlyAddPct")));
                        ps.setBigDecimal(6, big(row.get("maxDrawdown")));
                        Object recover = row.get("recoverDays");
                        if (recover == null) ps.setNull(7, java.sql.Types.INTEGER);
                        else ps.setInt(7, ((Number) recover).intValue());
                        ps.setInt(8, ((Number) row.getOrDefault("tCount", 0)).intValue());
                        ps.setBigDecimal(9, big(row.get("tNetProfit")));
                        try {
                            ps.setString(10, json.writeValueAsString(jsonSafe(row.getOrDefault("features", Map.of()))));
                        } catch (JsonProcessingException e) {
                            ps.setString(10, "{}");
                        }
                    });
        } catch (Exception ex) {
            log.warn("t_scan_result 写入失败：{}", ex.getMessage());
        }
    }

    /** 回看历史任务：返回任务头 + 每日快照 + 交易明细，表缺失时返回 null。 */
    public Map<String, Object> loadTask(long id) {
        try {
            List<Map<String, Object>> tasks = jdbc.queryForList(
                    "SELECT * FROM `t_sim_task` WHERE `id` = ?", id);
            if (tasks.isEmpty()) return null;
            Map<String, Object> result = new LinkedHashMap<>(tasks.get(0));
            result.put("daily", jdbc.queryForList("SELECT * FROM `t_sim_daily` WHERE `task_id` = ? ORDER BY `trade_date`", id));
            result.put("trades", jdbc.queryForList("SELECT * FROM `t_sim_trade` WHERE `task_id` = ? ORDER BY `id`", id));
            result.put("scanRows", jdbc.queryForList("SELECT * FROM `t_scan_result` WHERE `task_id` = ? ORDER BY `buy_date`", id));
            return result;
        } catch (Exception ex) {
            log.warn("读取历史任务 {} 失败：{}", id, ex.getMessage());
            return null;
        }
    }

    private static Date date(LocalDate d) {
        return d == null ? null : Date.valueOf(d);
    }

    private static BigDecimal dec(double v, int scale) {
        if (!Double.isFinite(v)) return null;
        return BigDecimal.valueOf(v).setScale(scale, java.math.RoundingMode.HALF_UP);
    }

    private static BigDecimal big(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal b) return b;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return null;
    }
}
