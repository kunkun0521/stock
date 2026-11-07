package com.li.stock.forecast;

import com.li.stock.common.PageResult;
import com.li.stock.common.QuerySupport;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * stock-predict 预测结果的查询服务：全部为只读小查询，不加缓存。
 */
@Service
public class ForecastService {

    /** 选股与明细默认看融合模型。 */
    static final String DEFAULT_MODEL = "ensemble";

    private final ForecastRepository repository;

    public ForecastService(ForecastRepository repository) {
        this.repository = repository;
    }

    public ForecastOverviewVO overview(Integer horizon) {
        int h = horizon == null ? 10 : horizon;
        LocalDate latest = repository.latestAsofDate(h);
        long selected = latest == null ? 0 : repository.countSelected(latest, h, ensembleId(h));
        ForecastRepository.ForecastOverviewStats stats = repository.overviewStats(h);
        return new ForecastOverviewVO(latest, h,
                stats == null ? 0 : stats.modelTotal(),
                stats == null ? 0 : stats.championCount(),
                stats == null ? 0 : stats.candidateCount(),
                stats == null ? 0 : stats.retiredCount(),
                selected,
                stats == null ? null : stats.avgRankIc60());
    }

    public List<ForecastModelVO> models(Integer horizon) {
        int h = horizon == null ? 10 : horizon;
        return repository.models(h);
    }

    public List<ForecastPickVO> picks(LocalDate date, Integer horizon, String modelId, Integer limit) {
        int h = horizon == null ? 10 : horizon;
        String model = resolveModel(modelId, h);
        LocalDate asof = date != null ? date : repository.latestAsofDate(h, model);
        if (asof == null) {
            return List.of();
        }
        return repository.picks(asof, h, model, QuerySupport.limit(limit, 25, 100));
    }

    public PageResult<ForecastPredictionVO> predictions(LocalDate date, Integer horizon,
                                                        String modelId, Integer page, Integer size) {
        int h = horizon == null ? 10 : horizon;
        String model = resolveModel(modelId, h);
        LocalDate asof = date != null ? date : repository.latestAsofDate(h, model);
        int p = QuerySupport.page(page);
        int s = QuerySupport.size(size, 50, QuerySupport.MAX_PAGE_SIZE);
        if (asof == null) {
            return PageResult.empty(p, s);
        }
        long total = repository.countPredictions(asof, h, model);
        List<ForecastPredictionVO> items = repository.predictions(asof, h, model,
                s, QuerySupport.offset(p, s));
        return PageResult.of(items, total, p, s);
    }

    public List<ForecastEvalVO> eval(String modelId, Integer horizon, Integer limit) {
        int h = horizon == null ? 10 : horizon;
        String model = QuerySupport.trimToNull(modelId);
        return repository.eval(model, h, QuerySupport.limit(limit, 120, 500));
    }

    public List<ForecastWeightVO> weights(Integer horizon) {
        int h = horizon == null ? 10 : horizon;
        return repository.latestWeights(h);
    }

    public List<LocalDate> dates(Integer limit) {
        return repository.asofDates(QuerySupport.limit(limit, 60, 250));
    }

    private String resolveModel(String modelId, int horizon) {
        String model = QuerySupport.trimToNull(modelId);
        return model != null ? model : ensembleId(horizon);
    }

    private String ensembleId(int horizon) {
        return DEFAULT_MODEL + "_h" + horizon;
    }
}
