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
 * L2 低估值深跌（左侧，中线）。
 *
 * <p>用横截面估值分位（而不是个股自身时间序列分位，样本仅约 2 年）判断「便宜」，
 * 叠加 120 日深度回撤与流动性约束，等待估值修复。</p>
 */
@Component
public class L2DeepValue extends AbstractStrategy {

    private static final StrategyMeta META = new StrategyMeta(
            "L2_DEEP_VALUE", "L2 低估值深跌", "LEFT", "1.0",
            "PB / PE 处于全市场低位 + 股价深度回撤 + 成交额充足，等待估值修复；估值回到中位或回补一半回撤离场。",
            "震荡或慢牛中的估值修复；低估值可能是价值陷阱，修复周期长，需重点看 20/40/60 日事件研究。",
            List.of(5, 10, 20, 40, 60));

    @Override
    public StrategyMeta meta() {
        return META;
    }

    @Override
    public List<ParamDef> paramDefs() {
        return List.of(
                ParamDef.decP("pbRankMax", "PB 分位上限(%)", 20, 1, 50, 1, "横截面百分位，越低越便宜"),
                ParamDef.decP("peRankMax", "PE 分位上限(%)", 30, 1, 60, 1, "横截面百分位"),
                ParamDef.intP("ddN", "回撤窗口(日)", 120, 20, 250, "距区间最高收盘的回撤"),
                ParamDef.decP("ddThr", "回撤阈值(%)", -25, -60, -5, 1, "低于该回撤才入选"),
                ParamDef.decP("minAmount20", "20 日均成交额下限(万元)", 5000, 0, 200000, 500, "流动性约束"),
                ParamDef.decP("stopLossPct", "固定止损(%)", 12, 3, 40, 0.5, "相对买入价"),
                ParamDef.intP("maxHoldDays", "最长持有(交易日)", 60, 10, 250, "时间止损"),
                ParamDef.decP("exitPbRank", "PB 分位出场(%)", 50, 20, 90, 5, "估值修复到该分位离场"),
                ParamDef.decP("recoverRatio", "回撤回补比例", 0.5, 0.2, 1, 0.1, "回补多少幅度的回撤后离场")
        );
    }

    @Override
    public Map<String, Object> defaults() {
        return params("pbRankMax", 20d, "peRankMax", 30d, "ddN", 120, "ddThr", -25d,
                "minAmount20", 5000d, "stopLossPct", 12d, "maxHoldDays", 60,
                "exitPbRank", 50d, "recoverRatio", 0.5);
    }

    @Override
    public int warmupBars(Map<String, Object> p) {
        return Math.max(i(p, "ddN", 120), 20) + 2;
    }

    @Override
    public List<SignalSeries> evaluate(EvalContext ctx) {
        MarketPanel panel = ctx.panel;
        MarketPanel.Stock[] stocks = panel.stocks();
        int ddN = ctx.i("ddN");
        double pbMax = ctx.d("pbRankMax") / 100d, peMax = ctx.d("peRankMax") / 100d;
        double ddThr = ctx.d("ddThr") / 100d, minAmount = ctx.d("minAmount20") * 10000d;
        double stopLossPct = ctx.d("stopLossPct"), exitPb = ctx.d("exitPbRank") / 100d;
        double recover = ctx.d("recoverRatio");
        int warmup = warmupBars(ctx.params);

        float[][] pbRank = ctx.pbRank();
        float[][] peRank = ctx.peRank();

        List<SignalSeries> out = new ArrayList<>(stocks.length);
        boolean[][] entryAll = new boolean[stocks.length][];
        float[][] scoreAll = new float[stocks.length][];
        float[][] cPb = new float[stocks.length][];
        float[][] cDd = new float[stocks.length][];

        for (MarketPanel.Stock s : stocks) {
            SignalSeries sig = new SignalSeries(s.idx, s.rows(), ctx.explain);
            out.add(sig);
            entryAll[s.idx] = sig.entry;
            scoreAll[s.idx] = sig.score;
            if (!ctx.universe.stockOk(s.idx) || s.rows() <= warmup) continue;
            float[] pbRow = pbRank[s.idx];
            float[] peRow = peRank[s.idx];
            if (pbRow == null || peRow == null) continue;

            double[] dd = Indicators.drawdownFromHigh(s.close, ddN);
            double[] hhv = Indicators.hhv(s.close, ddN, true);
            double[] amtMa = Indicators.sma(s.amount, 20);
            double targetFactor = 1 + ddThr * (1 - recover);

            float[] a = new float[s.rows()];
            float[] b = new float[s.rows()];
            Arrays.fill(a, Float.NaN);
            Arrays.fill(b, Float.NaN);

            for (int row = warmup; row < s.rows(); row++) {
                if (!ctx.universe.rowOk(s.idx, row)) continue;
                if (Double.isNaN(pbRow[row]) || Double.isNaN(peRow[row])) continue;
                boolean cheap = pbRow[row] <= pbMax && peRow[row] <= peMax;
                boolean deep = ok(dd[row]) && dd[row] <= ddThr;
                boolean liquid = ok(amtMa[row]) && amtMa[row] >= minAmount;
                boolean valid = s.pe[row] > 0 && s.pb[row] > 0;
                boolean noCrash = !hasLimitDown(s, row, 5);

                if (cheap && deep && liquid && valid && noCrash) {
                    sig.entry[row] = true;
                    sig.stopPct[row] = (float) stopLossPct;
                    a[row] = -pbRow[row];
                    b[row] = (float) (-dd[row]);
                    if (ctx.explain) {
                        sig.detail[row] = "PB分位=" + f(pbRow[row] * 100, 1) + "%(≤" + f(pbMax * 100, 0) + ")"
                                + "；PE分位=" + f(peRow[row] * 100, 1) + "%(≤" + f(peMax * 100, 0) + ")"
                                + "；" + ddN + "日回撤=" + f(dd[row] * 100, 1) + "%"
                                + "；20日均额=" + f(amtMa[row] / 10000, 0) + "万";
                    }
                }

                if (pbRow[row] >= exitPb) {
                    sig.markExit(row, "PB 分位修复至 " + f(pbRow[row] * 100, 0) + "%");
                } else if (ok(hhv[row]) && s.close[row] >= hhv[row] * targetFactor) {
                    sig.markExit(row, "回补本轮回撤 " + f(recover * 100, 0) + "%");
                }
            }
            cPb[s.idx] = a;
            cDd[s.idx] = b;
        }

        CrossSection.scoreCandidates(panel, entryAll, scoreAll,
                new double[]{1, 1}, new float[][][]{cPb, cDd});
        return out;
    }
}
