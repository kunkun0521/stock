package com.li.stock.service;

import com.li.stock.common.BizException;
import com.li.stock.common.PageResult;
import com.li.stock.common.QuerySupport;
import com.li.stock.domain.model.ForecastPrediction;
import com.li.stock.domain.model.ForecastRun;
import com.li.stock.domain.model.ScoreBar;
import com.li.stock.domain.vo.ShortTermDailyForecastVO;
import com.li.stock.domain.vo.ShortTermEvaluateVO;
import com.li.stock.domain.vo.ShortTermRunBuildVO;
import com.li.stock.domain.vo.ShortTermRunSummaryVO;
import com.li.stock.domain.vo.ShortTermRunVO;
import com.li.stock.repository.ScoreCalculationRepository;
import com.li.stock.repository.ShortTermHistoryRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 按次保存、按交易日展开的短期持有预测策略。
 *
 * <p>策略只使用预测日及之前的历史行情生成当前信号，最多回看约 420 个自然日，
 * 再从历史中寻找相似的 5/10/20 日收益、20 日趋势和成交额状态。对相似样本未来
 * 20 个交易日的逐日收益做距离加权平均，得到未来第 1 至第 20 个交易日的预测路径。</p>
 *
 * <p>每次执行都会新建一个 run_id，并保存每只股票 20 条明细；之后可重复调用评估接口，
 * 将已经发生的实际逐日收益写回预测记录。因此页面展示的是可复盘的历史预测，而不是
 * 每次计算都覆盖旧结果的单一快照。</p>
 */
@Service
public class ShortTermForecastHistoryService {

    public static final int MAX_HORIZON_DAYS = 20;
    private static final int MAX_ANALOGS = 30;
    private static final int LOOKBACK_CALENDAR_DAYS = 420;
    private static final double TARGET_RETURN = 0.15;

    private final ScoreCalculationRepository dailyRepository;
    private final ShortTermHistoryRepository historyRepository;

    public ShortTermForecastHistoryService(ScoreCalculationRepository dailyRepository,
                                           ShortTermHistoryRepository historyRepository) {
        this.dailyRepository = dailyRepository;
        this.historyRepository = historyRepository;
    }

    /** 新建一批预测，永远不覆盖已存在的预测批次。 */
    public ShortTermRunBuildVO rebuild(LocalDate requestedDate, Integer requestedAdjustFlag) {
        int adjustFlag = validFlag(requestedAdjustFlag);
        LocalDate asOfDate = requestedDate == null
                ? dailyRepository.latestDate(adjustFlag) : requestedDate;
        if (asOfDate == null) {
            throw BizException.notFound("行情分表中没有可用于预测的数据，请先导入 stock_daily_00、stock_daily_30 或 stock_daily_60");
        }

        long startedAt = System.currentTimeMillis();
        List<ScoreBar> bars = dailyRepository.loadBars(asOfDate, adjustFlag, LOOKBACK_CALENDAR_DAYS);
        Map<String, List<ScoreBar>> grouped = bars.stream()
                .collect(Collectors.groupingBy(this::key, LinkedHashMap::new, Collectors.toList()));
        List<List<ScoreBar>> eligible = grouped.values().stream()
                .filter(rows -> eligible(rows, asOfDate))
                .toList();

        // 先在内存中计算，确保没有可预测股票时不会创建一个空批次。
        List<List<ForecastPrediction>> drafts = new ArrayList<>();
        for (List<ScoreBar> rows : eligible) {
            List<ForecastPrediction> prediction = forecastPath(rows, asOfDate, adjustFlag);
            if (!prediction.isEmpty()) {
                drafts.add(prediction);
            }
        }
        if (drafts.isEmpty()) {
            throw BizException.badRequest("当前行情数据不足以生成逐日预测，请补充至少 40 个交易日数据");
        }

        ForecastRun run = new ForecastRun();
        run.setForecastDate(asOfDate);
        run.setAdjustFlag(adjustFlag);
        run.setSourceLatestDate(asOfDate);
        run.setModelVersion("analog-daily-v3");
        run.setStockCount(drafts.size());
        historyRepository.createRun(run);

        List<ForecastPrediction> predictions = drafts.stream().flatMap(List::stream).toList();
        predictions.forEach(item -> item.setRunId(run.getId()));
        int savedRows = historyRepository.savePredictions(predictions);
        return new ShortTermRunBuildVO(run.getId(), asOfDate, adjustFlag, grouped.size(), drafts.size(),
                savedRows, MAX_HORIZON_DAYS, System.currentTimeMillis() - startedAt);
    }

    public List<ShortTermRunVO> runs(Integer adjustFlag, Integer limit) {
        return historyRepository.listRuns(validFlag(adjustFlag), QuerySupport.limit(limit, 30, 120));
    }

    public List<LocalDate> sourceDates(Integer adjustFlag, Integer limit) {
        return historyRepository.sourceDates(validFlag(adjustFlag), QuerySupport.limit(limit, 120, 500));
    }

    public PageResult<ShortTermRunSummaryVO> summary(long runId, String keyword, BigDecimal minProbability,
                                                     String requestedSortBy, String requestedSortDirection,
                                                     Integer page, Integer size) {
        requireRun(runId);
        String sortBy = validSortBy(requestedSortBy);
        String sortDirection = validSortDirection(requestedSortDirection);
        int actualPage = QuerySupport.page(page);
        int actualSize = QuerySupport.size(size, 50, QuerySupport.MAX_PAGE_SIZE);
        long total = historyRepository.summaryCount(runId, QuerySupport.trimToNull(keyword), minProbability);
        if (total == 0) return PageResult.empty(actualPage, actualSize);
        List<ShortTermRunSummaryVO> rows = historyRepository.summaryPage(runId,
                QuerySupport.trimToNull(keyword), minProbability,
                sortBy,
                sortDirection,
                QuerySupport.offset(actualPage, actualSize), actualSize);
        return PageResult.of(rows, total, actualPage, actualSize);
    }

    public List<ShortTermDailyForecastVO> daily(long runId, String code) {
        requireRun(runId);
        String actualCode = QuerySupport.trimToNull(code);
        if (actualCode == null) throw BizException.badRequest("code 不能为空");
        return historyRepository.daily(runId, actualCode);
    }

    /**
     * 将当前已经发生的行情写回批次。未来日期仍标记 pending，随着每天新行情入库可重复评估。
     */
    public ShortTermEvaluateVO evaluate(long runId) {
        ShortTermRunVO run = requireRun(runId);
        List<ForecastPrediction> predictions = historyRepository.predictionRows(runId);
        if (predictions.isEmpty()) {
            throw BizException.notFound("预测批次没有明细记录");
        }
        LocalDate latestDate = dailyRepository.latestDate(run.adjustFlag());
        if (latestDate == null) {
            throw BizException.notFound("行情分表中没有可用于评估的数据，请先导入 stock_daily_00、stock_daily_30 或 stock_daily_60");
        }

        List<ScoreBar> actualBars = dailyRepository.loadBarsBetween(run.forecastDate(), latestDate, run.adjustFlag());
        Map<String, List<ScoreBar>> actualGrouped = actualBars.stream()
                .collect(Collectors.groupingBy(this::key, LinkedHashMap::new, Collectors.toList()));
        LocalDateTime evaluatedAt = LocalDateTime.now();
        int completeRows = 0;
        for (ForecastPrediction prediction : predictions) {
            List<ScoreBar> rows = actualGrouped.getOrDefault(prediction.getCode() + "|" + prediction.getMarket(), List.of());
            Evaluation evaluation = actual(rows, run.forecastDate(), prediction.getHorizonDays());
            prediction.setActualTargetDate(evaluation.targetDate());
            prediction.setActualDailyReturn(evaluation.dailyReturn() == null ? null : decimal(evaluation.dailyReturn()));
            prediction.setActualCumulativeReturn(evaluation.cumulativeReturn() == null ? null : decimal(evaluation.cumulativeReturn()));
            prediction.setPredictionError(evaluation.cumulativeReturn() == null || prediction.getPredictedCumulativeReturn() == null
                    ? null : decimal(evaluation.cumulativeReturn() - prediction.getPredictedCumulativeReturn().doubleValue()));
            prediction.setEvaluationStatus(evaluation.complete() ? "complete" : "pending");
            prediction.setEvaluatedAt(evaluatedAt);
            if (evaluation.complete()) completeRows++;
        }
        int updatedRows = historyRepository.updateEvaluations(predictions);
        return new ShortTermEvaluateVO(runId, updatedRows, completeRows, predictions.size() - completeRows, latestDate);
    }

    private ShortTermRunVO requireRun(long runId) {
        ShortTermRunVO run = historyRepository.findRun(runId);
        if (run == null) throw BizException.notFound("预测批次不存在：" + runId);
        return run;
    }

    private List<ForecastPrediction> forecastPath(List<ScoreBar> rows, LocalDate forecastDate, int adjustFlag) {
        Signal current = signalAt(rows, rows.size() - 1);
        if (current == null) return List.of();

        List<AnalogPath> analogs = new ArrayList<>();
        int lastUsableSignal = rows.size() - MAX_HORIZON_DAYS - 1;
        for (int index = 25; index <= lastUsableSignal; index++) {
            Signal historical = signalAt(rows, index);
            if (historical == null) continue;
            double distance = distance(current, historical);
            double[] dailyReturns = forwardDailyReturns(rows, index);
            if (Double.isFinite(distance) && dailyReturns != null) {
                analogs.add(new AnalogPath(distance, dailyReturns));
            }
        }
        analogs.sort(Comparator.comparingDouble(AnalogPath::distance));
        List<AnalogPath> selected = analogs.stream().limit(MAX_ANALOGS).toList();

        double[] dailyReturns = new double[MAX_HORIZON_DAYS];
        double[] probabilities = new double[MAX_HORIZON_DAYS];
        double confidence;
        if (selected.isEmpty()) {
            // 样本不足时退化到技术信号，并显著降低置信度，防止页面把弱信号误读成可靠预测。
            double base20 = current.ret5() * 0.25 + current.ret10() * 0.30 + current.ret20() * 0.45;
            for (int day = 0; day < MAX_HORIZON_DAYS; day++) {
                dailyReturns[day] = clamp(base20 / MAX_HORIZON_DAYS, -0.03, 0.03);
            }
            confidence = 0.10;
        } else {
            double weightSum = 0;
            double[] weightedDaily = new double[MAX_HORIZON_DAYS];
            double[] hitWeights = new double[MAX_HORIZON_DAYS];
            double distanceSum = 0;
            for (AnalogPath analog : selected) {
                double weight = 1.0 / (0.05 + analog.distance());
                weightSum += weight;
                distanceSum += analog.distance();
                double cumulative = 1.0;
                for (int day = 0; day < MAX_HORIZON_DAYS; day++) {
                    weightedDaily[day] += weight * analog.dailyReturns()[day];
                    cumulative *= 1.0 + analog.dailyReturns()[day];
                    if (cumulative - 1.0 >= TARGET_RETURN) hitWeights[day] += weight;
                }
            }
            for (int day = 0; day < MAX_HORIZON_DAYS; day++) {
                dailyReturns[day] = clamp(weightedDaily[day] / weightSum, -0.20, 0.20);
                probabilities[day] = clamp(hitWeights[day] / weightSum, 0, 1);
            }
            double averageDistance = distanceSum / selected.size();
            confidence = Math.min(1.0, selected.size() / (double) MAX_ANALOGS)
                    * (1.0 - Math.min(averageDistance, 1.0));
        }
        confidence = clamp(confidence, 0, 1);

        ScoreBar latest = rows.get(rows.size() - 1);
        List<ForecastPrediction> result = new ArrayList<>(MAX_HORIZON_DAYS);
        double cumulative = 1.0;
        for (int day = 0; day < MAX_HORIZON_DAYS; day++) {
            cumulative *= 1.0 + dailyReturns[day];
            double cumulativeReturn = clamp(cumulative - 1.0, -0.80, 1.50);
            if (selected.isEmpty()) probabilities[day] = cumulativeReturn >= TARGET_RETURN ? 0.25 : 0.05;
            // 综合分：累计预估收益 55% + 达到 15% 的历史概率 35% + 置信度 10%。
            double returnScore = clamp(0.5 + cumulativeReturn * 2.5, 0, 1) * 100;
            double score = returnScore * 0.55 + probabilities[day] * 100 * 0.35 + confidence * 100 * 0.10;

            ForecastPrediction item = new ForecastPrediction();
            item.setCode(latest.code());
            item.setMarket(latest.market());
            item.setForecastDate(forecastDate);
            item.setAdjustFlag(adjustFlag);
            item.setHorizonDays(day + 1);
            item.setPredictedDailyReturn(decimal(dailyReturns[day]));
            item.setPredictedCumulativeReturn(decimal(cumulativeReturn));
            item.setTarget15Probability(decimal(probabilities[day]));
            item.setConfidence(decimal(confidence));
            item.setForecastScore(decimal(score));
            item.setAnalogCount(selected.size());
            item.setEvaluationStatus("pending");
            result.add(item);
        }
        return result;
    }

    private Evaluation actual(List<ScoreBar> rows, LocalDate forecastDate, int horizon) {
        if (rows.isEmpty()) return Evaluation.pending();
        int asOfIndex = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (forecastDate.equals(rows.get(i).tradeDate())) {
                asOfIndex = i;
                break;
            }
        }
        if (asOfIndex < 0 || asOfIndex + horizon >= rows.size()) return Evaluation.pending();
        Double base = closeAt(rows, asOfIndex);
        Double previous = closeAt(rows, asOfIndex + horizon - 1);
        Double target = closeAt(rows, asOfIndex + horizon);
        if (base == null || previous == null || target == null || base <= 0 || previous <= 0) {
            return Evaluation.pending();
        }
        return new Evaluation(rows.get(asOfIndex + horizon).tradeDate(), target / previous - 1,
                target / base - 1, true);
    }

    private boolean eligible(List<ScoreBar> rows, LocalDate asOfDate) {
        if (rows.size() < 40 || !asOfDate.equals(rows.get(rows.size() - 1).tradeDate())) return false;
        ScoreBar latest = rows.get(rows.size() - 1);
        return latest.close() != null && latest.close().signum() > 0
                && !Integer.valueOf(0).equals(latest.tradeStatus())
                && !Integer.valueOf(1).equals(latest.isSt());
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

    private double[] forwardDailyReturns(List<ScoreBar> rows, int index) {
        double[] result = new double[MAX_HORIZON_DAYS];
        for (int day = 1; day <= MAX_HORIZON_DAYS; day++) {
            Double previous = closeAt(rows, index + day - 1);
            Double current = closeAt(rows, index + day);
            if (previous == null || current == null || previous <= 0) return null;
            result[day - 1] = clamp(current / previous - 1, -0.30, 0.30);
        }
        return result;
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

    private String key(ScoreBar row) {
        return row.code() + "|" + row.market();
    }

    private int validFlag(Integer adjustFlag) {
        int flag = adjustFlag == null ? 2 : adjustFlag;
        if (flag < 1 || flag > 3) throw BizException.badRequest("adjustFlag 必须是 1、2 或 3");
        return flag;
    }

    private String validSortBy(String sortBy) {
        if (sortBy == null || sortBy.isBlank()) return "score20";
        return switch (sortBy) {
            case "score20", "predictedCum5", "predictedCum10", "predictedCum15", "predictedCum20" -> sortBy;
            default -> throw BizException.badRequest("sortBy 必须是 score20、predictedCum5、predictedCum10、predictedCum15 或 predictedCum20");
        };
    }

    private String validSortDirection(String sortDirection) {
        if (sortDirection == null || sortDirection.isBlank()) return "desc";
        return switch (sortDirection.toLowerCase()) {
            case "asc", "desc" -> sortDirection.toLowerCase();
            default -> throw BizException.badRequest("sortDirection 必须是 asc 或 desc");
        };
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }

    private record Signal(double ret5, double ret10, double ret20, double trend20, double amountRatio) {}

    private record AnalogPath(double distance, double[] dailyReturns) {}

    private record Evaluation(LocalDate targetDate, Double dailyReturn, Double cumulativeReturn, boolean complete) {
        private static Evaluation pending() { return new Evaluation(null, null, null, false); }
    }
}
