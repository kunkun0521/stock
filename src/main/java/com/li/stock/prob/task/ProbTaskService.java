package com.li.stock.prob.task;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 异步任务调度：后端通过命令行拉起 Python 引擎，进度写进 prob_run（文档 §10.1、§10.3）。
 *
 * <p>前端点「一键运行」→ 先插入一条 RUNNING 记录拿到 run_id → 把 --run-id 传给引擎 CLI，
 * 引擎复用同一条记录写成功/失败数量与配置快照；Java 侧在子进程退出后兜底修正状态。
 * 全程不需要人工敲命令，也不启动额外的常驻服务。</p>
 */
@Service
public class ProbTaskService {

    private static final Logger log = LoggerFactory.getLogger(ProbTaskService.class);
    private static final Map<String, String> COMMANDS = Map.of(
            "DAILY", "daily", "PREPARE", "prepare", "TRAIN", "train",
            "PREDICT", "predict", "WALKFORWARD", "walkforward", "SETTLE", "settle",
            "EVALUATE", "evaluate", "BACKTEST", "backtest");

    private final JdbcTemplate jdbc;
    private final ProbRepository repo;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final AtomicInteger running = new AtomicInteger(0);

    @Value("${stock.prob.python:py}") private String python;
    @Value("${stock.prob.python-args:-3}") private String pythonArgs;
    @Value("${stock.prob.workdir:D:/code/PythonProject/stock-probability-engine}") private String workdir;
    @Value("${stock.prob.enabled:true}") private boolean enabled;

    public ProbTaskService(JdbcTemplate jdbc, ProbRepository repo) {
        this.jdbc = jdbc;
        this.repo = repo;
    }

    public int runningCount() {
        return running.get();
    }

    public Map<String, Object> launch(String type, Map<String, Object> body) {
        String t = (type == null ? "DAILY" : type.toUpperCase());
        String cli = COMMANDS.get(t);
        if (cli == null) throw new IllegalArgumentException("不支持的任务类型：" + t);
        if (!enabled) throw new IllegalStateException("概率引擎任务已在配置中关闭（stock.prob.enabled=false）");
        if (running.get() >= 2) throw new IllegalStateException("已有 2 个引擎任务在运行，请等待完成");

        long runId = createRun(t);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("runId", runId);
        res.put("type", t);
        res.put("status", "RUNNING");
        res.put("message", "任务已提交，引擎正在后台运行；完成后页面会自动刷新");

        Map<String, Object> opts = body == null ? Map.of() : body;
        pool.submit(() -> execute(runId, cli, opts));
        return res;
    }

    private long createRun(String type) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO prob_run(run_type,asof_date,config_json,model_version,status,"
                            + "n_universe,n_ok,n_fail,excluded_json,data_warn_count,started_at,message) "
                            + "VALUES(?,NULL,'{}',?, 'RUNNING',0,0,0,'{}',0,NOW(),?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, type);
            ps.setString(2, "spe-web-" + LocalDate.now());
            ps.setString(3, "由网页触发：" + type);
            return ps;
        }, kh);
        Number key = kh.getKey();
        if (key == null) throw new IllegalStateException("无法获取 prob_run 自增主键");
        return key.longValue();
    }

    private void execute(long runId, String cli, Map<String, Object> opts) {
        running.incrementAndGet();
        List<String> cmd = new ArrayList<>();
        cmd.add(python);
        if (pythonArgs != null && !pythonArgs.isBlank()) cmd.add(pythonArgs.trim());
        cmd.add("-m");
        cmd.add("engine.cli");
        cmd.add(cli);
        cmd.add("--run-id");
        cmd.add(String.valueOf(runId));
        Object asof = opts.get("asof");
        if (asof != null && !String.valueOf(asof).isBlank() && !"latest".equals(asof)) {
            cmd.add("--asof");
            cmd.add(String.valueOf(asof));
        }
        Object limit = opts.get("limitStocks");
        if (limit != null && !String.valueOf(limit).isBlank()
                && !"0".equals(String.valueOf(limit))) {
            cmd.add("--limit-stocks");
            cmd.add(String.valueOf(limit));
        }
        if (Boolean.TRUE.equals(opts.get("force"))) cmd.add("--force");
        if (Boolean.TRUE.equals(opts.get("noResume"))) cmd.add("--no-resume");
        if (Boolean.TRUE.equals(opts.get("fast"))) cmd.add("--fast");

        log.info("[PROB] 启动引擎任务 runId={} cmd={}", runId, cmd);
        StringBuilder tail = new StringBuilder();
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(workdir));
            pb.redirectErrorStream(true);
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            Process proc = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    tail.append(line).append('\n');
                    if (tail.length() > 8000) tail.delete(0, tail.length() - 8000);
                }
            }
            boolean done = proc.waitFor(60, TimeUnit.MINUTES);
            if (!done) {
                proc.destroyForcibly();
                finish(runId, "FAILED", "任务超过 60 分钟被终止");
                return;
            }
            int exit = proc.exitValue();
            String msg = tail.length() > 1500 ? tail.substring(tail.length() - 1500) : tail.toString();
            if (exit != 0) {
                finish(runId, "FAILED", "引擎退出码 " + exit + "：" + msg);
            } else {
                // 引擎正常退出时已写好 SUCCESS/FAILED，这里只在仍是 RUNNING 时兜底
                jdbc.update("UPDATE prob_run SET status='SUCCESS',finished_at=NOW(),"
                                + "message=CONCAT(IFNULL(message,''),' | 后端已确认完成') "
                                + "WHERE run_id=? AND status='RUNNING'", runId);
            }
            log.info("[PROB] 引擎任务 runId={} 结束 exit={}", runId, exit);
        } catch (Exception e) {
            log.warn("[PROB] 引擎任务 runId={} 启动失败：{}", runId, e.toString());
            finish(runId, "FAILED", "无法启动 Python 引擎：" + e.getMessage()
                    + "。请检查 stock.prob.python / workdir 配置");
        } finally {
            running.decrementAndGet();
            // 任务结束后立刻失效总览缓存，页面下一次轮询就能看到新数据
            repo.invalidateOverview();
        }
    }

    private void finish(long runId, String status, String message) {
        try {
            jdbc.update("UPDATE prob_run SET status=?,finished_at=NOW(),message=? WHERE run_id=?",
                    status, message == null ? "" : message.substring(0, Math.min(message.length(), 1800)), runId);
        } catch (Exception e) {
            log.warn("[PROB] 更新 prob_run 状态失败：{}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
    }
}
