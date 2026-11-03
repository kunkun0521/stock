package com.li.stock.domain.query;

import java.time.LocalDate;

/**
 * 股票列表查询条件。
 *
 * @param keyword    代码 / 名称 / secid 模糊匹配
 * @param market     市场代码，1=上海 0=深圳
 * @param exchange   交易所 SH / SZ
 * @param board      板块，精确匹配
 * @param industry   行业，精确匹配
 * @param status     内部状态标记
 * @param listedOnly 仅未退市
 * @param ipoFrom    上市日期下限
 * @param ipoTo      上市日期上限
 * @param sort       排序字段，白名单内生效
 * @param order      asc / desc
 */
public record StockQuery(
        String keyword,
        Integer market,
        String exchange,
        String board,
        String industry,
        Integer status,
        Boolean listedOnly,
        LocalDate ipoFrom,
        LocalDate ipoTo,
        String sort,
        String order) {
}
