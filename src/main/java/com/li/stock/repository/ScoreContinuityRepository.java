package com.li.stock.repository;

import com.li.stock.domain.model.ScoreDailyPoint;
import com.li.stock.mapper.ScoreContinuityMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/** 连续评分区间筛选的数据读取，只查询 stock_score_daily，不写库。 */
@Repository
public class ScoreContinuityRepository {

    private final ScoreContinuityMapper mapper;

    public ScoreContinuityRepository(ScoreContinuityMapper mapper) {
        this.mapper = mapper;
    }

    public List<LocalDate> availableDates(int adjustFlag, LocalDate asOfDate, int limit) {
        return mapper.availableDates(adjustFlag, asOfDate, limit);
    }

    public List<ScoreDailyPoint> loadScores(int adjustFlag, LocalDate fromDate, LocalDate toDate, String keyword) {
        return mapper.loadScores(adjustFlag, fromDate, toDate, keyword);
    }
}
