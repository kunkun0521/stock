package com.li.stock.ga;

import com.li.stock.ga.GaModel.Fold;
import com.li.stock.ga.GaModel.Individual;
import com.li.stock.ga.GaModel.SampleMatrix;
import com.li.stock.ga.GaModel.Segment;
import com.li.stock.ga.config.GaConfig;
import com.li.stock.ga.engine.GaEngine;
import com.li.stock.ga.feature.FeatureKeys;
import com.li.stock.ga.label.LabelBuilder;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GA 核心验收用例（规格 15.1 的第 2、3、4、5 条）：纯内存构造数据，不依赖数据库。
 */
class GaCoreAcceptanceTest {

    /** 用例 5：训练段与验证段之间恰好隔 h 个交易日，Hold-out 不出现在任何折里。 */
    @Test
    void splitKeepsPurgeGapAndHoldout() {
        int h = 5, holdout = 20, labeled = 320;
        GaEngine.Split s = GaEngine.split(labeled, h, 3, 120, 30, 30, holdout, 60);
        assertEquals(3, s.folds().size());
        assertEquals(labeled - holdout, s.holdoutFrom());
        for (Fold f : s.folds()) {
            assertEquals(h, f.purgeGap(), "训练段与验证段之间必须恰好隔 h 个交易日");
            assertTrue(f.validTo() < s.holdoutFrom(), "Hold-out 样本不得出现在任何折里");
            assertTrue(f.trainFrom() >= 0);
        }
    }

    /** 用例 5：数据不足时自动降级折数与训练段长度并写告警。 */
    @Test
    void splitDegradesWhenDataIsShort() {
        GaEngine.Split s = GaEngine.split(160, 5, 3, 120, 30, 30, 20, 60);
        assertFalse(s.warnings().isEmpty(), "降级时必须给出告警");
        assertTrue(s.folds().size() < 3, "折数应降到 3 以下");
    }

    /** 用例 2：到期日按 tradestatus=1 的交易日计，跳过停牌；不足 h 个返回 null。 */
    @Test
    void targetDateSkipsSuspendedDays() {
        List<LocalDate> tradable = List.of(
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 7), LocalDate.of(2026, 1, 8),
                LocalDate.of(2026, 1, 12)); // 1/6、1/9~1/11 视为停牌/非交易日
        assertEquals(LocalDate.of(2026, 1, 7), LabelBuilder.targetDate(tradable, LocalDate.of(2026, 1, 5), 1));
        assertEquals(LocalDate.of(2026, 1, 12), LabelBuilder.targetDate(tradable, LocalDate.of(2026, 1, 5), 3));
        assertNull(LabelBuilder.targetDate(tradable, LocalDate.of(2026, 1, 5), 5));
    }

    /** 用例 3：染色体 JSON 可还原，且至少激活 minActive 个特征。 */
    @Test
    void chromosomeRoundTripKeepsAtLeastThreeActiveFeatures() {
        int n = FeatureKeys.N;
        double[] w = new double[n];
        int[] m = new int[n];
        w[0] = 1.25; w[1] = -0.5;
        m[0] = 1; m[1] = 1;
        Individual ind = new Individual(w, m, 0.1, 0.05);
        String json = ind.chromosomeJson();
        assertTrue(json.contains("\"w\":["));
        Individual restored = GaEngine.parseIndividual(Map.of("chromosomeJson", json, "individualId", 7L), n, 3);
        assertEquals(1.25, restored.w[0], 1e-6);
        assertEquals(-0.5, restored.w[1], 1e-6);
        assertTrue(restored.active() >= 3, "变异/还原后至少 3 位特征开关为 1");
    }

    /** 用例 4：完全相关特征 BalAcc 接近 1；无信号时 F_seg 接近 0；覆盖率不足时惩罚生效。 */
    @Test
    void fitnessRewardsSignalAndPunishesLowCoverage() {
        int dates = 6, per = 10, rows = dates * per, n = FeatureKeys.N;
        int[][] byDate = new int[dates][per];
        float[][] z = new float[rows][n];
        int[] y = new int[rows], dateIdx = new int[rows];
        double[] ret = new double[rows];
        boolean[] noise = new boolean[rows];
        String[] code = new String[rows];
        for (int d = 0; d < dates; d++) {
            for (int i = 0; i < per; i++) {
                int idx = d * per + i;
                byDate[d][i] = idx;
                y[idx] = i % 2 == 0 ? 1 : 0;
                z[idx][0] = y[idx] == 1 ? 2f : -2f;   // 与标签完全相关
                ret[idx] = y[idx] == 1 ? 0.03 : -0.03;
                dateIdx[idx] = d;
                code[idx] = "00000" + idx;
            }
        }
        GaConfig cfg = new GaConfig(null); // 只用代码默认值，不触碰数据库
        SampleMatrix mx = new SampleMatrix(z, y, ret, noise, dateIdx, code, n);
        GaEngine.FitnessEvaluator eval = new GaEngine.FitnessEvaluator(cfg, byDate);

        int[] mask = new int[n];
        mask[0] = 1;
        double[] strong = new double[n];
        strong[0] = 3;
        Segment good = eval.segment(new Individual(strong, mask, 0, 0.01), mx, 0, dates - 1, 5);
        assertTrue(good.balacc() > 0.9, "完全相关特征的 BalAcc 应接近 1，实际 " + good.balacc());
        assertTrue(good.fSeg() > 0.5, "有效信号的段内得分应明显大于 0，实际 " + good.fSeg());

        // 全部观望 → coverage = 0 < cov_min → F_seg = 0
        Segment abstain = eval.segment(new Individual(new double[n], mask, 0, 0.05), mx, 0, dates - 1, 5);
        assertEquals(0.0, abstain.coverage(), 1e-9);
        assertEquals(0.0, abstain.fSeg(), 1e-9);

        // 无信息特征：把第 0 个特征改成与标签无关的循环值
        for (int idx = 0; idx < rows; idx++) z[idx][0] = (idx % 3) - 1;
        Segment random = eval.segment(new Individual(strong, mask, 0, 0.0), mx, 0, dates - 1, 5);
        assertTrue(random.balacc() < 0.8, "随机特征的 BalAcc 不应接近 1，实际 " + random.balacc());
        assertTrue(random.fSeg() < good.fSeg(), "随机特征的 F_seg 必须低于有效信号");
    }

    /** 数学工具：sigmoid/logit 互逆、Spearman 完全单调为 1、softmax 权重和为 1。 */
    @Test
    void mathHelpersAreConsistent() {
        assertEquals(2.0, GaMath.logit(GaMath.sigmoid(2.0)), 1e-9);
        double[] a = {1, 2, 3, 4, 5}, b = {3, 6, 9, 12, 15};
        assertEquals(1.0, GaMath.spearman(a, b), 1e-9);
        double[] w = GaMath.softmax(new double[]{1, 2, 3}, 0.1);
        assertEquals(1.0, w[0] + w[1] + w[2], 1e-9);
    }
}
