package com.li.stock.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/** 评分自动计算任务的交易日查询，与页面评分排行接口互不干扰。 */
@Mapper
public interface ScoreScheduleMapper {

    /** 取行情库中最近 limit 个实际有日线记录的交易日，按日期倒序。 */
    List<LocalDate> recentTradeDates(@Param("adjustFlag") int adjustFlag, @Param("limit") int limit);
}
