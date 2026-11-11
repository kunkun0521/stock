package com.li.stock.domain.vo;

import java.util.List;

/** 一次评分自动计算批次的执行摘要（写入日志，也可供后续扩展查询）。 */
public record ScoreScheduleRunVO(
        String trigger,
        int adjustFlag,
        int plannedDays,
        int successDays,
        int failedDays,
        int savedRows,
        long elapsedMs,
        boolean skipped,
        List<String> details
) {
    public static ScoreScheduleRunVO skipped(String trigger, int adjustFlag) {
        return new ScoreScheduleRunVO(trigger, adjustFlag, 0, 0, 0, 0, 0, true,
                List.of("上一批评分重算尚未结束，本次跳过"));
    }
}
