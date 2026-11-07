package com.li.stock.service;

import com.li.stock.common.BizException;
import com.li.stock.domain.model.TBar;
import com.li.stock.domain.model.TPositionState;
import com.li.stock.domain.model.TSignal;
import com.li.stock.domain.model.TTradeAction;
import com.li.stock.domain.model.TTradingConfig;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 做T模拟执行器：逐交易日推进状态机，撮合策略引擎给出的最高优先级信号。
 * <p>所有成交都经过资金、整手、T+1、费用四重复核；首日按用户指定的买入日期无条件建仓，
 * 后续只在信号触发时操作。清仓后允许按技术条件重新建仓，直到数据结束。</p>
 */
@Service
public class TTradingSimulator {

    private final TTradingStrategyEngine engine;

    public TTradingSimulator(TTradingStrategyEngine engine) {
        this.engine = engine;
    }

    public SimulationResult run(List<TBar> bars, double totalCapital, double availableCash,
                                int initialShares, double initialAvgCost,
                                TTradingConfig cfg, boolean intraday) {
        if (bars == null || bars.isEmpty()) {
            throw BizException.badRequest("所选区间没有日线数据，请调整买入日期或复权口径");
        }

        TPositionState state = new TPositionState(totalCapital, availableCash,
                initialShares, initialAvgCost);
        double peak = state.totalAssets(bars.get(0).close());
        double maxDrawdown = 0d;

        for (int i = 0; i < bars.size(); i++) {
            TBar bar = bars.get(i);
            state.newDay();
            String label = null;

            if (i == 0 && initialShares == 0) {
                int shares = TTradingStrategyEngine.lots(
                        availableCash * cfg.initialPositionRatio() / bar.close());
                // 高价股按目标比例可能不足一手：退化为按全部可用现金买最小整手，
                // 避免因「必须留补仓弹药」而直接拒绝整次模拟。
                boolean fallbackToFullCash = shares < 100;
                if (fallbackToFullCash) {
                    shares = TTradingStrategyEngine.lots(availableCash / bar.close());
                }
                while (shares >= 100 && cfg.buyCost(bar.close(), shares) > state.availableCash() + 1e-6) {
                    shares -= 100;
                }
                if (shares < 100) {
                    throw BizException.badRequest(String.format(
                            "可用资金不足：首日收盘价 %.3f 元，买 100 股至少需要 %.2f 元",
                            bar.close(), cfg.buyCost(bar.close(), 100)));
                }
                String reason = fallbackToFullCash
                        ? "高价股按目标建仓比例不足一手，按可用资金买入最小整手（后续做T与补仓空间有限）"
                        : String.format("按指定买入日期以收盘价初始建仓（动用可用资金 %.0f%%，留出现金做T与补仓）",
                                cfg.initialPositionRatio() * 100);
                state.applyBuy(bar.tradeDate(), TTradeAction.BUY, bar.close(), shares, reason, cfg);
                label = TTradeAction.BUY.label();
            } else if (i > 0) {
                List<TSignal> signals = engine.signals(bar, state, cfg, intraday);
                if (!signals.isEmpty()) {
                    label = execute(state, bar.tradeDate(), signals.get(0), cfg);
                }
            }

            double assets = state.totalAssets(bar.close());
            peak = Math.max(peak, assets);
            double drawdown = peak > 0 ? (assets / peak - 1d) * 100d : 0d;
            maxDrawdown = Math.min(maxDrawdown, drawdown);
            state.addEquity(new TPositionState.EquityPoint(bar.tradeDate(), bar.close(), bar.ma20(),
                    state.avgCost(), state.dilutedCost(), state.availableCash(),
                    state.position(), assets, drawdown, label));
        }
        return new SimulationResult(state, maxDrawdown, bars.get(bars.size() - 1));
    }

    /** 按实际成交价复核股数与资金，返回真正执行的动作名（未执行返回 null）。 */
    private String execute(TPositionState state, LocalDate date, TSignal signal, TTradingConfig cfg) {
        double price = signal.price();
        TTradeAction action = signal.action();
        int want = Math.abs(signal.shares());
        if (price <= 0 || want <= 0) {
            return null;
        }

        if (action.buy()) {
            int shares = TTradingStrategyEngine.lots(Math.min(want,
                    state.availableCash() / (price * 1.002)));
            while (shares >= 100 && cfg.buyCost(price, shares) > state.availableCash() + 1e-6) {
                shares -= 100;
            }
            if (shares < 100) {
                return null;
            }
            state.applyBuy(date, action, price, shares, signal.reason(), cfg);
            return action.label();
        }

        // 卖出：T+1 可卖数量复核；离场/倒T减仓允许卖出零股，其余保持整手
        int shares = Math.min(want, state.sellable());
        if (action != TTradeAction.EXIT && action != TTradeAction.REVERSE_T_REDUCE) {
            shares = TTradingStrategyEngine.lots(shares);
        }
        if (shares <= 0) {
            return null;
        }
        state.applySell(date, action, price, shares, signal.reason(), cfg);
        return action.label();
    }

    /** 一次模拟的内部产物。 */
    public record SimulationResult(TPositionState state, double maxDrawdownPct, TBar lastBar) {
    }
}
