package com.li.stock.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 预测看板总览。
 *
 * @param latestAsof     最新信号日（无预测数据时为 null）
 * @param horizon        持有期（交易日）
 * @param modelTotal     模型总数（含融合模型）
 * @param championCount  champion 状态模型数
 * @param candidateCount candidate（影子运行）模型数
 * @param retiredCount   已退役模型数
 * @param selectedCount  最新信号日融合模型入选数量
 * @param avgRankIc60    全部在役模型近 60 批 RankIC 均值（无评估数据为 null）
 */
public record ForecastOverviewVO(LocalDate latestAsof, Integer horizon,
                                 long modelTotal, long championCount, long candidateCount,
                                 long retiredCount, long selectedCount,
                                 BigDecimal avgRankIc60) {
}
