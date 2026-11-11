package com.li.stock.mapper;

import com.li.stock.domain.model.ScoreDailyPoint;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/** 连续评分区间筛选的只读查询。 */
@Mapper
public interface ScoreContinuityMapper {

    /** 截止日（含）之前最近 limit 个有评分记录的交易日，降序。 */
    List<LocalDate> availableDates(@Param("adjustFlag") int adjustFlag,
                                   @Param("asOfDate") LocalDate asOfDate,
                                   @Param("limit") int limit);

    /** 指定日期区间内的逐日评分，附带股票基本信息。 */
    List<ScoreDailyPoint> loadScores(@Param("adjustFlag") int adjustFlag,
                                     @Param("fromDate") LocalDate fromDate,
                                     @Param("toDate") LocalDate toDate,
                                     @Param("keyword") String keyword);
}
