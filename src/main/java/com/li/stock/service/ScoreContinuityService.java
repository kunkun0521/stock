package com.li.stock.service;

import com.li.stock.common.BizException;
import com.li.stock.common.PageResult;
import com.li.stock.common.QuerySupport;
import com.li.stock.domain.model.ScoreDailyPoint;
import com.li.stock.domain.vo.ScoreContinuityItemVO;
import com.li.stock.domain.vo.ScoreContinuityVO;
import com.li.stock.repository.ScoreContinuityRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 连续评分区间筛选：一次读取最近 20 个评分交易日的数据，在内存中同时算出
 * 全部「连续天数 × 分数区间」的命中矩阵和当前组合的分页明细。
 *
 * <p>纯只读，不写入任何表，也不改动原有评分排行逻辑。</p>
 */
@Service
public class ScoreContinuityService {

    private static final Set<String> SORTS = Set.of("latest", "change", "avg", "code");

    private final ScoreContinuityRepository repository;

    public ScoreContinuityService(ScoreContinuityRepository repository) {
        this.repository = repository;
    }

    public ScoreContinuityVO query(LocalDate date, Integer adjustFlag, Integer days, String bandKey,
                                   String keyword, String sort, Integer page, Integer size) {
        int flag = adjustFlag == null ? 2 : adjustFlag;
        if (flag < 1 || flag > 3) {
            throw BizException.badRequest("adjustFlag 必须是 1、2 或 3");
        }
        int selectedDays = days == null ? 3 : days;
        if (!ScoreContinuityAnalyzer.DAY_OPTIONS.contains(selectedDays)) {
            throw BizException.badRequest("连续天数仅支持 3、5、7、10、15、20 天");
        }
        ScoreContinuityVO.Band band = ScoreContinuityAnalyzer.band(bandKey);
        if (band == null) {
            throw BizException.badRequest("不支持的评分区间：" + bandKey);
        }
        // Set.of(...) 的 contains(null) 会抛 NPE，未传排序参数时先判空
        String actualSort = sort != null && SORTS.contains(sort) ? sort : "latest";
        int actualPage = QuerySupport.page(page);
        int actualSize = QuerySupport.size(size, 20, QuerySupport.MAX_PAGE_SIZE);
        String actualKeyword = QuerySupport.trimToNull(keyword);

        List<LocalDate> desc = repository.availableDates(flag, date, ScoreContinuityAnalyzer.MAX_WINDOW);
        List<LocalDate> datesAsc = new ArrayList<>(desc);
        Collections.reverse(datesAsc);

        List<String> warnings = new ArrayList<>();
        if (datesAsc.isEmpty()) {
            warnings.add("评分表 stock_score_daily 暂无数据，或所选评分日之前没有记录；请先运行评分任务生成多日评分。");
        } else if (datesAsc.size() < ScoreContinuityAnalyzer.MAX_WINDOW) {
            String unavailable = ScoreContinuityAnalyzer.DAY_OPTIONS.stream()
                    .filter(option -> option > datesAsc.size())
                    .map(String::valueOf)
                    .collect(Collectors.joining("、"));
            warnings.add("当前仅有 " + datesAsc.size() + " 个评分交易日，连续 " + unavailable
                    + " 天暂无法判断；补齐每日评分记录后会自动生效。");
        }

        List<ScoreDailyPoint> rows = datesAsc.isEmpty()
                ? List.of()
                : repository.loadScores(flag, datesAsc.get(0), datesAsc.get(datesAsc.size() - 1), actualKeyword);
        if (actualKeyword != null && rows.isEmpty()) {
            warnings.add("关键词「" + actualKeyword + "」没有匹配到评分记录，清空关键词可查看全市场结果。");
        }

        ScoreContinuityAnalyzer.Analysis analysis = ScoreContinuityAnalyzer.analyze(
                rows, datesAsc, selectedDays, band, actualSort);
        List<ScoreContinuityItemVO> items = analysis.items();
        long total = items.size();
        int offset = QuerySupport.offset(actualPage, actualSize);
        List<ScoreContinuityItemVO> pageItems = offset >= total
                ? List.of()
                : items.subList(offset, (int) Math.min(total, (long) offset + actualSize));
        PageResult<ScoreContinuityItemVO> result = PageResult.of(pageItems, total, actualPage, actualSize);

        LocalDate asOf = datesAsc.isEmpty() ? date : datesAsc.get(datesAsc.size() - 1);
        return new ScoreContinuityVO(
                asOf, flag, datesAsc, datesAsc.size(),
                ScoreContinuityAnalyzer.DAY_OPTIONS, ScoreContinuityAnalyzer.BANDS, analysis.matrix(),
                selectedDays, band.key(), band.label(), band.min(), band.max(),
                analysis.universeSize(), result, warnings);
    }
}
