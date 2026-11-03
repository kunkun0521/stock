package com.li.stock.controller;

import com.li.stock.common.ApiResponse;
import com.li.stock.domain.query.CrawlTaskRequest;
import com.li.stock.domain.vo.CrawlTaskVO;
import com.li.stock.service.CrawlTaskService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 独立的股票行情抓取工具接口。 */
@RestController
@RequestMapping("/api/data-crawler")
public class CrawlTaskController {
    private final CrawlTaskService service;

    public CrawlTaskController(CrawlTaskService service) {
        this.service = service;
    }

    @PostMapping("/tasks")
    public ApiResponse<CrawlTaskVO> start(@RequestBody(required = false) CrawlTaskRequest request) {
        return ApiResponse.ok(service.start(request));
    }

    @GetMapping("/tasks/{id}")
    public ApiResponse<CrawlTaskVO> find(@PathVariable long id) {
        return ApiResponse.ok(service.find(id));
    }

    @GetMapping("/tasks")
    public ApiResponse<List<CrawlTaskVO>> recent(
            @RequestParam(required = false, defaultValue = "20") Integer limit) {
        return ApiResponse.ok(service.recent(limit));
    }
}
