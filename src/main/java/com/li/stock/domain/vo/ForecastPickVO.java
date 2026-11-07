package com.li.stock.domain.vo;

import java.math.BigDecimal;

/**
 * Top N 选股明细；到期批次联表带出真实结果。
 *
 * @param outcomeFilled 该批次真实结果是否已回填
 * @param realRet       H 日真实收益率%（未到期为 null）
 * @param excessRet     相对全池均值的超额收益%（未到期为 null）
 */
public record ForecastPickVO(String code, String market, String name, String industry,
                             BigDecimal probUp, BigDecimal predRet,
                             BigDecimal predQ10, BigDecimal predQ90,
                             BigDecimal score, Integer rankAll,
                             Boolean outcomeFilled, Integer entryTradable,
                             BigDecimal realRet, BigDecimal excessRet) {
}
