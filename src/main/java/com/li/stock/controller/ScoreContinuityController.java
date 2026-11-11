package com.li.stock.controller;

import com.li.stock.common.ApiResponse;
import com.li.stock.domain.vo.ScoreContinuityVO;
import com.li.stock.service.ScoreContinuityService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 量化评分页的「连续评分区间」筛选接口。
 *
 * <p>独立路径 /api/rankings/scores/continuous，与原有评分排行接口互不影响。</p>
 */
@RestController
@RequestMapping("/api/rankings/scores/continuous")
public class ScoreContinuityController {

    private final ScoreContinuityService service;

    public ScoreContinuityController(ScoreContinuityService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<ScoreContinuityVO> query(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false, defaultValue = "2") Integer adjustFlag,
            @RequestParam(required = false, defaultValue = "3") Integer days,
            @RequestParam(name = "band", required = false) String band,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(service.query(date, adjustFlag, days, band, keyword, sort, page, size));
    }
}
