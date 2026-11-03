package com.li.stock.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 数值处理工具：统一四舍五入与空值处理。
 */
public final class Numbers {

    private Numbers() {
    }

    public static BigDecimal round(BigDecimal value, int scale) {
        return value == null ? null : value.setScale(scale, RoundingMode.HALF_UP);
    }

    public static BigDecimal round(double value, int scale) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return null;
        }
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }

    public static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** 百分比 = 分子 / 分母 × 100，分母为 0 或 null 时返回 0。 */
    public static BigDecimal percent(Number numerator, Number denominator, int scale) {
        if (numerator == null || denominator == null) {
            return BigDecimal.ZERO.setScale(scale);
        }
        BigDecimal d = new BigDecimal(denominator.toString());
        if (d.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO.setScale(scale);
        }
        return new BigDecimal(numerator.toString())
                .multiply(BigDecimal.valueOf(100))
                .divide(d, scale, RoundingMode.HALF_UP);
    }
}
