package com.li.stock.domain;

import java.time.LocalDate;

/**
 * 规格第三节的一行日线。
 * <p>数据源为项目中的 {@code stock_daily_all}（即 stock_daily_00 / 30 / 60 三张分表之和），
 * 查询时按代码前缀路由到对应物理分表。价格、成交额统一用 double 参与引擎计算，
 * 对外输出与落库由 web / repo 层转成 BigDecimal 保留两位小数。</p>
 */
public record Bar(
        String code,
        String market,
        LocalDate date,
        double open,
        double high,
        double low,
        double close,
        double preclose,
        double volume,
        double amount,
        double amplitude,
        double pctChg,
        double turnover,
        int tradeStatus,
        int isSt) {

    /** 停牌：tradestatus=0，或 OHLC 任一缺失 / 非正。停牌日只做估值，不撮合。 */
    public boolean suspended() {
        return tradeStatus == 0 || open <= 0 || high <= 0 || low <= 0 || close <= 0;
    }

    /** 一字板：最高价等于最低价（含浮点误差），视为当日无法成交。 */
    public boolean oneWordBoard() {
        return high - low <= 1e-8;
    }

    /** 涨跌停比例：ST 为 5%，30 开头（创业板）20%，其余 10%。与规格第二节一致。 */
    public double limitRatio() {
        if (isSt == 1) return 0.05;
        return code != null && code.startsWith("30") ? 0.20 : 0.10;
    }
}
