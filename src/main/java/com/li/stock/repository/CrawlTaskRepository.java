package com.li.stock.repository;

import com.li.stock.domain.model.CrawlTask;
import com.li.stock.domain.vo.CrawlTaskVO;
import com.li.stock.mapper.CrawlTaskMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class CrawlTaskRepository {
    private final CrawlTaskMapper mapper;

    public CrawlTaskRepository(CrawlTaskMapper mapper) {
        this.mapper = mapper;
    }

    public CrawlTask create(CrawlTask task) {
        mapper.create(task);
        return task;
    }

    public CrawlTaskVO find(long id) {
        return mapper.find(id);
    }

    public List<CrawlTaskVO> recent(int limit) {
        return mapper.recent(limit);
    }

    public void updateProgress(long id, int totalCount, int successCount, int failCount, String remark) {
        mapper.updateProgress(id, totalCount, successCount, failCount, remark);
    }

    public void finish(long id, String status, int successCount, int failCount, String remark) {
        mapper.finish(id, status, successCount, failCount, remark);
    }
}
