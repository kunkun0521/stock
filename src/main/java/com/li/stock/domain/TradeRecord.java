package com.li.stock.domain;

import java.time.LocalDate;

/**
 * 规格第十节 t_sim_trade 的一行交易明细，同时也是接口 trades[] 的元素。
 *
 * @param realizedPnl 已实现盈亏，仅完整做T往返有值
 */
public record TradeRecord(
        LocalDate tradeDate,
        String action,
        double price,
        int qty,
        double amount,
        double fee,
        Double realizedPnl,
        String note) {
}
