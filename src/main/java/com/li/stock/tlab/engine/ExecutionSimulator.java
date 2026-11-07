package com.li.stock.tlab.engine;

import com.li.stock.domain.Bar;
import com.li.stock.domain.Fill;
import com.li.stock.domain.Order;
import com.li.stock.domain.Params;
import com.li.stock.domain.Pending;
import com.li.stock.domain.PositionState;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规格 5.2 / 5.3：唯一读取当日 K 线并判定成交的地方。
 * <p>StrategyEngine 只产出挂单；本类按优先级撮合，负责：</p>
 * <ul>
 *   <li>停牌、一字板当日无任何成交（验收用例 6）</li>
 *   <li>挂单价限制在 [跌停价, 涨停价] 内</li>
 *   <li>做T双向挂单的四种成交情况（顺序无关）</li>
 *   <li>T+1：卖出数量不得超过开盘时的 sellableQty（验收用例 2）</li>
 *   <li>所有数量是 100 的整数倍；cash、qty 任何时刻不为负（验收用例 5）</li>
 *   <li>清仓成交当天其余挂单全部作废</li>
 * </ul>
 */
@Component
public class ExecutionSimulator {

    private final FeeCalculator fees;

    public ExecutionSimulator(FeeCalculator fees) {
        this.fees = fees;
    }

    /**
     * @param orders   StrategyEngine 产出的挂单（已按优先级排序）
     * @param bar      当日 K 线（t 日）
     * @param state    当日开盘前的持仓状态，成交后原地更新
     * @param p        生效参数
     * @param date     当日交易日
     * @param dayIndex 当日索引，用于补仓间隔
     * @return 成交明细；空列表表示当日无成交
     */
    public List<Fill> execute(List<Order> orders, Bar bar, PositionState state, Params p,
                              LocalDate date, int dayIndex) {
        List<Fill> fills = new ArrayList<>();
        if (orders == null || orders.isEmpty()) return fills;
        // 停牌 / 一字板当日无任何成交，只做估值（验收用例 6）
        if (bar.suspended() || bar.oneWordBoard()) return fills;

        double preclose = bar.preclose() > 0 ? bar.preclose() : bar.open();
        double limitUp = fees.round2(preclose * (1 + bar.limitRatio()));
        double limitDown = Math.max(0.01d, fees.round2(preclose * (1 - bar.limitRatio())));

        // 做T两腿必须联合判定，先把同一 group 的订单抽出来
        Map<String, List<Order>> grouped = new LinkedHashMap<>();
        List<Order> singles = new ArrayList<>();
        for (Order o : orders) {
            if (o.group() != null && !o.group().isEmpty()) {
                grouped.computeIfAbsent(o.group(), k -> new ArrayList<>()).add(o);
            } else {
                singles.add(o);
            }
        }

        for (Order o : singles) {
            if (state.closed) break;
            Fill fill = matchOne(o, bar, state, p, limitUp, limitDown, date, dayIndex);
            if (fill == null) continue;
            fills.add(fill);
            if (fill.closePosition()) {
                state.closed = true;
                break;   // 清仓成交当天其余挂单全部作废
            }
        }

        if (!state.closed) {
            for (List<Order> pair : grouped.values()) {
                for (Fill f : matchSwingT(pair, bar, state, p, limitUp, limitDown, date)) {
                    if (f != null) fills.add(f);
                }
                if (state.closed) break;
            }
        }
        state.refreshMaxInvested();
        return fills;
    }

    /** 单腿撮合，返回 null 表示未成交。 */
    private Fill matchOne(Order o, Bar bar, PositionState state, Params p,
                          double limitUp, double limitDown, LocalDate date, int dayIndex) {
        if (o.qty() < 100 || o.qty() % 100 != 0) return null;

        // 遗留头寸超期并入持仓：不产生买卖，只清除 pending
        if (Order.CARRY_ABSORB.equals(o.kind())) {
            state.pending = null;
            return Fill.of("CARRY_ABSORB", 0, o.qty(), 0, o.reason()).withClearedPending();
        }

        // 市价类成交（止损 / 时间止损 / 强制回补 / 回本跳空 / 超期强平）按开盘价 + 滑点
        if (o.marketOnOpen()) {
            if (Order.BUY.equals(o.side())) {
                double price = clamp(fees.slip(bar.open(), true, p), limitDown, limitUp);
                int qty = capBuy(o.qty(), price, state, p);
                if (qty < 100) return null;
                Fill f = applyBuy(o.kind(), price, qty, state, p, date, o.reason());
                if (Order.FORCE_BUYBACK.equals(o.kind())) {
                    state.pending = null;
                    return f.withClearedPending();
                }
                return f;
            }
            double price = clamp(fees.slip(bar.open(), false, p), limitDown, limitUp);
            int qty = capSell(o.qty(), state);
            if (qty < 100) return null;
            return applySell(o.kind(), price, qty, state, p, o.reason(), true);
        }

        double limit = clamp(o.limitPrice(), limitDown, limitUp);
        if (Order.BUY.equals(o.side())) {
            double price;
            if (bar.open() <= limit) price = clamp(bar.open(), limitDown, limitUp);  // 开盘价更优
            else if (bar.low() <= limit) price = limit;
            else return null;
            int qty = capBuy(o.qty(), price, state, p);
            if (qty < 100) return null;
            Fill f = applyBuy(o.kind(), price, qty, state, p, date, o.reason());
            if (Order.ADD.equals(o.kind())) {
                state.addCount++;
                state.lastAddDate = date;
                state.lastAddDayIndex = dayIndex;
                state.addAmount += qty * price;
            }
            if (Order.BUYBACK.equals(o.kind())) {
                state.pending = null;
                return f.withClearedPending();
            }
            return f;
        }

        // 卖出限价单
        double price;
        if (bar.open() >= limit) price = clamp(bar.open(), limitDown, limitUp);
        else if (bar.high() >= limit) price = limit;
        else return null;
        int qty = capSell(o.qty(), state);
        if (qty < 100) return null;
        Fill f = applySell(o.kind(), price, qty, state, p, o.reason(), Order.EXIT.equals(o.kind()));
        if (Order.PENDING_SELL.equals(o.kind())) {
            state.pending = null;
            return f.withClearedPending();
        }
        return f;
    }

    /**
     * 做T双向挂单：成交判定与盘中先后顺序无关。
     * 两腿都成交 → 完整往返，持仓不变；只成交一腿 → 登记 pending。
     */
    private List<Fill> matchSwingT(List<Order> pair, Bar bar, PositionState state, Params p,
                                   double limitUp, double limitDown, LocalDate date) {
        List<Fill> out = new ArrayList<>();
        Order sell = null;
        Order buy = null;
        for (Order o : pair) {
            if (Order.T_SELL.equals(o.kind())) sell = o;
            if (Order.T_BUY.equals(o.kind())) buy = o;
        }
        if (sell == null || buy == null || sell.qty() != buy.qty()) return out;

        double sellLimit = clamp(sell.limitPrice(), limitDown, limitUp);
        double buyLimit = clamp(buy.limitPrice(), limitDown, limitUp);
        if (sellLimit <= buyLimit) return out;

        boolean sellHit = bar.high() >= sellLimit;
        boolean buyHit = bar.low() <= buyLimit;
        // 跳空：开盘价直接越过限价时按开盘价成交
        double sellPrice = bar.open() >= sellLimit ? clamp(bar.open(), limitDown, limitUp) : sellLimit;
        double buyPrice = bar.open() <= buyLimit ? clamp(bar.open(), limitDown, limitUp) : buyLimit;

        int n = Math.min(fees.roundLot(sell.qty()), fees.roundLot(state.sellableQty));
        if (n < 100 || (!sellHit && !buyHit)) return out;

        if (sellHit && buyHit) {
            double sellAmount = n * sellPrice;
            double sellFee = fees.fee(sellAmount, true, p);
            // 完整往返按「先卖底仓、再用所得现金买回」撮合，与盘中先后顺序无关，
            // 因此买回可用现金需包含卖出净收入，保证 buyQty == n、持仓不变（验收用例 3）
            double afterSellCash = state.cash + sellAmount - sellFee;
            int buyQty = capBuyWithCash(n, buyPrice, afterSellCash, p);
            if (buyQty < 100) return out;
            double buyAmount = buyQty * buyPrice;
            double buyFee = fees.fee(buyAmount, false, p);
            double net = sellAmount - sellFee - buyAmount - buyFee;
            state.cumSellNet += sellAmount - sellFee;
            state.qty -= n;
            state.sellableQty -= n;
            state.cash = afterSellCash;
            state.cumBuyCost += buyAmount + buyFee;
            state.qty += buyQty;
            state.cash -= buyAmount + buyFee;
            state.feeTotal += sellFee + buyFee;
            state.tRoundTrips++;
            state.tNetProfit += net;
            if (net > 0) state.tWinCount++;
            state.lastBuyDate = date;
            state.refreshMaxInvested();
            out.add(Fill.of("T_SELL", sellPrice, n, sellFee, "高抛底仓 " + n + " 股"));
            out.add(new Fill("T_BUY", buyPrice, buyQty, buyFee, net,
                    "完整往返：卖 " + n + " 股 / 买回 " + buyQty + " 股，净收益 " + fmt(net) + " 元",
                    null, 0d, false, false));
            return out;
        }

        if (sellHit) {
            double sellFee = fees.fee(n * sellPrice, true, p);
            state.cumSellNet += n * sellPrice - sellFee;
            state.qty -= n;
            state.sellableQty -= n;
            state.cash += n * sellPrice - sellFee;
            state.feeTotal += sellFee;
            state.tPendingCount++;
            state.pending = new Pending(Pending.BUYBACK, sellPrice, n, date, 0);
            state.refreshMaxInvested();
            out.add(Fill.of("T_SELL", sellPrice, n, sellFee,
                    "只成交卖腿，登记 pendingBuyback " + n + " 股").withPending(Pending.BUYBACK, sellPrice));
            return out;
        }

        int buyQty = capBuy(n, buyPrice, state, p);
        if (buyQty < 100) return out;
        double buyFee = fees.fee(buyQty * buyPrice, false, p);
        state.cumBuyCost += buyQty * buyPrice + buyFee;
        state.qty += buyQty;
        state.cash -= buyQty * buyPrice + buyFee;
        state.feeTotal += buyFee;
        state.tPendingCount++;
        state.pending = new Pending(Pending.SELL, buyPrice, buyQty, date, 0);
        state.lastBuyDate = date;
        state.refreshMaxInvested();
        out.add(Fill.of("T_BUY", buyPrice, buyQty, buyFee,
                "只成交买腿，登记 pendingSell " + buyQty + " 股").withPending(Pending.SELL, buyPrice));
        return out;
    }

    private Fill applyBuy(String action, double price, int qty, PositionState state, Params p,
                          LocalDate date, String note) {
        double amount = qty * price;
        double fee = fees.fee(amount, false, p);
        state.cash -= amount + fee;
        state.qty += qty;
        state.cumBuyCost += amount + fee;
        state.feeTotal += fee;
        state.lastBuyDate = date;
        state.refreshMaxInvested();
        return Fill.of(action, price, qty, fee, note);
    }

    private Fill applySell(String action, double price, int qty, PositionState state, Params p,
                           String note, boolean closePosition) {
        double amount = qty * price;
        double fee = fees.fee(amount, true, p);
        state.cash += amount - fee;
        state.qty -= qty;
        state.sellableQty -= qty;
        state.cumSellNet += amount - fee;
        state.feeTotal += fee;
        state.refreshMaxInvested();
        if (state.qty < 0) state.qty = 0;
        if (state.sellableQty < 0) state.sellableQty = 0;
        boolean cleared = closePosition && state.qty <= 0;
        if (cleared) {
            state.qty = 0;
            state.sellableQty = 0;
        }
        return new Fill(action, price, qty, fee, null, note, null, 0d, cleared, false);
    }

    /** 买入数量受现金约束，向下取整到整手，保证 cash 不为负。 */
    private int capBuy(int qty, double price, PositionState state, Params p) {
        return capBuyWithCash(qty, price, state.cash, p);
    }

    private int capBuyWithCash(int qty, double price, double cash, Params p) {
        double rate = 1 + p.commissionRate + p.transferFeeRate + p.otherFeeRate;
        int affordable = fees.roundLot((cash - p.minCommission) / Math.max(price * rate, 0.01));
        return fees.roundLot(Math.min(qty, Math.max(0, affordable)));
    }

    /** 卖出数量受 T+1 可卖数量约束。 */
    private int capSell(int qty, PositionState state) {
        return fees.roundLot(Math.min(Math.min(qty, state.sellableQty), state.qty));
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.min(hi, Math.max(lo, v));
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }
}
