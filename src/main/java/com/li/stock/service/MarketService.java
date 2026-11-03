package com.li.stock.service;

import com.li.stock.common.BizException;
import com.li.stock.common.QuerySupport;
import com.li.stock.domain.vo.BreadthPointVO;
import com.li.stock.domain.vo.DistributionVO;
import com.li.stock.domain.vo.MarketOverviewVO;
import com.li.stock.domain.vo.QuoteVO;
import com.li.stock.domain.vo.SectorPerfVO;
import com.li.stock.repository.MarketRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 市场级统计：概览、榜单、涨跌分布、板块表现、市场宽度。
 */
@Service
public class MarketService {

    /**
     * 涨跌幅分布分桶边界（左闭右开），首尾为哨兵值用于兜住极端涨跌幅。
     */
    private static final double[] EDGES = {-1000d, -9d, -7d, -5d, -3d, -1d, 0d, 1d, 3d, 5d, 7d, 9d, 1000d};

    private final MarketRepository marketRepository;

    public MarketService(MarketRepository marketRepository) {
        this.marketRepository = marketRepository;
    }

    public MarketOverviewVO overview(LocalDate date) {
        return marketRepository.overview(resolveDate(date));
    }

    /** 最近交易日列表，最新在前。 */
    public List<LocalDate> calendar(Integer limit) {
        return marketRepository.recentTradeDates(QuerySupport.limit(limit, 30, 250));
    }

    public List<QuoteVO> ranking(String type, LocalDate date, Integer limit, String board, Boolean includeSt) {
        LocalDate tradeDate = resolveDate(date);
        int size = QuerySupport.limit(limit, 20, 200);
        return marketRepository.ranking(tradeDate, type, size, board, Boolean.TRUE.equals(includeSt));
    }

    public DistributionVO distribution(LocalDate date) {
        LocalDate tradeDate = resolveDate(date);
        List<MarketRepository.BucketCount> rows = marketRepository.distribution(tradeDate);

        int[] counts = new int[EDGES.length - 1];
        int total = 0;
        for (MarketRepository.BucketCount row : rows) {
            if (row.bucket() == null) {
                continue;
            }
            counts[bucketIndex(row.bucket())] += row.count();
            total += row.count();
        }

        List<DistributionVO.Bucket> buckets = new ArrayList<>(counts.length);
        for (int i = 0; i < counts.length; i++) {
            buckets.add(new DistributionVO.Bucket(label(i), edge(i), edge(i + 1), counts[i]));
        }
        return new DistributionVO(tradeDate, total, buckets);
    }

    /**
     * 板块 / 行业表现。
     *
     * @param dimension board 或 industry
     */
    public List<SectorPerfVO> sectors(String dimension, LocalDate date, Integer limit) {
        String dim = QuerySupport.trimToNull(dimension);
        if (dim == null) {
            dim = "board";
        }
        if (!"board".equalsIgnoreCase(dim) && !"industry".equalsIgnoreCase(dim)) {
            throw BizException.badRequest("不支持的维度：" + dimension + "（可选 board/industry）");
        }
        return marketRepository.sectorPerformance(resolveDate(date), dim,
                QuerySupport.limit(limit, 30, 200));
    }

    /**
     * 市场宽度时序：默认最近 60 个交易日。
     */
    public List<BreadthPointVO> breadth(LocalDate from, LocalDate to, Integer days) {
        int n = QuerySupport.limit(days, 60, 500);
        List<LocalDate> dates = marketRepository.recentTradeDates(n);
        if (dates.isEmpty()) {
            throw BizException.notFound("行情分表暂无数据，请先导入 stock_daily_00、stock_daily_30 或 stock_daily_60");
        }
        LocalDate start = from != null ? from : dates.get(dates.size() - 1);
        LocalDate end = to != null ? to : dates.get(0);
        if (start.isAfter(end)) {
            throw BizException.badRequest("开始日期不能晚于结束日期");
        }
        return marketRepository.breadth(start, end);
    }

    private LocalDate resolveDate(LocalDate date) {
        if (date != null) {
            return date;
        }
        return marketRepository.latestTradeDate()
                .orElseThrow(() -> BizException.notFound("行情分表暂无数据，请先导入 stock_daily_00、stock_daily_30 或 stock_daily_60"));
    }

    private int bucketIndex(int value) {
        for (int i = 0; i < EDGES.length - 1; i++) {
            if (value >= EDGES[i] && value < EDGES[i + 1]) {
                return i;
            }
        }
        return value < EDGES[0] ? 0 : EDGES.length - 2;
    }

    private BigDecimal edge(int index) {
        if (index == 0 || index == EDGES.length - 1) {
            return null;
        }
        return BigDecimal.valueOf(EDGES[index]);
    }

    private String label(int index) {
        if (index == 0) {
            return "≤ -9%";
        }
        if (index == EDGES.length - 2) {
            return "≥ 9%";
        }
        return format(EDGES[index]) + "% ~ " + format(EDGES[index + 1]) + "%";
    }

    private String format(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
