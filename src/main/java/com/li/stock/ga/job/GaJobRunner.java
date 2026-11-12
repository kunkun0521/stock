package com.li.stock.ga.job;

import com.li.stock.ga.config.GaConfig;
import com.li.stock.ga.engine.GaEngine;
import com.li.stock.ga.feature.FeatureService;
import com.li.stock.ga.metric.MetricService;
import com.li.stock.ga.pool.PoolService;
import com.li.stock.ga.predict.ChampionService;
import com.li.stock.ga.predict.PredictionService;
import com.li.stock.ga.repository.GaDataRepository;
import com.li.stock.ga.repository.GaStoreRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * GA 后台任务的统一编排与互斥执行（规格 13）。
 *
 * <p>与项目现有的 {@code ScoreScheduledTask} 保持同样的做法：单守护线程 + 每任务互斥锁 +
 * 幂等服务层，任务失败只记日志与 ga_job_log，不阻塞其它无依赖任务。看板的「立即运行」
 * 与 @Scheduled 定时任务都走 {@link #submit(String)}，立即返回、后台异步执行。</p>
 *
 * <p>链路：FeatureJob → PredictJob → VerifyJob → MetricJob / PoolJob；
 * EvolveJob 每周六执行（5 个周期依次进化），随后准入、校准重拟与因子体检。
 * 非交易日（行情库最大交易日早于今天）定时链路自动跳过，手动触发不受此限制。</p>
 */
@Service
public class GaJobRunner {

    private static final Logger log = LoggerFactory.getLogger(GaJobRunner.class);

    /** 看板 jobs/status 列出的全部可执行任务，顺序即展示顺序。 */
    public static final List<String> JOB_NAMES = List.of(
            "FeatureJob", "PredictJob", "VerifyJob", "MetricJob", "PoolJob",
            "EvolveJob", "CalibrateJob", "FactorIcJob", "ReplayJob",
            "DailyPipeline", "WeeklyEvolve");

    private final GaConfig config;
    private final GaDataRepository data;
    private final GaStoreRepository store;
    private final FeatureService features;
    private final PredictionService predictions;
    private final MetricService metrics;
    private final PoolService pool;
    private final ChampionService champions;
    private final GaEngine engine;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "stock-ga-jobs");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, AtomicBoolean> running = new ConcurrentHashMap<>();

    /** 总开关：false 时关闭所有定时触发，看板手动触发仍可用。 */
    @Value("${stock.ga.enabled:true}")
    private boolean enabled;

    public GaJobRunner(GaConfig config, GaDataRepository data, GaStoreRepository store,
                       FeatureService features, PredictionService predictions, MetricService metrics,
                       PoolService pool, ChampionService champions, GaEngine engine) {
        this.config = config;
        this.data = data;
        this.store = store;
        this.features = features;
        this.predictions = predictions;
        this.metrics = metrics;
        this.pool = pool;
        this.champions = champions;
        this.engine = engine;
    }

    // ------------------------------------------------------------------ 定时触发

    /** 交易日 17:30（晚于日线抓取）执行完整日链路。 */
    @Scheduled(cron = "${stock.ga.cron-daily:0 30 17 * * ?}", zone = "Asia/Shanghai")
    public void scheduledDaily() {
        if (!enabled) {
            log.info("GA 定时任务已停用（stock.ga.enabled=false）");
            return;
        }
        submitInternal("DailyPipeline", true);
    }

    /** 每周六 02:00 依次进化 5 个周期，并做准入、校准与每周淘汰评估。 */
    @Scheduled(cron = "${stock.ga.cron-evolve:0 0 2 ? * SAT}", zone = "Asia/Shanghai")
    public void scheduledWeekly() {
        if (!enabled) {
            log.info("GA 定时任务已停用（stock.ga.enabled=false）");
            return;
        }
        submitInternal("WeeklyEvolve", true);
    }

    // ------------------------------------------------------------------ 看板状态 / 手动触发

    /** jobs/status：固定任务清单 × ga_job_log 每个任务最后一条记录。 */
    public List<Map<String, Object>> status() {
        Map<String, Map<String, Object>> last = new LinkedHashMap<>();
        try {
            for (Map<String, Object> row : store.jobStatus()) {
                String name = str(row.get("jobName"));
                if (!name.isEmpty()) last.put(name, row);
            }
        } catch (Exception e) {
            log.warn("读取 ga_job_log 失败：{}", e.getMessage());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String name : JOB_NAMES) {
            Map<String, Object> row = last.get(name);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("running", running.getOrDefault(name, new AtomicBoolean(false)).get());
            m.put("lastRunAt", row == null ? "" : str(row.get("finishedAt")));
            m.put("lastStatus", row == null ? "NEVER" : str(row.get("status")));
            m.put("lastMessage", row == null ? "尚未运行" : str(row.get("message")));
            out.add(m);
        }
        return out;
    }

    /** jobs/run：看板「立即运行」；立即返回，任务在守护线程排队执行。 */
    public Map<String, Object> submit(String name) {
        String message = submitInternal(name, false);
        return Map.of("job", name, "message", message, "accepted", true);
    }

    private String submitInternal(String name, boolean skipIfNotTradingDay) {
        return submitNamed(name, () -> execute(name, skipIfNotTradingDay));
    }

    /** 带互斥锁与日志的通用异步提交；同一任务并发触发时直接跳过。 */
    private String submitNamed(String name, Callable<String> task) {
        if (!JOB_NAMES.contains(name)) {
            throw new IllegalArgumentException("未知任务：" + name + "，可选：" + String.join(" / ", JOB_NAMES));
        }
        AtomicBoolean lock = running.computeIfAbsent(name, k -> new AtomicBoolean(false));
        if (!lock.compareAndSet(false, true)) {
            return name + " 正在运行，已跳过重复触发";
        }
        executor.submit(() -> {
            long begin = System.currentTimeMillis();
            try {
                String msg = task.call();
                store.logJob(name, "SUCCESS", msg, 0);
                log.info("[GA] {} 完成（{} ms）：{}", name, System.currentTimeMillis() - begin, brief(msg));
            } catch (Exception e) {
                String err = e.getClass().getSimpleName() + ": " + e.getMessage();
                try { store.logJob(name, "FAILED", err, 0); } catch (Exception ignored) {}
                log.error("[GA] {} 失败（{} ms）：{}", name, System.currentTimeMillis() - begin, err, e);
            } finally {
                lock.set(false);
            }
        });
        return "已提交 " + name + "，后台异步执行，可在「后台任务」中查看结果";
    }

    /** /api/ga/evolution/run：指定周期进化（horizon 为空则全部周期）。 */
    public Map<String, Object> submitEvolve(Integer horizon) {
        int h = horizon == null ? 0 : horizon;
        String message = submitNamed("EvolveJob", () -> h > 0 ? evolveOne(h) : evolveAll(LocalDate.now()));
        return Map.of("job", "EvolveJob", "horizon", h, "message", message, "accepted", true);
    }

    /** /api/ga/evolution/replay：带日期与周期列表的历史回放。 */
    public Map<String, Object> submitReplay(LocalDate from, LocalDate to, int step, List<Integer> hs) {
        List<Integer> horizons = hs.isEmpty() ? config.horizons() : hs;
        String message = submitNamed("ReplayJob",
                () -> predictions.replay(from, to, Math.max(1, step), horizons));
        return Map.of("job", "ReplayJob", "message", message, "accepted", true);
    }

    // ------------------------------------------------------------------ 任务实现

    private String execute(String name, boolean skipIfNotTradingDay) {
        LocalDate today = LocalDate.now();
        int flag = config.globalInt("adjust_flag");
        if (skipIfNotTradingDay && ("DailyPipeline".equals(name) || "FeatureJob".equals(name)
                || "PredictJob".equals(name)) && !isTradingDay(flag, today)) {
            return today + " 非交易日（行情库尚无当日数据），跳过";
        }
        return switch (name) {
            case "FeatureJob" -> feature(flag, today);
            case "PredictJob" -> predict(flag);
            case "VerifyJob" -> predictions.verify(today);
            case "MetricJob" -> metrics.rebuild(today);
            case "PoolJob" -> pool.evaluate(today, false);
            case "EvolveJob" -> evolveAll(today);
            case "CalibrateJob" -> calibrateAll();
            case "FactorIcJob" -> factorIcAll(today);
            case "ReplayJob" -> replayDefault(flag, today);
            case "DailyPipeline" -> dailyPipeline(flag, today);
            case "WeeklyEvolve" -> weeklyEvolve(flag, today);
            default -> throw new IllegalArgumentException("未实现的任务：" + name);
        };
    }

    private boolean isTradingDay(int flag, LocalDate today) {
        LocalDate latest = data.latestDate(flag);
        return latest != null && !latest.isBefore(today);
    }

    private String feature(int flag, LocalDate day) {
        int marketRows = features.buildMarket(flag, day);
        FeatureService.BuildResult built = features.build(flag, day, day);
        List<String> issues = features.qualityCheck(flag, day);
        return String.format("市场环境 %d 行，特征写入 %d 行（跳过 %d / 共 %d）%s",
                marketRows, built.written(), built.skipped(), built.total(),
                issues.isEmpty() ? "" : "；质检告警：" + String.join(" / ", issues));
    }

    private String predict(int flag) {
        LocalDate t = data.latestDate(flag);
        if (t == null) return "行情库没有交易日，跳过预测";
        return predictions.predictDay(t, 0, true);
    }

    /** 收盘后日链路：特征 → 预测 → 回填 → 指标 → 每日淘汰 → 影子晋升 / 冠军衰减。 */
    private String dailyPipeline(int flag, LocalDate today) {
        List<String> steps = new ArrayList<>();
        steps.add("Feature: " + feature(flag, today));
        steps.add("Predict: " + predict(flag));
        steps.add("Verify: " + predictions.verify(today));
        steps.add("Metric: " + metrics.rebuild(today));
        steps.add("Pool: " + pool.evaluate(today, false));
        for (int h : config.horizons()) {
            steps.add("H" + h + " 晋升检查: " + champions.checkPromotion(h, today));
        }
        List<Integer> retire = metrics.checkRetire(today);
        for (int h : retire) {
            GaEngine.EvolveResult r = engine.evolve(h, today, "EVOLVE", null, null, null);
            steps.add("H" + h + " 衰减重进化: " + r.status() + " / runId=" + r.runId());
            if ("SUCCESS".equals(r.status())) steps.add("准入: " + champions.admit(r.runId(), h, today));
        }
        return String.join("；", steps);
    }

    /** 每周：5 个周期顺序进化 → 准入 → Platt 校准 → 因子体检 → 每周淘汰规则。 */
    private String weeklyEvolve(int flag, LocalDate today) {
        List<String> steps = new ArrayList<>();
        for (int h : config.horizons()) {
            GaEngine.EvolveResult r = engine.evolve(h, today, "EVOLVE", null, null, null);
            steps.add(String.format("H%d %s runId=%d F=%.4f%s", h, r.status(), r.runId(), r.bestFitness(),
                    r.error() == null || r.error().isBlank() ? "" : " (" + r.error() + ")"));
            if ("SUCCESS".equals(r.status())) steps.add("H" + h + " 准入: " + champions.admit(r.runId(), h, today));
        }
        steps.add("校准: " + calibrateAll());
        steps.add("因子体检: " + factorIcAll(today));
        steps.add("每周淘汰: " + pool.evaluate(today, true));
        return String.join("；", steps);
    }

    private String evolveOne(int h) {
        LocalDate today = LocalDate.now();
        GaEngine.EvolveResult r = engine.evolve(h, today, "MANUAL", null, null, null);
        String base = String.format("H%d %s runId=%d 代数=%d F=%.4f%s", h, r.status(), r.runId(),
                r.generations(), r.bestFitness(),
                r.error() == null || r.error().isBlank() ? "" : " (" + r.error() + ")");
        if (!"SUCCESS".equals(r.status())) return base;
        return base + "；准入: " + champions.admit(r.runId(), h, today);
    }

    private String evolveAll(LocalDate today) {
        List<String> steps = new ArrayList<>();
        for (int h : config.horizons()) {
            GaEngine.EvolveResult r = engine.evolve(h, today, "EVOLVE", null, null, null);
            steps.add("H" + h + " " + r.status() + " runId=" + r.runId() + " F=" + String.format("%.4f", r.bestFitness()));
            if ("SUCCESS".equals(r.status())) steps.add("准入: " + champions.admit(r.runId(), h, today));
        }
        return String.join("；", steps);
    }

    private String calibrateAll() {
        List<String> steps = new ArrayList<>();
        for (int h : config.horizons()) steps.add("H" + h + ": " + champions.refitCalibration(h));
        return String.join("；", steps);
    }

    private String factorIcAll(LocalDate today) {
        List<String> steps = new ArrayList<>();
        for (int h : config.horizons()) steps.add(metrics.factorIc(today, h));
        return String.join("；", steps);
    }

    /** 看板不传日期时的默认回放：最近约 60 个交易日、步长取 ga_config 的 replay_step。 */
    private String replayDefault(int flag, LocalDate today) {
        LocalDate from = today.minusDays(120);
        LocalDate to = data.latestDate(flag);
        if (to == null) return "行情库没有交易日，跳过回放";
        int step = config.globalInt("replay_step");
        return predictions.replay(from, to, step, config.horizons());
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    private static String brief(String s) {
        if (s == null) return "";
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
