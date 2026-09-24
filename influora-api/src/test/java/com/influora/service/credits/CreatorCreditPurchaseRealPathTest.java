package com.influora.service.credits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.influora.config.CreatorCreditProperties;
import com.influora.config.RazorpayProperties;
import com.influora.domain.entity.CreatorCreditAccount;
import com.influora.domain.entity.CreatorCreditGrant;
import com.influora.domain.entity.CreatorCreditLedgerEntry;
import com.influora.domain.entity.CreatorCreditOrder;
import com.influora.domain.entity.CreatorCreditPack;
import com.influora.domain.enums.CreatorCreditOrderStatus;
import com.influora.domain.enums.CreditBucket;
import com.influora.domain.enums.CreditLedgerReason;
import com.influora.integration.razorpay.RazorpayClient;
import com.influora.repository.CreatorCreditAccountRepository;
import com.influora.repository.CreatorCreditGrantRepository;
import com.influora.repository.CreatorCreditLedgerRepository;
import com.influora.repository.CreatorCreditOrderRepository;
import com.influora.repository.CreatorCreditWelcomeClaimRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.service.IdempotencyService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md §12, K-24, A26) — review finding #4 (round 2): the ONLY local test
 * that runs {@link CreatorCreditOrderService#confirmPaid} and {@link
 * CreatorCreditService#creditPurchase} for REAL, against a real H2 database, with {@code
 * influora.creator-credits.enabled=false}.
 *
 * <p>Before this test existed, every local unit test that touched {@code confirmPaid}/{@code
 * creditPurchase} mocked one or both of {@link CreatorCreditOrderService}/{@link
 * CreatorCreditService} ({@code CreatorCreditOrderServiceTest}, and {@code
 * RazorpayWebhookControllerTest#creatorCreditWebhookIgnoresFlag} also mocks {@link
 * CreatorCreditService}). That left two real defects unfalsifiable locally: (1) K-24 — {@code
 * creditPurchase} is deliberately NEVER flag-gated (a purchase must always credit, even with the
 * flag off — see its javadoc); adding {@code if (!properties.isEnabled()) return;} to it would
 * strand real, already-paid money, and every mocked test would stay green. (2) A26 — "60 credits
 * expiring {@code paidAt + 90d}" was asserted only against mocks, never against the real grant
 * math. The only REAL local coverage was {@code CreatorCreditConcurrencyIntegrationTest}, which SKIPS locally
 * (Docker down).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@EntityScan(basePackageClasses = CreatorCreditAccount.class)
@EnableJpaRepositories(
        basePackageClasses = CreatorCreditAccountRepository.class,
        excludeFilters =
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = "com\\.influora\\.repository\\.(?!CreatorCredit|CreatorVoiceSpeak).*"))
@TestPropertySource(
        properties = {
            "spring.datasource.url=" + "jdbc:h2:mem:creator_credit_purchase_real_path_test;DB_CLOSE_DELAY=-1;MODE=MySQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
            // K-24 — deliberately OFF: creditPurchase/confirmPaid must still credit for real.
            "influora.creator-credits.enabled=false"
        })
@Import({
    CreatorCreditService.class,
    CreatorCreditOrderService.class,
    CreatorCreditAccountInitializer.class,
    CreatorCreditWelcomeClaimWriter.class,
    CreatorCreditProperties.class,
    CreatorCreditServiceTest.ClockTestConfig.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CreatorCreditPurchaseRealPathTest {

    private static final String CREATOR_USER_ID = "01HCREATORUSER0000000A";

    @Autowired private CreatorCreditOrderService orderService;
    @Autowired private CreatorCreditOrderRepository orderRepository;
    @Autowired private CreatorCreditAccountRepository accountRepository;
    @Autowired private CreatorCreditGrantRepository grantRepository;
    @Autowired private CreatorCreditLedgerRepository ledgerRepository;
    @Autowired private CreatorCreditWelcomeClaimRepository welcomeClaimRepository;
    @Autowired private CreatorCreditServiceTest.MutableClock clock;

    @MockBean private CreatorProfileRepository creatorProfileRepository;
    @MockBean private MetaOAuthTokenRepository metaOAuthTokenRepository;
    @MockBean private IdempotencyService idempotencyService;
    // Out-of-domain integration surfaces confirmPaid never needs for this path (Razorpay is only
    // called by createOrder; invoice numbering is deferred, best-effort, and cannot affect the
    // credit — see CreatorCreditOrderService#confirmPaid's own javadoc).
    @MockBean private RazorpayClient razorpayClient;
    @MockBean private RazorpayProperties razorpayProperties;
    @MockBean private CreatorCreditInvoiceApplier invoiceApplier;

    @BeforeEach
    void resetClockAndData() {
        orderRepository.deleteAll();
        ledgerRepository.deleteAll();
        grantRepository.deleteAll();
        welcomeClaimRepository.deleteAll();
        accountRepository.deleteAll();
        clock.setInstant(Instant.parse("2026-09-10T10:00:00Z"));
    }

    /**
     * {@code creator_credit_orders.pack_id} is a plain, non-relational {@code @Column} on the
     * entity (no {@code @ManyToOne}/{@code @JoinColumn}) — Hibernate's {@code create-drop} schema
     * for this H2 harness therefore never generates the raw-SQL migration's {@code fk_cco_pack} FK,
     * so a real, persisted {@code creator_credit_packs} row is not required to satisfy it. A
     * Mockito-stubbed pack (exactly {@code CreatorCreditOrderServiceTest#pack60}'s pattern) is
     * enough to drive {@link CreatorCreditOrder#newPending}.
     */
    private CreatorCreditPack pack60() {
        CreatorCreditPack pack = mock(CreatorCreditPack.class);
        when(pack.getId()).thenReturn("pack-60-id");
        when(pack.getCode()).thenReturn("PACK_60");
        when(pack.getCredits()).thenReturn(60);
        when(pack.getPricePaise()).thenReturn(24900);
        return pack;
    }

    private CreatorCreditOrder seedPendingOrder() {
        accountRepository
                .findById(CREATOR_USER_ID)
                .orElseGet(() -> accountRepository.saveAndFlush(CreatorCreditAccount.newAccount(CREATOR_USER_ID)));
        CreatorCreditOrder order =
                CreatorCreditOrder.newPending(
                        com.influora.common.Ulids.newUlid(), CREATOR_USER_ID, pack60(), "idem-" + com.influora.common.Ulids.newUlid());
        order.setRazorpayOrderId("order_test_FAKE" + com.influora.common.Ulids.newUlid().substring(0, 8));
        return orderRepository.saveAndFlush(order);
    }

    @Test
    @DisplayName(
            "K-24/A26 (real, not mocked, flag OFF): confirmPaid on a PENDING order runs the REAL"
                    + " creditPurchase and credits exactly once — one PAID grant, 60 remaining, expires"
                    + " at paidAt+90d; one GRANT_PURCHASE ledger row referencing order:<id>; the order"
                    + " moves to CREDITED; a second confirmPaid call is a true no-op")
    void confirmPaidCreditsForRealEvenWithFlagOff() {
        CreatorCreditOrder pending = seedPendingOrder();
        String orderId = pending.getId();
        String razorpayOrderId = pending.getRazorpayOrderId();
        long amountPaise = pending.getAmountPaise();
        String currency = pending.getCurrency();
        String paymentId = "pay_test_FAKE" + com.influora.common.Ulids.newUlid().substring(0, 8);

        Instant expectedPaidAt = clock.instant();
        CreatorCreditOrder credited =
                orderService.confirmPaid(orderId, paymentId, razorpayOrderId, amountPaise, currency);

        assertEquals(CreatorCreditOrderStatus.CREDITED, credited.getStatus());
        assertNotNull(credited.getGrantId());

        List<CreatorCreditGrant> grants = grantRepository.findSpendable(CREATOR_USER_ID, clock.instant());
        assertEquals(1, grants.size(), "exactly one grant must exist from this purchase");
        CreatorCreditGrant grant = grants.get(0);
        assertEquals(CreditBucket.PAID, grant.getBucket());
        assertEquals(60, grant.getCreditsRemaining());
        assertEquals(60, grant.getCreditsGranted());
        assertEquals(
                expectedPaidAt.plus(Duration.ofDays(90)),
                grant.getExpiresAt(),
                "a paid pack's grant must expire exactly 90 days after paidAt (A26, owner ruling R2)");

        List<CreatorCreditLedgerEntry> ledgerRows =
                ledgerRepository.findByCreatorUserIdAndReferenceIdIn(
                        CREATOR_USER_ID, List.of("order:" + orderId));
        List<CreatorCreditLedgerEntry> grantRows =
                ledgerRows.stream().filter(e -> e.getReason() == CreditLedgerReason.GRANT_PURCHASE).toList();
        assertEquals(1, grantRows.size(), "exactly one GRANT_PURCHASE ledger row for this order");
        assertEquals("order:" + orderId, grantRows.get(0).getReferenceId());
        assertEquals(60, grantRows.get(0).getDelta());
        assertEquals(grant.getId(), grantRows.get(0).getGrantId());

        // A second confirmPaid call for the SAME order (a retried webhook delivery, the
        // reconciliation job's own sweep, or a duplicate /verify call) must be a true no-op —
        // exactly-once (A26) — not a second grant.
        CreatorCreditOrder secondCall =
                orderService.confirmPaid(orderId, paymentId, razorpayOrderId, amountPaise, currency);
        assertEquals(CreatorCreditOrderStatus.CREDITED, secondCall.getStatus());
        assertEquals(credited.getGrantId(), secondCall.getGrantId());
        assertEquals(
                1,
                grantRepository.findSpendable(CREATOR_USER_ID, clock.instant()).size(),
                "a second confirmPaid must never mint a second grant");
        assertEquals(
                1,
                ledgerRepository
                        .findByCreatorUserIdAndReferenceIdIn(CREATOR_USER_ID, List.of("order:" + orderId))
                        .stream()
                        .filter(e -> e.getReason() == CreditLedgerReason.GRANT_PURCHASE)
                        .count(),
                "a second confirmPaid must never write a second GRANT_PURCHASE ledger row");
    }
}
