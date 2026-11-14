package com.li.stock.quant.backtest;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** 每日净值点。 */
public record EquityPoint(LocalDate date, double nav, double cash, double positionValue,
                          double benchmarkNav, double drawdown, int positionCount) {

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", String.valueOf(date));
        m.put("nav", round(nav, 6));
        m.put("cash", round(cash, 2));
        m.put("positionValue", round(positionValue, 2));
        m.put("benchmarkNav", round(benchmarkNav, 6));
        m.put("drawdown", round(drawdown, 6));
        m.put("positionCount", positionCount);
        return m;
    }

    private static double round(double v, int scale) {
        double f = Math.pow(10, scale);
        return Math.round(v * f) / f;
    }
}
