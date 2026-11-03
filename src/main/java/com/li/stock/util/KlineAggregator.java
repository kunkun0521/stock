package com.li.stock.util;

import com.li.stock.domain.model.Bar;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 日线聚合为周线 / 月线。
 * <p>周线按 ISO 周（周一为起点）分组，月线按自然月分组；成交额、成交量、换手率累加，
 * 开高低收取区间首末与极值，涨跌幅按区间前收盘重算。</p>
 */
public final class KlineAggregator {

    private KlineAggregator() {
    }

    public static List<Bar> weekly(List<Bar> dailyBars) {
        Map<String, List<Bar>> groups = new LinkedHashMap<>();
        for (Bar bar : dailyBars) {
            LocalDate date = bar.tradeDate();
            int week = date.get(WeekFields.ISO.weekOfWeekBasedYear());
            int weekYear = date.get(WeekFields.ISO.weekBasedYear());
            groups.computeIfAbsent(weekYear + "-W" + week, key -> new ArrayList<>()).add(bar);
        }
        return mergeGroups(groups.values());
    }

    public static List<Bar> monthly(List<Bar> dailyBars) {
        Map<String, List<Bar>> groups = new LinkedHashMap<>();
        for (Bar bar : dailyBars) {
            groups.computeIfAbsent(YearMonth.from(bar.tradeDate()).toString(), key -> new ArrayList<>()).add(bar);
        }
        return mergeGroups(groups.values());
    }

    private static List<Bar> mergeGroups(Iterable<List<Bar>> groups) {
        List<Bar> merged = new ArrayList<>();
        for (List<Bar> group : groups) {
            merged.add(merge(group));
        }
        return merged;
    }

    private static Bar merge(List<Bar> group) {
        Bar first = group.get(0);
        Bar last = group.get(group.size() - 1);

        BigDecimal high = null;
        BigDecimal low = null;
        BigDecimal amount = BigDecimal.ZERO;
        BigDecimal turnover = BigDecimal.ZERO;
        long volume = 0L;
        boolean hasVolume = false;

        for (Bar bar : group) {
            high = high == null || (bar.high() != null && bar.high().compareTo(high) > 0) ? bar.high() : high;
            low = low == null || (bar.low() != null && bar.low().compareTo(low) < 0) ? bar.low() : low;
            if (bar.volume() != null) {
                volume += bar.volume();
                hasVolume = true;
            }
            if (bar.amount() != null) {
                amount = amount.add(bar.amount());
            }
            if (bar.turnover() != null) {
                turnover = turnover.add(bar.turnover());
            }
        }

        BigDecimal preclose = first.preclose() != null ? first.preclose() : first.open();
        BigDecimal close = last.close();
        BigDecimal chgAmt = null;
        BigDecimal pctChg = null;
        BigDecimal amplitude = null;

        if (preclose != null && preclose.signum() != 0 && close != null) {
            chgAmt = Numbers.round(close.subtract(preclose), 4);
            pctChg = close.subtract(preclose)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(preclose, 6, RoundingMode.HALF_UP)
                    .setScale(4, RoundingMode.HALF_UP);
            if (high != null && low != null) {
                amplitude = high.subtract(low)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(preclose, 6, RoundingMode.HALF_UP)
                        .setScale(4, RoundingMode.HALF_UP);
            }
        }

        return new Bar(
                last.tradeDate(),
                first.open(),
                high,
                low,
                close,
                preclose,
                hasVolume ? Long.valueOf(volume) : null,
                Numbers.round(amount, 4),
                pctChg,
                chgAmt,
                Numbers.round(turnover, 4),
                amplitude,
                last.isSt(),
                last.tradeStatus());
    }
}
