package com.li.stock.quant.strategy;

import com.li.stock.quant.data.MarketPanel;

import java.util.Arrays;

/**
 * 横截面计算：同日、同股票池内的百分位与评分。
 *
 * <p>规格 4.3 的 RANK / PE_RANK / PB_RANK 与 5.x 的「横截面百分位平均 × 100」都在这里实现。
 * 实现按「交易日 → 当日存在数据的行」遍历，不扫全表，491 个交易日约 155 万个单元，
 * 单次计算在几十毫秒量级。</p>
 */
public final class CrossSection {

    private CrossSection() {
    }

    @FunctionalInterface
    public interface ValueAt {
        float value(MarketPanel.Stock stock, int row);
    }

    /** 逐日横截面百分位（0~1，越大越靠前）。无效值返回 NaN。 */
    public static float[][] percentile(MarketPanel panel, Universe universe, ValueAt fn) {
        MarketPanel.Stock[] stocks = panel.stocks();
        float[][] out = new float[stocks.length][];
        for (MarketPanel.Stock s : stocks) {
            if (!universe.stockOk(s.idx)) continue;
            float[] arr = new float[s.rows()];
            Arrays.fill(arr, Float.NaN);
            out[s.idx] = arr;
        }
        int[][] dayRows = panel.dayRows();
        for (int[] rows : dayRows) {
            int n = 0;
            int[] packed = new int[rows.length];
            double[] vals = new double[rows.length];
            for (int p : rows) {
                MarketPanel.Stock s = stocks[MarketPanel.stockOf(p)];
                int r = MarketPanel.rowOf(p);
                if (!universe.rowOk(s.idx, r)) continue;
                float v = fn.value(s, r);
                if (Float.isNaN(v) || Float.isInfinite(v)) continue;
                vals[n] = v;
                packed[n++] = p;
            }
            if (n == 0) continue;
            double[] sorted = Arrays.copyOf(vals, n);
            Arrays.sort(sorted);
            for (int i = 0; i < n; i++) {
                int p = packed[i];
                out[MarketPanel.stockOf(p)][MarketPanel.rowOf(p)] = (float) rank(sorted, vals[i]);
            }
        }
        return out;
    }

    /**
     * 对当日「满足入场条件」的候选，用各分项百分位加权得到 0~100 的评分。
     *
     * @param entry  入场标记
     * @param score  输出评分
     * @param weights 各分项权重（内部归一到 1）
     * @param comps  分项数值，数组顺序与 weights 一致
     */
    public static void scoreCandidates(MarketPanel panel, boolean[][] entry, float[][] score,
                                       double[] weights, float[][][] comps) {
        MarketPanel.Stock[] stocks = panel.stocks();
        double wSum = 0;
        for (double w : weights) wSum += w;
        if (wSum <= 0) wSum = 1;
        int[][] dayRows = panel.dayRows();
        for (int[] rows : dayRows) {
            int n = 0;
            int[] packed = new int[rows.length];
            for (int p : rows) {
                int si = MarketPanel.stockOf(p);
                int ri = MarketPanel.rowOf(p);
                boolean[] entryRow = entry[si];
                if (entryRow == null || !entryRow[ri]) continue;
                boolean all = true;
                for (float[][] comp : comps) {
                    float[] compRow = comp[si];
                    if (compRow == null) {
                        all = false;
                        break;
                    }
                    float v = compRow[ri];
                    if (Float.isNaN(v) || Float.isInfinite(v)) {
                        all = false;
                        break;
                    }
                }
                if (!all) continue;
                packed[n++] = p;
            }
            if (n == 0) continue;
            double[] acc = new double[n];
            for (int c = 0; c < comps.length; c++) {
                double[] vals = new double[n];
                for (int i = 0; i < n; i++) {
                    int p = packed[i];
                    vals[i] = comps[c][MarketPanel.stockOf(p)][MarketPanel.rowOf(p)];
                }
                double[] sorted = vals.clone();
                Arrays.sort(sorted);
                double w = weights[c] / wSum;
                for (int i = 0; i < n; i++) {
                    acc[i] += w * rank(sorted, vals[i]);
                }
            }
            for (int i = 0; i < n; i++) {
                int p = packed[i];
                score[MarketPanel.stockOf(p)][MarketPanel.rowOf(p)] = (float) (acc[i] * 100);
            }
        }
        // 没有参与排序的入场信号给一个兜底分，避免 0 分被当成无效
        for (MarketPanel.Stock s : stocks) {
            float[] sc = score[s.idx];
            if (sc == null) continue;
            for (int i = 0; i < sc.length; i++) {
                if (entry[s.idx][i] && sc[i] == 0f) sc[i] = 50f;
            }
        }
    }

    /** 百分位：并列取平均名次，n=1 时 0.5，取值 0~1。 */
    private static double rank(double[] sorted, double v) {
        int n = sorted.length;
        if (n <= 1) return 0.5;
        int lo = lowerBound(sorted, v);
        int hi = upperBound(sorted, v);
        double pos = (lo + hi - 1) / 2.0;
        return pos / (n - 1);
    }

    private static int lowerBound(double[] a, double v) {
        int lo = 0, hi = a.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid] < v) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    private static int upperBound(double[] a, double v) {
        int lo = 0, hi = a.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid] <= v) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }
}
