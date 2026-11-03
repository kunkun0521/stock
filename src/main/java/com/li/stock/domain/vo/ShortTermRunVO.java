package com.li.stock.domain.vo;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 前端可选的历史预测批次。 */
public record ShortTermRunVO(
        Long id,
        LocalDate forecastDate,
        Integer adjustFlag,
        LocalDate sourceLatestDate,
        String modelVersion,
        Integer stockCount,
        LocalDateTime createdAt
) {}
