package com.li.stock.domain.vo;

import java.math.BigDecimal;

/**
 * /api/t/params/default 的元素：参数及说明，前端据此渲染高级设置。
 */
public record ParamItemVO(
        String key,
        String label,
        String group,
        BigDecimal value,
        String unit,
        String tip,
        BigDecimal min,
        BigDecimal max,
        String type) {
}
