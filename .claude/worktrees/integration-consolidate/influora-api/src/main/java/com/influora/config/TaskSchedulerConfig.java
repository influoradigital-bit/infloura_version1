package com.influora.config;

import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * M-17 ("all jobs unpaginated on one scheduler thread (email latency spikes)") — the other half of
 * that finding, independent of the per-job pagination fixes applied to {@code
 * PlatformStatsAggregationJob}/{@code ScoreCalculationJob}: with no {@link
 * org.springframework.scheduling.TaskScheduler} bean declared anywhere in this codebase, Spring
 * Boot's auto-configuration backs every {@code @Scheduled} method (the nightly aggregation/scoring/
 * settlement jobs AND {@code EmailWorker}'s 30-second outbox poll) with a single-threaded {@link
 * ThreadPoolTaskScheduler} — every scheduled method in the app serializes onto ONE thread. A long
 * nightly job (even a paginated one; pagination bounds memory, not wall-clock time) blocks {@code
 * EmailWorker.processOutbox} from firing on schedule for its entire duration, which is exactly the
 * "email latency spikes" symptom the audit names.
 *
 * <p>Fix: an explicit pool of {@value #POOL_SIZE} threads so scheduled jobs run concurrently
 * instead of queueing behind each other. {@code @SchedulerLock} (see {@link SchedulerLockConfig})
 * still prevents any single job from running twice concurrently across app instances — this only
 * changes how many *different* jobs this one instance can run at the same time.
 *
 * <p>Sized generously relative to the current job count (a dozen {@code @Scheduled} methods across
 * {@code job/*} and {@code EmailWorker}, only a couple of which ever overlap in practice) — cheap
 * to keep idle threads for, and removes the shared-thread contention entirely rather than just
 * shrinking it.
 */
@Configuration
public class TaskSchedulerConfig implements SchedulingConfigurer {

    private static final int POOL_SIZE = 10;

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.setScheduler(taskScheduler());
    }

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(POOL_SIZE);
        scheduler.setThreadNamePrefix("influora-scheduler-");
        scheduler.setErrorHandler(
                throwable ->
                        LoggerFactory.getLogger(TaskSchedulerConfig.class)
                                .error("Uncaught exception in a @Scheduled task", throwable));
        scheduler.initialize();
        return scheduler;
    }
}
