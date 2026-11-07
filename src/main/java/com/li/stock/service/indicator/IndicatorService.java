package com.li.stock.service.indicator;

import com.li.stock.domain.Bar;
import com.li.stock.domain.Indicator;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 规格第四节：一次性算出整段 IndicatorSet 列表。
 * <p>第 i 份指标只用 bars[0..i] 计算，保证「策略在 t 日只能读取 t-1 及以前指标」；
 * 防前视由 Backtester 的索引对齐（planOrders 传 indicators[t-1]）保证。</p>
 * <p>停牌日也要产出指标（用最近有效收盘价填充），保证索引与 bars 一一对应。</p>
 */
@Service
public class IndicatorService {

    /**
     * @param bars 时间升序的完整日线（含预热段）
     * @return 与 bars 等长、位置一一对应的指标列表
     */
    public List<Indicator> compute(List<Bar> bars) {
        List<Indicator> out = new ArrayList<>(bars.size());
        List<Double> tr = new ArrayList<>();
        List<Double> gains = new ArrayList<>();
        List<Double> losses = new ArrayList<>();

        for (int i = 0; i < bars.size(); i++) {
            Bar b = bars.get(i);
            double close = b.close() > 0 ? b.close() : lastClose(bars, i);
            double prevClose = i > 0 ? effectiveClose(bars, i - 1) : close;

            // TR = max(high-low, |high-preclose|, |low-preclose|)
            double high = b.high() > 0 ? b.high() : close;
            double low = b.low() > 0 ? b.low() : close;
            double preclose = b.preclose() > 0 ? b.preclose() : prevClose;
            double trueRange = Math.max(high - low,
                    Math.max(Math.abs(high - preclose), Math.abs(low - preclose)));
            tr.add(trueRange);

            double change = close - prevClose;
            gains.add(Math.max(change, 0d));
            losses.add(Math.max(-change, 0d));

            double ma5 = sma(bars, i, 5, close);
            double ma10 = sma(bars, i, 10, close);
            double ma20 = sma(bars, i, 20, close);
            double ma20Prev = i >= 5 ? sma(bars, i - 5, 20, close) : ma20;
            double ma20Slope = ma20Prev > 0 ? ma20 / ma20Prev - 1 : 0d;

            double atr14 = avgTail(tr, 14);
            double atrPct = close > 0 ? atr14 / close : 0d;
            double rsi14 = rsi(gains, losses, 14);
            double percentB = bollingerPctB(bars, i, 20, close);
            double volRatio = volumeRatio(bars, i, 5);
            double ret5 = i >= 5 && effectiveClose(bars, i - 5) > 0
                    ? close / effectiveClose(bars, i - 5) - 1 : 0d;
            double ret20 = i >= 20 && effectiveClose(bars, i - 20) > 0
                    ? close / effectiveClose(bars, i - 20) - 1 : 0d;
            double devMa20 = ma20 > 0 ? close / ma20 - 1 : 0d;
            double avgAmp20 = amplitudeAvg(bars, i, 20);

            out.add(new Indicator(b.date(), close, atrPct, ma5, ma10, ma20, ma20Slope,
                    rsi14, percentB, volRatio, ret5, ret20, devMa20, avgAmp20));
        }
        return out;
    }

    /** 停牌日用最近一根有效收盘价，避免 0 值污染均线。 */
    private static double effectiveClose(List<Bar> bars, int i) {
        double c = bars.get(i).close();
        return c > 0 ? c : lastClose(bars, i);
    }

    private static double lastClose(List<Bar> bars, int i) {
        for (int j = i; j >= 0; j--) {
            if (bars.get(j).close() > 0) return bars.get(j).close();
        }
        return 0d;
    }

    private static double sma(List<Bar> bars, int end, int window, double fallback) {
        int from = Math.max(0, end - window + 1);
        double sum = 0d;
        int n = 0;
        for (int j = from; j <= end; j++) {
            double c = effectiveClose(bars, j);
            if (c > 0) { sum += c; n++; }
        }
        return n > 0 ? sum / n : fallback;
    }

    private static double avgTail(List<Double> values, int window) {
        int from = Math.max(0, values.size() - window);
        double sum = 0d;
        for (int i = from; i < values.size(); i++) sum += values.get(i);
        int n = values.size() - from;
        return n > 0 ? sum / n : 0d;
    }

    /** Wilder 平滑的 RSI14；样本不足或振幅为 0 时返回 50（中性）。 */
    private static double rsi(List<Double> gains, List<Double> losses, int window) {
        if (gains.size() < window) return 50d;
        double avgGain = avgTail(gains, window);
        double avgLoss = avgTail(losses, window);
        if (avgLoss <= 0) return avgGain > 0 ? 100d : 50d;
        double rs = avgGain / avgLoss;
        return 100 - 100 / (1 + rs);
    }

    /** %B = (close - (MA20 - 2σ)) / (4σ)，σ=0 时返回 0.5。 */
    private static double bollingerPctB(List<Bar> bars, int end, int window, double close) {
        int from = Math.max(0, end - window + 1);
        double sum = 0d;
        int n = 0;
        for (int j = from; j <= end; j++) {
            double c = effectiveClose(bars, j);
            if (c > 0) { sum += c; n++; }
        }
        if (n < 2) return 0.5d;
        double mean = sum / n;
        double variance = 0d;
        for (int j = from; j <= end; j++) {
            double c = effectiveClose(bars, j);
            if (c > 0) variance += (c - mean) * (c - mean);
        }
        double sd = Math.sqrt(variance / n);
        if (sd <= 0) return 0.5d;
        return (close - (mean - 2 * sd)) / (4 * sd);
    }

    private static double volumeRatio(List<Bar> bars, int end, int window) {
        double prevSum = 0d;
        int n = 0;
        for (int j = Math.max(0, end - window); j < end; j++) {
            double v = bars.get(j).volume();
            if (v > 0) { prevSum += v; n++; }
        }
        double avg = n > 0 ? prevSum / n : 0d;
        return avg > 0 ? bars.get(end).volume() / avg : 1d;
    }

    /** amplitude 在库中是百分数（如 1.4959 表示 1.4959%），此处保持原口径供展示。 */
    private static double amplitudeAvg(List<Bar> bars, int end, int window) {
        double sum = 0d;
        int n = 0;
        for (int j = Math.max(0, end - window + 1); j <= end; j++) {
            sum += bars.get(j).amplitude();
            n++;
        }
        return n > 0 ? sum / n : 0d;
    }
}
