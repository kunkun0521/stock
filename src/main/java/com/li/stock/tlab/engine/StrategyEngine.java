package com.li.stock.tlab.engine;

import com.li.stock.domain.Bar;
import com.li.stock.domain.Indicator;
import com.li.stock.domain.Order;
import com.li.stock.domain.Params;
import com.li.stock.domain.Pending;
import com.li.stock.domain.PositionState;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 规格 5.3：分档补仓（Add）/ 做T（SwingT）/ 离场（Exit）三个模块。
 *
 * <p><b>硬性约束</b>：本类是纯函数。入参只有 state、t-1 指标、bars[&lt;= t-1] 历史与参数，
 * 不含任何当日 K 线；所有「当日是否成交」的判断只出现在 {@link ExecutionSimulator}。
 * 因此把 t 日及以后的 K 线全部篡改，本方法在 t 日的输出不变（验收用例 7）。</p>
 *
 * <p>同日冲突优先级：止损与时间止损 &gt; 回本离场 &gt; 补仓 &gt; 遗留头寸处理 &gt; 做T。
 * 资金按优先级预留：先扣补仓预算，剩余才给做T的买入腿。</p>
 */
@Component
public class StrategyEngine {

    private static final String T_GROUP = "T";

    private final FeeCalculator fees;

    public StrategyEngine(FeeCalculator fees) {
        this.fees = fees;
    }

    /**
     * @param state   当日开盘前的持仓状态（只读，不修改）
     * @param ind     indicators[t-1]
     * @param history bars[&lt;= t-1]，最后一根即昨收 C0
     * @param p       生效参数
     * @param cfg     静态上下文（总资金、可支配资金、交易日索引等）
     */
    public StrategyPlan planOrders(PositionState state, Indicator ind, List<Bar> history,
                                   Params p, EngineConfig cfg) {
        List<Order> orders = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        if (history.isEmpty()) return new StrategyPlan(orders, List.of("无历史行情，无法生成挂单"));

        Bar last = history.get(history.size() - 1);
        double c0 = last.close() > 0 ? last.close() : ind.close();
        double limitRatio = last.limitRatio();
        double limitUp = fees.round2(c0 * (1 + limitRatio));
        double limitDown = fees.round2(c0 * (1 - limitRatio));

        // ---- 1. 止损与时间止损：下一个交易日开盘全部卖出（最高优先级） ----
        double prevPnL = state.totalPnL(c0);
        double lossLine = -p.maxLossRate * Math.max(state.maxInvested, state.netInvested());
        boolean stopLoss = state.qty > 0 && state.maxInvested > 0 && prevPnL <= lossLine;
        boolean timeStop = state.qty > 0 && state.holdDays > p.maxHoldDays;
        if (stopLoss || timeStop) {
            int qty = fees.roundLot(state.sellableQty);
            if (qty >= 100) {
                orders.add(Order.of(Order.STOP, Order.SELL, 0, qty, 0, true, null,
                        stopLoss ? "止损：累计盈亏已触及 -" + pct(p.maxLossRate) + " × 最大占用资金"
                                : "时间止损：持仓超过 " + p.maxHoldDays + " 个交易日"));
                notes.add("触发" + (stopLoss ? "止损" : "时间止损") + "，次日开盘全部卖出 " + qty + " 股");
            }
        } else {
            notes.add("未触发止损 / 时间止损（当前累计盈亏 " + money(prevPnL)
                    + "，持仓 " + state.holdDays + " 日）");
        }

        // ---- 2. 回本或达标离场：求最小卖出价 p ----
        if (!stopLoss && !timeStop && state.qty > 0 && state.sellableQty >= 100) {
            double target = p.targetProfitRate * Math.max(state.maxInvested, state.netInvested());
            double exitPrice = solveExitPrice(state, p, target);
            exitPrice = Math.min(limitUp, Math.max(limitDown, fees.round2(exitPrice)));
            if (exitPrice > 0) {
                int qty = fees.roundLot(state.sellableQty);
                boolean marketOnOpen = exitPrice <= c0;
                orders.add(Order.of(Order.EXIT, Order.SELL, exitPrice, qty, 0, marketOnOpen, null,
                        "回本/达标离场价（目标收益率 " + pct(p.targetProfitRate) + "）"));
                notes.add(marketOnOpen
                        ? "摊薄成本已低于现价，次日开盘即按市价离场 " + qty + " 股"
                        : "挂回本/达标卖单 " + money(exitPrice) + " 元 × " + qty + " 股");
            }
        }

        // ---- 3. 分档补仓 ----
        double addReserved = planAdd(state, ind, history, p, cfg, c0, limitDown, orders, notes);

        // ---- 4. 遗留头寸处理 ----
        planPending(state, p, cfg, c0, limitUp, limitDown, orders, notes);

        // ---- 5. 做T（pending 存在时不开新的做T） ----
        planSwingT(state, ind, history, p, c0, limitUp, limitDown, addReserved, orders, notes);

        orders.sort(Comparator.comparingInt(Order::priority));
        return new StrategyPlan(orders, notes);
    }

    /** 分档补仓。返回本单预留资金，供做T买入腿的可用现金扣减。 */
    private double planAdd(PositionState state, Indicator ind, List<Bar> history, Params p,
                           EngineConfig cfg, double c0, double limitDown,
                           List<Order> orders, List<String> notes) {
        if (state.qty <= 0) {
            notes.add("补仓：无持仓，不触发");
            return 0;
        }
        double diluted = state.dilutedCost();
        if (diluted <= 0) {
            notes.add("补仓：摊薄成本 ≤ 0，按规则不补仓");
            return 0;
        }
        if (state.addCount >= Math.min(p.maxAddTimes, p.addSteps.size())) {
            notes.add("补仓：已达 maxAddTimes=" + p.maxAddTimes + "，不补仓");
            return 0;
        }
        int sinceLast = state.lastAddDayIndex < 0 ? Integer.MAX_VALUE : cfg.dayIndex() - state.lastAddDayIndex;
        if (sinceLast < p.addMinIntervalDays) {
            notes.add("补仓：距上次补仓 " + sinceLast + " 个交易日，未达间隔 " + p.addMinIntervalDays);
            return 0;
        }
        boolean confirmOk = true;
        if ("STOP_FALL".equalsIgnoreCase(p.addConfirm)) {
            confirmOk = (last(history).close() > last(history).open()) || ind.rsi14() > cfg.prev2Rsi();
            if (!confirmOk) {
                notes.add("补仓：STOP_FALL 企稳确认未通过（昨收未高于开盘且 RSI14 未回升）");
                return 0;
            }
        }

        int stepIndex = Math.min(state.addCount, p.addSteps.size() - 1);
        double step = p.addSteps.get(stepIndex);
        double addPrice = Math.max(limitDown, fees.round2(diluted * (1 - step)));
        if (addPrice <= 0) return 0;

        double budget = cfg.availableCash() * (1 - p.tReserveRatio)
                * p.addRatios.get(Math.min(stepIndex, p.addRatios.size() - 1));
        budget = Math.min(budget, state.cash);
        int qtyByBudget = fees.roundLot(budget / addPrice);

        // 补仓后持仓市值不超过 totalCapital × maxPositionRatio
        double capValue = cfg.totalCapital() * p.maxPositionRatio;
        int qtyByCap = fees.roundLot(Math.max(0, capValue - state.qty * c0) / c0);
        int qty = Math.min(qtyByBudget, qtyByCap);
        if (qty < 100) {
            notes.add("补仓：第 " + (stepIndex + 1) + " 档触发价 " + money(addPrice)
                    + " 元，但预算或仓位上限不足 100 股");
            return 0;
        }
        double reserved = qty * addPrice;
        orders.add(Order.of(Order.ADD, Order.BUY, addPrice, qty, reserved, false, null,
                "第 " + (stepIndex + 1) + " 档：摊薄成本下跌 " + pct(step)));
        notes.add("挂补仓单 " + money(addPrice) + " 元 × " + qty + " 股，预留 " + money(reserved) + " 元");
        return reserved;
    }

    /** 遗留头寸：pending 存在时不开新做T，优先回补 / 减仓，超期按 carryPolicy 处理。 */
    private void planPending(PositionState state, Params p, EngineConfig cfg, double c0,
                             double limitUp, double limitDown,
                             List<Order> orders, List<String> notes) {
        Pending pending = state.pending;
        if (pending == null) return;
        boolean expired = pending.heldDays() >= p.maxCarryDays;
        boolean forceSell = p.carryPolicy.toUpperCase().contains("SELL_FORCE");
        double cost = p.roundTripCostRate() + p.minNetProfitRate;

        if (Pending.BUYBACK.equals(pending.kind())) {
            int qty = fees.roundLot(Math.min(pending.qty(), state.cash / Math.max(c0, 0.01)));
            if (expired) {
                if (qty >= 100) {
                    orders.add(Order.of(Order.FORCE_BUYBACK, Order.BUY, 0, qty, qty * c0, true, null,
                            "遗留回补超 " + p.maxCarryDays + " 日，收盘价强制回补"));
                    notes.add("遗留回补头寸超期，按收盘价强制回补 " + qty + " 股");
                }
            } else {
                double price = Math.max(limitDown, fees.round2(pending.refPx() * (1 - cost)));
                qty = Math.min(fees.roundLot(pending.qty()), fees.roundLot(state.cash / price));
                if (qty >= 100) {
                    orders.add(Order.of(Order.BUYBACK, Order.BUY, price, qty, qty * price, false, null,
                            "回补限价 = 参考价 × (1 - 往返成本 - 最低净利)"));
                    notes.add("挂回补单 " + money(price) + " 元 × " + qty + " 股");
                }
            }
        } else {
            int qty = Math.min(fees.roundLot(pending.qty()), fees.roundLot(state.sellableQty));
            if (expired && !forceSell) {
                orders.add(Order.of(Order.CARRY_ABSORB, Order.SELL, 0, qty, 0, false, null,
                        "遗留待卖头寸超期，按 SELL_ABSORB 并入持仓"));
                notes.add("遗留待卖头寸超期，并入持仓 " + qty + " 股（等同一次补仓）");
            } else {
                double price = Math.min(limitUp, fees.round2(pending.refPx() * (1 + cost)));
                if (qty >= 100) {
                    orders.add(Order.of(Order.PENDING_SELL, Order.SELL, price, qty, 0,
                            expired, null, expired ? "超期收盘强平" : "减仓限价 = 参考价 × (1 + 往返成本 + 最低净利)"));
                    notes.add("挂遗留减仓单 " + money(price) + " 元 × " + qty + " 股");
                }
            }
        }
    }

    /** 双向限价挂单做T：昨收 C0 上下各挂一个 band，撮合顺序无关。 */
    private void planSwingT(PositionState state, Indicator ind, List<Bar> history, Params p,
                            double c0, double limitUp, double limitDown, double addReserved,
                            List<Order> orders, List<String> notes) {
        if (state.pending != null) {
            notes.add("做T：存在遗留头寸，今日不开新的做T");
            return;
        }
        if (state.sellableQty < 100) {
            notes.add("做T：无可卖底仓（T+1 约束，当日买入不可卖）");
            return;
        }
        double band = Math.min(p.bandMax, Math.max(p.bandMin(), p.kAtr * ind.atrPct()));
        double sellLimit = Math.min(limitUp, fees.round2(c0 * (1 + band)));
        double buyLimit = Math.max(limitDown, fees.round2(c0 * (1 - band)));
        if (sellLimit <= buyLimit) {
            notes.add("做T：涨跌停限制导致价带无效");
            return;
        }

        // 资金按优先级预留：先扣补仓预算，剩余才给做T买入腿
        double tCash = Math.max(0, state.cash - addReserved);
        double byCash = tCash / buyLimit;
        double byPosition = state.sellableQty * p.tRatio;
        double amount = last(history).amount();
        double byLiquidity = amount > 0 && p.liquidityParticipation > 0
                ? amount * p.liquidityParticipation / buyLimit : Double.MAX_VALUE;
        int n = fees.roundLot(Math.min(byCash, Math.min(byPosition, byLiquidity)));
        if (n < 100) {
            notes.add("做T：预留补仓资金后可用现金 / 底仓 / 流动性不足 100 股");
            return;
        }
        double grossProfit = n * (sellLimit - buyLimit);
        double totalFee = fees.fee(n * sellLimit, true, p) + fees.fee(n * buyLimit, false, p);
        double netProfit = grossProfit - totalFee;
        if (netProfit < p.minTNetProfitYuan || band < p.bandMin()) {
            notes.add("做T：预期净利 " + money(netProfit) + " 元低于下限 " + money(p.minTNetProfitYuan) + " 元");
            return;
        }

        orders.add(Order.of(Order.T_SELL, Order.SELL, sellLimit, n, 0, false, T_GROUP,
                "高抛：昨收 × (1 + " + pct(band) + ")"));
        orders.add(Order.of(Order.T_BUY, Order.BUY, buyLimit, n, n * buyLimit + totalFee / 2, false, T_GROUP,
                "低吸：昨收 × (1 - " + pct(band) + ")，预期净利 " + money(netProfit) + " 元"));
        notes.add("双向挂单：卖 " + money(sellLimit) + " / 买 " + money(buyLimit)
                + " 元 × " + n + " 股，预期净利 " + money(netProfit) + " 元");
    }

    /** 求最小价格 p 使 qty*p - sellFees(qty*p) - (cumBuyCost - cumSellNet) >= target。 */
    private double solveExitPrice(PositionState state, Params p, double target) {
        double need = state.netInvested() + target;
        if (need <= 0) return 0.01d;   // 做T已赚回本金，次日开盘即走
        double lo = 0d;
        double hi = Math.max(1d, need / Math.max(state.qty, 1) * 4);
        for (int i = 0; i < 80; i++) {
            double mid = (lo + hi) / 2;
            double amount = state.qty * mid;
            double net = amount - fees.fee(amount, true, p);
            if (net >= need) hi = mid;
            else lo = mid;
        }
        return Math.ceil(hi * 100) / 100d;
    }

    private static Bar last(List<Bar> bars) {
        return bars.get(bars.size() - 1);
    }

    private static String money(double v) {
        return String.format("%.2f", v);
    }

    private static String pct(double v) {
        return String.format("%.2f%%", v * 100);
    }
}
