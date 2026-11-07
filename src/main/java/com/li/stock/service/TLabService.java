package com.li.stock.service;

import com.li.stock.common.BizException;
import com.li.stock.domain.Bar;
import com.li.stock.domain.DailyPoint;
import com.li.stock.domain.Indicator;
import com.li.stock.domain.Params;
import com.li.stock.domain.SimOutcome;
import com.li.stock.domain.TradeRecord;
import com.li.stock.domain.vo.ParamItemVO;
import com.li.stock.domain.vo.PlanRequest;
import com.li.stock.domain.vo.PlanResultVO;
import com.li.stock.domain.vo.ScanRequest;
import com.li.stock.domain.vo.ScanResultVO;
import com.li.stock.domain.vo.SimulateRequest;
import com.li.stock.domain.vo.SimulateResultVO;
import com.li.stock.tlab.engine.Backtester;
import com.li.stock.tlab.engine.EntryScanner;
import com.li.stock.tlab.engine.FeeCalculator;
import com.li.stock.tlab.engine.NextDayPlanner;
import com.li.stock.service.indicator.IndicatorService;
import com.li.stock.repository.TLabDailyRepository;
import com.li.stock.repository.TLabTaskRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 三个功能的编排层：参数校验 → 读取分表日线 → 指标 → 引擎 → 组装 VO → 可选落库。
 * <p>与项目现有 Service 风格一致：构造器注入、BizException 抛业务错误、
 * 金额对外 BigDecimal 保留两位小数。</p>
 */
@Service
public class TLabService {

    /** 指标预热至少需要的 K 线根数，规格第三节。 */
    private static final int WARMUP_BARS = 21;
    private static final int MAX_BARS = 1200;

    private final TLabDailyRepository dailyRepo;
    private final TLabTaskRepository taskRepo;
    private final IndicatorService indicatorService;
    private final Backtester backtester;
    private final EntryScanner scanner;
    private final NextDayPlanner planner;
    private final FeeCalculator fees;

    public TLabService(TLabDailyRepository dailyRepo, TLabTaskRepository taskRepo,
                       IndicatorService indicatorService, Backtester backtester,
                       EntryScanner scanner, NextDayPlanner planner, FeeCalculator fees) {
        this.dailyRepo = dailyRepo;
        this.taskRepo = taskRepo;
        this.indicatorService = indicatorService;
        this.backtester = backtester;
        this.scanner = scanner;
        this.planner = planner;
        this.fees = fees;
    }

    /** GET /api/t/params/default：默认参数及说明，前端据此渲染高级设置。 */
    public List<ParamItemVO> defaultParams() {
        List<ParamItemVO> out = new ArrayList<>();
        add(out, "commissionRate", "佣金费率", "费用", 0.00025, "%", "双边，按成交额收取，可含最低佣金", 0, 0.01, "rate");
        add(out, "minCommission", "最低佣金", "费用", 5, "元", "单笔佣金不足该值时按该值收取", 0, 100, "money");
        add(out, "stampTaxRate", "印花税率", "费用", 0.0005, "%", "仅卖出单边收取（2023-08-28 起减半）", 0, 0.01, "rate");
        add(out, "transferFeeRate", "过户费率", "费用", 0.00001, "%", "双边", 0, 0.01, "rate");
        add(out, "otherFeeRate", "经手费+证管费", "费用", 0.00005, "%", "双边合计", 0, 0.01, "rate");
        add(out, "slippageBp", "滑点", "费用", 1, "bp", "仅市价类成交，1bp=0.01%", 0, 100, "number");
        add(out, "maxPositionRatio", "仓位上限", "补仓", 0.6, "%", "持仓市值占总资金上限，只限制补仓", 0.05, 1, "rate");
        add(out, "tReserveRatio", "做T预留现金", "补仓", 0.3, "%", "可支配资金中留给做T、补仓不动用的比例", 0, 0.9, "rate");
        add(out, "addSteps", "补仓跌幅档位", "补仓", 0.05, "%", "相对摊薄成本的三档跌幅，默认 5/10/15%", 0.001, 0.9, "list");
        add(out, "addRatios", "补仓预算分配", "补仓", 0.3, "%", "各档动用的补仓预算比例，默认 0.3/0.3/0.4", 0, 1, "list");
        add(out, "maxAddTimes", "最大补仓次数", "补仓", 3, "次", "达到后不再补仓", 0, 10, "number");
        add(out, "addMinIntervalDays", "补仓间隔", "补仓", 2, "日", "两次补仓之间至少间隔的交易日数", 0, 60, "number");
        add(out, "tRatio", "单次做T仓位", "做T", 0.5, "%", "单次做T数量占可卖底仓比例上限", 0.05, 1, "rate");
        add(out, "kAtr", "价带ATR倍数", "做T", 0.4, "倍", "band = kAtr × ATR%", 0, 3, "number");
        add(out, "bandMax", "价带上限", "做T", 0.05, "%", "band 的最大值", 0.001, 0.2, "rate");
        add(out, "minNetProfitRate", "最低净利率", "做T", 0.003, "%", "单次往返净利润下限（占价格）", 0, 0.1, "rate");
        add(out, "minTNetProfitYuan", "最低净利润", "做T", 20, "元", "单次往返净利润下限（元）", 0, 10000, "money");
        add(out, "liquidityParticipation", "流动性参与率", "做T", 0.01, "%", "单笔数量不超过当日成交额的比例", 0.0001, 1, "rate");
        add(out, "maxCarryDays", "遗留头寸期限", "做T", 3, "日", "超过后按 carryPolicy 处理", 1, 60, "number");
        add(out, "targetProfitRate", "达标收益率", "离场", 0, "%", "0 表示回本即走", -0.5, 5, "rate");
        add(out, "maxLossRate", "最大亏损率", "离场", 0.2, "%", "相对最大占用资金的止损线", 0.001, 1, "rate");
        add(out, "maxHoldDays", "最长持有天数", "离场", 60, "日", "超过后时间止损", 1, 500, "number");
        add(out, "holdDays", "扫描持有天数", "扫描", 20, "日", "每个买入日窗口的回放长度", 2, 120, "number");
        add(out, "initPositionRatio", "扫描初始仓位", "扫描", 0.3, "%", "初始仓位占总资金比例", 0.01, 1, "rate");
        add(out, "adjustFlag", "复权口径", "数据", 2, "", "1 后复权 / 2 前复权 / 3 不复权", 1, 3, "number");
        return out;
    }

    public SimulateResultVO simulate(SimulateRequest request) {
        if (request == null) throw BizException.badRequest("请求体不能为空");
        String code = resolveCode(request.code());
        String market = resolveMarket(code, request.market());
        Params p = Params.from(request.params());
        double capital = positive(request.totalCapital(), "总资金");
        double cash = positive(request.availableCash(), "可支配资金");
        if (cash > capital) throw BizException.badRequest("availableCash 不能大于 totalCapital");
        if (request.buyPrice() != null && request.buyPrice() <= 0) {
            throw BizException.badRequest("买入价必须是正数");
        }
        if (request.buyDate() != null && request.endDate() != null && request.endDate().isBefore(request.buyDate())) {
            throw BizException.badRequest("结束日期不能早于买入日期");
        }

        List<Bar> bars = loadBars(code, p.adjustFlag, null, request.endDate());
        List<Indicator> indicators = indicatorService.compute(bars);
        List<String> warnings = new ArrayList<>();
        int start = firstIndexAtOrAfter(bars, request.buyDate(), WARMUP_BARS - 1);
        if (start < WARMUP_BARS - 1) {
            warnings.add("历史数据不足：买入日早于第 21 个交易日，指标预热不完整，结果仅供参考");
        }
        int end = request.endDate() == null ? bars.size() - 1 : lastIndexAtOrBefore(bars, request.endDate());
        end = Math.max(start, Math.min(end, start + MAX_BARS));

        double buyPrice = request.buyPrice() == null ? bars.get(start).close() : request.buyPrice();
        Double initAmount = request.initAmount();
        Integer initQty = request.initQty();
        if (initQty == null && initAmount == null) initAmount = capital * p.initPositionRatio;
        double plannedCost = initAmount != null ? initAmount : (initQty != null ? initQty * buyPrice : 0);
        if (plannedCost + fees.fee(plannedCost, false, p) > capital) {
            throw BizException.badRequest("初始买入金额加上手续费不得超过 totalCapital");
        }

        SimOutcome full = backtester.run(bars, indicators, start, end, p,
                request.buyPrice(), initQty, initAmount, capital, cash, Backtester.Mode.FULL);
        SimOutcome onlyAdd = backtester.run(bars, indicators, start, end, p,
                request.buyPrice(), initQty, initAmount, capital, cash, Backtester.Mode.ONLY_ADD);
        SimOutcome buyHold = backtester.run(bars, indicators, start, end, p,
                request.buyPrice(), initQty, initAmount, capital, cash, Backtester.Mode.BUY_HOLD);

        warnings.add("默认使用前复权（adjustflag=2），区间内如有分红送转，前复权价与真实成交价存在偏差，v1 忽略该差异");
        warnings.add("仅日线 OHLC，不含分时路径；限价单假设按限价足量成交，实际成交率会低于回测");
        if (full.initQty() <= 0) {
            double lotCost = 100 * full.buyPrice() * (1 + p.commissionRate + p.transferFeeRate + p.otherFeeRate)
                    + p.minCommission;
            warnings.add(String.format("本次未建仓：初始买入金额 %.2f 元不足买入 100 股整手（约需 %.2f 元）。"
                            + "按规格「不足 100 股不操作」，请调高 initAmount / initQty 或改用价格更低的股票",
                    plannedCost, lotCost));
        }
        if (p.targetProfitRate <= 0 && full.state().closed && full.daily().size() <= 3) {
            warnings.add("当前达标收益率为 0（回本即走），策略在建仓后很快离场，补仓 / 做T来不及触发；"
                    + "如需观察完整过程，可在高级参数中调高「达标收益率」或缩短「最长持有天数」");
        }

        Map<String, Object> summary = buildSummary(full, onlyAdd, buyHold, capital, cash, bars, end);
        List<Map<String, Object>> daily = dailyMaps(full.daily());
        List<Map<String, Object>> trades = tradeMaps(full.trades());
        Map<String, Object> benchmarks = buildBenchmarks(full, onlyAdd, buyHold);
        List<Map<String, Object>> kline = klineMaps(bars, indicators, full.daily(), start, end);

        Long taskId = taskRepo.createTask("SIMULATE", code, market, bars.get(start).date(),
                full.buyPrice(), full.initQty(), money(capital), money(cash), p, summary);
        if (taskId == null) {
            warnings.add("t_sim_* 表尚未创建，本次结果未持久化；可执行 t_lab_schema.sql 后重试");
        } else {
            taskRepo.saveDaily(taskId, full.daily());
            taskRepo.saveTrades(taskId, full.trades());
        }

        return new SimulateResultVO(taskId, code, market, dailyRepo.nameOf(code),
                bars.get(start).date(), full.daily().isEmpty() ? bars.get(start).date() : full.daily().get(full.daily().size() - 1).tradeDate(),
                p.adjustFlag, money(full.buyPrice()), full.initQty(), money(capital), money(cash),
                summary, daily, trades, benchmarks, kline, warnings);
    }

    public ScanResultVO scanEntry(ScanRequest request) {
        if (request == null) throw BizException.badRequest("请求体不能为空");
        String code = resolveCode(request.code());
        String market = resolveMarket(code, request.market());
        Params p = Params.from(request.params());
        if (request.holdDays() != null) p.holdDays = Math.max(2, Math.min(120, request.holdDays()));
        if (request.initPositionRatio() != null) {
            p.initPositionRatio = Math.max(0.01, Math.min(1, request.initPositionRatio()));
        }
        double capital = positive(request.totalCapital(), "总资金");
        double cash = positive(request.availableCash(), "可支配资金");
        if (cash > capital) throw BizException.badRequest("availableCash 不能大于 totalCapital");

        List<Bar> bars = loadBars(code, p.adjustFlag, null, null);
        List<Indicator> indicators = indicatorService.compute(bars);
        int fromIndex = request.fromDate() == null ? 0 : firstIndexAtOrAfter(bars, request.fromDate(), 0);
        EntryScanner.ScanBundle bundle = scanner.scan(bars, indicators, fromIndex, p, capital, cash);

        List<String> warnings = new ArrayList<>(bundle.warnings());
        warnings.add("样本仅来自本股票约 " + bars.size() + " 根日线，参数与买点结论容易过拟合；调优请只在训练段做，再到后段验证");
        boolean allFlat = !bundle.rows().isEmpty() && bundle.rows().stream().allMatch(row ->
                Math.abs(((Number) row.getOrDefault("feeTotal", 0)).doubleValue()) < 1e-9);
        if (allFlat) {
            warnings.add("所有买入日窗口均未产生成交（feeTotal 全为 0），通常是初始仓位预算"
                    + " totalCapital × initPositionRatio 不足买入 100 股整手；"
                    + "请调高资金 / initPositionRatio，或改用价格更低的股票");
        }
        Long taskId = taskRepo.createTask("SCAN", code, market, null, null, null,
                money(capital), money(cash), p, summaryOfScan(bundle));
        if (taskId == null) warnings.add("t_sim_* 表尚未创建，本次结果未持久化");
        else taskRepo.saveScanRows(taskId, bundle.rows());

        LocalDate rangeStart = bundle.rows().isEmpty() ? null : (LocalDate) bundle.rows().get(0).get("buyDate");
        LocalDate rangeEnd = bundle.rows().isEmpty() ? null : (LocalDate) bundle.rows().get(bundle.rows().size() - 1).get("buyDate");
        return new ScanResultVO(taskId, code, market, dailyRepo.nameOf(code), p.holdDays,
                bundle.rows().size(), rangeStart, rangeEnd, bundle.rows(), bundle.featureBuckets(),
                bundle.recommendations(), bundle.currentSignal(), bundle.stability(), warnings);
    }

    public PlanResultVO nextPlan(PlanRequest request) {
        if (request == null) throw BizException.badRequest("请求体不能为空");
        String code = resolveCode(request.code());
        String market = resolveMarket(code, request.market());
        Params p = Params.from(request.params());
        int qty = request.qty() == null ? 0 : Math.max(0, request.qty());
        int sellable = request.sellableQty() == null ? qty : Math.max(0, request.sellableQty());
        double avgCost = request.avgCost() == null ? 0 : request.avgCost();
        double cash = request.cash() == null ? 0 : Math.max(0, request.cash().doubleValue());
        double capital = request.totalCapital() == null ? cash + qty * avgCost : request.totalCapital().doubleValue();
        if (avgCost < 0) throw BizException.badRequest("持仓成本价不能为负");
        if (capital <= 0) throw BizException.badRequest("totalCapital 必须是正数");

        LocalDate asOf = request.asOfDate();
        List<Bar> bars = loadBars(code, p.adjustFlag, null, asOf);
        List<Indicator> indicators = indicatorService.compute(bars);
        List<String> warnings = new ArrayList<>();
        if (bars.size() < WARMUP_BARS) {
            warnings.add("历史数据不足：少于 21 根K线，指标不完整，计划仅供参考");
        }
        LocalDate effectiveDate = bars.get(bars.size() - 1).date();
        NextDayPlanner.PlanBundle bundle = planner.plan(bars, indicators, effectiveDate,
                qty, sellable, avgCost, cash, capital, p);
        warnings.addAll(bundle.warnings());

        Long taskId = taskRepo.createTask("PLAN", code, market, effectiveDate, null, qty,
                money(capital), money(cash), p, bundle.indicators());
        if (taskId == null) warnings.add("t_sim_* 表尚未创建，本次结果未持久化");
        return new PlanResultVO(taskId, code, market, dailyRepo.nameOf(code), effectiveDate,
                bundle.orders(), bundle.explanations(), bundle.indicators(), bundle.levels(), warnings);
    }

    /** GET /api/t/tasks/{id}：表未创建或任务不存在时返回明确的业务错误。 */
    public Map<String, Object> loadTask(long id) {
        Map<String, Object> task = taskRepo.loadTask(id);
        if (task == null) throw BizException.notFound("历史任务 " + id + " 不存在，或 t_sim_* 表尚未创建");
        return task;
    }

    // ---------------------------------------------------------------- 组装

    private Map<String, Object> buildSummary(SimOutcome full, SimOutcome onlyAdd, SimOutcome buyHold,
                                             double capital, double cash, List<Bar> bars, int end) {
        Map<String, Object> m = new LinkedHashMap<>();
        double lastClose = full.daily().isEmpty() ? bars.get(end).close() : full.daily().get(full.daily().size() - 1).close();
        double pnl = full.state().totalPnL(lastClose);
        double maxInvested = Math.max(full.state().maxInvested, 0d);
        m.put("totalPnl", money(pnl));
        m.put("pnlPctOnInvested", pct(maxInvested > 0 ? pnl / maxInvested : 0));
        m.put("pnlPctOnCapital", pct(capital > 0 ? pnl / capital : 0));
        m.put("maxDrawdown", pct(full.maxDrawdown()));
        m.put("recoverDate", full.recoverDate());
        m.put("recoverDays", full.recoverDays());
        m.put("holdDays", full.holdDays());
        m.put("maxInvested", money(maxInvested));
        m.put("capitalUtilization", pct(capital > 0 ? maxInvested / capital : 0));
        m.put("addCount", full.state().addCount);
        m.put("addAmount", money(full.state().addAmount));
        m.put("tRoundTrips", full.state().tRoundTrips);
        m.put("tPendingCount", full.state().tPendingCount);
        m.put("tNetProfit", money(full.state().tNetProfit));
        m.put("tWinRate", pct(full.state().tRoundTrips > 0
                ? (double) full.state().tWinCount / full.state().tRoundTrips : 0));
        m.put("feeTotal", money(full.state().feeTotal));
        m.put("finalCash", money(full.state().cash));
        m.put("finalQty", full.state().qty);
        m.put("finalEquity", money(full.state().equity(lastClose)));
        m.put("dilutedCost", money(full.state().dilutedCost()));
        m.put("status", full.state().closed ? "已离场" : (full.state().qty > 0 ? "持仓中" : "未建仓"));
        m.put("vsOnlyAdd", pct(full.finalPnlPct() - onlyAdd.finalPnlPct()));
        m.put("vsBuyHold", pct(full.finalPnlPct() - buyHold.finalPnlPct()));
        return m;
    }

    private Map<String, Object> buildBenchmarks(SimOutcome full, SimOutcome onlyAdd, SimOutcome buyHold) {
        Map<String, Object> m = new LinkedHashMap<>();
        double base = full.equityCurve().isEmpty() ? 0 : full.equityCurve().get(0);
        m.put("strategyPct", pct(full.finalPnlPct()));
        m.put("onlyAddPct", pct(onlyAdd.finalPnlPct()));
        m.put("buyHoldPct", pct(buyHold.finalPnlPct()));
        List<Map<String, Object>> curves = new ArrayList<>();
        int n = Math.min(full.equityCurve().size(),
                Math.min(onlyAdd.equityCurve().size(), buyHold.equityCurve().size()));
        for (int i = 0; i < n; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", i);
            row.put("date", i < full.daily().size() ? full.daily().get(i).tradeDate() : null);
            row.put("strategy", money(full.equityCurve().get(i)));
            row.put("onlyAdd", money(onlyAdd.equityCurve().get(i)));
            row.put("buyHold", money(buyHold.equityCurve().get(i)));
            curves.add(row);
        }
        m.put("curves", curves);
        m.put("baseEquity", money(base));
        return m;
    }

    private List<Map<String, Object>> dailyMaps(List<DailyPoint> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DailyPoint d : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tradeDate", d.tradeDate());
            m.put("close", money(d.close()));
            m.put("qty", d.qty());
            m.put("sellableQty", d.sellableQty());
            m.put("cash", money(d.cash()));
            m.put("equity", money(d.equity()));
            m.put("dilutedCost", money(d.dilutedCost()));
            m.put("totalPnl", money(d.totalPnl()));
            m.put("events", d.events());
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> tradeMaps(List<TradeRecord> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TradeRecord t : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tradeDate", t.tradeDate());
            m.put("action", t.action());
            m.put("price", money(t.price()));
            m.put("qty", t.qty());
            m.put("amount", money(t.amount()));
            m.put("fee", money(t.fee()));
            m.put("realizedPnl", t.realizedPnl() == null ? null : money(t.realizedPnl()));
            m.put("note", t.note());
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> klineMaps(List<Bar> bars, List<Indicator> indicators,
                                                 List<DailyPoint> daily, int start, int end) {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<LocalDate, String> events = new LinkedHashMap<>();
        Map<LocalDate, Double> diluted = new LinkedHashMap<>();
        for (DailyPoint d : daily) {
            events.put(d.tradeDate(), d.events());
            // 清仓 / 未建仓时摊薄成本为 0，不能当作真实成本画进曲线（否则会把 Y 轴下限拉到 0）
            if (d.qty() > 0 && d.dilutedCost() > 0) diluted.put(d.tradeDate(), d.dilutedCost());
        }
        for (int i = start; i <= Math.min(end, bars.size() - 1); i++) {
            Bar b = bars.get(i);
            Indicator ind = indicators.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tradeDate", b.date());
            m.put("open", money(b.open()));
            m.put("high", money(b.high()));
            m.put("low", money(b.low()));
            m.put("close", money(b.close()));
            m.put("volume", money(b.volume()));
            m.put("ma5", money(ind.ma5()));
            m.put("ma10", money(ind.ma10()));
            m.put("ma20", money(ind.ma20()));
            Double dc = diluted.get(b.date());
            m.put("dilutedCost", dc == null ? null : money(dc));
            m.put("events", events.getOrDefault(b.date(), ""));
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> summaryOfScan(EntryScanner.ScanBundle b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("windowCount", b.rows().size());
        m.put("recommendations", b.recommendations().size());
        m.put("stability", b.stability());
        return m;
    }

    // ---------------------------------------------------------------- 通用

    private List<Bar> loadBars(String code, int adjustFlag, LocalDate from, LocalDate to) {
        if (!dailyRepo.exists(code, adjustFlag)) {
            throw BizException.notFound("股票 " + code + " 在 adjustflag=" + adjustFlag
                    + " 口径下没有日线数据，请检查代码或复权口径");
        }
        List<Bar> bars = dailyRepo.listAsc(code, from, to, adjustFlag);
        if (bars.isEmpty()) throw BizException.notFound("所选区间没有日线数据");
        return bars.size() > MAX_BARS ? new ArrayList<>(bars.subList(bars.size() - MAX_BARS, bars.size())) : bars;
    }

    private static String resolveCode(String raw) {
        String code = raw == null ? "" : raw.trim();
        if (!code.matches("\\d{6}")) {
            throw BizException.badRequest("股票代码必须是 6 位数字，当前为：" + (code.isEmpty() ? "空" : code));
        }
        if (!(code.startsWith("00") || code.startsWith("30") || code.startsWith("60"))) {
            throw BizException.badRequest("仅支持 00 / 30 / 60 开头的 A 股代码：" + code);
        }
        return code;
    }

    /** 规格第三节：6 开头 SH，0 或 3 开头 SZ。 */
    private static String resolveMarket(String code, String market) {
        if (market != null && !market.isBlank()) return market.trim().toUpperCase();
        return code.startsWith("6") ? "SH" : "SZ";
    }

    private static double positive(BigDecimal value, String label) {
        if (value == null || value.signum() <= 0) {
            throw BizException.badRequest(label + "必须是正数");
        }
        return value.doubleValue();
    }

    private static int firstIndexAtOrAfter(List<Bar> bars, LocalDate date, int fallback) {
        if (date == null) return Math.min(fallback, bars.size() - 1);
        for (int i = 0; i < bars.size(); i++) {
            if (!bars.get(i).date().isBefore(date)) return i;
        }
        return Math.max(0, bars.size() - 1);
    }

    private static int lastIndexAtOrBefore(List<Bar> bars, LocalDate date) {
        for (int i = bars.size() - 1; i >= 0; i--) {
            if (!bars.get(i).date().isAfter(date)) return i;
        }
        return 0;
    }

    private static void add(List<ParamItemVO> out, String key, String label, String group,
                            double value, String unit, String tip, double min, double max, String type) {
        out.add(new ParamItemVO(key, label, group, BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP),
                unit, tip, BigDecimal.valueOf(min), BigDecimal.valueOf(max), type));
    }

    private static BigDecimal money(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal pct(double ratio) {
        return BigDecimal.valueOf(ratio * 100).setScale(2, RoundingMode.HALF_UP);
    }
}
