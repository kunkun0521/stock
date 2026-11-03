package com.li.stock.repository;

import com.li.stock.domain.model.ScoreBar;
import com.li.stock.service.ScoreCalculationService.ScoreResult;
import com.li.stock.mapper.ScoreCalculationMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/** 评分计算的数据读写。 */
@Repository
public class ScoreCalculationRepository {

    private final ScoreCalculationMapper mapper;

    public ScoreCalculationRepository(ScoreCalculationMapper mapper) {
        this.mapper = mapper;
    }

    public LocalDate latestDate(int adjustFlag) {
        return mapper.latestDate(adjustFlag);
    }

    public List<ScoreBar> loadBars(LocalDate asOfDate, int adjustFlag, int calendarDays) {
        return mapper.loadBars(asOfDate, adjustFlag, calendarDays);
    }

    public List<ScoreBar> loadBarsBetween(LocalDate fromDate, LocalDate toDate, int adjustFlag) {
        return mapper.loadBarsBetween(fromDate, toDate, adjustFlag);
    }

    public int save(List<ScoreResult> results) {
        return results.isEmpty() ? 0 : mapper.save(results);
    }
}
