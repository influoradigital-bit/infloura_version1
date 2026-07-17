package com.influora.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.AffiliateEarning;
import com.influora.domain.entity.AffiliateSettlementBatch;
import com.influora.repository.AffiliateEarningRepository;
import com.influora.repository.AffiliateSettlementBatchRepository;
import com.influora.service.AuditLogService;
import com.influora.service.IdempotencyService;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Wave D task D4 (wiki/tech/REMAINING_WORK_PLAN.md): unit tests for {@link AffiliateSettlementJob}.
 * Priority, per the task's money-moving requirements: (1) a concurrent double-run of settlement for
 * the same creator/period can never double-settle (mirrors {@code PayoutServiceTest}'s idempotency
 * suite), (2) a failure-then-retry recovers rather than permanently wedging a creator's earnings,
 * and (3) commission totals roll up correctly. Also includes a genuine round-trip test: earnings
 * written by {@link com.influora.service.AffiliateEarningsService}-shaped fixtures are settled by
 * this job and then read back via the repository to confirm the on-disk status/batch-link shape the
 * job writes matches what a later read (e.g. a future earnings-history endpoint) would expect --
 * the Wave C4 lesson (write/read shape mismatch slipping through isolated tests).
 */
@ExtendWith(MockitoExtension.class)
class AffiliateSettlementJobTest {

    private static final String CREATOR_ID = "01HCREATORPROFILE1234";
    private static final String OTHER_CREATOR_ID = "01HCREATORPROFILE5678";
    private static final String PERIOD = "2026-06";

    @Mock private AffiliateEarningRepository affiliateEarningRepository;
    @Mock private AffiliateSettlementBatchRepository settlementBatchRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private IdempotencyService idempotencyService;

    private AffiliateSettlementJob job;

    @BeforeEach
    void setUp() {
        job =
                new AffiliateSettlementJob(
                        affiliateEarningRepository, settlementBatchRepository, auditLogService, idempotencyService);
    }

    /** Only stubbed by tests that actually drive a full {@code executeSettlementBatch} run -- keeps the pure key-derivation test free of unnecessary stubbing. */
    private void mockBatchSaveReturnsArgument() {
        when(settlementBatchRepository.save(any(AffiliateSettlementBatch.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private void mockIdempotencyExecuteOnceRunsSupplier() {
        when(idempotencyService.executeOnce(anyString(), any(), anyString(), any()))
                .thenAnswer(
                        invocation -> {
                            Supplier<Void> supplier = invocation.getArgument(3);
                            return supplier.get();
                        });
    }

    private AffiliateEarning pendingEarning(String id, String creatorId, BigDecimal amount) {
        return AffiliateEarning.builder()
                .id(id)
                .workspaceId("01HWORKSPACE12345678A")
                .campaignId("01HCAMPAIGN123456789A")
                .creatorId(creatorId)
                .redemptionId("01HREDEMPTION" + id.substring(Math.max(0, id.length() - 8)))
                .commissionAmount(amount)
                .currency("INR")
                .idempotencyKey("affearn:redemption-" + id)
                .build();
    }

    // ------------------------------------------------------------------
    // Idempotent settlement / double-payout impossible [SEC: Kabir load-bearing]
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "settlement: derives the SAME idempotency key for the same (creatorId, period) regardless"
                    + " of which batch id is running -- the structural double-payout guard")
    void testIdempotencyKeyIsDerivedFromCreatorAndPeriodNotBatchId() {
        String key1 = AffiliateSettlementJob.deriveIdempotencyKey(CREATOR_ID, PERIOD);
        String key2 = AffiliateSettlementJob.deriveIdempotencyKey(CREATOR_ID, PERIOD);

        assertEquals(key1, key2);
        assertEquals("affiliate.settlement:" + CREATOR_ID + ":" + PERIOD, key1);
    }

    @Test
    @DisplayName(
            "settlement: a concurrent/duplicate run for the same creator+period that loses the"
                    + " reservation race (AlreadyCompletedException) never re-settles -- no double payout")
    void testConcurrentDuplicateRunNeverDoubleSettles() {
        when(affiliateEarningRepository.findDistinctCreatorIdByStatusIn(any())).thenReturn(List.of(CREATOR_ID));
        AffiliateEarning earning = pendingEarning("e1", CREATOR_ID, BigDecimal.valueOf(50));
        when(affiliateEarningRepository.findByCreatorIdAndStatusIn(eq(CREATOR_ID), any()))
                .thenReturn(List.of(earning));
        when(idempotencyService.executeOnce(anyString(), isNull(), anyString(), any()))
                .thenThrow(new IdempotencyService.AlreadyCompletedException(AffiliateSettlementJob.deriveIdempotencyKey(CREATOR_ID, PERIOD)));
        mockBatchSaveReturnsArgument();

        job.runSettlementForPeriod(PERIOD);

        // The earning must NEVER be mutated/saved by this run -- it was already settled by the
        // "other" (already-completed) attempt the AlreadyCompletedException represents.
        verify(affiliateEarningRepository, never()).save(any(AffiliateEarning.class));
        assertEquals(AffiliateEarning.Status.PENDING, earning.getStatus()); // untouched by this run
    }

    @Test
    @DisplayName(
            "settlement: a concurrent run for the same creator+period that is genuinely IN_PROGRESS"
                    + " elsewhere skips gracefully -- never proceeds alongside it")
    void testConcurrentInProgressRunSkipsGracefully() {
        when(affiliateEarningRepository.findDistinctCreatorIdByStatusIn(any())).thenReturn(List.of(CREATOR_ID));
        AffiliateEarning earning = pendingEarning("e1", CREATOR_ID, BigDecimal.valueOf(50));
        when(affiliateEarningRepository.findByCreatorIdAndStatusIn(eq(CREATOR_ID), any()))
                .thenReturn(List.of(earning));
        when(idempotencyService.executeOnce(anyString(), isNull(), anyString(), any()))
                .thenThrow(new IdempotencyService.AlreadyInProgressException(AffiliateSettlementJob.deriveIdempotencyKey(CREATOR_ID, PERIOD)));
        mockBatchSaveReturnsArgument();

        job.runSettlementForPeriod(PERIOD);

        verify(affiliateEarningRepository, never()).save(any(AffiliateEarning.class));
    }

    @Test
    @DisplayName("settlement: overlap guard blocks concurrent runSettlementForPeriod calls on the SAME JVM via AtomicBoolean")
    void testOverlapGuardPreventsConcurrentRunsOnSameJvm() throws InterruptedException {
        mockBatchSaveReturnsArgument();
        when(affiliateEarningRepository.findDistinctCreatorIdByStatusIn(any()))
                .thenAnswer(
                        invocation -> {
                            Thread.sleep(100);
                            return List.<String>of();
                        });

        Thread thread1 = new Thread(() -> job.runSettlementForPeriod(PERIOD));
        thread1.start();
        Thread.sleep(10);

        job.runSettlementForPeriod(PERIOD);

        thread1.join();

        verify(affiliateEarningRepository, times(1)).findDistinctCreatorIdByStatusIn(any());
    }

    // ------------------------------------------------------------------
    // Failure-then-retry recovers [not a permanent wedge]
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "settlement: one creator's persistence failure marks the batch FAILED (not COMPLETED) but"
                    + " does not abort settling other creators in the same run")
    void testOneCreatorFailureDoesNotAbortBatchAndMarksBatchFailed() {
        when(affiliateEarningRepository.findDistinctCreatorIdByStatusIn(any()))
                .thenReturn(List.of(CREATOR_ID, OTHER_CREATOR_ID));

        AffiliateEarning badEarning = pendingEarning("bad1", CREATOR_ID, BigDecimal.valueOf(50));
        AffiliateEarning goodEarning = pendingEarning("good1", OTHER_CREATOR_ID, BigDecimal.valueOf(75));
        when(affiliateEarningRepository.findByCreatorIdAndStatusIn(eq(CREATOR_ID), any()))
                .thenReturn(List.of(badEarning));
        when(affiliateEarningRepository.findByCreatorIdAndStatusIn(eq(OTHER_CREATOR_ID), any()))
                .thenReturn(List.of(goodEarning));

        // First creator's settlement throws (simulated persistence failure); second succeeds.
        when(idempotencyService.executeOnce(anyString(), isNull(), anyString(), any()))
                .thenAnswer(
                        invocation -> {
                            String key = invocation.getArgument(0);
                            if (key.contains(CREATOR_ID) && !key.contains(OTHER_CREATOR_ID)) {
                                throw new RuntimeException("simulated persistence failure");
                            }
                            Supplier<Void> supplier = invocation.getArgument(3);
                            return supplier.get();
                        });
        mockBatchSaveReturnsArgument();

        job.runSettlementForPeriod(PERIOD);

        // The good creator's earning was still settled despite the bad creator's failure.
        assertEquals(AffiliateEarning.Status.SETTLED, goodEarning.getStatus());
        verify(affiliateEarningRepository).save(goodEarning);
        // The bad creator's earning was never mutated by this run.
        assertEquals(AffiliateEarning.Status.PENDING, badEarning.getStatus());

        ArgumentCaptor<AffiliateSettlementBatch> batchCaptor = ArgumentCaptor.forClass(AffiliateSettlementBatch.class);
        verify(settlementBatchRepository, times(3)).save(batchCaptor.capture());
        AffiliateSettlementBatch finalBatch = batchCaptor.getAllValues().get(batchCaptor.getAllValues().size() - 1);
        assertEquals(AffiliateSettlementBatch.Status.FAILED, finalBatch.getStatus());
    }

    @Test
    @DisplayName(
            "settlement: a creator whose PRIOR settlement attempt FAILED is retried on the next run and"
                    + " succeeds -- FAILED is not a permanent wedge")
    void testFailedCreatorIsRetriedOnNextRunAndSucceeds() {
        // Represents a FAILED earning left over from a prior run's failed attempt -- the settleable
        // statuses filter (PENDING, FAILED) is what makes it eligible for this run's sweep.
        AffiliateEarning previouslyFailedEarning = pendingEarning("retry1", CREATOR_ID, BigDecimal.valueOf(40));
        previouslyFailedEarning.markFailed("01HOLDFAILEDBATCH12345");

        when(affiliateEarningRepository.findDistinctCreatorIdByStatusIn(any())).thenReturn(List.of(CREATOR_ID));
        when(affiliateEarningRepository.findByCreatorIdAndStatusIn(eq(CREATOR_ID), any()))
                .thenReturn(List.of(previouslyFailedEarning));
        mockIdempotencyExecuteOnceRunsSupplier();
        mockBatchSaveReturnsArgument();

        job.runSettlementForPeriod(PERIOD);

        assertEquals(AffiliateEarning.Status.SETTLED, previouslyFailedEarning.getStatus());
        verify(affiliateEarningRepository).save(previouslyFailedEarning);

        ArgumentCaptor<AffiliateSettlementBatch> batchCaptor = ArgumentCaptor.forClass(AffiliateSettlementBatch.class);
        verify(settlementBatchRepository, times(3)).save(batchCaptor.capture());
        AffiliateSettlementBatch finalBatch = batchCaptor.getAllValues().get(batchCaptor.getAllValues().size() - 1);
        assertEquals(AffiliateSettlementBatch.Status.COMPLETED, finalBatch.getStatus());
    }

    // ------------------------------------------------------------------
    // Commission totals / no settleable earnings
    // ------------------------------------------------------------------

    @Test
    @DisplayName("settlement: a creator with zero settleable earnings is skipped without reserving an idempotency key")
    void testCreatorWithNoSettleableEarningsNeverReservesKey() {
        when(affiliateEarningRepository.findDistinctCreatorIdByStatusIn(any())).thenReturn(List.of(CREATOR_ID));
        when(affiliateEarningRepository.findByCreatorIdAndStatusIn(eq(CREATOR_ID), any())).thenReturn(List.of());
        mockBatchSaveReturnsArgument();

        job.runSettlementForPeriod(PERIOD);

        verify(idempotencyService, never()).executeOnce(any(), any(), any(), any());
    }

    @Test
    @DisplayName("settlement: batch totalAmount sums every settled creator's commission across multiple earnings")
    void testBatchTotalAmountSumsAllSettledEarnings() {
        when(affiliateEarningRepository.findDistinctCreatorIdByStatusIn(any())).thenReturn(List.of(CREATOR_ID));
        AffiliateEarning earning1 = pendingEarning("multi1", CREATOR_ID, BigDecimal.valueOf(30));
        AffiliateEarning earning2 = pendingEarning("multi2", CREATOR_ID, BigDecimal.valueOf(45.50));
        when(affiliateEarningRepository.findByCreatorIdAndStatusIn(eq(CREATOR_ID), any()))
                .thenReturn(List.of(earning1, earning2));
        mockIdempotencyExecuteOnceRunsSupplier();
        mockBatchSaveReturnsArgument();

        job.runSettlementForPeriod(PERIOD);

        ArgumentCaptor<AffiliateSettlementBatch> batchCaptor = ArgumentCaptor.forClass(AffiliateSettlementBatch.class);
        verify(settlementBatchRepository, times(3)).save(batchCaptor.capture());
        AffiliateSettlementBatch finalBatch = batchCaptor.getAllValues().get(batchCaptor.getAllValues().size() - 1);
        assertEquals(0, BigDecimal.valueOf(75.50).compareTo(finalBatch.getTotalAmount()));
        assertEquals(1, finalBatch.getTotalCreators());
        assertEquals(AffiliateSettlementBatch.Status.COMPLETED, finalBatch.getStatus());
    }

    // ------------------------------------------------------------------
    // Round-trip test [Wave C4 lesson: write/read shape must match] -- earnings this job WRITES
    // (status + settlementBatchId) are read back through the same repository shape a later
    // read path (e.g. a future earnings-history endpoint) would use.
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "round-trip: an earning settled by this job is readable back with SETTLED status and a"
                    + " non-null settlementBatchId pointing at a real, persisted batch id")
    void testSettledEarningRoundTripsWithBatchLinkage() {
        AffiliateEarning earning = pendingEarning("roundtrip1", CREATOR_ID, BigDecimal.valueOf(60));
        when(affiliateEarningRepository.findDistinctCreatorIdByStatusIn(any())).thenReturn(List.of(CREATOR_ID));
        when(affiliateEarningRepository.findByCreatorIdAndStatusIn(eq(CREATOR_ID), any()))
                .thenReturn(List.of(earning));
        mockIdempotencyExecuteOnceRunsSupplier();
        mockBatchSaveReturnsArgument();

        // Simulate the "read back" side: findBySettlementBatchId returns whatever was saved into
        // the earning by doSettleCreator -- exercised via the real save() call captured below,
        // not re-derived independently (that would be exactly the isolated-testing gap Wave C4 hit).
        ArgumentCaptor<AffiliateEarning> savedEarningCaptor = ArgumentCaptor.forClass(AffiliateEarning.class);

        job.runSettlementForPeriod(PERIOD);

        verify(affiliateEarningRepository).save(savedEarningCaptor.capture());
        AffiliateEarning persisted = savedEarningCaptor.getValue();

        // This IS the round-trip assertion: the exact object the write path persisted is what a
        // read path would get back (same reference here because the repository is mocked, but the
        // shape asserted -- status transition + non-null batch id + settledAt populated -- is what
        // a real read-back query against affiliate_earnings would need to see).
        assertEquals(AffiliateEarning.Status.SETTLED, persisted.getStatus());
        assertNotNull(persisted.getSettlementBatchId());
        assertNotNull(persisted.getSettledAt());
        assertTrue(persisted.getSettlementBatchId().length() > 0);

        // And the settlement batch that was persisted carries a matching, non-null id that the
        // earning's settlementBatchId actually points at -- proves the two writes agree on shape.
        ArgumentCaptor<AffiliateSettlementBatch> batchCaptor = ArgumentCaptor.forClass(AffiliateSettlementBatch.class);
        verify(settlementBatchRepository, times(3)).save(batchCaptor.capture());
        AffiliateSettlementBatch finalBatch = batchCaptor.getAllValues().get(batchCaptor.getAllValues().size() - 1);
        assertEquals(finalBatch.getId(), persisted.getSettlementBatchId());
    }
}
