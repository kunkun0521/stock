package com.li.stock.util;

import com.li.stock.domain.model.TBar;
import com.li.stock.domain.vo.StockDailyVO;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 把 stock_daily 日线转换为做T回测所需的 TBar，并补齐均线、量能、支撑压力。
 * <p>输入必须是时间升序；指标样本不足时用 {@link Double#NaN} 表示，
 * 策略引擎会跳过对应过滤条件，对外输出时由 Numbers.round 转成 null。</p>
 */
public final class TIndicatorUtil {

    private static final int LOOKBACK = 20;

    private TIndicatorUtil() {
    }

    public static List<TBar> toBars(List<StockDailyVO> rows) {
        int n = rows.size();
        List<TBar> bars = new ArrayList<>(n);
        double[] close = new double[n];
        double[] volume = new double[n];

        double lastClose = 0d;
        for (int i = 0; i < n; i++) {
            StockDailyVO row = rows.get(i);
            double c = d(row.close(), lastClose);
            if (c > 0) {
                lastClose = c;
            }
            close[i] = lastClose;
            volume[i] = row.volume() == null ? 0d : row.volume().doubleValue();
        }

        double[] ma5 = sma(close, 5);
        double[] ma10 = sma(close, 10);
        double[] ma20 = sma(close, 20);
        double[] volMa5 = sma(volume, 5);

        for (int i = 0; i < n; i++) {
            StockDailyVO row = rows.get(i);
            double c = close[i];
            double o = d(row.open(), c);
            double h = d(row.high(), c);
            double l = d(row.low(), c);
            int from = Math.max(0, i - LOOKBACK + 1);
            double support = Double.POSITIVE_INFINITY;
            double resistance = 0d;
            for (int j = from; j <= i; j++) {
                StockDailyVO past = rows.get(j);
                double pastLow = d(past.low(), close[j]);
                double pastHigh = d(past.high(), close[j]);
                if (pastLow > 0) support = Math.min(support, pastLow);
                resistance = Math.max(resistance, pastHigh);
            }
            if (Double.isInfinite(support)) {
                support = l;
            }
            boolean tradable = c > 0 && (row.tradeStatus() == null || row.tradeStatus() != 0);
            bars.add(new TBar(row.tradeDate(), o, h, l, c, d(row.preclose(), c),
                    ma5[i], ma10[i], ma20[i], volMa5[i], volume[i],
                    d(row.turnover(), 0d), d(row.amplitude(), 0d), d(row.pctChg(), 0d),
                    support, resistance, tradable));
        }
        return bars;
    }

    /** 简单移动平均，样本不足返回 NaN。 */
    private static double[] sma(double[] values, int window) {
        double[] out = new double[values.length];
        double sum = 0d;
        for (int i = 0; i < values.length; i++) {
            sum += values[i];
            if (i >= window) {
                sum -= values[i - window];
            }
            out[i] = i >= window - 1 ? sum / window : Double.NaN;
        }
        return out;
    }

    private static double d(BigDecimal value, double fallback) {
        return value == null ? fallback : value.doubleValue();
    }
}
