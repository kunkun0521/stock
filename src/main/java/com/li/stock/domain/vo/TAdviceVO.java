package com.li.stock.domain.vo;

import java.math.BigDecimal;

/**
 * 下一步操作建议（条件单口径）：价格触及 triggerPrice 时才执行。
 *
 * @param distancePct        触发价相对最新收盘价的百分比距离
 * @param expectDilutedCost  按建议执行后预估的摊薄成本，无法预估时为 null
 * @param risk               该动作的风险提示
 */
public record TAdviceVO(
        String action,
        String actionLabel,
        int shares,
        BigDecimal triggerPrice,
        BigDecimal currentPrice,
        BigDecimal distancePct,
        String reason,
        int priority,
        BigDecimal expectDilutedCost,
        String risk) {
}
