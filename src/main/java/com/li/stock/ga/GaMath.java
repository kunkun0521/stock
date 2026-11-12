package com.li.stock.ga;

import java.util.Arrays;

/** GA 子系统共用的数学工具：统计、标准化、相关、平滑、激活函数。 */
public final class GaMath {

    public static final double EPS = 1e-9;

    public static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
    public static boolean finite(double v) { return Double.isFinite(v); }
    public static double sigmoid(double s) {
        if (s >= 0) return 1.0 / (1.0 + Math.exp(-s));
        double e = Math.exp(s);
        return e / (1.0 + e);
    }
    public static double logit(double p) {
        double q = clamp(p, 1e-6, 1 - 1e-6);
        return Math.log(q / (1 - q));
    }

    public static double mean(double[] v) { return mean(v, 0, v.length); }

    public static double mean(double[] v, int from, int to) {
        if (to <= from) return 0;
        double s = 0; int n = 0;
        for (int i = from; i < to; i++) if (finite(v[i])) { s += v[i]; n++; }
        return n == 0 ? 0 : s / n;
    }

    /** 样本标准差（除以 n−1），用于年化波动率与 ICIR。 */
    public static double stdSample(double[] v) { return stdSample(v, 0, v.length); }

    public static double stdSample(double[] v, int from, int to) {
        double m = mean(v, from, to);
        double s = 0; int n = 0;
        for (int i = from; i < to; i++) if (finite(v[i])) { s += (v[i] - m) * (v[i] - m); n++; }
        return n < 2 ? 0 : Math.sqrt(s / (n - 1));
    }

    /** 总体标准差（除以 n），用于布林带。 */
    public static double stdPop(double[] v, int from, int to) {
        double m = mean(v, from, to);
        double s = 0; int n = 0;
        for (int i = from; i < to; i++) if (finite(v[i])) { s += (v[i] - m) * (v[i] - m); n++; }
        return n == 0 ? 0 : Math.sqrt(s / n);
    }

    /** 已排序数组上的线性插值分位数。 */
    public static double quantileSorted(double[] sorted, double q) {
        if (sorted.length == 0) return 0;
        double pos = clamp(q, 0, 1) * (sorted.length - 1);
        int lo = (int) Math.floor(pos), hi = (int) Math.ceil(pos);
        return lo == hi ? sorted[lo] : sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo);
    }

    /**
     * 规格 4.2：截面 1%/99% 缩尾后 z-score；
     * 缺失率 &gt; missingRatio 时该日该特征整体置 0（返回 true 表示触发了整体置 0）。
     */
    public static boolean winsorizeZ(double[] values, boolean[] valid, double q,
                                     double missingRatio, float[] out) {
        int n = values.length, nValid = 0;
        double[] pool = new double[n];
        for (int i = 0; i < n; i++) {
            if (valid[i] && finite(values[i])) pool[nValid++] = values[i];
        }
        if (nValid == 0 || (double) (n - nValid) / n > missingRatio) {
            Arrays.fill(out, 0f);
            return true;
        }
        double[] sorted = Arrays.copyOf(pool, nValid);
        Arrays.sort(sorted);
        double lo = quantileSorted(sorted, q), hi = quantileSorted(sorted, 1 - q);
        double m = 0;
        for (int i = 0; i < n; i++) {
            if (valid[i] && finite(values[i])) out[i] = (float) clamp(values[i], lo, hi);
            else out[i] = 0f;
        }
        for (int i = 0; i < n; i++) m += out[i];
        m /= n;
        double ss = 0;
        for (int i = 0; i < n; i++) ss += (out[i] - m) * (out[i] - m);
        double sd = Math.sqrt(ss / Math.max(1, n - 1));
        if (sd < EPS) { Arrays.fill(out, 0f); return false; }
        for (int i = 0; i < n; i++) out[i] = (float) ((out[i] - m) / sd);
        return false;
    }

    /** 时序 z-score：用截至当前（含）的 window 根历史，避免未来函数。 */
    public static double timeSeriesZ(double[] series, int idx, int window) {
        int from = Math.max(0, idx - window + 1);
        double m = mean(series, from, idx + 1);
        double sd = stdSample(series, from, idx + 1);
        return sd < EPS ? 0 : (series[idx] - m) / sd;
    }

    /** 平均秩（并列取平均），Spearman 相关的基础。 */
    public static double[] ranks(double[] v) {
        int n = v.length;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> Double.compare(v[a], v[b]));
        double[] r = new double[n];
        int i = 0;
        while (i < n) {
            int j = i + 1;
            while (j < n && Double.compare(v[idx[j]], v[idx[i]]) == 0) j++;
            double avg = (i + j - 1) / 2.0 + 1;
            for (int k = i; k < j; k++) r[idx[k]] = avg;
            i = j;
        }
        return r;
    }

    /** Pearson 相关；长度不足或零方差返回 0。 */
    public static double pearson(double[] a, double[] b) {
        int n = Math.min(a.length, b.length);
        if (n < 2) return 0;
        double ma = mean(a, 0, n), mb = mean(b, 0, n);
        double num = 0, da = 0, db = 0;
        for (int i = 0; i < n; i++) {
            double x = a[i] - ma, y = b[i] - mb;
            num += x * y; da += x * x; db += y * y;
        }
        return da < EPS || db < EPS ? 0 : num / Math.sqrt(da * db);
    }

    public static double spearman(double[] a, double[] b) { return pearson(ranks(a), ranks(b)); }

    /** EMA：以首段 SMA 作为种子，返回同长数组。 */
    public static double[] ema(double[] v, int period) {
        double[] out = new double[v.length];
        if (v.length == 0) return out;
        double alpha = 2.0 / (period + 1);
        double seed = mean(v, 0, Math.min(period, v.length));
        out[0] = seed;
        for (int i = 1; i < v.length; i++) out[i] = alpha * v[i] + (1 - alpha) * out[i - 1];
        return out;
    }

    /** Wilder 平滑（RSI / ATR / ADX 用）。 */
    public static double[] wilder(double[] v, int period) {
        double[] out = new double[v.length];
        if (v.length == 0) return out;
        double seed = mean(v, 0, Math.min(period, v.length));
        out[0] = seed;
        for (int i = 1; i < v.length; i++) out[i] = (out[i - 1] * (period - 1) + v[i]) / period;
        return out;
    }

    public static double[] softmax(double[] v, double temperature) {
        double[] out = new double[v.length];
        if (v.length == 0) return out;
        double max = v[0];
        for (double x : v) max = Math.max(max, x);
        double t = Math.max(1e-6, temperature), sum = 0;
        for (int i = 0; i < v.length; i++) { out[i] = Math.exp((v[i] - max) / t); sum += out[i]; }
        for (int i = 0; i < v.length; i++) out[i] /= sum;
        return out;
    }

    /** 小样本安全除法。 */
    public static double div(double a, double b, double fallback) {
        return Math.abs(b) < EPS || !finite(a) || !finite(b) ? fallback : a / b;
    }

    public static double round6(double v) { return finite(v) ? Math.round(v * 1e6) / 1e6 : 0; }

    private GaMath() {}
}
