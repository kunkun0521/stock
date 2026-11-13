package com.li.stock.ga.api;

import com.li.stock.common.ApiResponse;
import com.li.stock.ga.config.GaConfig;
import com.li.stock.ga.job.GaJobRunner;
import com.li.stock.ga.metric.GaQueryService;
import com.li.stock.ga.pool.PoolService;
import com.li.stock.ga.predict.ChampionService;
import com.li.stock.ga.repository.GaStoreRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 遗传算法涨跌预测看板接口（规格 12）。
 *
 * <p>路径统一 {@code /api/ga/**}，返回结构沿用项目现有的 {@link ApiResponse}（code/message/data）。
 * 参数全部用字符串接收再自行解析：前端筛选条大量传空串，直接用 LocalDate/boolean 绑定会在
 * 空串上报 400。字段名统一 camelCase，比例统一 0~1，日期统一 yyyy-MM-dd 字符串。</p>
 *
 * <p>写操作（淘汰干预、进化、回放、晋升、回滚、配置保存、任务触发）都返回 {message}，
 * 供前端 AsyncPanel 直接提示；失败返回 code=1，由 client.js 转成 ApiError。</p>
 *
 * <p>仅用于研究与复盘，输出不构成投资建议。</p>
 */
@RestController
@RequestMapping("/api/ga")
public class GaController {

    private final GaQueryService query;
    private final GaStoreRepository store;
    private final GaConfig config;
    private final GaJobRunner jobs;
    private final ChampionService champions;
    private final PoolService pool;

    @Value("${stock.ga.cron-daily:0 30 17 * * ?}")
    private String cronDaily;
    @Value("${stock.ga.cron-evolve:0 0 2 ? * SAT}")
    private String cronEvolve;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public GaController(GaQueryService query, GaStoreRepository store, GaConfig config,
                        GaJobRunner jobs, ChampionService champions, PoolService pool) {
        this.query = query;
        this.store = store;
        this.config = config;
        this.jobs = jobs;
        this.champions = champions;
        this.pool = pool;
    }

    // ================================================================== 总览

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview(
            @RequestParam(required = false) Integer horizon,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String board,
            @RequestParam(required = false) String strict,
            @RequestParam(required = false) String includeReplay) {
        LocalDate toD = date(to, LocalDate.now());
        LocalDate fromD = date(from, toD.minusDays(60));
        return ApiResponse.ok(query.overview(horizon, fromD, toD, boards(board),
                bool(strict, false), bool(includeReplay, false)));
    }

    @GetMapping("/overview/trend")
    public ApiResponse<Map<String, Object>> trend(
            @RequestParam(required = false) Integer horizon,
            @RequestParam(required = false, defaultValue = "20") int window,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        LocalDate toD = date(to, LocalDate.now());
        return ApiResponse.ok(query.trend(horizon, Math.max(1, window), date(from, toD.minusDays(120)), toD));
    }

    @GetMapping("/overview/calendar")
    public ApiResponse<List<Map<String, Object>>> calendar(
            @RequestParam(required = false) Integer horizon,
            @RequestParam(required = false) String month) {
        return ApiResponse.ok(query.calendar(horizon, month));
    }

    // ================================================================== 预测复盘 / CSV

    @GetMapping("/predictions")
    public ApiResponse<Map<String, Object>> predictions(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String horizon,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String board,
            @RequestParam(required = false) String minConf,
            @RequestParam(required = false) String maxConf,
            @RequestParam(required = false) String inPool,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String includeReplay,
            @RequestParam(required = false) String strict,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return ApiResponse.ok(store.listPredictions(predQuery(from, to, horizon, direction, result, board,
                minConf, maxConf, inPool, sort, keyword, includeReplay, strict),
                Math.max(1, page), Math.min(200, Math.max(1, size))));
    }

    /** CSV 直链导出：不经过 client.js（它会按 JSON 解析），带 BOM 方便 Excel 直接打开。 */
    @GetMapping("/predictions/export")
    public ResponseEntity<byte[]> exportPredictions(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String horizon,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String board,
            @RequestParam(required = false) String minConf,
            @RequestParam(required = false) String maxConf,
            @RequestParam(required = false) String inPool,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String includeReplay,
            @RequestParam(required = false) String strict) {
        Map<String, Object> page = store.listPredictions(predQuery(from, to, horizon, direction, result,
                board, minConf, maxConf, inPool, sort, keyword, includeReplay, strict), 1, 50000);
        List<Map<String, Object>> rows = castRows(page.get("items"));
        StringBuilder sb = new StringBuilder("\uFEFF");
        if (rows.isEmpty()) {
            sb.append("tradeDate,code,market,horizon,predDirection,probUp,confidence,targetDate,"
                    + "actualReturn,isCorrect,status,championVersion,inPool,isReplay\n");
        } else {
            List<String> keys = new ArrayList<>(rows.get(0).keySet());
            sb.append(String.join(",", keys)).append('\n');
            for (Map<String, Object> row : rows) {
                boolean first = true;
                for (String k : keys) {
                    if (!first) sb.append(',');
                    sb.append(csv(row.get(k)));
                    first = false;
                }
                sb.append('\n');
            }
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"ga_predictions_" + LocalDate.now() + ".csv\"");
        return new ResponseEntity<>(sb.toString().getBytes(StandardCharsets.UTF_8), headers, HttpStatus.OK);
    }

    // ================================================================== 个股详情 / 错误分析

    @GetMapping("/stock/{market}/{code}")
    public ApiResponse<Map<String, Object>> stock(
            @PathVariable String market,
            @PathVariable String code,
            @RequestParam(required = false) Integer horizon,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        LocalDate toD = date(to, LocalDate.now());
        return ApiResponse.ok(query.stockDetail(market, code, horizon, date(from, toD.minusDays(180)), toD));
    }

    @GetMapping("/analysis/buckets")
    public ApiResponse<List<Map<String, Object>>> buckets(
            @RequestParam(required = false, defaultValue = "5") int horizon,
            @RequestParam(required = false, defaultValue = "vol") String dim,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ApiResponse.ok(query.buckets(horizon, dim, rangeFrom(from, to), date(to, LocalDate.now())));
    }

    @GetMapping("/analysis/reliability")
    public ApiResponse<Map<String, Object>> reliability(
            @RequestParam(required = false, defaultValue = "5") int horizon,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ApiResponse.ok(query.reliability(horizon, rangeFrom(from, to), date(to, LocalDate.now())));
    }

    @GetMapping("/analysis/confusion")
    public ApiResponse<Map<String, Object>> confusion(
            @RequestParam(required = false, defaultValue = "5") int horizon,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ApiResponse.ok(query.confusion(horizon, rangeFrom(from, to), date(to, LocalDate.now())));
    }

    @GetMapping("/analysis/top-errors")
    public ApiResponse<List<Map<String, Object>>> topErrors(
            @RequestParam(required = false, defaultValue = "5") int horizon,
            @RequestParam(required = false, defaultValue = "20") int topN) {
        return ApiResponse.ok(query.topErrors(horizon, topN));
    }

    @GetMapping("/analysis/quantile")
    public ApiResponse<Map<String, Object>> quantile(
            @RequestParam(required = false, defaultValue = "5") int horizon,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ApiResponse.ok(query.quantile(horizon, rangeFrom(from, to), date(to, LocalDate.now())));
    }

    // ================================================================== 淘汰管理

    @GetMapping("/pool")
    public ApiResponse<Map<String, Object>> pool(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String reason,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return ApiResponse.ok(store.poolList(status, reason, Math.max(1, page), Math.min(200, Math.max(1, size))));
    }

    @GetMapping("/pool/log")
    public ApiResponse<List<Map<String, Object>>> poolLog(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String market,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        LocalDate toD = date(to, LocalDate.now());
        return ApiResponse.ok(store.poolLog(code, market, date(from, toD.minusDays(365)), toD));
    }

    @PostMapping("/pool/override")
    public ApiResponse<Map<String, Object>> poolOverride(@RequestBody(required = false) Map<String, Object> body) {
        try {
            Map<String, Object> b = body == null ? Map.of() : body;
            String code = str(b.get("code"));
            String market = str(b.get("market"));
            if (code.isEmpty()) return failed("股票代码不能为空");
            if (market.isEmpty()) market = guessMarket(code);
            String msg = pool.override(code, market, str(b.get("action")), str(b.get("remark")), LocalDate.now());
            return ApiResponse.ok(Map.of("message", msg, "ok", true));
        } catch (Exception e) {
            return failed(e.getMessage());
        }
    }

    // ================================================================== 进化监控

    @GetMapping("/evolution/runs")
    public ApiResponse<Map<String, Object>> runs(
            @RequestParam(required = false) Integer horizon,
            @RequestParam(name = "runType", required = false) String runType,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return ApiResponse.ok(store.listRuns(horizon, runType, Math.max(1, page), Math.min(200, Math.max(1, size))));
    }

    @GetMapping("/evolution/runs/{runId}/generations")
    public ApiResponse<List<Map<String, Object>>> generations(@PathVariable long runId) {
        return ApiResponse.ok(store.generations(runId));
    }

    @GetMapping("/individuals")
    public ApiResponse<List<Map<String, Object>>> individuals(
            @RequestParam(required = false) Long runId,
            @RequestParam(required = false) Integer horizon,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(store.individuals(runId, horizon, status));
    }

    @GetMapping("/champions")
    public ApiResponse<List<Map<String, Object>>> champions(@RequestParam(required = false) Integer horizon) {
        return ApiResponse.ok(store.championVersions(horizon));
    }

    /** 手动触发单周期进化；horizon 为空时触发全部周期。异步，返回提交说明。 */
    @PostMapping("/evolution/run")
    public ApiResponse<Map<String, Object>> evolutionRun(@RequestParam(required = false) Integer horizon) {
        return ApiResponse.ok(jobs.submitEvolve(horizon));
    }

    /** 历史回放；horizons 为数组或逗号分隔字符串，缺省用 ga_config 的全部周期。 */
    @PostMapping("/evolution/replay")
    public ApiResponse<Map<String, Object>> evolutionReplay(@RequestBody(required = false) Map<String, Object> body) {
        try {
            Map<String, Object> b = body == null ? Map.of() : body;
            LocalDate to = date(b.get("to"), LocalDate.now());
            LocalDate from = date(b.get("from"), to.minusDays(120));
            int step = integer(b.get("step"), config.globalInt("replay_step"));
            List<Integer> hs = horizons(b.get("horizons"));
            if (from.isAfter(to)) return failed("起始日期不能晚于结束日期");
            return ApiResponse.ok(jobs.submitReplay(from, to, Math.max(1, step), hs));
        } catch (Exception e) {
            return failed(e.getMessage());
        }
    }

    @PostMapping("/champions/promote")
    public ApiResponse<Map<String, Object>> promote(
            @RequestParam(required = false) Long runId,
            @RequestParam(required = false) Integer horizon) {
        try {
            if (runId == null || horizon == null) return failed("runId 与 horizon 不能为空");
            String msg = champions.promoteManually(runId, horizon, LocalDate.now());
            return ApiResponse.ok(Map.of("message", msg, "ok", true));
        } catch (Exception e) {
            return failed(e.getMessage());
        }
    }

    @PostMapping("/champions/rollback")
    public ApiResponse<Map<String, Object>> rollback(@RequestBody(required = false) Map<String, Object> body) {
        try {
            Map<String, Object> b = body == null ? Map.of() : body;
            String tag = str(b.get("versionTag"));
            int h = integer(b.get("horizon"), 0);
            if (tag.isEmpty() || h <= 0) return failed("horizon 与 versionTag 不能为空");
            int n = store.rollback(h, tag, LocalDate.now());
            if (n == 0) return failed("找不到版本 " + tag);
            return ApiResponse.ok(Map.of("message", "已回滚到 " + tag + "，恢复 " + n + " 个冠军个体", "ok", true));
        } catch (Exception e) {
            return failed(e.getMessage());
        }
    }

    // ================================================================== 因子体检 / 选股榜

    @GetMapping("/factors/ic")
    public ApiResponse<List<Map<String, Object>>> factorsIc(
            @RequestParam(required = false, defaultValue = "5") int horizon,
            @RequestParam(required = false, defaultValue = "60") int days) {
        return ApiResponse.ok(query.factorsIc(horizon, days));
    }

    @GetMapping("/picks")
    public ApiResponse<List<Map<String, Object>>> picks(
            @RequestParam(required = false) String date,
            @RequestParam(required = false, defaultValue = "20") int topN,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String minConsensus) {
        LocalDate d = date(date, LocalDate.now());
        int dir = "DOWN".equalsIgnoreCase(direction) || "-1".equals(direction) ? -1 : 1;
        double min = doubleVal(minConsensus, 0);
        return ApiResponse.ok(query.picks(d, topN, dir, min));
    }

    // ================================================================== 配置 / 后台任务

    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> config(@RequestParam(required = false) String scope) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", config.list(scope));
        out.put("defaults", GaConfig.defaults());
        return ApiResponse.ok(out);
    }

    @PutMapping("/config")
    public ApiResponse<Map<String, Object>> saveConfig(@RequestBody(required = false) Map<String, Object> body) {
        try {
            Map<String, Object> b = body == null ? Map.of() : body;
            String scope = str(b.get("scope"));
            String key = str(b.get("paramKey"));
            String value = str(b.get("paramValue"));
            if (scope.isEmpty() || key.isEmpty()) return failed("scope 与 paramKey 不能为空");
            config.set(scope, key, value);
            return ApiResponse.ok(Map.of("ok", true, "message", key + " 已保存"));
        } catch (Exception e) {
            return failed(e.getMessage());
        }
    }

    @GetMapping("/jobs/status")
    public ApiResponse<List<Map<String, Object>>> jobsStatus() {
        return ApiResponse.ok(jobs.status());
    }

    @PostMapping("/jobs/run")
    public ApiResponse<Map<String, Object>> runJob(@RequestBody(required = false) Map<String, Object> body) {
        try {
            String name = str((body == null ? Map.of() : body).get("job"));
            if (name.isEmpty()) return failed("job 不能为空");
            return ApiResponse.ok(jobs.submit(name));
        } catch (Exception e) {
            return failed(e.getMessage());
        }
    }

    /** 最近 N 条任务执行日志，供「后台任务」页验证触发是否生效。 */
    @GetMapping("/jobs/log")
    public ApiResponse<List<Map<String, Object>>> jobsLog(@RequestParam(required = false, defaultValue = "30") Integer limit) {
        return ApiResponse.ok(store.recentJobLogs(limit == null ? 30 : limit));
    }

    /** 定时调度状态：cron、下次触发时间、配置文件总开关与看板运行时开关。 */
    @GetMapping("/jobs/schedule")
    public ApiResponse<Map<String, Object>> jobsSchedule() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configEnabled", jobs.isConfigEnabled());
        out.put("runtimeEnabled", jobs.isRuntimeEnabled());
        out.put("enabled", jobs.isScheduleEnabled());
        out.put("cronDaily", cronDaily);
        out.put("cronEvolve", cronEvolve);
        out.put("nextDaily", nextFire(cronDaily));
        out.put("nextEvolve", nextFire(cronEvolve));
        out.put("dailyJob", "DailyPipeline");
        out.put("weeklyJob", "WeeklyEvolve");
        out.put("initJob", "InitJob");
        out.put("initEnabled", jobs.isInitEnabled());
        out.put("initReady", jobs.isInitReady());
        out.put("timezone", "Asia/Shanghai");
        out.put("serverTime", LocalDateTime.now().format(TIME_FMT));
        out.put("executor", "单线程串行队列");
        return ApiResponse.ok(out);
    }

    /** 看板切换定时触发开关；不传 enabled 则取反。手动触发始终不受影响。 */
    @PostMapping("/jobs/schedule/toggle")
    public ApiResponse<Map<String, Object>> toggleSchedule(@RequestBody(required = false) Map<String, Object> body) {
        try {
            Object raw = (body == null ? Map.of() : body).get("enabled");
            boolean value = raw == null ? !jobs.isRuntimeEnabled()
                    : (raw instanceof Boolean b ? b : "1".equals(String.valueOf(raw))
                    || "true".equalsIgnoreCase(String.valueOf(raw)));
            boolean effective = jobs.setRuntimeEnabled(value);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("enabled", effective);
            out.put("runtimeEnabled", jobs.isRuntimeEnabled());
            out.put("configEnabled", jobs.isConfigEnabled());
            out.put("message", effective ? "定时触发已启用" : "定时触发已停用（手动运行仍可用）");
            return ApiResponse.ok(out);
        } catch (Exception e) {
            return failed(e.getMessage());
        }
    }

    /** 按定时规则立即执行一次，走与 @Scheduled 完全相同的提交路径。 */
    @PostMapping("/jobs/schedule/fire")
    public ApiResponse<Map<String, Object>> fireSchedule(@RequestBody(required = false) Map<String, Object> body) {
        try {
            String kind = str((body == null ? Map.of() : body).get("kind"));
            String message = jobs.fireScheduled(kind);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("kind", kind);
            out.put("job", "weekly".equalsIgnoreCase(kind) ? "WeeklyEvolve" : "DailyPipeline");
            out.put("message", message);
            out.put("accepted", true);
            return ApiResponse.ok(out);
        } catch (Exception e) {
            return failed(e.getMessage());
        }
    }

    /** 计算 cron 的下一次触发时间；解析失败返回空串，不影响接口本身。 */
    private static String nextFire(String cron) {
        try {
            ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
            ZonedDateTime next = CronExpression.parse(cron).next(now);
            return next == null ? "" : next.format(TIME_FMT);
        } catch (Exception e) {
            return "";
        }
    }

    // ================================================================== 参数工具

    /** 预测列表 / CSV 共用的筛选参数，统一在这里做前端枚举 → SQL 取值的归一化。 */
    private static Map<String, String> predQuery(String from, String to, String horizon, String direction,
                                                 String result, String board, String minConf, String maxConf,
                                                 String inPool, String sort, String keyword,
                                                 String includeReplay, String strict) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("from", str(from).trim());
        q.put("to", str(to).trim());
        q.put("horizon", str(horizon).trim());
        q.put("direction", str(direction).trim());
        q.put("result", str(result).trim());
        q.put("board", String.join(",", boards(board)));
        q.put("minConf", str(minConf).trim());
        q.put("maxConf", str(maxConf).trim());
        // 前端传 true/false，SQL 列是 tinyint
        q.put("inPool", boolText(inPool));
        q.put("includeReplay", boolText(includeReplay));
        q.put("strict", boolText(strict));
        q.put("sort", str(sort).trim());
        q.put("keyword", str(keyword).trim());
        return q;
    }

    private static String boolText(String v) {
        String s = str(v).trim();
        if (s.isEmpty()) return "";
        if ("true".equalsIgnoreCase(s) || "yes".equalsIgnoreCase(s) || "1".equals(s)) return "1";
        if ("false".equalsIgnoreCase(s) || "no".equalsIgnoreCase(s) || "0".equals(s)) return "0";
        return s;
    }

    /** 板块 → 代码前缀；主板包含 00 与 60 两个前缀。 */
    private static List<String> boards(String board) {
        String b = str(board).trim().toUpperCase();
        if (b.isEmpty() || "ALL".equals(b)) return List.of();
        return switch (b) {
            case "MAIN" -> List.of("00", "60");
            case "GEM" -> List.of("30");
            case "STAR" -> List.of("688");
            case "BJ" -> List.of("4", "8", "9");
            default -> List.of(b);
        };
    }

    private static List<Integer> horizons(Object raw) {
        List<Integer> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                try { out.add(Integer.parseInt(str(o).trim())); } catch (NumberFormatException ignored) {}
            }
        } else {
            for (String part : str(raw).split(",")) {
                try { out.add(Integer.parseInt(part.trim())); } catch (NumberFormatException ignored) {}
            }
        }
        return out.isEmpty() ? List.of(1, 3, 5, 10, 15) : out;
    }

    private static String guessMarket(String code) {
        if (code.length() >= 1 && "569".indexOf(code.charAt(0)) >= 0) return "SH";
        return "SZ";
    }

    private static LocalDate date(Object raw, LocalDate fallback) {
        String s = str(raw).trim();
        if (s.isEmpty()) return fallback;
        try {
            return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static LocalDate rangeFrom(String from, String to) {
        return date(from, date(to, LocalDate.now()).minusDays(120));
    }

    private static boolean bool(String raw, boolean fallback) {
        String s = str(raw).trim();
        if (s.isEmpty()) return fallback;
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s);
    }

    private static int integer(Object raw, int fallback) {
        try { return Integer.parseInt(str(raw).trim()); } catch (Exception e) { return fallback; }
    }

    private static double doubleVal(Object raw, double fallback) {
        try { return Double.parseDouble(str(raw).trim()); } catch (Exception e) { return fallback; }
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    private static String csv(Object o) {
        String s = str(o).replace("\"", "\"\"");
        return "\"" + s + "\"";
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castRows(Object o) {
        if (!(o instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) if (item instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        return out;
    }

    private static <T> ApiResponse<T> failed(String message) {
        return new ApiResponse<>(1, message == null ? "操作失败" : message, null, System.currentTimeMillis());
    }
}
