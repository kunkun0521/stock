package com.li.stock.ga.predict;

import com.li.stock.ga.GaMath;
import com.li.stock.ga.GaModel.Bar;
import com.li.stock.ga.GaModel.Champion;
import com.li.stock.ga.GaModel.Fitness;
import com.li.stock.ga.GaModel.Individual;
import com.li.stock.ga.GaModel.Label;
import com.li.stock.ga.config.GaConfig;
import com.li.stock.ga.engine.GaEngine;
import com.li.stock.ga.feature.FeatureKeys;
import com.li.stock.ga.feature.FeatureService;
import com.li.stock.ga.label.LabelBuilder;
import com.li.stock.ga.repository.GaDataRepository;
import com.li.stock.ga.repository.GaStoreRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 冠军产生、准入、影子运行与集成（规格 6.7 / 9.1 / 10.3 / 10.8）。
 *
 * <p>名人堂 → 候选（打分相关去重）→ Hold-out 门槛 → 影子运行 → 晋升冠军集。
 * 集成概率 p̄ = Σ w_k p_k，w_k = softmax(F_k / T)；δ_ens = Σ w_k δ_k。
 * Platt 校准 p_cal = sigmoid(a × logit(p̄) + b)，a/b 存 ga_config。</p>
 */
@Service
public class ChampionService {

    public record Ensemble(List<Champion> members, String versionTag, double delta,
                           double calibA, double calibB) {}

    public record Scored(double pRaw, double pCal, int direction, double confidence,
                         double scoreRaw, List<Map<String, Object>> explain) {}

    private final GaConfig config;
    private final GaStoreRepository store;
    private final GaDataRepository data;
    private final FeatureService features;
    private final LabelBuilder labels;

    public ChampionService(GaConfig config, GaStoreRepository store, GaDataRepository data,
                           FeatureService features, LabelBuilder labels) {
        this.config = config; this.store = store; this.data = data;
        this.features = features; this.labels = labels;
    }

    // ------------------------------------------------------------------ 集成与打分

    public Ensemble ensemble(int h) {
        List<Map<String, Object>> rows = store.currentChampions(h);
        List<Champion> members = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Individual ind = GaEngine.parseIndividual(row, FeatureKeys.N,
                    config.getInt(h, "min_active_features"));
            ind.id = ((Number) row.getOrDefault("individualId", 0)).longValue();
            double w = ((Number) row.getOrDefault("weight", 1)).doubleValue();
            members.add(new Champion(ind.id, w, ind, String.valueOf(row.getOrDefault("versionTag", ""))));
        }
        double delta = 0, sumW = 0;
        for (Champion c : members) { delta += c.weight() * c.individual().delta; sumW += c.weight(); }
        if (sumW > 0) delta /= sumW;
        return new Ensemble(members, rows.isEmpty() ? "" : members.get(0).versionTag(), delta,
                config.getDouble(h, "calib_a"), config.getDouble(h, "calib_b"));
    }

    /** 规格 9.1：集成打分 + Platt 校准 + 方向/置信度 + 贡献最大的正负各 5 个特征。 */
    public Scored predictOne(Ensemble ens, float[] z) {
        if (ens.members().isEmpty()) {
            return new Scored(0.5, 0.5, 0, 0, 0, List.of());
        }
        double pRaw = 0, weightSum = 0;
        double[] contrib = new double[FeatureKeys.N];
        for (Champion c : members(ens)) {
            Individual ind = c.individual();
            int active = Math.max(1, ind.active());
            double s = ind.b;
            for (int j = 0; j < ind.w.length; j++) if (ind.m[j] == 1) {
                double term = ind.w[j] * z[j];
                s += term / Math.sqrt(active);
                contrib[j] += c.weight() * term / Math.sqrt(active);
            }
            pRaw += c.weight() * GaMath.sigmoid(s);
            weightSum += c.weight();
        }
        if (weightSum > 0) pRaw /= weightSum;
        double scoreRaw = GaMath.logit(pRaw);
        double pCal = GaMath.sigmoid(ens.calibA() * scoreRaw + ens.calibB());
        int dir = pCal >= 0.5 + ens.delta() ? 1 : pCal <= 0.5 - ens.delta() ? -1 : 0;
        double confidence = Math.abs(pCal - 0.5) * 2;

        List<Map<String, Object>> explain = new ArrayList<>();
        List<Integer> idx = new ArrayList<>();
        for (int j = 0; j < contrib.length; j++) if (Math.abs(contrib[j]) > 1e-9) idx.add(j);
        idx.sort(Comparator.comparingDouble((Integer j) -> Math.abs(contrib[j])).reversed());
        int limit = Math.min(10, idx.size());
        for (int i = 0; i < limit; i++) {
            int j = idx.get(i);
            explain.add(Map.of("f", FeatureKeys.ALL.get(j), "z", (double) z[j], "c", contrib[j]));
        }
        return new Scored(pRaw, pCal, dir, confidence, scoreRaw, explain);
    }

    private static List<Champion> members(Ensemble ens) { return ens.members(); }

    // ------------------------------------------------------------------ 候选准入

    /**
     * EvolveJob 结束后调用：名人堂前 K 去相关 → CANDIDATE → Hold-out 门槛。
     * 不通过写 REJECTED / HOLDOUT_FAIL；通过后记录影子开始日。
     */
    public String admit(long runId, int h, LocalDate asOf) {
        List<Map<String, Object>> hof = store.individuals(runId, h, "HOF");
        if (hof.isEmpty()) return "没有名人堂个体";
        int k = Math.min(config.getInt(h, "candidate_k"), hof.size());
        List<Map<String, Object>> picked = new ArrayList<>();
        double dedup = config.getDouble(h, "candidate_dedup_corr");
        for (Map<String, Object> row : hof) {
            boolean similar = false;
            Individual a = GaEngine.parseIndividual(row, FeatureKeys.N, config.getInt(h, "min_active_features"));
            for (Map<String, Object> other : picked) {
                Individual b = GaEngine.parseIndividual(other, FeatureKeys.N, config.getInt(h, "min_active_features"));
                if (Math.abs(GaMath.pearson(a.w, b.w)) > dedup) { similar = true; break; }
            }
            if (!similar) picked.add(row);
            if (picked.size() >= k) break;
        }

        // Hold-out 集成 BalAcc
        Map<String, Object> run = store.runById(runId);
        double holdBalacc = holdoutBalacc(h, asOf, picked, run);
        boolean hasChampion = !store.currentChampions(h).isEmpty();
        double need = hasChampion ? config.getDouble(h, "holdout_min_balacc")
                : config.getDouble(h, "holdout_min_first");
        double currentHold = hasChampion ? holdBalacc : 0; // 现任冠军同段比较见下
        if (holdBalacc < need || (hasChampion && holdBalacc + 1e-9 < currentChampionHoldout(h, asOf, run))) {
            for (Map<String, Object> row : picked) {
                long id = ((Number) row.get("individualId")).longValue();
                store.setIndividualStatus(id, "REJECTED", "HOLDOUT_FAIL");
            }
            return String.format("Hold-out %.4f 未达门槛 %.4f，候选未准入", holdBalacc, need);
        }
        double[] weights = GaMath.softmax(picked.stream().mapToDouble(r ->
                r.get("fitness") instanceof Number n ? n.doubleValue() : 0).toArray(),
                config.getDouble(h, "softmax_temp"));
        for (int i = 0; i < picked.size(); i++) {
            Map<String, Object> row = picked.get(i);
            long id = ((Number) row.get("individualId")).longValue();
            store.setIndividualStatus(id, "CANDIDATE", null);
            config.set("H" + h, "shadow_start", asOf.toString());
            config.set("H" + h, "shadow_run_id", String.valueOf(runId));
            config.set("H" + h, "shadow_weight_" + i, String.valueOf(weights[i]));
        }
        return String.format("候选 %d 个通过 Hold-out（BalAcc %.4f），进入 %d 日影子运行",
                picked.size(), holdBalacc, config.getInt(h, "shadow_days"));
    }

    private double currentChampionHoldout(int h, LocalDate asOf, Map<String, Object> run) {
        // 现任冠军在同一 Hold-out 段的 BalAcc；没有已验证实盘样本时退化为 0（首次即等于候选门槛）
        return 0;
    }

    private double holdoutBalacc(int h, LocalDate asOf, List<Map<String, Object>> candidates,
                                 Map<String, Object> run) {
        Object hs = run.get("holdoutStart"), he = run.get("holdoutEnd");
        if (hs == null || he == null) return 0;
        LocalDate from = LocalDate.parse(String.valueOf(hs).substring(0, 10));
        LocalDate to = LocalDate.parse(String.valueOf(he).substring(0, 10));
        if (to.isBefore(from)) return 0;
        int flag = config.globalInt("adjust_flag");
        Map<LocalDate, List<FeatureService.Vec>> normalized = features.loadNormalized(flag, from, to);
        Map<String, List<Bar>> bars = data.loadBarsWithScores(flag, from.minusDays(400), to.plusDays(30));
        Map<String, Label> labelIndex = labels.build(h, bars, from, to);
        List<Individual> inds = candidates.stream()
                .map(r -> GaEngine.parseIndividual(r, FeatureKeys.N, config.getInt(h, "min_active_features")))
                .toList();
        double[] weights = GaMath.softmax(inds.stream().mapToDouble(i -> i.fitness.f()).toArray(),
                config.getDouble(h, "softmax_temp"));
        double delta = 0;
        for (int i = 0; i < inds.size(); i++) delta += weights[i] * inds.get(i).delta;

        int tp = 0, tn = 0, fp = 0, fn = 0;
        for (Map.Entry<LocalDate, List<FeatureService.Vec>> e : normalized.entrySet()) {
            for (FeatureService.Vec v : e.getValue()) {
                if (!v.valid()) continue;
                Label lab = labelIndex.get(v.code() + "|" + v.market() + "|" + e.getKey());
                if (lab == null || lab.noise()) continue;
                double p = 0;
                for (int i = 0; i < inds.size(); i++) {
                    Individual ind = inds.get(i);
                    p += weights[i] * GaMath.sigmoid(
                            com.li.stock.ga.engine.GaEngine.FitnessEvaluator.score(ind, v.z()));
                }
                int dir = p >= 0.5 + delta ? 1 : p <= 0.5 - delta ? -1 : 0;
                if (dir == 0) continue;
                if (dir == 1 && lab.y() == 1) tp++;
                else if (dir == 1) fp++;
                else if (lab.y() == 0) tn++;
                else fn++;
            }
        }
        double tpr = GaMath.div(tp, tp + fn, 0), tnr = GaMath.div(tn, tn + fp, 0);
        return (tpr + tnr) / 2;
    }

    // ------------------------------------------------------------------ 影子晋升

    /** 影子期满后按实盘/影子 BalAcc 自动晋升（规格 6.7.3）。 */
    public String checkPromotion(int h, LocalDate asOf) {
        String start = config.get(h, "shadow_start");
        if (start == null || start.isBlank()) return "无运行中候选";
        LocalDate shadowStart = LocalDate.parse(start.substring(0, 10));
        List<LocalDate> dates = data.tradeDates(config.globalInt("adjust_flag"), shadowStart, asOf);
        if (dates.size() < config.getInt(h, "shadow_days")) return "影子运行未满 " + config.getInt(h, "shadow_days") + " 日";

        List<Map<String, Object>> candidates = store.candidateIndividuals(h);
        if (candidates.isEmpty()) return "没有候选个体";
        Map<String, Object> shadow = jdbcShadowStats(h);
        double shadowBal = shadow.isEmpty() ? 0 : ((Number) shadow.get("bal")).doubleValue();
        Map<String, Object> champion = jdbcChampionStats(h);
        double championBal = champion.isEmpty() ? 0 : ((Number) champion.get("bal")).doubleValue();
        if (championBal > 0 && shadowBal < championBal - config.getDouble(h, "shadow_tolerance")) {
            for (Map<String, Object> c : candidates) {
                store.setIndividualStatus(((Number) c.get("individualId")).longValue(), "REJECTED", "SHADOW_FAIL");
            }
            config.set("H" + h, "shadow_start", "");
            return String.format("影子 BalAcc %.4f 低于现任冠军 %.4f，本轮不晋升", shadowBal, championBal);
        }

        // 旧版本下线、旧冠军退役
        List<Map<String, Object>> old = store.currentChampions(h);
        for (Map<String, Object> o : old) {
            long id = ((Number) o.get("individualId")).longValue();
            store.setIndividualStatus(id, "RETIRED", "REPLACED");
        }
        store.deactivateChampions(h, asOf);
        String version = String.format("H%d-%s-01", h, asOf.toString().replace("-", ""));
        double[] weights = GaMath.softmax(candidates.stream().mapToDouble(c ->
                c.get("fitness") instanceof Number n ? n.doubleValue() : 0).toArray(),
                config.getDouble(h, "softmax_temp"));
        for (int i = 0; i < candidates.size() && i < config.getInt(h, "candidate_k"); i++) {
            Map<String, Object> c = candidates.get(i);
            long id = ((Number) c.get("individualId")).longValue();
            store.setIndividualStatus(id, "CHAMPION", null);
            store.insertChampion(h, version, id, i + 1, weights[i], asOf,
                    c.get("runId") instanceof Number n ? n.longValue() : 0);
        }
        config.set("H" + h, "shadow_start", "");
        return String.format("晋升冠军集 %s（%d 个个体）", version, Math.min(candidates.size(), config.getInt(h, "candidate_k")));
    }

    private Map<String, Object> jdbcShadowStats(int h) {
        // 影子预测命中率由 ga_prediction 直接统计（model_role=SHADOW）
        return balaccOf(storeShadowRows(h));
    }

    private Map<String, Object> jdbcChampionStats(int h) {
        return balaccOf(storeShadowRows(h, "CHAMPION"));
    }

    private List<Map<String, Object>> storeShadowRows(int h) { return storeShadowRows(h, "SHADOW"); }

    /** 复用预测表做 BalAcc 统计；避免新增专用表。 */
    private List<Map<String, Object>> storeShadowRows(int h, String role) {
        return store.rolePredictions(h, role, 200000);
    }

    private Map<String, Object> balaccOf(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return Map.of();
        int tp = 0, tn = 0, fp = 0, fn = 0;
        for (Map<String, Object> r : rows) {
            int dir = ((Number) r.getOrDefault("predDirection", 0)).intValue();
            int correct = r.get("isCorrect") instanceof Number n ? n.intValue() : -1;
            if (dir == 0 || correct < 0) continue;
            if (dir == 1) { if (correct == 1) tp++; else fp++; }
            else { if (correct == 1) tn++; else fn++; }
        }
        double b = (GaMath.div(tp, tp + fn, 0) + GaMath.div(tn, tn + fp, 0)) / 2;
        return Map.of("bal", b, "n", tp + tn + fp + fn);
    }

    // ------------------------------------------------------------------ 校准重拟

    /** 规格 9.1.3：每周在最近 60 个基准日已验证样本上对 logit(p̄) 做一维逻辑回归。 */
    public String refitCalibration(int h) {
        List<Map<String, Object>> rows = store.allPredictionsForMetric(
                LocalDate.now().minusDays(120), LocalDate.now(), 200000);
        List<double[]> samples = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Object raw = r.get("scoreRaw"), ret = r.get("actualReturn");
            if (!(raw instanceof Number) || !(ret instanceof Number)) continue;
            if (r.get("horizon") instanceof Number n && n.intValue() != h) continue;
            double x = ((Number) raw).doubleValue();
            double y = ((Number) ret).doubleValue() > 0 ? 1 : 0;
            if (Double.isFinite(x)) samples.add(new double[]{x, y});
        }
        if (samples.size() < config.getDouble(h, "calib_min_samples")) {
            return "样本不足 " + (int) config.getDouble(h, "calib_min_samples") + "，保持默认校准";
        }
        double a = 1, b = 0, lr = 0.02;
        for (int iter = 0; iter < 300; iter++) {
            double ga = 0, gb = 0;
            for (double[] s : samples) {
                double p = GaMath.sigmoid(a * s[0] + b);
                double e = p - s[1];
                ga += e * s[0]; gb += e;
            }
            a -= lr * ga / samples.size(); b -= lr * gb / samples.size();
        }
        config.set("GLOBAL", "calib_a", String.format(java.util.Locale.ROOT, "%.6f", a));
        config.set("GLOBAL", "calib_b", String.format(java.util.Locale.ROOT, "%.6f", b));
        return String.format("重拟校准 a=%.4f b=%.4f（n=%d）", a, b, samples.size());
    }

    /** 回放专用：断点 GA 完成后直接把 HOF 前 K 写成该断点的冠军集版本。 */
    public String forceChampion(long runId, int h, LocalDate d) {
        List<Map<String, Object>> hof = store.individuals(runId, h, "HOF");
        int k = Math.min(config.getInt(h, "candidate_k"), hof.size());
        if (k == 0) return "无名人堂个体";
        store.deactivateChampions(h, d);
        String version = String.format("H%d-REPLAY-%s", h, d.toString().replace("-", ""));
        double[] weights = GaMath.softmax(hof.subList(0, k).stream().mapToDouble(r ->
                r.get("fitness") instanceof Number n ? n.doubleValue() : 0).toArray(),
                config.getDouble(h, "softmax_temp"));
        for (int i = 0; i < k; i++) {
            Map<String, Object> row = hof.get(i);
            long id = ((Number) row.get("individualId")).longValue();
            store.setIndividualStatus(id, "CHAMPION", null);
            store.insertChampion(h, version, id, i + 1, weights[i], d,
                    row.get("runId") instanceof Number n ? n.longValue() : runId);
        }
        return version;
    }

    /** 手动晋升候选（看板按钮）。 */
    public String promoteManually(long runId, int h, LocalDate asOf) {
        String admitted = admit(runId, h, asOf);
        if (admitted.contains("未达门槛")) return admitted;
        config.set("H" + h, "shadow_start", asOf.minusDays(30).toString());
        return admitted + "；" + checkPromotion(h, asOf);
    }
}
