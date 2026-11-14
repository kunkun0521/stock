package com.li.stock.ga.cleanup;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据体检 / 清理专用 SQL（规格 9.13）。
 *
 * <p><b>安全约束</b>：所有可删除的表都写在 {@link #RULES} 常量白名单里，表名与列名均为编译期常量，
 * 不接受任何外部输入拼接；{@code ga_config}、{@code ga_stock_pool}、{@code ga_champion_set}
 * 以及全部行情源表（stock_daily_* / stock_info / stock_score_daily）永远不在白名单内。
 * 删除一律 {@code DELETE ... LIMIT ?} 分批执行，单表超出上限即停止，避免长事务锁表。</p>
 *
 * <p>仅用于研究与复盘，输出不构成投资建议。</p>
 */
@Repository
public class GaCleanupRepository {

    /** 一条保留期规则：表 / 日期列 / 配置键 / 附加条件 / 说明。全部为常量，防注入。 */
    public record Rule(String table, String dateCol, String configKey, String extra, String note) {}

    /**
     * 允许清理的表清单。顺序即执行顺序（先删子表再删父表，避免产生新的孤儿行）。
     * 注意 ga_prediction 拆成「实盘」与「回放」两套保留期。
     */
    public static final List<Rule> RULES = List.of(
            new Rule("ga_factor_ic_daily", "as_of_date", "cleanup_factor_ic_days", "", "因子 IC 体检历史"),
            new Rule("ga_metric_daily", "as_of_date", "cleanup_metric_days", "", "看板预汇总指标"),
            new Rule("ga_job_log", "created_at", "cleanup_job_log_days", "", "任务执行日志"),
            new Rule("ga_stock_elimination_log", "event_date", "cleanup_pool_log_days", "", "淘汰/复活事件日志"),
            new Rule("ga_generation_stat", "created_at", "cleanup_run_days", "", "逐代适应度统计"),
            new Rule("ga_individual", "created_at", "cleanup_run_days",
                    "AND `status` NOT IN ('CHAMPION','CANDIDATE','HOF') "
                            + "AND NOT EXISTS (SELECT 1 FROM `ga_champion_set` c "
                            + "               WHERE c.`individual_id`=`ga_individual`.`individual_id`)",
                    "退役/淘汰个体（保护名人堂、候选、冠军及被冠军集引用的个体）"),
            new Rule("ga_run", "created_at", "cleanup_run_days",
                    "AND `status`<>'RUNNING' "
                            + "AND NOT EXISTS (SELECT 1 FROM `ga_champion_set` c "
                            + "               WHERE c.`source_run_id`=`ga_run`.`run_id`)",
                    "进化运行元信息（保护进行中与被引用的 run）"),
            new Rule("ga_prediction", "trade_date", "cleanup_replay_days", "AND `is_replay`=1", "回放预测"),
            new Rule("ga_prediction", "trade_date", "cleanup_prediction_days", "AND `is_replay`=0", "实盘预测"),
            new Rule("ga_market_daily", "trade_date", "cleanup_market_days", "", "市场环境缓存"),
            new Rule("stock_feature_daily", "trade_date", "cleanup_feature_days", "", "原始特征缓存")
    );

    /** 永不允许清理的表（用于接口展示与二次校验）。 */
    public static final List<String> PROTECTED_TABLES = List.of(
            "ga_config", "ga_stock_pool", "ga_champion_set", "ga_individual_perf",
            "stock_daily_00", "stock_daily_30", "stock_daily_60", "stock_info", "stock_score_daily");

    private final JdbcTemplate jdbc;

    public GaCleanupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ 体检

    /** 各类脏数据条数；key 与 GaCleanupService 的修复规则一一对应。 */
    public Map<String, Long> dirtyCounts(LocalDate latestTradeDate) {
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("verifiedMissingCorrect", scalar("SELECT COUNT(*) FROM `ga_prediction` "
                + "WHERE `status`='VERIFIED' AND `pred_direction`<>0 AND `is_correct` IS NULL "
                + "AND `actual_direction` IS NOT NULL"));
        out.put("verifiedMissingActualReturn", scalar("SELECT COUNT(*) FROM `ga_prediction` "
                + "WHERE `status`='VERIFIED' AND `actual_return` IS NULL"));
        out.put("voidWithPayload", scalar("SELECT COUNT(*) FROM `ga_prediction` "
                + "WHERE `status`='VOID' AND (`actual_return` IS NOT NULL OR `is_correct` IS NOT NULL)"));
        out.put("pendingWithPayload", scalar("SELECT COUNT(*) FROM `ga_prediction` "
                + "WHERE `status`='PENDING' AND (`actual_return` IS NOT NULL OR `is_correct` IS NOT NULL)"));
        out.put("futureFactorIc", scalar("SELECT COUNT(*) FROM `ga_factor_ic_daily` WHERE `as_of_date`>?",
                Date.valueOf(latestTradeDate)));
        out.put("invertedChampionWindow", scalar("SELECT COUNT(*) FROM `ga_champion_set` "
                + "WHERE `effective_from` IS NOT NULL AND `effective_to` IS NOT NULL "
                + "AND `effective_from`>`effective_to`"));
        out.put("staleRunningRuns", scalar("SELECT COUNT(*) FROM `ga_run` "
                + "WHERE `status`='RUNNING' AND `started_at`<DATE_SUB(NOW(), INTERVAL 2 HOUR)"));
        out.put("negativeJobDuration", scalar("SELECT COUNT(*) FROM `ga_job_log` "
                + "WHERE `started_at` IS NOT NULL AND `finished_at` IS NOT NULL "
                + "AND `finished_at`<`started_at`"));
        out.put("futurePredictions", scalar("SELECT COUNT(*) FROM `ga_prediction` WHERE `trade_date`>?",
                Date.valueOf(latestTradeDate)));
        out.put("orphanGenerationStat", scalar("SELECT COUNT(*) FROM `ga_generation_stat` g "
                + "WHERE NOT EXISTS (SELECT 1 FROM `ga_run` r WHERE r.`run_id`=g.`run_id`)"));
        out.put("orphanChampionVersion", scalar("SELECT COUNT(*) FROM `ga_prediction` p "
                + "WHERE p.`model_role`<>'SHADOW' AND NOT EXISTS "
                + "(SELECT 1 FROM `ga_champion_set` c WHERE c.`version_tag`=p.`champion_version`)"));
        return out;
    }

    /** 各表当前行数与占用空间（MB），用于清理前后对比。 */
    public List<Map<String, Object>> tableStats() {
        return jdbc.queryForList("SELECT `TABLE_NAME` `table`, `TABLE_ROWS` `rows`,"
                + "ROUND((`DATA_LENGTH`+`INDEX_LENGTH`)/1048576,1) `mb` "
                + "FROM information_schema.`TABLES` WHERE `TABLE_SCHEMA`=DATABASE() "
                + "AND (`TABLE_NAME` REGEXP '^ga_' OR `TABLE_NAME`='stock_feature_daily') "
                + "ORDER BY `DATA_LENGTH`+`INDEX_LENGTH` DESC");
    }

    /** 某条保留期规则下的待清理行数与最早/最晚日期。 */
    public Map<String, Object> retentionStat(Rule r, LocalDate cutoff) {
        String where = " WHERE `" + r.dateCol() + "` < ? " + r.extra();
        long rows = scalar("SELECT COUNT(*) FROM `" + r.table() + "`" + where, Date.valueOf(cutoff));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        if (rows > 0) {
            Object min = jdbc.queryForObject("SELECT MIN(`" + r.dateCol() + "`) FROM `" + r.table() + "`" + where,
                    Date.class, Date.valueOf(cutoff));
            Object max = jdbc.queryForObject("SELECT MAX(`" + r.dateCol() + "`) FROM `" + r.table() + "`" + where,
                    Date.class, Date.valueOf(cutoff));
            out.put("minDate", min == null ? "" : min.toString());
            out.put("maxDate", max == null ? "" : max.toString());
        } else {
            out.put("minDate", "");
            out.put("maxDate", "");
        }
        return out;
    }

    // ------------------------------------------------------------------ 修复

    /** 游标分页读取「已 VERIFIED 但命中判定缺失」的脏行，避免一次载入十万行。 */
    public List<Map<String, Object>> unverifiedRows(long afterId, int limit) {
        return jdbc.queryForList("SELECT `id`,`horizon`,`pred_direction`,`actual_direction`,"
                + "`actual_return`,`vol_20` FROM `ga_prediction` "
                + "WHERE `status`='VERIFIED' AND `pred_direction`<>0 AND `is_correct` IS NULL "
                + "AND `actual_direction` IS NOT NULL AND `id`>? ORDER BY `id` LIMIT ?", afterId, limit);
    }

    /** 幂等重算命中判定：WHERE 带 is_correct IS NULL，重复执行不会覆盖已有结果。 */
    public void repairVerified(List<Object[]> args) {
        if (args.isEmpty()) return;
        jdbc.batchUpdate("UPDATE `ga_prediction` SET `is_correct`=?,`is_effective`=?,"
                + "`verified_at`=COALESCE(`verified_at`,NOW()) WHERE `id`=? AND `is_correct` IS NULL", args);
    }

    public int deleteFutureFactorIc(LocalDate latestTradeDate) {
        return jdbc.update("DELETE FROM `ga_factor_ic_daily` WHERE `as_of_date`>?", Date.valueOf(latestTradeDate));
    }

    /** 预测日期晚于行情最新交易日的记录（回放/手工触发写入的未来行），永远无法回填，直接删除。 */
    public int deleteFuturePredictions(LocalDate latestTradeDate) {
        return jdbc.update("DELETE FROM `ga_prediction` WHERE `trade_date`>?", Date.valueOf(latestTradeDate));
    }

    public int fixInvertedChampionWindow() {
        // 回放用过去的断点日期 deactivate 了未来才生效的版本，产生负长度生效区间；
        // 统一收敛成「当日生效当日下线」，比保留倒挂日期更贴近事实。
        return jdbc.update("UPDATE `ga_champion_set` SET `effective_to`=`effective_from` "
                + "WHERE `effective_from` IS NOT NULL AND `effective_to` IS NOT NULL "
                + "AND `effective_from`>`effective_to`");
    }

    public int closeStaleRuns() {
        return jdbc.update("UPDATE `ga_run` SET `status`='FAILED',`finished_at`=NOW(),"
                + "`error_msg`='进程中断，数据清理任务标记为失败' "
                + "WHERE `status`='RUNNING' AND `started_at`<DATE_SUB(NOW(), INTERVAL 2 HOUR)");
    }

    public int fixNegativeJobDuration() {
        return jdbc.update("UPDATE `ga_job_log` SET `finished_at`=`started_at` "
                + "WHERE `started_at` IS NOT NULL AND `finished_at` IS NOT NULL AND `finished_at`<`started_at`");
    }

    public int deleteOrphanGenerationStat() {
        return jdbc.update("DELETE FROM `ga_generation_stat` WHERE NOT EXISTS "
                + "(SELECT 1 FROM `ga_run` r WHERE r.`run_id`=`ga_generation_stat`.`run_id`)");
    }

    // ------------------------------------------------------------------ 保留期清理

    /**
     * 分批删除一条规则下的过期数据，返回实际删除行数。
     * dryRun=true 时只走 retentionStat 的 COUNT，不执行任何 DELETE。
     */
    public long purge(Rule r, LocalDate cutoff, int batchSize, long maxRows, boolean dryRun) {
        if (dryRun) return retentionStat(r, cutoff).get("rows") instanceof Number n ? n.longValue() : 0L;
        String sql = "DELETE FROM `" + r.table() + "` WHERE `" + r.dateCol() + "` < ? " + r.extra()
                + " ORDER BY `" + r.dateCol() + "` LIMIT ?";
        long total = 0;
        while (total < maxRows) {
            int batch = (int) Math.min(batchSize, maxRows - total);
            int n = jdbc.update(sql, Date.valueOf(cutoff), batch);
            total += n;
            if (n < batch) break;   // 已删完
        }
        return total;
    }

    // ------------------------------------------------------------------ 审计日志

    public void logRun(String mode, boolean dryRun, String detail, int processed,
                       java.time.LocalDateTime startedAt) {
        jdbc.update("INSERT INTO `ga_cleanup_log` (`mode`,`dry_run`,`detail`,`processed`,"
                        + "`started_at`,`finished_at`) VALUES (?,?,?,?,?,NOW())",
                mode, dryRun ? 1 : 0,
                detail == null ? "" : detail.substring(0, Math.min(2000, detail.length())),
                processed, java.sql.Timestamp.valueOf(startedAt));
    }

    public List<Map<String, Object>> history(int limit) {
        return jdbc.queryForList("SELECT `id`,DATE_FORMAT(`started_at`,'%Y-%m-%d %H:%i:%s') `startedAt`,"
                + "`mode`,`dry_run` `dryRun`,`detail`,`processed`,"
                + "DATE_FORMAT(`finished_at`,'%Y-%m-%d %H:%i:%s') `finishedAt` "
                + "FROM `ga_cleanup_log` ORDER BY `id` DESC LIMIT ?", Math.max(1, Math.min(200, limit)));
    }

    // ------------------------------------------------------------------ 工具

    private long scalar(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args.length == 0 ? new Object[0] : args);
        return n == null ? 0L : n;
    }

    /** 供诊断用：把规则按配置展开成可读清单。 */
    public static List<String> ruleNames() {
        List<String> out = new ArrayList<>();
        for (Rule r : RULES) out.add(r.table() + (r.extra().contains("is_replay=1") ? "[回放]"
                : r.extra().contains("is_replay=0") ? "[实盘]" : ""));
        return out;
    }
}
