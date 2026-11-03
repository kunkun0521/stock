package com.li.stock.controller;

import com.li.stock.common.ApiResponse;
import com.li.stock.domain.vo.BreadthPointVO;
import com.li.stock.domain.vo.DistributionVO;
import com.li.stock.domain.vo.MarketOverviewVO;
import com.li.stock.domain.vo.QuoteVO;
import com.li.stock.domain.vo.SectorPerfVO;
import com.li.stock.service.MarketService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 市场维度接口：概览、交易日历、榜单、涨跌分布、板块表现、市场宽度。
 * <p>所有接口的 date 参数为空时自动落到最新交易日。</p>
 */
@RestController
@RequestMapping("/api/market")
public class MarketController {

    private final MarketService marketService;

    public MarketController(MarketService marketService) {
        this.marketService = marketService;
    }

    /** 市场概览：涨跌家数、涨跌停、成交额、平均涨跌幅。 */
    @GetMapping("/overview")
    public ApiResponse<MarketOverviewVO> overview(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(marketService.overview(date));
    }

    /** 最近交易日列表，用于日期选择器。 */
    @GetMapping("/calendar")
    public ApiResponse<List<LocalDate>> calendar(@RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(marketService.calendar(limit));
    }

    /**
     * 榜单。
     *
     * @param type gainers 涨幅榜 / losers 跌幅榜 / amount 成交额 / volume 成交量 /
     *             turnover 换手率 / amplitude 振幅 / pe 市盈率 / pb 市净率
     */
    @GetMapping("/ranking")
    public ApiResponse<List<QuoteVO>> ranking(
            @RequestParam(required = false, defaultValue = "gainers") String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String board,
            @RequestParam(required = false, defaultValue = "false") Boolean includeSt) {
        return ApiResponse.ok(marketService.ranking(type, date, limit, board, includeSt));
    }

    /** 涨跌幅分布（直方图数据）。 */
    @GetMapping("/distribution")
    public ApiResponse<DistributionVO> distribution(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(marketService.distribution(date));
    }

    /**
     * 板块 / 行业表现排名。
     *
     * @param dimension board（板块）或 industry（行业）
     */
    @GetMapping("/sectors")
    public ApiResponse<List<SectorPerfVO>> sectors(
            @RequestParam(required = false, defaultValue = "board") String dimension,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(marketService.sectors(dimension, date, limit));
    }

    /**
     * 市场宽度时序：近 N 个交易日的上涨家数、下跌家数与成交额。
     *
     * @param days 默认 60，最大 500；指定 from 时以 from 为准
     */
    @GetMapping("/breadth")
    public ApiResponse<List<BreadthPointVO>> breadth(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer days) {
        return ApiResponse.ok(marketService.breadth(from, to, days));
    }
}
