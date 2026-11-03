package com.li.stock.service;

import com.li.stock.common.BizException;
import com.li.stock.common.PageResult;
import com.li.stock.common.QuerySupport;
import com.li.stock.domain.model.ScoreBar;
import com.li.stock.domain.model.ForecastResult;
import com.li.stock.domain.vo.ShortTermBuildVO;
import com.li.stock.domain.vo.ShortTermForecastVO;
import com.li.stock.repository.ScoreCalculationRepository;
import com.li.stock.repository.ShortTermForecastRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 短期持有期预测策略。
 *
 * <p>策略边界：每周一或每次行情更新后重算，预测期限为 5/10/15/20 个交易日，
 * 20 个交易日作为一个月的最长持有期。模型不承诺收益，而是输出历史相似形态下的
 * 条件期望收益、达到 +15% 的经验概率和置信度。</p>
 *
 * <p>为了避免未来函数，历史样本在日期 i 的信号只使用 i 日及之前的数据，
 * 再用 i+N 日的收盘价计算样本的真实未来收益。当前信号与历史信号越相似，
 * 该样本在预测中的权重越高。</p>
 */
@Service
public class ShortTermForecastService {

    public static final int[] HORIZONS = {5, 10, 15, 20};
    private static final int MAX_ANALOGS = 30;
    private static final int LOOKBACK_CALENDAR_DAYS = 420;
    private static final double TARGET_RETURN = 0.15;

    private final ScoreCalculationRepository dailyRepository;
    private final ShortTermForecastRepository forecastRepository;

    public ShortTermForecastService(ScoreCalculationRepository dailyRepository,
                                    ShortTermForecastRepository forecastRepository) {
        this.dailyRepository = dailyRepository;
        this.forecastRepository = forecastRepository;
    }

    public PageResult<ShortTermForecastVO> page(LocalDate tradeDate, Integer adjustFlag,
                                                String keyword, BigDecimal minProbability,
                                                Integer page, Integer size) {
        int flag = validFlag(adjustFlag);
        int actualPage = QuerySupport.page(page);
        int actualSize = QuerySupport.size(size, 50, QuerySupport.MAX_PAGE_SIZE);
        long total = forecastRepository.count(tradeDate, flag, keyword, minProbability);
        if (total == 0) return PageResult.empty(actualPage, actualSize);
        List<ShortTermForecastVO> rows = forecastRepository.page(tradeDate, flag, keyword,
                minProbability, QuerySupport.offset(actualPage, actualSize), actualSize);
        return PageResult.of(rows, total, actualPage, actualSize);
    }

    public List<LocalDate> dates(Integer adjustFlag) {
        return forecastRepository.availableDates(validFlag(adjustFlag), 120);
    }

    public ShortTermBuildVO rebuild(LocalDate requestedDate, Integer requestedAdjustFlag) {
        int adjustFlag = validFlag(requestedAdjustFlag);
        LocalDate asOfDate = requestedDate == null
                ? dailyRepository.latestDate(adjustFlag) : requestedDate;
        if (asOfDate == null) {
            throw BizException.notFound("行情分表中没有可用于预测的数据，请先导入 stock_daily_00、stock_daily_30 或 stock_daily_60");
        }

        long start = System.currentTimeMillis();
        List<ScoreBar> bars = dailyRepository.loadBars(asOfDate, adjustFlag, LOOKBACK_CALENDAR_DAYS);
        Map<String, List<ScoreBar>> grouped = bars.stream()
                .collect(Collectors.groupingBy(
                        row -> row.code() + "|" + row.market(),
                        LinkedHashMap::new,
                        Collectors.toList()));

        List<List<ScoreBar>> eligible = grouped.values().stream()
                .filter(rows -> eligible(rows, asOfDate))
                .toList();
        List<ForecastResult> results = new ArrayList<>();
        for (List<ScoreBar> rows : eligible) {
            for (int horizon : HORIZONS) {
                ForecastPoint point = forecast(rows, horizon);
                if (point != null) {
                    ScoreBar latest = rows.get(rows.size() - 1);
                    results.add(new ForecastResult(
                            latest.code(), latest.market(), asOfDate, adjustFlag, horizon,
                            decimal(point.expectedReturn()), decimal(point.targetProbability()),
                            decimal(point.confidence()), decimal(point.score()), point.analogCount()));
                }
            }
        }
        int savedRows = forecastRepository.save(results);
        return new ShortTermBuildVO(asOfDate, adjustFlag, grouped.size(), eligible.size(), savedRows,
                HORIZONS.clone(), System.currentTimeMillis() - start);
    }

    private int validFlag(Integer adjustFlag) {
        int flag = adjustFlag == null ? 2 : adjustFlag;
        if (flag < 1 || flag > 3) {
            throw BizException.badRequest("adjustFlag 必须是 1、2 或 3");
        }
        return flag;
    }

    private boolean eligible(List<ScoreBar> rows, LocalDate asOfDate) {
        if (rows.size() < 40 || !asOfDate.equals(rows.get(rows.size() - 1).tradeDate())) return false;
        ScoreBar latest = rows.get(rows.size() - 1);
        return latest.close() != null && latest.close().signum() > 0
                && !Integer.valueOf(0).equals(latest.tradeStatus())
                && !Integer.valueOf(1).equals(latest.isSt());
    }

    /**
     * 计算一个期限的预测值。
     * 信号由短周期收益、20日趋势和成交额放大组成；历史相似样本提供收益分布，
     * 因而页面可以同时展示“预估涨幅”和“达到15%的历史概率”。
     */
    private ForecastPoint forecast(List<ScoreBar> rows, int horizon) {
        Signal current = signalAt(rows, rows.size() - 1);
        if (current == null) return null;

        List<Analog> analogs = new ArrayList<>();
        int lastUsableSignal = rows.size() - horizon - 1;
        for (int index = 25; index <= lastUsableSignal; index++) {
            Signal historical = signalAt(rows, index);
            Double futureReturn = forwardReturn(rows, index, horizon);
            if (historical == null || futureReturn == null) continue;
            double distance = distance(current, historical);
            if (Double.isFinite(distance)) analogs.add(new Analog(distance, futureReturn));
        }
        analogs.sort(Comparator.comparingDouble(Analog::distance));
        List<Analog> selected = analogs.stream().limit(MAX_ANALOGS).toList();

        double expected;
        double probability;
        double confidence;
        if (selected.isEmpty()) {
            // 样本不足时只保留技术信号，并把置信度降到很低，防止误读为可靠预测。
            expected = technicalEstimate(current, horizon);
            probability = expected >= TARGET_RETURN ? 0.25 : 0.05;
            confidence = 0.10;
        } else {
            double weightSum = 0;
            double returnSum = 0;
            double hitWeight = 0;
            double distanceSum = 0;
            for (Analog analog : selected) {
                double weight = 1.0 / (0.05 + analog.distance());
                weightSum += weight;
                returnSum += weight * analog.futureReturn();
                if (analog.futureReturn() >= TARGET_RETURN) hitWeight += weight;
                distanceSum += analog.distance();
            }
            expected = returnSum / weightSum;
            probability = hitWeight / weightSum;
            double averageDistance = distanceSum / selected.size();
            confidence = Math.min(1.0, selected.size() / (double) MAX_ANALOGS)
                    * (1.0 - Math.min(averageDistance, 1.0));
        }
        expected = clamp(expected, -0.25, 0.35);
        probability = clamp(probability, 0, 1);
        confidence = clamp(confidence, 0, 1);

        // 预测综合分：预估收益 55% + 达到15%的概率 35% + 样本置信度 10%。
        double returnScore = clamp(0.5 + expected * 2.5, 0, 1) * 100;
        double score = returnScore * 0.55 + probability * 100 * 0.35 + confidence * 100 * 0.10;
        return new ForecastPoint(expected, probability, confidence, score, selected.size());
    }

    private Signal signalAt(List<ScoreBar> rows, int index) {
        Double close = closeAt(rows, index);
        Double ret5 = returnAt(rows, index, 5);
        Double ret10 = returnAt(rows, index, 10);
        Double ret20 = returnAt(rows, index, 20);
        Double ma20 = meanClose(rows, index, 20);
        if (close == null || ret5 == null || ret10 == null || ret20 == null || ma20 == null) return null;
        Double amountRatio = amountRatio(rows, index, 20);
        return new Signal(ret5, ret10, ret20, close / ma20 - 1,
                amountRatio == null ? 1.0 : amountRatio);
    }

    private double distance(Signal current, Signal historical) {
        return Math.abs(current.ret5() - historical.ret5()) * 2.0
                + Math.abs(current.ret10() - historical.ret10()) * 1.5
                + Math.abs(current.ret20() - historical.ret20())
                + Math.abs(current.trend20() - historical.trend20()) * 2.0
                + Math.abs(current.amountRatio() - historical.amountRatio()) * 0.08;
    }

    private double technicalEstimate(Signal signal, int horizon) {
        double base = signal.ret5() * 0.25 + signal.ret10() * 0.30 + signal.ret20() * 0.45;
        return base * horizon / 20.0;
    }

    private Double forwardReturn(List<ScoreBar> rows, int index, int horizon) {
        Double from = closeAt(rows, index);
        Double to = closeAt(rows, index + horizon);
        return from == null || to == null || from <= 0 ? null : to / from - 1;
    }

    private Double returnAt(List<ScoreBar> rows, int index, int days) {
        Double old = closeAt(rows, index - days);
        Double current = closeAt(rows, index);
        return old == null || current == null || old <= 0 ? null : current / old - 1;
    }

    private Double meanClose(List<ScoreBar> rows, int endIndex, int window) {
        if (endIndex + 1 < window) return null;
        double sum = 0;
        for (int i = endIndex - window + 1; i <= endIndex; i++) {
            Double close = closeAt(rows, i);
            if (close == null) return null;
            sum += close;
        }
        return sum / window;
    }

    private Double amountRatio(List<ScoreBar> rows, int endIndex, int window) {
        if (endIndex + 1 < window) return null;
        Double current = number(rows.get(endIndex).amount());
        if (current == null) return null;
        double sum = 0;
        int count = 0;
        for (int i = endIndex - window + 1; i <= endIndex; i++) {
            Double amount = number(rows.get(i).amount());
            if (amount != null) {
                sum += amount;
                count++;
            }
        }
        return count == 0 || sum == 0 ? null : current / (sum / count);
    }

    private Double closeAt(List<ScoreBar> rows, int index) {
        return index < 0 || index >= rows.size() ? null : number(rows.get(index).close());
    }

    private Double number(BigDecimal value) {
        return value == null || !Double.isFinite(value.doubleValue()) ? null : value.doubleValue();
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }

    private record Signal(double ret5, double ret10, double ret20, double trend20, double amountRatio) {}

    private record Analog(double distance, double futureReturn) {}

    private record ForecastPoint(double expectedReturn, double targetProbability,
                                 double confidence, double score, int analogCount) {}
}
