package com.li.stock.service;

import com.li.stock.domain.model.TBar;
import com.li.stock.domain.model.TPositionState;
import com.li.stock.domain.model.TSignal;
import com.li.stock.domain.model.TTradeAction;
import com.li.stock.domain.model.TTradingConfig;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 做T + 成本摊薄策略引擎：只读行情与持仓，产出当日候选信号，不修改状态。
 *
 * <p>优先级从高到低：止损 → 回本离场 → 止盈离场 → 正T买回 → 倒T减仓 →
 * 补仓 → 正T高抛 → 减仓 → 建仓。模拟器每个交易日只执行最高优先级的一条，
 * 避免同日相互冲突的指令。</p>
 *
 * <p>相比 Python 原型补齐了两条关键闭环：正T高抛后回落买回（含超时强制回补），
 * 以及补仓后反弹减掉补仓仓位（倒T低吸高抛）。</p>
 */
@Service
public class TTradingStrategyEngine {

    /** 把任意股数向下取整到整手（A 股最小交易单位 100 股）。 */
    public static int lots(double shares) {
        if (!Double.isFinite(shares) || shares <= 0) {
            return 0;
        }
        // 限制在 1 亿股以内，避免异常资金输入导致 int 溢出
        return (int) Math.min(shares / 100, 1_000_000d) * 100;
    }

    /**
     * @param intraday true 时用当日 high/low 判定是否触及目标价并按目标价成交；
     *                 false 时按收盘价判定、收盘价成交（更保守，无日内假设）
     */
    public List<TSignal> signals(TBar bar, TPositionState state,
                                 TTradingConfig cfg, boolean intraday) {
        List<TSignal> out = new ArrayList<>();
        if (!bar.tradable() || bar.close() <= 0) {
            return out;
        }

        double close = bar.close();
        int position = state.position();
        int sellable = state.sellable();
        double avgCost = state.avgCost();

        // 空仓：只在技术条件满足时重新建仓
        if (position == 0) {
            if (technicalOk(bar, cfg, true)) {
                int shares = lots(state.availableCash() * cfg.initialPositionRatio() / close);
                if (shares < 100) {
                    shares = lots(state.availableCash() / close);
                }
                offer(out, bar, intraday, true, TTradeAction.BUY, shares, close,
                        String.format("空仓且价格站上 MA20、换手达标，动用可用资金 %.0f%% 重新建仓（留出现金做T）",
                                cfg.initialPositionRatio() * 100), 10);
            }
            return out;
        }

        double floating = state.floatingPnlPct(close);
        double diluted = state.dilutedCost();

        // 1. 止损离场：优先于一切加仓/回补动作，避免越跌越补
        if (floating <= cfg.stopLossPct() && sellable > 0) {
            offer(out, bar, intraday, true, TTradeAction.EXIT, sellable,
                    avgCost * (1 + cfg.stopLossPct()),
                    String.format("相对成本浮亏 %.2f%%，触发止损线 %.2f%%，清仓离场",
                            floating * 100, cfg.stopLossPct() * 100), 100);
        }

        // 2. 回本离场：摊薄成本已达标 + 已落袋盈利 + 现价站上回本价
        double costTarget = state.initialAvgCost() > 0
                ? state.initialAvgCost() * (1 - cfg.costReductionTargetPct())
                : 0d;
        boolean costReached = state.initialAvgCost() > 0 && diluted <= costTarget;
        boolean principalRecovered = diluted <= 0;
        if ((costReached || principalRecovered) && state.realizedPnl() > 0
                && state.tradeCount() > 1 && sellable > 0) {
            offer(out, bar, intraday, false, TTradeAction.EXIT, sellable, Math.max(diluted, 0.01),
                    String.format("摊薄成本 %.3f（较初始成本降 %.2f%%），已实现盈利 %.0f 元且现价站上回本价，落袋离场",
                            diluted,
                            state.initialAvgCost() > 0
                                    ? (1 - diluted / state.initialAvgCost()) * 100 : 0d,
                            state.realizedPnl()), 95);
        }

        // 3. 止盈离场
        if (floating >= cfg.exitProfitPct() && sellable > 0) {
            offer(out, bar, intraday, false, TTradeAction.EXIT, sellable,
                    avgCost * (1 + cfg.exitProfitPct()),
                    String.format("相对成本浮盈 %.2f%%，达到止盈线 %.2f%%，清仓锁定收益",
                            floating * 100, cfg.exitProfitPct() * 100), 90);
        }

        // 4. 正T买回：高抛后回落买回（闭环一），超时则按市价强制回补
        if (state.pendingTSellShares() > 0) {
            double target = state.pendingTSellPrice() * (1 - cfg.tBuybackDrop());
            int affordable = lots(state.availableCash() / Math.max(target, 0.01));
            int shares = lots(Math.min(state.pendingTSellShares(), affordable));
            boolean timeout = state.pendingTSellDays() >= cfg.tMaxHoldDays();
            if (timeout) {
                int byClose = lots(Math.min(state.pendingTSellShares(),
                        state.availableCash() / close));
                offer(out, bar, false, true, TTradeAction.BUY_T, byClose, close,
                        String.format("正T高抛已等待 %d 个交易日仍未回落 %.2f%%，按市价回补以防踏空",
                                state.pendingTSellDays(), cfg.tBuybackDrop() * 100), 86);
            } else {
                offer(out, bar, intraday, true, TTradeAction.BUY_T, shares, target,
                        String.format("正T高抛价 %.3f，回落至 %.3f（-%.2f%%）买回 %d 股，价差摊薄成本",
                                state.pendingTSellPrice(), target, cfg.tBuybackDrop() * 100, shares), 85);
            }
        }

        // 5. 倒T减仓：补仓后反弹，减掉补仓部分（闭环二）
        if (state.pendingAddShares() > 0 && sellable > 0) {
            double target = state.pendingAddPrice() * (1 + cfg.reverseReboundPct());
            int shares = Math.min(state.pendingAddShares(), sellable);
            offer(out, bar, intraday, false, TTradeAction.REVERSE_T_REDUCE, shares, target,
                    String.format("补仓价 %.3f 反弹 %.2f%% 至 %.3f，减掉补仓的 %d 股完成倒T",
                            state.pendingAddPrice(), cfg.reverseReboundPct() * 100, target, shares), 80);
        }

        // 6. 补仓摊薄（倒T低吸）：一次只持有未消化的一批补仓
        if (state.pendingAddShares() == 0 && technicalOk(bar, cfg, false) && !state.positionFull(close, cfg)) {
            double room = Math.max(0d, state.totalCapital() * cfg.maxPositionRatio() - position * close);
            double budget = Math.min(state.availableCash() * cfg.addPositionRatio(), room);
            int shares = lots(budget / close);
            double target = avgCost * (1 - cfg.addDropPct());
            offer(out, bar, intraday, true, TTradeAction.ADD, shares, target,
                    String.format("相对成本下跌 %.2f%%，按可用资金 %.0f%% 补仓 %d 股摊薄成本（近20日支撑 %.3f）",
                            floating * 100, cfg.addPositionRatio() * 100, shares, bar.support()), 70);
        }

        // 7. 正T高抛（正T高抛低吸）
        if (floating >= cfg.tProfitPct() && state.pendingTSellShares() == 0 && sellable >= 100) {
            // 小仓位时按比例算不足 100 股，按最小整手 100 股高抛，保证高价股也能做T
            int wanted = Math.max(lots(position * cfg.tSellRatio()), 100);
            int shares = Math.min(wanted, lots(sellable));
            double target = avgCost * (1 + cfg.tProfitPct());
            offer(out, bar, intraday, false, TTradeAction.SELL_T, shares, target,
                    String.format("相对成本浮盈 %.2f%%，高抛 %.0f%% 仓位（%d 股）锁定价差，等待回落买回",
                            floating * 100, cfg.tSellRatio() * 100, shares), 60);
        }

        // 8. 常规减仓
        if (floating >= cfg.reduceProfitPct() && sellable >= 100) {
            int shares = Math.min(lots(position * 0.3), lots(sellable));
            double target = avgCost * (1 + cfg.reduceProfitPct());
            offer(out, bar, intraday, false, TTradeAction.SELL_REDUCE, shares, target,
                    String.format("浮盈 %.2f%% 达到减仓线，减 30%% 仓位降低回撤风险",
                            floating * 100), 50);
        }

        out.sort(Comparator.comparingInt(TSignal::priority).reversed());
        return out;
    }

    /**
     * 价格是否越过目标价 → 生成一条信号。
     * CLOSE 模式按收盘价成交；INTRADAY 模式按目标触发价成交（限价单口径）。
     *
     * @param belowTrigger true 表示「下跌触及」（补仓、买回、跌破止损），
     *                     false 表示「上涨触及」（高抛、反弹减仓、止盈、站上回本价）。
     *                     触发方向与买卖方向无关，不能用 action.buy() 推断。
     */
    private void offer(List<TSignal> out, TBar bar, boolean intraday, boolean belowTrigger,
                       TTradeAction action, int shares, double target,
                       String reason, int priority) {
        if (shares <= 0 || target <= 0) {
            return;
        }
        boolean reached = belowTrigger
                ? (intraday ? bar.low() <= target : bar.close() <= target)
                : (intraday ? bar.high() >= target : bar.close() >= target);
        if (!reached) {
            return;
        }
        double price = intraday ? Math.max(target, 0.01) : bar.close();
        out.add(new TSignal(action, action.buy() ? shares : -shares, price, reason, priority));
    }

    /**
     * 技术过滤：停牌不操作；换手率/振幅在字段缺失（0）时跳过该过滤；
     * 均线过滤只用于建仓——下跌补仓时价格本就在 MA20 下方，若强制过滤会导致永远补不了仓。
     */
    private boolean technicalOk(TBar bar, TTradingConfig cfg, boolean requireMa) {
        if (!bar.tradable()) {
            return false;
        }
        if (requireMa && cfg.useMaFilter() && Double.isFinite(bar.ma20()) && bar.ma20() > 0) {
            if (bar.close() < bar.ma20() * 0.97) {
                return false;
            }
        }
        if (bar.turnover() > 0 && bar.turnover() < cfg.minTurnover()) {
            return false;
        }
        return !(bar.amplitude() > 0 && bar.amplitude() > cfg.maxAmplitude());
    }
}
