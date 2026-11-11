package com.li.stock.service;

import com.li.stock.domain.model.ScoreDailyPoint;
import com.li.stock.domain.vo.ScoreContinuityItemVO;
import com.li.stock.domain.vo.ScoreContinuityVO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 连续评分区间判定：边界、缺日、矩阵计数、排序。 */
class ScoreContinuityAnalyzerTest {

    private static final List<LocalDate> DATES = List.of(
            LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 6), LocalDate.of(2026, 11, 9),
            LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 11));

    private static ScoreDailyPoint point(String code, LocalDate date, String score) {
        return new ScoreDailyPoint(code, "SZ", date, new BigDecimal(score),
                "名称" + code, "SZ", "主板", "电子", new BigDecimal("10.00"));
    }

    private static List<ScoreDailyPoint> allDays(String code, String... scores) {
        List<ScoreDailyPoint> rows = new ArrayList<>();
        for (int i = 0; i < DATES.size(); i++) {
            rows.add(point(code, DATES.get(i), scores[i]));
        }
        return rows;
    }

    private static long count(ScoreContinuityAnalyzer.Analysis analysis, int days, String band) {
        return analysis.matrix().stream()
                .filter(cell -> cell.days() == days && cell.band().equals(band))
                .findFirst().orElseThrow().count();
    }

    @Test
    void bandsAreLeftClosedRightOpen() {
        ScoreContinuityVO.Band b30 = ScoreContinuityAnalyzer.band("30-50");
        assertTrue(ScoreContinuityAnalyzer.contains(b30, new BigDecimal("30")));
        assertTrue(ScoreContinuityAnalyzer.contains(b30, new BigDecimal("49.9999")));
        assertFalse(ScoreContinuityAnalyzer.contains(b30, new BigDecimal("50")));
        assertFalse(ScoreContinuityAnalyzer.contains(b30, new BigDecimal("29.9999")));

        ScoreContinuityVO.Band b75 = ScoreContinuityAnalyzer.band("60-75");
        assertTrue(ScoreContinuityAnalyzer.contains(b75, new BigDecimal("60")));
        assertFalse(ScoreContinuityAnalyzer.contains(b75, new BigDecimal("75")));

        ScoreContinuityVO.Band b90 = ScoreContinuityAnalyzer.band("90+");
        assertTrue(ScoreContinuityAnalyzer.contains(b90, new BigDecimal("90")));
        assertTrue(ScoreContinuityAnalyzer.contains(b90, new BigDecimal("100")));
        assertEquals(6, ScoreContinuityAnalyzer.BANDS.size());
    }

    @Test
    void missingOrOutOfBandDayBreaksContinuity() {
        List<ScoreDailyPoint> rows = new ArrayList<>();
        rows.addAll(allDays("000001", "65", "65", "65", "65", "65"));   // 3 天、5 天均命中 60-75
        rows.addAll(allDays("000002", "40", "40", "40", "40", "40"));   // 命中 30-50
        rows.addAll(allDays("000003", "65", "65", "65", "65", "80"));   // 最近 3 天跨区间
        rows.addAll(allDays("000004", "65", "65", "65", "65", "65"));
        rows.removeIf(p -> p.code().equals("000004") && p.tradeDate().equals(DATES.get(1))); // 缺第 2 天
        for (int i = 2; i < DATES.size(); i++) {
            rows.add(point("000005", DATES.get(i), "65"));              // 只有最近 3 天记录
        }

        ScoreContinuityAnalyzer.Analysis analysis = ScoreContinuityAnalyzer.analyze(
                rows, DATES, 3, ScoreContinuityAnalyzer.band("60-75"), "code");

        assertEquals(5, analysis.universeSize());
        assertEquals(3L, count(analysis, 3, "60-75"));   // 000001 / 000004 / 000005
        assertEquals(1L, count(analysis, 5, "60-75"));   // 仅 000001
        assertEquals(1L, count(analysis, 3, "30-50"));   // 仅 000002
        assertEquals(1L, count(analysis, 5, "30-50"));   // 000002 五天也都在 30~50
    }

    @Test
    void insufficientDatesAreMarkedUnavailable() {
        ScoreContinuityAnalyzer.Analysis analysis = ScoreContinuityAnalyzer.analyze(
                List.of(), List.of(), 3, ScoreContinuityAnalyzer.band("0-30"), "latest");
        assertEquals(36, analysis.matrix().size());
        assertTrue(analysis.matrix().stream().noneMatch(ScoreContinuityVO.MatrixCell::available));
        assertTrue(analysis.items().isEmpty());
        assertEquals(0, analysis.universeSize());
    }

    @Test
    void selectedCombinationBuildsSeriesAndSorts() {
        List<ScoreDailyPoint> rows = new ArrayList<>();
        rows.addAll(allDays("000001", "0", "0", "60", "65", "70"));   // 最近 3 天上行
        rows.addAll(allDays("000002", "0", "0", "70", "65", "60"));   // 最近 3 天下行

        ScoreContinuityVO.Band band = ScoreContinuityAnalyzer.band("60-75");
        ScoreContinuityAnalyzer.Analysis byChange = ScoreContinuityAnalyzer.analyze(rows, DATES, 3, band, "change");
        ScoreContinuityAnalyzer.Analysis byLatest = ScoreContinuityAnalyzer.analyze(rows, DATES, 3, band, "latest");

        assertEquals(2, byChange.items().size());
        assertEquals("000001", byChange.items().get(0).code());
        assertEquals(new BigDecimal("-10"), byChange.items().get(1).scoreChange());
        assertEquals("000001", byLatest.items().get(0).code());
        assertTrue(byLatest.items().get(0).latestScore().compareTo(byLatest.items().get(1).latestScore()) > 0);

        ScoreContinuityItemVO first = byChange.items().get(0);
        assertEquals(3, first.series().size());
        assertEquals(new BigDecimal("60"), first.firstScore());
        assertEquals(new BigDecimal("70"), first.latestScore());
        assertEquals(new BigDecimal("10"), first.scoreChange());
        assertEquals(DATES.get(2), first.startDate());
        assertEquals(DATES.get(4), first.endDate());
        assertEquals(0L, count(byChange, 3, "0-30"));    // 只看最近 3 个交易日的窗口
    }
}
