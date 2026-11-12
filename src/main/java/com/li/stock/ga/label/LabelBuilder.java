package com.li.stock.ga.label;

import com.li.stock.ga.GaMath;
import com.li.stock.ga.GaModel.Bar;
import com.li.stock.ga.GaModel.Label;
import com.li.stock.ga.config.GaConfig;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 标签与噪声阈值（规格 5）。
 *
 * <p>ret_h(t) = C(t+h)/C(t) − 1，t+h 是该股票 t 之后第 h 个 tradestatus=1 的交易日；
 * 训练/适应度标签 y = 1(ret_h &gt; 0)，abs(ret_h) &lt; θ_h 的样本视为噪声不参与训练与适应度，
 * 但仍保留在矩阵里供复盘统计。θ_h = max(floor_h, coef × vol_20(t) × √h)。</p>
 */
@Service
public class LabelBuilder {

    private final GaConfig config;

    public LabelBuilder(GaConfig config) { this.config = config; }

    public double threshold(int h, double vol20) {
        double floor = config.floor(h);
        double coef = config.globalDouble("noise_vol_coef");
        return Math.max(floor, coef * Math.max(0, vol20) * Math.sqrt(h));
    }

    /**
     * 构造某周期的全量标签索引：key = code|market|yyyy-MM-dd。
     * 只使用 ≤ 到期日 的行情，训练段由 FoldSplitter 再切分，标签本身不含未来函数约束
     * （有标签日的定义即 t+h ≤ 最新日）。
     */
    public Map<String, Label> build(int h, Map<String, List<Bar>> bars, LocalDate from, LocalDate to) {
        boolean excess = "EXCESS".equalsIgnoreCase(config.get(h, "label_mode"));
        Map<String, double[]> raw = new HashMap<>();   // key -> {ret, vol20}
        Map<LocalDate, List<double[]>> byDate = new HashMap<>();

        for (Map.Entry<String, List<Bar>> e : bars.entrySet()) {
            List<Bar> series = e.getValue();
            List<Bar> tradable = new ArrayList<>();
            for (Bar b : series) if (b.tradable()) tradable.add(b);
            if (tradable.size() <= h) continue;
            double[] pct = new double[tradable.size()];
            for (int i = 0; i < tradable.size(); i++) {
                Bar b = tradable.get(i);
                pct[i] = Double.isFinite(b.pctChg()) ? b.pctChg() / 100.0
                        : (b.preclose() > 0 ? b.close() / b.preclose() - 1 : 0);
            }
            for (int i = 0; i + h < tradable.size(); i++) {
                Bar t = tradable.get(i);
                if (t.date().isBefore(from) || t.date().isAfter(to)) continue;
                double c0 = t.close(), c1 = tradable.get(i + h).close();
                if (c0 <= 0 || c1 <= 0) continue;
                double ret = c1 / c0 - 1;
                double vol20 = GaMath.stdSample(pct, Math.max(0, i - 19), i + 1);
                String key = t.code() + "|" + t.market() + "|" + t.date();
                double[] pair = {ret, vol20};
                raw.put(key, pair);
                byDate.computeIfAbsent(t.date(), d -> new ArrayList<>()).add(pair);
            }
        }

        Map<LocalDate, Double> dailyMean = new HashMap<>();
        byDate.forEach((d, list) -> dailyMean.put(d,
                list.stream().mapToDouble(p -> p[0]).average().orElse(0)));

        Map<String, Label> out = new HashMap<>();
        raw.forEach((key, pair) -> {
            String[] parts = key.split("\\|");
            LocalDate date = LocalDate.parse(parts[2]);
            double ret = pair[0] - (excess ? dailyMean.getOrDefault(date, 0.0) : 0.0);
            double theta = threshold(h, pair[1]);
            out.put(key, new Label(parts[0], date, h, ret, theta,
                    ret > 0 ? 1 : 0, Math.abs(ret) < theta));
        });
        return out;
    }

    /** 到期日：t 之后第 h 个 tradestatus=1 的交易日，返回 null 表示不足。 */
    public static LocalDate targetDate(List<LocalDate> tradableDates, LocalDate t, int h) {
        int count = 0;
        for (LocalDate d : tradableDates) {
            if (!d.isAfter(t)) continue;
            if (++count == h) return d;
        }
        return null;
    }

    /** 规格 5 的严格口径：方向正确且 abs(ret) ≥ θ。 */
    public static int effective(Label label, int predDirection) {
        if (label == null || predDirection == 0) return 0;
        int actual = label.ret() > 0 ? 1 : label.ret() < 0 ? -1 : 0;
        return actual == predDirection && Math.abs(label.ret()) >= label.threshold() ? 1 : 0;
    }
}
