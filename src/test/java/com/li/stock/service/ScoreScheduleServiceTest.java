package com.li.stock.service;

import com.li.stock.common.BizException;
import com.li.stock.domain.vo.ScoreBuildVO;
import com.li.stock.domain.vo.ScoreScheduleRunVO;
import com.li.stock.repository.ScoreScheduleRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 评分自动计算编排逻辑：全部用匿名子类替换数据库与算法，不依赖 Spring 上下文。 */
class ScoreScheduleServiceTest {

    /** 模拟 Mapper 结果：按交易日倒序返回，与 SQL 的 ORDER BY DESC 一致。 */
    private static final List<LocalDate> TRADE_DATES = List.of(
            LocalDate.of(2026, 11, 11), LocalDate.of(2026, 11, 10),
            LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 6),
            LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 4),
            LocalDate.of(2026, 11, 3), LocalDate.of(2026, 10, 30),
            LocalDate.of(2026, 10, 29), LocalDate.of(2026, 10, 28),
            LocalDate.of(2026, 10, 27), LocalDate.of(2026, 10, 26));

    private static ScoreScheduleRepository repository(List<LocalDate> dates) {
        return new ScoreScheduleRepository(null) {
            @Override
            public List<LocalDate> recentTradeDates(int adjustFlag, int limit) {
                return dates.stream().limit(limit).toList();
            }
        };
    }

    private static ScoreCalculationService calculation(List<LocalDate> calls, Set<LocalDate> failing) {
        return new ScoreCalculationService(null) {
            @Override
            public ScoreBuildVO rebuild(LocalDate requestedDate, Integer requestedAdjustFlag) {
                if (failing.contains(requestedDate)) {
                    throw BizException.notFound("模拟失败 " + requestedDate);
                }
                calls.add(requestedDate);
                return new ScoreBuildVO(requestedDate, requestedAdjustFlag, 100, 90, 90, 10);
            }
        };
    }

    @Test
    void startupRebuildsLastTenTradeDaysFromOldestToNewest() {
        List<LocalDate> calls = new ArrayList<>();
        ScoreScheduleService service = new ScoreScheduleService(calculation(calls, Set.of()), repository(TRADE_DATES));

        ScoreScheduleRunVO run = service.rebuildRecent("startup", 10, 2);

        assertEquals(10, run.plannedDays());
        assertEquals(10, run.successDays());
        assertEquals(0, run.failedDays());
        assertEquals(900, run.savedRows());
        assertEquals(2, run.adjustFlag());
        assertFalse(run.skipped());
        assertEquals(10, run.details().size());
        // SQL 返回倒序，服务层按日期从旧到新逐个重算
        List<LocalDate> expected = TRADE_DATES.subList(0, 10).stream().sorted().toList();
        assertEquals(expected, calls);
        assertTrue(run.details().get(0).startsWith(expected.get(0).toString()));
    }

    @Test
    void singleDayFailureDoesNotStopRemainingDays() {
        List<LocalDate> calls = new ArrayList<>();
        LocalDate broken = TRADE_DATES.get(3);
        ScoreScheduleService service = new ScoreScheduleService(
                calculation(calls, Set.of(broken)), repository(TRADE_DATES));

        ScoreScheduleRunVO run = service.rebuildRecent("scheduled", 5, 2);

        assertEquals(5, run.plannedDays());
        assertEquals(4, run.successDays());
        assertEquals(1, run.failedDays());
        assertEquals(360, run.savedRows());
        assertFalse(calls.contains(broken));
        assertTrue(run.details().stream().anyMatch(text -> text.contains("失败")));
    }

    @Test
    void noTradeDateReturnsEmptySummary() {
        ScoreScheduleService service = new ScoreScheduleService(
                calculation(new ArrayList<>(), Set.of()), repository(List.of()));

        ScoreScheduleRunVO run = service.rebuildRecent("startup", 10, 2);

        assertEquals(0, run.plannedDays());
        assertEquals(0, run.successDays());
        assertEquals(0, run.failedDays());
        assertFalse(run.skipped());
        assertTrue(run.details().get(0).contains("没有可用交易日"));
    }

    @Test
    void clampsDaysAndFallsBackToDefaultAdjustFlag() {
        List<LocalDate> calls = new ArrayList<>();
        ScoreScheduleService service = new ScoreScheduleService(calculation(calls, Set.of()), repository(TRADE_DATES));

        ScoreScheduleRunVO tooSmall = service.rebuildRecent("startup", 0, 9);
        assertEquals(1, tooSmall.plannedDays());
        assertEquals(2, tooSmall.adjustFlag());

        ScoreScheduleRunVO tooLarge = service.rebuildRecent("startup", 999, 3);
        assertEquals(TRADE_DATES.size(), tooLarge.plannedDays());
        assertEquals(3, tooLarge.adjustFlag());
    }

    @Test
    void tradeDateQueryFailureIsCapturedInsteadOfThrowing() {
        ScoreScheduleRepository broken = new ScoreScheduleRepository(null) {
            @Override
            public List<LocalDate> recentTradeDates(int adjustFlag, int limit) {
                throw new IllegalStateException("数据库不可用");
            }
        };
        ScoreScheduleService service = new ScoreScheduleService(
                calculation(new ArrayList<>(), Set.of()), broken);

        ScoreScheduleRunVO run = service.rebuildRecent("startup", 10, 2);

        assertEquals(0, run.plannedDays());
        assertEquals(1, run.failedDays());
        assertTrue(run.details().get(0).contains("查询最近交易日失败"));
        assertFalse(service.isRunning(), "异常后必须释放互斥，下一次任务才能执行");
    }
}
