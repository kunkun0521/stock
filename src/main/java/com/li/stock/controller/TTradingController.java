package com.li.stock.controller;

import com.li.stock.common.ApiResponse;
import com.li.stock.domain.query.TTradingRequest;
import com.li.stock.domain.vo.TStrategyConfigVO;
import com.li.stock.domain.vo.TTradingResultVO;
import com.li.stock.service.TTradingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 做T + 成本摊薄 + 回本盈利模拟接口。
 * <p>纯内存规则回放，只读 stock_daily 分表，不写库、不产生真实交易。
 * 所有输出均为历史行情下的模拟结果，不构成投资建议。</p>
 */
@RestController
@RequestMapping("/api/t-trading")
public class TTradingController {

    private final TTradingService service;

    public TTradingController(TTradingService service) {
        this.service = service;
    }

    /** 默认策略参数，供页面初始化高级参数表单。 */
    @GetMapping("/defaults")
    public ApiResponse<TStrategyConfigVO> defaults() {
        return ApiResponse.ok(service.defaults());
    }

    /** 运行一次完整模拟，返回成交日志、资产曲线与下一步操作建议。 */
    @PostMapping("/simulate")
    public ApiResponse<TTradingResultVO> simulate(@RequestBody TTradingRequest request) {
        return ApiResponse.ok(service.simulate(request));
    }
}
