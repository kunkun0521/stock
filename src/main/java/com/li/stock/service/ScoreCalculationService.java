package com.li.stock.service;

import com.li.stock.common.BizException;
import com.li.stock.domain.model.ScoreBar;
import com.li.stock.domain.vo.ScoreBuildVO;
import com.li.stock.repository.ScoreCalculationRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Java 版日频横截面多因子评分任务。
 *
 * 数据边界严格截止到 asOfDate，避免把未来行情带入评分；默认使用最新已入库交易日。
 */
@Service
public class ScoreCalculationService {

    private static final int DEFAULT_CALENDAR_DAYS = 420;
    private static final Map<String, Double> WEIGHTS = Map.of(
            "value", 0.25,
            "momentum", 0.30,
            "trend", 0.15,
            "risk", 0.20,
            "liquidity", 0.10);

    private final ScoreCalculationRepository repository;

    public ScoreCalculationService(ScoreCalculationRepository repository) {
        this.repository = repository;
    }

    public ScoreBuildVO rebuild(LocalDate requestedDate, Integer requestedAdjustFlag) {
        int adjustFlag = requestedAdjustFlag == null ? 2 : requestedAdjustFlag;
        if (adjustFlag < 1 || adjustFlag > 3) {
            throw BizException.badRequest("adjustFlag 必须是 1、2 或 3");
        }
        LocalDate asOfDate = requestedDate == null ? repository.latestDate(adjustFlag) : requestedDate;
        if (asOfDate == null) {
            throw BizException.notFound("行情分表中没有可用于评分的数据，请先导入 stock_daily_00、stock_daily_30 或 stock_daily_60");
        }

        long start = System.currentTimeMillis();
        List<ScoreBar> bars = repository.loadBars(asOfDate, adjustFlag, DEFAULT_CALENDAR_DAYS);
        Map<String, List<ScoreBar>> grouped = bars.stream()
                .collect(Collectors.groupingBy(
                        bar -> bar.code() + "|" + bar.market(),
                        LinkedHashMap::new,
                        Collectors.toList()));

        List<Feature> features = new ArrayList<>();
        for (List<ScoreBar> rows : grouped.values()) {
            if (eligible(rows, asOfDate)) {
                Feature feature = feature(rows);
                if (feature != null) {
                    features.add(feature);
                }
            }
        }
        if (features.isEmpty()) {
            throw BizException.notFound("最新交易日没有足够历史数据的可评分股票");
        }

        Map<String, List<List<Double>>> populations = populations(features);
        List<ScoreResult> results = features.stream()
                .map(feature -> score(feature, populations, adjustFlag))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(ScoreResult::totalScore,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        int saved = repository.save(results);
        return new ScoreBuildVO(asOfDate, adjustFlag, grouped.size(), results.size(), saved,
                System.currentTimeMillis() - start);
    }

    private boolean eligible(List<ScoreBar> rows, LocalDate asOfDate) {
        if (rows.isEmpty() || !asOfDate.equals(rows.get(rows.size() - 1).tradeDate())) {
            return false;
        }
        ScoreBar latest = rows.get(rows.size() - 1);
        return valid(latest.close()) && latest.close().signum() > 0
                && !Integer.valueOf(0).equals(latest.tradeStatus())
                && !Integer.valueOf(1).equals(latest.isSt());
    }

    private Feature feature(List<ScoreBar> rows) {
        ScoreBar latest = rows.get(rows.size() - 1);
        double close = latest.close().doubleValue();
        Double ma20 = meanClose(rows, 20);
        Double ma60 = meanClose(rows, 60);
        List<Double> prior120 = closeValues(rows, Math.max(0, rows.size() - 121), rows.size() - 1);
        Double breakout = prior120.isEmpty() ? null : close / prior120.stream().max(Double::compareTo).orElse(close) - 1;

        return new Feature(
                latest.code(), latest.market(), latest.tradeDate(),
                latest.close(), positive(latest.peTtm()), positive(latest.pbMrq()),
                positive(latest.psTtm()), positive(latest.pcfNcfTtm()),
                returnOf(rows, 21), returnOf(rows, 63), returnOf(rows, 126), returnOf(rows, 252),
                ma20 == null ? null : close / ma20 - 1,
                ma60 == null ? null : close / ma60 - 1,
                breakout,
                annualizedVolatility(rows, 60),
                maxDrawdown(rows, 252),
                ratio(latest.amount(), meanAmount(rows, 20)),
                meanTurnover(rows, 20));
    }

    private Map<String, List<List<Double>>> populations(List<Feature> features) {
        Map<String, List<List<Double>>> populations = new LinkedHashMap<>();
        for (String name : WEIGHTS.keySet()) {
            int count = switch (name) {
                case "value", "momentum" -> 4;
                case "trend" -> 3;
                case "risk" -> 2;
                case "liquidity" -> 2;
                default -> 0;
            };
            List<List<Double>> values = new ArrayList<>();
            for (int i = 0; i < count; i++) values.add(new ArrayList<>());
            populations.put(name, values);
        }
        for (Feature feature : features) {
            Map<String, List<Metric>> metrics = metrics(feature);
            for (Map.Entry<String, List<Metric>> entry : metrics.entrySet()) {
                for (int i = 0; i < entry.getValue().size(); i++) {
                    Double value = entry.getValue().get(i).value();
                    if (value != null && Double.isFinite(value)) {
                        populations.get(entry.getKey()).get(i).add(value);
                    }
                }
            }
        }
        return populations;
    }

    private ScoreResult score(Feature feature, Map<String, List<List<Double>>> populations,
                              int adjustFlag) {
        Map<String, List<Metric>> metrics = metrics(feature);
        Map<String, BigDecimal> factorScores = new HashMap<>();
        double weighted = 0;
        double denominator = 0;
        for (String factor : WEIGHTS.keySet()) {
            List<Metric> factorMetrics = metrics.get(factor);
            List<Double> scores = new ArrayList<>();
            for (int i = 0; i < factorMetrics.size(); i++) {
                Metric metric = factorMetrics.get(i);
                if (metric.value() == null) continue;
                Double value = percentile(populations.get(factor).get(i), metric.value(), metric.higherIsBetter());
                if (value != null) scores.add(value);
            }
            if (!scores.isEmpty()) {
                double factorScore = scores.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                factorScores.put(factor, decimal(factorScore * 100));
                weighted += WEIGHTS.get(factor) * factorScore;
                denominator += WEIGHTS.get(factor);
            }
        }
        if (denominator == 0) return null;
        return new ScoreResult(
                feature.code(), feature.market(), feature.tradeDate(), adjustFlag,
                decimal(weighted / denominator * 100), factorScores.get("value"),
                factorScores.get("momentum"), factorScores.get("trend"),
                factorScores.get("risk"), factorScores.get("liquidity"), feature.close(),
                decimal(feature.peTtm()), decimal(feature.pbMrq()), decimal(feature.psTtm()),
                decimal(feature.pcfNcfTtm()),
                decimal(feature.ret21()), decimal(feature.ret63()), decimal(feature.ret126()),
                decimal(feature.ret252()), decimal(feature.vol60()), decimal(feature.drawdown252()),
                decimal(feature.amountRatio20()), decimal(feature.turnover20()));
    }

    private Map<String, List<Metric>> metrics(Feature f) {
        Map<String, List<Metric>> result = new LinkedHashMap<>();
        result.put("value", List.of(
                new Metric(f.peTtm(), false), new Metric(f.pbMrq(), false),
                new Metric(f.psTtm(), false), new Metric(f.pcfNcfTtm(), false)));
        result.put("momentum", List.of(
                new Metric(f.ret21(), true), new Metric(f.ret63(), true),
                new Metric(f.ret126(), true), new Metric(f.ret252(), true)));
        result.put("trend", List.of(
                new Metric(f.trend20(), true), new Metric(f.trend60(), true),
                new Metric(f.breakout120(), true)));
        result.put("risk", List.of(
                new Metric(f.vol60(), false), new Metric(f.drawdown252(), true)));
        result.put("liquidity", List.of(
                new Metric(f.amountRatio20(), true), new Metric(f.turnover20(), true)));
        return result;
    }

    private Double percentile(List<Double> values, double value, boolean higherIsBetter) {
        if (values == null || values.isEmpty()) return null;
        List<Double> sorted = values.stream().sorted().toList();
        long lower = sorted.stream().filter(v -> v < value).count();
        long equal = sorted.stream().filter(v -> Double.compare(v, value) == 0).count();
        double score = (lower + 0.5 * equal) / sorted.size();
        return higherIsBetter ? score : 1 - score;
    }

    private Double returnOf(List<ScoreBar> rows, int days) {
        if (rows.size() <= days) return null;
        Double old = number(rows.get(rows.size() - days - 1).close());
        Double current = number(rows.get(rows.size() - 1).close());
        return old == null || current == null || old <= 0 ? null : current / old - 1;
    }

    private Double annualizedVolatility(List<ScoreBar> rows, int window) {
        int from = Math.max(1, rows.size() - window);
        List<Double> changes = new ArrayList<>();
        for (int i = from; i < rows.size(); i++) {
            Double previous = number(rows.get(i - 1).close());
            Double current = number(rows.get(i).close());
            if (previous != null && current != null && previous > 0 && current > 0) {
                changes.add(Math.log(current / previous));
            }
        }
        Double std = sampleStdDev(changes);
        return std == null ? null : std * Math.sqrt(252);
    }

    private Double maxDrawdown(List<ScoreBar> rows, int window) {
        int from = Math.max(0, rows.size() - window);
        double peak = 0;
        double worst = 0;
        for (Double close : closeValues(rows, from, rows.size())) {
            peak = Math.max(peak, close);
            if (peak > 0) worst = Math.min(worst, close / peak - 1);
        }
        return worst;
    }

    private Double meanClose(List<ScoreBar> rows, int window) {
        List<Double> values = closeValues(rows, Math.max(0, rows.size() - window), rows.size());
        return values.size() < window ? null : values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }

    private Double meanAmount(List<ScoreBar> rows, int window) {
        List<Double> values = rows.subList(Math.max(0, rows.size() - window), rows.size()).stream()
                .map(row -> number(row.amount())).filter(Objects::nonNull).toList();
        return values.isEmpty() ? null : values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }

    private Double meanTurnover(List<ScoreBar> rows, int window) {
        List<Double> values = rows.subList(Math.max(0, rows.size() - window), rows.size()).stream()
                .map(row -> number(row.turnover())).filter(Objects::nonNull).toList();
        return values.isEmpty() ? null : values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }

    private List<Double> closeValues(List<ScoreBar> rows, int from, int to) {
        return rows.subList(Math.max(0, from), Math.min(rows.size(), to)).stream()
                .map(row -> number(row.close())).filter(Objects::nonNull).toList();
    }

    private Double sampleStdDev(List<Double> values) {
        if (values.size() < 2) return null;
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = values.stream().mapToDouble(value -> Math.pow(value - mean, 2)).sum() / (values.size() - 1);
        return Math.sqrt(Math.max(variance, 0));
    }

    private Double ratio(BigDecimal numerator, Double denominator) {
        Double n = number(numerator);
        return n == null || denominator == null || denominator == 0 ? null : n / denominator;
    }

    private Double positive(BigDecimal value) {
        Double number = number(value);
        return number != null && number > 0 ? number : null;
    }

    private boolean valid(BigDecimal value) {
        return value != null && Double.isFinite(value.doubleValue());
    }

    private Double number(BigDecimal value) {
        return valid(value) ? value.doubleValue() : null;
    }

    private BigDecimal decimal(Double value) {
        return value == null || !Double.isFinite(value) ? null
                : BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }

    private record Metric(Double value, boolean higherIsBetter) {}

    private record Feature(
            String code, String market, LocalDate tradeDate, BigDecimal close,
            Double peTtm, Double pbMrq, Double psTtm, Double pcfNcfTtm,
            Double ret21, Double ret63, Double ret126, Double ret252,
            Double trend20, Double trend60, Double breakout120,
            Double vol60, Double drawdown252, Double amountRatio20, Double turnover20) {}

    /** ScoreCalculationRepository 使用的持久化结果结构。 */
    public record ScoreResult(
            String code, String market, LocalDate tradeDate, int adjustFlag,
            BigDecimal totalScore, BigDecimal valueScore, BigDecimal momentumScore,
            BigDecimal trendScore, BigDecimal riskScore, BigDecimal liquidityScore,
            BigDecimal close, BigDecimal peTtm, BigDecimal pbMrq, BigDecimal psTtm, BigDecimal pcfNcfTtm,
            BigDecimal ret21, BigDecimal ret63, BigDecimal ret126, BigDecimal ret252,
            BigDecimal vol60, BigDecimal drawdown252, BigDecimal amountRatio20,
            BigDecimal turnover20) {}
}
