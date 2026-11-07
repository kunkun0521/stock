package com.li.stock.controller;

import com.li.stock.common.ApiResponse;
import com.li.stock.common.PageResult;
import com.li.stock.domain.vo.*;
import com.li.stock.service.ForecastService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 多模型预测体系（stock-predict）的只读查询接口。
 * <p>数据由独立的 Python 预测引擎写入，本包只负责查询展示，不写库、
 * 不依赖原有代码；预测结果不构成投资建议。</p>
 */
@RestController
@RequestMapping("/api/forecast")
public class ForecastController {

    private final ForecastService forecastService;

    public ForecastController(ForecastService forecastService) {
        this.forecastService = forecastService;
    }

    /** 看板总览：最新信号日、模型数量、当日入选数、近期 RankIC 均值。 */
    @GetMapping("/overview")
    public ApiResponse<ForecastOverviewVO> overview(
            @RequestParam(required = false, defaultValue = "10") Integer horizon) {
        return ApiResponse.ok(forecastService.overview(horizon));
    }

    /** 模型注册表 + 近 60 批滚动表现（RankIC 均值、ICIR、TopN 超额）。 */
    @GetMapping("/models")
    public ApiResponse<List<ForecastModelVO>> models(
            @RequestParam(required = false, defaultValue = "10") Integer horizon) {
        return ApiResponse.ok(forecastService.models(horizon));
    }

    /** Top N 选股（默认融合模型），到期后联表展示真实收益。 */
    @GetMapping("/picks")
    public ApiResponse<List<ForecastPickVO>> picks(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false, defaultValue = "10") Integer horizon,
            @RequestParam(required = false) String modelId,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(forecastService.picks(date, horizon, modelId, limit));
    }

    /** 全部预测明细（分页），按 rank_all 升序。 */
    @GetMapping("/predictions")
    public ApiResponse<PageResult<ForecastPredictionVO>> predictions(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false, defaultValue = "10") Integer horizon,
            @RequestParam(required = false) String modelId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(forecastService.predictions(date, horizon, modelId, page, size));
    }

    /** 模型评估历史（IC/RankIC/AUC/TopN 等，按日期倒序）。 */
    @GetMapping("/eval")
    public ApiResponse<List<ForecastEvalVO>> eval(
            @RequestParam(required = false) String modelId,
            @RequestParam(required = false, defaultValue = "10") Integer horizon,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(forecastService.eval(modelId, horizon, limit));
    }

    /** 最近一次融合权重。 */
    @GetMapping("/weights")
    public ApiResponse<List<ForecastWeightVO>> weights(
            @RequestParam(required = false, defaultValue = "10") Integer horizon) {
        return ApiResponse.ok(forecastService.weights(horizon));
    }

    /** 有预测数据的信号日列表，用于日期选择器。 */
    @GetMapping("/dates")
    public ApiResponse<List<LocalDate>> dates(@RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(forecastService.dates(limit));
    }
}
