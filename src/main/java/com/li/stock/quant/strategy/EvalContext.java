package com.li.stock.quant.strategy;

import com.li.stock.quant.data.MarketPanel;

import java.util.Map;

/**
 * 策略求值上下文：行情面板 + 股票池 + 参数 + 是否输出条件明细。
 *
 * <p>横截面排名（PE/PB 分位）按需惰性计算并缓存在上下文中，同一轮回测内只算一次。</p>
 */
public final class EvalContext {

    public final MarketPanel panel;
    public final Universe universe;
    public final Map<String, Object> params;
    public final boolean explain;

    private volatile float[][] peRankCache;
    private volatile float[][] pbRankCache;

    public EvalContext(MarketPanel panel, Universe universe, Map<String, Object> params, boolean explain) {
        this.panel = panel;
        this.universe = universe;
        this.params = params;
        this.explain = explain;
    }

    public int i(String key) {
        Object v = params.get(key);
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return 0;
        }
    }

    public double d(String key) {
        Object v = params.get(key);
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (Exception e) {
            return 0;
        }
    }

    public boolean b(String key) {
        Object v = params.get(key);
        if (v instanceof Boolean bool) return bool;
        return "true".equalsIgnoreCase(String.valueOf(v)) || "1".equals(String.valueOf(v));
    }

    /** PE 横截面百分位（仅 pe_ttm &gt; 0 参与）。 */
    public float[][] peRank() {
        float[][] local = peRankCache;
        if (local != null) return local;
        synchronized (this) {
            if (peRankCache == null) {
                peRankCache = CrossSection.percentile(panel, universe,
                        (s, r) -> s.pe[r] > 0 ? s.pe[r] : Float.NaN);
            }
            return peRankCache;
        }
    }

    /** PB 横截面百分位（仅 pb_mrq &gt; 0 参与）。 */
    public float[][] pbRank() {
        float[][] local = pbRankCache;
        if (local != null) return local;
        synchronized (this) {
            if (pbRankCache == null) {
                pbRankCache = CrossSection.percentile(panel, universe,
                        (s, r) -> s.pb[r] > 0 ? s.pb[r] : Float.NaN);
            }
            return pbRankCache;
        }
    }
}
