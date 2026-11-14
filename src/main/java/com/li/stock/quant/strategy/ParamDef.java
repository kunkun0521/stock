package com.li.stock.quant.strategy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 参数定义：同时驱动后端校验与前端动态表单（规格 4.2）。
 *
 * @param key      参数键
 * @param label    中文标签
 * @param type     int / decimal / bool / enum
 * @param def      默认值
 * @param min      最小值（数值型）
 * @param max      最大值（数值型）
 * @param step     步长（数值型）
 * @param options  枚举可选项
 * @param desc     说明
 */
public record ParamDef(String key, String label, String type, Object def,
                       Double min, Double max, Double step, List<String> options, String desc) {

    public static ParamDef intP(String key, String label, int def, int min, int max, String desc) {
        return new ParamDef(key, label, "int", def, (double) min, (double) max, 1d, List.of(), desc);
    }

    public static ParamDef decP(String key, String label, double def, double min, double max, double step,
                                String desc) {
        return new ParamDef(key, label, "decimal", def, min, max, step, List.of(), desc);
    }

    public static ParamDef boolP(String key, String label, boolean def, String desc) {
        return new ParamDef(key, label, "bool", def, null, null, null, List.of(), desc);
    }

    public static ParamDef enumP(String key, String label, String def, List<String> options, String desc) {
        return new ParamDef(key, label, "enum", def, null, null, null, options, desc);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("type", type);
        m.put("defaultValue", def);
        if (min != null) m.put("min", min);
        if (max != null) m.put("max", max);
        if (step != null) m.put("step", step);
        if (!options.isEmpty()) m.put("options", options);
        m.put("desc", desc == null ? "" : desc);
        return m;
    }
}
