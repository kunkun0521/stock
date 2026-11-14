package com.li.stock.quant.api;

import com.li.stock.common.ApiResponse;
import com.li.stock.common.BizException;
import com.li.stock.quant.data.MarketPanel;
import com.li.stock.quant.data.PanelService;
import com.li.stock.quant.strategy.Strategy;
import com.li.stock.quant.strategy.StrategyRegistry;
import com.li.stock.quant.task.QuantRepository;
import com.li.stock.quant.task.QuantTaskService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 量化策略研究与回测平台接口（/api/quant）。
 *
 * <p>响应统一 {@link ApiResponse}；耗时任务（回测）一律异步，提交返回 taskId，
 * 之后轮询 {@code /backtests/{id}} 拿进度。策略列表会把 ParamDef 一起返回，
 * 前端据此动态生成参数表单，新增策略无需改接口与页面。</p>
 *
 * <p>性能：行情面板按快照缓存在内存，首次加载约数秒，之后回测/选股只做纯内存计算；
 * 列表与明细全部走主键/索引，分页有上限。</p>
 */
@RestController
@RequestMapping("/api/quant")
public class QuantController {

    private final PanelService panels;
    private final StrategyRegistry registry;
    private final QuantTaskService tasks;
    private final QuantRepository repo;

    public QuantController(PanelService panels, StrategyRegistry registry,
                           QuantTaskService tasks, QuantRepository repo) {
        this.panels = panels;
        this.registry = registry;
        this.tasks = tasks;
        this.repo = repo;
    }

    // ------------------------------------------------------------------ 策略

    @GetMapping("/strategies")
    public ApiResponse<List<Map<String, Object>>> strategies() {
        Map<String, Map<String, Object>> latest = new LinkedHashMap<>();
        try {
            tasks.latestBacktests().forEach(m -> latest.put(String.valueOf(m.get("strategyKey")), m));
        } catch (Exception ignore) {
            // 首次启动表还没建好时不影响策略列表
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (Strategy s : registry.all()) {
            Map<String, Object> m = registry.describe(s);
            Map<String, Object> last = latest.get(s.meta().key());
            if (last != null) {
                Map<String, Object> brief = new LinkedHashMap<>();
                brief.put("taskId", last.get("id"));
                brief.put("status", last.get("status"));
                brief.put("createdAt", last.get("createdAt"));
                Object summary = last.get("summary");
                if (summary instanceof Map<?, ?> sm) {
                    brief.put("metrics", sm.get("metrics"));
                }
                m.put("lastBacktest", brief);
            }
            m.put("dataReady", panels.peek() != null);
            list.add(m);
        }
        return ApiResponse.ok(list);
    }

    @GetMapping("/strategies/{key}")
    public ApiResponse<Map<String, Object>> strategy(@PathVariable String key) {
        return ApiResponse.ok(registry.describe(registry.get(key)));
    }

    // ------------------------------------------------------------------ 数据

    @GetMapping("/data/overview")
    public ApiResponse<Map<String, Object>> overview() {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> status = panels.status();
        m.putAll(status);
        m.put("strategyCount", registry.all().size());
        m.put("runningTasks", tasks.runningCount());
        if (status.get("loaded") == Boolean.TRUE) {
            MarketPanel p = panels.peek();
            if (p != null) {
                m.put("benchmarkLast", p.benchmarkNav()[p.dateCount() - 1]);
            }
        } else if (status.get("loading") != Boolean.TRUE) {
            // 首次访问顺手预热，页面刷新时通常已就绪
            panels.startLoad();
            m.put("message", "行情面板开始加载，请稍候刷新");
        }
        m.put("config", Map.of(
                "adjustflag", 2,
                "source", "stock_daily_all",
                "maxPositions", 10,
                "costNote", "佣金 0.025%（最低 5 元）+ 印花税 0.05%（卖出）+ 过户费 0.001%"
        ));
        return ApiResponse.ok(m);
    }

    @PostMapping("/data/reload")
    public ApiResponse<Map<String, Object>> reload() {
        panels.invalidate();
        panels.startLoad();
        return ApiResponse.ok(Map.of("message", "已触发重新加载行情面板", "status", panels.status()));
    }

    // ------------------------------------------------------------------ 回测

    @PostMapping("/backtests")
    public ApiResponse<Map<String, Object>> submit(@RequestBody Map<String, Object> body) {
        String key = str(body.get("strategyKey"));
        @SuppressWarnings("unchecked")
        Map<String, Object> params = body.get("params") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        Map<String, Object> config = body.get("config") instanceof Map<?, ?> c
                ? cast(c) : body;
        long id = tasks.submit(key, params, config);
        return ApiResponse.ok(Map.of("taskId", id, "status", "PENDING"));
    }

    @GetMapping("/backtests")
    public ApiResponse<Map<String, Object>> list(@RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int size,
                                                 @RequestParam(required = false) String strategyKey) {
        return ApiResponse.ok(tasks.list(page, size, strategyKey));
    }

    @GetMapping("/backtests/{id}")
    public ApiResponse<Map<String, Object>> task(@PathVariable long id) {
        return ApiResponse.ok(tasks.taskView(id));
    }

    @GetMapping("/backtests/{id}/metrics")
    public ApiResponse<Map<String, Object>> metrics(@PathVariable long id,
                                                    @RequestParam(defaultValue = "ALL") String split) {
        String s = split.toUpperCase();
        if (!List.of("ALL", "IS", "OOS").contains(s)) {
            throw BizException.badRequest("split 只能是 ALL / IS / OOS");
        }
        return ApiResponse.ok(repo.getMetrics(id, s));
    }

    @GetMapping("/backtests/{id}/equity")
    public ApiResponse<List<Map<String, Object>>> equity(@PathVariable long id) {
        return ApiResponse.ok(repo.getEquity(id));
    }

    @GetMapping("/backtests/{id}/trades")
    public ApiResponse<Map<String, Object>> trades(@PathVariable long id,
                                                   @RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(repo.getTrades(id, page, size));
    }

    @GetMapping("/backtests/{id}/events")
    public ApiResponse<List<Map<String, Object>>> events(@PathVariable long id) {
        return ApiResponse.ok(repo.getEvents(id));
    }

    @PostMapping("/backtests/{id}/cancel")
    public ApiResponse<Map<String, Object>> cancel(@PathVariable long id) {
        tasks.cancel(id);
        return ApiResponse.ok(Map.of("message", "已请求取消"));
    }

    // ------------------------------------------------------------------ 每日选股

    @PostMapping("/signals/scan")
    public ApiResponse<Map<String, Object>> scan(@RequestBody(required = false) Map<String, Object> body) {
        List<String> keys = new ArrayList<>();
        Map<String, Map<String, Object>> params = new LinkedHashMap<>();
        if (body != null) {
            Object rawKeys = body.get("strategies");
            if (rawKeys instanceof List<?> list) {
                list.forEach(k -> keys.add(String.valueOf(k)));
            }
            if (body.get("params") instanceof Map<?, ?> pm) {
                pm.forEach((k, v) -> {
                    if (v instanceof Map<?, ?> one) params.put(String.valueOf(k), cast(one));
                });
            }
        }
        return ApiResponse.ok(tasks.scan(keys, params));
    }

    @GetMapping("/signals")
    public ApiResponse<List<Map<String, Object>>> signals(@RequestParam(required = false) String date,
                                                          @RequestParam(required = false) String strategyKey) {
        LocalDate d;
        if (date == null || date.isBlank()) {
            MarketPanel p = panels.peek();
            d = p != null ? p.dateAt(p.dateCount() - 1) : LocalDate.now();
        } else {
            d = LocalDate.parse(date);
        }
        return ApiResponse.ok(tasks.storedSignals(d, strategyKey));
    }

    // ------------------------------------------------------------------ 工具

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }
}
