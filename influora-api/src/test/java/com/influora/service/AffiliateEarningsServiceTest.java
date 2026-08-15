package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.AffiliateEarning;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.CouponCode;
import com.influora.domain.entity.CouponRedemption;
import com.influora.domain.entity.CreatorProfile;
import com.influora.repository.AffiliateEarningRepository;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CouponCodeRepository;
import com.influora.repository.CouponRedemptionRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Wave D task D4 (wiki/tech/REMAINING_WORK_PLAN.md): unit tests for {@link
 * AffiliateEarningsService}. Priority is the same class of guarantee {@code PayoutServiceTest}/
 * {@code RedemptionServiceTest} already prove for their own classes: a retried/duplicate call for
 * the same redemption can never double-credit a creator, validation runs before the idempotency key
 * is ever reserved, and commission math is correct.
 */
@ExtendWith(MockitoExtension.class)
class AffiliateEarningsServiceTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String CAMPAIGN_ID = "01HCAMPAIGN123456789A";
    private static final String CREATOR_ID = "01HCREATORPROFILE1234";
    private static final String COUPON_ID = "01HCOUPON1234567890AB";
    private static final String REDEMPTION_ID = "01HREDEMPTION12345678";

    @Mock private AffiliateEarningRepository affiliateEarningRepository;
    @Mock private CouponCodeRepository couponCodeRepository;
    @Mock private CouponRedemptionRepository couponRedemptionRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private CreatorContextService creatorContext;
    @Mock private AuditLogService auditLogService;
    @Mock private IdempotencyService idempotencyService;

    private AffiliateEarningsService service;

    @BeforeEach
    void setUp() {
        // [SEC: Kabir HIGH-1 fix] Production code now takes an @Lazy self-reference so
        // recordEarning can invoke doRecordEarning through the (real) Spring proxy instead of via
        // same-bean self-invocation -- see AffiliateEarningsService class javadoc. There is no
        // Spring container/proxy in this plain Mockito unit test, so `self` is wired back to
        // `service` itself via the test-only setSelfForTesting seam: calling `self.doRecordEarning`
        // here is equivalent to calling `this.doRecordEarning` would have been, which is exactly
        // right for testing this class's OWN business logic (the self-invocation bug only matters
        // at runtime, where Spring's real proxy intercepts calls through `self` but not `this`).
        service =
                new AffiliateEarningsService(
                        affiliateEarningRepository,
                        couponCodeRepository,
                        couponRedemptionRepository,
                        campaignRepository,
                        workspaceRepository,
                        creatorContext,
                        auditLogService,
                        idempotencyService,
                        null);
        service.setSelfForTesting(service);
    }

    private void mockIdempotencyExecuteOnce() {
        when(idempotencyService.executeOnce(anyString(), any(), anyString(), any()))
                .thenAnswer(
                        invocation -> {
                            Supplier<AffiliateEarning> supplier = invocation.getArgument(3);
                            return supplier.get();
                        });
    }

    private CouponCode coupon() {
        return CouponCode.builder()
                .id(COUPON_ID)
                .workspaceId(WORKSPACE_ID)
                .campaignId(CAMPAIGN_ID)
                .creatorId(CREATOR_ID)
                .code("PRIYA_SUMMER25")
                .discountType("percentage")
                .discountValue(BigDecimal.valueOf(15))
                .build();
    }

    private CouponRedemption redemption(BigDecimal orderAmount) {
        return CouponRedemption.builder()
                .id(REDEMPTION_ID)
                .couponId(COUPON_ID)
                .orderId("order-1")
                .orderAmount(orderAmount)
                .discountApplied(BigDecimal.valueOf(30))
                .customerId("cust-1")
                .idempotencyKey("brand-webhook-key")
                .build();
    }

    // ------------------------------------------------------------------
    // Idempotency [SEC: Kabir] -- double-credit is structurally impossible
    // ------------------------------------------------------------------

    @Test
    @DisplayName("recordEarning: a redemption already credited (UNIQUE(redemption_id) hit) returns the SAME earning and never touches the idempotency service")
    void testAlreadyCreditedRedemptionIsCleanNoOp() {
        AffiliateEarning existing =
                AffiliateEarning.builder()
                        .id("01HEXISTINGEARNING123")
                        .workspaceId(WORKSPACE_ID)
                        .campaignId(CAMPAIGN_ID)
                        .creatorId(CREATOR_ID)
                        .redemptionId(REDEMPTION_ID)
                        .commissionAmount(BigDecimal.valueOf(20))
                        .currency("INR")
                        .idempotencyKey("affearn:" + REDEMPTION_ID)
                        .build();
        when(affiliateEarningRepository.findByRedemptionId(REDEMPTION_ID)).thenReturn(Optional.of(existing));

        AffiliateEarning result = service.recordEarning(redemption(BigDecimal.valueOf(200)));

        assertSame(existing, result);
        verifyNoInteractions(couponCodeRepository, idempotencyService, auditLogService);
        verify(affiliateEarningRepository, never()).save(any());
    }

    @Test
    @DisplayName("recordEarning: first call for a fresh redemption computes commission exactly once and persists it")
    void testFirstCallRecordsEarningExactlyOnce() {
        when(affiliateEarningRepository.findByRedemptionId(REDEMPTION_ID)).thenReturn(Optional.empty());
        when(couponCodeRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon()));
        mockIdempotencyExecuteOnce();
        when(affiliateEarningRepository.save(any(AffiliateEarning.class))).thenAnswer(inv -> inv.getArgument(0));

        AffiliateEarning result = service.recordEarning(redemption(BigDecimal.valueOf(200)));

        assertEquals(WORKSPACE_ID, result.getWorkspaceId());
        assertEquals(CAMPAIGN_ID, result.getCampaignId());
        assertEquals(CREATOR_ID, result.getCreatorId());
        assertEquals(REDEMPTION_ID, result.getRedemptionId());
        // 200 * 0.10 = 20.00
        assertEquals(0, BigDecimal.valueOf(20.00).compareTo(result.getCommissionAmount()));
        assertEquals(AffiliateEarning.Status.PENDING, result.getStatus());
        verify(affiliateEarningRepository, times(1)).save(any(AffiliateEarning.class));
        verify(auditLogService, times(1))
                .recordMoneyEvent(
                        eq(WORKSPACE_ID), eq("AFFILIATE_EARNING_RECORDED"), any(), isNull(), isNull(), anyString(), anyMap());
    }

    @Test
    @DisplayName(
            "recordEarning: loses the concurrent reservation race but the winner's row is already"
                    + " visible -- returns it gracefully instead of double-crediting or throwing 500")
    void testConcurrentRaceLoserReturnsWinnerGracefully() {
        AffiliateEarning winner =
                AffiliateEarning.builder()
                        .id("01HWINNEREARNING12345")
                        .workspaceId(WORKSPACE_ID)
                        .campaignId(CAMPAIGN_ID)
                        .creatorId(CREATOR_ID)
                        .redemptionId(REDEMPTION_ID)
                        .commissionAmount(BigDecimal.valueOf(20))
                        .currency("INR")
                        .idempotencyKey("affearn:" + REDEMPTION_ID)
                        .build();
        when(affiliateEarningRepository.findByRedemptionId(REDEMPTION_ID))
                .thenReturn(Optional.empty()) // pre-check race window
                .thenReturn(Optional.of(winner)); // post-race replay
        when(couponCodeRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon()));
        when(idempotencyService.executeOnce(anyString(), any(), anyString(), any()))
                .thenThrow(new IdempotencyService.AlreadyInProgressException("affearn:" + REDEMPTION_ID));

        AffiliateEarning result = service.recordEarning(redemption(BigDecimal.valueOf(200)));

        assertSame(winner, result);
        verify(affiliateEarningRepository, never()).save(any());
        verifyNoInteractions(auditLogService);
    }

    @Test
    @DisplayName(
            "recordEarning: loses the race and the winner's row is STILL not visible -- throws a"
                    + " retry-safe 409, never silently double-runs")
    void testConcurrentRaceNoVisibleWinnerThrows409() {
        when(affiliateEarningRepository.findByRedemptionId(REDEMPTION_ID)).thenReturn(Optional.empty());
        when(couponCodeRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon()));
        when(idempotencyService.executeOnce(anyString(), any(), anyString(), any()))
                .thenThrow(new IdempotencyService.AlreadyInProgressException("affearn:" + REDEMPTION_ID));

        ApiException ex =
                assertThrows(ApiException.class, () -> service.recordEarning(redemption(BigDecimal.valueOf(200))));

        assertEquals("IDEMPOTENCY_KEY_IN_PROGRESS", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        verify(affiliateEarningRepository, never()).save(any());
    }

    @Test
    @DisplayName(
            "recordEarning: a transient persistence failure marks the key FAILED, but a subsequent"
                    + " legitimate retry reaches doRecordEarning again and succeeds -- never permanently wedged")
    void testTransientFailureThenSuccessfulRetryUnwedgesEarning() {
        when(affiliateEarningRepository.findByRedemptionId(REDEMPTION_ID)).thenReturn(Optional.empty());
        when(couponCodeRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon()));

        when(idempotencyService.executeOnce(anyString(), any(), anyString(), any()))
                .thenAnswer(
                        invocation -> {
                            Supplier<AffiliateEarning> supplier = invocation.getArgument(3);
                            return supplier.get();
                        });
        when(affiliateEarningRepository.save(any(AffiliateEarning.class)))
                .thenThrow(new RuntimeException("transient DB failure"));

        assertThrows(
                RuntimeException.class, () -> service.recordEarning(redemption(BigDecimal.valueOf(200))));

        // Second attempt: persistence now succeeds.
        when(affiliateEarningRepository.save(any(AffiliateEarning.class))).thenAnswer(inv -> inv.getArgument(0));

        AffiliateEarning result = service.recordEarning(redemption(BigDecimal.valueOf(200)));

        assertEquals(0, BigDecimal.valueOf(20.00).compareTo(result.getCommissionAmount()));
        verify(affiliateEarningRepository, times(2)).save(any(AffiliateEarning.class));
    }

    // ------------------------------------------------------------------
    // Validation before executeOnce [mirrors PayoutService E2 HIGH-1]
    // ------------------------------------------------------------------

    @Test
    @DisplayName("recordEarning: COUPON_NOT_FOUND (404) never reserves an idempotency key")
    void testCouponNotFoundNeverReservesKey() {
        when(affiliateEarningRepository.findByRedemptionId(REDEMPTION_ID)).thenReturn(Optional.empty());
        when(couponCodeRepository.findById(COUPON_ID)).thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(ApiException.class, () -> service.recordEarning(redemption(BigDecimal.valueOf(200))));

        assertEquals("COUPON_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
        verifyNoInteractions(idempotencyService);
        verify(affiliateEarningRepository, never()).save(any());
    }

    // ------------------------------------------------------------------
    // Commission calculation correctness
    // ------------------------------------------------------------------

    @Test
    @DisplayName("recordEarning: commission is 10% of orderAmount (not discountApplied), rounded half-up")
    void testCommissionIsPercentOfOrderAmountNotDiscount() {
        when(affiliateEarningRepository.findByRedemptionId(REDEMPTION_ID)).thenReturn(Optional.empty());
        when(couponCodeRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon()));
        mockIdempotencyExecuteOnce();
        when(affiliateEarningRepository.save(any(AffiliateEarning.class))).thenAnswer(inv -> inv.getArgument(0));

        // orderAmount = 333.33, discountApplied (irrelevant here) = 30 -- commission must be based
        // on orderAmount: 333.33 * 0.10 = 33.333 -> rounds half-up to 33.33.
        AffiliateEarning result = service.recordEarning(redemption(BigDecimal.valueOf(333.33)));

        assertEquals(0, BigDecimal.valueOf(33.33).compareTo(result.getCommissionAmount()));
    }

    // ------------------------------------------------------------------
    // P2-13: per-campaign commission-rate resolution
    // (wiki/decisions/2026-07-12-P2-13-affiliate-commission-rate-model.md)
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "recordEarning: campaign with commissionRate == null falls back to the flat 10%"
                    + " DEFAULT_COMMISSION_RATE unchanged (regression safety, real-money path)")
    void testCampaignWithNullRateUsesDefaultTenPercent() {
        when(affiliateEarningRepository.findByRedemptionId(REDEMPTION_ID)).thenReturn(Optional.empty());
        when(couponCodeRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon()));
        when(campaignRepository.findById(CAMPAIGN_ID))
                .thenReturn(Optional.of(Campaign.builder().id(CAMPAIGN_ID).workspaceId(WORKSPACE_ID).title("Campaign").build()));
        mockIdempotencyExecuteOnce();
        when(affiliateEarningRepository.save(any(AffiliateEarning.class))).thenAnswer(inv -> inv.getArgument(0));

        AffiliateEarning result = service.recordEarning(redemption(BigDecimal.valueOf(200)));

        // 200 * 0.10 (default, campaign has no override) = 20.00
        assertEquals(0, BigDecimal.valueOf(20.00).compareTo(result.getCommissionAmount()));
        verify(auditLogService, times(1))
                .recordMoneyEvent(
                        eq(WORKSPACE_ID),
                        eq("AFFILIATE_EARNING_RECORDED"),
                        any(),
                        isNull(),
                        isNull(),
                        anyString(),
                        eq(java.util.Map.of(
                                "earningId", result.getId(),
                                "redemptionId", REDEMPTION_ID,
                                "couponId", COUPON_ID,
                                "campaignId", CAMPAIGN_ID,
                                "creatorId", CREATOR_ID,
                                "commissionRate", "0.10")));
    }

    @Test
    @DisplayName(
            "recordEarning: campaign with an explicit commissionRate override uses that rate"
                    + " instead of the 10% default")
    void testCampaignWithOverrideRateUsesConfiguredRate() {
        when(affiliateEarningRepository.findByRedemptionId(REDEMPTION_ID)).thenReturn(Optional.empty());
        when(couponCodeRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon()));
        Campaign campaignWithOverride =
                Campaign.builder()
                        .id(CAMPAIGN_ID)
                        .workspaceId(WORKSPACE_ID)
                        .title("Campaign")
                        .commissionRate(new BigDecimal("0.20"))
                        .build();
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaignWithOverride));
        mockIdempotencyExecuteOnce();
        when(affiliateEarningRepository.save(any(AffiliateEarning.class))).thenAnswer(inv -> inv.getArgument(0));

        AffiliateEarning result = service.recordEarning(redemption(BigDecimal.valueOf(200)));

        // 200 * 0.20 (campaign override, not the 0.10 default) = 40.00
        assertEquals(0, BigDecimal.valueOf(40.00).compareTo(result.getCommissionAmount()));
    }

    @Test
    @DisplayName("deriveIdempotencyKey: deterministic and namespaced by redemptionId")
    void testDeriveIdempotencyKeyIsDeterministic() {
        String key1 = AffiliateEarningsService.deriveIdempotencyKey(REDEMPTION_ID);
        String key2 = AffiliateEarningsService.deriveIdempotencyKey(REDEMPTION_ID);

        assertEquals(key1, key2);
        assertEquals("affearn:" + REDEMPTION_ID, key1);
    }

    // ------------------------------------------------------------------
    // V-GA-7 principal-scoped list
    // ------------------------------------------------------------------

    @Test
    @DisplayName("listForCreator V-GA-7: scopes query to authenticated creator profile id only")
    void testListForCreatorIsPrincipalScoped() {
        AuthPrincipal principal = org.mockito.Mockito.mock(AuthPrincipal.class);
        CreatorProfile profile = CreatorProfile.newForUser(CREATOR_ID, "01HCREATORUSER1234567", "Priya");
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        when(affiliateEarningRepository.findByCreatorIdOrderByCreatedAtDesc(CREATOR_ID))
                .thenReturn(List.of());
        when(campaignRepository.findAllById(any())).thenReturn(List.of());
        when(workspaceRepository.findAllById(any())).thenReturn(List.of());
        when(couponRedemptionRepository.findAllById(any())).thenReturn(List.of());

        var response = service.listForCreator(principal, null, null);

        assertEquals(0, response.earnings().size());
        assertEquals(0, response.summary().thisMonthSales());
        assertEquals(0, response.totalElements());
        assertFalse(response.hasMore());
        verify(affiliateEarningRepository).findByCreatorIdOrderByCreatedAtDesc(CREATOR_ID);
        verify(affiliateEarningRepository, never())
                .findByCreatorIdOrderByCreatedAtDesc("01HOTHERCREATORPROF01");
    }

    /**
     * CR-83 — the earlier version of this endpoint returned every row ever recorded in one
     * response. Pins that a `limit` smaller than the creator's full history now returns exactly
     * that many rows, reports the true total, and flags `hasMore` — while the summary still
     * reflects every one of the 5 underlying earnings, not just the 2 on this page.
     */
    @Test
    @DisplayName("listForCreator CR-83: pages the row list without narrowing the summary")
    void testListForCreatorPaginatesRowsButNotSummary() {
        AuthPrincipal principal = org.mockito.Mockito.mock(AuthPrincipal.class);
        CreatorProfile profile = CreatorProfile.newForUser(CREATOR_ID, "01HCREATORUSER1234567", "Priya");
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);

        java.util.List<com.influora.domain.entity.AffiliateEarning> fiveEarnings =
                java.util.stream.IntStream.range(0, 5)
                        .mapToObj(
                                i ->
                                        com.influora.domain.entity.AffiliateEarning.builder()
                                                .id("earn_" + i)
                                                .workspaceId("ws_1")
                                                .campaignId("camp_1")
                                                .creatorId(CREATOR_ID)
                                                .redemptionId("redemption_" + i)
                                                .commissionAmount(new java.math.BigDecimal("10.00"))
                                                .currency("INR")
                                                .idempotencyKey("affearn:redemption_" + i)
                                                .build())
                        .toList();
        when(affiliateEarningRepository.findByCreatorIdOrderByCreatedAtDesc(CREATOR_ID))
                .thenReturn(fiveEarnings);
        when(campaignRepository.findAllById(any())).thenReturn(List.of());
        when(workspaceRepository.findAllById(any())).thenReturn(List.of());
        when(couponRedemptionRepository.findAllById(any())).thenReturn(List.of());

        var page0 = service.listForCreator(principal, 0, 2);

        assertEquals(2, page0.earnings().size());
        assertEquals(0, page0.page());
        assertEquals(2, page0.limit());
        assertEquals(5, page0.totalElements());
        assertTrue(page0.hasMore());
        // The summary must count all 5 earnings' commission, not just the 2 rows returned.
        assertEquals(
                new java.math.BigDecimal("50.00"), page0.summary().unsettledCommission());

        var page2 = service.listForCreator(principal, 2, 2);
        assertEquals(1, page2.earnings().size());
        assertFalse(page2.hasMore());
    }

    /**
     * CR-83 follow-up (Priya red-team) — {@code pageNumber * pageSize} as a plain {@code int}
     * multiply overflows for a large caller-supplied {@code page} (e.g. 100_000_000 × 100),
     * wrapping negative and throwing {@link IndexOutOfBoundsException} out of {@code subList} —
     * an authenticated, self-scoped 500 from ordinary user input. Pins that an absurd page
     * number degrades to an empty page instead.
     */
    @Test
    @DisplayName("listForCreator CR-83: an absurd page number returns an empty page instead of throwing")
    void testListForCreatorAbsurdPageNumberDoesNotOverflow() {
        AuthPrincipal principal = org.mockito.Mockito.mock(AuthPrincipal.class);
        CreatorProfile profile = CreatorProfile.newForUser(CREATOR_ID, "01HCREATORUSER1234567", "Priya");
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);

        var single =
                java.util.List.of(
                        com.influora.domain.entity.AffiliateEarning.builder()
                                .id("earn_only")
                                .workspaceId("ws_1")
                                .campaignId("camp_1")
                                .creatorId(CREATOR_ID)
                                .redemptionId("redemption_only")
                                .commissionAmount(new java.math.BigDecimal("10.00"))
                                .currency("INR")
                                .idempotencyKey("affearn:redemption_only")
                                .build());
        when(affiliateEarningRepository.findByCreatorIdOrderByCreatedAtDesc(CREATOR_ID)).thenReturn(single);
        when(campaignRepository.findAllById(any())).thenReturn(List.of());
        when(workspaceRepository.findAllById(any())).thenReturn(List.of());
        when(couponRedemptionRepository.findAllById(any())).thenReturn(List.of());

        // Would overflow int (100_000_000 * 100 > Integer.MAX_VALUE) if multiplied as a plain int.
        var response = service.listForCreator(principal, 100_000_000, 100);

        assertEquals(0, response.earnings().size());
        assertEquals(1, response.totalElements());
        assertFalse(response.hasMore());
    }
}
