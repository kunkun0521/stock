package com.li.stock.domain.model;

/**
 * 策略引擎产生的单条操作信号。
 *
 * @param price     触发/成交价：CLOSE 模式为当日收盘价，INTRADAY 模式为目标触发价
 * @param shares    建议股数，买入为正、卖出为负；最终成交数量由模拟器按资金与整手规则复核
 */
public record TSignal(TTradeAction action, int shares, double price,
                      String reason, int priority) {
}
