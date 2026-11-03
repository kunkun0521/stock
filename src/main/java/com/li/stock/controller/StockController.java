package com.li.stock.controller;

import com.li.stock.common.ApiResponse;
import com.li.stock.common.PageResult;
import com.li.stock.domain.query.StockQuery;
import com.li.stock.domain.vo.DataRangeVO;
import com.li.stock.domain.vo.FilterOptionsVO;
import com.li.stock.domain.vo.KlineSeriesVO;
import com.li.stock.domain.vo.QuoteVO;
import com.li.stock.domain.vo.RangeStatsVO;
import com.li.stock.domain.vo.StockBriefVO;
import com.li.stock.domain.vo.StockDailyVO;
import com.li.stock.domain.vo.StockInfoVO;
import com.li.stock.service.StockService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 股票维度接口：列表、详情、快照、日线、K 线、区间统计。
 */
@RestController
@RequestMapping("/api/stocks")
public class StockController {

    private final StockService stockService;

    public StockController(StockService stockService) {
        this.stockService = stockService;
    }

    /**
     * 股票列表：关键字搜索 + 多条件筛选 + 分页 + 排序。
     */
    @GetMapping
    public ApiResponse<PageResult<StockInfoVO>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer market,
            @RequestParam(required = false) String exchange,
            @RequestParam(required = false) String board,
            @RequestParam(required = false) String industry,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Boolean listedOnly,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ipoFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ipoTo,
            @RequestParam(required = false, defaultValue = "code") String sort,
            @RequestParam(required = false, defaultValue = "asc") String order,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        StockQuery query = new StockQuery(keyword, market, exchange, board, industry, status,
                listedOnly, ipoFrom, ipoTo, sort, order);
        return ApiResponse.ok(stockService.listStocks(query, page, size));
    }

    /** 代码/名称联想，输入框自动补全用。 */
    @GetMapping("/suggest")
    public ApiResponse<List<StockBriefVO>> suggest(@RequestParam String keyword,
                                                   @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(stockService.suggest(keyword, limit));
    }

    /** 筛选项：板块、行业、交易所，避免前端硬编码。 */
    @GetMapping("/filters")
    public ApiResponse<FilterOptionsVO> filters() {
        return ApiResponse.ok(stockService.filters());
    }

    /** 股票基础信息。 */
    @GetMapping("/{code}")
    public ApiResponse<StockInfoVO> detail(@PathVariable String code) {
        return ApiResponse.ok(stockService.getStock(code));
    }

    /** 最新行情快照（详情页头部、卡片用）。 */
    @GetMapping("/{code}/quote")
    public ApiResponse<QuoteVO> quote(@PathVariable String code) {
        return ApiResponse.ok(stockService.quote(code));
    }

    /** 日线明细分页，表格用，最新日期在前。 */
    @GetMapping("/{code}/daily")
    public ApiResponse<PageResult<StockDailyVO>> daily(
            @PathVariable String code,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer adjustFlag,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(stockService.pageDaily(code, from, to, adjustFlag, page, size));
    }

    /**
     * K 线数据（含 MA5/10/20/60、MACD、RSI、BOLL）。
     *
     * @param period         day / week / month
     * @param adjustFlag     1=后复权 2=前复权 3=不复权
     * @param withIndicators 是否返回技术指标，默认 true
     */
    @GetMapping("/{code}/kline")
    public ApiResponse<KlineSeriesVO> kline(
            @PathVariable String code,
            @RequestParam(required = false, defaultValue = "day") String period,
            @RequestParam(required = false) Integer adjustFlag,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Boolean withIndicators) {
        return ApiResponse.ok(stockService.kline(code, period, adjustFlag, from, to, limit, withIndicators));
    }

    /** 区间统计：区间涨跌幅、极值、年化波动率、平均成交额/换手率。 */
    @GetMapping("/{code}/stats")
    public ApiResponse<RangeStatsVO> stats(
            @PathVariable String code,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer adjustFlag) {
        return ApiResponse.ok(stockService.rangeStats(code, from, to, adjustFlag));
    }

    /** 数据覆盖范围：首末交易日、记录数、复权口径、数据来源。 */
    @GetMapping("/{code}/data-range")
    public ApiResponse<DataRangeVO> dataRange(@PathVariable String code) {
        return ApiResponse.ok(stockService.dataRange(code));
    }
}
