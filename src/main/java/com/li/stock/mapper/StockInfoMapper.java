package com.li.stock.mapper;

import com.li.stock.domain.query.StockQuery;
import com.li.stock.domain.vo.StockBriefVO;
import com.li.stock.domain.vo.StockInfoVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface StockInfoMapper {
    long count(@Param("query") StockQuery query);

    List<StockInfoVO> page(@Param("query") StockQuery query,
                           @Param("offset") int offset,
                           @Param("size") int size);

    StockInfoVO findByCode(@Param("code") String code);

    List<StockBriefVO> suggest(@Param("keyword") String keyword, @Param("limit") int limit);

    List<String> distinctBoards();

    List<String> distinctIndustries();

    List<String> distinctExchanges();

    List<StockInfoVO> crawlerStocks(@Param("prefixes") List<String> prefixes,
                                    @Param("limit") int limit);

    int upsertCrawlerStocks(@Param("list") List<StockInfoVO> list);
}
