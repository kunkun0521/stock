package com.li.stock.prob.api;

import com.li.stock.prob.task.ProbRepository;
import com.li.stock.prob.task.ProbTaskService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A股股票概率预测引擎 REST 接口（文档 §10.3）。
 *
 * <p>统一返回 {@link ProbResponse}：{code,message,data,disclaimer}；前端 client.js 只取 data。
 * 参数全部用字符串接收再解析（前端筛选条常传空串，直接用数值/日期类型绑定会 400）。
 * 全部为只读查询 + 一个异步任务触发入口；统计计算在 Python 引擎，后端不重写公式。</p>
 *
 * <p>性能：列表走覆盖索引、ORDER BY 不追加多余键、分页上限 200；任务异步，页面轮询进度。</p>
 */
@RestController
@RequestMapping("/api/prob")
public class ProbController {

    private final ProbRepository repo;
    private final ProbTaskService tasks;

    public ProbController(ProbRepository repo, ProbTaskService tasks) {
        this.repo = repo;
        this.tasks = tasks;
    }

    private static int intOf(String s, int def) {
        try {
            return s == null || s.isBlank() ? def : Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static Integer optInt(String s) {
        try {
            return s == null || s.isBlank() ? null : Integer.parseInt(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean boolOf(String s) {
        return "1".equals(s) || "true".equalsIgnoreCase(s) || "yes".equalsIgnoreCase(s);
    }

    // ------------------------------------------------------------------ 总览 / 榜单

    @GetMapping("/overview")
    public ProbResponse<Map<String, Object>> overview() {
        return ProbResponse.ok(repo.overview());
    }

    @GetMapping("/predictions")
    public ProbResponse<Map<String, Object>> predictions(
            @RequestParam(required = false) String asof,
            @RequestParam(required = false, defaultValue = "1") String horizon,
            @RequestParam(required = false, defaultValue = "p_up") String sort,
            @RequestParam(required = false, defaultValue = "desc") String order,
            @RequestParam(required = false) String riskLevel,
            @RequestParam(required = false) String minGrade,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false, defaultValue = "false") String excludeHighVol,
            @RequestParam(required = false, defaultValue = "1") String page,
            @RequestParam(required = false, defaultValue = "20") String size) {
        return ProbResponse.ok(repo.predictions(asof, intOf(horizon, 1), sort, order,
                optInt(riskLevel), minGrade, keyword, boolOf(excludeHighVol),
                intOf(page, 1), intOf(size, 20)));
    }

    // ------------------------------------------------------------------ 个股

    @GetMapping("/stocks/{market}/{code}")
    public ProbResponse<Map<String, Object>> stock(@PathVariable String market,
                                                   @PathVariable String code) {
        return ProbResponse.ok(repo.stock(market, code));
    }

    @GetMapping("/stocks/{market}/{code}/history")
    public ProbResponse<Map<String, Object>> history(@PathVariable String market,
                                                     @PathVariable String code,
                                                     @RequestParam(required = false, defaultValue = "1") String horizon,
                                                     @RequestParam(required = false) String from,
                                                     @RequestParam(required = false) String to) {
        return ProbResponse.ok(repo.history(market, code, intOf(horizon, 1), from, to));
    }

    @GetMapping("/stocks/{market}/{code}/signals")
    public ProbResponse<Map<String, Object>> signals(@PathVariable String market,
                                                     @PathVariable String code,
                                                     @RequestParam(required = false, defaultValue = "1") String horizon) {
        return ProbResponse.ok(repo.signals(market, code, intOf(horizon, 1)));
    }

    // ------------------------------------------------------------------ 评估 / 复盘

    @GetMapping("/eval/metrics")
    public ProbResponse<Map<String, Object>> metrics(@RequestParam(required = false) String horizon,
                                                     @RequestParam(required = false) String scope,
                                                     @RequestParam(required = false) String model) {
        return ProbResponse.ok(repo.metrics(optInt(horizon), scope, model));
    }

    @GetMapping("/eval/series")
    public ProbResponse<Map<String, Object>> metricSeries(@RequestParam(required = false, defaultValue = "Brier") String metric,
                                                          @RequestParam(required = false) String horizon) {
        return ProbResponse.ok(repo.metricSeries(metric, optInt(horizon)));
    }

    @GetMapping("/eval/calibration")
    public ProbResponse<Map<String, Object>> calibration(@RequestParam(required = false) String horizon,
                                                         @RequestParam(required = false) String model) {
        return ProbResponse.ok(repo.calibration(optInt(horizon), model));
    }

    @GetMapping("/eval/methods")
    public ProbResponse<Map<String, Object>> methods(@RequestParam(required = false) String horizon) {
        return ProbResponse.ok(repo.methods(optInt(horizon)));
    }

    // ------------------------------------------------------------------ 回测

    @GetMapping("/backtests")
    public ProbResponse<Map<String, Object>> backtests(
            @RequestParam(required = false, defaultValue = "1") String page,
            @RequestParam(required = false, defaultValue = "20") String size) {
        return ProbResponse.ok(repo.backtests(intOf(page, 1), intOf(size, 20)));
    }

    @GetMapping("/backtests/{id}")
    public ProbResponse<Map<String, Object>> backtest(@PathVariable long id) {
        return ProbResponse.ok(repo.backtest(id));
    }

    // ------------------------------------------------------------------ 任务与配置

    @GetMapping("/runs")
    public ProbResponse<Map<String, Object>> runs(
            @RequestParam(required = false, defaultValue = "1") String page,
            @RequestParam(required = false, defaultValue = "20") String size) {
        Map<String, Object> data = new LinkedHashMap<>(repo.runs(intOf(page, 1), intOf(size, 20)));
        data.put("running", tasks.runningCount());
        return ProbResponse.ok(data);
    }

    @GetMapping("/runs/{id}")
    public ProbResponse<Map<String, Object>> run(@PathVariable long id) {
        return ProbResponse.ok(repo.run(id));
    }

    @PostMapping("/runs")
    public ProbResponse<Map<String, Object>> launch(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> opts = body == null ? Map.of() : body;
        return ProbResponse.ok(tasks.launch(String.valueOf(opts.getOrDefault("type", "DAILY")), opts));
    }

    @GetMapping("/config")
    public ProbResponse<Map<String, Object>> config() {
        return ProbResponse.ok(repo.config());
    }

    @GetMapping("/features")
    public ProbResponse<Map<String, Object>> features() {
        return ProbResponse.ok(repo.features());
    }

    @GetMapping("/models")
    public ProbResponse<Map<String, Object>> models() {
        return ProbResponse.ok(repo.modelRegistry());
    }
}
