package com.li.stock.repository;

import com.li.stock.domain.model.ForecastResult;
import com.li.stock.domain.vo.ShortTermForecastVO;
import com.li.stock.mapper.ShortTermForecastMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Repository
public class ShortTermForecastRepository {

    private final ShortTermForecastMapper mapper;

    public ShortTermForecastRepository(ShortTermForecastMapper mapper) {
        this.mapper = mapper;
    }

    public long count(LocalDate tradeDate, Integer adjustFlag, String keyword, BigDecimal minProbability) {
        return mapper.count(tradeDate, adjustFlag, keyword, minProbability);
    }

    public List<ShortTermForecastVO> page(LocalDate tradeDate, Integer adjustFlag, String keyword,
                                          BigDecimal minProbability, int offset, int size) {
        return mapper.page(tradeDate, adjustFlag, keyword, minProbability, offset, size);
    }

    public List<LocalDate> availableDates(Integer adjustFlag, int limit) {
        return mapper.availableDates(adjustFlag, limit);
    }

    public LocalDate latestDate(int adjustFlag) {
        return mapper.latestDate(adjustFlag);
    }

    public int save(List<ForecastResult> results) {
        return results.isEmpty() ? 0 : mapper.save(results);
    }
}
