package com.li.stock.quant.indicator;

import java.util.Arrays;

/**
 * 指标库：纯函数，价量序列 → 同长度序列。
 *
 * <p>约定：返回值与入参等长，窗口不足的位置为 {@link Double#NaN}；
 * 所有方法不修改入参，可在多线程中复用（无状态）。</p>
 *
 * <p>与规格 4.3 一一对应：MA / EMA / RSI(Wilder) / MACD / BOLL / ATR / BIAS /
 * HHV / LLV / ROC / VOL_MA / VOL_RATIO / DRAWDOWN_FROM_HIGH。横截面百分位
 * 依赖全市场面板，放在 {@code CrossSection}。</p>
 */
public final class Indicators {

    private Indicators() {
    }

    /** 简单移动平均（窗口内出现 NaN 则该点为 NaN）。 */
    public static double[] sma(float[] a, int n) {
        int len = a.length;
        double[] out = new double[len];
        Arrays.fill(out, Double.NaN);
        if (n <= 0) return out;
        double sum = 0;
        int valid = 0;
        for (int i = 0; i < len; i++) {
            if (!Float.isNaN(a[i])) {
                sum += a[i];
                valid++;
            }
            if (i >= n && !Float.isNaN(a[i - n])) {
                sum -= a[i - n];
                valid--;
            }
            if (i >= n - 1) {
                out[i] = valid == n ? sum / n : Double.NaN;
            }
        }
        return out;
    }

    /** 指数移动平均（首值用第一个有效值播种，与常见行情软件一致）。 */
    public static double[] ema(float[] a, int n) {
        double[] out = new double[a.length];
        Arrays.fill(out, Double.NaN);
        if (n <= 0) return out;
        double alpha = 2d / (n + 1);
        double prev = Double.NaN;
        for (int i = 0; i < a.length; i++) {
            float v = a[i];
            if (Float.isNaN(v)) {
                out[i] = prev;
                continue;
            }
            prev = Double.isNaN(prev) ? v : prev + alpha * (v - prev);
            out[i] = prev;
        }
        return out;
    }

    /** RSI，Wilder 平滑。 */
    public static double[] rsi(float[] close, int n) {
        int len = close.length;
        double[] out = new double[len];
        Arrays.fill(out, Double.NaN);
        if (len <= n || n <= 0) return out;
        double avgGain = 0;
        double avgLoss = 0;
        for (int i = 1; i <= n; i++) {
            double chg = close[i] - close[i - 1];
            if (chg > 0) avgGain += chg;
            else avgLoss -= chg;
        }
        avgGain /= n;
        avgLoss /= n;
        out[n] = rsiValue(avgGain, avgLoss);
        for (int i = n + 1; i < len; i++) {
            double chg = close[i] - close[i - 1];
            double gain = Math.max(0, chg);
            double loss = Math.max(0, -chg);
            avgGain = (avgGain * (n - 1) + gain) / n;
            avgLoss = (avgLoss * (n - 1) + loss) / n;
            out[i] = rsiValue(avgGain, avgLoss);
        }
        return out;
    }

    private static double rsiValue(double avgGain, double avgLoss) {
        if (avgLoss == 0) return avgGain == 0 ? 50 : 100;
        double rs = avgGain / avgLoss;
        return 100 - 100 / (1 + rs);
    }

    /** MACD：dif、dea、hist(柱 = dif - dea)。 */
    public static Macd macd(float[] close, int fast, int slow, int signal) {
        double[] emaFast = ema(close, fast);
        double[] emaSlow = ema(close, slow);
        int len = close.length;
        float[] difF = new float[len];
        for (int i = 0; i < len; i++) {
            difF[i] = (float) (emaFast[i] - emaSlow[i]);
        }
        double[] dea = ema(difF, signal);
        double[] dif = new double[len];
        double[] hist = new double[len];
        for (int i = 0; i < len; i++) {
            dif[i] = difF[i];
            hist[i] = dif[i] - dea[i];
        }
        return new Macd(dif, dea, hist);
    }

    public record Macd(double[] dif, double[] dea, double[] hist) {
    }

    /** 布林带。 */
    public static Boll boll(float[] close, int n, double k) {
        double[] mid = sma(close, n);
        int len = close.length;
        double[] upper = new double[len];
        double[] lower = new double[len];
        Arrays.fill(upper, Double.NaN);
        Arrays.fill(lower, Double.NaN);
        for (int i = n - 1; i < len; i++) {
            if (Double.isNaN(mid[i])) continue;
            double sum = 0;
            for (int j = i - n + 1; j <= i; j++) {
                double d = close[j] - mid[i];
                sum += d * d;
            }
            double std = Math.sqrt(sum / n);
            upper[i] = mid[i] + k * std;
            lower[i] = mid[i] - k * std;
        }
        return new Boll(upper, mid, lower);
    }

    public record Boll(double[] upper, double[] mid, double[] lower) {
    }

    /** ATR，Wilder 平滑。 */
    public static double[] atr(float[] high, float[] low, float[] close, int n) {
        int len = close.length;
        double[] tr = new double[len];
        tr[0] = high[0] - low[0];
        for (int i = 1; i < len; i++) {
            double hl = high[i] - low[i];
            double hc = Math.abs(high[i] - close[i - 1]);
            double lc = Math.abs(low[i] - close[i - 1]);
            tr[i] = Math.max(hl, Math.max(hc, lc));
        }
        double[] out = new double[len];
        Arrays.fill(out, Double.NaN);
        if (len <= n) return out;
        double sum = 0;
        for (int i = 1; i <= n; i++) sum += tr[i];
        out[n] = sum / n;
        for (int i = n + 1; i < len; i++) {
            out[i] = (out[i - 1] * (n - 1) + tr[i]) / n;
        }
        return out;
    }

    /** BIAS = (close - MA) / MA * 100。 */
    public static double[] bias(float[] close, int n) {
        double[] ma = sma(close, n);
        double[] out = new double[close.length];
        for (int i = 0; i < close.length; i++) {
            out[i] = Double.isNaN(ma[i]) || ma[i] == 0 ? Double.NaN
                    : (close[i] - ma[i]) / ma[i] * 100;
        }
        return out;
    }

    /**
     * 区间最高价。
     *
     * @param includeToday true 含当日（窗口 [i-n+1, i]），false 不含当日（[i-n, i-1]）
     */
    public static double[] hhv(float[] a, int n, boolean includeToday) {
        int len = a.length;
        double[] out = new double[len];
        Arrays.fill(out, Double.NaN);
        for (int i = 0; i < len; i++) {
            int end = includeToday ? i : i - 1;
            int start = end - n + 1;
            if (start < 0 || end < 0) continue;
            float m = Float.NEGATIVE_INFINITY;
            boolean any = false;
            for (int j = start; j <= end; j++) {
                if (!Float.isNaN(a[j])) {
                    m = Math.max(m, a[j]);
                    any = true;
                }
            }
            if (any) out[i] = m;
        }
        return out;
    }

    /** 区间最低价，语义同 {@link #hhv}。 */
    public static double[] llv(float[] a, int n, boolean includeToday) {
        int len = a.length;
        double[] out = new double[len];
        Arrays.fill(out, Double.NaN);
        for (int i = 0; i < len; i++) {
            int end = includeToday ? i : i - 1;
            int start = end - n + 1;
            if (start < 0 || end < 0) continue;
            float m = Float.POSITIVE_INFINITY;
            boolean any = false;
            for (int j = start; j <= end; j++) {
                if (!Float.isNaN(a[j])) {
                    m = Math.min(m, a[j]);
                    any = true;
                }
            }
            if (any) out[i] = m;
        }
        return out;
    }

    /** ROC(n) = close / close[i-n] - 1。 */
    public static double[] roc(float[] close, int n) {
        double[] out = new double[close.length];
        Arrays.fill(out, Double.NaN);
        for (int i = n; i < close.length; i++) {
            if (close[i - n] != 0) out[i] = close[i] / close[i - n] - 1;
        }
        return out;
    }

    /** 成交量均线。includeToday=false 表示「不含当日」，用于量比。 */
    public static double[] volMa(float[] volume, int n, boolean includeToday) {
        return includeToday ? sma(volume, n) : shiftSma(volume, n);
    }

    /** 不含当日的 n 日均量：mean(volume[i-n .. i-1])。 */
    private static double[] shiftSma(float[] a, int n) {
        int len = a.length;
        double[] out = new double[len];
        Arrays.fill(out, Double.NaN);
        for (int i = n; i < len; i++) {
            double sum = 0;
            for (int j = i - n; j < i; j++) sum += a[j];
            out[i] = sum / n;
        }
        return out;
    }

    /** 量比 = volume / VOL_MA(n, 不含当日)。 */
    public static double[] volRatio(float[] volume, int n) {
        double[] ma = shiftSma(volume, n);
        double[] out = new double[volume.length];
        for (int i = 0; i < volume.length; i++) {
            out[i] = Double.isNaN(ma[i]) || ma[i] <= 0 ? Double.NaN : volume[i] / ma[i];
        }
        return out;
    }

    /** 距 n 日最高收盘的回撤（负数），close / HHV(close,n) - 1。 */
    public static double[] drawdownFromHigh(float[] close, int n) {
        double[] hhv = hhv(close, n, true);
        double[] out = new double[close.length];
        for (int i = 0; i < close.length; i++) {
            out[i] = Double.isNaN(hhv[i]) || hhv[i] <= 0 ? Double.NaN : close[i] / hhv[i] - 1;
        }
        return out;
    }

    /** 均线斜率：ma[i] - ma[i-lookback]。 */
    public static double[] slope(double[] ma, int lookback) {
        double[] out = new double[ma.length];
        Arrays.fill(out, Double.NaN);
        for (int i = lookback; i < ma.length; i++) {
            if (!Double.isNaN(ma[i]) && !Double.isNaN(ma[i - lookback])) {
                out[i] = ma[i] - ma[i - lookback];
            }
        }
        return out;
    }

    /** a 上穿 b。 */
    public static boolean crossUp(double[] a, double[] b, int i) {
        if (i <= 0 || i >= a.length) return false;
        return !Double.isNaN(a[i]) && !Double.isNaN(b[i])
                && !Double.isNaN(a[i - 1]) && !Double.isNaN(b[i - 1])
                && a[i - 1] <= b[i - 1] && a[i] > b[i];
    }

    /** 价格序列（float）上穿指标序列（double）。 */
    public static boolean crossUp(float[] a, double[] b, int i) {
        if (i <= 0 || i >= a.length || i >= b.length) return false;
        return !Float.isNaN(a[i]) && !Double.isNaN(b[i])
                && !Float.isNaN(a[i - 1]) && !Double.isNaN(b[i - 1])
                && a[i - 1] <= b[i - 1] && a[i] > b[i];
    }

    /** a 下穿 b。 */
    public static boolean crossDown(double[] a, double[] b, int i) {
        if (i <= 0 || i >= a.length) return false;
        return !Double.isNaN(a[i]) && !Double.isNaN(b[i])
                && !Double.isNaN(a[i - 1]) && !Double.isNaN(b[i - 1])
                && a[i - 1] >= b[i - 1] && a[i] < b[i];
    }

    /** 价格序列（float）下穿指标序列（double）。 */
    public static boolean crossDown(float[] a, double[] b, int i) {
        if (i <= 0 || i >= a.length || i >= b.length) return false;
        return !Float.isNaN(a[i]) && !Double.isNaN(b[i])
                && !Float.isNaN(a[i - 1]) && !Double.isNaN(b[i - 1])
                && a[i - 1] >= b[i - 1] && a[i] < b[i];
    }

    /** 最近 days 根（含当日）跌停次数；limitPct 为该股票涨跌停幅度（百分数）。 */
    public static int limitDownCount(float[] pct, int i, int days, double limitPct) {
        int cnt = 0;
        double thr = -limitPct + 0.1;
        for (int j = Math.max(0, i - days + 1); j <= i; j++) {
            if (pct[j] <= thr) cnt++;
        }
        return cnt;
    }

    /** 最近 days 根（含当日）累计涨跌幅（小数）。 */
    public static double cumReturn(float[] close, int i, int days) {
        int j = i - days;
        if (j < 0) return Double.NaN;
        return close[i] / close[j] - 1;
    }

    /** 连续下跌天数（pct<0，含当日）。 */
    public static int consecutiveDown(float[] pct, int i) {
        int n = 0;
        for (int j = i; j >= 0; j--) {
            if (pct[j] < 0) n++;
            else break;
        }
        return n;
    }

    /** 判断 x 是否有效数值。 */
    public static boolean ok(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }
}
