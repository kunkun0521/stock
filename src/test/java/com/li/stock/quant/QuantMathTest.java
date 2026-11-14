package com.li.stock.quant;

import com.li.stock.quant.analytics.EventRow;
import com.li.stock.quant.analytics.EventStudy;
import com.li.stock.quant.analytics.Performance;
import com.li.stock.quant.backtest.EquityPoint;
import com.li.stock.quant.backtest.TradeRecord;
import com.li.stock.quant.data.MarketPanel;
import com.li.stock.quant.indicator.Indicators;
import com.li.stock.quant.strategy.SignalSeries;
import com.li.stock.quant.strategy.Universe;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 量化平台核心数学的单元测试：指标、绩效、事件研究。
 *
 * <p>指标用可手算的序列验证；事件研究用固定价格的合成面板验证「T+1 开盘买入、
 * T+h 收盘卖出、扣双边成本」的口径；绩效验证净值恒等与换手率分母。</p>
 */
class QuantMathTest {

    @Test
    void smaMatchesHandCalculation() {
        float[] close = {1, 2, 3, 4, 5};
        double[] ma3 = Indicators.sma(close, 3);
        assertTrue(Double.isNaN(ma3[1]));
        assertEquals(2.0, ma3[2], 1e-9);
        assertEquals(3.0, ma3[3], 1e-9);
        assertEquals(4.0, ma3[4], 1e-9);
    }

    @Test
    void rsiIsHundredWhenOnlyGains() {
        float[] close = new float[30];
        for (int i = 0; i < close.length; i++) close[i] = 10 + i;
        double[] rsi = Indicators.rsi(close, 14);
        assertEquals(100.0, rsi[20], 1e-9);
    }

    @Test
    void bollAndAtrAreSane() {
        float[] close = new float[40];
        float[] high = new float[40];
        float[] low = new float[40];
        for (int i = 0; i < 40; i++) {
            close[i] = 10 + i * 0.5f;
            high[i] = close[i] + 1;
            low[i] = close[i] - 1;
        }
        Indicators.Boll boll = Indicators.boll(close, 20, 2);
        assertTrue(boll.upper()[25] > boll.mid()[25]);
        assertTrue(boll.mid()[25] > boll.lower()[25]);
        double[] atr = Indicators.atr(high, low, close, 14);
        assertEquals(2.0, atr[20], 1e-6);
    }

    @Test
    void performanceComputesReturnDrawdownAndTurnover() {
        List<EquityPoint> points = new ArrayList<>();
        points.add(new EquityPoint(LocalDate.of(2025, 1, 2), 1.0, 1_000_000, 0, 1.0, 0, 0));
        points.add(new EquityPoint(LocalDate.of(2025, 1, 3), 1.10, 1_100_000, 0, 1.0, 0, 0));
        points.add(new EquityPoint(LocalDate.of(2025, 1, 6), 0.99, 990_000, 0, 1.0, -0.1, 0));
        List<TradeRecord> trades = List.of(
                new TradeRecord(1, "000001", "SZ", LocalDate.of(2024, 12, 2), 10, LocalDate.of(2024, 12, 20),
                        11, 10000, 10000, 0.10, 14, "信号出场", 80, -0.02, 0.15, 100_000));
        Map<String, Object> m = Performance.compute(points, trades, 0.02);
        assertEquals(-0.01, (double) m.get("totalReturn"), 1e-9);
        assertEquals(-0.1, (double) m.get("maxDrawdown"), 1e-9);
        assertEquals(1.0, (double) m.get("winRate"), 1e-9);
        double turnover = (double) m.get("turnover");
        assertTrue(turnover > 1 && turnover < 100, "换手率应以平均资金为分母，实际 " + turnover);
    }

    @Test
    void eventStudyUsesNextOpenAndAppliesCosts() {
        // 1 只股票、10 个交易日，价格 10,11,12,...；T=第 0 日发信号，T+1 开盘 11 买入，T+2 收盘 12 卖出
        int n = 10;
        LocalDate[] dates = new LocalDate[n];
        int[] day = new int[n];
        float[] open = new float[n];
        float[] high = new float[n];
        float[] low = new float[n];
        float[] close = new float[n];
        float[] preclose = new float[n];
        float[] pct = new float[n];
        float[] volume = new float[n];
        float[] amount = new float[n];
        float[] pe = new float[n];
        float[] pb = new float[n];
        boolean[] tradable = new boolean[n];
        boolean[] st = new boolean[n];
        float[] bench = new float[n];
        for (int i = 0; i < n; i++) {
            dates[i] = LocalDate.of(2025, 1, 2).plusDays(i);
            day[i] = i;
            open[i] = (float) (10 * (1 + 0.01 * i));
            high[i] = open[i] + 0.05f;
            low[i] = open[i] - 0.05f;
            close[i] = open[i];
            preclose[i] = i == 0 ? open[i] : close[i - 1];
            pct[i] = 1.0f;
            volume[i] = 1_000_000;
            amount[i] = 20_000_000;
            pe[i] = 10;
            pb[i] = 1;
            tradable[i] = true;
            bench[i] = (float) (1 + i * 0.01);
        }
        MarketPanel.Stock stock = new MarketPanel.Stock(0, "600000", "SH", day, open, high, low, close,
                preclose, pct, volume, amount, pe, pb, tradable, st, true);
        int[][] dayRows = new int[n][];
        for (int i = 0; i < n; i++) dayRows[i] = new int[]{MarketPanel.pack(0, i)};
        MarketPanel panel = new MarketPanel("TEST", dates, new MarketPanel.Stock[]{stock}, dayRows,
                1_000_000, System.currentTimeMillis(), 1,
                new double[]{1, 1.01, 1.02, 1.03, 1.04, 1.05, 1.06, 1.07, 1.08, 1.09},
                new MarketPanel.Quality(1, 0, 0, 0, 0, 0, 1));
        Universe universe = new Universe(panel, false, Set.of("MAIN"), true, 0);

        SignalSeries sig = new SignalSeries(0, n, false);
        sig.entry[0] = true;
        sig.score[0] = 90;
        List<EventRow> rows = EventStudy.run(panel, universe, List.of(sig), List.of(1),
                0.1, 0.025, 0.05, 0.001);
        EventRow all = rows.stream().filter(r -> "ALL".equals(r.splitKey())).findFirst().orElseThrow();
        assertEquals(1, all.cnt());
        double expected = (close[2] * 0.999) / (open[1] * 1.001) - 1
                - (0.025 * 2 + 0.05 + 0.001 * 2) / 100;
        assertEquals(expected, all.meanRet(), 1e-9);
        assertTrue(all.excessMean() != 0);
    }

    @Test
    void performanceSliceRebasesNav() {
        List<EquityPoint> points = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            points.add(new EquityPoint(LocalDate.of(2025, 1, 2).plusDays(i), 1 + i * 0.1, 0, 0, 1 + i * 0.05, 0, 0));
        }
        List<EquityPoint> slice = Performance.slice(points, 4, 9);
        assertEquals(6, slice.size());
        assertEquals(1.0, slice.get(0).nav(), 1e-9);
        assertEquals(1.9 / 1.4, slice.get(5).nav(), 1e-9);
    }
}
