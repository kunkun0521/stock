package com.li.stock.mapper;

import com.li.stock.domain.model.ForecastResult;
import com.li.stock.domain.vo.ShortTermForecastVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Mapper
public interface ShortTermForecastMapper {
    long count(@Param("tradeDate") LocalDate tradeDate, @Param("adjustFlag") Integer adjustFlag,
               @Param("keyword") String keyword, @Param("minProbability") BigDecimal minProbability);

    List<ShortTermForecastVO> page(@Param("tradeDate") LocalDate tradeDate,
                                   @Param("adjustFlag") Integer adjustFlag,
                                   @Param("keyword") String keyword,
                                   @Param("minProbability") BigDecimal minProbability,
                                   @Param("offset") int offset, @Param("size") int size);

    List<LocalDate> availableDates(@Param("adjustFlag") Integer adjustFlag, @Param("limit") int limit);

    LocalDate latestDate(@Param("adjustFlag") int adjustFlag);

    int save(@Param("results") List<ForecastResult> results);
}
