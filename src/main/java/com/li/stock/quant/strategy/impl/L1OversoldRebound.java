package com.li.stock.quant.strategy.impl;

import com.li.stock.quant.data.MarketPanel;
import com.li.stock.quant.indicator.Indicators;
import com.li.stock.quant.strategy.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * L1 超跌反弹（左侧，短线）。
 *
 * <p>入场：RSI 超卖 + 乖离过大或跌破布林下轨 + 缩量（抛压衰竭）+ 近 3 日无跌停 +
 * 近 5 日累计跌幅受控。出场：反弹到 MA20 / RSI 回升，或固定止损 / 时间止损。</p>
 */
@Component
public class L1OversoldRebound extends AbstractStrategy {

    private static final StrategyMeta META = new StrategyMeta(
            "L1_OVERSOLD_REBOUND", "L1 超跌反弹", "LEFT", "1.0",
            "短期急跌 + RSI 超卖 + 缩量，赌均值回归；跌破止损或持有到期离场。",
            "震荡市、超跌反弹；单边下跌行情胜率会明显下降，建议做「开启 / 关闭大盘过滤」对照回测。",
            List.of(1, 3, 5, 10, 20));

    @Override
    public StrategyMeta meta() {
        return META;
    }

    @Override
    public List<ParamDef> paramDefs() {
        return List.of(
                ParamDef.intP("rsiN", "RSI 周期", 14, 5, 60, "Wilder 平滑"),
                ParamDef.decP("rsiBuy", "RSI 买入阈值", 28, 10, 45, 1, "低于该值视为超卖"),
                ParamDef.intP("biasN", "BIAS 周期", 20, 5, 120, "收盘价相对均线的偏离"),
                ParamDef.decP("biasBuy", "BIAS 买入阈值(%)", -8, -30, -2, 0.5, "低于该值视为偏离过大"),
                ParamDef.intP("bollN", "布林周期", 20, 10, 120, "BOLL 中轨窗口"),
                ParamDef.decP("bollK", "布林倍数", 2, 1, 3, 0.1, "上下轨标准差倍数"),
                ParamDef.intP("volN", "缩量均量周期", 5, 3, 30, "VOL_MA 不含当日"),
                ParamDef.decP("volShrink", "缩量系数", 0.8, 0.3, 1.2, 0.05, "当日量 < 系数 × 均量"),
                ParamDef.decP("maxDrop5d", "5 日最大跌幅(%)", 20, 5, 40, 1, "超过则视为接飞刀"),
                ParamDef.boolP("requireProfit", "剔除亏损股", false, "要求 pe_ttm > 0"),
                ParamDef.decP("stopLossPct", "固定止损(%)", 8, 2, 30, 0.5, "相对买入价"),
                ParamDef.intP("maxHoldDays", "最长持有(交易日)", 15, 3, 120, "时间止损"),
                ParamDef.decP("exitRsi", "RSI 出场阈值", 55, 40, 90, 1, "RSI 高于该值离场"),
                ParamDef.intP("exitMaN", "出场均线周期", 20, 5, 120, "收盘回到该均线上方离场")
        );
    }

    @Override
    public Map<String, Object> defaults() {
        return params("rsiN", 14, "rsiBuy", 28d, "biasN", 20, "biasBuy", -8d,
                "bollN", 20, "bollK", 2d, "volN", 5, "volShrink", 0.8, "maxDrop5d", 20d,
                "requireProfit", false, "stopLossPct", 8d, "maxHoldDays", 15,
                "exitRsi", 55d, "exitMaN", 20);
    }

    @Override
    public int warmupBars(Map<String, Object> p) {
        return Math.max(Math.max(i(p, "rsiN", 14) + 1, i(p, "biasN", 20)),
                Math.max(i(p, "bollN", 20), i(p, "volN", 5) + 1)) + 2;
    }

    @Override
    public List<SignalSeries> evaluate(EvalContext ctx) {
        MarketPanel panel = ctx.panel;
        MarketPanel.Stock[] stocks = panel.stocks();
        int rsiN = ctx.i("rsiN"), rsiBuy = (int) Math.round(ctx.d("rsiBuy"));
        int biasN = ctx.i("biasN"), bollN = ctx.i("bollN"), volN = ctx.i("volN"), exitMaN = ctx.i("exitMaN");
        double biasBuy = ctx.d("biasBuy"), bollK = ctx.d("bollK"), volShrink = ctx.d("volShrink");
        double maxDrop5d = ctx.d("maxDrop5d"), stopLossPct = ctx.d("stopLossPct"), exitRsi = ctx.d("exitRsi");
        boolean requireProfit = ctx.b("requireProfit");
        int warmup = warmupBars(ctx.params);

        List<SignalSeries> out = new ArrayList<>(stocks.length);
        boolean[][] entryAll = new boolean[stocks.length][];
        float[][] scoreAll = new float[stocks.length][];
        float[][] cRsi = new float[stocks.length][];
        float[][] cBias = new float[stocks.length][];
        float[][] cShrink = new float[stocks.length][];

        for (MarketPanel.Stock s : stocks) {
            SignalSeries sig = new SignalSeries(s.idx, s.rows(), ctx.explain);
            out.add(sig);
            entryAll[s.idx] = sig.entry;
            scoreAll[s.idx] = sig.score;
            if (!ctx.universe.stockOk(s.idx) || s.rows() <= warmup) continue;

            double[] rsi = Indicators.rsi(s.close, rsiN);
            double[] bias = Indicators.bias(s.close, biasN);
            Indicators.Boll boll = Indicators.boll(s.close, bollN, bollK);
            double[] volMa = Indicators.volMa(s.volume, volN, true);
            double[] maExit = Indicators.sma(s.close, exitMaN);

            float[] a = new float[s.rows()];
            float[] b = new float[s.rows()];
            float[] c = new float[s.rows()];
            java.util.Arrays.fill(a, Float.NaN);
            java.util.Arrays.fill(b, Float.NaN);
            java.util.Arrays.fill(c, Float.NaN);

            for (int row = warmup; row < s.rows(); row++) {
                if (!ctx.universe.rowOk(s.idx, row)) continue;
                boolean oversold = ok(rsi[row]) && rsi[row] < rsiBuy;
                boolean deviation = (ok(bias[row]) && bias[row] <= biasBuy)
                        || (ok(boll.lower()[row]) && s.close[row] <= boll.lower()[row]);
                double ratio = ok(volMa[row]) && volMa[row] > 0 ? s.volume[row] / volMa[row] : Double.NaN;
                boolean shrink = ok(ratio) && ratio < volShrink;
                boolean noCrash = !hasLimitDown(s, row, 3);
                double ret5 = row >= 5 ? s.close[row] / s.close[row - 5] - 1 : Double.NaN;
                boolean notFreeFall = ok(ret5) && ret5 * 100 >= -maxDrop5d;
                boolean profitOk = !requireProfit || s.pe[row] > 0;

                if (oversold && deviation && shrink && noCrash && notFreeFall && profitOk) {
                    sig.entry[row] = true;
                    sig.stopPct[row] = (float) stopLossPct;
                    a[row] = (float) (100 - rsi[row]);          // RSI 越低越高分
                    b[row] = (float) (-bias[row]);              // 偏离越负越高分
                    c[row] = (float) (1 - ratio);               // 缩量越明显越高分
                    if (ctx.explain) {
                        sig.detail[row] = "RSI" + rsiN + "=" + f(rsi[row], 1) + "(<" + f(rsiBuy, 0) + ")"
                                + "；" + (ok(bias[row]) && bias[row] <= biasBuy
                                ? "BIAS" + biasN + "=" + f(bias[row], 1) + "%(≤" + f(biasBuy, 0) + ")"
                                : "收盘≤布林下轨 " + f(boll.lower()[row], 2))
                                + "；量比=" + f(ratio, 2) + "(<" + f(volShrink, 2) + ")"
                                + "；5日涨幅=" + f(ret5 * 100, 1) + "%";
                    }
                }

                boolean backToMa = ok(maExit[row]) && s.close[row] >= maExit[row];
                boolean rsiRecovered = ok(rsi[row]) && rsi[row] > exitRsi;
                if (backToMa) sig.markExit(row, "反弹到 MA" + exitMaN);
                else if (rsiRecovered) sig.markExit(row, "RSI 回升至 " + f(rsi[row], 0));
            }
            cRsi[s.idx] = a;
            cBias[s.idx] = b;
            cShrink[s.idx] = c;
        }

        CrossSection.scoreCandidates(panel, entryAll, scoreAll,
                new double[]{1, 1, 1}, new float[][][]{cRsi, cBias, cShrink});
        return out;
    }
}
