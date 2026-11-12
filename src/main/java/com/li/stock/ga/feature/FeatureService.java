package com.li.stock.ga.feature;

import com.li.stock.ga.GaMath;
import com.li.stock.ga.GaModel.Bar;
import com.li.stock.ga.GaModel.FeatureRow;
import com.li.stock.ga.GaModel.MarketPoint;
import com.li.stock.ga.config.GaConfig;
import com.li.stock.ga.repository.GaDataRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 特征工程（规格 4 / 10.1）。
 *
 * <p><b>防未来函数</b>：每只股票按日期升序一次性计算，位置 i 的特征只使用下标 ≤ i 的数据；
 * 市场环境也只用截至当日的等权收益序列。所有「直接取同名列」的评分派生指标在评分表无记录时
 * 一律从日线自算（本项目 stock_score_daily 只有 11 天历史）。</p>
 *
 * <p>stock_feature_daily 存原始值，标准化在 {@link #normalizedByDate} 中按日截面完成，可重复重算。</p>
 */
@Service
public class FeatureService {

    /** 标准化后的一行：z 向量 + 预测落库所需快照。 */
    public record Vec(String code, String market, LocalDate date, float[] z, boolean valid, int limitFlag,
                      double close, double vol20, double amountMa20, double peTtm, int regime, int isSt) {}

    private final GaDataRepository data;
    private final GaConfig config;

    public FeatureService(GaDataRepository data, GaConfig config) {
        this.data = data;
        this.config = config;
    }

    /** 预热所需自然日：252 个交易日约 365 天，留足余量。 */
    private LocalDate warmStart(LocalDate from) {
        return from.minusDays(Math.max(400, config.globalInt("feature_lookback_days")));
    }

    /**
     * 计算 [from,to] 的原始特征并（可选）落库。返回写入行数。
     * 返回的内存结果可直接交给 GA，避免 feature_persist=0 时重复读库。
     */
    public BuildResult build(int flag, LocalDate from, LocalDate to) {
        Map<String, List<Bar>> bars = data.loadBarsWithScores(flag, warmStart(from), to);
        Map<LocalDate, MarketPoint> market = data.loadMarket(flag, warmStart(from), to);
        List<String> prefixes = config.universePrefixes();

        Map<LocalDate, List<FeatureRow>> byDate = new LinkedHashMap<>();
        Map<LocalDate, Double> mktRet1 = new HashMap<>();
        market.forEach((d, p) -> mktRet1.put(d, p.ret1()));
        int skipped = 0;
        for (Map.Entry<String, List<Bar>> e : bars.entrySet()) {
            List<Bar> series = e.getValue();
            if (series.isEmpty()) continue;
            String code = series.get(0).code();
            if (prefixes.stream().noneMatch(code::startsWith)) { skipped++; continue; }
            StockIndicators ind = new StockIndicators(series, config, mktRet1);
            for (Bar bar : series) {
                if (bar.date().isBefore(from) || bar.date().isAfter(to)) continue;
                float[] v = ind.compute(bar, market.get(bar.date()));
                int limit = ind.limitFlag(bar, config);
                boolean valid = bar.tradable();
                byDate.computeIfAbsent(bar.date(), d -> new ArrayList<>())
                        .add(new FeatureRow(bar.code(), bar.market(), bar.date(), v, valid, limit));
            }
        }

        List<FeatureRow> flat = new ArrayList<>();
        byDate.values().forEach(flat::addAll);
        int written = config.globalBool("feature_persist") ? data.upsertFeatures(flat, flag) : 0;
        return new BuildResult(byDate, written, skipped, flat.size());
    }

    public record BuildResult(Map<LocalDate, List<FeatureRow>> byDate, int written, int skipped, int total) {}

    /** 市场环境（规格 3.1 / 10.1）：池内等权收益构造指数与三状态识别。 */
    public int buildMarket(int flag, LocalDate to) {
        LocalDate from = warmStart(to);
        Map<String, List<Bar>> bars = data.loadBarsWithScores(flag, from, to);
        List<String> prefixes = config.universePrefixes();
        Map<LocalDate, List<Bar>> byDate = new LinkedHashMap<>();
        for (List<Bar> series : bars.values()) {
            if (series.isEmpty() || prefixes.stream().noneMatch(series.get(0).code()::startsWith)) continue;
            for (Bar b : series) if (b.tradable()) byDate.computeIfAbsent(b.date(), d -> new ArrayList<>()).add(b);
        }
        List<LocalDate> dates = new ArrayList<>(byDate.keySet());
        double[] rets = new double[dates.size()];
        double[] idx = new double[dates.size()];
        double[] idxMa20 = new double[dates.size()];
        double[] idxMa60 = new double[dates.size()];
        double cum = 1.0;
        for (int i = 0; i < dates.size(); i++) {
            List<Bar> rows = byDate.get(dates.get(i));
            double sum = 0, up = 0, turn = 0;
            for (Bar b : rows) {
                double r = Double.isFinite(b.pctChg()) ? b.pctChg() / 100.0
                        : (b.preclose() > 0 ? b.close() / b.preclose() - 1 : 0);
                sum += r;
                if (r > 0) up++;
                turn += Double.isFinite(b.turnover()) ? b.turnover() : 0;
            }
            rets[i] = rows.isEmpty() ? 0 : sum / rows.size();
            cum *= (1 + rets[i]);
            idx[i] = cum;
        }
        for (int i = 0; i < dates.size(); i++) {
            idxMa20[i] = GaMath.mean(idx, Math.max(0, i - 19), i + 1);
            idxMa60[i] = GaMath.mean(idx, Math.max(0, i - 59), i + 1);
        }
        List<MarketPoint> points = new ArrayList<>();
        for (int i = 0; i < dates.size(); i++) {
            double ret5 = i >= 5 ? idx[i] / idx[i - 5] - 1 : 0;
            double ret20 = i >= 20 ? idx[i] / idx[i - 20] - 1 : 0;
            double vol20 = GaMath.stdSample(rets, Math.max(0, i - 19), i + 1);
            List<Bar> rows = byDate.get(dates.get(i));
            long up = rows.stream().filter(b -> (Double.isFinite(b.pctChg()) ? b.pctChg() : 0) > 0).count();
            double breadth = rows.isEmpty() ? 0 : (double) up / rows.size();
            double avgTurn = rows.stream().mapToDouble(b -> Double.isFinite(b.turnover()) ? b.turnover() : 0)
                    .average().orElse(0);
            boolean ma20Up = i >= 5 && idxMa20[i] > idxMa20[i - 5];
            int regime = idx[i] > idxMa60[i] && ma20Up ? 1
                    : idx[i] < idxMa60[i] && !ma20Up ? 3 : 2;
            points.add(new MarketPoint(dates.get(i), rows.size(), rets[i], ret5, ret20, vol20,
                    breadth, avgTurn, idx[i], regime));
        }
        return data.upsertMarket(points, flag);
    }

    /**
     * 读回原始特征并按日截面做 1%/99% 缩尾 + z-score（规格 4.2）。
     * 市场特征用自身滚动 252 日时序 z-score；缺失率超阈值的特征整体置 0 并记告警。
     */
    public Map<LocalDate, List<Vec>> normalizedByDate(Map<LocalDate, List<FeatureRow>> raw,
                                                      int flag, LocalDate from, LocalDate to) {
        double q = config.globalDouble("winsor_q");
        double missingRatio = config.globalDouble("missing_feature_ratio");
        int zWindow = config.globalInt("market_z_window");
        Map<LocalDate, MarketPoint> market = data.loadMarket(flag, warmStart(from), to);
        List<LocalDate> allDates = new ArrayList<>(market.keySet());
        double[] mktRet5 = new double[allDates.size()], mktVol20 = new double[allDates.size()],
                breadth = new double[allDates.size()], regime = new double[allDates.size()];
        for (int i = 0; i < allDates.size(); i++) {
            MarketPoint p = market.get(allDates.get(i));
            mktRet5[i] = p.ret5(); mktVol20[i] = p.vol20(); breadth[i] = p.breadthUp(); regime[i] = p.regime();
        }
        Map<LocalDate, Integer> datePos = new HashMap<>();
        for (int i = 0; i < allDates.size(); i++) datePos.put(allDates.get(i), i);

        Map<LocalDate, List<Vec>> out = new LinkedHashMap<>();
        for (Map.Entry<LocalDate, List<FeatureRow>> e : raw.entrySet()) {
            LocalDate d = e.getKey();
            List<FeatureRow> rows = e.getValue();
            int n = rows.size();
            float[][] z = new float[FeatureKeys.N][n];
            for (int j = 0; j < FeatureKeys.STOCK_N; j++) {
                double[] vals = new double[n];
                boolean[] valid = new boolean[n];
                for (int i = 0; i < n; i++) {
                    float[] f = rows.get(i).values();
                    vals[i] = j < f.length ? f[j] : Double.NaN;
                    valid[i] = rows.get(i).valid() && Double.isFinite(vals[i]);
                }
                float[] col = new float[n];
                boolean dropped = GaMath.winsorizeZ(vals, valid, q, missingRatio, col);
                if (dropped) System.err.println("[GA][WARN] " + d + " 特征 " + FeatureKeys.ALL.get(j)
                        + " 缺失率超过 " + missingRatio + "，该日整体置 0");
                z[j] = col;
            }
            int pos = datePos.getOrDefault(d, -1);
            for (int k = 0; k < FeatureKeys.MARKET_FEATURES.size(); k++) {
                double[] series = switch (k) {
                    case 0 -> mktRet5; case 1 -> mktVol20; case 2 -> breadth; default -> regime;
                };
                float v = pos < 0 ? 0f : (float) GaMath.timeSeriesZ(series, pos, zWindow);
                float[] col = new float[n];
                Arrays.fill(col, v);
                z[FeatureKeys.STOCK_N + k] = col;
            }
            List<Vec> vecs = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                FeatureRow r = rows.get(i);
                float[] row = new float[FeatureKeys.N];
                for (int j = 0; j < FeatureKeys.N; j++) row[j] = z[j][i];
                int vi = FeatureKeys.IDX_VOL_20;
                vecs.add(new Vec(r.code(), r.market(), d, row, r.valid(), r.limitFlag(), 0,
                        vi < r.values().length ? r.values()[vi] : 0, 0, 0,
                        pos >= 0 ? (int) regime[pos] : 2, 0));
            }
            out.put(d, vecs);
        }
        return out;
    }

    /** 从库读回特征再标准化（GA / 预测走这条路径）。 */
    public Map<LocalDate, List<Vec>> loadNormalized(int flag, LocalDate from, LocalDate to) {
        return normalizedByDate(data.loadFeatures(flag, from, to), flag, from, to);
    }

    /** 规格 0 数据质检：价格口径抽样 + 关键字段缺失率 + 主键重复。 */
    public List<String> qualityCheck(int flag, LocalDate date) {
        List<String> issues = new ArrayList<>();
        String price = data.checkPriceConsistency();
        if (!price.isEmpty()) issues.add(price);
        int n = data.countFeatureDate(date, flag);
        if (n == 0) issues.add(date + " 尚无特征数据，请先运行 FeatureJob");
        return issues;
    }

    // ================================================================== 个股指标计算

    /** 一只股票的全序列预计算，compute(bar) 只取 ≤ 该位置的数据。 */
    static final class StockIndicators {
        private final List<Bar> bars;
        private final int n;
        private final double[] c, o, h, l, pc, vol, amt, turn, amp, pct;
        private final double[] ma5, ma10, ma20, ma60, dif, dea, rsi6, rsi14, k, dd, j, atr, adx;
        private final double[] obv, macdHist, bollPct, vol20, vol60, atrPct, amp5, dd20, dd252;
        private final double[] downVol, beta, vr, amountRatio, turnover20, turn5, turnZ, obvSlope;
        private final double[] amihud, gap, body, upper, lower, closePos, streak, limitCnt;
        private final double[] ret1, ret3, ret5v, ret10, ret21, ret63, ret126, ret252;
        private final double[] ep, bp, sp, cfp, loss;
        private final double[] scoreTotal, scoreValue, scoreMom, scoreTrend, scoreRisk, scoreLiq;
        private final double[] scoreRet21, scoreRet63, scoreRet126, scoreRet252, scoreVol60, scoreDd252;
        private final double[] scoreAmountRatio, scoreTurnover;
        private final double[] mktRet;
        private final GaConfig config;

        StockIndicators(List<Bar> bars, GaConfig config, Map<LocalDate, Double> marketRet1) {
            this.bars = bars; this.n = bars.size(); this.config = config;
            mktRet = new double[n];
            for (int i = 0; i < n; i++) mktRet[i] = marketRet1.getOrDefault(bars.get(i).date(), 0.0);
            c = new double[n]; o = new double[n]; h = new double[n]; l = new double[n]; pc = new double[n];
            vol = new double[n]; amt = new double[n]; turn = new double[n]; amp = new double[n]; pct = new double[n];
            for (int i = 0; i < n; i++) {
                Bar b = bars.get(i);
                c[i] = b.close(); o[i] = b.open(); h[i] = b.high(); l[i] = b.low(); pc[i] = b.preclose();
                vol[i] = fin(b.volume()); amt[i] = fin(b.amount()); turn[i] = fin(b.turnover());
                amp[i] = fin(b.amplitude()); pct[i] = fin(b.pctChg());
            }
            ma5 = sma(c, 5); ma10 = sma(c, 10); ma20 = sma(c, 20); ma60 = sma(c, 60);
            double[] e12 = GaMath.ema(c, 12), e26 = GaMath.ema(c, 26);
            dif = new double[n];
            for (int i = 0; i < n; i++) dif[i] = e12[i] - e26[i];
            dea = GaMath.ema(dif, 9);
            macdHist = new double[n];
            for (int i = 0; i < n; i++) macdHist[i] = c[i] > 0 ? 2 * (dif[i] - dea[i]) / c[i] : 0;
            bollPct = new double[n];
            for (int i = 0; i < n; i++) {
                double sd = GaMath.stdPop(c, Math.max(0, i - 19), i + 1);
                bollPct[i] = GaMath.div(c[i] - (ma20[i] - 2 * sd), 4 * sd, 0);
            }
            rsi6 = rsi(c, 6); rsi14 = rsi(c, 14);
            double[] kk = new double[n], ddv = new double[n], jv = new double[n];
            double kv = 50, dv = 50;
            for (int i = 0; i < n; i++) {
                int from = Math.max(0, i - 8);
                double hh = max(h, from, i + 1), ll = min(l, from, i + 1);
                double rsv = hh - ll < GaMath.EPS ? 50 : (c[i] - ll) / (hh - ll) * 100;
                kv = 2.0 / 3 * kv + 1.0 / 3 * rsv;
                dv = 2.0 / 3 * dv + 1.0 / 3 * kv;
                kk[i] = kv; ddv[i] = dv; jv[i] = 3 * kv - 2 * dv;
            }
            k = kk; dd = ddv; j = jv;
            double[] tr = new double[n], pdm = new double[n], mdm = new double[n], dx = new double[n];
            for (int i = 0; i < n; i++) {
                tr[i] = i == 0 ? h[i] - l[i] : Math.max(h[i] - l[i],
                        Math.max(Math.abs(h[i] - pc[i]), Math.abs(l[i] - pc[i])));
                double up = i == 0 ? 0 : h[i] - h[i - 1], dn = i == 0 ? 0 : l[i - 1] - l[i];
                pdm[i] = up > dn && up > 0 ? up : 0;
                mdm[i] = dn > up && dn > 0 ? dn : 0;
            }
            double[] str = GaMath.wilder(tr, 14), spdm = GaMath.wilder(pdm, 14), smdm = GaMath.wilder(mdm, 14);
            atr = str;
            for (int i = 0; i < n; i++) {
                double s = spdm[i] + smdm[i];
                dx[i] = s < GaMath.EPS ? 0 : 100 * Math.abs(spdm[i] - smdm[i]) / s;
            }
            adx = GaMath.wilder(dx, 14);
            atrPct = new double[n];
            for (int i = 0; i < n; i++) atrPct[i] = c[i] > 0 ? atr[i] / c[i] : 0;
            obv = new double[n];
            for (int i = 0; i < n; i++) obv[i] = (i == 0 ? 0 : obv[i - 1]) + (c[i] > c[Math.max(0, i - 1)] ? vol[i]
                    : c[i] < c[Math.max(0, i - 1)] ? -vol[i] : 0);
            vol20 = new double[n]; vol60 = new double[n]; downVol = new double[n];
            double[] logRet = new double[n];
            for (int i = 0; i < n; i++) logRet[i] = i > 0 && c[i - 1] > 0 && c[i] > 0 ? Math.log(c[i] / c[i - 1]) : 0;
            for (int i = 0; i < n; i++) {
                double[] pr = new double[Math.min(20, i + 1)], nr = new double[Math.min(20, i + 1)];
                int cnt = 0;
                for (int t = Math.max(1, i - 19); t <= i; t++) {
                    pr[cnt] = pct[t] / 100.0;
                    nr[cnt] = logRet[t] < 0 ? logRet[t] : 0;
                    cnt++;
                }
                vol20[i] = GaMath.stdSample(Arrays.copyOf(pr, cnt), 0, cnt);
                downVol[i] = GaMath.stdSample(Arrays.copyOf(nr, cnt), 0, cnt);
                vol60[i] = GaMath.stdSample(logRet, Math.max(0, i - 59), i + 1) * Math.sqrt(252);
            }
            amp5 = rollingMean(amp, 5);
            dd20 = new double[n]; dd252 = new double[n];
            for (int i = 0; i < n; i++) {
                dd20[i] = GaMath.div(c[i], max(c, Math.max(0, i - 19), i + 1), 1) - 1;
                double peak = 0, worst = 0;
                for (int t = Math.max(0, i - 251); t <= i; t++) {
                    peak = Math.max(peak, c[t]);
                    if (peak > 0) worst = Math.min(worst, c[t] / peak - 1);
                }
                dd252[i] = worst;
            }
            double[] meanAmt = rollingMean(amt, 20), meanVol = rollingMean(vol, 20);
            vr = new double[n]; amountRatio = new double[n]; turnover20 = rollingMean(turn, 20);
            turn5 = rollingMean(turn, 5); turnZ = new double[n]; obvSlope = new double[n];
            for (int i = 0; i < n; i++) {
                vr[i] = GaMath.div(GaMath.mean(vol, Math.max(0, i - 4), i + 1), meanVol[i], 0);
                amountRatio[i] = GaMath.div(amt[i], meanAmt[i], 0);
                double sd = GaMath.stdSample(turn, Math.max(0, i - 19), i + 1);
                turnZ[i] = GaMath.div(turn[i] - turnover20[i], sd, 0);
                obvSlope[i] = i >= 10 ? GaMath.div(obv[i] - obv[i - 10], 10 * meanVol[i], 0) : 0;
            }
            amihud = new double[n];
            for (int i = 0; i < n; i++) {
                double s = 0; int cnt = 0;
                for (int t = Math.max(0, i - 19); t <= i; t++) { s += Math.abs(pct[t]) / 100.0 / (amt[t] + GaMath.EPS); cnt++; }
                amihud[i] = Math.log(1 + 1e9 * (cnt == 0 ? 0 : s / cnt));
            }
            gap = new double[n]; body = new double[n]; upper = new double[n]; lower = new double[n];
            closePos = new double[n]; streak = new double[n]; limitCnt = new double[n];
            for (int i = 0; i < n; i++) {
                gap[i] = pc[i] > 0 ? o[i] / pc[i] - 1 : 0;
                double rng = h[i] - l[i] + GaMath.EPS;
                body[i] = (c[i] - o[i]) / rng;
                upper[i] = (h[i] - Math.max(o[i], c[i])) / rng;
                lower[i] = (Math.min(o[i], c[i]) - l[i]) / rng;
                closePos[i] = (c[i] - l[i]) / rng;
                int s = 0;
                for (int t = i; t > 0 && Math.abs(s) < 60; t--) {
                    double ch = c[t] - c[t - 1];
                    if (ch > 0) { if (s < 0) break; s++; }
                    else if (ch < 0) { if (s > 0) break; s--; }
                    else break;
                }
                streak[i] = s;
                int cnt = 0;
                for (int t = Math.max(0, i - 9); t <= i; t++) if (isLimitUp(t)) cnt++;
                limitCnt[i] = cnt;
            }
            ret1 = ret(c, 1); ret3 = ret(c, 3); ret5v = ret(c, 5); ret10 = ret(c, 10);
            ret21 = ret(c, 21); ret63 = ret(c, 63); ret126 = ret(c, 126); ret252 = ret(c, 252);
            beta = new double[n];
            for (int i = 0; i < n; i++) beta[i] = rollingBeta(i);
            ep = new double[n]; bp = new double[n]; sp = new double[n]; cfp = new double[n]; loss = new double[n];
            scoreTotal = new double[n]; scoreValue = new double[n]; scoreMom = new double[n];
            scoreTrend = new double[n]; scoreRisk = new double[n]; scoreLiq = new double[n];
            scoreRet21 = new double[n]; scoreRet63 = new double[n]; scoreRet126 = new double[n];
            scoreRet252 = new double[n]; scoreVol60 = new double[n]; scoreDd252 = new double[n];
            scoreAmountRatio = new double[n]; scoreTurnover = new double[n];
            for (int i = 0; i < n; i++) {
                Bar b = bars.get(i);
                ep[i] = inverse(b.peTtm()); bp[i] = inverse(b.pbMrq());
                sp[i] = inverse(b.psTtm()); cfp[i] = inverse(b.pcfNcfTtm());
                loss[i] = b.peTtm() != null && b.peTtm() <= 0 ? 1 : 0;
                scoreTotal[i] = b.scoreOr(com.li.stock.ga.GaModel.S_TOTAL, Double.NaN);
                scoreValue[i] = b.scoreOr(com.li.stock.ga.GaModel.S_VALUE, Double.NaN);
                scoreMom[i] = b.scoreOr(com.li.stock.ga.GaModel.S_MOMENTUM, Double.NaN);
                scoreTrend[i] = b.scoreOr(com.li.stock.ga.GaModel.S_TREND, Double.NaN);
                scoreRisk[i] = b.scoreOr(com.li.stock.ga.GaModel.S_RISK, Double.NaN);
                scoreLiq[i] = b.scoreOr(com.li.stock.ga.GaModel.S_LIQUIDITY, Double.NaN);
                scoreRet21[i] = b.scoreOr(com.li.stock.ga.GaModel.S_RET21, Double.NaN);
                scoreRet63[i] = b.scoreOr(com.li.stock.ga.GaModel.S_RET63, Double.NaN);
                scoreRet126[i] = b.scoreOr(com.li.stock.ga.GaModel.S_RET126, Double.NaN);
                scoreRet252[i] = b.scoreOr(com.li.stock.ga.GaModel.S_RET252, Double.NaN);
                scoreVol60[i] = b.scoreOr(com.li.stock.ga.GaModel.S_VOL60, Double.NaN);
                scoreDd252[i] = b.scoreOr(com.li.stock.ga.GaModel.S_DD252, Double.NaN);
                scoreAmountRatio[i] = b.scoreOr(com.li.stock.ga.GaModel.S_AMOUNT_RATIO, Double.NaN);
                scoreTurnover[i] = b.scoreOr(com.li.stock.ga.GaModel.S_TURNOVER, Double.NaN);
            }
        }

        /** 规格 4.1 顺序：构造一行原始特征向量。 */
        float[] compute(Bar b, MarketPoint m) {
            int i = indexOf(b.date());
            float[] v = new float[FeatureKeys.N];
            if (i < 0) { Arrays.fill(v, Float.NaN); return v; }
            set(v, "ret_1", choose(ret1[i], Double.NaN));
            set(v, "ret_3", choose(ret3[i], Double.NaN));
            set(v, "ret_5", choose(ret5v[i], Double.NaN));
            set(v, "ret_10", choose(ret10[i], Double.NaN));
            set(v, "ret_21", choose(scoreRet21[i], ret21[i]));
            set(v, "ret_63", choose(scoreRet63[i], ret63[i]));
            set(v, "ret_126", choose(scoreRet126[i], ret126[i]));
            set(v, "ret_252", choose(scoreRet252[i], ret252[i]));
            set(v, "rel_ret_5", m == null ? ret5v[i] : ret5v[i] - m.ret5());
            set(v, "ma_gap_5", gapToMa(c[i], ma5[i]));
            set(v, "ma_gap_10", gapToMa(c[i], ma10[i]));
            set(v, "ma_gap_20", gapToMa(c[i], ma20[i]));
            set(v, "ma_gap_60", gapToMa(c[i], ma60[i]));
            set(v, "ma_slope_20", i >= 5 ? GaMath.div(ma20[i], ma20[i - 5], 1) - 1 : Double.NaN);
            set(v, "macd_hist", macdHist[i]);
            set(v, "boll_pct_20", bollPct[i]);
            set(v, "adx_14", adx[i]);
            set(v, "rsi_6", rsi6[i]);
            set(v, "rsi_14", rsi14[i]);
            set(v, "kdj_j", j[i]);
            set(v, "cci_14", cci(i));
            set(v, "vol_20", vol20[i]);
            set(v, "vol_60", choose(scoreVol60[i], vol60[i]));
            set(v, "atr_14_pct", atrPct[i]);
            set(v, "amp_5", amp5[i]);
            set(v, "dd_20", dd20[i]);
            set(v, "drawdown_252", choose(scoreDd252[i], dd252[i]));
            set(v, "downvol_20", downVol[i]);
            set(v, "beta_60", beta[i]);
            set(v, "vr_5_20", vr[i]);
            set(v, "amount_ratio_20", choose(scoreAmountRatio[i], amountRatio[i]));
            set(v, "turnover_20", choose(scoreTurnover[i], turnover20[i]));
            set(v, "turn_5", turn5[i]);
            set(v, "turn_z_20", turnZ[i]);
            set(v, "obv_slope_10", obvSlope[i]);
            set(v, "amihud_20", amihud[i]);
            set(v, "gap_pct", gap[i]);
            set(v, "body_pct", body[i]);
            set(v, "upper_shadow", upper[i]);
            set(v, "lower_shadow", lower[i]);
            set(v, "close_pos", closePos[i]);
            set(v, "up_streak", streak[i]);
            set(v, "limit_cnt_10", limitCnt[i]);
            set(v, "ep", ep[i]); set(v, "bp", bp[i]); set(v, "sp", sp[i]); set(v, "cfp", cfp[i]);
            set(v, "loss_flag", loss[i]);
            set(v, "total_score", scoreTotal[i]);
            set(v, "value_score", scoreValue[i]);
            set(v, "momentum_score", scoreMom[i]);
            set(v, "trend_score", scoreTrend[i]);
            set(v, "risk_score", scoreRisk[i]);
            set(v, "liquidity_score", scoreLiq[i]);
            set(v, "mkt_ret_5", m == null ? Double.NaN : m.ret5());
            set(v, "mkt_vol_20", m == null ? Double.NaN : m.vol20());
            set(v, "breadth_up", m == null ? Double.NaN : m.breadthUp());
            set(v, "regime_code", m == null ? Double.NaN : m.regime());
            return v;
        }

        int limitFlag(Bar b, GaConfig cfg) {
            int i = indexOf(b.date());
            if (i < 0 || !b.tradable()) return 0;
            double ampLimit = b.code().startsWith("30") ? cfg.globalDouble("limit_gem")
                    : b.isSt() == 1 ? cfg.globalDouble("limit_st") : cfg.globalDouble("limit_main");
            double r = pc[i] > 0 ? c[i] / pc[i] - 1 : 0;
            double eps = cfg.globalDouble("limit_touch_eps");
            if (r >= ampLimit - eps) return 1;
            if (r <= -ampLimit + eps) return -1;
            return 0;
        }

        private boolean isLimitUp(int i) {
            Bar b = bars.get(i);
            if (!b.tradable() || pc[i] <= 0) return false;
            double lim = b.code().startsWith("30") ? 0.20 : b.isSt() == 1 ? 0.05 : 0.10;
            return c[i] / pc[i] - 1 >= lim - config.globalDouble("limit_touch_eps");
        }

        private int indexOf(LocalDate d) {
            // 日期升序，二分查找
            int lo = 0, hi = n - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                int cmp = bars.get(mid).date().compareTo(d);
                if (cmp == 0) return mid;
                if (cmp < 0) lo = mid + 1; else hi = mid - 1;
            }
            return -1;
        }

        private double cci(int i) {
            int from = Math.max(0, i - 13), cnt = i - from + 1;
            double[] tp = new double[cnt];
            for (int t = from; t <= i; t++) tp[t - from] = (h[t] + l[t] + c[t]) / 3;
            double m = GaMath.mean(tp, 0, cnt), md = 0;
            for (double x : tp) md += Math.abs(x - m);
            md /= cnt;
            return GaMath.div(tp[cnt - 1] - m, 0.015 * md, 0);
        }

        /** 规格 4.1：近 60 日个股日收益对市场等权日收益的回归斜率 cov/var。 */
        private double rollingBeta(int i) {
            int from = Math.max(1, i - 59);
            int cnt = i - from + 1;
            if (cnt < 5) return Double.NaN;
            double[] a = new double[cnt], b = new double[cnt];
            for (int t = from; t <= i; t++) {
                a[t - from] = c[t - 1] > 0 ? c[t] / c[t - 1] - 1 : 0;
                b[t - from] = mktRet[t];
            }
            double ma = GaMath.mean(a, 0, cnt), mb = GaMath.mean(b, 0, cnt);
            double cov = 0, var = 0;
            for (int t = 0; t < cnt; t++) { cov += (a[t] - ma) * (b[t] - mb); var += (b[t] - mb) * (b[t] - mb); }
            return var < GaMath.EPS ? 0 : cov / var;
        }

        private static void set(float[] v, String key, double x) {
            int idx = FeatureKeys.indexOf(key);
            if (idx >= 0) v[idx] = (float) x;
        }

        private static double choose(double preferred, double fallback) {
            return Double.isFinite(preferred) ? preferred : fallback;
        }

        private static double gapToMa(double c, double ma) { return ma > 0 ? c / ma - 1 : Double.NaN; }

        private static double inverse(Double x) {
            return x == null || !Double.isFinite(x) || x <= 0 ? Double.NaN : 1.0 / x;
        }

        private static double fin(double x) { return Double.isFinite(x) ? x : 0; }

        private static double[] sma(double[] v, int w) {
            double[] out = new double[v.length];
            for (int i = 0; i < v.length; i++) out[i] = GaMath.mean(v, Math.max(0, i - w + 1), i + 1);
            return out;
        }

        private static double[] rollingMean(double[] v, int w) { return sma(v, w); }

        private static double[] ret(double[] c, int d) {
            double[] out = new double[c.length];
            for (int i = 0; i < c.length; i++) {
                out[i] = i >= d && c[i - d] > 0 ? c[i] / c[i - d] - 1 : Double.NaN;
            }
            return out;
        }

        private static double[] rsi(double[] c, int period) {
            int n = c.length;
            double[] gain = new double[n], lossArr = new double[n];
            for (int i = 1; i < n; i++) {
                double ch = c[i] - c[i - 1];
                gain[i] = Math.max(0, ch); lossArr[i] = Math.max(0, -ch);
            }
            double[] ag = GaMath.wilder(gain, period), al = GaMath.wilder(lossArr, period);
            double[] out = new double[n];
            for (int i = 0; i < n; i++) {
                double rs = al[i] < GaMath.EPS ? (ag[i] > 0 ? 1e6 : 0) : ag[i] / al[i];
                out[i] = 100 - 100 / (1 + rs);
            }
            return out;
        }

        private static double max(double[] a, int from, int to) {
            double m = Double.NEGATIVE_INFINITY;
            for (int i = from; i < to; i++) m = Math.max(m, a[i]);
            return Double.isFinite(m) ? m : 0;
        }

        private static double min(double[] a, int from, int to) {
            double m = Double.POSITIVE_INFINITY;
            for (int i = from; i < to; i++) m = Math.min(m, a[i]);
            return Double.isFinite(m) ? m : 0;
        }
    }
}
