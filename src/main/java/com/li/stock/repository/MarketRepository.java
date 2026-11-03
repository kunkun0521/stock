package com.li.stock.repository;

import com.li.stock.domain.vo.BreadthPointVO;
import com.li.stock.domain.vo.MarketOverviewVO;
import com.li.stock.domain.vo.QuoteVO;
import com.li.stock.domain.vo.SectorPerfVO;
import com.li.stock.mapper.MarketMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 市场级（跨股票）聚合查询。
 * <p><b>去重说明：</b>表里同一 (code, trade_date) 可能存在多个 adjustflag 的记录，
 * 而成交量、成交额、涨跌幅等字段与复权口径无关。因此所有横截面统计都先按 code 去重再聚合，
 * 避免同一只股票被重复计数。</p>
 */
@Repository
public class MarketRepository {

    private final MarketMapper mapper;

    public MarketRepository(MarketMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<LocalDate> latestTradeDate() {
        return Optional.ofNullable(mapper.latestTradeDate());
    }

    /** 最近 N 个交易日，最新在前。 */
    public List<LocalDate> recentTradeDates(int limit) {
        return mapper.recentTradeDates(limit);
    }

    public MarketOverviewVO overview(LocalDate tradeDate) {
        return mapper.overview(tradeDate);
    }

    /**
     * 涨跌幅按整数百分位分组，服务层再做区间归并，避免写死固定分桶。
     */
    public List<BucketCount> distribution(LocalDate tradeDate) {
        return mapper.distribution(tradeDate);
    }

    public List<QuoteVO> ranking(LocalDate tradeDate, String type, int limit, String board, boolean includeSt) {
        return mapper.ranking(tradeDate, type, limit, board, includeSt);
    }

    public List<SectorPerfVO> sectorPerformance(LocalDate tradeDate, String dimension, int limit) {
        return mapper.sectorPerformance(tradeDate, dimension, limit);
    }

    public List<BreadthPointVO> breadth(LocalDate from, LocalDate to) {
        return mapper.breadth(from, to);
    }

    /** 分布分组的原始计数。 */
    public record BucketCount(Integer bucket, int count) {
    }
}
