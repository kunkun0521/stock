package com.li.stock.mapper;

import com.li.stock.domain.model.DataRangeStat;
import com.li.stock.domain.vo.CrawlerDateRangeVO;
import com.li.stock.domain.vo.StockDailyVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface StockDailyMapper {
    long count(@Param("tableName") String tableName, @Param("code") String code, @Param("from") LocalDate from,
               @Param("to") LocalDate to, @Param("adjustFlag") Integer adjustFlag);

    List<StockDailyVO> page(@Param("tableName") String tableName, @Param("code") String code, @Param("from") LocalDate from,
                            @Param("to") LocalDate to, @Param("adjustFlag") Integer adjustFlag,
                            @Param("offset") int offset, @Param("size") int size);

    List<StockDailyVO> recentDesc(@Param("tableName") String tableName, @Param("code") String code, @Param("from") LocalDate from,
                                  @Param("to") LocalDate to, @Param("adjustFlag") Integer adjustFlag,
                                  @Param("limit") int limit);

    List<StockDailyVO> listAsc(@Param("tableName") String tableName, @Param("code") String code, @Param("from") LocalDate from,
                               @Param("to") LocalDate to, @Param("adjustFlag") Integer adjustFlag);

    StockDailyVO latest(@Param("tableName") String tableName, @Param("code") String code, @Param("adjustFlag") Integer adjustFlag);

    StockDailyVO latestBefore(@Param("tableName") String tableName, @Param("code") String code, @Param("date") LocalDate date,
                              @Param("adjustFlag") Integer adjustFlag);

    DataRangeStat dataRange(@Param("tableName") String tableName, @Param("code") String code);

    Integer preferredAdjustFlag(@Param("tableName") String tableName, @Param("code") String code);

    List<CrawlerDateRangeVO> crawlerDateRanges(@Param("codes") List<String> codes,
                                               @Param("adjustFlags") List<Integer> adjustFlags);

    /**
     * 按抓取路由写入分表。tableName 只能由 StockDailyRepository 的白名单路由产生，
     * XML 中使用 ${tableName} 是为了让 MyBatis 支持动态表名。
     */
    int saveCrawlerBatch(@Param("tableName") String tableName,
                         @Param("list") List<StockDailyVO> list);
}
