package com.li.stock.service;

import com.li.stock.domain.model.ScoreDailyPoint;
import com.li.stock.domain.vo.ScoreContinuityItemVO;
import com.li.stock.domain.vo.ScoreContinuityVO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「连续 N 个评分交易日综合分均落在同一分数区间」的纯计算逻辑。
 *
 * <p>不依赖 Spring 与数据库，输入一批逐日评分即可同时算出全部「天数 × 区间」的命中数
 * 与当前选中组合的明细，避免为矩阵里的每个格子单独查库。</p>
 */
public final class ScoreContinuityAnalyzer {

    /** 最多回溯的评分交易日数量，等于最大可选连续天数。 */
    public static final int MAX_WINDOW = 20;

    public static final List<Integer> DAY_OPTIONS = List.of(3, 5, 7, 10, 15, 20);

    /** 六档分区，左闭右开；最后一档无上界。 */
    public static final List<ScoreContinuityVO.Band> BANDS = List.of(
            new ScoreContinuityVO.Band("0-30", "0~30 分", new BigDecimal("0"), new BigDecimal("30")),
            new ScoreContinuityVO.Band("30-50", "30~50 分", new BigDecimal("30"), new BigDecimal("50")),
            new ScoreContinuityVO.Band("50-60", "50~60 分", new BigDecimal("50"), new BigDecimal("60")),
            new ScoreContinuityVO.Band("60-75", "60~75 分", new BigDecimal("60"), new BigDecimal("75")),
            new ScoreContinuityVO.Band("75-90", "75~90 分", new BigDecimal("75"), new BigDecimal("90")),
            new ScoreContinuityVO.Band("90+", "90 分以上", new BigDecimal("90"), null));

    private ScoreContinuityAnalyzer() {
    }

    /** 按 key 查找区间；null 或空串回退到第一档。 */
    public static ScoreContinuityVO.Band band(String key) {
        if (key == null || key.isBlank()) {
            return BANDS.get(0);
        }
        return BANDS.stream().filter(item -> item.key().equals(key)).findFirst().orElse(null);
    }

    /** 单个分数是否落在区间内。 */
    public static boolean contains(ScoreContinuityVO.Band band, BigDecimal score) {
        if (score == null) {
            return false;
        }
        return score.compareTo(band.min()) >= 0
                && (band.max() == null || score.compareTo(band.max()) < 0);
    }

    /**
     * @param universeSize 参与计算的股票数（关键词过滤后）
     * @param matrix       36 个「天数 × 区间」命中数
     * @param items        当前选中组合的全部命中明细（未分页，已排序）
     */
    public record Analysis(int universeSize,
                           List<ScoreContinuityVO.MatrixCell> matrix,
                           List<ScoreContinuityItemVO> items) {
    }

    public static Analysis analyze(List<ScoreDailyPoint> rows,
                                   List<LocalDate> datesAsc,
                                   int selectedDays,
                                   ScoreContinuityVO.Band selectedBand,
                                   String sort) {
        Map<String, List<ScoreDailyPoint>> grouped = new LinkedHashMap<>();
        for (ScoreDailyPoint point : rows) {
            grouped.computeIfAbsent(point.code() + "|" + point.market(), key -> new ArrayList<>()).add(point);
        }

        int totalDates = datesAsc.size();
        Map<String, Long> counts = new HashMap<>();
        List<ScoreContinuityItemVO> items = new ArrayList<>();

        for (List<ScoreDailyPoint> points : grouped.values()) {
            Map<LocalDate, BigDecimal> scoreByDate = new HashMap<>();
            Map<LocalDate, BigDecimal> closeByDate = new HashMap<>();
            ScoreDailyPoint meta = null;
            for (ScoreDailyPoint point : points) {
                scoreByDate.put(point.tradeDate(), point.totalScore());
                closeByDate.put(point.tradeDate(), point.close());
                if (meta == null || point.tradeDate().compareTo(meta.tradeDate()) >= 0) {
                    meta = point;
                }
            }

            for (int days : DAY_OPTIONS) {
                if (days > totalDates) {
                    continue;
                }
                List<LocalDate> window = datesAsc.subList(totalDates - days, totalDates);
                List<ScoreContinuityItemVO.ScorePoint> series = new ArrayList<>(days);
                boolean complete = true;
                for (LocalDate date : window) {
                    BigDecimal score = scoreByDate.get(date);
                    if (score == null) {
                        // 窗口内缺任意一天即不算「连续」
                        complete = false;
                        break;
                    }
                    series.add(new ScoreContinuityItemVO.ScorePoint(date, score));
                }
                if (!complete) {
                    continue;
                }

                for (ScoreContinuityVO.Band band : BANDS) {
                    boolean hit = true;
                    for (ScoreContinuityItemVO.ScorePoint point : series) {
                        if (!contains(band, point.score())) {
                            hit = false;
                            break;
                        }
                    }
                    if (!hit) {
                        continue;
                    }
                    counts.merge(days + "|" + band.key(), 1L, Long::sum);
                    if (days == selectedDays && band.key().equals(selectedBand.key())) {
                        items.add(buildItem(meta, days, band, window, series, closeByDate));
                    }
                }
            }
        }

        List<ScoreContinuityVO.MatrixCell> matrix = new ArrayList<>(DAY_OPTIONS.size() * BANDS.size());
        for (int days : DAY_OPTIONS) {
            for (ScoreContinuityVO.Band band : BANDS) {
                matrix.add(new ScoreContinuityVO.MatrixCell(days, band.key(),
                        counts.getOrDefault(days + "|" + band.key(), 0L), days <= totalDates));
            }
        }
        items.sort(comparator(sort));
        return new Analysis(grouped.size(), matrix, items);
    }

    private static ScoreContinuityItemVO buildItem(ScoreDailyPoint meta,
                                                   int days,
                                                   ScoreContinuityVO.Band band,
                                                   List<LocalDate> window,
                                                   List<ScoreContinuityItemVO.ScorePoint> series,
                                                   Map<LocalDate, BigDecimal> closeByDate) {
        List<BigDecimal> values = series.stream().map(ScoreContinuityItemVO.ScorePoint::score).toList();
        BigDecimal first = values.get(0);
        BigDecimal latest = values.get(values.size() - 1);
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal avg = sum.divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP);
        BigDecimal min = values.stream().min(BigDecimal::compareTo).orElse(first);
        BigDecimal max = values.stream().max(BigDecimal::compareTo).orElse(latest);
        LocalDate endDate = window.get(window.size() - 1);

        return new ScoreContinuityItemVO(
                meta.code(), meta.name(), meta.exchange(), meta.board(), meta.industry(),
                days, band.key(), latest, first, latest.subtract(first), avg, min, max,
                closeByDate.get(endDate), window.get(0), endDate, series);
    }

    private static Comparator<ScoreContinuityItemVO> comparator(String sort) {
        Comparator<BigDecimal> desc = Comparator.nullsLast(Comparator.<BigDecimal>reverseOrder());
        Comparator<ScoreContinuityItemVO> primary;
        if ("change".equals(sort)) {
            primary = Comparator.comparing(ScoreContinuityItemVO::scoreChange, desc);
        } else if ("avg".equals(sort)) {
            primary = Comparator.comparing(ScoreContinuityItemVO::avgScore, desc);
        } else if ("code".equals(sort)) {
            primary = Comparator.comparing(ScoreContinuityItemVO::code);
        } else {
            primary = Comparator.comparing(ScoreContinuityItemVO::latestScore, desc);
        }
        return primary.thenComparing(ScoreContinuityItemVO::code);
    }
}
