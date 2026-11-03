package com.li.stock.repository;

import com.li.stock.domain.model.ForecastPrediction;
import com.li.stock.domain.model.ForecastRun;
import com.li.stock.domain.vo.ShortTermDailyForecastVO;
import com.li.stock.domain.vo.ShortTermRunSummaryVO;
import com.li.stock.domain.vo.ShortTermRunVO;
import com.li.stock.mapper.ShortTermHistoryMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 短期预测历史记录仓储。 */
@Repository
public class ShortTermHistoryRepository {
    private final ShortTermHistoryMapper mapper;

    public ShortTermHistoryRepository(ShortTermHistoryMapper mapper) {
        this.mapper = mapper;
    }

    public ForecastRun createRun(ForecastRun run) {
        mapper.createRun(run);
        return run;
    }

    public int savePredictions(List<ForecastPrediction> list) {
        return list.isEmpty() ? 0 : mapper.savePredictions(list);
    }

    public List<ShortTermRunVO> listRuns(Integer adjustFlag, int limit) {
        return mapper.listRuns(adjustFlag, limit);
    }

    public List<LocalDate> sourceDates(Integer adjustFlag, int limit) {
        return mapper.sourceDates(adjustFlag, limit);
    }

    public ShortTermRunVO findRun(long runId) {
        return mapper.findRun(runId);
    }

    public long summaryCount(long runId, String keyword, BigDecimal minProbability) {
        return mapper.summaryCount(runId, keyword, minProbability);
    }

    public List<ShortTermRunSummaryVO> summaryPage(long runId, String keyword, BigDecimal minProbability,
                                                   String sortBy, String sortDirection, int offset, int size) {
        return mapper.summaryPage(runId, keyword, minProbability, sortBy, sortDirection, offset, size);
    }

    public List<ShortTermDailyForecastVO> daily(long runId, String code) {
        return mapper.daily(runId, code);
    }

    public List<ForecastPrediction> predictionRows(long runId) {
        return mapper.predictionRows(runId);
    }

    public int updateEvaluations(List<ForecastPrediction> list) {
        if (list.isEmpty()) return 0;
        mapper.saveEvaluations(list);
        // MySQL 对 ON DUPLICATE KEY UPDATE 可能按 2 计数，业务上返回实际处理的预测条数。
        return list.size();
    }
}
