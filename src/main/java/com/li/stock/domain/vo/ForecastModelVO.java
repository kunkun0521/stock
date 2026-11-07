package com.li.stock.domain.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 模型注册信息 + 近 60 批滚动表现。
 *
 * @param icir60        近 60 批 RankIC 均值 / 标准差（融合权重依据）
 * @param avgTopnExcess 近 60 批 TopN 平均超额收益（%）
 */
public record ForecastModelVO(String modelId, String modelType, Integer horizon,
                              String labelDef, String featureVersion,
                              LocalDate trainStart, LocalDate trainEnd,
                              String status, String note, LocalDateTime createdAt,
                              BigDecimal avgRankIc60, BigDecimal icir60,
                              BigDecimal avgTopnExcess, Long evalCount) {
}
