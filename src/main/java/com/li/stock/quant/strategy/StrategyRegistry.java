package com.li.stock.quant.strategy;

import com.li.stock.common.BizException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 策略注册中心：启动时收集所有 {@link Strategy} 实现，按内置顺序稳定排序。
 *
 * <p>同时负责参数归一与校验，保证后端校验规则与前端 ParamDef 表单完全一致。</p>
 */
@Component
public class StrategyRegistry {

    /** 内置顺序，前端卡片按此展示。 */
    private static final List<String> ORDER = List.of(
            "L1_OVERSOLD_REBOUND", "L2_DEEP_VALUE", "R1_VOLUME_BREAKOUT", "R2_MA_MACD", "X1_REVERSAL_CONFIRM");

    private final Map<String, Strategy> strategies = new LinkedHashMap<>();

    public StrategyRegistry(List<Strategy> list) {
        List<Strategy> sorted = new ArrayList<>(list);
        sorted.sort((a, b) -> {
            int ia = ORDER.indexOf(a.meta().key());
            int ib = ORDER.indexOf(b.meta().key());
            return Integer.compare(ia < 0 ? 99 : ia, ib < 0 ? 99 : ib);
        });
        for (Strategy s : sorted) {
            strategies.put(s.meta().key(), s);
        }
    }

    public Strategy get(String key) {
        Strategy s = strategies.get(key);
        if (s == null) {
            throw BizException.notFound("策略不存在：" + key);
        }
        return s;
    }

    public boolean contains(String key) {
        return strategies.containsKey(key);
    }

    public List<Strategy> all() {
        return new ArrayList<>(strategies.values());
    }

    /**
     * 合并默认值并做类型/范围校验，返回可直接参与计算的新 Map。
     */
    public Map<String, Object> normalize(String key, Map<String, Object> input) {
        Strategy strategy = get(key);
        Map<String, Object> out = new LinkedHashMap<>(strategy.defaults());
        Map<String, ParamDef> defs = new LinkedHashMap<>();
        for (ParamDef d : strategy.paramDefs()) defs.put(d.key(), d);
        if (input != null) {
            for (Map.Entry<String, Object> e : input.entrySet()) {
                ParamDef def = defs.get(e.getKey());
                if (def == null) {
                    throw BizException.badRequest("未知参数：" + e.getKey());
                }
                out.put(e.getKey(), coerce(def, e.getValue()));
            }
        }
        return out;
    }

    private Object coerce(ParamDef def, Object raw) {
        if (raw == null || (raw instanceof String s && s.isBlank())) return def.def();
        switch (def.type()) {
            case "int" -> {
                int v;
                try {
                    v = raw instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(raw).trim());
                } catch (Exception e) {
                    throw BizException.badRequest("参数【" + def.label() + "】必须是整数");
                }
                if (def.min() != null && v < def.min()) {
                    throw BizException.badRequest("参数【" + def.label() + "】不能小于 " + def.min().intValue());
                }
                if (def.max() != null && v > def.max()) {
                    throw BizException.badRequest("参数【" + def.label() + "】不能大于 " + def.max().intValue());
                }
                return v;
            }
            case "decimal" -> {
                double v;
                try {
                    v = raw instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(raw).trim());
                } catch (Exception e) {
                    throw BizException.badRequest("参数【" + def.label() + "】必须是数字");
                }
                if (def.min() != null && v < def.min()) {
                    throw BizException.badRequest("参数【" + def.label() + "】不能小于 " + def.min());
                }
                if (def.max() != null && v > def.max()) {
                    throw BizException.badRequest("参数【" + def.label() + "】不能大于 " + def.max());
                }
                return v;
            }
            case "bool" -> {
                if (raw instanceof Boolean b) return b;
                String s = String.valueOf(raw).trim();
                if ("true".equalsIgnoreCase(s) || "1".equals(s)) return true;
                if ("false".equalsIgnoreCase(s) || "0".equals(s)) return false;
                throw BizException.badRequest("参数【" + def.label() + "】必须是 true/false");
            }
            case "enum" -> {
                String s = String.valueOf(raw).trim();
                if (!def.options().contains(s)) {
                    throw BizException.badRequest("参数【" + def.label() + "】取值必须是 " + def.options());
                }
                return s;
            }
            default -> {
                return raw;
            }
        }
    }

    /** 前端展示用的策略描述。 */
    public Map<String, Object> describe(Strategy s) {
        StrategyMeta m = s.meta();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("key", m.key());
        map.put("name", m.name());
        map.put("type", m.type());
        map.put("version", m.version());
        map.put("description", m.description());
        map.put("fit", m.fit());
        map.put("horizons", m.horizons());
        map.put("paramDefs", s.paramDefs().stream().map(ParamDef::toMap).toList());
        map.put("defaults", s.defaults());
        map.put("warmupBars", s.warmupBars(s.defaults()));
        return map;
    }
}
