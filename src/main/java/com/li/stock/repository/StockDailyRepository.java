package com.li.stock.repository;

import com.li.stock.domain.model.DataRangeStat;
import com.li.stock.domain.vo.CrawlerDateRangeVO;
import com.li.stock.domain.vo.StockDailyVO;
import com.li.stock.mapper.StockDailyMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 股票日线访问。单只股票查询按代码路由到对应分表，跨股票统计通过 stock_daily_all 统一视图读取三张分表。
 */
@Repository
public class StockDailyRepository {

    private final StockDailyMapper mapper;

    public StockDailyRepository(StockDailyMapper mapper) {
        this.mapper = mapper;
    }

    public long count(String code, LocalDate from, LocalDate to, Integer adjustFlag) {
        return mapper.count(tableForCode(code), code, from, to, adjustFlag);
    }

    /** 分页查询，按交易日期倒序（最新在前），适合表格。 */
    public List<StockDailyVO> page(String code, LocalDate from, LocalDate to, Integer adjustFlag,
                                   int offset, int size) {
        return mapper.page(tableForCode(code), code, from, to, adjustFlag, offset, size);
    }

    /** 取最近 limit 条并翻转为时间升序，供 K 线使用。 */
    public List<StockDailyVO> recentAsc(String code, LocalDate from, LocalDate to, Integer adjustFlag, int limit) {
        List<StockDailyVO> desc = recentDesc(code, from, to, adjustFlag, limit);
        List<StockDailyVO> asc = new ArrayList<>(desc);
        Collections.reverse(asc);
        return asc;
    }

    public List<StockDailyVO> recentDesc(String code, LocalDate from, LocalDate to, Integer adjustFlag, int limit) {
        return mapper.recentDesc(tableForCode(code), code, from, to, adjustFlag, limit);
    }

    /** 区间全量（时间升序），用于区间统计。单只股票量级可控。 */
    public List<StockDailyVO> listAsc(String code, LocalDate from, LocalDate to, Integer adjustFlag) {
        return mapper.listAsc(tableForCode(code), code, from, to, adjustFlag);
    }

    public Optional<StockDailyVO> latest(String code, Integer adjustFlag) {
        return Optional.ofNullable(mapper.latest(tableForCode(code), code, adjustFlag));
    }

    public Optional<StockDailyVO> latestBefore(String code, LocalDate date, Integer adjustFlag) {
        return Optional.ofNullable(mapper.latestBefore(tableForCode(code), code, date, adjustFlag));
    }

    public DataRangeStat dataRange(String code) {
        return mapper.dataRange(tableForCode(code), code);
    }

    /**
     * 该股票记录最多的复权口径，作为未显式指定 adjustFlag 时的默认值。
     * <p>表里同一交易日可能有多个复权口径的记录，若不指定口径，K 线会出现重复日期。</p>
     */
    public Optional<Integer> preferredAdjustFlag(String code) {
        return Optional.ofNullable(mapper.preferredAdjustFlag(tableForCode(code), code));
    }

    /** 批量读取最早/最新日期，用于同时补齐历史前段和最新后段。 */
    public Map<String, CrawlerDateRangeVO> crawlerDateRanges(List<String> codes,
                                                              List<Integer> adjustFlags) {
        Map<String, CrawlerDateRangeVO> result = new LinkedHashMap<>();
        if (codes.isEmpty() || adjustFlags.isEmpty()) return result;
        for (CrawlerDateRangeVO row : mapper.crawlerDateRanges(codes, adjustFlags)) {
            result.put(row.code() + "#" + row.adjustFlag(), row);
        }
        return result;
    }

    public int saveCrawlerBatch(List<StockDailyVO> rows) {
        if (rows.isEmpty()) return 0;
        Map<String, List<StockDailyVO>> groups = new LinkedHashMap<>();
        for (StockDailyVO row : rows) {
            groups.computeIfAbsent(crawlerTable(row.code()), ignored -> new ArrayList<>()).add(row);
        }
        return groups.entrySet().stream()
                .mapToInt(entry -> mapper.saveCrawlerBatch(entry.getKey(), entry.getValue()))
                .sum();
    }

    /**
     * 按 A 股代码前缀路由到唯一的物理分表。这里返回值只来自白名单，
     * 既避免 SQL 动态表名注入，也避免数据重新落回已经废弃的旧表。
     */
    private String tableForCode(String code) {
        if (code != null && code.startsWith("00")) return "stock_daily_00";
        if (code != null && code.startsWith("30")) return "stock_daily_30";
        if (code != null && code.startsWith("60")) return "stock_daily_60";
        throw new IllegalArgumentException("不支持的股票代码分表前缀：" + code + "，仅支持 00、30、60");
    }

    private String crawlerTable(String code) {
        return tableForCode(code);
    }

}
