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
 * X1 超跌企稳确认（混合）。
 *
 * <p>先用左侧条件发现超跌，再用右侧的「收盘上穿 MA5 + 放量阳线」确认企稳，
 * 演示混合策略如何复用左侧与右侧的条件写法。</p>
 */
@Component
public class X1ReversalConfirm extends AbstractStrategy {

    private static final StrategyMeta META = new StrategyMeta(
            "X1_REVERSAL_CONFIRM", "X1 超跌企稳确认", "HYBRID", "1.0",
            "过去 10 日内出现过超卖，今日收盘上穿 MA5 且放量收阳，确认企稳后介入；跌破超卖窗口最低价离场。",
            "急跌后的第一波反弹；确认信号晚于纯左侧，但假信号更少。",
            List.of(1, 3, 5, 10, 20));

    @Override
    public StrategyMeta meta() {
        return META;
    }

    @Override
    public List<ParamDef> paramDefs() {
        return List.of(
                ParamDef.intP("lookback", "超卖观察窗口(日)", 10, 3, 60, "窗口内出现过超卖即算"),
                ParamDef.intP("rsiN", "RSI 周期", 14, 5, 60, "Wilder 平滑"),
                ParamDef.decP("rsiBuy", "RSI 超卖阈值", 28, 10, 45, 1, "低于该值视为超卖"),
                ParamDef.intP("bollN", "布林周期", 20, 10, 120, "跌破下轨也算超卖"),
                ParamDef.decP("bollK", "布林倍数", 2, 1, 3, 0.1, "上下轨标准差倍数"),
                ParamDef.intP("confirmMaN", "确认均线(日)", 5, 3, 20, "收盘上穿该均线"),
                ParamDef.decP("volConfirm", "放量倍数", 1.2, 0.8, 3, 0.1, "相对 5 日均量（含当日）"),
                ParamDef.intP("exitMaN", "出场均线(日)", 10, 5, 60, "连续 2 日跌破离场"),
                ParamDef.intP("maxHoldDays", "最长持有(交易日)", 20, 5, 120, "时间止损"),
                ParamDef.decP("stopLossPct", "兜底止损(%)", 10, 2, 30, 0.5, "与窗口最低价止损取更近者")
        );
    }

    @Override
    public Map<String, Object> defaults() {
        return params("lookback", 10, "rsiN", 14, "rsiBuy", 28d, "bollN", 20, "bollK", 2d,
                "confirmMaN", 5, "volConfirm", 1.2, "exitMaN", 10, "maxHoldDays", 20, "stopLossPct", 10d);
    }

    @Override
    public int warmupBars(Map<String, Object> p) {
        return Math.max(Math.max(i(p, "rsiN", 14) + 1, i(p, "bollN", 20)),
                i(p, "lookback", 10) + 5) + 2;
    }

    @Override
    public List<SignalSeries> evaluate(EvalContext ctx) {
        MarketPanel panel = ctx.panel;
        MarketPanel.Stock[] stocks = panel.stocks();
        int lookback = ctx.i("lookback"), rsiN = ctx.i("rsiN"), bollN = ctx.i("bollN");
        int confirmN = ctx.i("confirmMaN"), exitMaN = ctx.i("exitMaN");
        double rsiBuy = ctx.d("rsiBuy"), bollK = ctx.d("bollK"), volConfirm = ctx.d("volConfirm");
        double stopLoss = ctx.d("stopLossPct");
        int warmup = warmupBars(ctx.params);

        List<SignalSeries> out = new ArrayList<>(stocks.length);
        boolean[][] entryAll = new boolean[stocks.length][];
        float[][] scoreAll = new float[stocks.length][];
        float[][] cOversold = new float[stocks.length][];
        float[][] cVol = new float[stocks.length][];
        float[][] cLift = new float[stocks.length][];

        for (MarketPanel.Stock s : stocks) {
            SignalSeries sig = new SignalSeries(s.idx, s.rows(), ctx.explain);
            out.add(sig);
            entryAll[s.idx] = sig.entry;
            scoreAll[s.idx] = sig.score;
            if (!ctx.universe.stockOk(s.idx) || s.rows() <= warmup) continue;

            double[] rsi = Indicators.rsi(s.close, rsiN);
            Indicators.Boll boll = Indicators.boll(s.close, bollN, bollK);
            double[] maConfirm = Indicators.sma(s.close, confirmN);
            double[] maExit = Indicators.sma(s.close, exitMaN);
            double[] volMa5 = Indicators.volMa(s.volume, 5, true);
            double[] lowN = Indicators.llv(s.low, lookback, true);

            float[] a = new float[s.rows()];
            float[] b = new float[s.rows()];
            float[] c = new float[s.rows()];
            Arrays.fill(a, Float.NaN);
            Arrays.fill(b, Float.NaN);
            Arrays.fill(c, Float.NaN);

            for (int row = warmup; row < s.rows(); row++) {
                if (!ctx.universe.rowOk(s.idx, row)) continue;

                boolean oversoldSeen = false;
                for (int j = Math.max(0, row - lookback + 1); j <= row; j++) {
                    boolean r = ok(rsi[j]) && rsi[j] < rsiBuy;
                    boolean bollHit = ok(boll.lower()[j]) && s.close[j] <= boll.lower()[j];
                    if (r || bollHit) {
                        oversoldSeen = true;
                        break;
                    }
                }
                boolean confirm = Indicators.crossUp(s.close, maConfirm, row);
                double vr = ok(volMa5[row]) && volMa5[row] > 0 ? s.volume[row] / volMa5[row] : Double.NaN;
                boolean volumeUp = ok(vr) && vr >= volConfirm;
                boolean positive = s.pct[row] > 0;

                if (oversoldSeen && confirm && volumeUp && positive) {
                    sig.entry[row] = true;
                    sig.stopPct[row] = (float) stopLoss;
                    if (ok(lowN[row])) {
                        sig.stopPrice[row] = (float) (lowN[row] * 0.99);
                    }
                    a[row] = (float) (100 - (ok(rsi[row]) ? rsi[row] : 50));
                    b[row] = (float) vr;
                    c[row] = ok(maConfirm[row]) && maConfirm[row] > 0
                            ? (float) (s.close[row] / maConfirm[row] - 1) : Float.NaN;
                    if (ctx.explain) {
                        sig.detail[row] = "近" + lookback + "日现超卖；收盘上穿 MA" + confirmN
                                + "(" + f(maConfirm[row], 2) + ")；量比=" + f(vr, 2)
                                + "(≥" + f(volConfirm, 1) + ")；涨幅=" + f(s.pct[row], 2) + "%";
                    }
                }

                if (belowForDays(s.close, maExit, row, 2)) {
                    sig.markExit(row, "连续 2 日跌破 MA" + exitMaN);
                }
            }
            cOversold[s.idx] = a;
            cVol[s.idx] = b;
            cLift[s.idx] = c;
        }

        CrossSection.scoreCandidates(panel, entryAll, scoreAll,
                new double[]{1, 1, 1}, new float[][][]{cOversold, cVol, cLift});
        return out;
    }
}
