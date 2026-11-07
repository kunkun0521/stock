package com.li.stock.domain;

/**
 * 撮合成交结果。ExecutionSimulator 产出，Backtester 转换为 TradeRecord 并写每日快照。
 *
 * @param action        交易动作：INIT / ADD / T_SELL / T_BUY / BUYBACK / SELL_PENDING / EXIT / STOP / CARRY_ABSORB
 * @param price         成交价（已含滑点、已四舍五入到分）
 * @param qty           成交股数
 * @param fee           本笔费用（元）
 * @param realizedPnl   本笔已实现盈亏（完整做T往返才有值，其余为 null）
 * @param note          成交说明
 * @param pendingKind   本笔成交后需要登记的遗留头寸类型，null 表示不登记
 * @param pendingRefPx  遗留头寸参考价
 * @param closePosition 是否清仓（清仓成交当天其余挂单作废）
 * @param clearPending  本笔成交是否清除了已有遗留头寸
 */
public record Fill(
        String action,
        double price,
        int qty,
        double fee,
        Double realizedPnl,
        String note,
        String pendingKind,
        double pendingRefPx,
        boolean closePosition,
        boolean clearPending) {

    public static Fill of(String action, double price, int qty, double fee, String note) {
        return new Fill(action, price, qty, fee, null, note, null, 0d, false, false);
    }

    public Fill withPending(String kind, double refPx) {
        return new Fill(action, price, qty, fee, realizedPnl, note, kind, refPx, closePosition, clearPending);
    }

    public Fill withClearedPending() {
        return new Fill(action, price, qty, fee, realizedPnl, note, pendingKind, pendingRefPx, closePosition, true);
    }
}
