package com.li.stock.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 一只股票在一次预测批次中的一个目标交易日预测及其实际结果。 */
public class ForecastPrediction {
    private Long runId;
    private String code;
    private String market;
    private LocalDate forecastDate;
    private Integer adjustFlag;
    private Integer horizonDays;
    private BigDecimal predictedDailyReturn;
    private BigDecimal predictedCumulativeReturn;
    private BigDecimal target15Probability;
    private BigDecimal confidence;
    private BigDecimal forecastScore;
    private Integer analogCount;
    private LocalDate actualTargetDate;
    private BigDecimal actualDailyReturn;
    private BigDecimal actualCumulativeReturn;
    private BigDecimal predictionError;
    private String evaluationStatus;
    private LocalDateTime evaluatedAt;

    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getMarket() { return market; }
    public void setMarket(String market) { this.market = market; }
    public LocalDate getForecastDate() { return forecastDate; }
    public void setForecastDate(LocalDate forecastDate) { this.forecastDate = forecastDate; }
    public Integer getAdjustFlag() { return adjustFlag; }
    public void setAdjustFlag(Integer adjustFlag) { this.adjustFlag = adjustFlag; }
    public Integer getHorizonDays() { return horizonDays; }
    public void setHorizonDays(Integer horizonDays) { this.horizonDays = horizonDays; }
    public BigDecimal getPredictedDailyReturn() { return predictedDailyReturn; }
    public void setPredictedDailyReturn(BigDecimal predictedDailyReturn) { this.predictedDailyReturn = predictedDailyReturn; }
    public BigDecimal getPredictedCumulativeReturn() { return predictedCumulativeReturn; }
    public void setPredictedCumulativeReturn(BigDecimal predictedCumulativeReturn) { this.predictedCumulativeReturn = predictedCumulativeReturn; }
    public BigDecimal getTarget15Probability() { return target15Probability; }
    public void setTarget15Probability(BigDecimal target15Probability) { this.target15Probability = target15Probability; }
    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal confidence) { this.confidence = confidence; }
    public BigDecimal getForecastScore() { return forecastScore; }
    public void setForecastScore(BigDecimal forecastScore) { this.forecastScore = forecastScore; }
    public Integer getAnalogCount() { return analogCount; }
    public void setAnalogCount(Integer analogCount) { this.analogCount = analogCount; }
    public LocalDate getActualTargetDate() { return actualTargetDate; }
    public void setActualTargetDate(LocalDate actualTargetDate) { this.actualTargetDate = actualTargetDate; }
    public BigDecimal getActualDailyReturn() { return actualDailyReturn; }
    public void setActualDailyReturn(BigDecimal actualDailyReturn) { this.actualDailyReturn = actualDailyReturn; }
    public BigDecimal getActualCumulativeReturn() { return actualCumulativeReturn; }
    public void setActualCumulativeReturn(BigDecimal actualCumulativeReturn) { this.actualCumulativeReturn = actualCumulativeReturn; }
    public BigDecimal getPredictionError() { return predictionError; }
    public void setPredictionError(BigDecimal predictionError) { this.predictionError = predictionError; }
    public String getEvaluationStatus() { return evaluationStatus; }
    public void setEvaluationStatus(String evaluationStatus) { this.evaluationStatus = evaluationStatus; }
    public LocalDateTime getEvaluatedAt() { return evaluatedAt; }
    public void setEvaluatedAt(LocalDateTime evaluatedAt) { this.evaluatedAt = evaluatedAt; }
}
