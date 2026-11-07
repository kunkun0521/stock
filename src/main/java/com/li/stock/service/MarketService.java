package com.li.stock.service;

import com.li.stock.common.BizException;
import com.li.stock.common.MarketCache;
import com.li.stock.common.QuerySupport;
import com.li.stock.domain.vo.BreadthPointVO;
import com.li.stock.domain.vo.DistributionVO;
import com.li.stock.domain.vo.MarketOverviewVO;
import com.li.stock.domain.vo.QuoteVO;
import com.li.stock.domain.vo.SectorPerfVO;
import com.li.stock.repository.MarketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 市场级统计：概览、榜单、涨跌分布、板块表现、市场宽度。
 */
@Service
public class MarketService {

    private static final Logger log = LoggerFactory.getLogger(MarketService.class);

    /**
     * 涨跌幅分布分桶边界（左闭右开），首尾为哨兵值用于兜住极端涨跌幅。
     */
    private static final double[] EDGES = {-1000d, -9d, -7d, -5d, -3d, -1d, 0d, 1d, 3d, 5d, 7d, 9d, 1000d};

    /** 预热/刷新缓存时加载的榜单类型，与看板前端的榜单 Tab 保持一致。 */
    private static final List<String> RANK_TYPES = List.of("gainers", "losers", "amount", "turnover", "amplitude");

    private final MarketRepository marketRepository;
    private final MarketCache marketCache;

    public MarketService(MarketRepository marketRepository, MarketCache marketCache) {
        this.marketRepository = marketRepository;
        this.marketCache = marketCache;
    }

    public MarketOverviewVO overview(LocalDate date) {
        LocalDate tradeDate = resolveDate(date);
        return marketCache.get("overview:" + tradeDate, () -> marketRepository.overview(tradeDate));
    }

    /** 最近交易日列表，最新在前。 */
    public List<LocalDate> calendar(Integer limit) {
        int size = QuerySupport.limit(limit, 30, 250);
        return marketCache.get("calendar:" + size, () -> marketRepository.recentTradeDates(size));
    }

    public List<QuoteVO> ranking(String type, LocalDate date, Integer limit, String board, Boolean includeSt) {
        LocalDate tradeDate = resolveDate(date);
        int size = QuerySupport.limit(limit, 20, 200);
        boolean withSt = Boolean.TRUE.equals(includeSt);
        String key = "ranking:" + tradeDate + ":" + type + ":" + size + ":" + board + ":" + withSt;
        return marketCache.get(key, () -> marketRepository.ranking(tradeDate, type, size, board, withSt));
    }

    public DistributionVO distribution(LocalDate date) {
        LocalDate tradeDate = resolveDate(date);
        return marketCache.get("distribution:" + tradeDate, () -> buildDistribution(tradeDate));
    }

    private DistributionVO buildDistribution(LocalDate tradeDate) {
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
        LocalDate tradeDate = resolveDate(date);
        int size = QuerySupport.limit(limit, 30, 200);
        String queryDim = dim;
        String key = "sectors:" + queryDim + ":" + tradeDate + ":" + size;
        return marketCache.get(key, () -> marketRepository.sectorPerformance(tradeDate, queryDim, size));
    }

    /**
     * 市场宽度时序：默认最近 60 个交易日。
     *
     * <p>自定义 from/to 时把跨度收敛到 days 对应的时间窗内（按 2 倍自然日兜底换算），
     * 避免外部传入超大区间绕过 days 上限、对全历史做两次分组聚合。</p>
     */
    public List<BreadthPointVO> breadth(LocalDate from, LocalDate to, Integer days) {
        int n = QuerySupport.limit(days, 60, 500);
        List<LocalDate> dates = marketCache.get("calendar:" + n, () -> marketRepository.recentTradeDates(n));
        if (dates.isEmpty()) {
            throw BizException.notFound("行情分表暂无数据，请先导入 stock_daily_00、stock_daily_30 或 stock_daily_60");
        }
        LocalDate start = from != null ? from : dates.get(dates.size() - 1);
        LocalDate end = to != null ? to : dates.get(0);
        if (start.isAfter(end)) {
            throw BizException.badRequest("开始日期不能晚于结束日期");
        }
        long maxCalendarDays = n * 2L;
        if (ChronoUnit.DAYS.between(start, end) > maxCalendarDays) {
            start = end.minusDays(maxCalendarDays);
        }
        LocalDate queryStart = start;
        LocalDate queryEnd = end;
        String key = "breadth:" + queryStart + ":" + queryEnd;
        return marketCache.get(key, () -> marketRepository.breadth(queryStart, queryEnd));
    }

    /**
     * 预热缓存：把看板默认参数组合（最新交易日的概览、分布、板块、各榜单、宽度、日历）
     * 全部加载进本地缓存。应用启动时和抓取任务写入新行情后调用，
     * 调用前清空旧缓存，保证新增数据立刻进入缓存。
     */
    public void warmUp() {
        marketCache.evictAll();
        try {
            overview(null);
            distribution(null);
            calendar(60);
            sectors("board", null, 15);
            sectors("industry", null, 15);
            for (String type : RANK_TYPES) {
                ranking(type, null, 30, null, false);
            }
            breadth(null, null, 60);
            log.info("市场看板缓存预热完成，{}", marketCache.stats());
        } catch (RuntimeException ex) {
            // 预热失败不影响服务：后续请求会按需加载并回填缓存
            log.warn("市场看板缓存预热失败：{}", ex.getMessage());
        }
    }

    private LocalDate resolveDate(LocalDate date) {
        if (date != null) {
            return date;
        }
        return marketCache.get("latestTradeDate",
                () -> marketRepository.latestTradeDate()
                        .orElseThrow(() -> BizException.notFound("行情分表暂无数据，请先导入 stock_daily_00、stock_daily_30 或 stock_daily_60")));
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
