package com.li.stock.controller;

import com.li.stock.common.ApiResponse;
import com.li.stock.common.PageResult;
import com.li.stock.domain.vo.ScoreRankingVO;
import com.li.stock.domain.vo.ScoreBuildVO;
import com.li.stock.service.ScoreRankingService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 量化评分排行接口。 */
@RestController
@RequestMapping("/api/rankings")
public class ScoreRankingController {

    private final ScoreRankingService service;

    public ScoreRankingController(ScoreRankingService service) {
        this.service = service;
    }

    @GetMapping("/scores")
    public ApiResponse<PageResult<ScoreRankingVO>> scores(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false, defaultValue = "2") Integer adjustFlag,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) BigDecimal minScore,
            @RequestParam(required = false) Integer market,
            @RequestParam(required = false) String industry,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(service.page(date, adjustFlag, keyword, minScore, market, industry, page, size));
    }

    @GetMapping("/scores/dates")
    public ApiResponse<List<LocalDate>> dates(
            @RequestParam(required = false, defaultValue = "2") Integer adjustFlag) {
        return ApiResponse.ok(service.dates(adjustFlag));
    }

    /** 使用最新已入库行情实时重算评分并写入 stock_score_daily。 */
    @PostMapping("/scores/rebuild")
    public ApiResponse<ScoreBuildVO> rebuild(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false, defaultValue = "2") Integer adjustFlag) {
        return ApiResponse.ok(service.rebuild(date, adjustFlag));
    }
}
