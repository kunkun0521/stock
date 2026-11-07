package com.li.stock.tlab.engine;

import com.li.stock.domain.Order;

import java.util.List;

/**
 * StrategyEngine 的输出：按优先级排序的挂单 + 触发/不触发解释。
 * <p>解释文案供「次日操作计划」直接展示，回测时忽略即可。</p>
 */
public record StrategyPlan(List<Order> orders, List<String> explanations) {
}
