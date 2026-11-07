package com.li.stock.domain.vo;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 做T模拟结果附带的日 K 线点（OHLC + 均线 + 成交量）。
 * <p>与模拟共用一次行情读取，复权口径、日期范围完全一致；
 * inReplay 标记该交易日是否属于本次实际回放区间，前端据此高亮。</p>
 */
public record TKlinePointVO(
        LocalDate tradeDate,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volume,
        BigDecimal ma5,
        BigDecimal ma10,
        BigDecimal ma20,
        boolean inReplay) {
}
