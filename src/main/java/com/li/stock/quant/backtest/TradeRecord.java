package com.li.stock.quant.backtest;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一笔已平仓交易。
 *
 * @param entryAmount 买入金额（不含费用），用于换手率统计
 */
public record TradeRecord(int seq, String code, String market, LocalDate entryDate, double entryPrice,
                          LocalDate exitDate, double exitPrice, double shares, double pnl, double pnlPct,
                          int holdDays, String exitReason, double entryScore, double mae, double mfe,
                          double entryAmount) {

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", seq);
        m.put("code", code);
        m.put("market", market);
        m.put("entryDate", String.valueOf(entryDate));
        m.put("entryPrice", round(entryPrice, 4));
        m.put("exitDate", exitDate == null ? null : String.valueOf(exitDate));
        m.put("exitPrice", round(exitPrice, 4));
        m.put("shares", round(shares, 2));
        m.put("pnl", round(pnl, 2));
        m.put("pnlPct", round(pnlPct, 6));
        m.put("holdDays", holdDays);
        m.put("exitReason", exitReason);
        m.put("entryScore", round(entryScore, 2));
        m.put("mae", round(mae, 6));
        m.put("mfe", round(mfe, 6));
        return m;
    }

    private static double round(double v, int scale) {
        double f = Math.pow(10, scale);
        return Math.round(v * f) / f;
    }
}
