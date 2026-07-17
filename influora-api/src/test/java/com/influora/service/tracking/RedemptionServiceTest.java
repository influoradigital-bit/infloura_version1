package com.influora.service.tracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import com.influora.domain.entity.CouponCode;
import com.influora.domain.entity.CouponRedemption;
import com.influora.repository.CouponCodeRepository;
import com.influora.repository.CouponRedemptionRepository;
import com.influora.service.AuditLogService;
import com.influora.service.IdempotencyService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * Phase 4 UTM/Coupon Tracking: unit tests for RedemptionService -- idempotency (the {@code
 * [SEC: Kabir]}-flagged guarantee is the priority here, same rigor as CouponCodeServiceTest's
 * collision-retry proof), coupon not-found/expired/limit-reached rejection, and discount
 * calculation correctness for both real {@code discountType} values ("percentage"/"fixed").
 */
@ExtendWith(MockitoExtension.class)
class RedemptionServiceTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String COUPON_ID = "01HCOUPON1234567890AB";
    private static final String CAMPAIGN_ID = "01HCAMPAIGN123456789A";
    private static final String CREATOR_ID = "01HCREATORPROFILE1234";
    private static final String ORDER_ID = "order-9001";
    private static final String CUSTOMER_ID = "cust-42";
    private static final String IDEMPOTENCY_KEY = "brand-webhook-key-abc123";

    @Mock private CouponRedemptionRepository redemptionRepository;
    @Mock private CouponCodeRepository couponCodeRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private IdempotencyService idempotencyService;

    private RedemptionService service;

    @BeforeEach
    void setUp() {
        // [W1-7] doRedeem/validateCode/calculateDiscount moved to RedemptionWriter (a real Spring
        // proxy target for @Transactional — see that class's javadoc). Wired here with a REAL
        // RedemptionWriter instance (not mocked) sharing this test's same mocked repositories/audit
        // log, so every existing assertion below — which verifies interactions on those mocks —
        // continues to exercise the actual business logic end-to-end exactly as before extraction.
        RedemptionWriter redemptionWriter =
                new RedemptionWriter(redemptionRepository, couponCodeRepository, auditLogService);
        service = new RedemptionService(redemptionRepository, idempotencyService, redemptionWriter);
    }

    /**
     * Mocks the shared {@link IdempotencyService#executeOnce} to just run the supplier immediately,
     * as if this call won the reservation race -- mirrors {@code
     * RequestPaymentExecutorTest#mockIdempotencyExecuteOnce}, the established convention for the
     * other consumers of this shared service. Used by every non-racing test below so the "happy
     * path" tests keep exercising real behavior, not the race branch.
     */
    private void mockIdempotencyExecuteOnce() {
        when(idempotencyService.executeOnce(anyString(), any(), anyString(), any()))
                .thenAnswer(
                        invocation -> {
                            Supplier<CouponRedemption> supplier = invocation.getArgument(3);
                            return supplier.get();
                        });
    }

    // ------------------------------------------------------------------
    // Idempotency [SEC: Kabir] -- the load-bearing guarantee for this class
    // ------------------------------------------------------------------

    @Test
    @DisplayName("redeem: rejects a null idempotencyKey before touching any repository")
    void testRejectsNullIdempotencyKey() {
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.redeem("PRIYA_SUMMER25", ORDER_ID, BigDecimal.TEN, CUSTOMER_ID, null));

        assertEquals("IDEMPOTENCY_KEY_REQUIRED", ex.getCode());
        assertEquals(400, ex.getStatus().value());
        verifyNoInteractions(redemptionRepository, couponCodeRepository, auditLogService);
    }

    @Test
    @DisplayName("redeem: rejects a blank idempotencyKey before touching any repository")
    void testRejectsBlankIdempotencyKey() {
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.redeem("PRIYA_SUMMER25", ORDER_ID, BigDecimal.TEN, CUSTOMER_ID, "   "));

        assertEquals("IDEMPOTENCY_KEY_REQUIRED", ex.getCode());
        verifyNoInteractions(redemptionRepository, couponCodeRepository, auditLogService);
    }

    @Test
    @DisplayName(
            "redeem: same idempotencyKey called twice returns the SAME redemption and does NOT"
                    + " double-increment usage or write a second audit entry")
    void testDuplicateIdempotencyKeyIsCleanNoOp() {
        CouponCode coupon = percentageCoupon(20, null, null, 0);
        when(couponCodeRepository.findByCode("PRIYA_SUMMER25")).thenReturn(Optional.of(coupon));
        when(redemptionRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        mockIdempotencyExecuteOnce();
        when(redemptionRepository.save(any(CouponRedemption.class))).thenAnswer(inv -> inv.getArgument(0));

        CouponRedemption first =
                service.redeem("PRIYA_SUMMER25", ORDER_ID, BigDecimal.valueOf(100), CUSTOMER_ID, IDEMPOTENCY_KEY);

        // Second call: the key now resolves to the existing row.
        when(redemptionRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(first));

        CouponRedemption second =
                service.redeem("PRIYA_SUMMER25", ORDER_ID, BigDecimal.valueOf(100), CUSTOMER_ID, IDEMPOTENCY_KEY);

        assertSame(first, second);
        // Usage counter incremented exactly once (from the first call only).
        assertEquals(1, coupon.getUsageCount());
        verify(couponCodeRepository, times(1)).save(coupon);
        verify(redemptionRepository, times(1)).save(any(CouponRedemption.class));
        verify(auditLogService, times(1))
                .recordMoneyEvent(any(), eq("COUPON_REDEEMED"), any(), isNull(), isNull(), anyString(), anyMap());
        // The second call never re-validated the coupon code at all.
        verify(couponCodeRepository, times(1)).findByCode("PRIYA_SUMMER25");
    }

    @Test
    @DisplayName("redeem: existing-redemption idempotent return happens BEFORE any coupon validation")
    void testIdempotentReturnSkipsValidationEntirely() {
        CouponRedemption existing =
                CouponRedemption.builder()
                        .id("01HEXISTING1234567890")
                        .couponId(COUPON_ID)
                        .orderId(ORDER_ID)
                        .orderAmount(BigDecimal.TEN)
                        .discountApplied(BigDecimal.ONE)
                        .customerId(CUSTOMER_ID)
                        .idempotencyKey(IDEMPOTENCY_KEY)
                        .build();
        when(redemptionRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(existing));

        CouponRedemption result =
                service.redeem("ANY_CODE_EVEN_INVALID", ORDER_ID, BigDecimal.TEN, CUSTOMER_ID, IDEMPOTENCY_KEY);

        assertSame(existing, result);
        verifyNoInteractions(couponCodeRepository);
        verifyNoInteractions(auditLogService);
        verify(redemptionRepository, never()).save(any());
    }

    // ------------------------------------------------------------------
    // Concurrent double-submit race [SEC: Kabir, race-condition fast-follow] -- proves the
    // IdempotencyService.executeOnce wiring gracefully returns the winner instead of letting a
    // DataIntegrityViolationException / raw 500 propagate when two requests race on the same key.
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "redeem: loses the concurrent reservation race (AlreadyInProgressException) but the"
                    + " winner's row is already visible -- returns it gracefully instead of 500/throwing")
    void testConcurrentRaceLoserReturnsWinnerGracefully() {
        // Simulate: this request's own findByIdempotencyKey check sees nothing yet (classic
        // check-then-act race window), but by the time it tries to reserve the key via the shared
        // IdempotencyService, a concurrent request has already won that reservation (e.g. because
        // that request's own doRedeem already committed and inserted the CouponRedemption row).
        CouponRedemption winner =
                CouponRedemption.builder()
                        .id("01HWINNER123456789012")
                        .couponId(COUPON_ID)
                        .orderId(ORDER_ID)
                        .orderAmount(BigDecimal.valueOf(100))
                        .discountApplied(BigDecimal.valueOf(20))
                        .customerId(CUSTOMER_ID)
                        .idempotencyKey(IDEMPOTENCY_KEY)
                        .build();

        // First findByIdempotencyKey call (the app-level pre-check) sees nothing -- the race window.
        // Second findByIdempotencyKey call (after losing the reservation) sees the winner's committed row.
        when(redemptionRepository.findByIdempotencyKey(IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));

        when(idempotencyService.executeOnce(anyString(), any(), anyString(), any()))
                .thenThrow(new IdempotencyService.AlreadyInProgressException(IDEMPOTENCY_KEY));

        CouponRedemption result =
                service.redeem("PRIYA_SUMMER25", ORDER_ID, BigDecimal.valueOf(100), CUSTOMER_ID, IDEMPOTENCY_KEY);

        assertSame(winner, result);
        // The loser never touched coupon validation/mutation or wrote its own audit entry --
        // doRedeem's body (inside the supplier passed to executeOnce) never actually ran for this call.
        verifyNoInteractions(couponCodeRepository);
        verifyNoInteractions(auditLogService);
        verify(redemptionRepository, never()).save(any());
        verify(redemptionRepository, times(2)).findByIdempotencyKey(IDEMPOTENCY_KEY);
    }

    @Test
    @DisplayName(
            "redeem: loses the concurrent reservation race (AlreadyCompletedException) but the"
                    + " winner's row is already visible -- returns it gracefully")
    void testConcurrentRaceAlreadyCompletedReturnsWinnerGracefully() {
        CouponRedemption winner =
                CouponRedemption.builder()
                        .id("01HWINNER123456789012")
                        .couponId(COUPON_ID)
                        .orderId(ORDER_ID)
                        .orderAmount(BigDecimal.valueOf(100))
                        .discountApplied(BigDecimal.valueOf(20))
                        .customerId(CUSTOMER_ID)
                        .idempotencyKey(IDEMPOTENCY_KEY)
                        .build();

        when(redemptionRepository.findByIdempotencyKey(IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));

        when(idempotencyService.executeOnce(anyString(), any(), anyString(), any()))
                .thenThrow(new IdempotencyService.AlreadyCompletedException(IDEMPOTENCY_KEY));

        CouponRedemption result =
                service.redeem("PRIYA_SUMMER25", ORDER_ID, BigDecimal.valueOf(100), CUSTOMER_ID, IDEMPOTENCY_KEY);

        assertSame(winner, result);
        verifyNoInteractions(couponCodeRepository);
        verifyNoInteractions(auditLogService);
        verify(redemptionRepository, never()).save(any());
    }

    @Test
    @DisplayName(
            "redeem: loses the reservation race and the winner's row is STILL not visible yet -- throws"
                    + " a retry-safe 409 IDEMPOTENCY_KEY_IN_PROGRESS, never a generic 500")
    void testConcurrentRaceLoserWithNoVisibleWinnerYetThrows409() {
        // Both findByIdempotencyKey calls see nothing -- the winner hasn't committed its INSERT yet.
        when(redemptionRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());

        when(idempotencyService.executeOnce(anyString(), any(), anyString(), any()))
                .thenThrow(new IdempotencyService.AlreadyInProgressException(IDEMPOTENCY_KEY));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.redeem(
                                        "PRIYA_SUMMER25", ORDER_ID, BigDecimal.valueOf(100), CUSTOMER_ID, IDEMPOTENCY_KEY));

        assertEquals("IDEMPOTENCY_KEY_IN_PROGRESS", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        verifyNoInteractions(couponCodeRepository);
        verifyNoInteractions(auditLogService);
        verify(redemptionRepository, never()).save(any());
    }

    // ------------------------------------------------------------------
    // Validation failures
    // ------------------------------------------------------------------

    @Test
    @DisplayName("redeem: rejects a null orderAmount")
    void testRejectsNullOrderAmount() {
        when(redemptionRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        mockIdempotencyExecuteOnce();

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.redeem("PRIYA_SUMMER25", ORDER_ID, null, CUSTOMER_ID, IDEMPOTENCY_KEY));

        assertEquals("ORDER_AMOUNT_INVALID", ex.getCode());
        assertEquals(400, ex.getStatus().value());
        verifyNoInteractions(couponCodeRepository);
    }

    @Test
    @DisplayName("redeem: rejects a negative orderAmount")
    void testRejectsNegativeOrderAmount() {
        when(redemptionRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        mockIdempotencyExecuteOnce();

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.redeem(
                                        "PRIYA_SUMMER25",
                                        ORDER_ID,
                                        BigDecimal.valueOf(-5),
                                        CUSTOMER_ID,
                                        IDEMPOTENCY_KEY));

        assertEquals("ORDER_AMOUNT_INVALID", ex.getCode());
        verifyNoInteractions(couponCodeRepository);
    }

    @Test
    @DisplayName("redeem: INVALID_CODE (404) when the coupon code does not exist")
    void testCodeNotFound() {
        when(redemptionRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        mockIdempotencyExecuteOnce();
        when(couponCodeRepository.findByCode("NOPE")).thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.redeem("nope", ORDER_ID, BigDecimal.TEN, CUSTOMER_ID, IDEMPOTENCY_KEY));

        assertEquals("INVALID_CODE", ex.getCode());
        assertEquals(404, ex.getStatus().value());
        verify(redemptionRepository, never()).save(any());
        verifyNoInteractions(auditLogService);
    }

    @Test
    @DisplayName("redeem: lookup normalizes the code to upper-case before hitting the repository")
    void testCodeLookupIsUppercased() {
        when(redemptionRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        mockIdempotencyExecuteOnce();
        when(couponCodeRepository.findByCode("PRIYA_SUMMER25")).thenReturn(Optional.empty());

        assertThrows(
                ApiException.class,
                () -> service.redeem("  priya_summer25  ", ORDER_ID, BigDecimal.TEN, CUSTOMER_ID, IDEMPOTENCY_KEY));

        verify(couponCodeRepository).findByCode("PRIYA_SUMMER25");
    }

    @Test
    @DisplayName("redeem: CODE_EXPIRED (400) when expiresAt is in the past")
    void testCodeExpired() {
        CouponCode coupon = percentageCoupon(20, null, Instant.now().minus(1, ChronoUnit.DAYS), 0);
        when(redemptionRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        mockIdempotencyExecuteOnce();
        when(couponCodeRepository.findByCode("PRIYA_SUMMER25")).thenReturn(Optional.of(coupon));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.redeem(
                                        "PRIYA_SUMMER25", ORDER_ID, BigDecimal.TEN, CUSTOMER_ID, IDEMPOTENCY_KEY));

        assertEquals("CODE_EXPIRED", ex.getCode());
        assertEquals(400, ex.getStatus().value());
        verify(redemptionRepository, never()).save(any());
    }

    @Test
    @DisplayName("redeem: a future expiresAt does NOT reject the redemption")
    void testFutureExpiryIsFine() {
        CouponCode coupon = percentageCoupon(20, null, Instant.now().plus(1, ChronoUnit.DAYS), 0);
        when(redemptionRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        mockIdempotencyExecuteOnce();
        when(couponCodeRepository.findByCode("PRIYA_SUMMER25")).thenReturn(Optional.of(coupon));
        when(redemptionRepository.save(any(CouponRedemption.class))).thenAnswer(inv -> inv.getArgument(0));

        CouponRedemption result =
                service.redeem("PRIYA_SUMMER25", ORDER_ID, BigDecimal.valueOf(100), CUSTOMER_ID, IDEMPOTENCY_KEY);

        assertEquals(0, BigDecimal.valueOf(20).compareTo(result.getDiscountApplied()));
    }

    @Test
    @DisplayName("redeem: CODE_LIMIT_REACHED (400) when usageCount already meets usageLimit")
    void testUsageLimitReached() {
        CouponCode coupon = percentageCoupon(20, 5, null, 5);
        when(redemptionRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        mockIdempotencyExecuteOnce();
        when(couponCodeRepository.findByCode("PRIYA_SUMMER25")).thenReturn(Optional.of(coupon));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.redeem(
                                        "PRIYA_SUMMER25", ORDER_ID, BigDecimal.TEN, CUSTOMER_ID, IDEMPOTENCY_KEY));

        assertEquals("CODE_LIMIT_REACHED", ex.getCode());
        assertEquals(400, ex.getStatus().value());
        verify(redemptionRepository, never()).save(any());
    }

    @Test
    @DisplayName("redeem: usageCount one below usageLimit is still allowed (boundary check)")
    void testUsageLimitNotYetReachedAtBoundary() {
        CouponCode coupon = percentageCoupon(20, 5, null, 4);
        when(redemptionRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        mockIdempotencyExecuteOnce();
        when(couponCodeRepository.findByCode("PRIYA_SUMMER25")).thenReturn(Optional.of(coupon));
        when(redemptionRepository.save(any(CouponRedemption.class))).thenAnswer(inv -> inv.getArgument(0));

        CouponRedemption result =
                service.redeem("PRIYA_SUMMER25", ORDER_ID, BigDecimal.valueOf(100), CUSTOMER_ID, IDEMPOTENCY_KEY);

        assertEquals(5, coupon.getUsageCount());
        assertEquals(0, BigDecimal.valueOf(20).compareTo(result.getDiscountApplied()));
    }

    @Test
    @DisplayName("redeem: a null usageLimit means unlimited -- never rejected regardless of usageCount")
    void testNullUsageLimitIsUnlimited() {
        CouponCode coupon = percentageCoupon(20, null, null, 999);
        when(redemptionRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        mockIdempotencyExecuteOnce();
        when(couponCodeRepository.findByCode("PRIYA_SUMMER25")).thenReturn(Optional.of(coupon));
        when(redemptionRepository.save(any(CouponRedemption.class))).thenAnswer(inv -> inv.getArgument(0));

        CouponRedemption result =
                service.redeem("PRIYA_SUMMER25", ORDER_ID, BigDecimal.valueOf(100), CUSTOMER_ID, IDEMPOTENCY_KEY);

        assertEquals(0, BigDecimal.valueOf(20).compareTo(result.getDiscountApplied()));
    }

    // ------------------------------------------------------------------
    // Discount calculation correctness
    // ------------------------------------------------------------------

    @Test
    @DisplayName("calculateDiscount: percentage discount computes orderAmount * value / 100, rounded half-up")
    void testPercentageDiscountCalculation() {
        CouponCode coupon = percentageCoupon(15, null, null, 0);
        when(redemptionRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        mockIdempotencyExecuteOnce();
        when(couponCodeRepository.findByCode("PRIYA_SUMMER25")).thenReturn(Optional.of(coupon));
        when(redemptionRepository.save(any(CouponRedemption.class))).thenAnswer(inv -> inv.getArgument(0));

        CouponRedemption result =
                service.redeem("PRIYA_SUMMER25", ORDER_ID, BigDecimal.valueOf(333.33), CUSTOMER_ID, IDEMPOTENCY_KEY);

        // 333.33 * 15 / 100 = 49.9995 -> rounds half-up to 50.00
        assertEquals(0, BigDecimal.valueOf(50.00).compareTo(result.getDiscountApplied()));
    }

    @Test
    @DisplayName("calculateDiscount: fixed discount returns the flat discountValue when order is larger")
    void testFixedDiscountBelowOrderAmount() {
        CouponCode coupon = fixedCoupon(BigDecimal.valueOf(500), null, null, 0);
        when(redemptionRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        mockIdempotencyExecuteOnce();
        when(couponCodeRepository.findByCode("PRIYA_FIXED")).thenReturn(Optional.of(coupon));
        when(redemptionRepository.save(any(CouponRedemption.class))).thenAnswer(inv -> inv.getArgument(0));

        CouponRedemption result =
                service.redeem("PRIYA_FIXED", ORDER_ID, BigDecimal.valueOf(2000), CUSTOMER_ID, IDEMPOTENCY_KEY);

        assertEquals(0, BigDecimal.valueOf(500).compareTo(result.getDiscountApplied()));
    }

    @Test
    @DisplayName(
            "calculateDiscount: fixed discount is clamped to the order amount when the discount"
                    + " value exceeds it -- never discounts more than the order is worth")
    void testFixedDiscountClampedToOrderAmount() {
        CouponCode coupon = fixedCoupon(BigDecimal.valueOf(500), null, null, 0);
        when(redemptionRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        mockIdempotencyExecuteOnce();
        when(couponCodeRepository.findByCode("PRIYA_FIXED")).thenReturn(Optional.of(coupon));
        when(redemptionRepository.save(any(CouponRedemption.class))).thenAnswer(inv -> inv.getArgument(0));

        CouponRedemption result =
                service.redeem("PRIYA_FIXED", ORDER_ID, BigDecimal.valueOf(200), CUSTOMER_ID, IDEMPOTENCY_KEY);

        assertEquals(0, BigDecimal.valueOf(200).compareTo(result.getDiscountApplied()));
    }

    @Test
    @DisplayName("calculateDiscount: an unrecognized discountType throws UNSUPPORTED_DISCOUNT_TYPE (500)")
    void testUnsupportedDiscountTypeThrows() {
        CouponCode coupon =
                CouponCode.builder()
                        .id(COUPON_ID)
                        .workspaceId(WORKSPACE_ID)
                        .campaignId(CAMPAIGN_ID)
                        .creatorId(CREATOR_ID)
                        .code("PRIYA_WEIRD")
                        .discountType("free_shipping")
                        .discountValue(BigDecimal.ZERO)
                        .build();
        when(redemptionRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        mockIdempotencyExecuteOnce();
        when(couponCodeRepository.findByCode("PRIYA_WEIRD")).thenReturn(Optional.of(coupon));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.redeem(
                                        "PRIYA_WEIRD", ORDER_ID, BigDecimal.TEN, CUSTOMER_ID, IDEMPOTENCY_KEY));

        assertEquals("UNSUPPORTED_DISCOUNT_TYPE", ex.getCode());
        assertEquals(500, ex.getStatus().value());
        verify(redemptionRepository, never()).save(any());
        // A data-integrity failure like this must not increment usage or write an audit entry either.
        verify(couponCodeRepository, never()).save(any());
        verifyNoInteractions(auditLogService);
    }

    // ------------------------------------------------------------------
    // Happy path: full field + side-effect verification
    // ------------------------------------------------------------------

    @Test
    @DisplayName("redeem: happy path persists the redemption with correct fields, increments usage, and audits")
    void testHappyPathFieldsAndSideEffects() {
        CouponCode coupon = percentageCoupon(15, 100, null, 3);
        when(redemptionRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        mockIdempotencyExecuteOnce();
        when(couponCodeRepository.findByCode("PRIYA_SUMMER25")).thenReturn(Optional.of(coupon));
        when(redemptionRepository.save(any(CouponRedemption.class))).thenAnswer(inv -> inv.getArgument(0));

        CouponRedemption result =
                service.redeem(
                        "priya_summer25", ORDER_ID, BigDecimal.valueOf(200), CUSTOMER_ID, IDEMPOTENCY_KEY);

        assertEquals(COUPON_ID, result.getCouponId());
        assertEquals(ORDER_ID, result.getOrderId());
        assertEquals(0, BigDecimal.valueOf(200).compareTo(result.getOrderAmount()));
        assertEquals(0, BigDecimal.valueOf(30).compareTo(result.getDiscountApplied()));
        assertEquals(CUSTOMER_ID, result.getCustomerId());
        assertEquals(IDEMPOTENCY_KEY, result.getIdempotencyKey());

        // Usage counter incremented and coupon persisted.
        assertEquals(4, coupon.getUsageCount());
        ArgumentCaptor<CouponCode> couponCaptor = ArgumentCaptor.forClass(CouponCode.class);
        verify(couponCodeRepository).save(couponCaptor.capture());
        assertEquals(4, couponCaptor.getValue().getUsageCount());

        // Audit log written with the real workspaceId (available directly off CouponCode) and the
        // discount amount as serverAmount, before/after balances null (not a wallet mutation).
        ArgumentCaptor<BigDecimal> amountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(auditLogService)
                .recordMoneyEvent(
                        eq(WORKSPACE_ID),
                        eq("COUPON_REDEEMED"),
                        amountCaptor.capture(),
                        isNull(),
                        isNull(),
                        eq(IDEMPOTENCY_KEY),
                        anyMap());
        assertEquals(0, BigDecimal.valueOf(30).compareTo(amountCaptor.getValue()));
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static CouponCode percentageCoupon(
            int percentValue, Integer usageLimit, Instant expiresAt, int existingUsageCount) {
        CouponCode coupon =
                CouponCode.builder()
                        .id(COUPON_ID)
                        .workspaceId(WORKSPACE_ID)
                        .campaignId(CAMPAIGN_ID)
                        .creatorId(CREATOR_ID)
                        .code("PRIYA_SUMMER25")
                        .discountType("percentage")
                        .discountValue(BigDecimal.valueOf(percentValue))
                        .usageLimit(usageLimit)
                        .expiresAt(expiresAt)
                        .build();
        bumpUsageCount(coupon, existingUsageCount);
        return coupon;
    }

    private static CouponCode fixedCoupon(
            BigDecimal fixedValue, Integer usageLimit, Instant expiresAt, int existingUsageCount) {
        CouponCode coupon =
                CouponCode.builder()
                        .id(COUPON_ID)
                        .workspaceId(WORKSPACE_ID)
                        .campaignId(CAMPAIGN_ID)
                        .creatorId(CREATOR_ID)
                        .code("PRIYA_FIXED")
                        .discountType("fixed")
                        .discountValue(fixedValue)
                        .usageLimit(usageLimit)
                        .expiresAt(expiresAt)
                        .build();
        bumpUsageCount(coupon, existingUsageCount);
        return coupon;
    }

    private static void bumpUsageCount(CouponCode coupon, int times) {
        for (int i = 0; i < times; i++) {
            coupon.incrementUsageCount();
        }
    }
}
