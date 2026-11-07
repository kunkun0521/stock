package com.li.stock.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规格第六节的全部默认参数（含第七节扫描参数、第三节复权口径），前端可通过 params 对象按需覆盖。
 * <p>引擎内部数值用 double 计算，对外输出与落库统一由 web / repo 层转 BigDecimal，
 * 与项目现有 TTrading 模块的口径一致。</p>
 */
public class Params {

    /** 佣金费率，双向。 */
    public double commissionRate = 0.00025;
    /** 单笔最低佣金（元），双向。 */
    public double minCommission = 5;
    /** 印花税，仅卖出。 */
    public double stampTaxRate = 0.0005;
    /** 过户费，双向。 */
    public double transferFeeRate = 0.00001;
    /** 经手费 + 证管费，双向。 */
    public double otherFeeRate = 0.00005;
    /** 市价类成交滑点（基点，1bp = 0.01%）。 */
    public double slippageBp = 1;

    /** 持仓市值占总资金上限，只限制补仓。 */
    public double maxPositionRatio = 0.6;
    /** 可支配资金里留给做T的比例，补仓不动用。 */
    public double tReserveRatio = 0.3;
    public List<Double> addSteps = new ArrayList<>(List.of(0.05, 0.10, 0.15));
    public List<Double> addRatios = new ArrayList<>(List.of(0.3, 0.3, 0.4));
    public int maxAddTimes = 3;
    public int addMinIntervalDays = 2;
    /** NONE 或 STOP_FALL。 */
    public String addConfirm = "NONE";

    public double tRatio = 0.5;
    public double kAtr = 0.4;
    public double bandMax = 0.05;
    public double minNetProfitRate = 0.003;
    public double minTNetProfitYuan = 20;
    public double liquidityParticipation = 0.01;
    public int maxCarryDays = 3;
    public String carryPolicy = "BUYBACK_FORCE,SELL_ABSORB";

    public double targetProfitRate = 0;
    public double maxLossRate = 0.2;
    public int maxHoldDays = 60;
    /** CLOSE 或 NEXT_OPEN；扫描固定用 NEXT_OPEN 避免前视。 */
    public String buyPriceMode = "CLOSE";

    /** 扫描窗口持有交易日数。 */
    public int holdDays = 20;
    /** 扫描初始仓位占总资金比例。 */
    public double initPositionRatio = 0.3;
    /** 前复权口径。 */
    public int adjustFlag = 2;

    public static Params defaults() {
        return new Params();
    }

    /** 一个完整往返的成本率：两边佣金 + 印花税 + 两边过户费与规费，默认约 0.11%。 */
    public double roundTripCostRate() {
        return 2 * (commissionRate + transferFeeRate + otherFeeRate) + stampTaxRate;
    }

    /** 做T价带下限，保证扣掉往返成本后仍有最低净利。 */
    public double bandMin() {
        return (roundTripCostRate() + minNetProfitRate) / 2;
    }

    /** 按前端传入的 params 覆盖；未知键忽略，数值做范围保护，避免脏参数把回测搞崩。 */
    public static Params from(Map<String, Object> overrides) {
        Params p = new Params();
        if (overrides == null || overrides.isEmpty()) return p;
        overrides.forEach((key, value) -> {
            if (value == null) return;
            switch (key) {
                case "commissionRate" -> p.commissionRate = num(value, 0, 0.01, p.commissionRate);
                case "minCommission" -> p.minCommission = num(value, 0, 100, p.minCommission);
                case "stampTaxRate" -> p.stampTaxRate = num(value, 0, 0.01, p.stampTaxRate);
                case "transferFeeRate" -> p.transferFeeRate = num(value, 0, 0.01, p.transferFeeRate);
                case "otherFeeRate" -> p.otherFeeRate = num(value, 0, 0.01, p.otherFeeRate);
                case "slippageBp" -> p.slippageBp = num(value, 0, 100, p.slippageBp);
                case "maxPositionRatio" -> p.maxPositionRatio = num(value, 0.05, 1, p.maxPositionRatio);
                case "tReserveRatio" -> p.tReserveRatio = num(value, 0, 0.9, p.tReserveRatio);
                case "addSteps" -> p.addSteps = list(value, p.addSteps, 0.001, 0.9);
                case "addRatios" -> p.addRatios = list(value, p.addRatios, 0, 1);
                case "maxAddTimes" -> p.maxAddTimes = (int) num(value, 0, 10, p.maxAddTimes);
                case "addMinIntervalDays" -> p.addMinIntervalDays = (int) num(value, 0, 60, p.addMinIntervalDays);
                case "addConfirm" -> p.addConfirm = text(value, p.addConfirm, "NONE", "STOP_FALL");
                case "tRatio" -> p.tRatio = num(value, 0.05, 1, p.tRatio);
                case "kAtr" -> p.kAtr = num(value, 0, 3, p.kAtr);
                case "bandMax" -> p.bandMax = num(value, 0.001, 0.2, p.bandMax);
                case "minNetProfitRate" -> p.minNetProfitRate = num(value, 0, 0.1, p.minNetProfitRate);
                case "minTNetProfitYuan" -> p.minTNetProfitYuan = num(value, 0, 10000, p.minTNetProfitYuan);
                case "liquidityParticipation" -> p.liquidityParticipation = num(value, 0.0001, 1, p.liquidityParticipation);
                case "maxCarryDays" -> p.maxCarryDays = (int) num(value, 1, 60, p.maxCarryDays);
                case "carryPolicy" -> p.carryPolicy = text(value, p.carryPolicy);
                case "targetProfitRate" -> p.targetProfitRate = num(value, -0.5, 5, p.targetProfitRate);
                case "maxLossRate" -> p.maxLossRate = num(value, 0.001, 1, p.maxLossRate);
                case "maxHoldDays" -> p.maxHoldDays = (int) num(value, 1, 500, p.maxHoldDays);
                case "buyPriceMode" -> p.buyPriceMode = text(value, p.buyPriceMode, "CLOSE", "NEXT_OPEN");
                case "holdDays" -> p.holdDays = (int) num(value, 2, 120, p.holdDays);
                case "initPositionRatio" -> p.initPositionRatio = num(value, 0.01, 1, p.initPositionRatio);
                case "adjustFlag" -> p.adjustFlag = (int) num(value, 1, 3, p.adjustFlag);
                default -> { /* 未知键忽略，保证前后端参数版本不同也不会报错 */ }
            }
        });
        // 档位与预算分配长度对齐，避免越界；预算归一，避免补仓金额超预算。
        int n = Math.min(p.addSteps.size(), p.addRatios.size());
        p.addSteps = new ArrayList<>(p.addSteps.subList(0, n));
        p.addRatios = new ArrayList<>(p.addRatios.subList(0, n));
        double sum = p.addRatios.stream().mapToDouble(Double::doubleValue).sum();
        if (sum > 0 && Math.abs(sum - 1) > 1e-9) {
            for (int i = 0; i < p.addRatios.size(); i++) p.addRatios.set(i, p.addRatios.get(i) / sum);
        }
        if (p.bandMax < p.bandMin()) p.bandMax = p.bandMin();
        return p;
    }

    /** 本次生效的全部参数，写入 t_sim_task.params_json，供历史任务回看。 */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("commissionRate", commissionRate);
        m.put("minCommission", minCommission);
        m.put("stampTaxRate", stampTaxRate);
        m.put("transferFeeRate", transferFeeRate);
        m.put("otherFeeRate", otherFeeRate);
        m.put("slippageBp", slippageBp);
        m.put("maxPositionRatio", maxPositionRatio);
        m.put("tReserveRatio", tReserveRatio);
        m.put("addSteps", addSteps);
        m.put("addRatios", addRatios);
        m.put("maxAddTimes", maxAddTimes);
        m.put("addMinIntervalDays", addMinIntervalDays);
        m.put("addConfirm", addConfirm);
        m.put("tRatio", tRatio);
        m.put("kAtr", kAtr);
        m.put("bandMax", bandMax);
        m.put("minNetProfitRate", minNetProfitRate);
        m.put("minTNetProfitYuan", minTNetProfitYuan);
        m.put("liquidityParticipation", liquidityParticipation);
        m.put("maxCarryDays", maxCarryDays);
        m.put("carryPolicy", carryPolicy);
        m.put("targetProfitRate", targetProfitRate);
        m.put("maxLossRate", maxLossRate);
        m.put("maxHoldDays", maxHoldDays);
        m.put("buyPriceMode", buyPriceMode);
        m.put("holdDays", holdDays);
        m.put("initPositionRatio", initPositionRatio);
        m.put("adjustFlag", adjustFlag);
        m.put("roundTripCostRate", roundTripCostRate());
        m.put("bandMin", bandMin());
        return m;
    }

    private static double num(Object value, double min, double max, double fallback) {
        if (!(value instanceof Number number)) return fallback;
        double v = number.doubleValue();
        if (!Double.isFinite(v)) return fallback;
        return Math.min(max, Math.max(min, v));
    }

    private static String text(Object value, String fallback, String... allowed) {
        String v = String.valueOf(value).trim().toUpperCase();
        if (allowed.length == 0) return v.isEmpty() ? fallback : v;
        for (String a : allowed) if (a.equalsIgnoreCase(v)) return a;
        return fallback;
    }

    private static List<Double> list(Object value, List<Double> fallback, double min, double max) {
        if (!(value instanceof List<?> raw) || raw.isEmpty()) return new ArrayList<>(fallback);
        List<Double> out = new ArrayList<>();
        for (Object o : raw) {
            double v = num(o, min, max, Double.NaN);
            if (Double.isFinite(v)) out.add(v);
        }
        return out.isEmpty() ? new ArrayList<>(fallback) : out;
    }
}
