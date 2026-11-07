package com.li.stock.forecast;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface ForecastMapper {

    LocalDate latestAsofDate(@Param("horizon") int horizon, @Param("modelId") String modelId);

    long countSelected(@Param("asof") LocalDate asof, @Param("horizon") int horizon,
                       @Param("modelId") String modelId);

    ForecastRepository.ForecastOverviewStats overviewStats(@Param("horizon") int horizon);

    List<ForecastModelVO> models(@Param("horizon") int horizon);

    List<ForecastPickVO> picks(@Param("asof") LocalDate asof, @Param("horizon") int horizon,
                               @Param("modelId") String modelId, @Param("limit") int limit);

    long countPredictions(@Param("asof") LocalDate asof, @Param("horizon") int horizon,
                          @Param("modelId") String modelId);

    List<ForecastPredictionVO> predictions(@Param("asof") LocalDate asof, @Param("horizon") int horizon,
                                           @Param("modelId") String modelId,
                                           @Param("limit") int limit, @Param("offset") int offset);

    List<ForecastEvalVO> eval(@Param("modelId") String modelId, @Param("horizon") int horizon,
                              @Param("limit") int limit);

    List<ForecastWeightVO> latestWeights(@Param("horizon") int horizon);

    List<LocalDate> asofDates(@Param("limit") int limit);
}
