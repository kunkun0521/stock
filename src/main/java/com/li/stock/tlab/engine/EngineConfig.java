package com.li.stock.tlab.engine;

import java.time.LocalDate;

/**
 * planOrders 所需的静态上下文。全部来自 t-1 及以前，不含任何当日K线信息，
 * 保证「把 t 日及以后的K线全部篡改，planOrders 在 t 日的输出不变」（验收用例 7）。
 *
 * @param totalCapital   总资金
 * @param availableCash  可支配资金（补仓预算基数）
 * @param dayIndex       当前交易日索引，用于补仓间隔判断
 * @param asOfDate       计划针对的交易日（t 日）
 * @param prev2Rsi       t-2 日 RSI14，仅 STOP_FALL 企稳确认用
 */
public record EngineConfig(
        double totalCapital,
        double availableCash,
        int dayIndex,
        LocalDate asOfDate,
        double prev2Rsi) {
}
