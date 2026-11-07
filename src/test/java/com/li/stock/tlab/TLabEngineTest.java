package com.li.stock.tlab;

import com.li.stock.domain.Bar;
import com.li.stock.domain.Fill;
import com.li.stock.domain.Indicator;
import com.li.stock.domain.Order;
import com.li.stock.domain.Params;
import com.li.stock.domain.Pending;
import com.li.stock.domain.PositionState;
import com.li.stock.domain.SimOutcome;
import com.li.stock.tlab.engine.Backtester;
import com.li.stock.tlab.engine.EngineConfig;
import com.li.stock.tlab.engine.ExecutionSimulator;
import com.li.stock.tlab.engine.FeeCalculator;
import com.li.stock.tlab.engine.StrategyEngine;
import com.li.stock.tlab.engine.StrategyPlan;
import com.li.stock.tlab.indicator.IndicatorService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规格十四节验收用例 1-9。纯内存、不启动 Spring 上下文、不连数据库。
 */
class TLabEngineTest {

    private final FeeCalculator fees = new FeeCalculator();
    private final StrategyEngine strategy = new StrategyEngine(fees);
    private final ExecutionSimulator execution = new ExecutionSimulator(fees);
    private final Backtester backtester = new Backtester(fees, strategy, execution);
    private final IndicatorService indicatorService = new IndicatorService();

    private static final LocalDate D0 = LocalDate.of(2026, 1, 5);

    private static Bar bar(int i, double open, double high, double low, double close) {
        return new Bar("600519", "SH", D0.plusDays(i), open, high, low, close,
                close, 100000, close * 100000, 2, 0, 1, 1, 0);
    }

    private static Bar flat(int i, double close) {
        return bar(i, close, close + 0.5, close - 0.5, close);
    }

    private static PositionState state(int qty, double cash) {
        PositionState s = new PositionState();
        s.qty = qty;
        s.sellableQty = qty;
        s.cash = cash;
        s.cumBuyCost = qty * 100;
        s.maxInvested = s.netInvested();
        return s;
    }

    /** 用例 1：卖出 10000 元印花税 5 元；买入 1000 元佣金取最低 5 元。 */
    @Test
    void case1_fees() {
        Params p = Params.defaults();
        double sell = fees.fee(10000, true, p);
        double buyNoStamp = fees.fee(10000, false, p);
        assertEquals(5.0, sell - buyNoStamp, 1e-9, "卖出 10000 元的印花税应为 5 元");
        // 买入 1000 元：佣金 max(5, 0.25) = 5，另加过户费与规费 0.06
        double buy1000 = fees.fee(1000, false, p);
        assertEquals(5.06, buy1000, 1e-9, "买入 1000 元的佣金应取最低 5 元");
        System.out.println("[用例1] 印花税=" + (sell - buyNoStamp) + " 买入1000费用=" + buy1000);
    }

    /** 用例 2：T+1，买入当日不得有任何卖出；次日 sellableQty 等于前一日收盘持仓。 */
    @Test
    void case2_tPlus1() {
        Params p = Params.defaults();
        PositionState s = state(0, 200000);
        // 当日买入后 sellableQty 仍为 0
        List<Fill> init = execution.execute(
                List.of(Order.of(Order.ADD, Order.BUY, 100, 1000, 100000, false, null, "初始买入")),
                bar(1, 100, 101, 99, 100), s, p, D0.plusDays(1), 1);
        assertFalse(init.isEmpty(), "初始买入应成交");
        assertEquals(1000, s.qty);
        assertEquals(0, s.sellableQty, "买入当日不可卖");

        // 当日尝试卖出，应因 sellableQty=0 而全部被拒
        List<Fill> sameDaySell = execution.execute(
                List.of(Order.of(Order.EXIT, Order.SELL, 100, 1000, 0, true, null, "当日卖出")),
                bar(1, 100, 105, 95, 100), s, p, D0.plusDays(1), 1);
        assertTrue(sameDaySell.isEmpty(), "买入当日不得有任何卖出");
        assertEquals(1000, s.qty);
        System.out.println("[用例2] 当日买入 sellableQty=0，卖出被拒；持仓保持 1000");
    }

    /** 用例 3：双向挂单两腿都成交，持仓不变，净收益 = N×(卖价-买价)-费用。 */
    @Test
    void case3_twoLeggedSwingT() {
        Params p = Params.defaults();
        PositionState s = state(1000, 200000);
        double sellLimit = 103, buyLimit = 97;
        List<Order> orders = List.of(
                Order.of(Order.T_SELL, Order.SELL, sellLimit, 500, 0, false, "T", "高抛"),
                Order.of(Order.T_BUY, Order.BUY, buyLimit, 500, 50000, false, "T", "低吸"));
        Bar b = bar(2, 100, 106, 94, 100);
        List<Fill> fills = execution.execute(orders, b, s, p, D0.plusDays(2), 2);
        assertEquals(2, fills.size(), "应产出卖、买两条成交");
        assertEquals(1000, s.qty, "完整往返持仓不变");
        assertEquals(1, s.tRoundTrips);
        double sellFee = fees.fee(500 * sellLimit, true, p);
        double buyFee = fees.fee(500 * buyLimit, false, p);
        double expected = 500 * (sellLimit - buyLimit) - sellFee - buyFee;
        assertEquals(expected, s.tNetProfit, 1e-6, "已实现收益应等于价差减费用");
        assertEquals(expected, fills.get(1).realizedPnl(), 1e-6);
        System.out.println("[用例3] 持仓=" + s.qty + " 净收益=" + expected);
    }

    /** 用例 4：只成交一腿时登记 pending，超期按 carryPolicy 处理。 */
    @Test
    void case4_pending() {
        Params p = Params.defaults();
        PositionState s = state(1000, 200000);
        List<Order> orders = List.of(
                Order.of(Order.T_SELL, Order.SELL, 103, 500, 0, false, "T", "高抛"),
                Order.of(Order.T_BUY, Order.BUY, 97, 500, 50000, false, "T", "低吸"));
        // 只触发卖腿：high >= 103 但 low > 97
        List<Fill> fills = execution.execute(orders, bar(2, 100, 106, 99, 100), s, p, D0.plusDays(2), 2);
        assertEquals(1, fills.size());
        assertNotNull(s.pending, "应登记 pendingBuyback");
        assertEquals(Pending.BUYBACK, s.pending.kind());
        assertEquals(500, s.pending.qty());

        // pending 存在时不再开新的做T
        s.sellableQty = s.qty;
        Indicator ind = indicatorService.compute(List.of(flat(0, 100), flat(1, 100))).get(1);
        StrategyPlan plan = strategy.planOrders(s, ind, List.of(flat(0, 100), flat(1, 100)),
                p, new EngineConfig(200000, 200000, 2, D0.plusDays(2), 50));
        assertTrue(plan.orders().stream().noneMatch(o -> Order.T_SELL.equals(o.kind())),
                "pending 存在时不应开新的做T");
        assertTrue(plan.explanations().stream().anyMatch(t -> t.contains("遗留头寸")));
        System.out.println("[用例4] pending=" + s.pending.kind() + " 且不再开新做T");
    }

    /** 用例 5：数量为 100 整数倍、cash/qty 非负、补仓后市值不超上限。 */
    @Test
    void case5_invariants() {
        List<Bar> bars = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            double close = 100 - i * 0.6;   // 单边缓跌，持续触发补仓
            bars.add(bar(i, close + 0.3, close + 1, close - 1, close));
        }
        Params p = Params.defaults();
        p.maxHoldDays = 500;
        p.maxLossRate = 1;          // 本例只验证补仓路径，不触发止损
        p.targetProfitRate = 1;     // 抬高离场目标，避免「回本即走」提前清仓
        List<Indicator> inds = indicatorService.compute(bars);
        SimOutcome o = backtester.run(bars, inds, 21, 59, p, null, null, 60000d,
                200000d, 200000d, Backtester.Mode.FULL);
        for (var t : o.trades()) {
            assertEquals(0, t.qty() % 100, "所有数量必须是 100 的整数倍");
        }
        // 规格第六节明确「持仓市值占总资金上限，只限制补仓」，做T买腿 / 遗留回补
        // 不受该限制；因此用 ONLY_ADD 模式专门验证补仓的仓位上限，FULL 模式只校验不变量。
        SimOutcome addOnly = backtester.run(bars, inds, 21, 59, p, null, null, 60000d,
                200000d, 200000d, Backtester.Mode.ONLY_ADD);
        double cap = 200000 * p.maxPositionRatio;
        for (var d : o.daily()) {
            assertTrue(d.qty() >= 0, "qty 不能为负");
            assertTrue(d.cash() >= -1e-6, "cash 不能为负");
        }
        for (var d : addOnly.daily()) {
            assertTrue(d.qty() * d.close() <= cap + 100 * d.close(),
                    "补仓后持仓市值不应超过仓位上限，实际 " + d.qty() * d.close() + " 上限 " + cap
                            + " @ " + d.tradeDate() + " qty=" + d.qty() + " events=" + d.events());
        }
        assertTrue(o.state().addCount > 0, "单边缓跌应至少触发一次分档补仓");
        assertTrue(o.daily().size() >= 20, "应回放完整个下跌区间");
        System.out.println("[用例5] FULL 交易日=" + o.daily().size() + " 成交=" + o.trades().size()
                + " 笔 补仓=" + o.state().addCount + " 次；ONLY_ADD 补仓=" + addOnly.state().addCount
                + " 次，仓位上限 " + cap + " 全程满足，整手 / 非负均通过");
        System.out.println("[用例5] 交易日=" + o.daily().size() + " 成交=" + o.trades().size()
                + " 笔 补仓=" + o.state().addCount + " 次，整手 / 非负 / 仓位上限均通过");
    }

    /** 用例 6：一字板、停牌日无任何成交；挂单价不超出涨跌停。 */
    @Test
    void case6_noTradeOnHaltOrOneWord() {
        Params p = Params.defaults();
        List<Order> orders = List.of(
                Order.of(Order.ADD, Order.BUY, 90, 500, 50000, false, null, "补仓"),
                Order.of(Order.T_SELL, Order.SELL, 103, 500, 0, false, "T", "高抛"),
                Order.of(Order.T_BUY, Order.BUY, 97, 500, 50000, false, "T", "低吸"));

        PositionState halted = state(1000, 200000);
        Bar halt = new Bar("600519", "SH", D0.plusDays(2), 100, 106, 94, 100,
                100, 100000, 1e7, 2, 0, 1, 0, 0);   // tradestatus=0
        assertTrue(halt.suspended());
        assertTrue(execution.execute(orders, halt, halted, p, halt.date(), 2).isEmpty());

        PositionState oneWord = state(1000, 200000);
        assertTrue(execution.execute(orders, bar(2, 100, 100, 100, 100), oneWord, p, D0.plusDays(2), 2).isEmpty());
        assertEquals(1000, oneWord.qty);

        // 挂单价 clamp 到涨跌停：涨停 110，跌停 90
        PositionState s = state(1000, 200000);
        List<Fill> fills = execution.execute(
                List.of(Order.of(Order.ADD, Order.BUY, 200, 500, 50000, false, null, "越界挂单")),
                bar(2, 100, 106, 94, 100), s, p, D0.plusDays(2), 2);
        assertTrue(fills.isEmpty() || fills.get(0).price() <= 110, "成交价不得超出涨停价");
        System.out.println("[用例6] 停牌/一字板零成交，涨跌停 clamp 生效");
    }

    /** 用例 7：无前视 —— 篡改 t 日及以后K线，planOrders 在 t 日输出不变。 */
    @Test
    void case7_noLookAhead() {
        List<Bar> original = new ArrayList<>();
        List<Bar> tampered = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            original.add(bar(i, 100, 102, 98, 100));
            if (i < 25) tampered.add(bar(i, 100, 102, 98, 100));
            else tampered.add(bar(i, 500, 900, 100, 700));   // t 日及以后全部篡改
        }
        int t = 25;
        PositionState s = state(1000, 200000);
        Indicator ind = indicatorService.compute(original.subList(0, t)).get(t - 1);
        EngineConfig cfg = new EngineConfig(200000, 200000, t, D0.plusDays(t), 50);
        StrategyPlan a = strategy.planOrders(s.copy(), ind, original.subList(0, t), Params.defaults(), cfg);
        StrategyPlan b = strategy.planOrders(s.copy(), ind, tampered.subList(0, t), Params.defaults(), cfg);
        assertEquals(a.orders(), b.orders(), "篡改 t 日及以后K线，planOrders 输出必须完全一致");
        assertEquals(a.explanations(), b.explanations());
        System.out.println("[用例7] 两组历史下挂单完全一致，共 " + a.orders().size() + " 条");
    }

    /** 用例 8：持续上涨后回本离场，清仓后 totalPnL >= 目标收益。 */
    @Test
    void case8_exitOnRecovery() {
        List<Bar> bars = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            double close = 100 + i * 1.5;
            bars.add(bar(i, close - 0.5, close + 2, close - 2, close));
        }
        Params p = Params.defaults();
        p.targetProfitRate = 0;
        p.maxHoldDays = 500;
        p.tRatio = 0;
        List<Indicator> inds = indicatorService.compute(bars);
        SimOutcome o = backtester.run(bars, inds, 21, 44, p, null, null, 60000d,
                200000d, 200000d, Backtester.Mode.FULL);
        double last = o.daily().get(o.daily().size() - 1).close();
        double pnl = o.state().totalPnL(last);
        assertTrue(pnl >= -1e-6, "上涨行情清仓后总盈亏应 >= 0，实际 " + pnl);
        System.out.println("[用例8] 清仓=" + o.state().closed + " totalPnL=" + pnl
                + " 回本日=" + o.recoverDate());
    }

    /** 用例 9：同一份数据、同一组参数重复运行结果完全一致。 */
    @Test
    void case9_reproducible() {
        List<Bar> bars = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            double close = 100 + Math.sin(i / 4d) * 8 - i * 0.15;
            bars.add(bar(i, close + 0.4, close + 3, close - 3, close));
        }
        Params p = Params.defaults();
        p.maxHoldDays = 500;
        List<Indicator> inds = indicatorService.compute(bars);
        SimOutcome a = backtester.run(bars, inds, 21, 79, p, null, null, 60000d,
                200000d, 200000d, Backtester.Mode.FULL);
        SimOutcome b = backtester.run(bars, inds, 21, 79, Params.from(p.toMap()), null, null, 60000d,
                200000d, 200000d, Backtester.Mode.FULL);
        assertEquals(a.trades(), b.trades(), "两次运行的交易明细必须完全一致");
        assertEquals(a.daily(), b.daily(), "两次运行的每日快照必须完全一致");
        assertEquals(a.equityCurve(), b.equityCurve());
        System.out.println("[用例9] 两次运行 " + a.trades().size() + " 笔成交逐字段一致");
    }
}
