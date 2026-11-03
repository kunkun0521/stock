package com.li.stock.repository;

import com.li.stock.domain.query.StockQuery;
import com.li.stock.domain.vo.StockBriefVO;
import com.li.stock.domain.vo.StockInfoVO;
import com.li.stock.mapper.StockInfoMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * stock_info 表访问。
 */
@Repository
public class StockInfoRepository {

    private final StockInfoMapper mapper;

    public StockInfoRepository(StockInfoMapper mapper) {
        this.mapper = mapper;
    }

    public long count(StockQuery query) {
        return mapper.count(query);
    }

    public List<StockInfoVO> page(StockQuery query, int offset, int size) {
        return mapper.page(query, offset, size);
    }

    public Optional<StockInfoVO> findByCode(String code) {
        return Optional.ofNullable(mapper.findByCode(code));
    }

    public List<StockBriefVO> suggest(String keyword, int limit) {
        return mapper.suggest(keyword, limit);
    }

    public List<String> distinctBoards() {
        return mapper.distinctBoards();
    }

    public List<String> distinctIndustries() {
        return mapper.distinctIndustries();
    }

    public List<String> distinctExchanges() {
        return mapper.distinctExchanges();
    }

    public List<StockInfoVO> crawlerStocks(List<String> prefixes, int limit) {
        return mapper.crawlerStocks(prefixes, limit);
    }

    public int upsertCrawlerStocks(List<StockInfoVO> list) {
        return list.isEmpty() ? 0 : mapper.upsertCrawlerStocks(list);
    }

}
