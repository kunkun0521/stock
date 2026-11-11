package com.li.stock.repository;

import com.li.stock.mapper.ScoreScheduleMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/** 评分自动计算任务读取交易日。 */
@Repository
public class ScoreScheduleRepository {

    private final ScoreScheduleMapper mapper;

    public ScoreScheduleRepository(ScoreScheduleMapper mapper) {
        this.mapper = mapper;
    }

    public List<LocalDate> recentTradeDates(int adjustFlag, int limit) {
        return mapper.recentTradeDates(adjustFlag, limit);
    }
}
