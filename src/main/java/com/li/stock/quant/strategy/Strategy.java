package com.li.stock.quant.strategy;

import java.util.List;
import java.util.Map;

/**
 * 策略契约（规格 4.2）。
 *
 * <p>实现类放在 {@code com.li.stock.quant.strategy.impl} 下并标注 {@code @Component}，
 * 启动时由 {@link StrategyRegistry} 自动扫描注册；新增策略不需要改引擎、接口和页面。</p>
 *
 * <p>硬约束：第 t 日的输出只能使用 ≤ t 日的数据。指标全部是滚动窗口计算，
 * 引擎另提供截断一致性测试（用截至 T 的数据与全量数据分别求值比对）。</p>
 */
public interface Strategy {

    StrategyMeta meta();

    List<ParamDef> paramDefs();

    /** 默认参数（键值对，值与 ParamDef 的默认值一致）。 */
    Map<String, Object> defaults();

    /** 预热天数：数据起点后的前 warmup 个交易日不产生信号。 */
    int warmupBars(Map<String, Object> params);

    /** 对全市场逐股票求值，返回与面板等长的信号序列。 */
    List<SignalSeries> evaluate(EvalContext ctx);
}
