package com.li.stock.controller;

import com.li.stock.common.ApiResponse;
import com.li.stock.common.PageResult;
import com.li.stock.domain.vo.ShortTermDailyForecastVO;
import com.li.stock.domain.vo.ShortTermEvaluateVO;
import com.li.stock.domain.vo.ShortTermRunBuildVO;
import com.li.stock.domain.vo.ShortTermRunSummaryVO;
import com.li.stock.domain.vo.ShortTermRunVO;
import com.li.stock.service.ShortTermForecastHistoryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 按批次保存、按交易日展开的短期持有预测接口。 */
@RestController
@RequestMapping("/api/short-term/history")
public class ShortTermForecastHistoryController {

    private final ShortTermForecastHistoryService service;

    public ShortTermForecastHistoryController(ShortTermForecastHistoryService service) {
        this.service = service;
    }

    @GetMapping("/runs")
    public ApiResponse<List<ShortTermRunVO>> runs(
            @RequestParam(required = false, defaultValue = "2") Integer adjustFlag,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(service.runs(adjustFlag, limit));
    }

    @GetMapping("/source-dates")
    public ApiResponse<List<LocalDate>> sourceDates(
            @RequestParam(required = false, defaultValue = "2") Integer adjustFlag,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(service.sourceDates(adjustFlag, limit));
    }

    @PostMapping("/runs/rebuild")
    public ApiResponse<ShortTermRunBuildVO> rebuild(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false, defaultValue = "2") Integer adjustFlag) {
        return ApiResponse.ok(service.rebuild(date, adjustFlag));
    }

    @PostMapping("/runs/{runId}/evaluate")
    public ApiResponse<ShortTermEvaluateVO> evaluate(@PathVariable long runId) {
        return ApiResponse.ok(service.evaluate(runId));
    }

    @GetMapping("/runs/{runId}/summary")
    public ApiResponse<PageResult<ShortTermRunSummaryVO>> summary(
            @PathVariable long runId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) BigDecimal minProbability,
            @RequestParam(required = false, defaultValue = "score20") String sortBy,
            @RequestParam(required = false, defaultValue = "desc") String sortDirection,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(service.summary(runId, keyword, minProbability, sortBy, sortDirection, page, size));
    }

    @GetMapping("/runs/{runId}/stocks/{code}")
    public ApiResponse<List<ShortTermDailyForecastVO>> daily(
            @PathVariable long runId, @PathVariable String code) {
        return ApiResponse.ok(service.daily(runId, code));
    }
}
