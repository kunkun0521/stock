package com.li.stock.domain.vo;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 模型评估历史（每个到期批次一行）。
 */
public record ForecastEvalVO(String modelId, LocalDate asofDate, Integer horizon,
                             Integer nSamples, BigDecimal ic, BigDecimal rankIc,
                             BigDecimal auc, BigDecimal brier,
                             BigDecimal topnHitRate, BigDecimal topnAvgRet,
                             BigDecimal topnAvgExcess, BigDecimal allAvgRet,
                             BigDecimal decileSpread) {
}
