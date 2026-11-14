package com.li.stock.quant.analytics;

import com.li.stock.quant.data.MarketPanel;
import com.li.stock.quant.indicator.Indicators;
import com.li.stock.quant.strategy.SignalSeries;
import com.li.stock.quant.strategy.Universe;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 事件研究（规格 8.2）：量化信号本身的统计优势，不受资金与仓位限制。
 *
 * <p>收益定义：信号日 T 收盘产生信号，T+1 开盘买入，持有 h 个交易日后以收盘卖出，
 * 扣除双边成本与滑点。同股票两次信号间隔小于 h 日时剔除后者，避免重叠样本高估显著性。</p>
 */
public final class EventStudy {

    private EventStudy() {
    }

    public static List<EventRow> run(MarketPanel panel, Universe universe, List<SignalSeries> signals,
                                     List<Integer> horizons, double slippagePct, double commissionPct,
                                     double stampPct, double transferPct) {
        MarketPanel.Stock[] stocks = panel.stocks();
        SignalSeries[] byStock = new SignalSeries[stocks.length];
        for (SignalSeries s : signals) {
            if (s.stockIdx >= 0 && s.stockIdx < byStock.length) byStock[s.stockIdx] = s;
        }
        double feeRate = (commissionPct * 2 + stampPct + transferPct * 2) / 100d;
        double slip = slippagePct / 100d;

        // 每日信号评分分布（用于评分分档）
        Map<Integer, List<Float>> dayScoreList = new HashMap<>();
        for (SignalSeries s : signals) {
            MarketPanel.Stock st = stocks[s.stockIdx];
            for (int row = 0; row < s.entry.length; row++) {
                if (s.entry[row]) {
                    dayScoreList.computeIfAbsent(st.day[row], k -> new ArrayList<>()).add(s.score[row]);
                }
            }
        }
        Map<Integer, float[]> dayScoreSorted = new HashMap<>();
        dayScoreList.forEach((d, list) -> {
            float[] arr = new float[list.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = list.get(i);
            Arrays.sort(arr);
            dayScoreSorted.put(d, arr);
        });

        // 大盘状态：等权指数在 MA60 上方为多头
        double[] bench = panel.benchmarkNav();
        float[] benchF = new float[bench.length];
        for (int i = 0; i < bench.length; i++) benchF[i] = (float) bench[i];
        double[] benchMa = Indicators.sma(benchF, 60);

        List<EventRow> out = new ArrayList<>();
        for (int h : horizons) {
            if (h <= 0) continue;
            int[] lastRow = new int[stocks.length];
            Arrays.fill(lastRow, Integer.MIN_VALUE / 2);
            Map<String, Acc> acc = new LinkedHashMap<>();

            for (SignalSeries sig : signals) {
                MarketPanel.Stock s = stocks[sig.stockIdx];
                for (int row = 0; row < sig.entry.length; row++) {
                    if (!sig.entry[row]) continue;
                    if (row - lastRow[s.idx] < h) continue;
                    int dateIdx = s.day[row];
                    int entryDay = dateIdx + 1;
                    int exitDay = entryDay + h;
                    if (exitDay >= panel.dateCount()) continue;
                    int entryRow = s.rowOn(entryDay);
                    if (entryRow < 0 || !s.tradable[entryRow]) continue;
                    double limitPct = s.limitPct(entryRow);
                    double openPct = s.preclose[entryRow] > 0
                            ? (s.open[entryRow] / s.preclose[entryRow] - 1) * 100 : 0;
                    if (openPct >= limitPct - 0.1) continue;   // 一字涨停买不进

                    int exitRow = rowAtOrBefore(s, exitDay);
                    if (exitRow < 0) continue;
                    lastRow[s.idx] = row;

                    double entryPrice = s.open[entryRow] * (1 + slip);
                    double exitPrice = s.close[exitRow] * (1 - slip);
                    if (entryPrice <= 0) continue;
                    double ret = exitPrice / entryPrice - 1 - feeRate;
                    double benchRet = bench[entryDay] > 0 ? bench[exitDay] / bench[entryDay] - 1 : 0;

                    double mfe = 0;
                    double mae = 0;
                    for (int j = entryRow; j <= exitRow; j++) {
                        mfe = Math.max(mfe, s.high[j] / entryPrice - 1);
                        mae = Math.min(mae, s.low[j] / entryPrice - 1);
                    }

                    add(acc, "ALL", ret, benchRet, mfe, mae);
                    YearMonth ym = YearMonth.from(panel.dateAt(dateIdx));
                    add(acc, "MONTH:" + ym, ret, benchRet, mfe, mae);
                    boolean bull = !Double.isNaN(benchMa[dateIdx]) && bench[dateIdx] > benchMa[dateIdx];
                    add(acc, "STATE:" + (bull ? "BULL" : "BEAR"), ret, benchRet, mfe, mae);
                    add(acc, "BOARD:" + s.board, ret, benchRet, mfe, mae);
                    float[] scores = dayScoreSorted.get(dateIdx);
                    if (scores != null && scores.length > 3) {
                        double pct = percentile(scores, sig.score[row]);
                        if (pct >= 0.8) add(acc, "SCORE:TOP20", ret, benchRet, mfe, mae);
                        else if (pct <= 0.2) add(acc, "SCORE:BOT20", ret, benchRet, mfe, mae);
                    }
                }
            }

            acc.forEach((key, a) -> out.add(a.toRow(h, key)));
            // 保证每个持有期至少有 ALL 行，方便前端渲染
            if (!acc.containsKey("ALL")) out.add(new EventRow(h, "ALL", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        }
        return out;
    }

    private static void add(Map<String, Acc> acc, String key, double ret, double benchRet,
                            double mfe, double mae) {
        acc.computeIfAbsent(key, k -> new Acc()).add(ret, benchRet, mfe, mae);
    }

    private static int rowAtOrBefore(MarketPanel.Stock s, int dayIdx) {
        int p = s.rowOn(dayIdx);
        if (p >= 0) return p;
        int lo = 0, hi = s.day.length - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (s.day[mid] <= dayIdx) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }

    private static double percentile(float[] sorted, float v) {
        int n = sorted.length;
        if (n <= 1) return 0.5;
        int lo = 0, hi = n;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted[mid] < v) lo = mid + 1;
            else hi = mid;
        }
        return lo / (n - 1.0);
    }

    private static final class Acc {
        int n;
        double sum;
        double sumSq;
        double excessSum;
        double mfeSum;
        double maeSum;
        int wins;
        double sumWin;
        double sumLoss;
        final List<Double> values = new ArrayList<>();

        void add(double ret, double benchRet, double mfe, double mae) {
            n++;
            sum += ret;
            sumSq += ret * ret;
            excessSum += ret - benchRet;
            mfeSum += mfe;
            maeSum += mae;
            values.add(ret);
            if (ret > 0) {
                wins++;
                sumWin += ret;
            } else {
                sumLoss += ret;
            }
        }

        EventRow toRow(int horizon, String key) {
            if (n == 0) return new EventRow(horizon, key, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
            double mean = sum / n;
            double var = n > 1 ? (sumSq - n * mean * mean) / (n - 1) : 0;
            double std = Math.sqrt(Math.max(0, var));
            double t = std > 0 ? mean / (std / Math.sqrt(n)) : 0;
            List<Double> copy = new ArrayList<>(values);
            copy.sort(Double::compareTo);
            double median = copy.size() % 2 == 1
                    ? copy.get(copy.size() / 2)
                    : (copy.get(copy.size() / 2 - 1) + copy.get(copy.size() / 2)) / 2;
            return new EventRow(horizon, key, n, mean, median, (double) wins / n,
                    wins > 0 ? sumWin / wins : 0, (n - wins) > 0 ? sumLoss / (n - wins) : 0,
                    excessSum / n, t, mfeSum / n, maeSum / n);
        }
    }
}
