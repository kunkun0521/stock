package com.li.stock.mapper;

import com.li.stock.domain.model.ForecastPrediction;
import com.li.stock.domain.model.ForecastRun;
import com.li.stock.domain.model.ScoreBar;
import com.li.stock.domain.vo.ShortTermDailyForecastVO;
import com.li.stock.domain.vo.ShortTermRunSummaryVO;
import com.li.stock.domain.vo.ShortTermRunVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 逐日短期预测批次及其实际结果的 MyBatis 数据访问层。 */
@Mapper
public interface ShortTermHistoryMapper {
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int createRun(ForecastRun run);

    int savePredictions(@Param("list") List<ForecastPrediction> list);

    List<ShortTermRunVO> listRuns(@Param("adjustFlag") Integer adjustFlag, @Param("limit") int limit);

    List<java.time.LocalDate> sourceDates(@Param("adjustFlag") Integer adjustFlag, @Param("limit") int limit);

    ShortTermRunVO findRun(@Param("runId") long runId);

    long summaryCount(@Param("runId") long runId,
                      @Param("keyword") String keyword,
                      @Param("minProbability") BigDecimal minProbability);

    List<ShortTermRunSummaryVO> summaryPage(@Param("runId") long runId,
                                            @Param("keyword") String keyword,
                                            @Param("minProbability") BigDecimal minProbability,
                                            @Param("sortBy") String sortBy,
                                            @Param("sortDirection") String sortDirection,
                                            @Param("offset") int offset,
                                            @Param("size") int size);

    List<ShortTermDailyForecastVO> daily(@Param("runId") long runId, @Param("code") String code);

    List<ForecastPrediction> predictionRows(@Param("runId") long runId);

    int saveEvaluations(@Param("list") List<ForecastPrediction> list);
}
