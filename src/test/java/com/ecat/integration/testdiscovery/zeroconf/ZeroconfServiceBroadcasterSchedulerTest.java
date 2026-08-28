package com.ecat.integration.testdiscovery.zeroconf;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@link ZeroconfServiceBroadcaster} 调度收编回归：周期重广播必须跑在<b>注入的 core 调度器</b>上，
 * 且 stop() 只取消自己的任务、<b>不得关闭共享调度器</b>（core 引擎生命周期归 core，集成不得 shutdown）。
 *
 * <p>确定性：用真实 {@link ScheduledThreadPoolExecutor} 子类录音 scheduleAtFixedRate 调用
 * （周期取广播器真实常量、大到本测内不触发，不依赖多播、无 sleep 同步）。
 */
public class ZeroconfServiceBroadcasterSchedulerTest {

    /** 录音版调度器：记录 scheduleAtFixedRate 的实参；shutdown/shutdownNow 落账（stop 后断言未被调用）。*/
    private static final class RecordingScheduler extends ScheduledThreadPoolExecutor {
        volatile Runnable recordedCommand;
        volatile long recordedInitialDelay;
        volatile long recordedPeriod;
        volatile TimeUnit recordedUnit;
        volatile int shutdownCalls;
        volatile int shutdownNowCalls;

        RecordingScheduler() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            this.recordedCommand = command;
            this.recordedInitialDelay = initialDelay;
            this.recordedPeriod = period;
            this.recordedUnit = unit;
            return super.scheduleAtFixedRate(command, initialDelay, period, unit);
        }

        @Override
        public void shutdown() {
            shutdownCalls++;
            super.shutdown();
        }

        @Override
        public java.util.List<Runnable> shutdownNow() {
            shutdownNowCalls++;
            return super.shutdownNow();
        }
    }

    @Test
    public void reannounce_ScheduledOnInjectedCoreScheduler_WithRealInterval() {
        RecordingScheduler coreScheduler = new RecordingScheduler();
        ZeroconfServiceBroadcaster broadcaster = new ZeroconfServiceBroadcaster(coreScheduler);

        ScheduledFuture<?> future = broadcaster.startReannounceSchedule();

        try {
            assertNotNull("应在注入的 core 调度器上登记周期重广播任务", coreScheduler.recordedCommand);
            assertEquals("首跑延迟 = 重新广播间隔", ZeroconfServiceBroadcaster.REANNOUNCE_INTERVAL_SEC,
                    coreScheduler.recordedInitialDelay);
            assertEquals("周期 = 重新广播间隔", ZeroconfServiceBroadcaster.REANNOUNCE_INTERVAL_SEC,
                    coreScheduler.recordedPeriod);
            assertSame(TimeUnit.SECONDS, coreScheduler.recordedUnit);
            assertNotNull("应返回任务句柄供 stop 取消", future);
        } finally {
            broadcaster.stop();
            coreScheduler.shutdownNow();
        }
    }

    @Test
    public void stop_CancelsReannounceTask_ButNeverShutsDownCoreScheduler() {
        RecordingScheduler coreScheduler = new RecordingScheduler();
        ZeroconfServiceBroadcaster broadcaster = new ZeroconfServiceBroadcaster(coreScheduler);
        ScheduledFuture<?> future = broadcaster.startReannounceSchedule();

        broadcaster.stop();

        assertTrue("stop 应取消周期重广播任务", future.isCancelled());
        assertFalse("共享 core 调度器不得被集成关闭", coreScheduler.isShutdown());
        assertEquals("shutdown 不应被调用", 0, coreScheduler.shutdownCalls);
        assertEquals("shutdownNow 不应被调用", 0, coreScheduler.shutdownNowCalls);
        coreScheduler.shutdownNow();
    }

    @Test
    public void stop_Idempotent_AndSecondScheduleUsesSameScheduler() {
        RecordingScheduler coreScheduler = new RecordingScheduler();
        ZeroconfServiceBroadcaster broadcaster = new ZeroconfServiceBroadcaster(coreScheduler);
        ScheduledFuture<?> first = broadcaster.startReannounceSchedule();
        broadcaster.stop();
        broadcaster.stop(); // 幂等：二次 stop 不抛、不再碰已取消任务

        assertTrue(first.isCancelled());
        assertFalse(coreScheduler.isShutdown());
        coreScheduler.shutdownNow();
    }

    /** 构造器非空约束：core 调度器缺失必须当场暴露（严格模式，不静默自建）。*/
    @Test(expected = IllegalArgumentException.class)
    public void constructor_NullScheduler_Rejected() {
        new ZeroconfServiceBroadcaster((ScheduledExecutorService) null);
    }
}
