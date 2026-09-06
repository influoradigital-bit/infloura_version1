package com.influora.config;

import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
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
 *
 * <p>[T-CI-SCHEDULER] {@code @EnableScheduling} now lives HERE rather than on {@code
 * InfluoraApiApplication}, behind a property gate, so integration tests can boot the app context
 * without also starting all 30 {@code @Scheduled} jobs. {@code matchIfMissing = true} means
 * production and any environment that sets nothing keeps the exact prior behaviour (scheduling on);
 * only a deliberate {@code influora.scheduling.enabled=false} turns it off, which is what the
 * integration-test base class sets.
 *
 * <p>The failure that forced this: an unconditional {@code @EnableScheduling} meant every cached
 * {@code @SpringBootTest} context started its own scheduler pool. Those threads outlived the
 * context that owned them, kept polling a Testcontainers MySQL that had already been stopped
 * ({@code ConnectException: Connection refused}, Hikari timing out at {@code total=0}), and being
 * non-daemon they blocked JVM shutdown -- surefire had to kill the fork 30s after {@code
 * System.exit(0)}. The gate is confined to the nested {@code SchedulingActivation} class: the
 * {@link TaskScheduler} bean itself stays unconditional, because {@code AnalyzeSiteTriggerService}
 * injects it directly and is not a {@code @Scheduled} bean. Tests keep an idle pool and lose only
 * the {@code @Scheduled} triggering.
 */
@Configuration
public class TaskSchedulerConfig {

    private static final int POOL_SIZE = 10;

    /**
     * Activates {@code @Scheduled} processing, and ONLY that. Split out from the enclosing class so
     * the property gate switches off the firing of scheduled tasks without also removing the {@link
     * TaskScheduler} bean below.
     *
     * <p>That split is load-bearing, not tidiness: {@code AnalyzeSiteTriggerService} (see its field
     * at {@code AnalyzeSiteTriggerService.java:65}) constructor-injects {@link TaskScheduler} to run
     * one-off deferred work, and it is NOT a {@code @Scheduled} bean. Gating the whole
     * {@code @Configuration} off would delete that bean and leave a live constructor dependency
     * unsatisfiable, so every test context would fail with an unresolvable-dependency error --
     * exactly the class of boot failure this whole task exists to fix. Tests therefore keep a real,
     * idle scheduler pool; what they lose is only the {@code @Scheduled} triggering.
     */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(
            name = "influora.scheduling.enabled",
            havingValue = "true",
            matchIfMissing = true)
    static class SchedulingActivation implements SchedulingConfigurer {

        private final TaskScheduler taskScheduler;

        SchedulingActivation(TaskScheduler taskScheduler) {
            this.taskScheduler = taskScheduler;
        }

        @Override
        public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
            taskRegistrar.setScheduler(taskScheduler);
        }
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
