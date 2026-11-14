package com.li.stock.quant.strategy.impl;

import com.li.stock.quant.data.MarketPanel;
import com.li.stock.quant.indicator.Indicators;
import com.li.stock.quant.strategy.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * R2 均线多头 + MACD 金叉（右侧）。
 *
 * <p>多头排列确认趋势，趋势内 MACD 零轴上金叉介入，并用 ROC20 的横截面分位挑相对强势股。
 * 比 R1 更稳，但入场更晚。</p>
 */
@Component
public class R2MaMacd extends AbstractStrategy {

    private static final StrategyMeta META = new StrategyMeta(
            "R2_MA_MACD", "R2 均线多头 + MACD 金叉", "RIGHT", "1.0",
            "MA5 > MA10 > MA20 > MA60 且 MA60 向上，近 3 日 DIF 上穿 DEA 且 DIF > 0，优先选 ROC20 强势股。",
            "稳步上行的趋势行情；震荡市信号少但假信号杀伤大，建议配合大盘过滤。",
            List.of(1, 3, 5, 10, 20));

    @Override
    public StrategyMeta meta() {
        return META;
    }

    @Override
    public List<ParamDef> paramDefs() {
        return List.of(
                ParamDef.intP("ma5", "短均线", 5, 3, 20, "多头排列最短周期"),
                ParamDef.intP("ma10", "次短均线", 10, 5, 30, "多头排列第二周期"),
                ParamDef.intP("ma20", "中均线", 20, 10, 60, "多头排列第三周期，也是出场线"),
                ParamDef.intP("ma60", "长均线", 60, 20, 120, "趋势确认"),
                ParamDef.intP("slopeLookback", "长均线斜率回看(日)", 5, 1, 20, "MA60 今日高于 N 日前"),
                ParamDef.intP("crossWindow", "金叉有效窗口(日)", 3, 1, 10, "近 N 日内发生金叉"),
                ParamDef.intP("rocN", "强势周期(日)", 20, 5, 60, "ROC 横截面分位"),
                ParamDef.decP("rocRankMin", "ROC 分位下限(%)", 70, 50, 95, 5, "相对强势门槛"),
                ParamDef.decP("stopLossPct", "固定止损(%)", 8, 2, 25, 0.5, "相对买入价"),
                ParamDef.decP("trailPct", "移动止盈(%)", 12, 3, 30, 1, "自持仓最高收盘回撤"),
                ParamDef.intP("exitMaN", "出场均线(日)", 20, 5, 60, "连续 2 日跌破离场")
        );
    }

    @Override
    public Map<String, Object> defaults() {
        return params("ma5", 5, "ma10", 10, "ma20", 20, "ma60", 60, "slopeLookback", 5,
                "crossWindow", 3, "rocN", 20, "rocRankMin", 70d, "stopLossPct", 8d,
                "trailPct", 12d, "exitMaN", 20);
    }

    @Override
    public int warmupBars(Map<String, Object> p) {
        return i(p, "ma60", 60) + i(p, "slopeLookback", 5) + 2;
    }

    @Override
    public List<SignalSeries> evaluate(EvalContext ctx) {
        MarketPanel panel = ctx.panel;
        MarketPanel.Stock[] stocks = panel.stocks();
        int n5 = ctx.i("ma5"), n10 = ctx.i("ma10"), n20 = ctx.i("ma20"), n60 = ctx.i("ma60");
        int slopeLb = ctx.i("slopeLookback"), crossWin = ctx.i("crossWindow");
        int rocN = ctx.i("rocN"), exitMaN = ctx.i("exitMaN");
        double rocMin = ctx.d("rocRankMin") / 100d, stopLoss = ctx.d("stopLossPct"), trailPct = ctx.d("trailPct");
        int warmup = warmupBars(ctx.params);

        // 第一遍：ROC 序列 → 横截面分位（全市场同日比较）
        float[][] rocVals = new float[stocks.length][];
        for (MarketPanel.Stock s : stocks) {
            if (!ctx.universe.stockOk(s.idx)) continue;
            double[] roc = Indicators.roc(s.close, rocN);
            float[] f = new float[roc.length];
            for (int i = 0; i < roc.length; i++) f[i] = (float) roc[i];
            rocVals[s.idx] = f;
        }
        float[][] rocRank = CrossSection.percentile(panel, ctx.universe,
                (s, r) -> rocVals[s.idx] == null ? Float.NaN : rocVals[s.idx][r]);

        List<SignalSeries> out = new ArrayList<>(stocks.length);
        boolean[][] entryAll = new boolean[stocks.length][];
        float[][] scoreAll = new float[stocks.length][];
        float[][] cRoc = new float[stocks.length][];
        float[][] cHist = new float[stocks.length][];
        float[][] cSlope = new float[stocks.length][];

        for (MarketPanel.Stock s : stocks) {
            SignalSeries sig = new SignalSeries(s.idx, s.rows(), ctx.explain);
            out.add(sig);
            entryAll[s.idx] = sig.entry;
            scoreAll[s.idx] = sig.score;
            if (!ctx.universe.stockOk(s.idx) || s.rows() <= warmup) continue;

            double[] ma5 = Indicators.sma(s.close, n5);
            double[] ma10 = Indicators.sma(s.close, n10);
            double[] ma20 = Indicators.sma(s.close, n20);
            double[] ma60 = Indicators.sma(s.close, n60);
            Indicators.Macd macd = Indicators.macd(s.close, 12, 26, 9);
            double[] ma20Slope = Indicators.slope(ma20, slopeLb);
            float[] rankRow = rocRank[s.idx];

            float[] a = new float[s.rows()];
            float[] b = new float[s.rows()];
            float[] c = new float[s.rows()];
            Arrays.fill(a, Float.NaN);
            Arrays.fill(b, Float.NaN);
            Arrays.fill(c, Float.NaN);

            for (int row = warmup; row < s.rows(); row++) {
                if (!ctx.universe.rowOk(s.idx, row)) continue;
                boolean bullStack = ok(ma5[row]) && ok(ma10[row]) && ok(ma20[row]) && ok(ma60[row])
                        && ma5[row] > ma10[row] && ma10[row] > ma20[row] && ma20[row] > ma60[row];
                boolean longUp = row >= slopeLb && ok(ma60[row])
                        && ok(ma60[row - slopeLb]) && ma60[row] > ma60[row - slopeLb];
                boolean crossed = false;
                for (int j = Math.max(1, row - crossWin + 1); j <= row; j++) {
                    if (Indicators.crossUp(macd.dif(), macd.dea(), j)) {
                        crossed = true;
                        break;
                    }
                }
                boolean aboveZero = ok(macd.dif()[row]) && macd.dif()[row] > 0;
                boolean strong = rankRow != null && !Float.isNaN(rankRow[row]) && rankRow[row] >= rocMin;

                sig.trailPct[row] = (float) trailPct;   // 引擎按持仓最高收盘价滚动更新
                if (bullStack && longUp && crossed && aboveZero && strong) {
                    sig.entry[row] = true;
                    sig.stopPct[row] = (float) stopLoss;
                    a[row] = rankRow[row];
                    b[row] = (float) macd.hist()[row];
                    c[row] = ok(ma20Slope[row]) ? (float) ma20Slope[row] : Float.NaN;
                    if (ctx.explain) {
                        sig.detail[row] = "MA" + n5 + ">MA" + n10 + ">MA" + n20 + ">MA" + n60
                                + "；MA" + n60 + "上行；近" + crossWin + "日金叉；DIF=" + f(macd.dif()[row], 3)
                                + "；ROC" + rocN + "分位=" + f(rankRow[row] * 100, 0) + "%(≥" + f(rocMin * 100, 0) + ")";
                    }
                }

                if (Indicators.crossDown(macd.dif(), macd.dea(), row)) {
                    sig.markExit(row, "MACD 死叉");
                } else if (belowForDays(s.close, ma20, row, 2)) {
                    sig.markExit(row, "连续 2 日跌破 MA" + n20);
                }
            }
            cRoc[s.idx] = a;
            cHist[s.idx] = b;
            cSlope[s.idx] = c;
        }

        CrossSection.scoreCandidates(panel, entryAll, scoreAll,
                new double[]{1, 1, 1}, new float[][][]{cRoc, cHist, cSlope});
        return out;
    }
}
