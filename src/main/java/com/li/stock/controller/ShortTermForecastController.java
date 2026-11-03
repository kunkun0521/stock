package com.li.stock.controller;

import com.li.stock.common.ApiResponse;
import com.li.stock.common.PageResult;
import com.li.stock.domain.vo.ShortTermBuildVO;
import com.li.stock.domain.vo.ShortTermForecastVO;
import com.li.stock.service.ShortTermForecastService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 短期持有期预测接口：5/10/15/20 个交易日，20 日约等于一个月。 */
@RestController
@RequestMapping("/api/short-term")
public class ShortTermForecastController {

    private final ShortTermForecastService service;

    public ShortTermForecastController(ShortTermForecastService service) {
        this.service = service;
    }

    @GetMapping("/forecasts")
    public ApiResponse<PageResult<ShortTermForecastVO>> forecasts(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false, defaultValue = "2") Integer adjustFlag,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) BigDecimal minProbability,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(service.page(date, adjustFlag, keyword, minProbability, page, size));
    }

    @GetMapping("/forecasts/dates")
    public ApiResponse<List<LocalDate>> dates(
            @RequestParam(required = false, defaultValue = "2") Integer adjustFlag) {
        return ApiResponse.ok(service.dates(adjustFlag));
    }

    /** 按最新已入库日线实时重算短期预测。 */
    @PostMapping("/forecasts/rebuild")
    public ApiResponse<ShortTermBuildVO> rebuild(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false, defaultValue = "2") Integer adjustFlag) {
        return ApiResponse.ok(service.rebuild(date, adjustFlag));
    }
}
