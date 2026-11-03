package com.li.stock.domain.vo;

import java.time.LocalDateTime;

/** 数据抓取任务状态。 */
public record CrawlTaskVO(
        Long id,
        String taskName,
        Integer totalCount,
        Integer successCount,
        Integer failCount,
        LocalDateTime startTime,
        LocalDateTime endTime,
        String status,
        String remark
) {}
