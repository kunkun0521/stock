package com.li.stock.ga.repository;

import com.li.stock.ga.GaModel.Champion;
import com.li.stock.ga.GaModel.Individual;
import com.li.stock.ga.GaModel.Fitness;
import com.li.stock.ga.config.GaConfig;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * GA 业务表读写（规格 8.4—8.13）。
 *
 * <p>统一返回 camelCase 的 Map，直接对应前端契约；写入全部幂等，
 * 预测覆盖只删 PENDING 行再插入，已 VERIFIED 的记录永不改写（规格 9.1.8）。</p>
 */
@Repository
public class GaStoreRepository {

    private final JdbcTemplate jdbc;
    private final GaConfig config;

    public GaStoreRepository(JdbcTemplate jdbc, GaConfig config) {
        this.jdbc = jdbc;
        this.config = config;
    }

    // ------------------------------------------------------------------ ga_run

    public long insertRun(int horizon, String runType, LocalDate asOf, long seed, int popSize) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("INSERT INTO `ga_run` "
                            + "(`horizon`,`run_type`,`as_of_date`,`random_seed`,`pop_size`,`status`,`started_at`) "
                            + "VALUES (?,?,?,?,?,'RUNNING',NOW())", Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, horizon); ps.setString(2, runType); ps.setDate(3, Date.valueOf(asOf));
            ps.setLong(4, seed); ps.setInt(5, popSize);
            return ps;
        }, kh);
        return Objects.requireNonNull(kh.getKey()).longValue();
    }

    public void finishRun(long runId, String status, int generations, double bestFitness, long bestIndividualId,
                          LocalDate trainStart, LocalDate trainEnd, LocalDate validStart, LocalDate validEnd,
                          LocalDate holdoutStart, LocalDate holdoutEnd, String featureJson, String paramsJson,
                          String warnMsg, String errorMsg) {
        jdbc.update("UPDATE `ga_run` SET `status`=?,`generations_done`=?,`best_fitness`=?,`best_individual_id`=?,"
                        + "`train_start`=?,`train_end`=?,`valid_start`=?,`valid_end`=?,"
                        + "`holdout_start`=?,`holdout_end`=?,`feature_set_json`=?,`params_json`=?,"
                        + "`warn_msg`=?,`error_msg`=?,`finished_at`=NOW() WHERE `run_id`=?",
                status, generations, bestFitness, bestIndividualId > 0 ? bestIndividualId : null,
                toDate(trainStart), toDate(trainEnd), toDate(validStart), toDate(validEnd),
                toDate(holdoutStart), toDate(holdoutEnd), featureJson, paramsJson, warnMsg, errorMsg, runId);
    }

    public Map<String, Object> listRuns(Integer horizon, String runType, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (horizon != null) { where.append(" AND `horizon`=?"); args.add(horizon); }
        if (runType != null && !runType.isBlank()) { where.append(" AND `run_type`=?"); args.add(runType); }
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM `ga_run`" + where, Integer.class, args.toArray());
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM `ga_run`" + where
                + " ORDER BY `run_id` DESC LIMIT ? OFFSET ?", append(args, size, (page - 1) * size).toArray())
                .stream().map(this::camel).toList();
        return pageOf(rows, total == null ? 0 : total, page, size);
    }

    public boolean replayDone(LocalDate d, int h) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM `ga_run` WHERE `run_type`='REPLAY' "
                + "AND `horizon`=? AND `as_of_date`=? AND `status`='SUCCESS'", Integer.class, h, Date.valueOf(d));
        return n != null && n > 0;
    }

    // ------------------------------------------------------------------ ga_generation_stat

    public void insertGenerations(long runId, List<Object[]> args) {
        if (args.isEmpty()) return;
        jdbc.batchUpdate("INSERT INTO `ga_generation_stat` (`run_id`,`generation`,`best_fitness`,`avg_fitness`,"
                + "`median_fitness`,`worst_fitness`,`diversity`,`culled_count`,`immigrants_count`,"
                + "`mutation_rate_w`,`best_balacc_valid`,`best_ic_valid`) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE `best_fitness`=VALUES(`best_fitness`)", args);
    }

    public List<Map<String, Object>> generations(long runId) {
        return jdbc.queryForList("SELECT `generation`,`best_fitness`,`avg_fitness`,`median_fitness`,"
                + "`worst_fitness`,`diversity`,`culled_count`,`immigrants_count`,`mutation_rate_w` "
                + "FROM `ga_generation_stat` WHERE `run_id`=? ORDER BY `generation`", runId)
                .stream().map(this::camel).toList();
    }

    // ------------------------------------------------------------------ ga_individual

    public long insertIndividual(long runId, int horizon, Individual ind, String status, double balaccHoldout,
                                 double icHoldout) {
        KeyHolder kh = new GeneratedKeyHolder();
        Fitness f = ind.fitness;
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("INSERT INTO `ga_individual` (`run_id`,`horizon`,"
                            + "`generation`,`parent_ids`,`chromosome_json`,`n_active_features`,`fitness`,"
                            + "`fit_train`,`fit_valid`,`balacc_valid`,`ic_valid`,`coverage_valid`,`brier_valid`,"
                            + "`balacc_holdout`,`ic_holdout`,`status`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, runId); ps.setInt(2, horizon); ps.setInt(3, ind.generation);
            ps.setString(4, ind.parentIds); ps.setString(5, ind.chromosomeJson()); ps.setInt(6, ind.active());
            ps.setDouble(7, f.f()); ps.setDouble(8, f.fTrain()); ps.setDouble(9, f.fValid());
            ps.setDouble(10, f.balaccValid()); ps.setDouble(11, f.icValid());
            ps.setDouble(12, f.coverageValid()); ps.setDouble(13, f.brierValid());
            ps.setObject(14, balaccHoldout > 0 ? balaccHoldout : null);
            ps.setObject(15, icHoldout != 0 ? icHoldout : null);
            ps.setString(16, status);
            return ps;
        }, kh);
        return Objects.requireNonNull(kh.getKey()).longValue();
    }

    public Map<String, Object> runById(long runId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM `ga_run` WHERE `run_id`=?", runId)
                .stream().map(this::camel).toList();
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    public List<Map<String, Object>> individuals(Long runId, Integer horizon, String status) {
        StringBuilder w = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (runId != null) { w.append(" AND `run_id`=?"); args.add(runId); }
        if (horizon != null) { w.append(" AND `horizon`=?"); args.add(horizon); }
        if (status != null && !status.isBlank()) { w.append(" AND `status`=?"); args.add(status); }
        return jdbc.queryForList("SELECT `individual_id`,`run_id`,`horizon`,`generation`,`fitness`,`fit_train`,"
                + "`fit_valid`,`balacc_valid`,`ic_valid`,`coverage_valid`,`brier_valid`,`status`,"
                + "`retire_reason`,`n_active_features`,`chromosome_json` FROM `ga_individual`" + w
                + " ORDER BY `fitness` DESC LIMIT 500", args.toArray()).stream().map(this::camel).toList();
    }

    public void setIndividualStatus(long id, String status, String retireReason) {
        jdbc.update("UPDATE `ga_individual` SET `status`=?,`retire_reason`=?,"
                + (("CHAMPION".equals(status)) ? "`promoted_at`=NOW()," : "")
                + (("RETIRED".equals(status)) ? "`retired_at`=NOW()," : "")
                + "`individual_id`=`individual_id` WHERE `individual_id`=?", status, retireReason, id);
    }

    public List<Map<String, Object>> hof(int horizon, int limit) {
        return jdbc.queryForList("SELECT * FROM `ga_individual` WHERE `horizon`=? AND `status` IN ('HOF','CANDIDATE','CHAMPION') "
                + "ORDER BY `fitness` DESC LIMIT ?", horizon, limit).stream().map(this::camel).toList();
    }

    public List<Map<String, Object>> candidateIndividuals(int horizon) {
        return jdbc.queryForList("SELECT * FROM `ga_individual` WHERE `horizon`=? AND `status`='CANDIDATE' "
                + "ORDER BY `fitness` DESC", horizon).stream().map(this::camel).toList();
    }

    // ------------------------------------------------------------------ ga_champion_set

    public List<Map<String, Object>> currentChampions(int horizon) {
        return jdbc.queryForList("SELECT c.*, i.`chromosome_json`, i.`fitness` FROM `ga_champion_set` c "
                + "LEFT JOIN `ga_individual` i ON i.`individual_id`=c.`individual_id` "
                + "WHERE c.`horizon`=? AND c.`is_current`=1 ORDER BY c.`rank_no`", horizon)
                .stream().map(this::camel).toList();
    }

    /** horizon 为空时返回全部周期的版本（看板「全部周期」筛选）。 */
    public List<Map<String, Object>> championVersions(Integer horizon) {
        String where = horizon == null ? "" : " WHERE `horizon`=?";
        List<Object> args = horizon == null ? List.of() : List.<Object>of(horizon);
        return jdbc.queryForList("SELECT * FROM `ga_champion_set`" + where
                + " ORDER BY `id` DESC LIMIT 200", args.toArray()).stream().map(this::camel).toList();
    }

    public String currentVersionTag(int horizon) {
        List<String> v = jdbc.queryForList("SELECT `version_tag` FROM `ga_champion_set` "
                + "WHERE `horizon`=? AND `is_current`=1 LIMIT 1", String.class, horizon);
        return v.isEmpty() ? "" : v.get(0);
    }

    public void deactivateChampions(int horizon, LocalDate effectiveTo) {
        jdbc.update("UPDATE `ga_champion_set` SET `is_current`=0,`effective_to`=? "
                + "WHERE `horizon`=? AND `is_current`=1", Date.valueOf(effectiveTo), horizon);
    }

    public void insertChampion(int horizon, String versionTag, long individualId, int rankNo,
                               double weight, LocalDate effectiveFrom, long sourceRunId) {
        jdbc.update("INSERT INTO `ga_champion_set` (`horizon`,`version_tag`,`individual_id`,`rank_no`,`weight`,"
                        + "`is_current`,`effective_from`,`source_run_id`) VALUES (?,?,?,?,?,1,?,?)",
                horizon, versionTag, individualId, rankNo, weight, Date.valueOf(effectiveFrom), sourceRunId);
    }

    /** 回滚：把指定版本重新写成当前版本（保留全部历史，任意时刻只有一个当前版本）。 */
    public int rollback(int horizon, String versionTag, LocalDate today) {
        List<Map<String, Object>> old = jdbc.queryForList("SELECT * FROM `ga_champion_set` "
                + "WHERE `horizon`=? AND `version_tag`=? ORDER BY `rank_no`", horizon, versionTag);
        if (old.isEmpty()) return 0;
        deactivateChampions(horizon, today);
        for (Map<String, Object> m : old) {
            insertChampion(horizon, versionTag + "-R" + System.currentTimeMillis(),
                    ((Number) m.get("individual_id")).longValue(), ((Number) m.get("rank_no")).intValue(),
                    ((Number) m.get("weight")).doubleValue(), today,
                    m.get("source_run_id") == null ? 0 : ((Number) m.get("source_run_id")).longValue());
        }
        return old.size();
    }

    // ------------------------------------------------------------------ ga_prediction

    private static final String PRED_INSERT = "INSERT INTO `ga_prediction` (`trade_date`,`code`,`market`,"
            + "`adjustflag`,`horizon`,`model_role`,`is_replay`,`in_pool`,`champion_version`,`pred_direction`,"
            + "`prob_up`,`confidence`,`score_raw`,`base_close`,`limit_flag`,`is_st`,`vol_20`,`amount_ma_20`,"
            + "`pe_ttm`,`regime`,`explain_json`,`status`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PENDING')"
            // 回放重跑 / 重复触发时，该 (date,code,market,flag,horizon,role,is_replay) 可能已存在且已被
            // VerifyJob 回填成 VERIFIED/VOID。已验证记录永不改写，冲突行做无操作，避免 DuplicateKeyException。
            // 用 ON DUPLICATE KEY UPDATE 而非 INSERT IGNORE：只吞唯一键冲突，其它 SQL 错误照常抛出。
            + " ON DUPLICATE KEY UPDATE `id`=`id`";

    /** 只删 PENDING 行后插入，已验证的记录永不改写；重复触发幂等。 */
    public int replacePendingPredictions(LocalDate date, int horizon, String role, int isReplay,
                                         List<Object[]> rows) {
        jdbc.update("DELETE FROM `ga_prediction` WHERE `trade_date`=? AND `horizon`=? "
                        + "AND `model_role`=? AND `is_replay`=? AND `status`='PENDING'",
                Date.valueOf(date), horizon, role, isReplay);
        if (rows.isEmpty()) return 0;
        int batch = Math.max(100, config.globalInt("batch_size"));
        for (int from = 0; from < rows.size(); from += batch) {
            jdbc.batchUpdate(PRED_INSERT, rows.subList(from, Math.min(rows.size(), from + batch)));
        }
        return rows.size();
    }

    /**
     * 某交易日已生成回放预测的周期列表。用于回放断点续跑：本次要求的周期全部已生成的日子
     * 不必重复计算，也避免反复删除/重插 PENDING 行造成无谓 churn。
     */
    public List<Integer> replayHorizons(LocalDate date) {
        return jdbc.queryForList("SELECT DISTINCT `horizon` FROM `ga_prediction` "
                        + "WHERE `trade_date`=? AND `is_replay`=1 AND `model_role`='CHAMPION'",
                Integer.class, Date.valueOf(date));
    }

    public List<Map<String, Object>> pendingPredictions(int limit) {
        return jdbc.queryForList("SELECT `id`,`trade_date`,`code`,`market`,`horizon`,`model_role`,`is_replay`,"
                + "`vol_20`,`status` FROM `ga_prediction` WHERE `status`='PENDING' ORDER BY `trade_date` LIMIT ?", limit)
                .stream().map(this::camel).toList();
    }

    /** 按主键游标分页读取待回填预测，避免一次把全表读进内存。 */
    public List<Map<String, Object>> pendingPredictionsAfter(long afterId, int limit) {
        return jdbc.queryForList("SELECT `id`,`trade_date`,`code`,`market`,`horizon`,`model_role`,`is_replay`,"
                        + "`vol_20`,`pred_direction`,`status` FROM `ga_prediction` "
                        + "WHERE `status`='PENDING' AND `id`>? ORDER BY `id` LIMIT ?", afterId, limit)
                .stream().map(this::camel).toList();
    }

    public void batchVerify(List<Object[]> args) {
        if (args.isEmpty()) return;
        jdbc.batchUpdate("UPDATE `ga_prediction` SET `status`=?,`target_date`=?,`actual_close`=?,"
                + "`actual_return`=?,`actual_direction`=?,`is_correct`=?,`is_effective`=?,"
                + "`verified_at`=NOW() WHERE `id`=? AND `status`='PENDING'", args);
    }

    /** 预测复盘列表（动态筛选 + 分页 + 摘要）。 */
    public Map<String, Object> listPredictions(Map<String, String> q, int page, int size) {
        StringBuilder w = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        addRange(w, args, "`trade_date`", q.get("from"), q.get("to"));
        addEq(w, args, "`horizon`", q.get("horizon"), true);
        addEq(w, args, "`pred_direction`", q.get("direction"), true);
        String result = q.get("result");
        addEq(w, args, "`status`", mapResult(result), false);
        if ("CORRECT".equalsIgnoreCase(result)) w.append(" AND `is_correct`=1");
        if ("WRONG".equalsIgnoreCase(result)) w.append(" AND `is_correct`=0");
        addEq(w, args, "`in_pool`", q.get("inPool"), true);
        addEq(w, args, "`is_replay`", q.get("includeReplay"), true);
        addLike(w, args, q.get("board"));
        String keyword = q.get("keyword");
        if (keyword != null && !keyword.isBlank()) { w.append(" AND `code` LIKE ?"); args.add("%" + keyword.trim() + "%"); }
        String minConf = q.get("minConf"), maxConf = q.get("maxConf");
        if (notBlank(minConf)) { w.append(" AND `confidence` >= ?"); args.add(Double.parseDouble(minConf)); }
        if (notBlank(maxConf)) { w.append(" AND `confidence` <= ?"); args.add(Double.parseDouble(maxConf)); }
        if ("1".equals(q.get("strict"))) w.append(" AND (`is_effective`=1 OR `is_effective` IS NULL)");

        String order = safeOrder(q.get("sort"));
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM `ga_prediction`" + w, Integer.class, args.toArray());
        // 名称用标量子查询取，避免 LEFT JOIN 后 w 里未限定的 `code` 变成歧义列
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT p.*, (SELECT i.`name` FROM `stock_info` i WHERE i.`code`=p.`code` "
                        + "AND i.`exchange`=p.`market` LIMIT 1) `name` FROM `ga_prediction` p" + w
                        + " ORDER BY " + order + " LIMIT ? OFFSET ?",
                append(args, size, (page - 1) * size).toArray()).stream().map(this::camel).toList();
        Map<String, Object> out = pageOf(rows, total == null ? 0 : total, page, size);
        List<Object> sargs = new ArrayList<>(args);
        Map<String, Object> s = jdbc.queryForMap("SELECT COUNT(*) n, SUM(CASE WHEN `is_correct`=1 THEN 1 ELSE 0 END) c "
                + "FROM `ga_prediction`" + w + " AND `pred_direction`<>0 AND `status`='VERIFIED'", sargs.toArray());
        int n = ((Number) s.get("n")).intValue();
        out.put("summary", Map.of("hitRate", n == 0 ? 0 : ((Number) s.get("c")).doubleValue() / n, "n", n));
        return out;
    }

    /** 供指标汇总与分析使用的已验证主榜预测。 */
    public List<Map<String, Object>> verifiedChampion(LocalDate from, LocalDate to, int horizon,
                                                       boolean includeReplay, int limit) {
        StringBuilder w = new StringBuilder(" WHERE `status`='VERIFIED' AND `model_role`='CHAMPION' "
                + "AND `in_pool`=1 AND `trade_date` BETWEEN ? AND ?");
        List<Object> args = new ArrayList<>(List.of(Date.valueOf(from), Date.valueOf(to)));
        if (horizon > 0) { w.append(" AND `horizon`=?"); args.add(horizon); }
        if (!includeReplay) w.append(" AND `is_replay`=0");
        w.append(" ORDER BY `trade_date` LIMIT ?"); args.add(limit);
        return jdbc.queryForList("SELECT * FROM `ga_prediction`" + w, args.toArray())
                .stream().map(this::camel).toList();
    }

    /**
     * 因子 IC 专用：只取截面 Rank IC 需要的 4 列，并按交易日倒序取最近 N 行。
     *
     * <p>verifiedChampion 是 SELECT * 且 ORDER BY trade_date 升序 + LIMIT，
     * 单周期 120 天内已验证行可超 20 万，既会把 explain_json 大字段拉进堆，
     * 又会把「最近 20 个交易日」截成最早的一个月，导致 IC 口径错误。</p>
     */
    public List<Map<String, Object>> verifiedReturns(LocalDate from, LocalDate to, int horizon,
                                                     boolean includeReplay, int limit) {
        StringBuilder w = new StringBuilder(" WHERE `status`='VERIFIED' AND `model_role`='CHAMPION' "
                + "AND `in_pool`=1 AND `trade_date` BETWEEN ? AND ?");
        List<Object> args = new ArrayList<>(List.of(Date.valueOf(from), Date.valueOf(to)));
        if (horizon > 0) { w.append(" AND `horizon`=?"); args.add(horizon); }
        if (!includeReplay) w.append(" AND `is_replay`=0");
        w.append(" ORDER BY `trade_date` DESC, `id` DESC LIMIT ?"); args.add(limit);
        return jdbc.queryForList("SELECT `trade_date`,`code`,`market`,`actual_return` FROM `ga_prediction`"
                + w, args.toArray()).stream().map(this::camel).toList();
    }

    /** 影子 / 冠军预测的非观望已验证行，用于晋升 BalAcc 比较。 */
    public List<Map<String, Object>> rolePredictions(int h, String role, int limit) {
        return jdbc.queryForList("SELECT `pred_direction`,`is_correct`,`horizon` FROM `ga_prediction` "
                + "WHERE `horizon`=? AND `model_role`=? AND `status`='VERIFIED' "
                + "AND `pred_direction`<>0 AND `is_correct` IS NOT NULL "
                + "ORDER BY `trade_date` DESC LIMIT ?", h, role, limit)
                .stream().map(this::camel).toList();
    }

    public List<Map<String, Object>> allPredictionsForMetric(LocalDate from, LocalDate to, int limit) {
        // 只投影统计需要的列：explain_json 等大字段会让几十万行直接把堆吃满
        return jdbc.queryForList("SELECT `trade_date`,`horizon`,`code`,`market`,`model_role`,`is_replay`,"
                + "`in_pool`,`pred_direction`,`prob_up`,`actual_return`,`is_correct`,`regime` "
                + "FROM `ga_prediction` WHERE `status`='VERIFIED' "
                + "AND `trade_date` BETWEEN ? AND ? ORDER BY `trade_date` LIMIT ?",
                Date.valueOf(from), Date.valueOf(to), limit).stream().map(this::camel).toList();
    }

    /**
     * 指标汇总专用的窄查询：把 horizon / is_replay / CHAMPION / in_pool 全部下推到 SQL。
     *
     * <p>rebuild 会对 5 个周期 × 2 套口径各扫一遍区间，若不在 SQL 里过滤，
     * 每个组合都要拉取全量明细（单月可超 40 万行），既慢又会撞到 LIMIT 截断。</p>
     */
    public List<Map<String, Object>> allPredictionsForMetric(LocalDate from, LocalDate to,
                                                             int horizon, int replay, int limit) {
        return jdbc.queryForList("SELECT `trade_date`,`horizon`,`code`,`market`,`model_role`,`is_replay`,"
                + "`in_pool`,`pred_direction`,`prob_up`,`actual_return`,`is_correct`,`regime` "
                + "FROM `ga_prediction` WHERE `status`='VERIFIED' AND `horizon`=? AND `is_replay`=? "
                + "AND `model_role`='CHAMPION' AND `in_pool`=1 "
                + "AND `trade_date` BETWEEN ? AND ? ORDER BY `trade_date` LIMIT ?",
                horizon, replay, Date.valueOf(from), Date.valueOf(to), limit).stream().map(this::camel).toList();
    }

    /**
     * 初始化窗口内已有「实盘」（is_replay=0）冠军预测的交易日，用于 InitJob 幂等判断：
     * 只补缺失的日期，已经生成过的日子不重复计算。
     */
    public List<LocalDate> livePredictionDates(LocalDate from, LocalDate to) {
        return jdbc.queryForList("SELECT DISTINCT `trade_date` FROM `ga_prediction` "
                        + "WHERE `is_replay`=0 AND `model_role`='CHAMPION' AND `trade_date` BETWEEN ? AND ? "
                        + "ORDER BY `trade_date`",
                LocalDate.class, Date.valueOf(from), Date.valueOf(to));
    }

    // ------------------------------------------------------------------ ga_metric_daily

    private static final String METRIC_UPSERT = "INSERT INTO `ga_metric_daily` (`as_of_date`,`horizon`,"
            + "`scope_type`,`scope_value`,`window_days`,`is_replay`,`n_pred`,`n_verified`,`n_correct`,"
            + "`hit_rate`,`up_hit_rate`,`down_hit_rate`,`coverage`,`ic`,`avg_ret_up`,`avg_ret_down`,`spread`) "
            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE "
            + "`n_pred`=VALUES(`n_pred`),`n_verified`=VALUES(`n_verified`),`n_correct`=VALUES(`n_correct`),"
            + "`hit_rate`=VALUES(`hit_rate`),`up_hit_rate`=VALUES(`up_hit_rate`),"
            + "`down_hit_rate`=VALUES(`down_hit_rate`),`coverage`=VALUES(`coverage`),`ic`=VALUES(`ic`),"
            + "`avg_ret_up`=VALUES(`avg_ret_up`),`avg_ret_down`=VALUES(`avg_ret_down`),`spread`=VALUES(`spread`)";

    public void upsertMetrics(List<Object[]> args) {
        if (!args.isEmpty()) jdbc.batchUpdate(METRIC_UPSERT, args);
    }

    public List<Map<String, Object>> metricTrend(int horizon, int window, LocalDate from, LocalDate to) {
        return jdbc.queryForList("SELECT * FROM `ga_metric_daily` WHERE `scope_type`='ALL' AND `window_days`=? "
                        + "AND `horizon`=? AND `as_of_date` BETWEEN ? AND ? ORDER BY `as_of_date`",
                window, horizon, Date.valueOf(from), Date.valueOf(to)).stream().map(this::camel).toList();
    }

    public List<Map<String, Object>> metricLatest(int horizon, int window) {
        return jdbc.queryForList("SELECT * FROM `ga_metric_daily` WHERE `scope_type`='ALL' AND `window_days`=? "
                + "AND (`horizon`=? OR ?=0) ORDER BY `as_of_date` DESC LIMIT 200",
                window, horizon, horizon).stream().map(this::camel).toList();
    }

    // ------------------------------------------------------------------ ga_stock_pool / log

    public Map<String, Object> poolStates() {
        Map<String, Object> out = new LinkedHashMap<>();
        jdbc.queryForList("SELECT * FROM `ga_stock_pool`").forEach(m -> out.put(
                m.get("code") + "|" + m.get("market"), camel(m)));
        return out;
    }

    public void upsertPool(String code, String market, int flag, String status, String reason,
                           String detail, LocalDate since, Double hit60, Integer n60,
                           Double shadowHit20, Integer shadowN20, LocalDate evalDate) {
        jdbc.update("INSERT INTO `ga_stock_pool` (`code`,`market`,`adjustflag`,`pool_status`,`reason_code`,"
                        + "`reason_detail`,`since_date`,`hit_rate_60`,`n_verified_60`,`shadow_hit_rate_20`,"
                        + "`shadow_n_20`,`last_eval_date`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?) "
                        + "ON DUPLICATE KEY UPDATE `pool_status`=VALUES(`pool_status`),"
                        + "`reason_code`=VALUES(`reason_code`),`reason_detail`=VALUES(`reason_detail`),"
                        + "`since_date`=VALUES(`since_date`),`hit_rate_60`=VALUES(`hit_rate_60`),"
                        + "`n_verified_60`=VALUES(`n_verified_60`),"
                        + "`shadow_hit_rate_20`=VALUES(`shadow_hit_rate_20`),`shadow_n_20`=VALUES(`shadow_n_20`),"
                        + "`last_eval_date`=VALUES(`last_eval_date`)",
                code, market, flag, status, reason, detail, Date.valueOf(since),
                hit60, n60, shadowHit20, shadowN20, Date.valueOf(evalDate));
    }

    public void insertPoolLog(String code, String market, int flag, String eventType, LocalDate date,
                              String reason, String detail, String metricJson, String operator) {
        jdbc.update("INSERT INTO `ga_stock_elimination_log` (`code`,`market`,`adjustflag`,`event_type`,"
                        + "`event_date`,`reason_code`,`reason_detail`,`metric_json`,`operator`) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)", code, market, flag, eventType, Date.valueOf(date),
                reason, detail, metricJson, operator == null ? "SYSTEM" : operator);
    }

    public List<Map<String, Object>> lastPoolEvent(String code, String market, String eventType) {
        return jdbc.queryForList("SELECT * FROM `ga_stock_elimination_log` WHERE `code`=? AND `market`=? "
                        + "AND `event_type`=? ORDER BY `id` DESC LIMIT 1", code, market, eventType)
                .stream().map(this::camel).toList();
    }

    public Map<String, Object> poolList(String status, String reason, int page, int size) {
        StringBuilder w = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (notBlank(status)) { w.append(" AND `pool_status`=?"); args.add(status); }
        if (notBlank(reason)) { w.append(" AND `reason_code`=?"); args.add(reason); }
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM `ga_stock_pool`" + w, Integer.class, args.toArray());
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM `ga_stock_pool`" + w
                        + " ORDER BY `pool_status`,`since_date` DESC LIMIT ? OFFSET ?",
                append(args, size, (page - 1) * size).toArray()).stream().map(this::camel).toList();
        Map<String, Object> out = pageOf(rows, total == null ? 0 : total, page, size);
        out.put("distribution", jdbc.queryForList("SELECT `reason_code`, COUNT(*) n FROM `ga_stock_pool` "
                + "WHERE `pool_status`='ELIMINATED' AND `reason_code` IS NOT NULL GROUP BY `reason_code`")
                .stream().map(this::camel).toList());
        return out;
    }

    public List<Map<String, Object>> poolLog(String code, String market, LocalDate from, LocalDate to) {
        StringBuilder w = new StringBuilder(" WHERE `event_date` BETWEEN ? AND ?");
        List<Object> args = new ArrayList<>(List.of(Date.valueOf(from), Date.valueOf(to)));
        if (notBlank(code)) { w.append(" AND `code`=?"); args.add(code); }
        if (notBlank(market)) { w.append(" AND `market`=?"); args.add(market); }
        return jdbc.queryForList("SELECT * FROM `ga_stock_elimination_log`" + w
                + " ORDER BY `id` DESC LIMIT 500", args.toArray()).stream().map(this::camel).toList();
    }

    // ------------------------------------------------------------------ ga_factor_ic_daily / job

    public void upsertFactorIc(LocalDate date, int horizon, String feature, double ic20, double ic60,
                               double icir, int decay) {
        jdbc.update("INSERT INTO `ga_factor_ic_daily` (`as_of_date`,`horizon`,`feature_name`,`ic_20`,"
                        + "`ic_60`,`icir_60`,`decay_flag`) VALUES (?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE "
                        + "`ic_20`=VALUES(`ic_20`),`ic_60`=VALUES(`ic_60`),`icir_60`=VALUES(`icir_60`),"
                        + "`decay_flag`=VALUES(`decay_flag`)", Date.valueOf(date), horizon, feature,
                ic20, ic60, icir, decay);
    }

    /** 已经有因子体检记录的交易日；InitJob 用它跳过重复计算（截面 IC 很贵）。 */
    public List<LocalDate> factorIcDates(LocalDate from, LocalDate to) {
        return jdbc.queryForList("SELECT DISTINCT `as_of_date` FROM `ga_factor_ic_daily` "
                        + "WHERE `as_of_date` BETWEEN ? AND ? ORDER BY `as_of_date`",
                LocalDate.class, Date.valueOf(from), Date.valueOf(to));
    }

    public void logJob(String job, String status, String message, int processed) {
        logJob(job, status, message, processed, java.time.LocalDateTime.now());
    }

    /** 记录真实开始时间，便于看板显示耗时（旧实现 started_at/finished_at 都写成完成时刻）。 */
    public void logJob(String job, String status, String message, int processed,
                       java.time.LocalDateTime startedAt) {
        java.time.LocalDateTime start = startedAt == null ? java.time.LocalDateTime.now() : startedAt;
        // 开始与结束都用 JVM 时钟，避免与数据库 NOW() 有几秒时钟差时出现「负耗时」
        jdbc.update("INSERT INTO `ga_job_log` (`job_name`,`status`,`message`,`processed`,`started_at`,"
                        + "`finished_at`) VALUES (?,?,?,?,?,?)", job, status,
                message == null ? "" : message.substring(0, Math.min(1000, message.length())), processed,
                java.sql.Timestamp.valueOf(start), java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()));
    }

    /** 最近 N 条任务日志（新→旧），供「后台任务」页的运行历史使用。 */
    public List<Map<String, Object>> recentJobLogs(int limit) {
        int n = Math.max(1, Math.min(200, limit));
        return jdbc.queryForList("SELECT `id`,`job_name`,`status`,`message`,`processed`,`started_at`,"
                        + "`finished_at`,`created_at` FROM `ga_job_log` ORDER BY `id` DESC LIMIT ?", n)
                .stream().map(this::camel).toList();
    }

    public List<Map<String, Object>> jobStatus() {
        return jdbc.queryForList("SELECT j.`job_name`, j.`status`, j.`message`, j.`processed`, j.`finished_at` "
                + "FROM `ga_job_log` j JOIN (SELECT `job_name`, MAX(`id`) mid FROM `ga_job_log` GROUP BY `job_name`) x "
                + "ON x.mid=j.`id` ORDER BY j.`job_name`").stream().map(this::camel).toList();
    }

    // ------------------------------------------------------------------ 工具

    private static Map<String, Object> pageOf(List<?> rows, int total, int page, int size) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", rows); out.put("total", total); out.put("page", page); out.put("size", size);
        out.put("pages", Math.max(1, (int) Math.ceil((double) total / Math.max(1, size))));
        return out;
    }

    private static List<Object> append(List<Object> args, Object... more) {
        List<Object> out = new ArrayList<>(args);
        out.addAll(List.of(more));
        return out;
    }

    private static void addRange(StringBuilder w, List<Object> args, String col, String from, String to) {
        if (notBlank(from)) { w.append(" AND ").append(col).append(" >= ?"); args.add(Date.valueOf(from.trim())); }
        if (notBlank(to)) { w.append(" AND ").append(col).append(" <= ?"); args.add(Date.valueOf(to.trim())); }
    }

    private static void addEq(StringBuilder w, List<Object> args, String col, String v, boolean numeric) {
        if (!notBlank(v)) return;
        w.append(" AND ").append(col).append(" = ?");
        args.add(numeric ? Integer.parseInt(v.trim()) : v.trim());
    }

    /** 板块归一化为代码前缀；Controller 传逗号分隔的一个或多个前缀（如 MAIN → 00,60）。 */
    private static void addLike(StringBuilder w, List<Object> args, String board) {
        if (!notBlank(board) || "ALL".equalsIgnoreCase(board)) return;
        String[] parts = board.split(",");
        w.append(" AND (");
        boolean first = true;
        for (String part : parts) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            if (!first) w.append(" OR ");
            w.append("`code` LIKE ?");
            args.add(p + "%");
            first = false;
        }
        w.append(")");
    }

    /** 排序字段白名单：只允许前端约定的 camelCase 字段，防 SQL 注入。 */
    private static String safeOrder(String sort) {
        String field = "tradeDate", dir = "DESC";
        if (notBlank(sort)) {
            String[] parts = sort.trim().split(",");
            field = parts[0].trim();
            if (parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim())) dir = "ASC";
        }
        String col = switch (field) {
            case "confidence" -> "`confidence`";
            case "actualReturn" -> "ABS(`actual_return`)";
            case "probUp" -> "`prob_up`";
            default -> "`trade_date`";
        };
        return col + " " + dir + (col.equals("`trade_date`") ? ",`horizon`,`code`" : "");
    }

    private static String mapResult(String r) {
        if (r == null || r.isBlank() || "ALL".equalsIgnoreCase(r)) return null;
        return switch (r.toUpperCase()) {
            case "CORRECT" -> "VERIFIED";
            case "WRONG" -> "VERIFIED";
            case "PENDING" -> "PENDING";
            default -> null;
        };
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    private static Object toDate(LocalDate d) { return d == null ? null : Date.valueOf(d); }

    /** snake_case → camelCase；日期统一转 yyyy-MM-dd 字符串，避免前端拿到时间戳。 */
    private Map<String, Object> camel(Map<String, Object> src) {
        Map<String, Object> out = new LinkedHashMap<>();
        src.forEach((k, v) -> out.put(toCamel(k), normalize(v)));
        return out;
    }

    private static Object normalize(Object v) {
        if (v instanceof java.sql.Date d) return d.toLocalDate().toString();
        if (v instanceof java.sql.Timestamp t) return t.toLocalDateTime().toString();
        if (v instanceof java.time.LocalDateTime l) return l.toString();
        if (v instanceof java.time.LocalDate l) return l.toString();
        if (v instanceof byte[] b) return new String(b, java.nio.charset.StandardCharsets.UTF_8);
        return v;
    }

    private static String toCamel(String s) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : s.toCharArray()) {
            if (c == '_') upper = true;
            else { sb.append(upper ? Character.toUpperCase(c) : c); upper = false; }
        }
        return sb.toString();
    }
}
