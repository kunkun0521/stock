package com.li.stock.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 融合权重（最近一次）。
 */
public record ForecastWeightVO(LocalDate asofDate, Integer horizon, String modelId,
                               BigDecimal weight, BigDecimal icir60) {
}
