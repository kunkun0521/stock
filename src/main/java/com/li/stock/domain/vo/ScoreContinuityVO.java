package com.li.stock.domain.vo;

import com.li.stock.common.PageResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 连续评分区间筛选结果。
 *
 * <p>口径：取评分表中最近若干个有记录的交易日，要求某只股票在这 N 个交易日里
 * 每天都有评分记录，且每一天的 total_score 都落在同一个分数区间内（左闭右开，
 * 最后一档无上界）。一次返回全部「天数 × 区间」组合的命中数量矩阵，
 * 以及当前选中组合的分页明细。</p>
 *
 * @param asOfDate          实际使用的截止评分日
 * @param adjustFlag        复权口径
 * @param tradeDates        参与计算的评分交易日（升序，最多 20 个）
 * @param availableDays     可用评分交易日数量
 * @param dayOptions        可选连续天数
 * @param bands             可选分数区间
 * @param matrix            命中数量矩阵（天数 × 区间）
 * @param selectedDays      当前选中的连续天数
 * @param selectedBand      当前选中的区间 key
 * @param selectedBandLabel 当前选中区间的中文名
 * @param minScore          当前区间下界（含）
 * @param maxScore          当前区间上界（不含），null 表示无上界
 * @param universeSize      参与计算的股票总数（关键词过滤后）
 * @param result            当前选中组合的分页明细
 * @param warnings          数据完整性提示
 */
public record ScoreContinuityVO(
        LocalDate asOfDate,
        Integer adjustFlag,
        List<LocalDate> tradeDates,
        int availableDays,
        List<Integer> dayOptions,
        List<Band> bands,
        List<MatrixCell> matrix,
        int selectedDays,
        String selectedBand,
        String selectedBandLabel,
        BigDecimal minScore,
        BigDecimal maxScore,
        int universeSize,
        PageResult<ScoreContinuityItemVO> result,
        List<String> warnings
) {

    /** 分数区间：左闭右开，max 为 null 表示无上界。 */
    public record Band(String key, String label, BigDecimal min, BigDecimal max) {
    }

    /** 矩阵单元格：available 为 false 表示评分交易日不足以判断该连续天数。 */
    public record MatrixCell(int days, String band, long count, boolean available) {
    }
}
