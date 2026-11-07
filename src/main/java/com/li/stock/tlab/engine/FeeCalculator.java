package com.li.stock.tlab.engine;

import com.li.stock.domain.Params;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 规格 5.4：费用、滑点、往返成本与价格取整。纯函数、无状态，便于单元测试。
 */
@Component
public class FeeCalculator {

    /** 价格最小变动 0.01 元，四舍五入到分。 */
    public double round2(double value) {
        if (!Double.isFinite(value)) return 0d;
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * fee(amount, isSell) = max(minCommission, amount * commissionRate)
     * + amount * (transferFeeRate + otherFeeRate)
     * + (isSell ? amount * stampTaxRate : 0)
     */
    public double fee(double amount, boolean isSell, Params p) {
        double commission = Math.max(p.minCommission, amount * p.commissionRate);
        double result = commission + amount * (p.transferFeeRate + p.otherFeeRate);
        if (isSell) result += amount * p.stampTaxRate;
        return result;
    }

    /** 一个完整往返的成本率：两边佣金 + 印花税 + 两边过户费和规费，默认约 0.11%。 */
    public double roundTripCostRate(Params p) {
        return p.roundTripCostRate();
    }

    /**
     * 市价类成交滑点：买入加、卖出减。slippageBp=1 表示 0.01%。
     * 限价成交按限价，不调用本方法。
     */
    public double slip(double price, boolean isBuy, Params p) {
        double factor = p.slippageBp / 10000d;
        return round2(isBuy ? price * (1 + factor) : price * (1 - factor));
    }

    /** 向下取整到 100 股整数倍，不足 100 股返回 0。 */
    public int roundLot(double rawQty) {
        if (!Double.isFinite(rawQty) || rawQty < 100) return 0;
        return (int) (Math.floor(rawQty / 100d) * 100);
    }
}
