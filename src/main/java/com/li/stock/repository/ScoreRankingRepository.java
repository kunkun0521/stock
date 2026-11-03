package com.li.stock.repository;

import com.li.stock.domain.vo.ScoreRankingVO;
import com.li.stock.mapper.ScoreRankingMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** stock_score_daily 查询。评分由量化评分任务生成，本仓库只负责可筛选地读取。 */
@Repository
public class ScoreRankingRepository {

    private final ScoreRankingMapper mapper;

    public ScoreRankingRepository(ScoreRankingMapper mapper) {
        this.mapper = mapper;
    }

    public long count(LocalDate tradeDate, Integer adjustFlag, String keyword,
                      BigDecimal minScore, Integer market, String industry) {
        return mapper.count(tradeDate, adjustFlag, keyword, minScore, market, industry);
    }

    public List<ScoreRankingVO> page(LocalDate tradeDate, Integer adjustFlag, String keyword,
                                     BigDecimal minScore, Integer market, String industry,
                                     int offset, int size) {
        return mapper.page(tradeDate, adjustFlag, keyword, minScore, market, industry, offset, size);
    }

    public List<LocalDate> availableDates(Integer adjustFlag, int limit) {
        return mapper.availableDates(adjustFlag, limit);
    }

}
