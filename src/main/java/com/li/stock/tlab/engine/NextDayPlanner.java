package com.li.stock.tlab.engine;

import com.li.stock.domain.Bar;
import com.li.stock.domain.Indicator;
import com.li.stock.domain.Order;
import com.li.stock.domain.Params;
import com.li.stock.domain.PositionState;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规格第八节：次日操作计划。给定当前真实持仓，直接复用 StrategyEngine.planOrders，
 * 不做 K 线撮合。输出补仓 / 做T / 回本卖出 / 止损挂单与触发解释。
 */
@Component
public class NextDayPlanner {

    private final StrategyEngine strategy;
    private final FeeCalculator fees;

    public NextDayPlanner(StrategyEngine strategy, FeeCalculator fees) {
        this.strategy = strategy;
        this.fees = fees;
    }

    /**
     * @param bars       截至 asOfDate 的全部日线（时间升序）
     * @param indicators 与 bars 等长的指标
     * @param asOfDate   基准日
     */
    public PlanBundle plan(List<Bar> bars, List<Indicator> indicators, LocalDate asOfDate,
                           int qty, int sellableQty, double avgCost, double cash,
                           double capital, Params p) {
        int last = bars.size() - 1;
        PositionState state = new PositionState();
        state.qty = qty;
        state.sellableQty = Math.min(sellableQty, qty);
        state.cash = cash;
        state.cumBuyCost = qty * avgCost;
        state.cumSellNet = 0;
        state.maxInvested = state.netInvested();
        state.lastBuyDate = null;

        Indicator ind = indicators.get(last);
        double prev2Rsi = last >= 1 ? indicators.get(last - 1).rsi14() : ind.rsi14();
        EngineConfig cfg = new EngineConfig(capital, cash, last, asOfDate, prev2Rsi);
        StrategyPlan plan = strategy.planOrders(state, ind, bars, p, cfg);

        List<Map<String, Object>> orders = new ArrayList<>();
        Map<String, Order> tPair = new LinkedHashMap<>();
        for (Order o : plan.orders()) {
            if (Order.T_SELL.equals(o.kind()) || Order.T_BUY.equals(o.kind())) {
                tPair.put(o.kind(), o);
                continue;
            }
            orders.add(toCard(o));
        }
        if (tPair.size() == 2) {
            Order sell = tPair.get(Order.T_SELL);
            Order buy = tPair.get(Order.T_BUY);
            double gross = sell.qty() * (sell.limitPrice() - buy.limitPrice());
            double net = gross - fees.fee(sell.qty() * sell.limitPrice(), true, p)
                    - fees.fee(buy.qty() * buy.limitPrice(), false, p);
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("type", "T");
            card.put("label", "做T双向挂单");
            card.put("side", "BUY+SELL");
            card.put("sellPrice", money(sell.limitPrice()));
            card.put("buyPrice", money(buy.limitPrice()));
            card.put("qty", sell.qty());
            card.put("occupiedCash", money(buy.reservedCash()));
            card.put("expectedNetProfit", money(net));
            card.put("condition", "次日 low ≤ 买价 且 high ≥ 卖价 时完成一次往返；只成交一腿则登记遗留头寸");
            card.put("reason", sell.reason() + "；" + buy.reason());
            orders.add(card);
        }

        // 回本 / 达标卖出价与止损价，即使当天没有触发离场也给出参考价
        Map<String, Object> levels = new LinkedHashMap<>();
        double preclose = bars.get(last).preclose() > 0 ? bars.get(last).preclose() : ind.close();
        double limitRatio = bars.get(last).limitRatio();
        double limitUp = fees.round2(preclose * (1 + limitRatio));
        double limitDown = Math.max(0.01, fees.round2(preclose * (1 - limitRatio)));
        double exitTarget = p.targetProfitRate * state.maxInvested;
        double need = state.netInvested() + exitTarget;
        double exitPrice = qty > 0
                ? Math.min(limitUp, Math.max(limitDown, fees.round2(solve(need, qty, p)))) : 0d;
        double stopPrice = qty > 0
                ? Math.min(limitUp, Math.max(limitDown,
                fees.round2((state.netInvested() - p.maxLossRate * state.maxInvested) / qty))) : 0d;
        levels.put("exitPrice", money(exitPrice));
        levels.put("stopPrice", money(stopPrice));
        levels.put("limitUp", money(limitUp));
        levels.put("limitDown", money(limitDown));
        levels.put("dilutedCost", money(state.dilutedCost()));
        levels.put("totalPnlAtLastClose", money(state.totalPnL(ind.close())));

        Map<String, Object> indMap = new LinkedHashMap<>();
        indMap.put("date", ind.date());
        indMap.put("close", money(ind.close()));
        indMap.put("atrPct", round4(ind.atrPct()));
        indMap.put("ma5", money(ind.ma5()));
        indMap.put("ma10", money(ind.ma10()));
        indMap.put("ma20", money(ind.ma20()));
        indMap.put("ma20Slope", round4(ind.ma20Slope()));
        indMap.put("rsi14", round4(ind.rsi14()));
        indMap.put("percentB", round4(ind.percentB()));
        indMap.put("volRatio", round4(ind.volRatio()));
        indMap.put("ret5", round4(ind.ret5()));
        indMap.put("ret20", round4(ind.ret20()));
        indMap.put("devMa20", round4(ind.devMa20()));
        indMap.put("avgAmp20", round4(ind.avgAmp20()));

        List<String> warnings = new ArrayList<>();
        warnings.add("计划基于昨日及以前数据生成，不保证成交，也不保证盈利。");
        warnings.add("限价单假设能按限价成交且量足够，实际成交率会低于回测。");
        return new PlanBundle(orders, plan.explanations(), indMap, levels, warnings);
    }

    private Map<String, Object> toCard(Order o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", o.kind());
        m.put("label", switch (o.kind()) {
            case Order.ADD -> "补仓挂单";
            case Order.EXIT -> "回本 / 达标卖出";
            case Order.STOP -> "止损 / 时间止损";
            case Order.BUYBACK -> "遗留回补";
            case Order.PENDING_SELL -> "遗留减仓";
            case Order.FORCE_BUYBACK -> "强制回补";
            case Order.CARRY_ABSORB -> "遗留并入持仓";
            default -> o.kind();
        });
        m.put("side", o.side());
        m.put("price", money(o.limitPrice()));
        m.put("qty", o.qty());
        m.put("occupiedCash", money(o.reservedCash()));
        m.put("expectedNetProfit", null);
        m.put("condition", switch (o.kind()) {
            case Order.ADD -> "次日 open ≤ 挂单价，或 low ≤ 挂单价时成交";
            case Order.EXIT -> "次日 open ≥ 卖出价，或 high ≥ 卖出价时成交";
            case Order.STOP -> "次日开盘按市价全部卖出";
            case Order.BUYBACK, Order.PENDING_SELL -> "次日价格触及挂单价时成交";
            default -> "按规则触发";
        });
        m.put("reason", o.reason());
        return m;
    }

    /** 求最小价格 p 使 qty*p - sellFees(qty*p) >= need。 */
    private double solve(double need, int qty, Params p) {
        if (need <= 0) return 0.01d;
        double lo = 0, hi = Math.max(1, need / Math.max(qty, 1) * 4);
        for (int i = 0; i < 80; i++) {
            double mid = (lo + hi) / 2;
            double amount = qty * mid;
            if (amount - fees.fee(amount, true, p) >= need) hi = mid;
            else lo = mid;
        }
        return Math.ceil(hi * 100) / 100d;
    }

    private static BigDecimal money(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal round4(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP);
    }

    /** 计划结果聚合体。 */
    public record PlanBundle(
            List<Map<String, Object>> orders,
            List<String> explanations,
            Map<String, Object> indicators,
            Map<String, Object> levels,
            List<String> warnings) {
    }
}
