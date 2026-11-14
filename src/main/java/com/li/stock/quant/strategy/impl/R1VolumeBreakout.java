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
 * R1 放量突破（右侧）。
 *
 * <p>收盘创 N 日新高（不含当日）+ 放量 + 中期趋势向上 + 不过度乖离；初始止损按 ATR，
 * 移动止盈按 ATR 回撤，跌破突破位或连续两日跌破 MA20 离场。</p>
 */
@Component
public class R1VolumeBreakout extends AbstractStrategy {

    private static final StrategyMeta META = new StrategyMeta(
            "R1_VOLUME_BREAKOUT", "R1 放量突破", "RIGHT", "1.0",
            "价格放量突破 20 日新高且 MA20 > MA60，顺势持有；ATR 止损 + ATR 移动止盈。",
            "单边上涨、热点轮动；震荡市会反复小亏，建议开启「仅多头市场」过滤。",
            List.of(1, 3, 5, 10, 20));

    @Override
    public StrategyMeta meta() {
        return META;
    }

    @Override
    public List<ParamDef> paramDefs() {
        return List.of(
                ParamDef.intP("breakN", "突破窗口(日)", 20, 5, 120, "收盘 > 前 N 日最高价"),
                ParamDef.decP("volMul", "放量倍数", 1.5, 1, 4, 0.1, "相对前 N 日均量（不含当日）"),
                ParamDef.intP("volN", "均量周期", 20, 5, 60, "VOL_MA 不含当日"),
                ParamDef.intP("trendN", "趋势均线(日)", 60, 20, 120, "收盘需在 MA60 上方"),
                ParamDef.decP("maxBias", "最大乖离(%)", 15, 5, 40, 1, "BIAS20 上限，避免追高"),
                ParamDef.intP("atrN", "ATR 周期", 14, 5, 60, "波动率"),
                ParamDef.decP("atrStopMul", "ATR 止损倍数", 2, 0.5, 5, 0.1, "入场价 - 倍数 × ATR"),
                ParamDef.decP("hardStopPct", "硬止损(%)", 10, 3, 30, 0.5, "相对买入价"),
                ParamDef.decP("atrTrailMul", "ATR 移动止盈倍数", 2.5, 0.5, 6, 0.1, "最高收盘 - 倍数 × ATR"),
                ParamDef.intP("exitMaN", "出场均线(日)", 20, 5, 60, "连续 2 日跌破离场")
        );
    }

    @Override
    public Map<String, Object> defaults() {
        return params("breakN", 20, "volMul", 1.5, "volN", 20, "trendN", 60, "maxBias", 15d,
                "atrN", 14, "atrStopMul", 2d, "hardStopPct", 10d, "atrTrailMul", 2.5, "exitMaN", 20);
    }

    @Override
    public int warmupBars(Map<String, Object> p) {
        return Math.max(Math.max(i(p, "breakN", 20) + 1, i(p, "trendN", 60)),
                Math.max(i(p, "atrN", 14) + 1, i(p, "volN", 20))) + 2;
    }

    @Override
    public List<SignalSeries> evaluate(EvalContext ctx) {
        MarketPanel panel = ctx.panel;
        MarketPanel.Stock[] stocks = panel.stocks();
        int breakN = ctx.i("breakN"), volN = ctx.i("volN"), trendN = ctx.i("trendN");
        int atrN = ctx.i("atrN"), exitMaN = ctx.i("exitMaN");
        double volMul = ctx.d("volMul"), maxBias = ctx.d("maxBias");
        double atrStopMul = ctx.d("atrStopMul"), hardStop = ctx.d("hardStopPct"), atrTrail = ctx.d("atrTrailMul");
        int warmup = warmupBars(ctx.params);

        List<SignalSeries> out = new ArrayList<>(stocks.length);
        boolean[][] entryAll = new boolean[stocks.length][];
        float[][] scoreAll = new float[stocks.length][];
        float[][] cBreak = new float[stocks.length][];
        float[][] cVol = new float[stocks.length][];
        float[][] cRoc = new float[stocks.length][];

        for (MarketPanel.Stock s : stocks) {
            SignalSeries sig = new SignalSeries(s.idx, s.rows(), ctx.explain);
            out.add(sig);
            entryAll[s.idx] = sig.entry;
            scoreAll[s.idx] = sig.score;
            if (!ctx.universe.stockOk(s.idx) || s.rows() <= warmup) continue;

            double[] hhvPrev = Indicators.hhv(s.high, breakN, false);
            double[] volMaPrev = Indicators.volMa(s.volume, volN, false);
            double[] maTrend = Indicators.sma(s.close, trendN);
            double[] maExit = Indicators.sma(s.close, exitMaN);
            double[] bias = Indicators.bias(s.close, 20);
            double[] roc20 = Indicators.roc(s.close, 20);
            double[] atr = Indicators.atr(s.high, s.low, s.close, atrN);

            float[] a = new float[s.rows()];
            float[] b = new float[s.rows()];
            float[] c = new float[s.rows()];
            Arrays.fill(a, Float.NaN);
            Arrays.fill(b, Float.NaN);
            Arrays.fill(c, Float.NaN);

            for (int row = warmup; row < s.rows(); row++) {
                if (!ctx.universe.rowOk(s.idx, row)) continue;
                boolean breakout = ok(hhvPrev[row]) && s.close[row] > hhvPrev[row];
                double vr = ok(volMaPrev[row]) && volMaPrev[row] > 0 ? s.volume[row] / volMaPrev[row] : Double.NaN;
                boolean volumeUp = ok(vr) && vr >= volMul;
                boolean trend = ok(maTrend[row]) && s.close[row] > maTrend[row];
                boolean biasOk = !ok(bias[row]) || bias[row] <= maxBias;

                if (breakout && volumeUp && trend && biasOk) {
                    sig.entry[row] = true;
                    sig.stopPct[row] = (float) hardStop;
                    if (ok(atr[row])) {
                        sig.stopPrice[row] = (float) Math.max(s.close[row] - atrStopMul * atr[row],
                                s.close[row] * (1 - hardStop / 100));
                        sig.trailOffset[row] = (float) (atrTrail * atr[row]);
                    }
                    a[row] = (float) (s.close[row] / hhvPrev[row] - 1);
                    b[row] = (float) vr;
                    c[row] = (float) roc20[row];
                    if (ctx.explain) {
                        sig.detail[row] = "收盘" + f(s.close[row], 2) + ">" + breakN + "日高"
                                + f(hhvPrev[row], 2) + "；量比=" + f(vr, 2) + "(≥" + f(volMul, 1) + ")"
                                + "；MA" + trendN + "=" + f(maTrend[row], 2)
                                + "；BIAS20=" + f(bias[row], 1) + "%";
                    }
                }

                if (ok(hhvPrev[row]) && s.close[row] < hhvPrev[row]) {
                    sig.markExit(row, "跌回突破位");
                } else if (belowForDays(s.close, maExit, row, 2)) {
                    sig.markExit(row, "连续 2 日跌破 MA" + exitMaN);
                }
            }
            cBreak[s.idx] = a;
            cVol[s.idx] = b;
            cRoc[s.idx] = c;
        }

        CrossSection.scoreCandidates(panel, entryAll, scoreAll,
                new double[]{1, 1, 1}, new float[][][]{cBreak, cVol, cRoc});
        return out;
    }
}
