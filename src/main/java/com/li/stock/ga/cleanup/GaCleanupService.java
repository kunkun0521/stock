package com.li.stock.ga.cleanup;

import com.li.stock.ga.config.GaConfig;
import com.li.stock.ga.label.LabelBuilder;
import com.li.stock.ga.metric.MetricService;
import com.li.stock.ga.pool.PoolService;
import com.li.stock.ga.repository.GaDataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GA 数据体检 / 修复 / 保留期清理（规格 9.13）。
 *
 * <p>三段式：</p>
 * <ol>
 *   <li><b>体检 inspect</b>：只读，返回 11 类脏数据条数、各表占用、按保留期算出的待删行数。</li>
 *   <li><b>修复 repair</b>：把可判定的脏数据按业务口径重算（幂等），再用现有链路
 *       {@link MetricService#rebuild} / {@link MetricService#factorIc} / {@link PoolService#evaluate}
 *       重新生成派生指标，保证「清理后看板数据是对的」。</li>
 *   <li><b>清理 purge</b>：按配置保留期分批删除长期不用的历史数据，白名单 + 安全下限 +
 *       单次上限 + 默认 dry-run 四重保护。</li>
 * </ol>
 *
 * <p><b>安全约定</b>：{@code dryRun=true}（默认）只统计不写库；真正删除必须同时
 * {@code dryRun=false} 且 {@code confirm=true}。定时任务永远走 dry-run，只做预警。
 * 行情源表、配置表、股票池状态与当前冠军集永不在删除范围内。</p>
 *
 * <p>仅用于研究与复盘，输出不构成投资建议。</p>
 */
@Service
public class GaCleanupService {

    private static final Logger log = LoggerFactory.getLogger(GaCleanupService.class);

    /** 脏数据规则的中文说明与严重程度，供体检页直接展示。 */
    private static final Map<String, String> DIRTY_LABELS = Map.ofEntries(
            Map.entry("verifiedMissingCorrect", "已验证但缺命中判定（历史 VerifyJob 投影缺陷，会压低命中率）"),
            Map.entry("verifiedMissingActualReturn", "已验证但缺实际收益"),
            Map.entry("voidWithPayload", "VOID 行残留回填字段"),
            Map.entry("pendingWithPayload", "PENDING 行残留回填字段"),
            Map.entry("futureFactorIc", "因子 IC 记录日期晚于行情最新交易日（非交易日被写入）"),
            Map.entry("invertedChampionWindow", "冠军版本生效区间倒挂（effective_from > effective_to）"),
            Map.entry("staleRunningRuns", "僵死的 RUNNING 进化任务（进程中断未收尾）"),
            Map.entry("negativeJobDuration", "任务日志负耗时（finished_at < started_at）"),
            Map.entry("futurePredictions", "预测日期晚于行情最新交易日"),
            Map.entry("orphanGenerationStat", "逐代统计找不到所属 ga_run"),
            Map.entry("orphanChampionVersion", "主榜预测引用了不存在的冠军版本"));

    private final GaConfig config;
    private final GaDataRepository data;
    private final GaCleanupRepository repo;
    private final LabelBuilder labels;
    private final MetricService metrics;
    private final PoolService pool;

    public GaCleanupService(GaConfig config, GaDataRepository data, GaCleanupRepository repo,
                            LabelBuilder labels, MetricService metrics, PoolService pool) {
        this.config = config;
        this.data = data;
        this.repo = repo;
        this.labels = labels;
        this.metrics = metrics;
        this.pool = pool;
    }

    // ------------------------------------------------------------------ 体检

    /** 只读体检：脏数据、保留期策略、表占用、受保护表清单。 */
    public Map<String, Object> inspect() {
        LocalDate anchor = anchorDate();
        Map<String, Long> dirty = repo.dirtyCounts(anchor);
        List<Map<String, Object>> dirtyRows = new ArrayList<>();
        dirty.forEach((k, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", k);
            m.put("label", DIRTY_LABELS.getOrDefault(k, k));
            m.put("rows", n);
            m.put("level", n == 0 ? "ok" : "warn");
            dirtyRows.add(m);
        });

        List<Map<String, Object>> retention = retentionPreview(anchor);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("anchorDate", anchor.toString());
        out.put("today", LocalDate.now().toString());
        out.put("dirty", dirtyRows);
        out.put("dirtyTotal", dirty.values().stream().mapToLong(Long::longValue).sum());
        out.put("retention", retention);
        out.put("retentionTotal", retention.stream()
                .mapToLong(r -> r.get("rows") instanceof Number n ? n.longValue() : 0L).sum());
        out.put("tables", repo.tableStats());
        out.put("policy", policy(anchor));
        out.put("protectedTables", GaCleanupRepository.PROTECTED_TABLES);
        return out;
    }

    /** 当前保留期策略与每层保护的实际取值。 */
    public Map<String, Object> policy(LocalDate anchor) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("cleanupEnabled", config.globalInt("cleanup_enabled"));
        p.put("dryRunDefault", config.globalInt("cleanup_dry_run"));
        p.put("minKeepDays", minKeepDays());
        p.put("batchSize", batchSize());
        p.put("maxRows", maxRows());
        p.put("protectPending", config.globalInt("cleanup_protect_pending"));
        p.put("repairEnabled", config.globalInt("cleanup_repair"));
        p.put("regenerateIcDays", regenerateIcDays());
        List<Map<String, Object>> rules = new ArrayList<>();
        for (GaCleanupRepository.Rule r : effectiveRules()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("table", r.table());
            m.put("note", r.note());
            m.put("configKey", r.configKey());
            m.put("days", effectiveDays(r.configKey(), r.table()));
            m.put("cutoff", cutoff(r, anchor).toString());
            rules.add(m);
        }
        p.put("rules", rules);
        return p;
    }

    /** 生效规则：把「保护 PENDING」开关应用到预测表，保证预览与实际执行完全一致。 */
    private List<GaCleanupRepository.Rule> effectiveRules() {
        boolean protect = config.globalInt("cleanup_protect_pending") != 0;
        List<GaCleanupRepository.Rule> out = new ArrayList<>();
        for (GaCleanupRepository.Rule r : GaCleanupRepository.RULES) {
            out.add(protect && "ga_prediction".equals(r.table())
                    ? new GaCleanupRepository.Rule(r.table(), r.dateCol(), r.configKey(),
                    r.extra() + " AND `status`<>'PENDING'", r.note())
                    : r);
        }
        return out;
    }

    private List<Map<String, Object>> retentionPreview(LocalDate anchor) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (GaCleanupRepository.Rule r : effectiveRules()) {
            LocalDate cutoff = cutoff(r, anchor);
            Map<String, Object> stat = repo.retentionStat(r, cutoff);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("table", r.table());
            m.put("note", r.note());
            m.put("configKey", r.configKey());
            m.put("days", effectiveDays(r.configKey(), r.table()));
            m.put("cutoff", cutoff.toString());
            m.put("rows", stat.get("rows"));
            m.put("minDate", stat.get("minDate"));
            m.put("maxDate", stat.get("maxDate"));
            out.add(m);
        }
        return out;
    }

    // ------------------------------------------------------------------ 执行

    /**
     * 统一执行入口。
     *
     * @param mode    repair / purge / all
     * @param dryRun  true 时完全不写库（但会返回将要处理的行数）
     * @param confirm 真实删除必须为 true，防止误触
     */
    public Map<String, Object> run(String mode, boolean dryRun, boolean confirm) {
        String m = mode == null || mode.isBlank() ? "all" : mode.toLowerCase().trim();
        if (!List.of("repair", "purge", "all").contains(m)) {
            throw new IllegalArgumentException("mode 只能是 repair / purge / all");
        }
        if (!dryRun && !confirm) {
            throw new IllegalArgumentException("真实执行必须显式传 confirm=true（先跑 dryRun 预览）");
        }
        LocalDateTime start = LocalDateTime.now();
        LocalDate anchor = anchorDate();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", m);
        out.put("dryRun", dryRun);
        out.put("anchorDate", anchor.toString());
        List<String> steps = new ArrayList<>();
        int processed = 0;

        if (("repair".equals(m) || "all".equals(m)) && config.globalInt("cleanup_repair") == 1) {
            Map<String, Object> repaired = repair(dryRun, anchor);
            steps.add("修复：" + repaired.get("summary"));
            out.put("repair", repaired);
            processed += repaired.get("rows") instanceof Number n ? n.intValue() : 0;
        }
        if ("purge".equals(m) || "all".equals(m)) {
            Map<String, Object> purged = purge(dryRun, anchor);
            steps.add("清理：" + purged.get("summary"));
            out.put("purge", purged);
            processed += purged.get("rows") instanceof Number n ? n.intValue() : 0;
        }
        if (!dryRun && ("repair".equals(m) || "all".equals(m))
                && config.globalInt("cleanup_repair") == 1) {
            steps.add("重新生成：" + regenerate(anchor));
            out.put("regenerated", true);
        }
        out.put("steps", steps);
        out.put("processed", processed);
        out.put("elapsedMs", java.time.Duration.between(start, LocalDateTime.now()).toMillis());
        out.put("summary", dryRun ? "预览完成（未写库）" : "执行完成：" + String.join("；", steps));

        if (!dryRun) {
            repo.logRun(m, false, String.join("；", steps), processed, start);
            log.info("[GA] 数据清理完成：{}", out.get("summary"));
        }
        return out;
    }

    // ------------------------------------------------------------------ 修复

    /**
     * 修复可判定的脏数据。全部幂等：命中判定重算带 {@code is_correct IS NULL} 条件，
     * 重复执行不会改动已正确的数据。
     */
    public Map<String, Object> repair(boolean dryRun, LocalDate anchor) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Long> before = repo.dirtyCounts(anchor);
        // 只统计本方法真正会修复的 7 类；其余 4 类是结构性残留，仅体检列出、交由人工判断
        List<String> fixable = List.of("verifiedMissingCorrect", "futureFactorIc",
                "invertedChampionWindow", "staleRunningRuns", "negativeJobDuration",
                "orphanGenerationStat", "futurePredictions");
        Map<String, Long> fixed = new LinkedHashMap<>();
        long total = 0;

        if (dryRun) {
            for (String k : fixable) {
                long n = before.getOrDefault(k, 0L);
                fixed.put(k, n);
                total += n;
            }
        } else {
            // 1. 重算命中判定：与 PredictionService.verify 完全相同的口径
            //    correct = (pred_direction == actual_direction)，effective = correct && |ret| >= θ_h
            int n = recomputeVerified();
            fixed.put("verifiedMissingCorrect", (long) n);
            total += n;
            fixed.put("futureFactorIc", (long) repo.deleteFutureFactorIc(anchor));
            total += fixed.get("futureFactorIc");
            fixed.put("invertedChampionWindow", (long) repo.fixInvertedChampionWindow());
            total += fixed.get("invertedChampionWindow");
            fixed.put("staleRunningRuns", (long) repo.closeStaleRuns());
            total += fixed.get("staleRunningRuns");
            fixed.put("negativeJobDuration", (long) repo.fixNegativeJobDuration());
            total += fixed.get("negativeJobDuration");
            fixed.put("orphanGenerationStat", (long) repo.deleteOrphanGenerationStat());
            total += fixed.get("orphanGenerationStat");
            fixed.put("futurePredictions", (long) repo.deleteFuturePredictions(anchor));
            total += fixed.get("futurePredictions");
            // 残留的 VERIFIED 缺收益 / VOID 或 PENDING 带字段 / 孤儿冠军版本不自动改写，
            // 避免抹掉无法还原的业务事实，只在体检报告里列出。
        }
        out.put("rows", total);
        out.put("fixed", fixed);
        out.put("summary", String.format("%s %d 行脏数据%s", dryRun ? "将修复" : "已修复", total,
                dryRun ? "（预览）" : ""));
        return out;
    }

    /** 游标分页重算命中判定，避免一次把十万行读进堆。 */
    private int recomputeVerified() {
        int pageSize = Math.max(1000, Math.min(20000, config.globalInt("verify_page_size")));
        long cursor = 0;
        int total = 0;
        while (true) {
            List<Map<String, Object>> rows = repo.unverifiedRows(cursor, pageSize);
            if (rows.isEmpty()) break;
            cursor = rows.stream().mapToLong(r -> ((Number) r.get("id")).longValue()).max().orElse(cursor);
            List<Object[]> args = new ArrayList<>(rows.size());
            for (Map<String, Object> r : rows) {
                int h = ((Number) r.get("horizon")).intValue();
                int pred = ((Number) r.get("pred_direction")).intValue();
                int actual = ((Number) r.get("actual_direction")).intValue();
                double ret = r.get("actual_return") instanceof Number n ? n.doubleValue() : 0;
                double vol = r.get("vol_20") instanceof Number n ? n.doubleValue() : 0;
                int correct = pred == actual ? 1 : 0;
                double theta = labels.threshold(h, vol);
                int effective = correct == 1 && Math.abs(ret) >= theta ? 1 : 0;
                args.add(new Object[]{correct, effective, ((Number) r.get("id")).longValue()});
            }
            int batch = Math.max(100, config.globalInt("verify_batch_size"));
            for (int i = 0; i < args.size(); i += batch) {
                repo.repairVerified(args.subList(i, Math.min(args.size(), i + batch)));
            }
            total += args.size();
        }
        return total;
    }

    // ------------------------------------------------------------------ 清理

    /** 按保留期分批删除长期不用的历史数据。 */
    public Map<String, Object> purge(boolean dryRun, LocalDate anchor) {
        int batch = batchSize();
        long cap = maxRows();
        long remaining = cap;
        long total = 0;
        List<Map<String, Object>> details = new ArrayList<>();
        // 保护尚未回填的预测：它们还要等 VerifyJob 到期回填，删掉会永久丢失验证结果
        for (GaCleanupRepository.Rule r : effectiveRules()) {
            if (remaining <= 0) break;
            LocalDate cutoff = cutoff(r, anchor);
            long n = repo.purge(r, cutoff, batch, Math.min(remaining, cap), dryRun);
            remaining -= n;
            total += n;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("table", r.table());
            m.put("note", r.note());
            m.put("cutoff", cutoff.toString());
            m.put("rows", n);
            details.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", total);
        out.put("cap", cap);
        out.put("hitCap", total >= cap);
        out.put("details", details);
        out.put("summary", String.format("%s %d 行过期数据（共 %d 类规则，单次上限 %d）",
                dryRun ? "将删除" : "已删除", total, GaCleanupRepository.RULES.size(), cap));
        return out;
    }

    // ------------------------------------------------------------------ 重新生成

    /** 清理后重建派生数据，保证看板趋势 / 日历 / 因子 / 股票池口径与修复后的明细一致。 */
    private String regenerate(LocalDate anchor) {
        List<String> steps = new ArrayList<>();
        try {
            steps.add("指标 " + metrics.rebuild(anchor));
        } catch (Exception e) {
            steps.add("指标重建失败：" + e.getMessage());
            log.warn("[GA] 清理后指标重建失败：{}", e.getMessage());
        }
        int icDays = regenerateIcDays();
        if (icDays > 0) {
            try {
                List<LocalDate> dates = data.tradeDates(config.globalInt("adjust_flag"),
                        anchor.minusDays(Math.max(30, icDays * 4L)), anchor);
                List<LocalDate> recent = dates.size() > icDays
                        ? dates.subList(dates.size() - icDays, dates.size()) : dates;
                int calls = 0;
                for (LocalDate d : recent) {
                    for (int h : config.horizons()) { metrics.factorIc(d, h); calls++; }
                }
                steps.add(String.format("因子体检重算 %d 个交易日 / %d 次", recent.size(), calls));
            } catch (Exception e) {
                steps.add("因子体检重算失败：" + e.getMessage());
                log.warn("[GA] 清理后因子体检重算失败：{}", e.getMessage());
            }
        }
        try {
            steps.add("股票池 " + pool.evaluate(anchor, false));
        } catch (Exception e) {
            steps.add("股票池评估失败：" + e.getMessage());
            log.warn("[GA] 清理后股票池评估失败：{}", e.getMessage());
        }
        return String.join("；", steps);
    }

    public List<Map<String, Object>> history(int limit) {
        return repo.history(limit);
    }

    /**
     * 看板「立即运行 CleanupJob」入口。
     *
     * <p>是否真删由 {@code cleanup_dry_run} 决定：默认 1 = 只预览并给出待处理行数，
     * 用户到配置页把它改成 0 才会真正删除。定时任务永远只走预览，避免无人值守时误删。</p>
     */
    public String jobSummary() {
        boolean dryRun = config.globalInt("cleanup_dry_run") != 0;
        Map<String, Object> r = run("all", dryRun, !dryRun);
        return r.get("summary") + "；" + String.join("；",
                ((List<?>) r.getOrDefault("steps", List.of())).stream().map(String::valueOf).toList())
                + (dryRun ? "（当前为预览模式，需将 cleanup_dry_run 置 0 才会执行）" : "");
    }

    /** 定时体检：只读预览，把待清理规模写进任务日志，不删任何数据。 */
    public String scheduledInspect() {
        Map<String, Object> r = inspect();
        long dirty = r.get("dirtyTotal") instanceof Number n ? n.longValue() : 0;
        long expired = r.get("retentionTotal") instanceof Number n ? n.longValue() : 0;
        return String.format("数据体检（只读）：脏数据 %d 行，超过保留期 %d 行，锚点 %s；"
                        + "如需处理请在「数据清理」页预览后执行",
                dirty, expired, r.get("anchorDate"));
    }

    // ------------------------------------------------------------------ 保留期计算

    /** 以行情库最新交易日为锚点：避免行情滞后时把「最近数据」误判为过期。 */
    private LocalDate anchorDate() {
        LocalDate latest = data.latestDate(config.globalInt("adjust_flag"));
        return latest == null ? LocalDate.now() : latest;
    }

    private int minKeepDays() {
        return Math.max(7, config.globalInt("cleanup_min_keep_days"));
    }

    private int batchSize() {
        return Math.max(100, Math.min(20000, config.globalInt("cleanup_batch_size")));
    }

    private long maxRows() {
        return Math.max(1000L, (long) config.globalInt("cleanup_max_rows"));
    }

    private int regenerateIcDays() {
        return Math.max(0, Math.min(10, config.globalInt("cleanup_regenerate_ic_days")));
    }

    /**
     * 生效保留天数：不小于「安全下限」，且派生缓存必须覆盖 GA 实际读取的历史深度，
     * 否则清理后进化/标准化会缺数据。
     *
     * <p>口径来自 {@code GaEngine.evolve} 与 {@code FeatureService}：</p>
     * <ul>
     *   <li>特征表需覆盖 {@code feature_lookback_days}（默认 520 自然日）的训练区间 + 余量；</li>
     *   <li>市场表需覆盖「训练区间 + warmStart 再回溯一段」＝约 2 × lookback，
     *       因为 normalizedByDate 会用 warmStart(from) 起算 252 日时序 z-score。</li>
     * </ul>
     */
    private int effectiveDays(String key, String table) {
        int days = Math.max(minKeepDays(), config.globalInt(key));
        int lookback = Math.max(400, config.globalInt("feature_lookback_days"));
        if ("stock_feature_daily".equals(table)) return Math.max(days, lookback + 120);
        if ("ga_market_daily".equals(table)) return Math.max(days, lookback * 2 + 60);
        return days;
    }

    private LocalDate cutoff(GaCleanupRepository.Rule r, LocalDate anchor) {
        return anchor.minusDays(effectiveDays(r.configKey(), r.table()));
    }
}
