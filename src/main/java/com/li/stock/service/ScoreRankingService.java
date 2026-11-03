package com.li.stock.service;

import com.li.stock.common.PageResult;
import com.li.stock.common.QuerySupport;
import com.li.stock.domain.vo.ScoreBuildVO;
import com.li.stock.domain.vo.ScoreRankingVO;
import com.li.stock.repository.ScoreRankingRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Service
public class ScoreRankingService {

    private final ScoreRankingRepository repository;
    private final ScoreCalculationService calculationService;

    public ScoreRankingService(ScoreRankingRepository repository,
                               ScoreCalculationService calculationService) {
        this.repository = repository;
        this.calculationService = calculationService;
    }

    public PageResult<ScoreRankingVO> page(LocalDate tradeDate, Integer adjustFlag, String keyword,
                                           BigDecimal minScore, Integer market, String industry,
                                           Integer page, Integer size) {
        int actualPage = QuerySupport.page(page);
        int actualSize = QuerySupport.size(size, 50, QuerySupport.MAX_PAGE_SIZE);
        int flag = adjustFlag == null ? 2 : adjustFlag;
        if (flag < 1 || flag > 3) {
            throw com.li.stock.common.BizException.badRequest("adjustFlag 必须是 1、2 或 3");
        }
        long total = repository.count(tradeDate, flag, keyword, minScore, market, industry);
        if (total == 0) {
            return PageResult.empty(actualPage, actualSize);
        }
        List<ScoreRankingVO> items = repository.page(tradeDate, flag, keyword, minScore,
                market, industry, QuerySupport.offset(actualPage, actualSize), actualSize);
        return PageResult.of(items, total, actualPage, actualSize);
    }

    public List<LocalDate> dates(Integer adjustFlag) {
        int flag = adjustFlag == null ? 2 : adjustFlag;
        if (flag < 1 || flag > 3) {
            throw com.li.stock.common.BizException.badRequest("adjustFlag 必须是 1、2 或 3");
        }
        return repository.availableDates(flag, 120);
    }

    public ScoreBuildVO rebuild(LocalDate tradeDate, Integer adjustFlag) {
        return calculationService.rebuild(tradeDate, adjustFlag);
    }
}
