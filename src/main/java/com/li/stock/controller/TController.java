package com.li.stock.controller;

import com.li.stock.common.ApiResponse;
import com.li.stock.domain.vo.ParamItemVO;
import com.li.stock.domain.vo.PlanRequest;
import com.li.stock.domain.vo.PlanResultVO;
import com.li.stock.domain.vo.ScanRequest;
import com.li.stock.domain.vo.ScanResultVO;
import com.li.stock.domain.vo.SimulateRequest;
import com.li.stock.domain.vo.SimulateResultVO;
import com.li.stock.service.TLabService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 规格十一节五个接口，路径前缀 /api/t，与现有 /api/t-trading 完全隔离。
 * <p>纯历史日线的规则回测与计划工具，不产生真实交易，不构成投资建议。</p>
 */
@RestController
@RequestMapping("/api/t")
public class TController {

    private final TLabService service;

    public TController(TLabService service) {
        this.service = service;
    }

    /** 持仓回本模拟：taskId、summary、daily[]、trades[]、benchmarks、warnings[]。 */
    @PostMapping("/simulate")
    public ApiResponse<SimulateResultVO> simulate(@RequestBody SimulateRequest request) {
        return ApiResponse.ok(service.simulate(request));
    }

    /** 买入日扫描：rows[]、featureBuckets[]、recommendations[]、currentSignal、stability。 */
    @PostMapping("/scan-entry")
    public ApiResponse<ScanResultVO> scanEntry(@RequestBody ScanRequest request) {
        return ApiResponse.ok(service.scanEntry(request));
    }

    /** 次日操作计划：orders[]、explanations[]、indicators。 */
    @PostMapping("/next-plan")
    public ApiResponse<PlanResultVO> nextPlan(@RequestBody PlanRequest request) {
        return ApiResponse.ok(service.nextPlan(request));
    }

    /** 默认参数及说明，前端据此渲染高级设置。 */
    @GetMapping("/params/default")
    public ApiResponse<List<ParamItemVO>> defaultParams() {
        return ApiResponse.ok(service.defaultParams());
    }

    /** 回看历史任务。 */
    @GetMapping("/tasks/{id}")
    public ApiResponse<Map<String, Object>> task(@PathVariable long id) {
        return ApiResponse.ok(service.loadTask(id));
    }
}
