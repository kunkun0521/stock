package com.li.stock.mapper;

import com.li.stock.domain.model.CrawlTask;
import com.li.stock.domain.vo.CrawlTaskVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CrawlTaskMapper {
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int create(CrawlTask task);

    CrawlTaskVO find(@Param("id") long id);

    List<CrawlTaskVO> recent(@Param("limit") int limit);

    int updateProgress(@Param("id") long id,
                       @Param("totalCount") int totalCount,
                       @Param("successCount") int successCount,
                       @Param("failCount") int failCount,
                       @Param("remark") String remark);

    int finish(@Param("id") long id,
               @Param("status") String status,
               @Param("successCount") int successCount,
               @Param("failCount") int failCount,
               @Param("remark") String remark);
}
