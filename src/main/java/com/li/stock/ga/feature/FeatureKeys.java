package com.li.stock.ga.feature;

import java.util.List;

/**
 * 特征集 v1 的有序 key 列表（规格 4.1）。
 *
 * <p><b>顺序即基因位置</b>：{@code ga_run.feature_set_json} 持久化本列表，
 * 个体染色体 w[0..N-1] / m[0..N-1] 与之严格一一对应，任何新增特征只能追加到末尾并升级 feature_set_ver。</p>
 *
 * <p>列名同时是 {@code stock_feature_daily} 的列名，见 ga_schema.sql。</p>
 */
public final class FeatureKeys {

    public static final String VERSION = "v1";

    /** 个股特征：在每个交易日的股票池截面内缩尾 + z-score。 */
    public static final List<String> STOCK_FEATURES = List.of(
            "ret_1", "ret_3", "ret_5", "ret_10",
            "ret_21", "ret_63", "ret_126", "ret_252",
            "rel_ret_5",
            "ma_gap_5", "ma_gap_10", "ma_gap_20", "ma_gap_60",
            "ma_slope_20", "macd_hist", "boll_pct_20", "adx_14",
            "rsi_6", "rsi_14", "kdj_j", "cci_14",
            "vol_20", "vol_60", "atr_14_pct", "amp_5",
            "dd_20", "drawdown_252", "downvol_20", "beta_60",
            "vr_5_20", "amount_ratio_20", "turnover_20",
            "turn_5", "turn_z_20", "obv_slope_10", "amihud_20",
            "gap_pct", "body_pct", "upper_shadow", "lower_shadow", "close_pos",
            "up_streak", "limit_cnt_10",
            "ep", "bp", "sp", "cfp", "loss_flag",
            "total_score", "value_score", "momentum_score", "trend_score",
            "risk_score", "liquidity_score");

    /** 市场环境特征：同一天全池取值相同，按自身滚动 252 日时间序列 z-score。 */
    public static final List<String> MARKET_FEATURES = List.of(
            "mkt_ret_5", "mkt_vol_20", "breadth_up", "regime_code");

    public static final List<String> ALL;

    static {
        List<String> all = new java.util.ArrayList<>(STOCK_FEATURES);
        all.addAll(MARKET_FEATURES);
        ALL = List.copyOf(all);
    }

    public static final int N = ALL.size();
    public static final int STOCK_N = STOCK_FEATURES.size();

    private static final java.util.Map<String, Integer> INDEX = java.util.Map.copyOf(
            java.util.stream.IntStream.range(0, N).boxed().collect(
                    java.util.stream.Collectors.toMap(ALL::get, i -> i)));

    public static int indexOf(String key) {
        Integer i = INDEX.get(key);
        return i == null ? -1 : i;
    }

    public static boolean contains(String key) {
        return INDEX.containsKey(key);
    }

    /** 先验个体使用的因子下标（规格 6.5：动量、趋势为正，风险为负）。 */
    public static final int IDX_MOMENTUM_SCORE = indexOf("momentum_score");
    public static final int IDX_TREND_SCORE = indexOf("trend_score");
    public static final int IDX_RISK_SCORE = indexOf("risk_score");
    public static final int IDX_RET_21 = indexOf("ret_21");
    public static final int IDX_VOL_20 = indexOf("vol_20");
    public static final int IDX_DRAWDOWN_252 = indexOf("drawdown_252");

    private FeatureKeys() {}
}
