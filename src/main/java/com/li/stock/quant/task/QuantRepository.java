package com.li.stock.quant.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.li.stock.common.BizException;
import com.li.stock.quant.analytics.EventRow;
import com.li.stock.quant.backtest.EquityPoint;
import com.li.stock.quant.backtest.TradeRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 量化平台持久化：任务、指标、净值、交易、事件、信号。
 *
 * <p>全部走 JdbcTemplate + 批量写入，避免 ORM 的逐行开销：一次回测约 500 条净值、
 * 数百条交易，批量插入在毫秒级完成。</p>
 */
@Repository
public class QuantRepository {

    private static final Logger log = LoggerFactory.getLogger(QuantRepository.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public QuantRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ------------------------------------------------------------------ 任务

    public long insertTask(String strategyKey, String version, String paramsJson, String configJson,
                           String universeJson, LocalDate start, LocalDate end) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO quant_backtest_task(strategy_key, strategy_version, params_json, config_json,
                                                    universe_json, start_date, end_date, status, progress, stage,
                                                    created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, '排队中', NOW())
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, strategyKey);
            ps.setString(2, version);
            ps.setString(3, paramsJson);
            ps.setString(4, configJson);
            ps.setString(5, universeJson);
            ps.setDate(6, Date.valueOf(start));
            ps.setDate(7, Date.valueOf(end));
            return ps;
        }, holder);
        Number key = holder.getKey();
        if (key == null) throw new IllegalStateException("任务创建失败");
        return key.longValue();
    }

    public void markRunning(long id) {
        jdbc.update("UPDATE quant_backtest_task SET status='RUNNING', progress=1, stage='加载行情面板', "
                + "started_at=NOW() WHERE id=?", id);
    }

    public void updateProgress(long id, int progress, String stage) {
        jdbc.update("UPDATE quant_backtest_task SET progress=?, stage=? WHERE id=?", progress, stage, id);
    }

    public void finish(long id, String status, String error, String summaryJson, long durationMs,
                       String snapshot, int progress, String stage) {
        jdbc.update("""
                UPDATE quant_backtest_task
                SET status=?, error_msg=?, summary_json=?, duration_ms=?, data_snapshot=?,
                    progress=?, stage=?, finished_at=NOW()
                WHERE id=?
                """, status, error, summaryJson, durationMs, snapshot, progress, stage, id);
    }

    /** 应用重启后，把上次残留的 RUNNING/PENDING 标记为失败，避免页面一直转圈。 */
    public int markStaleTasks() {
        return jdbc.update("UPDATE quant_backtest_task SET status='FAILED', stage='服务重启中断', "
                + "error_msg='服务重启，任务已中断，请重新提交', finished_at=NOW() "
                + "WHERE status IN ('RUNNING','PENDING')");
    }

    public Map<String, Object> getTask(long id) {
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT id, strategy_key, strategy_version, params_json, config_json, universe_json,
                       start_date, end_date, status, progress, stage, data_snapshot, error_msg,
                       summary_json, created_at, started_at, finished_at, duration_ms
                FROM quant_backtest_task WHERE id=?
                """, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("strategyKey", rs.getString("strategy_key"));
            m.put("strategyVersion", rs.getString("strategy_version"));
            m.put("params", parseJson(rs.getString("params_json")));
            m.put("config", parseJson(rs.getString("config_json")));
            m.put("universe", parseJson(rs.getString("universe_json")));
            m.put("startDate", str(rs.getDate("start_date")));
            m.put("endDate", str(rs.getDate("end_date")));
            m.put("status", rs.getString("status"));
            m.put("progress", rs.getInt("progress"));
            m.put("stage", rs.getString("stage"));
            m.put("dataSnapshot", rs.getString("data_snapshot"));
            m.put("errorMsg", rs.getString("error_msg"));
            m.put("summary", parseJson(rs.getString("summary_json")));
            m.put("createdAt", ts(rs.getTimestamp("created_at")));
            m.put("startedAt", ts(rs.getTimestamp("started_at")));
            m.put("finishedAt", ts(rs.getTimestamp("finished_at")));
            m.put("durationMs", rs.getObject("duration_ms"));
            return m;
        }, id);
        if (rows.isEmpty()) throw BizException.notFound("回测任务不存在：" + id);
        return rows.get(0);
    }

    public Map<String, Object> listTasks(int page, int size, String strategyKey) {
        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, size));
        String where = strategyKey == null || strategyKey.isBlank() ? "" : " WHERE strategy_key=?";
        List<Object> args = new ArrayList<>();
        if (!where.isEmpty()) args.add(strategyKey);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM quant_backtest_task" + where, Long.class,
                args.toArray());
        List<Object> listArgs = new ArrayList<>(args);
        listArgs.add(s);
        listArgs.add((p - 1) * s);
        List<Map<String, Object>> items = jdbc.query("""
                        SELECT id, strategy_key, start_date, end_date, status, progress, stage, error_msg,
                               summary_json, created_at, finished_at, duration_ms
                        FROM quant_backtest_task
                        """ + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("strategyKey", rs.getString("strategy_key"));
                    m.put("startDate", str(rs.getDate("start_date")));
                    m.put("endDate", str(rs.getDate("end_date")));
                    m.put("status", rs.getString("status"));
                    m.put("progress", rs.getInt("progress"));
                    m.put("stage", rs.getString("stage"));
                    m.put("errorMsg", rs.getString("error_msg"));
                    Map<String, Object> summary = parseJson(rs.getString("summary_json"));
                    m.put("summary", summary);
                    m.put("createdAt", ts(rs.getTimestamp("created_at")));
                    m.put("finishedAt", ts(rs.getTimestamp("finished_at")));
                    m.put("durationMs", rs.getObject("duration_ms"));
                    return m;
                }, listArgs.toArray());
        int pages = s == 0 ? 0 : (int) ((total + s - 1) / s);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", total);
        out.put("page", p);
        out.put("size", s);
        out.put("pages", pages);
        return out;
    }

    // ------------------------------------------------------------------ 结果

    public void saveMetrics(long taskId, String split, Map<String, Object> metrics) {
        jdbc.update("INSERT INTO quant_backtest_metric(task_id, split_type, metrics_json) VALUES (?,?,?) "
                + "ON DUPLICATE KEY UPDATE metrics_json=VALUES(metrics_json)", taskId, split, toJson(metrics));
    }

    public void saveEquity(long taskId, List<EquityPoint> points) {
        if (points.isEmpty()) return;
        jdbc.batchUpdate("""
                INSERT INTO quant_backtest_equity(task_id, trade_date, nav, cash, position_value,
                                                 benchmark_nav, drawdown, position_count)
                VALUES (?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE nav=VALUES(nav), cash=VALUES(cash),
                                        position_value=VALUES(position_value),
                                        benchmark_nav=VALUES(benchmark_nav),
                                        drawdown=VALUES(drawdown), position_count=VALUES(position_count)
                """, points, 500, (ps, p) -> {
            ps.setLong(1, taskId);
            ps.setDate(2, Date.valueOf(p.date()));
            ps.setDouble(3, p.nav());
            ps.setDouble(4, p.cash());
            ps.setDouble(5, p.positionValue());
            ps.setDouble(6, p.benchmarkNav());
            ps.setDouble(7, p.drawdown());
            ps.setInt(8, p.positionCount());
        });
    }

    public void saveTrades(long taskId, List<TradeRecord> trades) {
        if (trades.isEmpty()) return;
        jdbc.batchUpdate("""
                INSERT INTO quant_backtest_trade(task_id, seq, code, market, entry_date, entry_price,
                                                 exit_date, exit_price, shares, pnl, pnl_pct, hold_days,
                                                 exit_reason, entry_score, mae, mfe)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, trades, 500, (ps, t) -> {
            ps.setLong(1, taskId);
            ps.setInt(2, t.seq());
            ps.setString(3, t.code());
            ps.setString(4, t.market());
            ps.setDate(5, Date.valueOf(t.entryDate()));
            ps.setDouble(6, t.entryPrice());
            ps.setDate(7, t.exitDate() == null ? null : Date.valueOf(t.exitDate()));
            ps.setDouble(8, t.exitPrice());
            ps.setDouble(9, t.shares());
            ps.setDouble(10, t.pnl());
            ps.setDouble(11, t.pnlPct());
            ps.setInt(12, t.holdDays());
            ps.setString(13, t.exitReason());
            ps.setDouble(14, t.entryScore());
            ps.setDouble(15, t.mae());
            ps.setDouble(16, t.mfe());
        });
    }

    public void saveEvents(long taskId, List<EventRow> rows) {
        if (rows.isEmpty()) return;
        jdbc.batchUpdate("""
                INSERT INTO quant_event_study(task_id, horizon, split_key, cnt, mean_ret, median_ret,
                                              win_rate, avg_win, avg_loss, excess_mean, t_stat,
                                              mfe_mean, mae_mean)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE cnt=VALUES(cnt), mean_ret=VALUES(mean_ret),
                                        median_ret=VALUES(median_ret), win_rate=VALUES(win_rate),
                                        avg_win=VALUES(avg_win), avg_loss=VALUES(avg_loss),
                                        excess_mean=VALUES(excess_mean), t_stat=VALUES(t_stat),
                                        mfe_mean=VALUES(mfe_mean), mae_mean=VALUES(mae_mean)
                """, rows, 200, (ps, e) -> {
            ps.setLong(1, taskId);
            ps.setInt(2, e.horizon());
            ps.setString(3, e.splitKey());
            ps.setInt(4, e.cnt());
            ps.setDouble(5, e.meanRet());
            ps.setDouble(6, e.medianRet());
            ps.setDouble(7, e.winRate());
            ps.setDouble(8, e.avgWin());
            ps.setDouble(9, e.avgLoss());
            ps.setDouble(10, e.excessMean());
            ps.setDouble(11, e.tStat());
            ps.setDouble(12, e.mfeMean());
            ps.setDouble(13, e.maeMean());
        });
    }

    public Map<String, Object> getMetrics(long taskId, String split) {
        List<String> rows = jdbc.queryForList(
                "SELECT metrics_json FROM quant_backtest_metric WHERE task_id=? AND split_type=?",
                String.class, taskId, split);
        if (rows.isEmpty()) return Map.of();
        return parseJson(rows.get(0));
    }

    public List<Map<String, Object>> getEquity(long taskId) {
        return jdbc.query("""
                SELECT trade_date, nav, cash, position_value, benchmark_nav, drawdown, position_count
                FROM quant_backtest_equity WHERE task_id=? ORDER BY trade_date
                """, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", str(rs.getDate("trade_date")));
            m.put("nav", rs.getDouble("nav"));
            m.put("cash", rs.getDouble("cash"));
            m.put("positionValue", rs.getDouble("position_value"));
            m.put("benchmarkNav", rs.getDouble("benchmark_nav"));
            m.put("drawdown", rs.getDouble("drawdown"));
            m.put("positionCount", rs.getInt("position_count"));
            return m;
        }, taskId);
    }

    public Map<String, Object> getTrades(long taskId, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(200, Math.max(1, size));
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM quant_backtest_trade WHERE task_id=?",
                Long.class, taskId);
        List<Map<String, Object>> items = jdbc.query("""
                SELECT seq, code, market, entry_date, entry_price, exit_date, exit_price, shares, pnl,
                       pnl_pct, hold_days, exit_reason, entry_score, mae, mfe
                FROM quant_backtest_trade WHERE task_id=? ORDER BY seq LIMIT ? OFFSET ?
                """, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", rs.getInt("seq"));
            m.put("code", rs.getString("code"));
            m.put("market", rs.getString("market"));
            m.put("entryDate", str(rs.getDate("entry_date")));
            m.put("entryPrice", rs.getDouble("entry_price"));
            m.put("exitDate", str(rs.getDate("exit_date")));
            m.put("exitPrice", rs.getDouble("exit_price"));
            m.put("shares", rs.getDouble("shares"));
            m.put("pnl", rs.getDouble("pnl"));
            m.put("pnlPct", rs.getDouble("pnl_pct"));
            m.put("holdDays", rs.getInt("hold_days"));
            m.put("exitReason", rs.getString("exit_reason"));
            m.put("entryScore", rs.getDouble("entry_score"));
            m.put("mae", rs.getDouble("mae"));
            m.put("mfe", rs.getDouble("mfe"));
            return m;
        }, taskId, s, (p - 1) * s);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", total);
        out.put("page", p);
        out.put("size", s);
        out.put("pages", s == 0 ? 0 : (int) (((total == null ? 0 : total) + s - 1) / s));
        return out;
    }

    public List<Map<String, Object>> getEvents(long taskId) {
        return jdbc.query("""
                SELECT horizon, split_key, cnt, mean_ret, median_ret, win_rate, avg_win, avg_loss,
                       excess_mean, t_stat, mfe_mean, mae_mean
                FROM quant_event_study WHERE task_id=? ORDER BY horizon, split_key
                """, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("horizon", rs.getInt("horizon"));
            m.put("splitKey", rs.getString("split_key"));
            m.put("cnt", rs.getInt("cnt"));
            m.put("meanRet", rs.getDouble("mean_ret"));
            m.put("medianRet", rs.getDouble("median_ret"));
            m.put("winRate", rs.getDouble("win_rate"));
            m.put("avgWin", rs.getDouble("avg_win"));
            m.put("avgLoss", rs.getDouble("avg_loss"));
            m.put("excessMean", rs.getDouble("excess_mean"));
            m.put("tStat", rs.getDouble("t_stat"));
            m.put("mfeMean", rs.getDouble("mfe_mean"));
            m.put("maeMean", rs.getDouble("mae_mean"));
            return m;
        }, taskId);
    }

    // ------------------------------------------------------------------ 信号

    public void saveSignals(LocalDate scanDate, String strategyKey, String paramsHash,
                            List<Map<String, Object>> items) {
        if (items.isEmpty()) return;
        jdbc.batchUpdate("""
                INSERT INTO quant_signal_daily(scan_date, strategy_key, params_hash, code, market,
                                              signal_type, score, detail_json, stop_price, close_price,
                                              ref_json, created_at)
                VALUES (?,?,?,?,?,? ,?,?,?,?,?, NOW())
                ON DUPLICATE KEY UPDATE score=VALUES(score), detail_json=VALUES(detail_json),
                                        stop_price=VALUES(stop_price), close_price=VALUES(close_price),
                                        ref_json=VALUES(ref_json), created_at=NOW()
                """, items, 200, (ps, m) -> {
            ps.setDate(1, Date.valueOf(scanDate));
            ps.setString(2, strategyKey);
            ps.setString(3, paramsHash);
            ps.setString(4, String.valueOf(m.get("code")));
            ps.setString(5, String.valueOf(m.get("market")));
            ps.setString(6, "BUY");
            ps.setDouble(7, toDouble(m.get("score")));
            ps.setString(8, toJson(m.get("detail")));
            ps.setDouble(9, toDouble(m.get("stopPrice")));
            ps.setDouble(10, toDouble(m.get("closePrice")));
            ps.setString(11, toJson(m.get("ref")));
        });
    }

    public List<Map<String, Object>> querySignals(LocalDate date, String strategyKey) {
        String sql = """
                SELECT scan_date, strategy_key, code, market, score, detail_json, stop_price, close_price,
                       ref_json, created_at
                FROM quant_signal_daily
                WHERE scan_date = ? """ + (strategyKey == null || strategyKey.isBlank() ? "" : " AND strategy_key = ? ")
                + " ORDER BY strategy_key, score DESC";
        Object[] args = strategyKey == null || strategyKey.isBlank()
                ? new Object[]{Date.valueOf(date)} : new Object[]{Date.valueOf(date), strategyKey};
        return jdbc.query(sql, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scanDate", str(rs.getDate("scan_date")));
            m.put("strategyKey", rs.getString("strategy_key"));
            m.put("code", rs.getString("code"));
            m.put("market", rs.getString("market"));
            m.put("score", rs.getDouble("score"));
            m.put("detail", parseJson(rs.getString("detail_json")));
            m.put("stopPrice", rs.getDouble("stop_price"));
            m.put("closePrice", rs.getDouble("close_price"));
            m.put("ref", parseJson(rs.getString("ref_json")));
            m.put("createdAt", ts(rs.getTimestamp("created_at")));
            return m;
        }, args);
    }

    public List<Map<String, Object>> latestBacktests() {
        return jdbc.query("""
                SELECT t.id, t.strategy_key, t.status, t.created_at, t.summary_json
                FROM quant_backtest_task t
                JOIN (SELECT strategy_key, MAX(id) mid FROM quant_backtest_task GROUP BY strategy_key) x
                  ON x.mid = t.id
                """, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("strategyKey", rs.getString("strategy_key"));
            m.put("status", rs.getString("status"));
            m.put("createdAt", ts(rs.getTimestamp("created_at")));
            m.put("summary", parseJson(rs.getString("summary_json")));
            return m;
        });
    }

    // ------------------------------------------------------------------ 工具

    public String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            log.warn("JSON 序列化失败：{}", e.getMessage());
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> parseJson(String s) {
        if (s == null || s.isBlank()) return new LinkedHashMap<>();
        try {
            Object o = json.readValue(s, Object.class);
            return o instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private static String str(Date d) {
        return d == null ? null : d.toLocalDate().toString();
    }

    private static String ts(Timestamp t) {
        return t == null ? null : t.toLocalDateTime().format(TS);
    }

    private static double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        try {
            return v == null ? 0 : Double.parseDouble(String.valueOf(v));
        } catch (Exception e) {
            return 0;
        }
    }
}
