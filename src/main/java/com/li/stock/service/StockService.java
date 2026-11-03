package com.li.stock.service;

import com.li.stock.common.BizException;
import com.li.stock.common.PageResult;
import com.li.stock.common.QuerySupport;
import com.li.stock.domain.model.Bar;
import com.li.stock.domain.model.DataRangeStat;
import com.li.stock.domain.query.StockQuery;
import com.li.stock.domain.vo.DataRangeVO;
import com.li.stock.domain.vo.FilterOptionsVO;
import com.li.stock.domain.vo.KlinePointVO;
import com.li.stock.domain.vo.KlineSeriesVO;
import com.li.stock.domain.vo.QuoteVO;
import com.li.stock.domain.vo.RangeStatsVO;
import com.li.stock.domain.vo.StockBriefVO;
import com.li.stock.domain.vo.StockDailyVO;
import com.li.stock.domain.vo.StockInfoVO;
import com.li.stock.repository.StockDailyRepository;
import com.li.stock.repository.StockInfoRepository;
import com.li.stock.util.IndicatorCalculator;
import com.li.stock.util.KlineAggregator;
import com.li.stock.util.Numbers;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 单只股票维度的查询与统计。
 */
@Service
public class StockService {

    private static final int KLINE_DEFAULT_LIMIT = 250;
    private static final int KLINE_MAX_LIMIT = 5000;

    private final StockInfoRepository stockInfoRepository;
    private final StockDailyRepository stockDailyRepository;

    public StockService(StockInfoRepository stockInfoRepository, StockDailyRepository stockDailyRepository) {
        this.stockInfoRepository = stockInfoRepository;
        this.stockDailyRepository = stockDailyRepository;
    }

    /* ---------------- 基础信息 ---------------- */

    public PageResult<StockInfoVO> listStocks(StockQuery query, Integer page, Integer size) {
        int p = QuerySupport.page(page);
        int s = QuerySupport.size(size, 20, QuerySupport.MAX_PAGE_SIZE);
        long total = stockInfoRepository.count(query);
        if (total == 0) {
            return PageResult.empty(p, s);
        }
        List<StockInfoVO> items = stockInfoRepository.page(query, QuerySupport.offset(p, s), s);
        return PageResult.of(items, total, p, s);
    }

    public StockInfoVO getStock(String code) {
        return requireStock(code);
    }

    public List<StockBriefVO> suggest(String keyword, Integer limit) {
        if (!QuerySupport.hasText(keyword)) {
            throw BizException.badRequest("搜索关键字不能为空");
        }
        return stockInfoRepository.suggest(keyword, QuerySupport.limit(limit, 10, 50));
    }

    public FilterOptionsVO filters() {
        return new FilterOptionsVO(
                stockInfoRepository.distinctBoards(),
                stockInfoRepository.distinctIndustries(),
                stockInfoRepository.distinctExchanges(),
                List.of(new FilterOptionsVO.Option(1, "启用"), new FilterOptionsVO.Option(0, "停用")));
    }

    /* ---------------- 行情 ---------------- */

    /** 最新一条行情快照；无行情时价格字段为 null，前端可提示“暂无行情”。 */
    public QuoteVO quote(String code) {
        StockInfoVO info = requireStock(code);
        Optional<StockDailyVO> latest = stockDailyRepository.latest(code, resolveAdjustFlag(code, null));
        if (latest.isEmpty()) {
            return new QuoteVO(code, info.name(), info.board(), info.industry(), null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        }
        StockDailyVO d = latest.get();
        return new QuoteVO(d.code(), info.name(), info.board(), info.industry(), d.tradeDate(),
                d.open(), d.high(), d.low(), d.close(), d.preclose(), d.pctChg(), d.chgAmt(),
                d.volume(), d.amount(), d.turnover(), d.amplitude(), d.peTtm(), d.pbMrq(),
                d.isSt(), d.tradeStatus());
    }

    public PageResult<StockDailyVO> pageDaily(String code, LocalDate from, LocalDate to,
                                              Integer adjustFlag, Integer page, Integer size) {
        requireStock(code);
        Integer flag = resolveAdjustFlag(code, adjustFlag);
        int p = QuerySupport.page(page);
        int s = QuerySupport.size(size, 30, QuerySupport.MAX_PAGE_SIZE);
        long total = stockDailyRepository.count(code, from, to, flag);
        if (total == 0) {
            return PageResult.empty(p, s);
        }
        List<StockDailyVO> items = stockDailyRepository.page(code, from, to, flag,
                QuerySupport.offset(p, s), s);
        return PageResult.of(items, total, p, s);
    }

    /**
     * K 线数据（含 MA/MACD/RSI/BOLL），可直接喂给 ECharts candlestick。
     *
     * @param period         day / week / month
     * @param adjustFlag     复权标记：1=后复权 2=前复权 3=不复权，null 表示不过滤
     * @param withIndicators 是否附带技术指标，默认 true
     */
    public KlineSeriesVO kline(String code, String period, Integer adjustFlag,
                               LocalDate from, LocalDate to, Integer limit, Boolean withIndicators) {
        StockInfoVO info = requireStock(code);
        String normalized = normalizePeriod(period);
        int lim = QuerySupport.limit(limit, KLINE_DEFAULT_LIMIT, KLINE_MAX_LIMIT);
        Integer flag = resolveAdjustFlag(code, adjustFlag);

        List<StockDailyVO> rows = stockDailyRepository.recentAsc(code, from, to, flag, lim);
        List<Bar> bars = rows.stream().map(StockService::toBar).toList();
        bars = switch (normalized) {
            case "week" -> KlineAggregator.weekly(bars);
            case "month" -> KlineAggregator.monthly(bars);
            default -> bars;
        };

        boolean withMa = withIndicators == null || withIndicators;
        List<KlinePointVO> points = IndicatorCalculator.toPoints(bars, withMa);

        Integer actualAdjust = rows.isEmpty() ? flag : rows.get(rows.size() - 1).adjustFlag();
        LocalDate start = points.isEmpty() ? null : points.get(0).tradeDate();
        LocalDate end = points.isEmpty() ? null : points.get(points.size() - 1).tradeDate();
        return new KlineSeriesVO(code, info.name(), normalized, actualAdjust,
                start, end, points.size(), points);
    }

    /** 区间统计：涨跌幅、极值、波动率、成交情况。 */
    public RangeStatsVO rangeStats(String code, LocalDate from, LocalDate to, Integer adjustFlag) {
        StockInfoVO info = requireStock(code);
        List<StockDailyVO> rows = stockDailyRepository.listAsc(code, from, to, resolveAdjustFlag(code, adjustFlag));
        if (rows.isEmpty()) {
            throw BizException.notFound("区间内没有行情数据：" + code);
        }

        StockDailyVO first = rows.get(0);
        StockDailyVO last = rows.get(rows.size() - 1);

        BigDecimal highest = null;
        LocalDate highestDate = null;
        BigDecimal lowest = null;
        LocalDate lowestDate = null;
        BigDecimal maxPct = null;
        LocalDate maxPctDate = null;
        BigDecimal minPct = null;
        LocalDate minPctDate = null;
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal turnoverSum = BigDecimal.ZERO;
        int turnoverCount = 0;

        double pctSum = 0d;
        double pctSquareSum = 0d;
        int pctCount = 0;

        for (StockDailyVO row : rows) {
            BigDecimal close = row.close();
            if (close != null) {
                if (highest == null || close.compareTo(highest) > 0) {
                    highest = close;
                    highestDate = row.tradeDate();
                }
                if (lowest == null || close.compareTo(lowest) < 0) {
                    lowest = close;
                    lowestDate = row.tradeDate();
                }
            }
            BigDecimal pct = row.pctChg();
            if (pct != null) {
                if (maxPct == null || pct.compareTo(maxPct) > 0) {
                    maxPct = pct;
                    maxPctDate = row.tradeDate();
                }
                if (minPct == null || pct.compareTo(minPct) < 0) {
                    minPct = pct;
                    minPctDate = row.tradeDate();
                }
                double v = pct.doubleValue();
                pctSum += v;
                pctSquareSum += v * v;
                pctCount++;
            }
            if (row.amount() != null) {
                totalAmount = totalAmount.add(row.amount());
            }
            if (row.turnover() != null) {
                turnoverSum = turnoverSum.add(row.turnover());
                turnoverCount++;
            }
        }

        BigDecimal firstClose = first.close();
        BigDecimal lastClose = last.close();
        BigDecimal returnPct = null;
        if (firstClose != null && lastClose != null && firstClose.signum() != 0) {
            returnPct = lastClose.subtract(firstClose)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(firstClose, 6, java.math.RoundingMode.HALF_UP)
                    .setScale(2, java.math.RoundingMode.HALF_UP);
        }

        int days = rows.size();
        BigDecimal avgAmount = days == 0 ? null
                : totalAmount.divide(BigDecimal.valueOf(days), 2, java.math.RoundingMode.HALF_UP);
        BigDecimal avgTurnover = turnoverCount == 0 ? null
                : turnoverSum.divide(BigDecimal.valueOf(turnoverCount), 2, java.math.RoundingMode.HALF_UP);

        double volatility = 0d;
        if (pctCount > 1) {
            double mean = pctSum / pctCount;
            double variance = Math.max((pctSquareSum - pctCount * mean * mean) / (pctCount - 1), 0d);
            volatility = Math.sqrt(variance) * Math.sqrt(252);
        }

        return new RangeStatsVO(
                code, info.name(), first.tradeDate(), last.tradeDate(), days,
                firstClose, lastClose, returnPct,
                highest, highestDate, lowest, lowestDate,
                maxPct, maxPctDate, minPct, minPctDate,
                Numbers.round(totalAmount, 2), avgAmount, avgTurnover,
                Numbers.round(volatility, 2));
    }

    /** 数据覆盖范围，用于判断该股票历史数据是否齐全。 */
    public DataRangeVO dataRange(String code) {
        StockInfoVO info = requireStock(code);
        DataRangeStat stat = stockDailyRepository.dataRange(code);
        return new DataRangeVO(code, info.name(), stat.firstDate(), stat.lastDate(),
                stat.rowCount(), stat.tradingDays(), stat.adjustFlags(), stat.dataSources());
    }

    /* ---------------- 内部方法 ---------------- */

    /**
     * 解析复权口径：显式传入优先，否则取该股票记录最多的口径（一般为前复权 2）。
     * <p>表内同一交易日可能同时存在多个复权口径的记录，不锁定口径会导致 K 线出现重复日期。</p>
     */
    private Integer resolveAdjustFlag(String code, Integer requested) {
        if (requested != null) {
            return requested;
        }
        return stockDailyRepository.preferredAdjustFlag(code).orElse(null);
    }

    private StockInfoVO requireStock(String code) {        if (!QuerySupport.hasText(code)) {
            throw BizException.badRequest("股票代码不能为空");
        }
        String normalized = code.trim();
        return stockInfoRepository.findByCode(normalized)
                .orElseThrow(() -> BizException.notFound("股票不存在：" + normalized));
    }

    private static String normalizePeriod(String period) {
        String key = QuerySupport.trimToNull(period);
        if (key == null) {
            return "day";
        }
        return switch (key.toLowerCase()) {
            case "day", "d", "daily" -> "day";
            case "week", "w", "weekly" -> "week";
            case "month", "m", "monthly" -> "month";
            default -> throw BizException.badRequest("不支持的周期：" + period + "（可选 day/week/month）");
        };
    }

    private static Bar toBar(StockDailyVO vo) {
        return new Bar(vo.tradeDate(), vo.open(), vo.high(), vo.low(), vo.close(), vo.preclose(),
                vo.volume(), vo.amount(), vo.pctChg(), vo.chgAmt(), vo.turnover(), vo.amplitude(),
                vo.isSt(), vo.tradeStatus());
    }
}
