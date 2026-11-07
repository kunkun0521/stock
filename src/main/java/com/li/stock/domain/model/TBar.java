package com.li.stock.domain.model;

import java.time.LocalDate;

/**
 * 做T回测用的单日行情 + 技术指标。
 * <p>策略内部统一用 double 运算（比例、均线、成本需要频繁乘除），
 * 对外输出的金额与价格由 {@code Numbers.round} 转回 BigDecimal，与项目其它模块口径一致。</p>
 *
 * @param support    近 20 个交易日最低价（含当日）
 * @param resistance 近 20 个交易日最高价（含当日）
 */
public record TBar(
        LocalDate tradeDate,
        double open,
        double high,
        double low,
        double close,
        double preclose,
        double ma5,
        double ma10,
        double ma20,
        double volMa5,
        double volume,
        double turnover,
        double amplitude,
        double pctChg,
        double support,
        double resistance,
        boolean tradable) {
}
