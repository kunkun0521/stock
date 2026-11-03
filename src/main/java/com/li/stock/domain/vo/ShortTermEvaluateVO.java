package com.li.stock.domain.vo;

import java.time.LocalDate;

/** 批次实际收益回填结果。 */
public record ShortTermEvaluateVO(
        Long runId,
        int updatedRows,
        int completeRows,
        int pendingRows,
        LocalDate latestDataDate
) {}
