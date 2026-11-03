package com.li.stock.domain.vo;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 市场概览（单日）。
 *
 * @param totalStocks  当日有行情记录的股票数
 * @param upCount      上涨家数
 * @param downCount    下跌家数
 * @param flatCount    平盘家数（涨跌幅为 0）
 * @param limitUpCount 涨停家数（按 +9.8% 及以上近似，不同板块涨跌幅限制不同，仅作情绪参考）
 * @param limitDownCount 跌停家数（按 -9.8% 及以下近似）
 * @param suspendedCount 停牌家数（tradestatus = 0）
 * @param upRatio      上涨占比（%）
 */
public record MarketOverviewVO(
        LocalDate tradeDate,
        int totalStocks,
        int upCount,
        int downCount,
        int flatCount,
        int limitUpCount,
        int limitDownCount,
        int suspendedCount,
        int stCount,
        BigDecimal upRatio,
        Long totalVolume,
        BigDecimal totalAmount,
        BigDecimal avgPctChg,
        BigDecimal avgTurnover) {
}
