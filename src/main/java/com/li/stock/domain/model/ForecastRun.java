package com.li.stock.domain.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 一次短期预测任务的批次记录。每次生成预测都单独保留，便于复盘。 */
public class ForecastRun {
    private Long id;
    private LocalDate forecastDate;
    private Integer adjustFlag;
    private LocalDate sourceLatestDate;
    private String modelVersion;
    private Integer stockCount;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public LocalDate getForecastDate() { return forecastDate; }
    public void setForecastDate(LocalDate forecastDate) { this.forecastDate = forecastDate; }
    public Integer getAdjustFlag() { return adjustFlag; }
    public void setAdjustFlag(Integer adjustFlag) { this.adjustFlag = adjustFlag; }
    public LocalDate getSourceLatestDate() { return sourceLatestDate; }
    public void setSourceLatestDate(LocalDate sourceLatestDate) { this.sourceLatestDate = sourceLatestDate; }
    public String getModelVersion() { return modelVersion; }
    public void setModelVersion(String modelVersion) { this.modelVersion = modelVersion; }
    public Integer getStockCount() { return stockCount; }
    public void setStockCount(Integer stockCount) { this.stockCount = stockCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
