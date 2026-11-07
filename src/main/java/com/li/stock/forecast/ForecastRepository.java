package com.li.stock.forecast;

import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * stock-predict 表的只读查询门面。
 */
@Repository
public class ForecastRepository {

    private final ForecastMapper mapper;

    public ForecastRepository(ForecastMapper mapper) {
        this.mapper = mapper;
    }

    /** 任一模型有预测数据的最新信号日。 */
    public LocalDate latestAsofDate(int horizon) {
        return mapper.latestAsofDate(horizon, null);
    }

    /** 指定模型有预测数据的最新信号日。 */
    public LocalDate latestAsofDate(int horizon, String modelId) {
        return mapper.latestAsofDate(horizon, modelId);
    }

    public long countSelected(LocalDate asof, int horizon, String modelId) {
        return mapper.countSelected(asof, horizon, modelId);
    }

    public ForecastOverviewStats overviewStats(int horizon) {
        return mapper.overviewStats(horizon);
    }

    public List<ForecastModelVO> models(int horizon) {
        return mapper.models(horizon);
    }

    public List<ForecastPickVO> picks(LocalDate asof, int horizon, String modelId, int limit) {
        return mapper.picks(asof, horizon, modelId, limit);
    }

    public long countPredictions(LocalDate asof, int horizon, String modelId) {
        return mapper.countPredictions(asof, horizon, modelId);
    }

    public List<ForecastPredictionVO> predictions(LocalDate asof, int horizon, String modelId,
                                                  int limit, int offset) {
        return mapper.predictions(asof, horizon, modelId, limit, offset);
    }

    public List<ForecastEvalVO> eval(String modelId, int horizon, int limit) {
        return mapper.eval(modelId, horizon, limit);
    }

    public List<ForecastWeightVO> latestWeights(int horizon) {
        return mapper.latestWeights(horizon);
    }

    public List<LocalDate> asofDates(int limit) {
        return mapper.asofDates(limit);
    }

    /** overview 聚合行（模型状态计数 + 在役模型近 60 批平均 RankIC）。 */
    public record ForecastOverviewStats(long modelTotal, long championCount,
                                        long candidateCount, long retiredCount,
                                        BigDecimal avgRankIc60) {
    }
}
