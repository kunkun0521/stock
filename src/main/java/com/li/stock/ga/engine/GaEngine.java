package com.li.stock.ga.engine;

import com.li.stock.ga.GaMath;
import com.li.stock.ga.GaModel.Bar;
import com.li.stock.ga.GaModel.Fitness;
import com.li.stock.ga.GaModel.Fold;
import com.li.stock.ga.GaModel.Individual;
import com.li.stock.ga.GaModel.Label;
import com.li.stock.ga.GaModel.SampleMatrix;
import com.li.stock.ga.GaModel.Segment;
import com.li.stock.ga.config.GaConfig;
import com.li.stock.ga.feature.FeatureKeys;
import com.li.stock.ga.feature.FeatureService;
import com.li.stock.ga.label.LabelBuilder;
import com.li.stock.ga.repository.GaDataRepository;
import com.li.stock.ga.repository.GaStoreRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * 遗传算法引擎（规格 6），每个预测周期独立种群。
 *
 * <p>防未来函数四道防线：特征只用 ≤ t；折间 purge h 个交易日；Hold-out 全程不可见；
 * 到期收益一律由 LabelBuilder 从原始日线重算。样本矩阵一次性加载成 float[][]，所有个体共享，
 * 适应度评估按个体并行（parallel stream），每个个体用独立派生的随机种子保证可复现。</p>
 */
@Service
public class GaEngine {

    public record Split(List<Fold> folds, int holdoutFrom, int holdoutTo, List<String> warnings) {}

    public record EvolveResult(long runId, int generations, double bestFitness, long bestIndividualId,
                               List<Individual> hof, String status, String warn, String error) {}

    private final GaConfig config;
    private final GaDataRepository data;
    private final GaStoreRepository store;
    private final FeatureService features;
    private final LabelBuilder labels;

    public GaEngine(GaConfig config, GaDataRepository data, GaStoreRepository store,
                    FeatureService features, LabelBuilder labels) {
        this.config = config; this.data = data; this.store = store;
        this.features = features; this.labels = labels;
    }

    // ================================================================== 数据切分

    /**
     * walk-forward（规格 6.4）：最近 holdoutDays 个有标签日作 Hold-out；
     * 其余做 K 折滚动，训练段 [a,b]、隔离带 h 个交易日、验证段 [b+h+1,c]。
     * 数据不足时 K: 3→2→1、训练段缩短到 minTrainDays。
     */
    public static Split split(int labeledDays, int h, int folds, int trainDays, int validDays,
                              int step, int holdoutDays, int minTrainDays) {
        List<String> warnings = new ArrayList<>();
        int usable = labeledDays - holdoutDays;
        if (usable < trainDays + h + validDays) {
            trainDays = Math.max(minTrainDays, trainDays / 2);
            warnings.add("数据不足，训练段缩短到 " + trainDays + " 日");
        }
        while (folds > 1 && usable < trainDays + (folds - 1) * step + h + validDays) {
            folds--;
            warnings.add("数据不足，滚动折数降为 " + folds);
        }
        List<Fold> out = new ArrayList<>();
        for (int k = 0; k < folds; k++) {
            int c = usable - 1 - (folds - 1 - k) * step;
            int b = c - h - validDays;
            int a = b - trainDays + 1;
            if (a < 0 || b < 0 || c >= usable) { warnings.add("第 " + (k + 1) + " 折区间越界，已跳过"); continue; }
            out.add(new Fold(a, b, b + h + 1, c));
        }
        if (out.isEmpty()) warnings.add("可用有标签日不足以构造任一滚动折");
        return new Split(out, usable, labeledDays - 1, warnings);
    }

    // ================================================================== 样本矩阵

    private static final class Matrix {
        SampleMatrix m;
        int[][] byDate;      // dateIdx -> sample indices
        int labeledDays;
        List<LocalDate> dates;
    }

    private Matrix buildMatrix(int h, LocalDate asOf, int flag, List<LocalDate> labeledDates,
                               Map<LocalDate, List<FeatureService.Vec>> normalized,
                               Map<String, Label> labelIndex) {
        int nDates = labeledDates.size();
        Map<LocalDate, Integer> datePos = new HashMap<>();
        for (int i = 0; i < nDates; i++) datePos.put(labeledDates.get(i), i);
        int perDay = config.getInt(h, "sample_per_day");
        long seed = config.getLong(h, "random_seed");
        List<float[]> zs = new ArrayList<>();
        List<Integer> ys = new ArrayList<>(), dts = new ArrayList<>();
        List<Double> rets = new ArrayList<>();
        List<Boolean> noises = new ArrayList<>();
        List<String> codes = new ArrayList<>();

        for (int di = 0; di < nDates; di++) {
            LocalDate d = labeledDates.get(di);
            List<FeatureService.Vec> vecs = normalized.get(d);
            if (vecs == null || vecs.isEmpty()) continue;
            List<FeatureService.Vec> pool = new ArrayList<>(vecs);
            // 运行级固定种子 + 日期派生，保证同数据同种子完全一致
            Random rnd = new Random(seed * 31 + d.toEpochDay());
            if (pool.size() > perDay) {
                java.util.Collections.shuffle(pool, rnd);
                pool = pool.subList(0, perDay);
            }
            for (FeatureService.Vec v : pool) {
                if (!v.valid()) continue;
                Label lab = labelIndex.get(v.code() + "|" + v.market() + "|" + d);
                if (lab == null) continue;
                zs.add(v.z());
                ys.add(lab.y());
                dts.add(di);
                rets.add(lab.ret());
                noises.add(lab.noise());
                codes.add(v.code() + "|" + v.market());
            }
        }
        int n = zs.size();
        float[][] z = new float[n][];
        for (int i = 0; i < n; i++) z[i] = zs.get(i);
        int[] y = new int[n], dateIdx = new int[n];
        double[] ret = new double[n];
        boolean[] noise = new boolean[n];
        String[] code = new String[n];
        for (int i = 0; i < n; i++) {
            y[i] = ys.get(i); dateIdx[i] = dts.get(i); ret[i] = rets.get(i);
            noise[i] = noises.get(i); code[i] = codes.get(i);
        }
        @SuppressWarnings("unchecked")
        List<Integer>[] buckets = new List[nDates];
        for (int i = 0; i < nDates; i++) buckets[i] = new ArrayList<>();
        for (int i = 0; i < n; i++) buckets[dateIdx[i]].add(i);
        int[][] byDate = new int[nDates][];
        for (int i = 0; i < nDates; i++) byDate[i] = buckets[i].stream().mapToInt(Integer::intValue).toArray();

        Matrix out = new Matrix();
        out.m = new SampleMatrix(z, y, ret, noise, dateIdx, code, FeatureKeys.N);
        out.byDate = byDate; out.labeledDays = nDates; out.dates = labeledDates;
        return out;
    }

    // ================================================================== 适应度

    /** 规格 6.2 / 6.3：个体打分、段内指标与最终适应度。 */
    public static final class FitnessEvaluator {
        private final GaConfig cfg;
        private final int[][] byDate;

        public FitnessEvaluator(GaConfig cfg, int[][] byDate) { this.cfg = cfg; this.byDate = byDate; }

        public static double score(Individual ind, float[] z) {
            int active = 0;
            double s = 0;
            for (int j = 0; j < ind.m.length; j++) {
                if (ind.m[j] == 1) {
                    // 与 ChampionService.predictOne 保持同一套缺失值口径：非有限 z 按截面均值 0 处理
                    double zj = j < z.length && Double.isFinite(z[j]) ? z[j] : 0.0;
                    s += Double.isFinite(ind.w[j]) ? ind.w[j] * zj : 0;
                    active++;
                }
            }
            return ind.b + s / Math.sqrt(Math.max(1, active));
        }

        /** 段内评估：from/to 为有标签交易日下标区间（左闭右闭）。 */
        public Segment segment(Individual ind, SampleMatrix m, int from, int to, int h) {
            int tp = 0, tn = 0, fp = 0, fn = 0, nonAbstain = 0, total = 0;
            double brierSum = 0;
            double[] icByBlock = new double[Math.max(1, to - from + 1)];
            int blockCount = 0;
            int blockDays = Math.max(1, cfg.getInt(h, "stability_block_days"));
            List<Double> blockBalacc = new ArrayList<>();
            int btp = 0, btn = 0, bfp = 0, bfn = 0;

            for (int d = from; d <= to; d++) {
                int[] idx = byDate[d];
                if (idx == null || idx.length == 0) continue;
                double[] scores = new double[idx.length], rets = new double[idx.length];
                int di = 0;
                for (int si : idx) {
                    if (m.noise[si]) continue;
                    double s = score(ind, m.z[si]);
                    double p = GaMath.sigmoid(s);
                    int dir = p >= 0.5 + ind.delta ? 1 : p <= 0.5 - ind.delta ? -1 : 0;
                    int actual = m.y[si];
                    total++;
                    brierSum += (p - actual) * (p - actual);
                    if (dir != 0) {
                        nonAbstain++;
                        if (dir == 1 && actual == 1) { tp++; btp++; }
                        else if (dir == 1) { fp++; bfp++; }
                        else if (actual == 0) { tn++; btn++; }
                        else { fn++; bfn++; }
                    }
                    scores[di] = s; rets[di] = m.ret[si]; di++;
                }
                if (di > 1) icByBlock[blockCount++] = GaMath.spearman(
                        Arrays.copyOf(scores, di), Arrays.copyOf(rets, di));
                // 每 blockDays 个交易日汇总一次 BalAcc（跨日累计块内混淆矩阵）
                if ((d - from + 1) % blockDays == 0 || d == to) {
                    blockBalacc.add((blockRate(btp, bfn) + blockRate(btn, bfp)) / 2);
                    btp = btn = bfp = bfn = 0;
                }
            }
            double tpr = GaMath.div(tp, tp + fn, 0);
            double tnr = GaMath.div(tn, tn + fp, 0);
            double balacc = (tpr + tnr) / 2;
            double ic = blockCount == 0 ? 0 : GaMath.mean(icByBlock, 0, blockCount);
            double brier = total == 0 ? 0.25 : brierSum / total;
            double coverage = total == 0 ? 0 : (double) nonAbstain / total;
            double stability = blockBalacc.isEmpty() ? 0
                    : blockBalacc.stream().filter(x -> x > 0.5).count() / (double) blockBalacc.size();

            double a = GaMath.clamp((balacc - 0.5) / cfg.getDouble(h, "fit_a_scale"), 0, 1);
            double b = GaMath.clamp(ic / cfg.getDouble(h, "fit_b_scale"), 0, 1);
            double r = GaMath.clamp((cfg.getDouble(h, "fit_r_base") - brier)
                    / cfg.getDouble(h, "fit_r_scale"), 0, 1);
            double fSeg = (cfg.getDouble(h, "fit_w_a") * a + cfg.getDouble(h, "fit_w_b") * b
                    + cfg.getDouble(h, "fit_w_s") * stability + cfg.getDouble(h, "fit_w_r") * r)
                    * Math.min(1, coverage / Math.max(1e-6, cfg.getDouble(h, "cov_target")));
            if (coverage < cfg.getDouble(h, "cov_min")) fSeg = 0;
            return new Segment(GaMath.clamp(fSeg, 0, 1), balacc, ic, brier, coverage, stability, total, nonAbstain);
        }

        private static double blockRate(int hit, int miss) {
            return hit + miss == 0 ? 0 : (double) hit / (hit + miss);
        }

        /** F = F_va − k×max(0,F_tr−F_va) − c×(Σm/N)。 */
        public Fitness fitness(Individual ind, SampleMatrix m, List<Fold> folds, int h) {
            double tr = 0, va = 0;
            double bv = 0, iv = 0, br = 0, cv = 0, sv = 0;
            for (Fold fold : folds) {
                Segment s1 = segment(ind, m, fold.trainFrom(), fold.trainTo(), h);
                Segment s2 = segment(ind, m, fold.validFrom(), fold.validTo(), h);
                tr += s1.fSeg(); va += s2.fSeg();
                bv += s2.balacc(); iv += s2.ic(); br += s2.brier(); cv += s2.coverage(); sv += s2.stability();
            }
            int k = Math.max(1, folds.size());
            tr /= k; va /= k;
            double f = va - cfg.getDouble(h, "overfit_penalty") * Math.max(0, tr - va)
                    - cfg.getDouble(h, "complexity_penalty") * ((double) ind.active() / ind.m.length);
            return new Fitness(GaMath.clamp(f, -1, 1), tr, va, bv / k, iv / k, br / k, cv / k, sv / k);
        }
    }

    // ================================================================== 遗传算子

    private Individual randomIndividual(Random rnd, int n, int minActive) {
        double[] w = new double[n];
        int[] m = new int[n];
        for (int j = 0; j < n; j++) {
            w[j] = -3 + 6 * rnd.nextDouble();
            m[j] = rnd.nextDouble() < 0.3 ? 1 : 0;
        }
        ensureActive(m, rnd, minActive);
        return new Individual(w, m, -1 + 2 * rnd.nextDouble(), rnd.nextDouble() * 0.15);
    }

    private Individual priorIndividual(Random rnd, int n, int minActive) {
        Individual ind = randomIndividual(rnd, n, minActive);
        for (int j = 0; j < n; j++) if (ind.m[j] == 1) ind.w[j] = rnd.nextGaussian() * 0.4;
        int[] pos = {FeatureKeys.IDX_MOMENTUM_SCORE, FeatureKeys.IDX_TREND_SCORE, FeatureKeys.IDX_RET_21};
        for (int p : pos) if (p >= 0) { ind.m[p] = 1; ind.w[p] = 1.2 + Math.abs(rnd.nextGaussian()) * 0.3; }
        int[] neg = {FeatureKeys.IDX_RISK_SCORE, FeatureKeys.IDX_VOL_20};
        for (int p : neg) if (p >= 0) { ind.m[p] = 1; ind.w[p] = -1.0 - Math.abs(rnd.nextGaussian()) * 0.3; }
        return ind;
    }

    private static void ensureActive(int[] m, Random rnd, int minActive) {
        int active = 0;
        for (int bit : m) active += bit;
        while (active < minActive) {
            int j = rnd.nextInt(m.length);
            if (m[j] == 0) { m[j] = 1; active++; }
        }
    }

    /** BLX-α 实数交叉 + 均匀位交叉。 */
    private Individual crossover(Individual a, Individual b, Random rnd, double alpha, int minActive) {
        int n = a.w.length;
        double[] w = new double[n];
        int[] m = new int[n];
        for (int j = 0; j < n; j++) {
            double lo = Math.min(a.w[j], b.w[j]), hi = Math.max(a.w[j], b.w[j]);
            double range = hi - lo;
            w[j] = GaMath.clamp(lo - alpha * range + rnd.nextDouble() * (range * (1 + 2 * alpha)), -3, 3);
            m[j] = rnd.nextBoolean() ? a.m[j] : b.m[j];
        }
        double bias = GaMath.clamp((a.b + b.b) / 2 + rnd.nextGaussian() * 0.05, -1, 1);
        double delta = GaMath.clamp((a.delta + b.delta) / 2, 0, 0.15);
        ensureActive(m, rnd, minActive);
        Individual child = new Individual(w, m, bias, delta);
        child.parentIds = a.id + "," + b.id;
        return child;
    }

    private void mutate(Individual ind, Random rnd, double pmW, double pmM, double sigma, int minActive) {
        for (int j = 0; j < ind.w.length; j++) {
            if (rnd.nextDouble() < pmW) ind.w[j] = GaMath.clamp(ind.w[j] + rnd.nextGaussian() * sigma, -3, 3);
            if (rnd.nextDouble() < pmM) ind.m[j] = 1 - ind.m[j];
        }
        if (rnd.nextDouble() < pmW) ind.b = GaMath.clamp(ind.b + rnd.nextGaussian() * sigma, -1, 1);
        if (rnd.nextDouble() < pmW) ind.delta = GaMath.clamp(ind.delta + rnd.nextGaussian() * sigma, 0, 0.15);
        ensureActive(ind.m, rnd, minActive);
    }

    private double diversity(List<Individual> pop) {
        if (pop.size() < 2) return 0;
        int n = pop.get(0).w.length;
        // 抽样最多 40 对，避免 O(pop² × N) 拖慢每代
        Random rnd = new Random(20261112L);
        double sum = 0; int pairs = 0;
        for (int k = 0; k < 40; k++) {
            Individual a = pop.get(rnd.nextInt(pop.size())), b = pop.get(rnd.nextInt(pop.size()));
            if (a == b) continue;
            double d = 0;
            for (int j = 0; j < n; j++) {
                double x = a.m[j] == 1 ? a.w[j] : 0, y = b.m[j] == 1 ? b.w[j] : 0;
                d += (x - y) * (x - y);
            }
            sum += Math.sqrt(d) / Math.sqrt(n); pairs++;
        }
        return pairs == 0 ? 0 : sum / pairs;
    }

    private Individual tournament(List<Individual> pop, Random rnd, int k) {
        Individual best = null;
        for (int i = 0; i < k; i++) {
            Individual c = pop.get(rnd.nextInt(pop.size()));
            if (best == null || c.fitness.f() > best.fitness.f()) best = c;
        }
        return best;
    }

    // ================================================================== 进化编排

    public EvolveResult evolve(int h, LocalDate asOf, String runType, Integer popOverride,
                               Integer genOverride, Map<LocalDate, List<FeatureService.Vec>> supplied) {
        int flag = config.globalInt("adjust_flag");
        int popSize = popOverride != null ? popOverride : config.getInt(h, "pop_size");
        int maxGen = genOverride != null ? genOverride : config.getInt(h, "max_gen");
        long seed = config.getLong(h, "random_seed") + h * 1000L + asOf.toEpochDay();
        int minLabeled = config.getInt(h, "min_labeled_days");
        long runId = store.insertRun(h, runType, asOf, seed, popSize);
        List<String> warnings = new ArrayList<>();

        try {
            LocalDate start = asOf.minusDays(Math.max(400, config.globalInt("feature_lookback_days")));
            List<LocalDate> allDates = data.tradeDates(flag, start, asOf);
            if (allDates.size() <= h + minLabeled) {
                throw new IllegalStateException("有标签交易日不足 T_min=" + minLabeled
                        + "（当前 " + Math.max(0, allDates.size() - h) + "），拒绝运行");
            }
            List<LocalDate> labeled = allDates.subList(0, allDates.size() - h);
            Map<LocalDate, List<FeatureService.Vec>> normalized = supplied != null ? supplied
                    : features.loadNormalized(flag, labeled.get(0), labeled.get(labeled.size() - 1));
            Map<String, List<Bar>> bars = data.loadBarsWithScores(flag, start, asOf);
            Map<String, Label> labelIndex = labels.build(h, bars, labeled.get(0), labeled.get(labeled.size() - 1));
            Matrix mx = buildMatrix(h, asOf, flag, labeled, normalized, labelIndex);
            if (mx.m.size() < 1000) {
                throw new IllegalStateException("有效样本不足 1000 条（当前 " + mx.m.size() + "），请先运行 FeatureJob");
            }

            int holdoutDays = config.getInt(h, "holdout_days");
            Split split = split(labeled.size(), h, config.getInt(h, "folds"),
                    config.getInt(h, "train_days"), config.getInt(h, "valid_days"),
                    config.getInt(h, "fold_step"), holdoutDays, config.getInt(h, "min_train_days"));
            warnings.addAll(split.warnings());
            if (split.folds().isEmpty()) throw new IllegalStateException("无法构造滚动验证折");

            FitnessEvaluator eval = new FitnessEvaluator(config, mx.byDate);
            int n = FeatureKeys.N, minActive = config.getInt(h, "min_active_features");
            Random initRnd = new Random(seed);
            List<Individual> pop = new ArrayList<>(popSize);
            List<Map<String, Object>> previous = store.hof(h, config.getInt(h, "candidate_k"));
            int rndCount = (int) Math.round(popSize * config.getDouble(h, "init_random"));
            int priorCount = (int) Math.round(popSize * config.getDouble(h, "init_prior"));
            for (int i = 0; i < rndCount; i++) pop.add(randomIndividual(initRnd, n, minActive));
            for (int i = 0; i < priorCount && pop.size() < popSize; i++) pop.add(priorIndividual(initRnd, n, minActive));
            while (pop.size() < popSize) {
                Individual base = previous.isEmpty() ? randomIndividual(initRnd, n, minActive)
                        : parseIndividual(previous.get(pop.size() % previous.size()), n, minActive);
                mutate(base, initRnd, 0.15, 0.03, 0.25, minActive);
                pop.add(base);
            }

            double bestEver = Double.NEGATIVE_INFINITY;
            int stagnant = 0, generations = 0;
            List<Object[]> genStats = new ArrayList<>();
            int patience = config.getInt(h, "patience");
            double minImprove = config.getDouble(h, "min_improve");
            double diversityThreshold = config.getDouble(h, "diversity_threshold");

            for (int g = 0; g < maxGen; g++) {
                final int generation = g;
                // pop 每代会被替换，parallel lambda 只能捕获当代的 final 引用
                final List<Individual> current = pop;
                // 每个个体派生独立种子，parallel 评估仍可复现
                IntStream.range(0, current.size()).parallel().forEach(i -> {
                    Individual ind = current.get(i);
                    ind.fitness = eval.fitness(ind, mx.m, split.folds(), h);
                    ind.generation = generation;
                    ind.id = i + 1L;
                });
                pop.sort(Comparator.comparingDouble((Individual x) -> x.fitness.f()).reversed());
                double best = pop.get(0).fitness.f();
                double avg = pop.stream().mapToDouble(x -> x.fitness.f()).average().orElse(0);
                double median = pop.get(pop.size() / 2).fitness.f();
                double worst = pop.get(pop.size() - 1).fitness.f();
                double div = diversity(pop);
                boolean lowDiversity = div < diversityThreshold;
                double pmW = config.getDouble(h, "pm_w") * (lowDiversity ? 2 : 1);
                double pmM = config.getDouble(h, "pm_m") * (lowDiversity ? 2 : 1);
                double immigrantRatio = lowDiversity ? 0.20 : config.getDouble(h, "immigrant_ratio");
                int culled = (int) Math.round(pop.size() * config.getDouble(h, "cull_ratio"));
                genStats.add(new Object[]{runId, g, best, avg, median, worst, div, culled,
                        (int) Math.round(popSize * immigrantRatio), pmW,
                        pop.get(0).fitness.balaccValid(), pop.get(0).fitness.icValid()});

                if (best > bestEver + minImprove) { bestEver = best; stagnant = 0; }
                else stagnant++;
                if (stagnant >= patience) { generations = g + 1; break; }
                generations = g + 1;
                if (g == maxGen - 1) break;

                // 精英直接进入下一代；末位淘汰记录数；其余由交叉变异与移民补足
                List<Individual> next = new ArrayList<>(popSize);
                int elites = Math.max(1, (int) Math.round(popSize * config.getDouble(h, "elite_ratio")));
                for (int i = 0; i < elites && i < pop.size(); i++) next.add(pop.get(i).copy());
                Random gr = new Random(seed + g * 7919L);
                double sigma = 0.5 * (1 - (double) g / Math.max(1, maxGen)) + 0.05;
                while (next.size() < popSize - (int) Math.round(popSize * immigrantRatio)) {
                    Individual p1 = tournament(pop, gr, config.getInt(h, "tournament_k"));
                    Individual p2 = tournament(pop, gr, config.getInt(h, "tournament_k"));
                    Individual child;
                    if (gr.nextDouble() < config.getDouble(h, "crossover_pc")) {
                        child = crossover(p1, p2, gr, config.getDouble(h, "blx_alpha"), minActive);
                    } else child = p1.copy();
                    mutate(child, gr, pmW, pmM, sigma, minActive);
                    next.add(child);
                }
                while (next.size() < popSize) next.add(randomIndividual(gr, n, minActive));
                pop = next;
            }

            // 末代重新评估并按 F 排序，取名人堂前 20
            final List<Individual> finalPop = pop;
            IntStream.range(0, finalPop.size()).parallel().forEach(i ->
                    finalPop.get(i).fitness = eval.fitness(finalPop.get(i), mx.m, split.folds(), h));
            pop.sort(Comparator.comparingDouble((Individual x) -> x.fitness.f()).reversed());
            store.insertGenerations(runId, genStats);
            int hofSize = Math.min(config.getInt(h, "hof_size"), pop.size());
            List<Individual> hof = new ArrayList<>();
            long bestId = 0;
            for (int i = 0; i < hofSize; i++) {
                Individual ind = pop.get(i);
                long id = store.insertIndividual(runId, h, ind, "HOF", 0, 0);
                ind.id = id;
                hof.add(ind);
                if (i == 0) bestId = id;
            }
            // 绝对淘汰：F < 0.05 / 严重过拟合 / 验证 BalAcc < 0.5 不得进名人堂
            for (Individual ind : hof) {
                boolean reject = ind.fitness.f() < 0.05
                        || ind.fitness.fTrain() - ind.fitness.fValid() > 0.3
                        || ind.fitness.balaccValid() < 0.5;
                if (reject) {
                    store.setIndividualStatus(ind.id, "REJECTED",
                            ind.fitness.f() < 0.05 ? "LOW_FITNESS" : "OVERFIT");
                }
            }
            LocalDate firstTrain = split.folds().isEmpty() ? null : labeled.get(split.folds().get(0).trainFrom());
            LocalDate lastTrain = split.folds().isEmpty() ? null : labeled.get(split.folds().get(0).trainTo());
            LocalDate firstValid = split.folds().isEmpty() ? null : labeled.get(split.folds().get(split.folds().size() - 1).validFrom());
            LocalDate lastValid = split.folds().isEmpty() ? null : labeled.get(split.folds().get(split.folds().size() - 1).validTo());
            LocalDate holdStart = labeled.get(Math.min(split.holdoutFrom(), labeled.size() - 1));
            LocalDate holdEnd = labeled.get(Math.min(split.holdoutTo(), labeled.size() - 1));
            String featureJson = "[" + String.join(",", FeatureKeys.ALL.stream().map(k -> "\"" + k + "\"").toList()) + "]";
            store.finishRun(runId, "SUCCESS", generations, hof.isEmpty() ? 0 : hof.get(0).fitness.f(), bestId,
                    firstTrain, lastTrain, firstValid, lastValid, holdStart, holdEnd,
                    featureJson, "{\"h\":" + h + ",\"pop\":" + popSize + ",\"gen\":" + maxGen
                            + ",\"folds\":" + split.folds().size() + ",\"samples\":" + mx.m.size() + "}",
                    String.join("；", warnings), null);
            return new EvolveResult(runId, generations, hof.isEmpty() ? 0 : hof.get(0).fitness.f(),
                    bestId, hof, "SUCCESS", String.join("；", warnings), null);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            store.finishRun(runId, "FAILED", 0, 0, 0, null, null, null, null, null, null,
                    null, null, String.join("；", warnings), msg.substring(0, Math.min(1000, msg.length())));
            return new EvolveResult(runId, 0, 0, 0, List.of(), "FAILED", String.join("；", warnings), msg);
        }
    }

    /** 从库里的 chromosome_json 还原个体（热启动）。 */
    public static Individual parseIndividual(Map<String, Object> row, int n, int minActive) {
        long id = row.get("individualId") instanceof Number num ? num.longValue() : 0;
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            Object raw = row.get("chromosomeJson");
            if (raw == null) throw new IllegalArgumentException("chromosome_json 为空");
            com.fasterxml.jackson.databind.JsonNode node = (raw instanceof byte[] b)
                    ? om.readTree(b) : om.readTree(String.valueOf(raw));
            if (node == null || !node.hasNonNull("w") || !node.hasNonNull("m")
                    || !node.hasNonNull("b") || !node.hasNonNull("delta")) {
                throw new IllegalArgumentException("染色体字段缺失");
            }
            double[] w = new double[n]; int[] m = new int[n];
            int i = 0;
            for (com.fasterxml.jackson.databind.JsonNode x : node.get("w")) if (i < n) w[i++] = x.asDouble();
            i = 0;
            for (com.fasterxml.jackson.databind.JsonNode x : node.get("m")) if (i < n) m[i++] = x.asInt();
            Individual ind = new Individual(w, m, node.get("b").asDouble(), node.get("delta").asDouble());
            ind.id = id;
            // 还原适应度：Hold-out 评估用 F 做 softmax 权重，若不还原会退化成均匀权重，
            // 与准入时按真实 F 加权的集成不是同一个模型。
            ind.fitness = new Fitness(dbl(row.get("fitness")), dbl(row.get("fitTrain")),
                    dbl(row.get("fitValid")), dbl(row.get("balaccValid")), dbl(row.get("icValid")),
                    dbl(row.get("brierValid")), dbl(row.get("coverageValid")), dbl(row.get("stabilityValid")));
            ensureActive(m, new Random(ind.id + 1), minActive);
            return ind;
        } catch (Exception e) {
            // 固定种子 7 会让批量解析失败的热启动个体基因型完全相同、多样性崩塌；
            // 改为按个体 id 派生，且异常时记录告警便于发现染色体版本不兼容。
            System.err.println("[GA][WARN] 染色体解析失败 individualId=" + id + "：" + e.getMessage());
            Random r = new Random(7L * 31 + id);
            double[] w = new double[n]; int[] m = new int[n];
            for (int j = 0; j < n; j++) { w[j] = r.nextGaussian(); m[j] = r.nextDouble() < 0.3 ? 1 : 0; }
            ensureActive(m, r, minActive);
            return new Individual(w, m, 0, 0.03);
        }
    }

    private static double dbl(Object o) { return o instanceof Number num ? num.doubleValue() : 0; }
}
