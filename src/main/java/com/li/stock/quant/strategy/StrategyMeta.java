package com.li.stock.quant.strategy;

import java.util.List;

/**
 * 策略元信息。
 *
 * @param key          唯一键，如 L1_OVERSOLD_REBOUND
 * @param name         中文名
 * @param type         LEFT / RIGHT / HYBRID
 * @param version      版本号，逻辑或参数变更需升级，旧回测仍可查看
 * @param description  思路说明
 * @param fit          适用行情
 * @param horizons     默认事件研究持有期
 */
public record StrategyMeta(String key, String name, String type, String version,
                           String description, String fit, List<Integer> horizons) {
}
