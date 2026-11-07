package com.li.stock.tlab.engine;

import com.li.stock.domain.Bar;
import com.li.stock.domain.DailyPoint;
import com.li.stock.domain.Fill;
import com.li.stock.domain.Indicator;
import com.li.stock.domain.Order;
import com.li.stock.domain.Params;
import com.li.stock.domain.PositionState;
import com.li.stock.domain.SimOutcome;
import com.li.stock.domain.TradeRecord;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 规格 5.2 的每日循环。防前视是硬性要求：
 * <ol>
 *   <li>停牌日只做估值</li>
 *   <li>sellableQty = 开盘时持仓（T+1，当日买入不可卖）</li>
 *   <li>planOrders(state, indicators[t-1], bars[&lt;=t-1]) 只看昨天及以前</li>
 *   <li>execute(orders, bar[t]) 只有这里才看当日 K 线</li>
 *   <li>应用成交、写交易明细</li>
 *   <li>收盘估值、写每日快照</li>
 * </ol>
 */
@Component
public class Backtester {

    /** 单次回放的模式，用于生成两个基准。 */
    public enum Mode {
        /** 完整策略：补仓 + 做T + 离场。 */
        FULL,
        /** 只补仓不做T：用于衡量做T贡献。 */
        ONLY_ADD,
        /** 买入持有：不补仓、不做T、不离场。 */
        BUY_HOLD
    }

    private final FeeCalculator fees;
    private final StrategyEngine strategy;
    private final ExecutionSimulator execution;

    public Backtester(FeeCalculator fees, StrategyEngine strategy, ExecutionSimulator execution) {
        this.fees = fees;
        this.strategy = strategy;
        this.execution = execution;
    }

    /**
     * @param bars       完整日线（含预热段），时间升序
     * @param indicators 与 bars 等长的指标
     * @param startIndex 买入日在 bars 中的索引（INIT 发生在此日）
     * @param endIndex   最后一个回放交易日索引（含）
     * @param p          生效参数
     * @param buyPrice   初始买入价（null 时按 buyPriceMode 取收盘 / 次开）
     * @param initQty    初始买入股数（null 时按 initAmount 折算）
     * @param initAmount 初始买入金额
     * @param capital    总资金
     * @param cash       可支配资金
     */
    public SimOutcome run(List<Bar> bars, List<Indicator> indicators, int startIndex, int endIndex,
                          Params p, Double buyPrice, Integer initQty, Double initAmount,
                          double capital, double cash, Mode mode) {
        List<DailyPoint> daily = new ArrayList<>();
        List<TradeRecord> trades = new ArrayList<>();
        List<Double> equity = new ArrayList<>();
        PositionState state = new PositionState();
        state.cash = cash;
        state.maxInvested = 0;

        Bar initBar = bars.get(startIndex);
        double price = buyPrice != null && buyPrice > 0
                ? buyPrice
                : ("NEXT_OPEN".equalsIgnoreCase(p.buyPriceMode) ? initBar.open() : initBar.close());
        price = fees.round2(price);
        int qty = initQty != null && initQty > 0
                ? fees.roundLot(initQty)
                : fees.roundLot((initAmount != null ? initAmount : capital * p.initPositionRatio) / Math.max(price, 0.01));
        if (qty < 100) qty = 0;

        double initialAmount = qty * price;
        double initFee = qty > 0 ? fees.fee(initialAmount, false, p) : 0d;
        if (qty > 0) {
            state.cash -= initialAmount + initFee;
            state.qty = qty;
            state.cumBuyCost = initialAmount + initFee;
            state.feeTotal = initFee;
            state.lastBuyDate = initBar.date();
            state.refreshMaxInvested();
            trades.add(new TradeRecord(initBar.date(), "INIT", price, qty, initialAmount, initFee,
                    null, "初始建仓：按" + (buyPrice != null ? "指定价" : p.buyPriceMode) + "成交"));
        }

        LocalDate recoverDate = null;
        Integer recoverDays = null;
        int holdDays = 0;
        double peakEquity = state.equity(initBar.close());
        double maxDrawdown = 0d;
        int lastIndex = Math.min(endIndex, bars.size() - 1);

        for (int t = startIndex; t <= lastIndex; t++) {
            Bar bar = bars.get(t);
            StringBuilder events = new StringBuilder();

            if (t > startIndex) {
                holdDays++;
                state.holdDays = holdDays;
                state.sellableQty = state.qty;   // T+1：开盘时持仓全部可卖，当日买入不计入
                if (state.pending != null) {
                    state.pending = state.pending.withHeldDays(state.pending.heldDays() + 1);
                }
                if (mode != Mode.BUY_HOLD && !state.closed && state.qty > 0) {
                    Indicator prev = indicators.get(Math.max(0, t - 1));
                    double prev2Rsi = t >= 2 ? indicators.get(t - 2).rsi14() : prev.rsi14();
                    EngineConfig cfg = new EngineConfig(capital, cash, t, bar.date(), prev2Rsi);
                    List<Bar> history = bars.subList(0, t);   // 严格 bars[<= t-1]
                    StrategyPlan plan = strategy.planOrders(state, prev, history, p, cfg);
                    List<Order> orders = new ArrayList<>(plan.orders());
                    if (mode == Mode.ONLY_ADD) {
                        orders.removeIf(o -> Order.T_SELL.equals(o.kind()) || Order.T_BUY.equals(o.kind()));
                    }
                    // 买入持有基准不产生任何挂单；其余模式正常撮合
                    List<Fill> fills = execution.execute(orders, bar, state, p, bar.date(), t);
                    for (Fill f : fills) {
                        String action = f.action();
                        trades.add(new TradeRecord(bar.date(), action, f.price(), f.qty(),
                                f.price() * f.qty(), f.fee(), f.realizedPnl(), f.note()));
                        appendEvent(events, action);
                    }
                }
            } else {
                appendEvent(events, "INIT");
            }

            double close = bar.close() > 0 ? bar.close() : (t > 0 ? bars.get(t - 1).close() : price);
            double equityNow = state.equity(close);
            peakEquity = Math.max(peakEquity, equityNow);
            if (peakEquity > 0) maxDrawdown = Math.max(maxDrawdown, (peakEquity - equityNow) / peakEquity);
            double pnl = state.totalPnL(close);
            if (recoverDate == null && pnl >= 0 && !trades.isEmpty()) {
                recoverDate = bar.date();
                recoverDays = holdDays;
            }
            equity.add(equityNow);
            daily.add(new DailyPoint(bar.date(), close, state.qty, state.sellableQty,
                    state.cash, equityNow, state.dilutedCost(), pnl, events.toString()));

            if (state.closed || (state.qty > 0 && holdDays > p.maxHoldDays && mode == Mode.BUY_HOLD)) break;
            if (state.closed) break;
        }

        double lastClose = daily.isEmpty() ? price : daily.get(daily.size() - 1).close();
        double finalPnlPct = capital > 0 ? state.totalPnL(lastClose) / capital : 0d;
        return new SimOutcome(daily, trades, state, equity, maxDrawdown, recoverDate, recoverDays,
                price, qty, holdDays, finalPnlPct);
    }

    private static void appendEvent(StringBuilder sb, String action) {
        if (sb.length() > 0) sb.append(',');
        sb.append(switch (action) {
            case "INIT" -> "INIT";
            case "ADD", "BUYBACK", "FORCE_BUYBACK" -> "ADD";
            case "T_SELL", "T_BUY", "SELL_PENDING", "PENDING_SELL", "CARRY_ABSORB" -> "T";
            case "EXIT" -> "EXIT";
            case "STOP" -> "STOP";
            default -> action;
        });
    }
}
