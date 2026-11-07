package com.li.stock.tlab.engine;

import com.li.stock.domain.Bar;
import com.li.stock.domain.Indicator;
import com.li.stock.domain.Params;
import com.li.stock.domain.SimOutcome;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规格第七节：买入日扫描。
 * <p>逐日假设「当天买入」并跑同一套策略，统计哪些买入日、哪些买入特征结果更好，
 * 给出推荐买点与当前信号，并做前 70% / 后 30% 稳健性检验。</p>
 */
@Component
public class EntryScanner {

    private static final int WARMUP_BARS = 21;
    private static final int MIN_SAMPLES = 15;

    /** 参与分桶的特征及其展示名。 */
    private static final String[][] FEATURES = {
            {"ret5", "近5日涨跌幅"},
            {"ret20", "近20日涨跌幅"},
            {"devMa20", "偏离MA20"},
            {"rsi14", "RSI14"},
            {"percentB", "布林%B"},
            {"volRatio", "量比"},
            {"atrPct", "ATR%"},
    };

    private final Backtester backtester;
    private final FeeCalculator fees;

    public EntryScanner(Backtester backtester, FeeCalculator fees) {
        this.backtester = backtester;
        this.fees = fees;
    }

    /**
     * @param bars       完整日线（含预热）
     * @param indicators 与 bars 等长的指标
     * @param fromIndex  扫描起点索引，至少 WARMUP_BARS
     * @param p          生效参数（holdDays / initPositionRatio 已就位）
     * @param capital    总资金
     * @param cash       可支配资金
     */
    public ScanBundle scan(List<Bar> bars, List<Indicator> indicators, int fromIndex,
                           Params p, double capital, double cash) {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int n = bars.size();
        int hold = p.holdDays;
        int start = Math.max(WARMUP_BARS - 1, Math.max(0, fromIndex));
        int lastStart = n - hold - 1;
        if (lastStart < start) {
            warnings.add("历史数据不足：需要至少 " + (WARMUP_BARS + hold) + " 个交易日，当前仅 " + n + " 根K线");
            return new ScanBundle(rows, List.of(), List.of(), Map.of(), Map.of(), warnings);
        }

        Params scanParams = Params.from(new LinkedHashMap<>(p.toMap()));
        scanParams.buyPriceMode = "NEXT_OPEN";

        double initAmount = capital * p.initPositionRatio;
        for (int d = start; d <= lastStart; d++) {
            // 以 d 的下一交易日开盘价买入（避免前视）
            int buyIdx = d + 1;
            double buyPrice = bars.get(buyIdx).open();
            int endIdx = Math.min(d + hold, n - 1);

            SimOutcome full = backtester.run(bars, indicators, buyIdx, endIdx, scanParams,
                    buyPrice, null, initAmount, capital, cash, Backtester.Mode.FULL);
            SimOutcome onlyAdd = backtester.run(bars, indicators, buyIdx, endIdx, scanParams,
                    buyPrice, null, initAmount, capital, cash, Backtester.Mode.ONLY_ADD);
            SimOutcome buyHold = backtester.run(bars, indicators, buyIdx, endIdx, scanParams,
                    buyPrice, null, initAmount, capital, cash, Backtester.Mode.BUY_HOLD);

            Indicator feat = indicators.get(d);
            Map<String, Object> features = featureMap(feat);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("buyDate", bars.get(buyIdx).date());
            row.put("signalDate", bars.get(d).date());
            row.put("finalPnlPct", pct(full.finalPnlPct()));
            row.put("buyHoldPct", pct(buyHold.finalPnlPct()));
            row.put("onlyAddPct", pct(onlyAdd.finalPnlPct()));
            row.put("maxDrawdown", pct(full.maxDrawdown()));
            row.put("recoverDays", full.recoverDays());
            row.put("tCount", full.state().tRoundTrips);
            row.put("tNetProfit", money(full.state().tNetProfit));
            row.put("feeTotal", money(full.state().feeTotal));
            row.put("features", features);
            row.put("score", score(full));
            rows.add(row);
        }

        List<Map<String, Object>> buckets = buildBuckets(rows);
        List<Map<String, Object>> recommendations = buildRecommendations(rows);
        Map<String, Object> currentSignal = buildCurrentSignal(bars, indicators, buckets, n);
        Map<String, Object> stability = buildStability(rows, buckets, warnings);

        warnings.add("窗口相互重叠，有效样本远小于窗口数（约 " + rows.size() + " 个窗口）；"
                + "约 200 天只代表这只股票最近一段行情，分桶结论只能当参考，不能当规律。");
        return new ScanBundle(rows, buckets, recommendations, currentSignal, stability, warnings);
    }

    /** 每个特征按分位数分 4 档，样本数小于 15 标注「样本不足」。 */
    private List<Map<String, Object>> buildBuckets(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String[] f : FEATURES) {
            String key = f[0];
            List<Map<String, Object>> sorted = new ArrayList<>(rows);
            sorted.sort(Comparator.comparingDouble(r -> num(((Map<?, ?>) r.get("features")).get(key))));
            int size = sorted.size();
            int per = Math.max(1, (int) Math.ceil(size / 4d));
            for (int b = 0; b < 4 && b * per < size; b++) {
                List<Map<String, Object>> slice = sorted.subList(b * per, Math.min(size, (b + 1) * per));
                double lo = num(((Map<?, ?>) slice.get(0).get("features")).get(key));
                double hi = num(((Map<?, ?>) slice.get(slice.size() - 1).get("features")).get(key));
                double sumRet = 0, sumDd = 0;
                int wins = 0;
                for (Map<String, Object> r : slice) {
                    sumRet += num(r.get("finalPnlPct"));
                    sumDd += num(r.get("maxDrawdown"));
                    if (num(r.get("finalPnlPct")) > 0) wins++;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("feature", key);
                item.put("featureLabel", f[1]);
                item.put("bucket", b);
                item.put("range", String.format("%.4f ~ %.4f", lo, hi));
                item.put("min", round(lo));
                item.put("max", round(hi));
                item.put("samples", slice.size());
                item.put("winRate", pct(slice.isEmpty() ? 0 : (double) wins / slice.size()));
                // sumRet / sumDd 已是百分数数值，此处只做四舍五入，不再乘 100
                item.put("avgReturn", round(slice.isEmpty() ? 0 : sumRet / slice.size()));
                item.put("avgDrawdown", round(slice.isEmpty() ? 0 : sumDd / slice.size()));
                item.put("insufficient", slice.size() < MIN_SAMPLES);
                out.add(item);
            }
        }
        return out;
    }

    private List<Map<String, Object>> buildRecommendations(List<Map<String, Object>> rows) {
        return rows.stream()
                .sorted(Comparator.comparingDouble((Map<String, Object> r) -> num(r.get("score"))).reversed())
                .limit(8)
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("buyDate", r.get("buyDate"));
                    m.put("score", round(num(r.get("score"))));
                    m.put("finalPnlPct", r.get("finalPnlPct"));
                    m.put("maxDrawdown", r.get("maxDrawdown"));
                    m.put("recoverDays", r.get("recoverDays"));
                    m.put("tCount", r.get("tCount"));
                    m.put("reason", "综合得分 = 收益 - 0.5×回撤 - 回本耗时惩罚");
                    return m;
                })
                .toList();
    }

    /** 用今日（最新一根K线）的特征落入的分桶历史统计，回答「现在是不是有利买点」。 */
    private Map<String, Object> buildCurrentSignal(List<Bar> bars, List<Indicator> indicators,
                                                    List<Map<String, Object>> buckets, int n) {
        Map<String, Object> out = new LinkedHashMap<>();
        Indicator today = indicators.get(n - 1);
        Map<String, Object> features = featureMap(today);
        out.put("date", bars.get(n - 1).date());
        out.put("features", features);
        List<Map<String, Object>> hits = new ArrayList<>();
        double sumAvg = 0;
        int hitCount = 0;
        for (String[] f : FEATURES) {
            double v = num(features.get(f[0]));
            for (Map<String, Object> b : buckets) {
                if (!f[0].equals(b.get("feature"))) continue;
                if (v >= num(b.get("min")) && v <= num(b.get("max"))) {
                    Map<String, Object> hit = new LinkedHashMap<>();
                    hit.put("feature", f[0]);
                    hit.put("featureLabel", f[1]);
                    hit.put("value", round(v));
                    hit.put("samples", b.get("samples"));
                    hit.put("winRate", b.get("winRate"));
                    hit.put("avgReturn", b.get("avgReturn"));
                    hit.put("insufficient", b.get("insufficient"));
                    hits.add(hit);
                    sumAvg += num(b.get("avgReturn"));
                    hitCount++;
                    break;
                }
            }
        }
        out.put("hits", hits);
        double avg = hitCount > 0 ? sumAvg / hitCount : 0;
        out.put("avgExpectedReturn", round(avg));
        out.put("favorable", avg > 0);
        out.put("verdict", avg > 0
                ? "当前特征落入的历史分桶平均收益为正，但样本有限，仅供参考"
                : "当前特征落入的历史分桶平均收益非正，不构成买入依据");
        return out;
    }

    /** 前 70% / 后 30% 各算一遍统计，两段结论方向不一致时标红提示。 */
    private Map<String, Object> buildStability(List<Map<String, Object>> rows,
                                                List<Map<String, Object>> buckets,
                                                List<String> warnings) {
        Map<String, Object> out = new LinkedHashMap<>();
        int split = Math.max(1, (int) (rows.size() * 0.7));
        double first = avg(rows.subList(0, split), "finalPnlPct");
        double second = avg(rows.subList(Math.min(split, rows.size()), rows.size()), "finalPnlPct");
        boolean consistent = (first >= 0) == (second >= 0);
        out.put("splitIndex", split);
        out.put("firstPeriodAvgReturn", round(first));
        out.put("secondPeriodAvgReturn", round(second));
        out.put("consistent", consistent);
        out.put("message", consistent
                ? "前后两段收益方向一致，但仍属单一股票的小样本区间"
                : "前后两段收益方向相反，结论不稳定，请勿据此调整参数或实盘");
        if (!consistent) warnings.add("稳健性提示：前 70% 与后 30% 的平均收益方向不一致，分桶结论不稳定");
        out.put("bucketCount", buckets.size());
        return out;
    }

    private double score(SimOutcome o) {
        double recoverPenalty = o.recoverDays() == null ? 3d : o.recoverDays() * 0.05d;
        return o.finalPnlPct() * 100 - o.maxDrawdown() * 100 * 0.5 - recoverPenalty;
    }

    private Map<String, Object> featureMap(Indicator i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ret5", round(i.ret5()));
        m.put("ret20", round(i.ret20()));
        m.put("devMa20", round(i.devMa20()));
        m.put("rsi14", round(i.rsi14()));
        m.put("percentB", round(i.percentB()));
        m.put("volRatio", round(i.volRatio()));
        m.put("atrPct", round(i.atrPct()));
        m.put("ma20Slope", round(i.ma20Slope()));
        return m;
    }

    private static double avg(List<Map<String, Object>> rows, String key) {
        if (rows.isEmpty()) return 0;
        double s = 0;
        for (Map<String, Object> r : rows) s += num(r.get(key));
        return s / rows.size();
    }

    private static double num(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        return 0d;
    }

    private static java.math.BigDecimal money(double v) {
        return java.math.BigDecimal.valueOf(v).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private java.math.BigDecimal pct(double ratio) {
        return java.math.BigDecimal.valueOf(ratio * 100).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private java.math.BigDecimal round(double v) {
        return java.math.BigDecimal.valueOf(v).setScale(4, java.math.RoundingMode.HALF_UP);
    }

    /** 扫描结果聚合体。 */
    public record ScanBundle(
            List<Map<String, Object>> rows,
            List<Map<String, Object>> featureBuckets,
            List<Map<String, Object>> recommendations,
            Map<String, Object> currentSignal,
            Map<String, Object> stability,
            List<String> warnings) {
    }
}
