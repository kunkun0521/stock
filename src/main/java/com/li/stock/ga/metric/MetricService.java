package com.li.stock.ga.metric;

import com.li.stock.ga.GaMath;
import com.li.stock.ga.config.GaConfig;
import com.li.stock.ga.feature.FeatureKeys;
import com.li.stock.ga.repository.GaDataRepository;
import com.li.stock.ga.repository.GaStoreRepository;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MetricJob / 因子体检 / 冠军衰减（规格 9.3 / 10.2 / 6.6.4）。
 *
 * <p>只统计 in_pool=1 且 model_role=CHAMPION 的预测；ga_metric_daily 预汇总让看板秒开。</p>
 */
@Service
public class MetricService {

    private final GaConfig config;
    private final GaStoreRepository store;
    private final GaDataRepository data;

    /** 特征截面缓存：逐日跑因子体检时相邻日期的 20 日窗口高度重叠，避免重复查全市场。 */
    private final Map<LocalDate, Map<String, double[]>> crossCache = new LinkedHashMap<>();

    public MetricService(GaConfig config, GaStoreRepository store, GaDataRepository data) {
        this.config = config; this.store = store; this.data = data;
    }

    private Map<String, double[]> crossSection(LocalDate day, int flag, Set<String> keys) {
        synchronized (crossCache) {
            Map<String, double[]> hit = crossCache.get(day);
            if (hit != null) return hit;
            Map<String, double[]> loaded = data.featureCrossSection(day, flag, keys);
            if (crossCache.size() >= 120) crossCache.remove(crossCache.keySet().iterator().next());
            crossCache.put(day, loaded);
            return loaded;
        }
    }

    /** 汇总 ALL / 板块 / 市场状态 × 当日 / 近20 / 近60 个基准日 × 实盘 / 回放。 */
    public String rebuild(LocalDate asOf) {
        LocalDate from = asOf.minusDays(120);
        List<Object[]> args = new ArrayList<>();
        for (int h : config.horizons()) {
            for (int replay : new int[]{0, 1}) {
                // 14 天分段 + SQL 下推周期/口径 + 增量合并：单段最多几万行，
                // 既不会把明细堆满内存，也不会像旧版 30 天段那样被 LIMIT 40 万截断（9 月单月已超）
                Map<String, Map<LocalDate, DayStat>> stats = new LinkedHashMap<>();
                for (LocalDate s = from; !s.isAfter(asOf); s = s.plusDays(14)) {
                    LocalDate e = s.plusDays(13).isAfter(asOf) ? asOf : s.plusDays(13);
                    List<Map<String, Object>> chunk = store.allPredictionsForMetric(s, e, h, replay, 200000);
                    if (chunk.isEmpty()) continue;
                    Map<String, Map<LocalDate, List<Map<String, Object>>>> bucket = new LinkedHashMap<>();
                    for (Map<String, Object> r : chunk) {
                        if (intVal(r.get("horizon")) != h) continue;
                        if (intVal(r.get("inPool")) != 1) continue;
                        if (!"CHAMPION".equals(String.valueOf(r.get("modelRole")))) continue;
                        if (intVal(r.get("isReplay")) != replay) continue;
                        LocalDate d = LocalDate.parse(String.valueOf(r.get("tradeDate")).substring(0, 10));
                        String code = String.valueOf(r.get("code"));
                        String board = code.length() >= 2 ? code.substring(0, 2) : "OTHER";
                        String regime = String.valueOf(r.getOrDefault("regime", "2"));
                        for (String scope : new String[]{"ALL|", "BOARD|" + board, "REGIME|" + regime}) {
                            bucket.computeIfAbsent(scope, k -> new LinkedHashMap<>())
                                    .computeIfAbsent(d, k -> new ArrayList<>()).add(r);
                        }
                    }
                    bucket.forEach((scope, byDay) -> byDay.forEach((d, list) ->
                            stats.computeIfAbsent(scope, k -> new java.util.TreeMap<>())
                                    .merge(d, dayStat(list), DayStat::plus)));
                }
                if (stats.isEmpty()) continue;

                for (Map.Entry<String, Map<LocalDate, DayStat>> e : stats.entrySet()) {
                    String[] parts = e.getKey().split("\\|", -1);
                    String scopeType = parts[0], scopeValue = parts[1];
                    Map<LocalDate, DayStat> byDay = e.getValue();
                    for (Map.Entry<LocalDate, DayStat> de : byDay.entrySet()) {
                        LocalDate day = de.getKey();
                        if (day.isBefore(asOf.minusDays(90))) continue;
                        for (int window : new int[]{1, 20, 60}) {
                            LocalDate winFrom = day.minusDays(Math.max(1, window * 2));
                            DayStat agg = null;
                            for (Map.Entry<LocalDate, DayStat> we : byDay.entrySet()) {
                                LocalDate d = we.getKey();
                                if (d.isBefore(winFrom) || d.isAfter(day)) continue;
                                agg = agg == null ? we.getValue() : agg.plus(we.getValue());
                            }
                            if (agg == null) continue;
                            args.add(agg.toRow(day, h, scopeType, scopeValue, window, replay));
                        }
                    }
                }
            }
        }
        store.upsertMetrics(args);
        return "汇总 " + args.size() + " 行指标";
    }

    /** 单日单作用域统计。 */
    private record DayStat(int nPred, int nVerified, int nCorrect, int upHit, int upN,
                           int downHit, int downN, double sumUp, int cntUp, double sumDown,
                           int cntDown, double ic, int icDays) {

        DayStat plus(DayStat o) {
            return new DayStat(nPred + o.nPred, nVerified + o.nVerified, nCorrect + o.nCorrect,
                    upHit + o.upHit, upN + o.upN, downHit + o.downHit, downN + o.downN,
                    sumUp + o.sumUp, cntUp + o.cntUp, sumDown + o.sumDown, cntDown + o.cntDown,
                    ic + o.ic, icDays + o.icDays);
        }

        Object[] toRow(LocalDate day, int h, String scopeType, String scopeValue,
                       int window, int replay) {
            double avgUp = cntUp == 0 ? 0 : sumUp / cntUp, avgDown = cntDown == 0 ? 0 : sumDown / cntDown;
            return new Object[]{Date.valueOf(day), h, scopeType, scopeValue, window, replay,
                    nPred, nVerified, nCorrect,
                    GaMath.div(nCorrect, nVerified, 0), GaMath.div(upHit, upN, 0), GaMath.div(downHit, downN, 0),
                    GaMath.div(nVerified, nPred, 0), icDays == 0 ? 0 : ic / icDays, avgUp, avgDown,
                    avgUp - avgDown};
        }
    }

    private DayStat dayStat(List<Map<String, Object>> rows) {
        int nPred = rows.size(), nVerified = 0, nCorrect = 0;
        int upHit = 0, upN = 0, downHit = 0, downN = 0;
        double sumUp = 0, sumDown = 0;
        int cntUp = 0, cntDown = 0;
        double[] prob = new double[nPred], rets = new double[nPred];
        int m = 0;
        for (Map<String, Object> r : rows) {
            int dir = intVal(r.get("predDirection"));
            Object correct = r.get("isCorrect");
            double ret = dbl(r.get("actualReturn"));
            if (dir != 0 && correct instanceof Number) {
                nVerified++;
                int c = ((Number) correct).intValue();
                nCorrect += c;
                if (dir == 1) { upN++; upHit += c; sumUp += ret; cntUp++; }
                else { downN++; downHit += c; sumDown += ret; cntDown++; }
            }
            prob[m] = dbl(r.get("probUp"));
            rets[m] = ret;
            m++;
        }
        // 当日截面 IC（预测概率与实际收益的秩相关），窗口汇总时按天平均
        double ic = 0;
        int icDays = 0;
        if (nPred >= 5) {
            ic = GaMath.spearman(java.util.Arrays.copyOf(prob, m), java.util.Arrays.copyOf(rets, m));
            icDays = 1;
        }
        return new DayStat(nPred, nVerified, nCorrect, upHit, upN, downHit, downN,
                sumUp, cntUp, sumDown, cntDown, ic, icDays);
    }


    /** 因子体检（规格 10.2）：截面 Rank IC 与衰减预警。 */
    public String factorIc(LocalDate asOf, int h) {
        int flag = config.globalInt("adjust_flag");
        List<Map<String, Object>> preds = store.verifiedReturns(asOf.minusDays(120), asOf, h, true, 200000);
        Map<LocalDate, Map<String, Double>> retByDate = new HashMap<>();
        for (Map<String, Object> p : preds) {
            LocalDate d = LocalDate.parse(String.valueOf(p.get("tradeDate")).substring(0, 10));
            retByDate.computeIfAbsent(d, k -> new HashMap<>()).put(
                    String.valueOf(p.get("code")) + "|" + String.valueOf(p.get("market")),
                    dbl(p.get("actualReturn")));
        }
        List<LocalDate> dates = retByDate.keySet().stream().sorted().toList();
        List<LocalDate> recent = dates.subList(Math.max(0, dates.size() - 20), dates.size());
        Set<String> keys = new java.util.LinkedHashSet<>(FeatureKeys.STOCK_FEATURES);
        Map<String, List<Double>> icSeries = new HashMap<>();
        for (String k : keys) icSeries.put(k, new ArrayList<>());
        for (LocalDate d : recent) {
            Map<String, double[]> cross = crossSection(d, flag, keys);
            Map<String, Double> rets = retByDate.getOrDefault(d, Map.of());
            List<String> codes = new ArrayList<>(rets.keySet());
            codes.retainAll(cross.keySet());
            if (codes.size() < 10) continue;
            double[] rr = new double[codes.size()];
            for (int i = 0; i < codes.size(); i++) rr[i] = rets.get(codes.get(i));
            int ki = 0;
            for (String k : keys) {
                double[] ff = new double[codes.size()];
                for (int i = 0; i < codes.size(); i++) ff[i] = cross.get(codes.get(i))[ki];
                icSeries.get(k).add(GaMath.spearman(ff, rr));
                ki++;
            }
        }
        double decayMin = config.globalDouble("ic_decay_min");
        for (String k : keys) {
            List<Double> series = icSeries.get(k);
            double ic20 = series.isEmpty() ? 0 : GaMath.mean(toArray(series.subList(0, Math.min(5, series.size()))));
            double ic60 = series.isEmpty() ? 0 : GaMath.mean(toArray(series));
            double sd = GaMath.stdSample(toArray(series));
            double icir = sd < GaMath.EPS ? 0 : ic60 / sd;
            int decay = ic20 * ic60 < 0 && Math.abs(ic60) >= decayMin ? 1 : 0;
            store.upsertFactorIc(asOf, h, k, ic20, ic60, icir, decay);
        }
        return "因子体检 H" + h + " 写入 " + keys.size() + " 个特征";
    }

    /** 冠军衰减（规格 6.6.4）：近 60 日 BalAcc < 阈值且样本数达标则退役并触发重进化。 */
    public List<Integer> checkRetire(LocalDate asOf) {
        List<Integer> toReEvolve = new ArrayList<>();
        int window = config.globalInt("retire_window");
        int minSamples = config.globalInt("retire_min_samples");
        double threshold = config.globalDouble("retire_balacc");
        for (int h : config.horizons()) {
            // retire_window 的口径是交易日：按交易日历换算成自然日区间并下推到 SQL，
            // 旧实现把全历史（含 150 万行回放）当「近 60 日」，衰减判断完全失真。
            LocalDate from = retireFrom(asOf, window);
            List<Map<String, Object>> rows = store.rolePredictions(h, "CHAMPION", from, asOf, 200000);
            Map<String, Object> b = balacc(rows);
            int n = b.get("n") instanceof Number num ? num.intValue() : 0;
            double bal = b.get("bal") instanceof Number num ? num.doubleValue() : 0;
            if (n >= minSamples && bal < threshold) {
                for (Map<String, Object> c : store.currentChampions(h)) {
                    store.setIndividualStatus(((Number) c.get("individualId")).longValue(),
                            "RETIRED", "LIVE_DECAY");
                }
                toReEvolve.add(h);
            }
        }
        return toReEvolve;
    }

    /** 把 N 个交易日换算成自然日起点：交易日 ≈ 7/5 自然日，再用真实交易日历校准。 */
    private LocalDate retireFrom(LocalDate asOf, int tradingDays) {
        LocalDate rough = asOf.minusDays(Math.max(30, Math.round(tradingDays * 7L / 5.0) + 20));
        List<LocalDate> dates = data.tradeDates(config.globalInt("adjust_flag"), rough, asOf);
        return dates.size() >= tradingDays ? dates.get(dates.size() - tradingDays) : rough;
    }

    private Map<String, Object> balacc(List<Map<String, Object>> rows) {
        int tp = 0, tn = 0, fp = 0, fn = 0;
        for (Map<String, Object> r : rows) {
            int dir = intVal(r.get("predDirection"));
            Object c = r.get("isCorrect");
            if (dir == 0 || !(c instanceof Number)) continue;
            boolean hit = ((Number) c).intValue() == 1;
            if (dir == 1) { if (hit) tp++; else fp++; }
            else { if (hit) tn++; else fn++; }
        }
        return Map.of("bal", (GaMath.div(tp, tp + fn, 0) + GaMath.div(tn, tn + fp, 0)) / 2,
                "n", tp + tn + fp + fn);
    }

    private static double[] toArray(List<Double> list) {
        double[] a = new double[list.size()];
        for (int i = 0; i < list.size(); i++) a[i] = list.get(i);
        return a;
    }

    private static int intVal(Object o) { return o instanceof Number n ? n.intValue() : 0; }
    private static double dbl(Object o) { return o instanceof Number n ? n.doubleValue() : 0; }
}
