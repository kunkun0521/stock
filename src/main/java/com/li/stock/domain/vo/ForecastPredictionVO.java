package com.li.stock.domain.vo;

import java.math.BigDecimal;

/**
 * 预测明细分页行。
 */
public record ForecastPredictionVO(String code, String market, String name,
                                   BigDecimal probUp, BigDecimal predRet,
                                   BigDecimal score, Integer rankAll, Integer isSelected) {
}
