package com.li.stock.schedule;

import com.li.stock.domain.vo.ScoreScheduleRunVO;
import com.li.stock.service.ScoreScheduleService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 定时触发与启动补跑的开关、天数传参：用信号量等待后台线程，不依赖 Spring 上下文。 */
class ScoreScheduledTaskTest {

    private static void field(Object target, String name, Object value) throws Exception {
        var f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static final class RecordingService extends ScoreScheduleService {
        private final List<String> calls = new CopyOnWriteArrayList<>();
        private final CountDownLatch latch;

        RecordingService(CountDownLatch latch) {
            super(null, null);
            this.latch = latch;
        }

        @Override
        public boolean isRunning() {
            return false;
        }

        @Override
        public ScoreScheduleRunVO rebuildRecent(String trigger, int days, int adjustFlag) {
            calls.add(trigger + "|" + days + "|" + adjustFlag);
            latch.countDown();
            return ScoreScheduleRunVO.skipped(trigger, adjustFlag);
        }
    }

    private static ScoreScheduledTask task(RecordingService service, boolean enabled,
                                           boolean startupEnabled) throws Exception {
        ScoreScheduledTask task = new ScoreScheduledTask(service);
        field(task, "enabled", enabled);
        field(task, "startupEnabled", startupEnabled);
        field(task, "startupDays", 10);
        field(task, "startupDelayMillis", 0L);
        field(task, "dailyDays", 1);
        field(task, "adjustFlag", 2);
        return task;
    }

    @Test
    void startupTriggersTenTradeDaysAndScheduledTriggersLatestDay() throws Exception {
        CountDownLatch latch = new CountDownLatch(2);
        RecordingService service = new RecordingService(latch);
        ScoreScheduledTask task = task(service, true, true);
        try {
            task.rebuildOnStartup();
            task.scheduledRebuild();
            assertTrue(latch.await(5, TimeUnit.SECONDS), "两个触发都应提交到后台线程");
            assertEquals(2, service.calls.size());
            assertTrue(service.calls.contains("startup|10|2"));
            assertTrue(service.calls.contains("scheduled|1|2"));
        } finally {
            task.shutdown();
        }
    }

    @Test
    void disabledTaskDoesNotTriggerAnything() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        RecordingService service = new RecordingService(latch);
        ScoreScheduledTask task = task(service, false, true);
        try {
            task.rebuildOnStartup();
            task.scheduledRebuild();
            assertTrue(!latch.await(300, TimeUnit.MILLISECONDS), "停用后不应提交重算任务");
            assertTrue(service.calls.isEmpty());
        } finally {
            task.shutdown();
        }
    }

    @Test
    void startupCanBeDisabledWhileScheduledStaysEnabled() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        RecordingService service = new RecordingService(latch);
        ScoreScheduledTask task = task(service, true, false);
        try {
            task.rebuildOnStartup();
            assertTrue(!latch.await(300, TimeUnit.MILLISECONDS), "关闭启动补跑后不应立即重算");
            task.scheduledRebuild();
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertEquals(List.of("scheduled|1|2"), service.calls);
        } finally {
            task.shutdown();
        }
    }
}
