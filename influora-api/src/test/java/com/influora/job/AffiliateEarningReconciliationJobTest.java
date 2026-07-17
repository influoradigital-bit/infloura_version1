package com.influora.job;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.CouponRedemption;
import com.influora.repository.CouponRedemptionRepository;
import com.influora.service.AffiliateEarningsService;
import com.influora.service.AuditLogService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * [SEC: Kabir, Wave D task D4 HIGH-1 -- reconciliation floor] Unit tests for {@link
 * AffiliateEarningReconciliationJob}, mirroring {@code StaleTokenCleanupJobTest}'s conventions.
 * Proves the sweep finds orphaned redemptions, backfills them via {@code
 * AffiliateEarningsService#recordEarning} (idempotent, never double-credits), isolates one
 * redemption's failure from the rest of the batch, and uses a grace-period threshold rather than
 * "now" so it never races a redemption still legitimately mid-transaction.
 */
@ExtendWith(MockitoExtension.class)
class AffiliateEarningReconciliationJobTest {

    private static final String COUPON_ID = "01HCOUPON1234567890AB";

    @Mock private CouponRedemptionRepository redemptionRepository;
    @Mock private AffiliateEarningsService affiliateEarningsService;
    @Mock private AuditLogService auditLogService;

    private AffiliateEarningReconciliationJob job;

    @BeforeEach
    void setUp() {
        job = new AffiliateEarningReconciliationJob(redemptionRepository, affiliateEarningsService, auditLogService);
    }

    private static CouponRedemption redemption(String id) {
        return CouponRedemption.builder()
                .id(id)
                .couponId(COUPON_ID)
                .orderId("order-" + id)
                .orderAmount(BigDecimal.valueOf(200))
                .discountApplied(BigDecimal.valueOf(30))
                .customerId("cust-1")
                .idempotencyKey("brand-webhook-key-" + id)
                .build();
    }

    @Test
    @DisplayName(
            "reconcile: an orphaned redemption (no matching affiliate_earnings row) is backfilled by"
                    + " calling recordEarning again -- this is the actual regression guard for the"
                    + " silent-commission-loss bug Kabir's report flagged")
    void testOrphanedRedemptionIsBackfilled() {
        CouponRedemption orphan = redemption("01HORPHANEDREDEMPTION1");
        when(redemptionRepository.findOrphanedWithoutAffiliateEarning(any(Instant.class)))
                .thenReturn(List.of(orphan));

        job.reconcileMissingAffiliateEarnings();

        verify(affiliateEarningsService, times(1)).recordEarning(orphan);
        verify(auditLogService)
                .recordToolCall(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("reconcile: query uses a grace-period threshold in the past, not 'now' -- never races an in-flight redemption")
    void testReconciliationUsesGracePeriodThreshold() {
        when(redemptionRepository.findOrphanedWithoutAffiliateEarning(any(Instant.class)))
                .thenReturn(Collections.emptyList());

        Instant before = Instant.now();
        job.reconcileMissingAffiliateEarnings();

        ArgumentCaptor<Instant> thresholdCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(redemptionRepository).findOrphanedWithoutAffiliateEarning(thresholdCaptor.capture());

        assertTrue(thresholdCaptor.getValue().isBefore(before));
        long minutesBeforeNow = ChronoUnit.MINUTES.between(thresholdCaptor.getValue(), before);
        assertTrue(minutesBeforeNow >= AffiliateEarningReconciliationJob.RECONCILIATION_GRACE_PERIOD.toMinutes() - 1);
    }

    @Test
    @DisplayName("reconcile: nothing orphaned -- no-op, recordEarning never called, no audit entry written")
    void testNoOrphansIsNoOp() {
        when(redemptionRepository.findOrphanedWithoutAffiliateEarning(any(Instant.class)))
                .thenReturn(Collections.emptyList());

        job.reconcileMissingAffiliateEarnings();

        verify(affiliateEarningsService, never()).recordEarning(any());
        verify(auditLogService, never())
                .recordToolCall(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("reconcile failure isolation: one redemption's backfill failure doesn't abort the rest of the sweep")
    void testOneRedemptionFailureDoesNotAbortBatch() {
        CouponRedemption badRedemption = redemption("01HBADREDEMPTION000001");
        CouponRedemption goodRedemption = redemption("01HGOODREDEMPTION00001");
        when(redemptionRepository.findOrphanedWithoutAffiliateEarning(any(Instant.class)))
                .thenReturn(List.of(badRedemption, goodRedemption));
        doThrow(new RuntimeException("still failing"))
                .when(affiliateEarningsService)
                .recordEarning(badRedemption);

        job.reconcileMissingAffiliateEarnings();

        verify(affiliateEarningsService, times(1)).recordEarning(badRedemption);
        verify(affiliateEarningsService, times(1)).recordEarning(goodRedemption);
        verify(auditLogService)
                .recordToolCall(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("overlap guard blocks concurrent runs via AtomicBoolean")
    void testOverlapGuardPreventsConcurrentRuns() throws InterruptedException {
        when(redemptionRepository.findOrphanedWithoutAffiliateEarning(any(Instant.class)))
                .thenAnswer(
                        invocation -> {
                            Thread.sleep(100);
                            return Collections.emptyList();
                        });

        Thread thread1 = new Thread(() -> job.reconcileMissingAffiliateEarnings());
        thread1.start();
        Thread.sleep(10);

        job.reconcileMissingAffiliateEarnings();

        thread1.join();

        verify(redemptionRepository, times(1)).findOrphanedWithoutAffiliateEarning(any(Instant.class));
    }
}
