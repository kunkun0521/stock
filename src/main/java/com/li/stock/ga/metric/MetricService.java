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

    public MetricService(GaConfig config, GaStoreRepository store, GaDataRepository data) {
        this.config = config; this.store = store; this.data = data;
    }

    /** 汇总 ALL / 板块 / 市场状态 × 当日 / 近20 / 近60 个基准日 × 实盘 / 回放。 */
    public String rebuild(LocalDate asOf) {
        LocalDate from = asOf.minusDays(120);
        List<Map<String, Object>> rows = store.allPredictionsForMetric(from, asOf, 400000);
        List<Object[]> args = new ArrayList<>();
        for (int h : config.horizons()) {
            for (int window : new int[]{1, 20, 60}) {
                for (int replay : new int[]{0, 1}) {
                    List<Map<String, Object>> filtered = rows.stream()
                            .filter(r -> intVal(r.get("horizon")) == h)
                            .filter(r -> intVal(r.get("inPool")) == 1)
                            .filter(r -> "CHAMPION".equals(String.valueOf(r.get("modelRole"))))
                            .filter(r -> "VERIFIED".equals(String.valueOf(r.get("status"))))
                            .filter(r -> intVal(r.get("isReplay")) == replay)
                            .toList();
                    Map<String, List<Map<String, Object>>> grouped = group(filtered);
                    for (Map.Entry<String, List<Map<String, Object>>> e : grouped.entrySet()) {
                        String[] parts = e.getKey().split("\\|");
                        LocalDate day = LocalDate.parse(parts[0]);
                        if (day.isBefore(asOf.minusDays(90))) continue;
                        List<Map<String, Object>> win = windowRows(rows, h, replay, day, window);
                        String scopeType = parts[1], scopeValue = parts[2];
                        args.add(metricRow(day, h, scopeType, scopeValue, window, replay, win));
                    }
                }
            }
        }
        store.upsertMetrics(args);
        return "汇总 " + args.size() + " 行指标";
    }

    private Map<String, List<Map<String, Object>>> group(List<Map<String, Object>> rows) {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String day = String.valueOf(r.get("tradeDate")).substring(0, 10);
            String code = String.valueOf(r.get("code"));
            String board = code.length() >= 2 ? code.substring(0, 2) : "OTHER";
            String regime = String.valueOf(r.getOrDefault("regime", "2"));
            for (String[] scope : new String[][]{{"ALL", ""}, {"BOARD", board}, {"REGIME", regime}}) {
                out.computeIfAbsent(day + "|" + scope[0] + "|" + scope[1], k -> new ArrayList<>()).add(r);
            }
        }
        return out;
    }

    private List<Map<String, Object>> windowRows(List<Map<String, Object>> all, int h, int replay,
                                                 LocalDate day, int window) {
        LocalDate from = day.minusDays(Math.max(1, window * 2));
        return all.stream()
                .filter(r -> intVal(r.get("horizon")) == h && intVal(r.get("isReplay")) == replay)
                .filter(r -> intVal(r.get("inPool")) == 1)
                .filter(r -> "CHAMPION".equals(String.valueOf(r.get("modelRole"))))
                .filter(r -> "VERIFIED".equals(String.valueOf(r.get("status"))))
                .filter(r -> {
                    LocalDate d = LocalDate.parse(String.valueOf(r.get("tradeDate")).substring(0, 10));
                    return !d.isAfter(day) && !d.isBefore(from);
                }).toList();
    }

    private Object[] metricRow(LocalDate day, int h, String scopeType, String scopeValue,
                               int window, int replay, List<Map<String, Object>> rows) {
        int nPred = rows.size(), nVerified = 0, nCorrect = 0;
        int upHit = 0, upN = 0, downHit = 0, downN = 0;
        double sumUp = 0, sumDown = 0; int cntUp = 0, cntDown = 0;
        Map<LocalDate, List<double[]>> byDate = new HashMap<>();
        for (Map<String, Object> r : rows) {
            int dir = intVal(r.get("predDirection"));
            Object correct = r.get("isCorrect");
            double ret = dbl(r.get("actualReturn"));
            double prob = dbl(r.get("probUp"));
            if (dir != 0 && correct instanceof Number) {
                nVerified++;
                int c = ((Number) correct).intValue();
                nCorrect += c;
                if (dir == 1) { upN++; upHit += c; sumUp += ret; cntUp++; }
                else { downN++; downHit += c; sumDown += ret; cntDown++; }
            }
            LocalDate d = LocalDate.parse(String.valueOf(r.get("tradeDate")).substring(0, 10));
            byDate.computeIfAbsent(d, k -> new ArrayList<>()).add(new double[]{prob, ret});
        }
        double ic = 0; int icDays = 0;
        for (List<double[]> list : byDate.values()) {
            if (list.size() < 5) continue;
            double[] a = new double[list.size()], b = new double[list.size()];
            for (int i = 0; i < list.size(); i++) { a[i] = list.get(i)[0]; b[i] = list.get(i)[1]; }
            ic += GaMath.spearman(a, b); icDays++;
        }
        ic = icDays == 0 ? 0 : ic / icDays;
        double avgUp = cntUp == 0 ? 0 : sumUp / cntUp, avgDown = cntDown == 0 ? 0 : sumDown / cntDown;
        return new Object[]{Date.valueOf(day), h, scopeType, scopeValue, window, replay,
                nPred, nVerified, nCorrect,
                GaMath.div(nCorrect, nVerified, 0), GaMath.div(upHit, upN, 0), GaMath.div(downHit, downN, 0),
                GaMath.div(nVerified, nPred, 0), ic, avgUp, avgDown, avgUp - avgDown};
    }

    /** 因子体检（规格 10.2）：截面 Rank IC 与衰减预警。 */
    public String factorIc(LocalDate asOf, int h) {
        int flag = config.globalInt("adjust_flag");
        List<Map<String, Object>> preds = store.verifiedChampion(asOf.minusDays(120), asOf, h, true, 200000);
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
            Map<String, double[]> cross = data.featureCrossSection(d, flag, keys);
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
            List<Map<String, Object>> rows = store.rolePredictions(h, "CHAMPION", 200000);
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
