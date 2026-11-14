package com.li.stock.quant.analytics;

import com.li.stock.quant.backtest.EquityPoint;
import com.li.stock.quant.backtest.TradeRecord;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 绩效指标（规格 8.1）。全部基于每日净值序列与已平仓交易计算，无状态。
 */
public final class Performance {

    private Performance() {
    }

    public static Map<String, Object> compute(List<EquityPoint> points, List<TradeRecord> trades, double rfRate) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (points == null || points.size() < 2) {
            m.put("tradeCount", trades == null ? 0 : trades.size());
            m.put("totalReturn", 0d);
            m.put("note", "净值序列过短，无法计算");
            return m;
        }
        int n = points.size();
        double navStart = points.get(0).nav();
        double navEnd = points.get(n - 1).nav();
        double benchmarkStart = points.get(0).benchmarkNav();
        double benchmarkEnd = points.get(n - 1).benchmarkNav();
        int days = n - 1;
        double years = days / 252d;

        double[] rets = new double[days];
        double sum = 0;
        for (int i = 1; i < n; i++) {
            double prev = points.get(i - 1).nav();
            rets[i - 1] = prev == 0 ? 0 : points.get(i).nav() / prev - 1;
            sum += rets[i - 1];
        }
        double mean = sum / days;
        double var = 0;
        double downsideVar = 0;
        for (double r : rets) {
            var += (r - mean) * (r - mean);
            if (r < 0) downsideVar += r * r;
        }
        double std = Math.sqrt(var / Math.max(1, days - 1));
        double downside = Math.sqrt(downsideVar / Math.max(1, days - 1));

        double totalReturn = navEnd / navStart - 1;
        double annualReturn = years > 0 ? Math.pow(1 + totalReturn, 1 / years) - 1 : 0;
        double volatility = std * Math.sqrt(252);
        double sharpe = std > 0 ? (mean - rfRate / 252) / std * Math.sqrt(252) : 0;
        double sortino = downside > 0 ? (mean * 252 - rfRate) / (downside * Math.sqrt(252)) : 0;

        // 最大回撤（含起止日期与恢复天数）
        double peak = navStart;
        double maxDd = 0;
        int peakIdx = 0;
        int ddStartIdx = 0;
        int ddEndIdx = 0;
        for (int i = 0; i < n; i++) {
            double nav = points.get(i).nav();
            if (nav > peak) {
                peak = nav;
                peakIdx = i;
            }
            double dd = nav / peak - 1;
            if (dd < maxDd) {
                maxDd = dd;
                ddStartIdx = peakIdx;
                ddEndIdx = i;
            }
        }
        int recoveryDays = -1;
        for (int i = ddEndIdx; i < n; i++) {
            if (points.get(i).nav() >= points.get(ddStartIdx).nav()) {
                recoveryDays = i - ddEndIdx;
                break;
            }
        }
        double calmar = maxDd < 0 ? annualReturn / Math.abs(maxDd) : 0;

        // 交易统计
        int wins = 0;
        int losses = 0;
        double sumWin = 0;
        double sumLoss = 0;
        double sumHold = 0;
        double maxSingleLoss = 0;
        int maxConsecLoss = 0;
        int consec = 0;
        double turnoverAmount = 0;
        int tradeCount = trades == null ? 0 : trades.size();
        if (trades != null) {
            for (TradeRecord t : trades) {
                double pnlPct = t.pnlPct();
                turnoverAmount += t.entryAmount();
                sumHold += t.holdDays();
                if (pnlPct > 0) {
                    wins++;
                    sumWin += pnlPct;
                    consec = 0;
                } else {
                    losses++;
                    sumLoss += pnlPct;
                    consec++;
                    maxConsecLoss = Math.max(maxConsecLoss, consec);
                }
                maxSingleLoss = Math.min(maxSingleLoss, pnlPct);
            }
        }
        double winRate = tradeCount > 0 ? (double) wins / tradeCount : 0;
        double avgWin = wins > 0 ? sumWin / wins : 0;
        double avgLoss = losses > 0 ? sumLoss / losses : 0;
        double profitFactor = sumLoss != 0 ? Math.abs(sumWin / sumLoss) : (sumWin > 0 ? 99 : 0);
        double expectancy = tradeCount > 0 ? (sumWin + sumLoss) / tradeCount : 0;

        double avgEquity = 0;
        double posSum = 0;
        for (EquityPoint p : points) {
            avgEquity += p.cash() + p.positionValue();
            posSum += p.positionCount();
        }
        avgEquity = avgEquity / n;
        double turnover = avgEquity > 0 && years > 0 ? turnoverAmount / avgEquity / years : 0;

        m.put("totalReturn", totalReturn);
        m.put("annualReturn", annualReturn);
        m.put("benchmarkReturn", benchmarkStart > 0 ? benchmarkEnd / benchmarkStart - 1 : 0);
        m.put("excessReturn", totalReturn - (benchmarkStart > 0 ? benchmarkEnd / benchmarkStart - 1 : 0));
        m.put("maxDrawdown", maxDd);
        m.put("maxDrawdownStart", String.valueOf(points.get(ddStartIdx).date()));
        m.put("maxDrawdownEnd", String.valueOf(points.get(ddEndIdx).date()));
        m.put("recoveryDays", recoveryDays);
        m.put("volatility", volatility);
        m.put("sharpe", sharpe);
        m.put("sortino", sortino);
        m.put("calmar", calmar);
        m.put("winRate", winRate);
        m.put("profitFactor", profitFactor);
        m.put("expectancy", expectancy);
        m.put("avgWin", avgWin);
        m.put("avgLoss", avgLoss);
        m.put("tradeCount", tradeCount);
        m.put("avgHoldDays", tradeCount > 0 ? sumHold / tradeCount : 0);
        m.put("turnover", turnover);
        m.put("avgPosition", posSum / n);
        m.put("maxSingleLoss", maxSingleLoss);
        m.put("maxConsecutiveLoss", maxConsecLoss);
        m.put("finalNav", navEnd / navStart);
        m.put("tradingDays", days);
        m.put("isSignificant", tradeCount >= 30);
        m.put("monthlyReturns", monthlyReturns(points));
        return m;
    }

    private static Map<String, Double> monthlyReturns(List<EquityPoint> points) {
        Map<YearMonth, Double> acc = new TreeMap<>();
        for (int i = 1; i < points.size(); i++) {
            YearMonth ym = YearMonth.from(points.get(i).date());
            double prev = points.get(i - 1).nav();
            double r = prev == 0 ? 0 : points.get(i).nav() / prev - 1;
            acc.merge(ym, 1 + r, (a, b) -> a * b);
        }
        Map<String, Double> out = new LinkedHashMap<>();
        acc.forEach((k, v) -> out.put(k.toString(), v - 1));
        return out;
    }

    /**
     * 截取一段净值并重新归一到 1（用于 IS / OOS 分段指标）。
     */
    public static List<EquityPoint> slice(List<EquityPoint> points, int fromIdx, int toIdx) {
        if (points == null || points.isEmpty()) return List.of();
        int from = Math.max(0, fromIdx);
        int to = Math.min(points.size() - 1, toIdx);
        if (to - from < 1) return List.of();
        double navBase = points.get(from).nav();
        double benchBase = points.get(from).benchmarkNav();
        List<EquityPoint> out = new ArrayList<>(to - from + 1);
        double peak = 1;
        for (int i = from; i <= to; i++) {
            EquityPoint p = points.get(i);
            double nav = navBase == 0 ? 1 : p.nav() / navBase;
            double bench = benchBase == 0 ? 1 : p.benchmarkNav() / benchBase;
            peak = Math.max(peak, nav);
            out.add(new EquityPoint(p.date(), nav, p.cash(), p.positionValue(), bench, nav / peak - 1,
                    p.positionCount()));
        }
        return out;
    }
}
