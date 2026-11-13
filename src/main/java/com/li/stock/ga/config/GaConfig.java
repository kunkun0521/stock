package com.li.stock.ga.config;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GA 参数配置（规格 0 / 8.1）。
 *
 * <p><b>所有阈值、权重、GA 参数都写进 ga_config，代码里只放默认值</b>：
 * 启动时 {@link #ensureDefaults()} 用 INSERT IGNORE 补齐缺省行（不覆盖用户已改的值），
 * 读取时周期级（H1/H3/H5/H10/H15）覆盖 GLOBAL，修改后下一次任务生效，无需重启。</p>
 */
@Service
public class GaConfig {

    /** 默认值 + 中文说明；顺序即配置页展示顺序。 */
    public static final Map<String, String> DESCRIPTIONS = new LinkedHashMap<>();

    private static void put(String key, String value, String desc) {
        DEFAULTS.put(key, value);
        DESCRIPTIONS.put(key, desc);
    }

    private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();

    static {
        put("adjust_flag", "2", "行情/评分表统一使用的复权口径数值（不做转换）");
        put("universe_prefixes", "00,60,30", "股票池代码前缀白名单");
        put("horizons", "1,3,5,10,15", "预测周期（交易日），每个周期独立种群与冠军");
        put("min_labeled_days", "150", "一次进化至少需要的有标签交易日 T_min");
        put("floors", "1:0.002,3:0.004,5:0.005,10:0.008,15:0.010", "各周期噪声阈值下限 floor_h");
        put("noise_vol_coef", "0.25", "θ_h = max(floor_h, coef × vol_20 × √h)");
        put("label_mode", "ABSOLUTE", "ABSOLUTE / EXCESS（减去同日池内等权收益）");
        put("sample_per_day", "600", "每个交易日随机采样的股票数");
        put("random_seed", "20261112", "运行级随机种子，保证可复现");
        put("train_days", "120", "每折训练段交易日数");
        put("valid_days", "30", "每折验证段交易日数");
        put("fold_step", "30", "折间步长（交易日）");
        put("folds", "3", "滚动折数 K，数据不足依次降到 2、1");
        put("min_train_days", "60", "数据不足时训练段可缩短到的下限");
        put("holdout_days", "20", "Hold-out 有标签交易日数，进化全程不可见");
        put("pop_size", "200", "种群规模");
        put("max_gen", "60", "最大代数");
        put("patience", "10", "早停：连续 N 代最优 F 提升小于 min_improve");
        put("min_improve", "0.0001", "早停提升阈值");
        put("tournament_k", "3", "锦标赛选择抽签人数");
        put("elite_ratio", "0.05", "精英直接进入下一代的比例");
        put("crossover_pc", "0.8", "交叉概率");
        put("blx_alpha", "0.3", "BLX-α 的 α");
        put("pm_w", "0.15", "实数基因变异率");
        put("pm_m", "0.03", "特征开关位变异率");
        put("immigrant_ratio", "0.10", "每代随机移民比例");
        put("diversity_threshold", "0.05", "多样性阈值，低于则临时加倍变异率与移民");
        put("init_random", "0.50", "初始种群：随机个体比例");
        put("init_prior", "0.30", "初始种群：因子先验个体比例");
        put("init_champion", "0.20", "初始种群：上届冠军变异体比例（热启动）");
        put("min_active_features", "3", "个体至少启用的特征数");
        put("cull_ratio", "0.30", "每代末位淘汰比例");
        put("cov_target", "0.30", "适应度覆盖率目标");
        put("cov_min", "0.10", "覆盖率低于此值 F_seg = 0");
        put("fit_w_a", "0.40", "F_seg 权重：平衡准确率项 A");
        put("fit_w_b", "0.25", "F_seg 权重：IC 项 B");
        put("fit_w_s", "0.20", "F_seg 权重：稳定性项 S");
        put("fit_w_r", "0.15", "F_seg 权重：Brier 项 R");
        put("overfit_penalty", "0.50", "F = F_va − k × max(0, F_tr − F_va) 的 k");
        put("complexity_penalty", "0.05", "复杂度惩罚系数（× Σm/N）");
        put("fit_a_scale", "0.15", "A = clip((BalAcc−0.5)/scale,0,1)");
        put("fit_b_scale", "0.06", "B = clip(IC/scale,0,1)");
        put("fit_r_base", "0.25", "R = clip((base−Brier)/scale,0,1) 的 base");
        put("fit_r_scale", "0.02", "R 项的 scale");
        put("stability_block_days", "10", "稳定性分块大小（交易日）");
        put("hof_size", "20", "名人堂个体数");
        put("candidate_k", "5", "候选集个体数 K");
        put("candidate_dedup_corr", "0.90", "候选打分相关系数高于此值只留 F 高者");
        put("holdout_min_balacc", "0.52", "有现任冠军时的 Hold-out 准入 BalAcc");
        put("holdout_min_first", "0.51", "首次无冠军时的 Hold-out 准入 BalAcc");
        put("shadow_days", "5", "影子运行交易日数");
        put("shadow_tolerance", "0.01", "影子 BalAcc 不低于现任冠军减此值即可晋升");
        put("softmax_temp", "0.10", "集成权重 softmax(F/T) 的温度 T");
        put("calib_a", "1", "Platt 校准斜率 a");
        put("calib_b", "0", "Platt 校准截距 b");
        put("calib_min_samples", "500", "重新拟合校准所需的最少已验证样本");
        put("retire_window", "60", "冠军衰减判断窗口（交易日）");
        put("retire_min_samples", "3000", "冠军衰减判断最少样本数");
        put("retire_balacc", "0.50", "冠军衰减 BalAcc 阈值");
        put("pool_poor_window", "60", "POOR_PREDICTABILITY 评估窗口（基准日）");
        put("pool_poor_min_samples", "40", "POOR_PREDICTABILITY 最少样本数");
        put("pool_poor_hit", "0.48", "POOR_PREDICTABILITY 淘汰命中率阈值");
        put("pool_restore_window", "20", "复活评估的影子预测窗口");
        put("pool_restore_samples", "20", "复活评估最少影子样本数");
        put("pool_restore_hit", "0.52", "复活命中率阈值");
        put("pool_cooldown_days", "20", "同一股票自动淘汰/复活的最小间隔（交易日）");
        put("pool_suspend_window", "20", "停牌评估窗口");
        put("pool_suspend_ratio", "0.30", "SUSPENDED 淘汰阈值");
        put("pool_suspend_restore", "0.10", "SUSPENDED 复活阈值");
        put("exclude_st", "1", "是否排除 ST");
        put("new_listing_days", "120", "NEW_LISTING 最少有效交易日");
        put("low_liquidity", "20000000", "LOW_LIQUIDITY 20 日均额淘汰阈值（元）");
        put("low_liquidity_restore", "30000000", "LOW_LIQUIDITY 复活阈值（迟滞防抖）");
        put("extreme_vol_q", "0.99", "EXTREME_VOL 淘汰的 vol_20 截面分位");
        put("extreme_vol_restore_q", "0.95", "EXTREME_VOL 复活的截面分位");
        put("data_missing_window", "5", "DATA_MISSING 评估窗口");
        put("data_missing_ratio", "0.20", "DATA_MISSING 关键字段缺失率阈值");
        put("limit_main", "0.10", "主板涨跌停幅度");
        put("limit_gem", "0.20", "30 开头创业板涨跌停幅度");
        put("limit_st", "0.05", "ST 涨跌停幅度");
        put("limit_touch_eps", "0.002", "触及涨跌停判定的容差（幅度减该值）");
        put("verify_void_days", "30", "基准日后超过 N 个自然日仍凑不满 h 个交易日则 VOID");
        put("winsor_q", "0.01", "截面缩尾分位（1% / 99%）");
        put("missing_feature_ratio", "0.30", "某日某特征缺失率超过此值则整体置 0 并告警");
        put("market_z_window", "252", "市场特征滚动时序 z-score 窗口");
        put("dedup_corr", "0", "训练前特征相关性去重阈值，0 表示关闭");
        put("feature_lookback_days", "520", "特征计算回溯的自然日数（需覆盖 252 交易日预热）");
        put("feature_persist", "1", "是否把原始特征写入 stock_feature_daily");
        put("replay_step", "10", "历史回放断点步长（交易日）");
        put("replay_pop_size", "100", "回放断点的种群规模");
        put("replay_max_gen", "30", "回放断点的最大代数");
        put("ic_decay_min", "0.02", "因子衰减预警的 |ic_60| 下限");
        put("ece_bins", "20", "可靠性曲线分桶数");
        put("ece_warn", "0.05", "ECE 提示重新校准的阈值");
        put("quantile_groups", "5", "分位分层回测组数");
        put("min_consensus", "4", "选股榜强共振阈值");
        put("alert_hit_rate", "0.50", "整体告警命中率阈值");
        put("alert_days", "5", "告警持续天数");
        put("price_check_tolerance", "0.0001", "两张源表 close 偏差告警阈值（0.01%）");
        put("price_check_samples", "200", "启动时价格口径抽样行数");
        put("color_scheme", "CN", "涨红跌绿（CN）");
        put("regime_specific", "0", "是否按市场状态训练三套冠军集（P1，默认关）");
        put("batch_size", "1000", "JDBC 批量写入每批条数");
        put("verify_batch_size", "5000", "回填每批条数");
        put("verify_page_size", "20000", "回填游标分页每页条数");
        put("init_days", "10", "初始化任务回灌的最近交易日天数（看板冷启动展示用）");
    }

    public static Map<String, String> defaults() {
        return Map.copyOf(DEFAULTS);
    }

    private final JdbcTemplate jdbc;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public GaConfig(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 启动时补齐默认配置行，已存在的（用户改过的）不覆盖。 */
    public void ensureDefaults() {
        DEFAULTS.forEach((key, value) -> jdbc.update(
                "INSERT IGNORE INTO `ga_config` (`scope`,`param_key`,`param_value`,`description`) VALUES (?,?,?,?)",
                "GLOBAL", key, value, DESCRIPTIONS.get(key)));
        reload();
    }

    public void reload() {
        cache.clear();
        jdbc.query("SELECT `scope`,`param_key`,`param_value` FROM `ga_config`",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> cache.put(
                        rs.getString("scope") + "|" + rs.getString("param_key"),
                        rs.getString("param_value") == null ? "" : rs.getString("param_value")));
    }

    /** 周期级覆盖全局：先查 H{h}，再查 GLOBAL，最后回退代码默认值。 */
    public String resolve(int horizon, String key) {
        String scoped = cache.get("H" + horizon + "|" + key);
        if (scoped != null) return scoped;
        String global = cache.get("GLOBAL|" + key);
        if (global != null) return global;
        return DEFAULTS.getOrDefault(key, "");
    }

    public String resolveGlobal(String key) {
        String global = cache.get("GLOBAL|" + key);
        return global != null ? global : DEFAULTS.getOrDefault(key, "");
    }

    public int getInt(int h, String key) { return (int) Math.round(getDouble(h, key)); }
    public long getLong(int h, String key) { return Math.round(getDouble(h, key)); }
    public boolean getBool(int h, String key) { return "1".equals(resolve(h, key)) || "true".equalsIgnoreCase(resolve(h, key)); }

    public double getDouble(int h, String key) {
        try { return Double.parseDouble(resolve(h, key)); }
        catch (Exception e) { return Double.parseDouble(DEFAULTS.getOrDefault(key, "0")); }
    }

    public String get(int h, String key) { return resolve(h, key); }

    public int globalInt(String key) { return (int) Math.round(globalDouble(key)); }

    public double globalDouble(String key) {
        try { return Double.parseDouble(resolveGlobal(key)); }
        catch (Exception e) { return Double.parseDouble(DEFAULTS.getOrDefault(key, "0")); }
    }

    public boolean globalBool(String key) {
        String v = resolveGlobal(key);
        return "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    public List<Integer> horizons() {
        List<Integer> out = new ArrayList<>();
        for (String part : resolveGlobal("horizons").split(",")) {
            try { out.add(Integer.parseInt(part.trim())); } catch (Exception ignored) {}
        }
        return out.isEmpty() ? List.of(1, 3, 5, 10, 15) : out;
    }

    public List<String> universePrefixes() {
        return List.of(resolveGlobal("universe_prefixes").split(","))
                .stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** 各周期噪声阈值下限。 */
    public double floor(int h) {
        String raw = resolveGlobal("floors");
        for (String pair : raw.split(",")) {
            String[] kv = pair.split(":");
            if (kv.length == 2 && kv[0].trim().equals(String.valueOf(h))) {
                try { return Double.parseDouble(kv[1].trim()); } catch (Exception ignored) { break; }
            }
        }
        return 0.002 * Math.sqrt(h);
    }

    /** 查询配置页数据。 */
    public List<Map<String, Object>> list(String scope) {
        String sql = "SELECT `scope`,`param_key`,`param_value`,`description`,`updated_at` FROM `ga_config`"
                + (scope == null || scope.isBlank() ? "" : " WHERE `scope` = ?") + " ORDER BY `id`";
        return (scope == null || scope.isBlank() ? jdbc.queryForList(sql) : jdbc.queryForList(sql, scope))
                .stream().map(m -> Map.<String, Object>of(
                        "scope", str(m.get("scope")),
                        "paramKey", str(m.get("param_key")),
                        "paramValue", str(m.get("param_value")),
                        "description", str(m.get("description")),
                        "updatedAt", str(m.get("updated_at")))).toList();
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    /** PUT /api/ga/config 或运行时状态写入：upsert 一行并立即刷新缓存。 */
    public void set(String scope, String key, String value) {
        // 允许写入运行时状态（如 H5|shadow_start）；未知 key 只做说明标注，不阻断配置热更新
        String desc = DESCRIPTIONS.getOrDefault(key, "运行时/自定义参数");
        jdbc.update("INSERT INTO `ga_config` (`scope`,`param_key`,`param_value`,`description`) VALUES (?,?,?,?) "
                        + "ON DUPLICATE KEY UPDATE `param_value`=VALUES(`param_value`),`description`=VALUES(`description`)",
                scope, key, value, desc);
        reload();
    }
}
