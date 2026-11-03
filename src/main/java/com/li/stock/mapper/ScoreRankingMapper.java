package com.li.stock.mapper;

import com.li.stock.domain.vo.ScoreRankingVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Mapper
public interface ScoreRankingMapper {
    long count(@Param("tradeDate") LocalDate tradeDate, @Param("adjustFlag") Integer adjustFlag,
               @Param("keyword") String keyword, @Param("minScore") BigDecimal minScore,
               @Param("market") Integer market, @Param("industry") String industry);

    List<ScoreRankingVO> page(@Param("tradeDate") LocalDate tradeDate, @Param("adjustFlag") Integer adjustFlag,
                              @Param("keyword") String keyword, @Param("minScore") BigDecimal minScore,
                              @Param("market") Integer market, @Param("industry") String industry,
                              @Param("offset") int offset, @Param("size") int size);

    List<LocalDate> availableDates(@Param("adjustFlag") Integer adjustFlag, @Param("limit") int limit);
}
