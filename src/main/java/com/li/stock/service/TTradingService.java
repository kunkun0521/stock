package com.li.stock.service;

import com.li.stock.common.BizException;
import com.li.stock.common.QuerySupport;
import com.li.stock.domain.model.TBar;
import com.li.stock.domain.model.TPositionState;
import com.li.stock.domain.model.TTradeAction;
import com.li.stock.domain.model.TTradingConfig;
import com.li.stock.domain.query.TTradingRequest;
import com.li.stock.domain.vo.StockBriefVO;
import com.li.stock.domain.vo.StockDailyVO;
import com.li.stock.domain.vo.StockInfoVO;
import com.li.stock.domain.vo.TAdviceVO;
import com.li.stock.domain.vo.TDailyPointVO;
import com.li.stock.domain.vo.TStrategyConfigVO;
import com.li.stock.domain.vo.TTradeRecordVO;
import com.li.stock.domain.vo.TTradingResultVO;
import com.li.stock.repository.StockDailyRepository;
import com.li.stock.repository.StockInfoRepository;
import com.li.stock.util.Numbers;
import com.li.stock.util.TIndicatorUtil;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 做T + 成本摊薄 + 回本盈利模拟服务。
 * <p>数据源复用 {@link StockDailyRepository} 的分表路由（stock_daily_00/30/60），
 * 本模块只读不写库；所有计算在内存完成，不产生任何持仓或交易记录。
 * 结果仅为基于历史行情的规则回放，不构成投资建议。</p>
 */
@Service
public class TTradingService {

    /** 单次模拟最多回放的交易日，防止超长区间拖慢接口。 */
    private static final int MAX_TRADING_DAYS = 1200;
    /** 指标预热窗口（自然日），保证买入首日就有完整的 MA20 / 20 日支撑压力。 */
    private static final int WARMUP_DAYS = 75;

    private static final int PRICE_SCALE = 3;
    private static final int MONEY_SCALE = 2;
    private static final int PCT_SCALE = 2;

    private final StockDailyRepository stockDailyRepository;
    private final StockInfoRepository stockInfoRepository;
    private final TTradingSimulator simulator;

    public TTradingService(StockDailyRepository stockDailyRepository,
                           StockInfoRepository stockInfoRepository,
                           TTradingSimulator simulator) {
        this.stockDailyRepository = stockDailyRepository;
        this.stockInfoRepository = stockInfoRepository;
        this.simulator = simulator;
    }

    /** 页面加载时读取的默认策略参数，避免前后端各维护一份默认值。 */
    public TStrategyConfigVO defaults() {
        return configVO(TTradingConfig.defaults(), "CLOSE");
    }

    public TTradingResultVO simulate(TTradingRequest request) {
        if (request == null) {
            throw BizException.badRequest("请求体不能为空");
        }
        String code = resolveCode(request.code());
        String market = resolveMarket(code, request.market());
        int adjustFlag = normalizeAdjustFlag(request.adjustFlag());
        String priceMode = "INTRADAY".equalsIgnoreCase(QuerySupport.trimToNull(request.priceMode()))
                ? "INTRADAY" : "CLOSE";
        boolean intraday = "INTRADAY".equals(priceMode);

        double availableCash = positive(request.availableCash(), "可支配资金");
        int initialShares = request.initialShares() == null ? 0 : Math.max(0, request.initialShares());
        double initialAvgCost = request.initialAvgCost() == null ? 0d : request.initialAvgCost().doubleValue();
        if (initialShares > 0 && initialAvgCost <= 0) {
            throw BizException.badRequest("填写已有底仓股数时，必须同时填写大于 0 的底仓成本价");
        }
        if (initialShares == 0) {
            initialAvgCost = 0d;
        }
        double initialAssets = availableCash + initialShares * initialAvgCost;
        double totalCapital = request.totalCapital() == null
                ? initialAssets
                : Math.max(request.totalCapital().doubleValue(), initialAssets);
        if (totalCapital <= 0 || !Double.isFinite(totalCapital)) {
            throw BizException.badRequest("总资金必须是正数");
        }

        LocalDate buyDate = request.buyDate();
        LocalDate endDate = request.endDate();
        if (buyDate != null && endDate != null && endDate.isBefore(buyDate)) {
            throw BizException.badRequest("结束日期不能早于初始买入日期");
        }
        LocalDate warmupFrom = buyDate == null ? null : buyDate.minusDays(WARMUP_DAYS);

        List<StockDailyVO> rows = stockDailyRepository.listAsc(code, warmupFrom, endDate, adjustFlag);
        if (rows.isEmpty()) {
            throw BizException.notFound("股票 " + code + " 在所选区间没有日线数据，请检查代码、日期或复权口径");
        }
        List<TBar> allBars = TIndicatorUtil.toBars(rows);
        List<TBar> bars = buyDate == null
                ? tail(allBars, MAX_TRADING_DAYS)
                : head(allBars.stream()
                        .filter(bar -> !bar.tradeDate().isBefore(buyDate))
                        .toList(), MAX_TRADING_DAYS);
        if (bars.isEmpty()) {
            throw BizException.notFound("初始买入日期之后没有行情数据，请换一个更早的日期");
        }

        TTradingConfig cfg = TTradingConfig.of(request.strategy());
        TTradingSimulator.SimulationResult result = simulator.run(
                bars, totalCapital, availableCash, initialShares, initialAvgCost, cfg, intraday);
        return assemble(code, market, adjustFlag, priceMode, request, cfg, result);
    }

    private TTradingResultVO assemble(String code, String market, int adjustFlag, String priceMode,
                                      TTradingRequest request, TTradingConfig cfg,
                                      TTradingSimulator.SimulationResult result) {
        TPositionState state = result.state();
        TBar last = result.lastBar();
        double close = last.close();
        double diluted = state.dilutedCost();
        double baseCost = baseCost(state);

        String name = stockInfoRepository.findByCode(code).map(StockInfoVO::name).orElse(null);
        String status = state.position() > 0 ? "持仓中"
                : (state.tradeCount() > 0 ? "已离场" : "未建仓");
        // 摊薄幅度对比账面成本：两者都是当前这批持仓的口径，
        // 差值正好等于「累计做T价差 ÷ 剩余股数」，能直接量化成本摊薄效果。
        double costReductionPct = state.avgCost() > 0 && state.position() > 0
                ? (1 - diluted / state.avgCost()) * 100d : 0d;

        List<TTradeRecordVO> trades = state.trades().stream()
                .map(t -> new TTradeRecordVO(t.tradeDate(), t.action(), t.actionLabel(),
                        round(t.price(), PRICE_SCALE), t.shares(),
                        round(t.amount(), MONEY_SCALE), round(t.fee(), MONEY_SCALE),
                        round(t.cashAfter(), MONEY_SCALE), t.positionAfter(),
                        round(t.avgCostAfter(), PRICE_SCALE),
                        round(t.dilutedCostAfter(), PRICE_SCALE),
                        round(t.realizedPnlAfter(), MONEY_SCALE), t.reason()))
                .toList();

        List<TDailyPointVO> equity = state.equity().stream()
                .map(p -> new TDailyPointVO(p.tradeDate(), round(p.close(), PRICE_SCALE),
                        round(p.ma20(), PRICE_SCALE), round(p.avgCost(), PRICE_SCALE),
                        round(p.dilutedCost(), PRICE_SCALE), round(p.cash(), MONEY_SCALE),
                        p.position(), round(p.totalAssets(), MONEY_SCALE),
                        round(p.drawdownPct(), PCT_SCALE), p.actionLabel()))
                .toList();

        String costText = state.position() > 0
                ? String.format("摊薄成本 %.3f（账面成本 %.3f，做T摊薄 %.2f%%；本期建仓价 %.3f）",
                        diluted, state.avgCost(), costReductionPct, baseCost)
                : String.format("已清仓（本期建仓价 %.3f，累计已实现盈亏 %.2f 元）",
                        baseCost, state.realizedPnl());
        String summary = String.format(
                "回放 %d 个交易日，成交 %d 笔（补仓 %d、正T买回 %d、倒T减仓 %d）；%s；总收益 %+.2f%%，当前%s。",
                equity.size(), state.tradeCount(), state.addCount(), state.tBuybackCount(),
                state.reverseReduceCount(), costText,
                state.totalReturnPct(close) * 100d, status);

        return new TTradingResultVO(
                code, market, name,
                equity.isEmpty() ? null : equity.get(0).tradeDate(),
                last.tradeDate(), equity.size(), adjustFlag,
                round(state.totalCapital(), MONEY_SCALE),
                round(state.initialAssets(), MONEY_SCALE),
                round(state.availableCash(), MONEY_SCALE),
                state.position(),
                baseCost > 0 ? round(baseCost, PRICE_SCALE) : null,
                state.position() > 0 ? round(state.avgCost(), PRICE_SCALE) : null,
                state.position() > 0 ? round(diluted, PRICE_SCALE) : null,
                round(costReductionPct, PCT_SCALE),
                round(close, PRICE_SCALE),
                round(state.marketValue(close), MONEY_SCALE),
                round(state.totalAssets(close), MONEY_SCALE),
                round(state.realizedPnl(), MONEY_SCALE),
                round(state.floatingPnl(close), MONEY_SCALE),
                round(state.totalPnl(close), MONEY_SCALE),
                round(state.totalReturnPct(close) * 100d, PCT_SCALE),
                round(result.maxDrawdownPct(), PCT_SCALE),
                round(state.feeTotal(), MONEY_SCALE),
                state.tradeCount(), state.addCount(), state.tBuybackCount(),
                state.reverseReduceCount(), status, summary,
                trades, equity, buildAdvices(state, last, cfg), configVO(cfg, priceMode));
    }

    /**
     * 下一步操作建议：全部是「条件单」口径，价格触及 triggerPrice 才执行。
     * 已挂的做T闭环优先，其次给出补仓/高抛/止盈/止损/回本各个关键价位。
     */
    private List<TAdviceVO> buildAdvices(TPositionState state, TBar last, TTradingConfig cfg) {
        List<TAdviceVO> list = new ArrayList<>();
        double close = last.close();
        int position = state.position();
        int sellable = state.sellable();
        double avgCost = state.avgCost();
        double netInvested = state.netInvested();

        if (position == 0) {
            double ref = Double.isFinite(last.ma20()) && last.ma20() > 0 ? last.ma20() : close;
            int shares = TTradingStrategyEngine.lots(
                    state.availableCash() * cfg.initialPositionRatio() / Math.max(ref, 0.01));
            if (shares < 100) {
                shares = TTradingStrategyEngine.lots(state.availableCash() / Math.max(ref, 0.01));
            }
            advice(list, TTradeAction.BUY, shares, ref, close, state, cfg,
                    String.format("当前空仓，价格站上 MA20（参考价）且换手达标时可动用可用资金 %.0f%% 重新建仓（留出现金做T）",
                            cfg.initialPositionRatio() * 100),
                    10, "重新建仓会重新承担全部价格波动风险，需先确认趋势与资金安排");
            return list;
        }

        // 已挂的闭环：优先级最高
        if (state.pendingTSellShares() > 0) {
            double trigger = state.pendingTSellPrice() * (1 - cfg.tBuybackDrop());
            int affordable = TTradingStrategyEngine.lots(state.availableCash() / Math.max(trigger, 0.01));
            int shares = TTradingStrategyEngine.lots(Math.min(state.pendingTSellShares(), affordable));
            advice(list, TTradeAction.BUY_T, shares, trigger, close, state, cfg,
                    String.format("正T高抛 %.3f 后尚有 %d 股待回补，回落至该价买回即可锁定价差、继续摊薄成本（已等待 %d 个交易日，满 %d 日将按市价回补）",
                            state.pendingTSellPrice(), state.pendingTSellShares(),
                            state.pendingTSellDays(), cfg.tMaxHoldDays()),
                    100, "若股价不回落而持续上涨，超时回补会抬高持仓成本，存在踏空风险");
        }
        if (state.pendingAddShares() > 0 && sellable > 0) {
            double trigger = state.pendingAddPrice() * (1 + cfg.reverseReboundPct());
            int shares = Math.min(state.pendingAddShares(), sellable);
            advice(list, TTradeAction.REVERSE_T_REDUCE, shares, trigger, close, state, cfg,
                    String.format("补仓价 %.3f 反弹 %.2f%% 后，减掉补仓的 %d 股完成倒T低吸高抛",
                            state.pendingAddPrice(), cfg.reverseReboundPct() * 100, shares),
                    95, "反弹不到位则补仓仓位继续持有，需承受回撤与仓位占用");
        }

        // 常规触发价位
        if (state.pendingAddShares() == 0 && !state.positionFull(close, cfg)) {
            double trigger = avgCost * (1 - cfg.addDropPct());
            double room = Math.max(0d, state.totalCapital() * cfg.maxPositionRatio() - position * close);
            double budget = Math.min(state.availableCash() * cfg.addPositionRatio(), room);
            int shares = TTradingStrategyEngine.lots(budget / Math.max(trigger, 0.01));
            advice(list, TTradeAction.ADD, shares, trigger, close, state, cfg,
                    String.format("再跌至该价（相对成本 -%.2f%%）可按可用资金 %.0f%% 补仓 %d 股，仍需换手率与振幅过滤",
                            cfg.addDropPct() * 100, cfg.addPositionRatio() * 100, shares),
                    70, "补仓会加大单票暴露，若继续下跌将放大亏损，务必受仓位上限约束");
        }
        if (state.pendingTSellShares() == 0 && sellable >= 100) {
            double trigger = avgCost * (1 + cfg.tProfitPct());
            int shares = Math.min(TTradingStrategyEngine.lots(position * cfg.tSellRatio()),
                    TTradingStrategyEngine.lots(sellable));
            advice(list, TTradeAction.SELL_T, shares, trigger, close, state, cfg,
                    String.format("涨至该价（相对成本 +%.2f%%）高抛 %.0f%% 仓位，等待回落买回做正T",
                            cfg.tProfitPct() * 100, cfg.tSellRatio() * 100),
                    60, "高抛后若股价继续上涨，未回补部分会少赚，需按超时规则回补");
        }
        double exitPrice = avgCost * (1 + cfg.exitProfitPct());
        advice(list, TTradeAction.EXIT, sellable, exitPrice, close, state, cfg,
                String.format("涨至该价（相对成本 +%.2f%%）触发止盈离场，清仓锁定收益", cfg.exitProfitPct() * 100),
                50, "离场后若继续上涨将不再参与，属于规则化止盈而非收益最大化");
        double stopPrice = avgCost * (1 + cfg.stopLossPct());
        advice(list, TTradeAction.EXIT, sellable, stopPrice, close, state, cfg,
                String.format("跌破该价（相对成本 %.2f%%）触发止损清仓，优先于一切补仓与回补动作",
                        cfg.stopLossPct() * 100),
                40, "止损是纪律性保护，单日跳空低开时实际成交价可能更差");
        advice(list, TTradeAction.HOLD, position, Math.max(state.dilutedCost(), 0.01d), close, state, cfg,
                String.format("摊薄成本（剩余持仓回本价）为 %.3f：股价站上该价即整体回本；配合已实现盈利与成本降幅达 %.2f%% 时可回本离场",
                        state.dilutedCost(), cfg.costReductionTargetPct() * 100),
                5, "摊薄成本是净投入除以剩余股数的口径，不等于券商显示的持仓成本");

        list.sort((a, b) -> Integer.compare(b.priority(), a.priority()));
        return list.stream().limit(6).toList();
    }

    private void advice(List<TAdviceVO> out, TTradeAction action, int shares, double trigger,
                        double close, TPositionState state, TTradingConfig cfg,
                        String reason, int priority, String risk) {
        if (shares <= 0 || trigger <= 0) {
            return;
        }
        Double expect = null;
        double turnover = trigger * shares;
        if (action == TTradeAction.HOLD) {
            expect = state.dilutedCost();
        } else if (action.buy()) {
            int after = state.position() + shares;
            if (after > 0) {
                expect = (state.netInvested() + turnover + cfg.fee(turnover, false)) / after;
            }
        } else {
            int after = state.position() - shares;
            if (after > 0) {
                expect = (state.netInvested() - (turnover - cfg.fee(turnover, true))) / after;
            }
        }
        out.add(new TAdviceVO(action.code(), action.label(), shares,
                round(trigger, PRICE_SCALE), round(close, PRICE_SCALE),
                round((trigger / close - 1d) * 100d, PCT_SCALE),
                reason, priority, expect == null ? null : round(expect, PRICE_SCALE), risk));
    }

    private TStrategyConfigVO configVO(TTradingConfig cfg, String priceMode) {
        return new TStrategyConfigVO(priceMode,
                round(cfg.addDropPct(), 4), round(cfg.addPositionRatio(), 4),
                round(cfg.maxPositionRatio(), 4), round(cfg.tProfitPct(), 4),
                round(cfg.tSellRatio(), 4), round(cfg.tBuybackDrop(), 4), cfg.tMaxHoldDays(),
                round(cfg.reverseReboundPct(), 4), round(cfg.reduceProfitPct(), 4),
                round(cfg.exitProfitPct(), 4), round(cfg.stopLossPct(), 4),
                round(cfg.costReductionTargetPct(), 4), cfg.useMaFilter(),
                round(cfg.minTurnover(), 2), round(cfg.maxAmplitude(), 2),
                cfg.includeFee(), round(cfg.commissionRate(), 6), round(cfg.stampDutyRate(), 6),
                round(cfg.initialPositionRatio(), 4));
    }

    /**
     * 成本对比基准 = 当前这批持仓的建仓价。
     * <p>取最后一次「建仓」而非第一笔：中途止损/止盈清仓后系统会重新建仓，
     * 用首笔价格对比会让新周期的成本降幅失真（例如股价整体上涨时显示成本"上升"）。
     * 从未建仓（一直持有用户传入的底仓）时退化为底仓成本。</p>
     */
    private double baseCost(TPositionState state) {
        double lastEntry = 0d;
        for (TPositionState.TradeLog log : state.trades()) {
            if ("buy".equals(log.action())) {
                lastEntry = log.price();
            }
        }
        if (lastEntry > 0) {
            return lastEntry;
        }
        return state.initialAvgCost() > 0 ? state.initialAvgCost() : state.avgCost();
    }

    private static List<TBar> head(List<TBar> bars, int limit) {
        return bars.size() <= limit ? bars : bars.subList(0, limit);
    }

    private static List<TBar> tail(List<TBar> bars, int limit) {
        return bars.size() <= limit ? bars : bars.subList(bars.size() - limit, bars.size());
    }

    private static BigDecimal round(double value, int scale) {
        return Numbers.round(value, scale);
    }

    private static double positive(BigDecimal value, String label) {
        if (value == null || value.doubleValue() <= 0 || !Double.isFinite(value.doubleValue())) {
            throw BizException.badRequest(label + "必须是正数");
        }
        return value.doubleValue();
    }

    private static int normalizeAdjustFlag(Integer value) {
        int flag = value == null ? 2 : value;
        if (flag < 1 || flag > 3) {
            throw BizException.badRequest("复权口径只能是 1（后复权）、2（前复权）或 3（不复权）");
        }
        return flag;
    }

    /**
     * 股票代码归一化：既接受 6 位数字代码，也接受直接输入的股票中文名。
     * <p>前端联想框选中后会回填代码，这里额外兜底用户手敲名称后直接提交的情况：
     * 名称优先精确匹配，其次前缀匹配，最后取联想结果第一项。</p>
     */
    private String resolveCode(String raw) {
        String keyword = QuerySupport.trimToNull(raw);
        if (keyword == null) {
            throw BizException.badRequest("股票代码不能为空");
        }
        String code = keyword.toUpperCase()
                .replaceAll("^(SH|SZ)", "")
                .replaceAll("\\.(SH|SZ)$", "");
        if (code.matches("\\d{6}")) {
            return requireSupported(code);
        }
        List<StockBriefVO> matches = stockInfoRepository.suggest(keyword, 20);
        if (matches.isEmpty()) {
            throw BizException.badRequest("未找到与「" + keyword + "」匹配的股票，请输入 6 位代码或从联想列表中选择");
        }
        StockBriefVO best = matches.stream()
                .filter(v -> keyword.equalsIgnoreCase(v.name()))
                .findFirst()
                .orElseGet(() -> matches.stream()
                        .filter(v -> v.name() != null && v.name().startsWith(keyword))
                        .findFirst()
                        .orElse(matches.get(0)));
        if (best.code() == null) {
            throw BizException.badRequest("未找到与「" + keyword + "」匹配的有效股票代码");
        }
        return requireSupported(best.code());
    }

    private static String requireSupported(String code) {
        if (!(code.startsWith("00") || code.startsWith("30") || code.startsWith("60"))) {
            throw BizException.badRequest("当前日线分表仅覆盖 00 / 30 / 60 开头的股票：" + code);
        }
        return code;
    }

    private static String resolveMarket(String code, String raw) {
        String market = QuerySupport.trimToNull(raw);
        if (market != null) {
            return market.toUpperCase();
        }
        return code.startsWith("6") ? "SH" : "SZ";
    }
}
