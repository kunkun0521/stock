package com.li.stock.mapper;

import com.li.stock.domain.vo.BreadthPointVO;
import com.li.stock.domain.vo.MarketOverviewVO;
import com.li.stock.domain.vo.QuoteVO;
import com.li.stock.domain.vo.SectorPerfVO;
import com.li.stock.repository.MarketRepository;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface MarketMapper {
    LocalDate latestTradeDate();

    List<LocalDate> recentTradeDates(@Param("limit") int limit);

    MarketOverviewVO overview(@Param("tradeDate") LocalDate tradeDate);

    List<MarketRepository.BucketCount> distribution(@Param("tradeDate") LocalDate tradeDate);

    List<QuoteVO> ranking(@Param("tradeDate") LocalDate tradeDate, @Param("type") String type,
                          @Param("limit") int limit, @Param("board") String board,
                          @Param("includeSt") boolean includeSt);

    List<SectorPerfVO> sectorPerformance(@Param("tradeDate") LocalDate tradeDate,
                                         @Param("dimension") String dimension,
                                         @Param("limit") int limit);

    List<BreadthPointVO> breadth(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
