package com.influora.config;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * H-13 (INFLUORA-PRODUCTION-READINESS-AUDIT-2026-07-14.md, CTO-approved) — distributed locking for
 * every {@code @Scheduled} job in this app. Without this, every job was guarded (if at all) only by
 * a per-JVM {@code AtomicBoolean}, which does nothing across multiple app instances: on a
 * multi-instance deploy, two instances' {@code MetricsPollingJob} both poll the same creators
 * (double Meta API spend + rate-limit burn), two instances' {@code ScoreCalculationJob}/{@code
 * PlatformStatsAggregationJob} both write duplicate/conflicting rows, two instances' dunning/
 * settlement jobs race each other, and the {@code EmailWorker} outbox poll can double-send.
 *
 * <p>Reuses the existing MySQL datasource (the {@code shedlock} table, V66 migration) — no new
 * infrastructure. {@code defaultLockAtMostFor} is the crash-safety upper bound: if an instance dies
 * mid-job without releasing its lock, another instance can take over after this long. Each {@code
 * @SchedulerLock} annotation on an individual job can (and mostly does) override both
 * {@code lockAtMostFor}/{@code lockAtLeastFor} with a value sized to that job's own expected
 * runtime — this default only applies to the (should-be-none) case of a caller that forgets to.
 *
 * <p><b>UNVERIFIED — needs {@code mvn verify}:</b> this environment has no Maven available, so the
 * {@code shedlock-spring}/{@code shedlock-provider-jdbc-template} coordinates in {@code pom.xml}
 * have not actually been resolved or compiled against this project's Spring Boot 3.3.5 + the rest
 * of its dependency tree. Confirm on first real build; bump the version in {@code pom.xml} if
 * resolution or compilation fails.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class SchedulerLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(dataSource);
    }
}
