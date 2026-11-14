package com.li.stock.quant.analytics;

import java.util.LinkedHashMap;
import java.util.Map;

/** 事件研究的一行统计（某持有期 × 某拆分维度）。 */
public record EventRow(int horizon, String splitKey, int cnt, double meanRet, double medianRet,
                       double winRate, double avgWin, double avgLoss, double excessMean,
                       double tStat, double mfeMean, double maeMean) {

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("horizon", horizon);
        m.put("splitKey", splitKey);
        m.put("cnt", cnt);
        m.put("meanRet", round(meanRet, 6));
        m.put("medianRet", round(medianRet, 6));
        m.put("winRate", round(winRate, 4));
        m.put("avgWin", round(avgWin, 6));
        m.put("avgLoss", round(avgLoss, 6));
        m.put("excessMean", round(excessMean, 6));
        m.put("tStat", round(tStat, 3));
        m.put("mfeMean", round(mfeMean, 6));
        m.put("maeMean", round(maeMean, 6));
        return m;
    }

    private static double round(double v, int scale) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0;
        double f = Math.pow(10, scale);
        return Math.round(v * f) / f;
    }
}
