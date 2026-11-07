package com.li.stock.domain.query;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 做T模拟请求。
 *
 * @param code            股票代码或中文名，必填；中文名按 stock_info 联想解析，仅支持 00/30/60 分表前缀
 * @param market          交易所 SH/SZ，可空时按代码推断，仅用于展示
 * @param buyDate         初始买入日期，可空时取数据起始日
 * @param endDate         模拟结束日期，可空时取库内最新交易日
 * @param totalCapital    策略总资金，用于单票仓位上限
 * @param availableCash   初始可用现金，必填
 * @param initialShares   已有底仓股数，0 表示首日建仓
 * @param initialAvgCost  已有底仓成本价，initialShares > 0 时必填
 * @param adjustFlag      复权口径 1=后复权 2=前复权 3=不复权，默认 2
 * @param priceMode       成交模式 CLOSE（收盘价）/ INTRADAY（日内触发价），默认 CLOSE
 * @param strategy        策略参数，整体可空
 */
public record TTradingRequest(
        String code,
        String market,
        LocalDate buyDate,
        LocalDate endDate,
        BigDecimal totalCapital,
        BigDecimal availableCash,
        Integer initialShares,
        BigDecimal initialAvgCost,
        Integer adjustFlag,
        String priceMode,
        TStrategyParams strategy) {
}
