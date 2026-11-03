package com.li.stock.mapper;

import com.li.stock.domain.model.ScoreBar;
import com.li.stock.service.ScoreCalculationService.ScoreResult;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface ScoreCalculationMapper {
    LocalDate latestDate(@Param("adjustFlag") int adjustFlag);

    List<ScoreBar> loadBars(@Param("asOfDate") LocalDate asOfDate,
                            @Param("adjustFlag") int adjustFlag,
                            @Param("calendarDays") int calendarDays);

    List<ScoreBar> loadBarsBetween(@Param("fromDate") LocalDate fromDate,
                                   @Param("toDate") LocalDate toDate,
                                   @Param("adjustFlag") int adjustFlag);

    int save(@Param("results") List<ScoreResult> results);
}
