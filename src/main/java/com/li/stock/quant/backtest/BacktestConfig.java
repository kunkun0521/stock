package com.li.stock.quant.backtest;

import com.li.stock.common.BizException;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 回测配置（规格第 6、7 章）。
 *
 * <p>所有字段都有默认值；来自前端的字段缺失或为空串时回落到默认值，
 * 超出范围的数值直接拒绝（字段级错误信息由全局异常处理器转成 400）。</p>
 */
public record BacktestConfig(
        LocalDate start,
        LocalDate end,
        double capital,
        int maxPositions,
        double maxWeightPct,
        double slippagePct,
        double commissionPct,
        double minCommission,
        double stampPct,
        double transferPct,
        String fillPrice,
        String limitUpAction,
        boolean forceCloseAtEnd,
        String marketFilter,
        boolean allowSt,
        Set<String> boards,
        boolean allowNewListing,
        double minAmount20Wan,
        int cooldownDays,
        double rfRate
) {

    public static final List<String> BOARD_OPTIONS = List.of("MAIN", "GEM", "STAR");

    public static BacktestConfig from(Map<String, Object> m, LocalDate defaultStart, LocalDate defaultEnd) {
        Map<String, Object> src = m == null ? Map.of() : m;
        LocalDate start = parseDate(str(src.get("startDate")), defaultStart);
        LocalDate end = parseDate(str(src.get("endDate")), defaultEnd);
        if (start.isAfter(end)) {
            throw BizException.badRequest("起始日期不能晚于结束日期");
        }
        double capital = num(src.get("capital"), 1_000_000);
        if (capital < 10_000) throw BizException.badRequest("初始资金不能少于 1 万元");
        if (capital > 1e10) throw BizException.badRequest("初始资金过大");

        int maxPositions = (int) num(src.get("maxPositions"), 10);
        if (maxPositions < 1 || maxPositions > 50) {
            throw BizException.badRequest("最大持仓数需在 1~50 之间");
        }
        double maxWeight = num(src.get("maxWeightPct"), 10);
        if (maxWeight <= 0 || maxWeight > 100) {
            throw BizException.badRequest("单票最大仓位需在 0~100% 之间");
        }
        double slippage = num(src.get("slippagePct"), 0.1);
        if (slippage < 0 || slippage > 5) throw BizException.badRequest("滑点需在 0~5% 之间");

        double commission = num(src.get("commissionPct"), 0.025);
        double minCommission = num(src.get("minCommission"), 5);
        double stamp = num(src.get("stampPct"), 0.05);
        double transfer = num(src.get("transferPct"), 0.001);

        String fill = str(src.get("fillPrice")).toUpperCase(Locale.ROOT);
        if (!fill.isEmpty() && !List.of("OPEN", "CLOSE", "VWAP").contains(fill)) {
            throw BizException.badRequest("成交价口径只能是 OPEN / CLOSE / VWAP");
        }
        String limitAction = str(src.get("limitUpAction")).toUpperCase(Locale.ROOT);
        if (!limitAction.isEmpty() && !List.of("CANCEL", "DELAY").contains(limitAction)) {
            throw BizException.badRequest("涨停处理只能是 CANCEL / DELAY");
        }
        String marketFilter = str(src.get("marketFilter")).toUpperCase(Locale.ROOT);
        if (!marketFilter.isEmpty() && !List.of("NONE", "BULL_ONLY", "BEAR_ONLY").contains(marketFilter)) {
            throw BizException.badRequest("大盘过滤只能是 NONE / BULL_ONLY / BEAR_ONLY");
        }

        Set<String> boards = new LinkedHashSet<>();
        Object rawBoards = src.get("boards");
        if (rawBoards instanceof List<?> list) {
            list.forEach(b -> boards.add(String.valueOf(b).toUpperCase(Locale.ROOT)));
        } else if (rawBoards instanceof String s && !s.isBlank()) {
            for (String part : s.split(",")) {
                if (!part.isBlank()) boards.add(part.trim().toUpperCase(Locale.ROOT));
            }
        }
        if (boards.isEmpty()) boards.addAll(BOARD_OPTIONS);
        for (String b : boards) {
            if (!BOARD_OPTIONS.contains(b)) throw BizException.badRequest("未知板块：" + b);
        }

        double minAmount = num(src.get("minAmount20Wan"), 0);
        if (minAmount < 0) throw BizException.badRequest("成交额下限不能为负");
        int cooldown = (int) num(src.get("cooldownDays"), 3);
        if (cooldown < 0 || cooldown > 60) throw BizException.badRequest("冷却期需在 0~60 个交易日");

        return new BacktestConfig(start, end, capital, maxPositions, maxWeight,
                slippage, commission, minCommission, stamp, transfer,
                fill.isEmpty() ? "OPEN" : fill,
                limitAction.isEmpty() ? "CANCEL" : limitAction,
                bool(src.get("forceCloseAtEnd"), false),
                marketFilter.isEmpty() ? "NONE" : marketFilter,
                bool(src.get("allowSt"), false),
                boards,
                bool(src.get("allowNewListing"), false),
                minAmount, cooldown,
                num(src.get("rfRate"), 0.02));
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("startDate", String.valueOf(start));
        m.put("endDate", String.valueOf(end));
        m.put("capital", capital);
        m.put("maxPositions", maxPositions);
        m.put("maxWeightPct", maxWeightPct);
        m.put("slippagePct", slippagePct);
        m.put("commissionPct", commissionPct);
        m.put("minCommission", minCommission);
        m.put("stampPct", stampPct);
        m.put("transferPct", transferPct);
        m.put("fillPrice", fillPrice);
        m.put("limitUpAction", limitUpAction);
        m.put("forceCloseAtEnd", forceCloseAtEnd);
        m.put("marketFilter", marketFilter);
        m.put("allowSt", allowSt);
        m.put("boards", boards);
        m.put("allowNewListing", allowNewListing);
        m.put("minAmount20Wan", minAmount20Wan);
        m.put("cooldownDays", cooldownDays);
        m.put("rfRate", rfRate);
        return m;
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static double num(Object v, double def) {
        if (v == null) return def;
        if (v instanceof Number n) return n.doubleValue();
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return def;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw BizException.badRequest("数值参数格式不正确：" + s);
        }
    }

    private static boolean bool(Object v, boolean def) {
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return def;
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    private static LocalDate parseDate(String s, LocalDate def) {
        if (s == null || s.isBlank()) return def;
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            throw BizException.badRequest("日期格式应为 yyyy-MM-dd：" + s);
        }
    }
}
