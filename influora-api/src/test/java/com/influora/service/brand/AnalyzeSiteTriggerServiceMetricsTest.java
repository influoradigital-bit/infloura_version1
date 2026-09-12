package com.influora.service.brand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.BrandProfile;
import com.influora.integration.ai.AnalyzeSiteAiClient;
import com.influora.integration.ai.AnalyzeSiteAiException;
import com.influora.integration.ai.dto.AnalyzeSiteAiDtos.AnalyzeSiteResponse;
import com.influora.integration.ai.dto.AnalyzeSiteAiDtos.Data;
import com.influora.integration.ai.dto.AnalyzeSiteAiDtos.ErrorDetail;
import com.influora.repository.BrandProfileRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * FIX 3 (2026-09-12 analyze-site prod incident) — {@code analyze_site.attempt} must be counted,
 * tagged by outcome.
 *
 * <p><b>Why this goes red pre-fix.</b> {@code AnalyzeSiteTriggerService} had no {@link
 * io.micrometer.core.instrument.MeterRegistry} at all — the class took five constructor arguments,
 * not six, and incremented nothing. So pre-fix this file does not compile (the six-arg constructor
 * does not exist) and, even if the constructor were widened by hand without the counters, every
 * assertion below would read {@code 0.0} from the registry against an expected {@code 1.0}. The
 * only signal that existed was the per-call {@code log.warn} at {@code
 * AnalyzeSiteAiClient:128-133}, which nothing aggregated; that is how a feature that had NEVER
 * succeeded on live went unnoticed from 2026-08-30 until someone ran a SQL query by hand.
 *
 * <p><b>Why {@code transport_failure} is the tag worth its own test.</b> That is the exact shape
 * the incident produced — an unresolvable base-url host makes {@code httpClient().send()} throw
 * before any SYN leaves the box, and the client wraps that as an {@code AnalyzeSiteAiException}
 * WITH a cause. A non-200 or an unparseable body throws the same exception type with NO cause, and
 * must count as {@code upstream_error} instead: conflating "influora-ai is unreachable" with
 * "influora-ai answered badly" would have blunted the one alarm that mattered.
 *
 * <p>Drives {@code runAnalysis} through the real AFTER_COMMIT path by capturing the {@link
 * TaskScheduler} runnable {@code onAnalyzeSiteRequested} submits and running it inline, so the
 * counted code is the production code path and not a test-only reimplementation of it.
 */
class AnalyzeSiteTriggerServiceMetricsTest {

    private static final String WORKSPACE_ID = "ws1";
    private static final String WEBSITE_URL = "https://acme.example.com";

    private AnalyzeSiteAiClient aiClient;
    private SimpleMeterRegistry meterRegistry;
    private AnalyzeSiteTriggerService service;
    private final AtomicReference<BrandProfile> stored = new AtomicReference<>();
    private final AtomicReference<Runnable> scheduled = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        stored.set(
                BrandProfile.builder()
                        .id("bp_1")
                        .workspaceId(WORKSPACE_ID)
                        .websiteUrl(WEBSITE_URL)
                        .build());

        BrandProfileRepository brandProfileRepository = mock(BrandProfileRepository.class);
        when(brandProfileRepository.findByWorkspaceId(WORKSPACE_ID))
                .thenAnswer(inv -> Optional.ofNullable(stored.get()));
        when(brandProfileRepository.save(any(BrandProfile.class)))
                .thenAnswer(
                        inv -> {
                            BrandProfile profile = inv.getArgument(0);
                            stored.set(profile);
                            return profile;
                        });

        aiClient = mock(AnalyzeSiteAiClient.class);
        meterRegistry = new SimpleMeterRegistry();

        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        doAnswer(
                        inv -> {
                            scheduled.set(inv.getArgument(0));
                            return null;
                        })
                .when(taskScheduler)
                .schedule(any(Runnable.class), any(java.time.Instant.class));

        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        PlatformTransactionManager noopTransactionManager =
                new PlatformTransactionManager() {
                    @Override
                    public TransactionStatus getTransaction(TransactionDefinition definition) {
                        return new SimpleTransactionStatus();
                    }

                    @Override
                    public void commit(TransactionStatus status) {}

                    @Override
                    public void rollback(TransactionStatus status) {}
                };

        service =
                new AnalyzeSiteTriggerService(
                        brandProfileRepository,
                        aiClient,
                        taskScheduler,
                        eventPublisher,
                        noopTransactionManager,
                        meterRegistry);
    }

    /**
     * Runs one full analyze cycle through the production path: trigger() marks ANALYZING and
     * publishes, the AFTER_COMMIT listener hands a runnable to the scheduler, and we run that
     * runnable here (the scheduler is mocked, so nothing is asynchronous or flaky).
     */
    private void runOneAttempt() {
        service.trigger(WORKSPACE_ID, WEBSITE_URL);
        service.onAnalyzeSiteRequested(
                new AnalyzeSiteRequestedEvent(WORKSPACE_ID, WEBSITE_URL));
        Runnable runnable = scheduled.get();
        assertEquals(
                true, runnable != null, "the AFTER_COMMIT listener must have scheduled the analysis");
        runnable.run();
    }

    private double count(String outcome) {
        Counter counter =
                meterRegistry
                        .find(AnalyzeSiteTriggerService.ATTEMPT_COUNTER)
                        .tag(AnalyzeSiteTriggerService.OUTCOME_TAG, outcome)
                        .counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    @DisplayName("transport failure (the incident's shape) counts outcome=transport_failure")
    void testTransportFailureIsCounted() {
        // Exactly what the live box produced: the base-url host did not resolve, so send() threw
        // UnknownHostException and the client wrapped it WITH a cause.
        when(aiClient.analyze(anyString(), anyString()))
                .thenThrow(
                        new AnalyzeSiteAiException(
                                "influora-ai analyze-site call failed",
                                new UnknownHostException("ai.influora.internal")));

        runOneAttempt();

        assertEquals(
                1.0,
                count(AnalyzeSiteTriggerService.OUTCOME_TRANSPORT_FAILURE),
                "an unreachable influora-ai must be counted as transport_failure — this is the"
                        + " series that would have screamed on 2026-08-30");
        assertEquals(0.0, count(AnalyzeSiteTriggerService.OUTCOME_SUCCESS));
        assertEquals(0.0, count(AnalyzeSiteTriggerService.OUTCOME_UPSTREAM_ERROR));
    }

    @Test
    @DisplayName("non-200 / unparseable (cause-less) counts outcome=upstream_error, NOT transport")
    void testCauselessExceptionIsUpstreamErrorNotTransportFailure() {
        // AnalyzeSiteAiClient throws the same exception type with NO cause for a non-200 status and
        // for an unparseable body. Those mean we DID reach influora-ai — counting them as
        // transport_failure would dilute the one alarm that matters.
        when(aiClient.analyze(anyString(), anyString()))
                .thenThrow(
                        new AnalyzeSiteAiException(
                                "influora-ai analyze-site call returned status 502"));

        runOneAttempt();

        assertEquals(1.0, count(AnalyzeSiteTriggerService.OUTCOME_UPSTREAM_ERROR));
        assertEquals(
                0.0,
                count(AnalyzeSiteTriggerService.OUTCOME_TRANSPORT_FAILURE),
                "a reachable-but-broken influora-ai must not be reported as unreachable");
    }

    @Test
    @DisplayName("HTTP 200 with success=false counts outcome=handled_failure")
    void testHandledFailureIsCounted() {
        when(aiClient.analyze(anyString(), anyString()))
                .thenReturn(
                        new AnalyzeSiteResponse(
                                false,
                                null,
                                new ErrorDetail("empty_page", "nothing readable on the page"),
                                "paste_a_link"));

        runOneAttempt();

        assertEquals(1.0, count(AnalyzeSiteTriggerService.OUTCOME_HANDLED_FAILURE));
        assertEquals(
                0.0,
                count(AnalyzeSiteTriggerService.OUTCOME_TRANSPORT_FAILURE),
                "a brand's genuinely unreadable site is a product outcome, not an infra alarm");
    }

    @Test
    @DisplayName("a usable result counts outcome=success")
    void testSuccessIsCounted() {
        Data data = new Data(WEBSITE_URL, List.of("skincare"), Map.of(), "#112233", List.of());
        when(aiClient.analyze(anyString(), anyString()))
                .thenReturn(new AnalyzeSiteResponse(true, data, null, null));

        runOneAttempt();

        assertEquals(1.0, count(AnalyzeSiteTriggerService.OUTCOME_SUCCESS));
        assertEquals(0.0, count(AnalyzeSiteTriggerService.OUTCOME_TRANSPORT_FAILURE));
        assertEquals(0.0, count(AnalyzeSiteTriggerService.OUTCOME_HANDLED_FAILURE));
    }
}
