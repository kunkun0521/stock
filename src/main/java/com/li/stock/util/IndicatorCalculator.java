package com.li.stock.util;

import com.li.stock.domain.model.Bar;
import com.li.stock.domain.vo.KlinePointVO;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 常用技术指标计算：MA、EMA、MACD、RSI、BOLL。
 * <p>全部基于时间升序的收盘价序列，指标在样本不足时返回 null（不参与前端绘制）。</p>
 */
public final class IndicatorCalculator {

    /** 价格类指标保留 3 位小数，足够展示且不臃肿。 */
    private static final int PRICE_SCALE = 3;

    private IndicatorCalculator() {
    }

    /**
     * 把 K 线数据转换为带指标的图表点。
     *
     * @param bars          时间升序的 K 线
     * @param withIndicators 是否计算技术指标
     */
    public static List<KlinePointVO> toPoints(List<Bar> bars, boolean withIndicators) {
        int n = bars.size();
        if (n == 0) {
            return List.of();
        }

        double[] close = new double[n];
        double last = 0d;
        for (int i = 0; i < n; i++) {
            BigDecimal c = bars.get(i).close();
            if (c != null) {
                last = c.doubleValue();
            }
            close[i] = last;
        }

        double[] ma5 = null;
        double[] ma10 = null;
        double[] ma20 = null;
        double[] ma60 = null;
        double[] dif = null;
        double[] dea = null;
        double[] macdHist = null;
        double[] rsi6 = null;
        double[] rsi12 = null;
        double[] rsi24 = null;
        double[] bollUpper = null;
        double[] bollMid = null;
        double[] bollLower = null;

        if (withIndicators) {
            ma5 = sma(close, 5);
            ma10 = sma(close, 10);
            ma20 = sma(close, 20);
            ma60 = sma(close, 60);

            double[][] macd = macd(close, 12, 26, 9);
            dif = macd[0];
            dea = macd[1];
            macdHist = macd[2];

            rsi6 = rsi(close, 6);
            rsi12 = rsi(close, 12);
            rsi24 = rsi(close, 24);

            double[][] boll = boll(close, 20, 2.0);
            bollUpper = boll[0];
            bollMid = boll[1];
            bollLower = boll[2];
        }

        List<KlinePointVO> points = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Bar bar = bars.get(i);
            points.add(new KlinePointVO(
                    bar.tradeDate(), bar.open(), bar.high(), bar.low(), bar.close(), bar.preclose(),
                    bar.volume(), bar.amount(), bar.pctChg(), bar.chgAmt(), bar.turnover(), bar.amplitude(),
                    bar.isSt(), bar.tradeStatus(),
                    withIndicators ? Numbers.round(ma5[i], PRICE_SCALE) : null,
                    withIndicators ? Numbers.round(ma10[i], PRICE_SCALE) : null,
                    withIndicators ? Numbers.round(ma20[i], PRICE_SCALE) : null,
                    withIndicators ? Numbers.round(ma60[i], PRICE_SCALE) : null,
                    withIndicators ? Numbers.round(dif[i], 4) : null,
                    withIndicators ? Numbers.round(dea[i], 4) : null,
                    withIndicators ? Numbers.round(macdHist[i], 4) : null,
                    withIndicators ? Numbers.round(rsi6[i], 2) : null,
                    withIndicators ? Numbers.round(rsi12[i], 2) : null,
                    withIndicators ? Numbers.round(rsi24[i], 2) : null,
                    withIndicators ? Numbers.round(bollUpper[i], PRICE_SCALE) : null,
                    withIndicators ? Numbers.round(bollMid[i], PRICE_SCALE) : null,
                    withIndicators ? Numbers.round(bollLower[i], PRICE_SCALE) : null));
        }
        return points;
    }

    /** 简单移动平均。 */
    public static double[] sma(double[] values, int period) {
        double[] out = new double[values.length];
        double sum = 0d;
        for (int i = 0; i < values.length; i++) {
            sum += values[i];
            if (i >= period) {
                sum -= values[i - period];
            }
            out[i] = i >= period - 1 ? sum / period : Double.NaN;
        }
        return out;
    }

    /** 指数移动平均，首值作为种子。 */
    public static double[] ema(double[] values, int period) {
        double[] out = new double[values.length];
        if (values.length == 0) {
            return out;
        }
        double k = 2d / (period + 1);
        double prev = values[0];
        out[0] = prev;
        for (int i = 1; i < values.length; i++) {
            prev = values[i] * k + prev * (1 - k);
            out[i] = prev;
        }
        return out;
    }

    /**
     * MACD。
     *
     * @return [DIF, DEA, MACD 柱]（MACD 柱 = 2 ×（DIF - DEA））
     */
    public static double[][] macd(double[] close, int fast, int slow, int signal) {
        double[] emaFast = ema(close, fast);
        double[] emaSlow = ema(close, slow);
        double[] dif = new double[close.length];
        for (int i = 0; i < close.length; i++) {
            dif[i] = emaFast[i] - emaSlow[i];
        }
        double[] dea = ema(dif, signal);
        double[] hist = new double[close.length];
        for (int i = 0; i < close.length; i++) {
            hist[i] = (dif[i] - dea[i]) * 2;
        }
        return new double[][]{dif, dea, hist};
    }

    /** RSI，采用 Wilder 平滑。 */
    public static double[] rsi(double[] close, int period) {
        double[] out = new double[close.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = Double.NaN;
        }
        if (close.length <= period) {
            return out;
        }
        double gain = 0d;
        double loss = 0d;
        for (int i = 1; i <= period; i++) {
            double diff = close[i] - close[i - 1];
            if (diff >= 0) {
                gain += diff;
            } else {
                loss -= diff;
            }
        }
        double avgGain = gain / period;
        double avgLoss = loss / period;
        out[period] = rsiValue(avgGain, avgLoss);
        for (int i = period + 1; i < close.length; i++) {
            double diff = close[i] - close[i - 1];
            double up = Math.max(diff, 0d);
            double down = Math.max(-diff, 0d);
            avgGain = (avgGain * (period - 1) + up) / period;
            avgLoss = (avgLoss * (period - 1) + down) / period;
            out[i] = rsiValue(avgGain, avgLoss);
        }
        return out;
    }

    private static double rsiValue(double avgGain, double avgLoss) {
        if (avgLoss == 0d) {
            return 100d;
        }
        double rs = avgGain / avgLoss;
        return 100d - 100d / (1 + rs);
    }

    /**
     * 布林带（总体标准差口径）。
     *
     * @return [上轨, 中轨, 下轨]
     */
    public static double[][] boll(double[] close, int period, double multiplier) {
        int n = close.length;
        double[] mid = sma(close, period);
        double[] upper = new double[n];
        double[] lower = new double[n];
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(mid[i])) {
                upper[i] = Double.NaN;
                lower[i] = Double.NaN;
                continue;
            }
            double sum = 0d;
            for (int j = i - period + 1; j <= i; j++) {
                double d = close[j] - mid[i];
                sum += d * d;
            }
            double sd = Math.sqrt(sum / period);
            upper[i] = mid[i] + multiplier * sd;
            lower[i] = mid[i] - multiplier * sd;
        }
        return new double[][]{upper, mid, lower};
    }
}
