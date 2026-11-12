package com.li.stock.ga;

import java.time.LocalDate;
import java.util.Arrays;

/**
 * GA 子系统的内存中领域模型（规格 4 / 5 / 6）。
 *
 * <p>规格建议根包 {@code com.quant.ga}；本项目 {@code StockApplication} 位于 {@code com.li.stock}
 * 且 {@code @MapperScan} 只扫 {@code com.li.stock.mapper}，为避免修改启动类、保证纯新增模块可被组件扫描到，
 * 这里按现有项目规范使用 {@code com.li.stock.ga}，子包命名沿用规格
 * feature / label / engine / fitness / predict / verify / pool / metric / api / job。</p>
 *
 * <p>所有金额/收益在内存中统一用 double（小数，不是百分数），落库时由 Repository 转换；
 * 适应度样本矩阵用 float 数组以控制内存（规格 14 节）。</p>
 */
public final class GaModel {

    /** 评分表可用列在 Bar.score 中的下标；null 表示该日没有评分记录。 */
    public static final int S_TOTAL = 0, S_VALUE = 1, S_MOMENTUM = 2, S_TREND = 3,
            S_RISK = 4, S_LIQUIDITY = 5, S_RET21 = 6, S_RET63 = 7, S_RET126 = 8,
            S_RET252 = 9, S_VOL60 = 10, S_DD252 = 11, S_AMOUNT_RATIO = 12, S_TURNOVER = 13;
    public static final int SCORE_N = 14;

    /** 一根日线；只含 ≤ 基准日的数据，score 可为 null（评分表历史只有近期）。 */
    public record Bar(
            String code, String market, LocalDate date,
            double open, double high, double low, double close, double preclose,
            double volume, double amount, double amplitude, double pctChg, double turnover,
            int tradeStatus, int isSt,
            Double peTtm, Double pbMrq, Double psTtm, Double pcfNcfTtm,
            double[] score) {

        public boolean tradable() {
            return tradeStatus != 0 && close > 0 && Double.isFinite(close);
        }

        public double scoreOr(int idx, double fallback) {
            return score != null && idx < score.length && Double.isFinite(score[idx]) ? score[idx] : fallback;
        }
    }

    /** 某只股票某日的原始特征向量（未标准化）。 */
    public record FeatureRow(String code, String market, LocalDate date, float[] values,
                             boolean valid, int limitFlag) {
        public FeatureRow {
            if (values == null) values = new float[0];
        }
        @Override public String toString() {
            return "FeatureRow{" + code + "@" + date + ", n=" + values.length + ", valid=" + valid + "}";
        }
    }

    /** 标签：ret_h 与噪声阈值；noise=true 不参与训练与适应度，但参与复盘统计。 */
    public record Label(String code, LocalDate date, int horizon, double ret, double threshold,
                        int y, boolean noise) {}

    /** walk-forward 折：下标区间均为「有标签交易日序列」的下标，左闭右闭。 */
    public record Fold(int trainFrom, int trainTo, int validFrom, int validTo) {
        public int purgeGap() { return validFrom - trainTo - 1; }
    }

    /** 单段评估指标。 */
    public record Segment(double fSeg, double balacc, double ic, double brier,
                          double coverage, double stability, int n, int nEffective) {}

    /** 个体完整适应度。 */
    public record Fitness(double f, double fTrain, double fValid,
                          double balaccValid, double icValid, double brierValid,
                          double coverageValid, double stabilityValid) {
        public static Fitness zero() { return new Fitness(0, 0, 0, 0, 0, 0.25, 0, 0); }
    }

    /** 个体（染色体）：w 权重、m 特征开关、b 偏置、delta 观望半宽。 */
    public static final class Individual {
        public final double[] w;
        public final int[] m;
        public double b;
        public double delta;
        public Fitness fitness = Fitness.zero();
        public long id;
        public int generation;
        public String parentIds = "";
        public transient double[] validScores; // 候选去相关用：验证样本上的打分序列

        public Individual(double[] w, int[] m, double b, double delta) {
            this.w = w; this.m = m; this.b = b; this.delta = delta;
        }

        public int active() {
            int n = 0;
            for (int bit : m) n += bit;
            return n;
        }

        public Individual copy() {
            Individual c = new Individual(w.clone(), m.clone(), b, delta);
            c.fitness = fitness; c.id = id; c.generation = generation; c.parentIds = parentIds;
            return c;
        }

        /** 序列化为染色体 JSON（键 w / m / b / delta）。 */
        public String chromosomeJson() {
            StringBuilder sb = new StringBuilder("{\"w\":[");
            for (int i = 0; i < w.length; i++) { if (i > 0) sb.append(','); sb.append(String.format(java.util.Locale.ROOT, "%.6f", w[i])); }
            sb.append("],\"m\":[");
            for (int i = 0; i < m.length; i++) { if (i > 0) sb.append(','); sb.append(m[i]); }
            sb.append(String.format(java.util.Locale.ROOT, "],\"b\":%.6f,\"delta\":%.6f}", b, delta));
            return sb.toString();
        }

        @Override public String toString() {
            return "Individual{id=" + id + ", F=" + String.format("%.4f", fitness.f()) + ", active=" + active() + "}";
        }
    }

    /**
     * 样本矩阵：一次加载到内存，所有个体共享。
     * z[sample][feature]、y[sample]、ret[sample]、noise[sample]、dateIdx[sample]。
     */
    public static final class SampleMatrix {
        public final float[][] z;
        public final int[] y;
        public final double[] ret;
        public final boolean[] noise;
        public final int[] dateIdx;       // 对应有标签交易日序列下标
        public final String[] code;
        public final int nFeatures;

        public SampleMatrix(float[][] z, int[] y, double[] ret, boolean[] noise,
                            int[] dateIdx, String[] code, int nFeatures) {
            this.z = z; this.y = y; this.ret = ret; this.noise = noise;
            this.dateIdx = dateIdx; this.code = code; this.nFeatures = nFeatures;
        }

        public int size() { return y.length; }
    }

    /** 冠军集里的一个成员。 */
    public record Champion(long individualId, double weight, Individual individual, String versionTag) {}

    /** 市场状态点。 */
    public record MarketPoint(LocalDate date, int stockCnt, double ret1, double ret5, double ret20,
                              double vol20, double breadthUp, double avgTurnover,
                              double index, int regime) {}

    /** 一次任务的执行摘要（供 /api/ga/jobs/status）。 */
    public record JobResult(String job, boolean ok, int processed, String message) {}

    /** 把 float[] 还原为可打印字符串，便于日志排查。 */
    public static String preview(float[] v, int n) {
        float[] p = Arrays.copyOf(v, Math.min(n, v.length));
        return Arrays.toString(p);
    }

    private GaModel() {}
}
