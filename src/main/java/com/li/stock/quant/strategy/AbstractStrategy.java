package com.li.stock.quant.strategy;

import com.li.stock.quant.data.MarketPanel;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 策略实现基类：参数读取、涨跌停判定、格式化等公共工具。
 */
public abstract class AbstractStrategy implements Strategy {

    protected static boolean ok(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    protected static int i(Map<String, Object> p, String key, int def) {
        Object v = p.get(key);
        return v instanceof Number n ? n.intValue() : def;
    }

    protected static double d(Map<String, Object> p, String key, double def) {
        Object v = p.get(key);
        return v instanceof Number n ? n.doubleValue() : def;
    }

    protected static boolean b(Map<String, Object> p, String key, boolean def) {
        Object v = p.get(key);
        if (v instanceof Boolean bool) return bool;
        return def;
    }

    /** 当日是否跌停（留 0.1 的四舍五入容差）。 */
    protected static boolean atLimitDown(MarketPanel.Stock s, int row) {
        return s.pct[row] <= -s.limitPct(row) + 0.1;
    }

    /** 最近 days 根（含当日）是否有跌停。 */
    protected static boolean hasLimitDown(MarketPanel.Stock s, int row, int days) {
        for (int j = Math.max(0, row - days + 1); j <= row; j++) {
            if (atLimitDown(s, j)) return true;
        }
        return false;
    }

    /** 连续 n 日收盘低于某均线。 */
    protected static boolean belowForDays(float[] close, double[] ma, int row, int days) {
        if (row - days + 1 < 0) return false;
        for (int j = row - days + 1; j <= row; j++) {
            if (!ok(ma[j]) || close[j] >= ma[j]) return false;
        }
        return true;
    }

    protected static String f(double v, int digits) {
        if (!ok(v)) return "-";
        return String.format("%." + digits + "f", v);
    }

    /** 构造参数默认值 Map（键值成对传入）。 */
    protected static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
