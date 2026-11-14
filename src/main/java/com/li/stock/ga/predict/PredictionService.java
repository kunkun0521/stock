package com.li.stock.ga.predict;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.li.stock.ga.GaMath;
import com.li.stock.ga.GaModel.Bar;
import com.li.stock.ga.config.GaConfig;
import com.li.stock.ga.engine.GaEngine;
import com.li.stock.ga.feature.FeatureService;
import com.li.stock.ga.label.LabelBuilder;
import com.li.stock.ga.repository.GaDataRepository;
import com.li.stock.ga.repository.GaStoreRepository;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PredictJob / VerifyJob / 历史回放（规格 9.1 / 9.2 / 6.8）。
 *
 * <p>幂等：同一唯一键已 PENDING 的记录先删后插，已 VERIFIED 永不改写；
 * 回填收益一律重读 stock_daily 的 t 与到期日 close，不使用 base_close；
 * 回放断点只调用 asOf = 断点日的 GA，严格不泄漏未来数据。</p>
 */
@Service
public class PredictionService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final GaConfig config;
    private final GaDataRepository data;
    private final GaStoreRepository store;
    private final FeatureService features;
    private final ChampionService champions;
    private final LabelBuilder labels;
    private final GaEngine engine;

    public PredictionService(GaConfig config, GaDataRepository data, GaStoreRepository store,
                             FeatureService features, ChampionService champions,
                             LabelBuilder labels, GaEngine engine) {
        this.config = config; this.data = data; this.store = store; this.features = features;
        this.champions = champions; this.labels = labels; this.engine = engine;
    }

    // ------------------------------------------------------------------ PredictJob

    /** 对最新交易日 t 的每个周期生成 CHAMPION 与（候选期）SHADOW 预测。 */
    public String predictDay(LocalDate t, int isReplay, boolean includeShadow) {
        int flag = config.globalInt("adjust_flag");
        Map<LocalDate, List<FeatureService.Vec>> normalized = features.loadNormalized(flag, t, t);
        if (normalized.getOrDefault(t, List.of()).isEmpty()) {
            // 特征缓存缺失时即时重算该日（不改变任何历史数据）
            FeatureService.BuildResult built = features.build(flag, t, t);
            normalized = features.normalizedByDate(built.byDate(), flag, t, t);
        }
        List<FeatureService.Vec> vecs = normalized.getOrDefault(t, List.of());
        if (vecs.isEmpty()) return t + " 无可用特征，跳过预测";

        Map<String, Bar> snapshots = new HashMap<>();
        for (List<Bar> bars : data.loadBarsWithScores(flag, t, t).values()) {
            for (Bar b : bars) snapshots.put(GaDataRepository.key(b.code(), b.market()), b);
        }
        Map<String, Double> amountMa20 = data.amountMa20(flag, t);
        Map<String, Object> pool = store.poolStates();
        Map<String, Object> market = marketOf(t, flag);
        List<String> log = new ArrayList<>();

        for (int h : config.horizons()) {
            ChampionService.Ensemble ens = champions.ensemble(h);
            if (ens.members().isEmpty()) { log.add("H" + h + " 无冠军集"); continue; }
            List<Object[]> rows = buildRows(t, h, flag, "CHAMPION", isReplay, vecs, snapshots,
                    pool, market, amountMa20, ens, ens.versionTag());
            int n = store.replacePendingPredictions(t, h, "CHAMPION", isReplay, rows);
            log.add("H" + h + " 主榜 " + n + " 条");

            if (includeShadow && !store.candidateIndividuals(h).isEmpty()) {
                ChampionService.Ensemble shadowEns = shadowEnsemble(h);
                if (!shadowEns.members().isEmpty()) {
                    List<Object[]> srows = buildRows(t, h, flag, "SHADOW", isReplay, vecs, snapshots,
                            pool, market, amountMa20, shadowEns, "SHADOW-" + h);
                    int sn = store.replacePendingPredictions(t, h, "SHADOW", isReplay, srows);
                    log.add("H" + h + " 影子 " + sn + " 条");
                }
            }
        }
        return String.join("；", log);
    }

    private List<Object[]> buildRows(LocalDate t, int h, int flag, String role, int isReplay,
                                     List<FeatureService.Vec> vecs, Map<String, Bar> snapshots,
                                     Map<String, Object> pool, Map<String, Object> market,
                                     Map<String, Double> amountMa20,
                                     ChampionService.Ensemble ens, String version) {
        List<Object[]> rows = new ArrayList<>();
        for (FeatureService.Vec v : vecs) {
            String key = GaDataRepository.key(v.code(), v.market());
            @SuppressWarnings("unchecked")
            Map<String, Object> state = (Map<String, Object>) pool.get(key);
            String status = state == null ? "ACTIVE" : String.valueOf(state.getOrDefault("poolStatus", "ACTIVE"));
            boolean inPool = !"ELIMINATED".equals(status);
            // 被淘汰的股票只在可复活判断类原因下继续生成影子预测
            if (!inPool) {
                String reason = String.valueOf(state.getOrDefault("reasonCode", ""));
                if (!(reason.equals("POOR_PREDICTABILITY") || reason.equals("EXTREME_VOL")
                        || reason.equals("LOW_LIQUIDITY"))) continue;
            }
            Bar b = snapshots.get(key);
            ChampionService.Scored s = champions.predictOne(ens, v.z());
            String explain;
            try { explain = JSON.writeValueAsString(s.explain()); } catch (Exception e) { explain = "[]"; }
            rows.add(new Object[]{
                    Date.valueOf(t), v.code(), v.market(), flag, h, role, isReplay, inPool ? 1 : 0,
                    version, s.direction(), num(s.pCal()), num(s.confidence()), num(s.scoreRaw()),
                    b == null ? null : num(b.close()), v.limitFlag(), b == null ? 0 : b.isSt(),
                    num(v.vol20()),
                    // amount_ma_20 存 20 日均额（错误分析按此分桶），当日额只在缓存缺失时兜底
                    num(amountMa20.getOrDefault(key, b == null ? Double.NaN : b.amount())),
                    b == null ? null : num(b.peTtm()),
                    market.getOrDefault("regime", 2), explain});
        }
        return rows;
    }

    /** 批量写入前清洗非有限值：NaN/Infinity 在 rewriteBatchedStatements 下会被拼进 SQL 导致语法错误。 */
    private static Double num(double v) {
        return Double.isFinite(v) ? v : null;
    }

    private ChampionService.Ensemble shadowEnsemble(int h) {
        List<Map<String, Object>> candidates = store.candidateIndividuals(h);
        if (candidates.isEmpty()) return new ChampionService.Ensemble(List.of(), "", 0, 1, 0);
        double[] weights = GaMath.softmax(candidates.stream().mapToDouble(c ->
                c.get("fitness") instanceof Number n ? n.doubleValue() : 0).toArray(),
                config.getDouble(h, "softmax_temp"));
        List<com.li.stock.ga.GaModel.Champion> members = new ArrayList<>();
        double delta = 0;
        for (int i = 0; i < candidates.size(); i++) {
            Map<String, Object> c = candidates.get(i);
            var ind = GaEngine.parseIndividual(c, com.li.stock.ga.feature.FeatureKeys.N,
                    config.getInt(h, "min_active_features"));
            long id = c.get("individualId") instanceof Number n ? n.longValue() : 0;
            members.add(new com.li.stock.ga.GaModel.Champion(id, weights[i], ind, "SHADOW"));
            delta += weights[i] * ind.delta;
        }
        return new ChampionService.Ensemble(members, "SHADOW", delta,
                config.getDouble(h, "calib_a"), config.getDouble(h, "calib_b"));
    }

    private Map<String, Object> marketOf(LocalDate t, int flag) {
        var m = data.loadMarket(flag, t, t).get(t);
        return m == null ? Map.of() : Map.of("regime", m.regime(), "mktRet5", m.ret5());
    }

    // ------------------------------------------------------------------ VerifyJob

    /** 到期回填；重复执行结果不变，超过 voidDays 仍凑不满 h 个交易日置 VOID。 */
    public String verify(LocalDate asOf) {
        int flag = config.globalInt("adjust_flag");
        int voidDays = config.globalInt("verify_void_days");
        int batchSize = config.globalInt("verify_batch_size");
        int pageSize = Math.max(1000, config.globalInt("verify_page_size"));

        long cursor = 0;
        int total = 0, verified = 0, voided = 0;
        while (true) {
            List<Map<String, Object>> pending = store.pendingPredictionsAfter(cursor, pageSize);
            if (pending.isEmpty()) break;
            total += pending.size();
            cursor = pending.stream().mapToLong(r -> ((Number) r.get("id")).longValue()).max().orElse(cursor);

            LocalDate min = pending.stream()
                    .map(r -> LocalDate.parse(String.valueOf(r.get("tradeDate")).substring(0, 10)))
                    .min(LocalDate::compareTo).orElse(asOf);
            var tradable = data.tradableDatesByStock(flag, min, asOf);
            Map<String, Double> closes = data.closeMap(flag, min, asOf);

            List<Object[]> updates = new ArrayList<>();
            for (Map<String, Object> r : pending) {
                LocalDate t = LocalDate.parse(String.valueOf(r.get("tradeDate")).substring(0, 10));
                String code = String.valueOf(r.get("code")), market = String.valueOf(r.get("market"));
                int h = ((Number) r.get("horizon")).intValue();
                String key = GaDataRepository.key(code, market);
                LocalDate target = LabelBuilder.targetDate(tradable.getOrDefault(key, List.of()), t, h);
                Object[] args = new Object[8];
                long id = ((Number) r.get("id")).longValue();
                if (target == null) {
                    if (t.plusDays(voidDays).isBefore(asOf)) {
                        args[0] = "VOID"; args[7] = id;
                        for (int i = 1; i < 7; i++) args[i] = null;
                        updates.add(args); voided++;
                    }
                    continue;
                }
                Double c0 = closes.get(key + "|" + t), c1 = closes.get(key + "|" + target);
                if (c0 == null || c1 == null || c0 <= 0) continue;
                double ret = c1 / c0 - 1;
                int actual = ret > 0 ? 1 : ret < 0 ? -1 : 0;
                int pred = ((Number) r.getOrDefault("predDirection", 0)).intValue();
                Integer correct = pred == 0 ? null : (pred == actual ? 1 : 0);
                Object vol20 = r.get("vol20");
                double theta = labels.threshold(h, vol20 instanceof Number n ? n.doubleValue() : 0);
                Integer effective = correct != null && correct == 1 && Math.abs(ret) >= theta ? 1 : 0;
                args[0] = "VERIFIED"; args[1] = Date.valueOf(target); args[2] = c1;
                args[3] = ret; args[4] = actual; args[5] = correct; args[6] = effective; args[7] = id;
                updates.add(args); verified++;
            }
            for (int i = 0; i < updates.size(); i += batchSize) {
                store.batchVerify(updates.subList(i, Math.min(updates.size(), i + batchSize)));
            }
        }
        if (total == 0) return "没有待回填预测";
        return String.format("回填 %d 条 VERIFIED、%d 条 VOID，%d 条仍 PENDING（未到期或缺少行情）",
                verified, voided, total - verified - voided);
    }

    // ------------------------------------------------------------------ 历史回放

    /**
     * Replay（规格 6.8）：每个断点 d 只用 ≤ d 的数据跑 GA，取冠军集后对 (d, d+step] 生成
     * is_replay=1 的预测；已完成断点自动跳过，支持断点续跑。
     */
    public String replay(LocalDate from, LocalDate to, int step, List<Integer> horizons) {
        int flag = config.globalInt("adjust_flag");
        List<LocalDate> dates = data.tradeDates(flag, from, to);
        if (dates.isEmpty()) return "回放区间内没有交易日";
        List<String> log = new ArrayList<>();
        int evolved = 0, predicted = 0, skipped = 0;
        for (int d = 0; d < dates.size(); d += Math.max(1, step)) {
            LocalDate cut = dates.get(d);
            LocalDate next = dates.get(Math.min(dates.size() - 1, d + step));
            boolean newChampion = false;
            // 先完成该断点所有周期的进化并落冠军集，再统一生成 (cut, next] 的预测。
            // 旧实现把 predictDay 放在 horizon 循环内，同一批交易日被重复生成 horizons.size() 次，
            // 既浪费时间又放大了与已回填记录撞唯一键的概率。
            for (int h : horizons) {
                if (store.replayDone(cut, h)) { log.add(cut + " H" + h + " 已完成，跳过"); continue; }
                GaEngine.EvolveResult res = engine.evolve(h, cut, "REPLAY",
                        config.globalInt("replay_pop_size"), config.globalInt("replay_max_gen"), null);
                if (!"SUCCESS".equals(res.status())) {
                    log.add(cut + " H" + h + " 进化失败：" + res.error());
                    continue;
                }
                String version = champions.forceChampion(res.runId(), h, cut);
                newChampion = true;
                evolved++;
                log.add(String.format("%s H%d 回放完成，冠军集 %s，覆盖至 %s", cut, h, version, next));
            }
            if (!newChampion) continue;
            for (int i = d + 1; i <= Math.min(dates.size() - 1, d + step); i++) {
                LocalDate t = dates.get(i);
                // 本次要求的周期在该日都已生成过（尤其是已被 VerifyJob 回填成 VERIFIED 的），
                // 直接跳过：已验证记录永不改写，不重复卷入写入；只补齐缺失周期。
                if (store.replayHorizons(t).containsAll(horizons)) { skipped++; continue; }
                predictDay(t, 1, false);
                predicted++;
            }
        }
        String summary = String.format("进化 %d 个断点周期，生成/补齐 %d 个交易日，跳过 %d 个已完整交易日",
                evolved, predicted, skipped);
        // 任务日志统一由 GaJobRunner / controller 写入，避免与外层重复记录 SUCCESS。
        return log.isEmpty() ? summary : summary + "\n" + String.join("\n", log);
    }
}
