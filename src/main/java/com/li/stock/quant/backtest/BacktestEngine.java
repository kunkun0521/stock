package com.li.stock.quant.backtest;

import com.li.stock.common.BizException;
import com.li.stock.quant.data.MarketPanel;
import com.li.stock.quant.indicator.Indicators;
import com.li.stock.quant.strategy.SignalSeries;
import com.li.stock.quant.strategy.Universe;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * 回测引擎（规格第 7 章）。
 *
 * <p>每日流程：开盘撮合前一日挂单（先卖后买释放资金）→ 盘中价格类止损/止盈 →
 * 收盘估值并更新移动止盈 → 收盘后生成次日挂单。T+1、涨停买不进、跌停卖不出、
 * 停牌顺延、跳空按开盘价成交都在这里统一处理，策略只负责信号。</p>
 *
 * <p>账户恒等：任意交易日净值 = 现金 + 持仓市值，现金不允许为负。</p>
 */
public final class BacktestEngine {

    @FunctionalInterface
    public interface Progress {
        void update(int percent, String stage);
    }

    /** 任务被取消时抛出，由任务层捕获并标记 CANCELED。 */
    public static final class CanceledException extends RuntimeException {
        public CanceledException() {
            super("任务已取消");
        }
    }

    public record Outcome(List<EquityPoint> equity, List<TradeRecord> trades,
                          int startIdx, int endIdx, int signalCount, int openPositions,
                          Map<String, Object> summary) {
    }

    public Outcome run(MarketPanel panel, BacktestConfig cfg, Universe universe,
                       List<SignalSeries> signals, int maxHoldDays,
                       Progress progress, BooleanSupplier cancelled) {
        int startIdx = resolveStart(panel, cfg.start());
        int endIdx = resolveEnd(panel, cfg.end());
        if (startIdx >= endIdx) {
            throw BizException.badRequest("回测区间内没有足够的交易日");
        }

        MarketPanel.Stock[] stocks = panel.stocks();
        SignalSeries[] byStock = new SignalSeries[stocks.length];
        for (SignalSeries s : signals) byStock[s.stockIdx] = s;

        double[] bench = panel.benchmarkNav();
        float[] benchF = new float[bench.length];
        for (int i = 0; i < bench.length; i++) benchF[i] = (float) bench[i];
        double[] benchMa = Indicators.sma(benchF, 60);

        double cash = cfg.capital();
        Map<Integer, Position> positions = new LinkedHashMap<>();
        Map<Integer, String> pendingSell = new LinkedHashMap<>();
        List<Order> pendingBuy = new ArrayList<>();
        Map<Integer, Integer> cooldownUntil = new HashMap<>();
        List<EquityPoint> equity = new ArrayList<>(endIdx - startIdx + 1);
        List<TradeRecord> trades = new ArrayList<>();
        double peakNav = 1;
        int signalCount = 0;

        double slip = cfg.slippagePct() / 100d;
        int totalDays = endIdx - startIdx + 1;

        for (int d = startIdx; d <= endIdx; d++) {
            if (cancelled.getAsBoolean()) throw new CanceledException();

            // ---------------------------------------------------------- 1 开盘撮合
            cash = executeSells(panel, cfg, positions, pendingSell, trades, cash, d, slip, cooldownUntil);
            cash = executeBuys(panel, cfg, positions, pendingBuy, cash, d, slip, byStock);

            // ---------------------------------------------------------- 2 盘中止损 / 移动止盈
            cash = executeStops(panel, cfg, positions, trades, cash, d, slip, cooldownUntil);

            // ---------------------------------------------------------- 3 收盘估值
            double positionValue = 0;
            for (Position pos : positions.values()) {
                MarketPanel.Stock s = stocks[pos.stockIdx];
                int row = s.rowOn(d);
                if (row >= 0 && s.close[row] > 0) {
                    pos.lastClose = s.close[row];
                    pos.highestClose = Math.max(pos.highestClose, s.close[row]);
                    pos.holdDays++;
                    pos.mae = Math.min(pos.mae, s.low[row] / pos.entryPrice - 1);
                    pos.mfe = Math.max(pos.mfe, s.high[row] / pos.entryPrice - 1);
                    SignalSeries sig = byStock[pos.stockIdx];
                    if (sig != null) {
                        double offset = sig.trailOffset[row];
                        if (offset > 0) {
                            pos.trailStop = Math.max(pos.trailStop, pos.highestClose - offset);
                        }
                        double trailPct = sig.trailPct[row];
                        if (trailPct > 0) {
                            pos.trailStop = Math.max(pos.trailStop, pos.highestClose * (1 - trailPct / 100));
                        }
                    }
                }
                positionValue += pos.shares * pos.lastClose;
            }
            double total = cash + positionValue;
            double nav = total / cfg.capital();
            peakNav = Math.max(peakNav, nav);
            equity.add(new EquityPoint(panel.dateAt(d), nav, cash, positionValue,
                    bench[startIdx] > 0 ? bench[d] / bench[startIdx] : 1, nav / peakNav - 1,
                    positions.size()));

            // ---------------------------------------------------------- 4 收盘后信号
            boolean marketOk = marketOk(cfg.marketFilter(), bench, benchMa, d);
            List<Candidate> candidates = new ArrayList<>();
            int[] rows = panel.dayRows()[d];
            for (int packed : rows) {
                int si = MarketPanel.stockOf(packed);
                int row = MarketPanel.rowOf(packed);
                SignalSeries sig = byStock[si];
                if (sig == null) continue;

                Position holding = positions.get(si);
                if (holding != null) {
                    if (sig.exit[row] && !pendingSell.containsKey(si)) {
                        pendingSell.put(si, sig.exitReason[row] == null ? "信号出场" : sig.exitReason[row]);
                    }
                    continue;
                }
                if (!sig.entry[row]) continue;
                if (pendingSell.containsKey(si)) continue;
                int cd = cooldownUntil.getOrDefault(si, -1);
                if (d < cd) continue;
                if (!universe.rowOk(si, row)) continue;
                if (!marketOk) continue;
                if (pendingBuy.stream().anyMatch(o -> o.stockIdx == si)) continue;
                candidates.add(new Candidate(si, row, sig.score[row], stocks[si].amount[row], stocks[si].code));
                signalCount++;
            }

            // 时间止损：持有满 N 个交易日，收盘判定、次日开盘卖出
            if (maxHoldDays > 0) {
                for (Position pos : positions.values()) {
                    if (pos.holdDays >= maxHoldDays && !pendingSell.containsKey(pos.stockIdx)) {
                        pendingSell.put(pos.stockIdx, "时间止损(" + maxHoldDays + "日)");
                    }
                }
            }

            // 入场候选排序：评分降序 → 成交额降序 → code 升序
            candidates.sort(Comparator.comparingDouble(Candidate::score).reversed()
                    .thenComparing(Comparator.comparingDouble(Candidate::amount).reversed())
                    .thenComparing(Candidate::code));
            int expectedOpen = positions.size() - pendingSell.size() + pendingBuy.size();
            int free = cfg.maxPositions() - expectedOpen;
            for (int i = 0; i < candidates.size() && i < Math.max(0, free); i++) {
                pendingBuy.add(new Order(candidates.get(i).stockIdx, candidates.get(i).score,
                        candidates.get(i).row));
            }

            if (progress != null && (d - startIdx) % 20 == 0) {
                int pct = 55 + (int) ((d - startIdx) * 30L / Math.max(1, totalDays));
                progress.update(pct, "回测推进 " + (d - startIdx + 1) + "/" + totalDays + " 交易日");
            }
        }

        // ---------------------------------------------------------- 期末处理
        LocalDate lastDate = panel.dateAt(endIdx);
        if (cfg.forceCloseAtEnd()) {
            for (Iterator<Position> it = positions.values().iterator(); it.hasNext(); ) {
                Position pos = it.next();
                MarketPanel.Stock s = stocks[pos.stockIdx];
                int row = s.rowOn(endIdx);
                double base = row >= 0 && s.close[row] > 0 ? s.close[row] : pos.lastClose;
                double price = base * (1 - slip);
                closePosition(pos, price, lastDate, "期末强平", cfg, trades);
                cash += pos.shares * price - sellFee(pos.shares * price, cfg);
                it.remove();
            }
            double total = cash;
            double nav = total / cfg.capital();
            if (!equity.isEmpty()) {
                equity.set(equity.size() - 1, new EquityPoint(lastDate, nav, cash, 0,
                        equity.get(equity.size() - 1).benchmarkNav(),
                        nav / Math.max(peakNav, nav) - 1, 0));
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("startDate", String.valueOf(panel.dateAt(startIdx)));
        summary.put("endDate", String.valueOf(lastDate));
        summary.put("tradingDays", totalDays);
        summary.put("signalCount", signalCount);
        summary.put("tradeCount", trades.size());
        summary.put("openPositions", positions.size());
        summary.put("dataSnapshot", panel.snapshotKey());
        summary.put("config", cfg.toMap());
        return new Outcome(equity, trades, startIdx, endIdx, signalCount, positions.size(), summary);
    }

    // ------------------------------------------------------------------ 撮合

    private double executeSells(MarketPanel panel, BacktestConfig cfg, Map<Integer, Position> positions,
                                Map<Integer, String> pendingSell, List<TradeRecord> trades,
                                double cash, int day, double slip, Map<Integer, Integer> cooldown) {
        if (pendingSell.isEmpty()) return cash;
        Iterator<Map.Entry<Integer, String>> it = pendingSell.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, String> entry = it.next();
            Position pos = positions.get(entry.getKey());
            if (pos == null) {
                it.remove();
                continue;
            }
            MarketPanel.Stock s = panel.stocks()[pos.stockIdx];
            int row = s.rowOn(day);
            if (row < 0 || !s.tradable[row]) continue;               // 停牌顺延
            double pct = s.preclose[row] > 0 ? (s.open[row] / s.preclose[row] - 1) * 100 : 0;
            boolean oneWordLimitDown = pct <= -s.limitPct(row) + 0.1 && s.high[row] == s.low[row];
            if (oneWordLimitDown) continue;                          // 一字跌停卖不出，顺延

            double price = fillPrice(cfg, s, row) * (1 - slip);
            if (price <= 0) continue;
            double gross = pos.shares * price;
            double fee = sellFee(gross, cfg);
            cash += gross - fee;
            double pnl = gross - fee - pos.cost;
            trades.add(new TradeRecord(trades.size() + 1, s.code, s.market, pos.entryDate, pos.entryPrice,
                    panel.dateAt(day), price, pos.shares, pnl, pos.cost > 0 ? pnl / pos.cost : 0,
                    pos.holdDays, entry.getValue(), pos.entryScore, pos.mae, pos.mfe, pos.entryAmount));
            positions.remove(pos.stockIdx);
            cooldown.put(pos.stockIdx, day + cfg.cooldownDays());
            it.remove();
        }
        return cash;
    }

    private double executeBuys(MarketPanel panel, BacktestConfig cfg, Map<Integer, Position> positions,
                               List<Order> pendingBuy, double cash, int day, double slip,
                               SignalSeries[] byStock) {
        if (pendingBuy.isEmpty()) return cash;
        double equityNow = cash;
        for (Position pos : positions.values()) equityNow += pos.shares * pos.lastClose;

        Iterator<Order> it = pendingBuy.iterator();
        while (it.hasNext()) {
            Order order = it.next();
            if (positions.size() >= cfg.maxPositions()) break;
            MarketPanel.Stock s = panel.stocks()[order.stockIdx];
            int row = s.rowOn(day);
            if (row < 0 || !s.tradable[row]) continue;               // 停牌顺延
            double pct = s.preclose[row] > 0 ? (s.open[row] / s.preclose[row] - 1) * 100 : 0;
            if (pct >= s.limitPct(row) - 0.1) {
                if ("CANCEL".equals(cfg.limitUpAction())) {
                    it.remove();
                }
                continue;                                            // 涨停买不进
            }
            double price = fillPrice(cfg, s, row) * (1 + slip);
            if (price <= 0) {
                it.remove();
                continue;
            }
            double budget = Math.min(equityNow * cfg.maxWeightPct() / 100d, cash * 0.995);
            if (budget < price * 100) {
                it.remove();
                continue;
            }
            double shares = budget / price;
            double amount = shares * price;
            double fee = buyFee(amount, cfg);
            if (amount + fee > cash) {
                shares = (cash - cfg.minCommission()) / (price * (1 + cfg.commissionPct() / 100
                        + cfg.transferPct() / 100));
                amount = shares * price;
                fee = buyFee(amount, cfg);
                if (amount + fee > cash || shares <= 0) {
                    it.remove();
                    continue;
                }
            }
            cash -= amount + fee;
            Position pos = new Position(order.stockIdx, row, day, s.code, s.market, price, shares,
                    amount + fee, order.score, panel.dateAt(day));
            pos.lastClose = price;
            pos.highestClose = price;
            pos.entryAmount = amount;
            applyStops(pos, byStock[order.stockIdx], order.signalRow);
            positions.put(order.stockIdx, pos);
            it.remove();
        }
        return cash;
    }

    /** 按信号日给出的止损参数设置初始风控：固定百分比与绝对价取更近者。 */
    private void applyStops(Position pos, SignalSeries sig, int signalRow) {
        if (sig == null || signalRow < 0 || signalRow >= sig.stopPct.length) return;
        double stop = 0;
        double pct = sig.stopPct[signalRow];
        if (pct > 0) stop = pos.entryPrice * (1 - pct / 100);
        float abs = sig.stopPrice[signalRow];
        if (!Float.isNaN(abs) && abs > 0) {
            stop = stop <= 0 ? abs : Math.max(stop, abs);
        }
        pos.stopPrice = stop;
    }

    private double executeStops(MarketPanel panel, BacktestConfig cfg, Map<Integer, Position> positions,
                                List<TradeRecord> trades, double cash, int day, double slip,
                                Map<Integer, Integer> cooldown) {
        if (positions.isEmpty()) return cash;
        Iterator<Position> it = positions.values().iterator();
        while (it.hasNext()) {
            Position pos = it.next();
            if (pos.entryDay >= day) continue;                        // T+1：买入当日不卖
            MarketPanel.Stock s = panel.stocks()[pos.stockIdx];
            int row = s.rowOn(day);
            if (row < 0 || !s.tradable[row]) continue;
            double threshold = Math.max(pos.stopPrice, pos.trailStop);
            if (threshold <= 0 || s.low[row] > threshold) continue;
            double raw = Math.min(s.open[row], threshold);            // 跳空低开按开盘价
            double price = raw * (1 - slip);
            double gross = pos.shares * price;
            double fee = sellFee(gross, cfg);
            cash += gross - fee;
            double pnl = gross - fee - pos.cost;
            String reason = pos.trailStop > pos.stopPrice ? "移动止盈" : "止损";
            trades.add(new TradeRecord(trades.size() + 1, s.code, s.market, pos.entryDate, pos.entryPrice,
                    panel.dateAt(day), price, pos.shares, pnl, pos.cost > 0 ? pnl / pos.cost : 0,
                    pos.holdDays, reason, pos.entryScore, pos.mae, pos.mfe, pos.entryAmount));
            cooldown.put(pos.stockIdx, day + cfg.cooldownDays());
            it.remove();
        }
        return cash;
    }

    private void closePosition(Position pos, double price, LocalDate date, String reason,
                               BacktestConfig cfg, List<TradeRecord> trades) {
        double gross = pos.shares * price;
        double pnl = gross - sellFee(gross, cfg) - pos.cost;
        trades.add(new TradeRecord(trades.size() + 1, pos.code, pos.market, pos.entryDate, pos.entryPrice,
                date, price, pos.shares, pnl, pos.cost > 0 ? pnl / pos.cost : 0, pos.holdDays, reason,
                pos.entryScore, pos.mae, pos.mfe, pos.entryAmount));
    }

    // ------------------------------------------------------------------ 工具

    private double fillPrice(BacktestConfig cfg, MarketPanel.Stock s, int row) {
        return switch (cfg.fillPrice()) {
            case "CLOSE" -> s.close[row];
            case "VWAP" -> s.volume[row] > 0 ? s.amount[row] / s.volume[row] : s.open[row];
            default -> s.open[row];
        };
    }

    private double buyFee(double amount, BacktestConfig cfg) {
        return Math.max(amount * cfg.commissionPct() / 100d, cfg.minCommission())
                + amount * cfg.transferPct() / 100d;
    }

    private double sellFee(double amount, BacktestConfig cfg) {
        return Math.max(amount * cfg.commissionPct() / 100d, cfg.minCommission())
                + amount * (cfg.stampPct() + cfg.transferPct()) / 100d;
    }

    private boolean marketOk(String filter, double[] bench, double[] benchMa, int day) {
        if (filter == null || "NONE".equals(filter)) return true;
        boolean bull = !Double.isNaN(benchMa[day]) && bench[day] > benchMa[day];
        return "BULL_ONLY".equals(filter) ? bull : !bull;
    }

    private int resolveStart(MarketPanel panel, LocalDate date) {
        int idx = panel.dateIndex(date);
        if (idx < 0) {
            // 非交易日：取之后第一个交易日
            for (int i = 0; i < panel.dateCount(); i++) {
                if (!panel.dateAt(i).isBefore(date)) return i;
            }
            return panel.dateCount() - 1;
        }
        return idx;
    }

    private int resolveEnd(MarketPanel panel, LocalDate date) {
        int idx = panel.dateIndex(date);
        if (idx >= 0) return idx;
        for (int i = panel.dateCount() - 1; i >= 0; i--) {
            if (!panel.dateAt(i).isAfter(date)) return i;
        }
        return 0;
    }

    private record Candidate(int stockIdx, int row, double score, double amount, String code) {
    }

    private record Order(int stockIdx, double score, int signalRow) {
    }

    private static final class Position {
        final int stockIdx;
        final int entryRow;
        final int entryDay;
        final String code;
        final String market;
        final double entryPrice;
        final double shares;
        final double cost;
        final double entryScore;
        final LocalDate entryDate;
        double lastClose;
        double highestClose;
        double stopPrice;
        double trailStop;
        int holdDays;
        double mae;
        double mfe;
        double entryAmount;

        Position(int stockIdx, int entryRow, int entryDay, String code, String market,
                 double entryPrice, double shares, double cost, double entryScore, LocalDate entryDate) {
            this.stockIdx = stockIdx;
            this.entryRow = entryRow;
            this.entryDay = entryDay;
            this.code = code;
            this.market = market;
            this.entryPrice = entryPrice;
            this.shares = shares;
            this.cost = cost;
            this.entryScore = entryScore;
            this.entryDate = entryDate;
            this.lastClose = entryPrice;
            this.highestClose = entryPrice;
        }
    }
}
