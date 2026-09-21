package com.influora.service.credits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.config.CreatorCreditProperties;
import com.influora.config.RazorpayProperties;
import com.influora.domain.entity.CreatorCreditAccount;
import com.influora.domain.entity.CreatorCreditOrder;
import com.influora.domain.entity.CreatorCreditPack;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.enums.CreatorCreditOrderStatus;
import com.influora.domain.enums.UserType;
import com.influora.integration.razorpay.CheckoutSignatureVerifier;
import com.influora.integration.razorpay.RazorpayClient;
import com.influora.repository.CreatorCreditAccountRepository;
import com.influora.repository.CreatorCreditGrantRepository;
import com.influora.repository.CreatorCreditLedgerRepository;
import com.influora.repository.CreatorCreditOrderRepository;
import com.influora.repository.CreatorCreditWelcomeClaimRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.CreatorContextService;
import com.influora.service.IdempotencyService;
import com.influora.web.CreatorCreditController;
import com.influora.web.dto.credits.CreatorCreditDtos.VerifyOrderRequest;
import java.time.Instant;
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
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md §12, A32, K-21) — review finding #5 (round 2): the REAL ownership
 * filter, {@link CreatorCreditOrderService#findOwnedOrder} -&gt; {@code
 * CreatorCreditOrderRepository#findByIdAndCreatorUserId}, run against a real H2 database.
 *
 * <p>Before this test existed, {@code CreatorCreditControllerIdorTest} mocked {@code
 * orderService.findOwnedOrder} to return empty directly — it proved the CONTROLLER reacts
 * correctly to an empty {@code Optional}, but never proved the empty {@code Optional} actually
 * comes from the real ownership filter. Changing {@code findOwnedOrder} to {@code
 * orderRepository.findById(orderId)} (dropping the {@code creatorUserId} filter entirely) would
 * leave every existing local test green, and creator A could then verify (and read the status of)
 * creator B's order.
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
            "spring.datasource.url="
                    + "jdbc:h2:mem:creator_credit_order_ownership_real_test;DB_CLOSE_DELAY=-1;MODE=MySQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
            "influora.creator-credits.enabled=true"
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
class CreatorCreditOrderOwnershipRealTest {

    private static final String CREATOR_A = "01HCREATORIDORAAAAAA01";
    private static final String CREATOR_B = "01HCREATORIDORBBBBBB01";

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
    @MockBean private RazorpayClient razorpayClient;
    @MockBean private RazorpayProperties razorpayProperties;
    @MockBean private CreatorCreditInvoiceApplier invoiceApplier;

    private String bOrderId;

    @BeforeEach
    void resetClockAndData() {
        orderRepository.deleteAll();
        ledgerRepository.deleteAll();
        grantRepository.deleteAll();
        welcomeClaimRepository.deleteAll();
        accountRepository.deleteAll();
        clock.setInstant(Instant.parse("2026-09-10T10:00:00Z"));

        accountRepository.saveAndFlush(CreatorCreditAccount.newAccount(CREATOR_B));
        CreatorCreditPack pack = mock(CreatorCreditPack.class);
        when(pack.getId()).thenReturn("pack-60-id");
        when(pack.getCode()).thenReturn("PACK_60");
        when(pack.getCredits()).thenReturn(60);
        when(pack.getPricePaise()).thenReturn(24900);

        CreatorCreditOrder bsOrder =
                CreatorCreditOrder.newPending(
                        com.influora.common.Ulids.newUlid(), CREATOR_B, pack, "idem-" + com.influora.common.Ulids.newUlid());
        bsOrder.setRazorpayOrderId("order_test_FAKE_B");
        orderRepository.saveAndFlush(bsOrder);
        bOrderId = bsOrder.getId();
    }

    @Test
    @DisplayName(
            "K-21 (real, not mocked): findOwnedOrder(A, B's orderId) is empty; findOwnedOrder(B, B's"
                    + " orderId) is present -- proves the real repository-level ownership filter, not a"
                    + " mocked approximation of it")
    void findOwnedOrderIsRepositoryScopedToTheOwner() {
        assertTrue(
                orderService.findOwnedOrder(CREATOR_A, bOrderId).isEmpty(),
                "creator A must never be able to look up creator B's order by id");
        assertTrue(
                orderService.findOwnedOrder(CREATOR_B, bOrderId).isPresent(),
                "creator B must still be able to look up her own order");
    }

    @Test
    @DisplayName(
            "A32/K-21 (real, not mocked): CreatorCreditController#verify driven by a REAL"
                    + " CreatorCreditOrderService -- creator A with creator B's order id gets 404, and"
                    + " B's order row in the database is completely unchanged (still PENDING, no"
                    + " grantId, no payment id, no confirmPaid ever called)")
    void controllerVerifyWithRealOrderServiceLeavesBsOrderUntouched() {
        CreatorContextService creatorContext = mock(CreatorContextService.class);
        CreatorCreditService creditService = mock(CreatorCreditService.class);
        CheckoutSignatureVerifier signatureVerifier = mock(CheckoutSignatureVerifier.class);
        CreatorCreditProperties creditProperties = mock(CreatorCreditProperties.class);

        AuthPrincipal principalA = new AuthPrincipal(CREATOR_A, "a@example.com", UserType.CREATOR, null);
        CreatorProfile profileA = mock(CreatorProfile.class);
        when(profileA.getUserId()).thenReturn(CREATOR_A);
        when(creatorContext.requireCreatorProfile(principalA)).thenReturn(profileA);

        CreatorCreditController controller =
                new CreatorCreditController(
                        creatorContext, creditService, orderService, creditProperties, signatureVerifier, razorpayClient);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> controller.verify(principalA, bOrderId, new VerifyOrderRequest("pay_x", "sig")));

        assertEquals("CREDIT_ORDER_NOT_FOUND", ex.getCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());

        // B's row, re-read straight from the database, is byte-for-byte untouched -- the signature
        // check, the Razorpay gateway fetch, and confirmPaid were never reached for it.
        CreatorCreditOrder reloaded = orderRepository.findById(bOrderId).orElseThrow();
        assertEquals(CreatorCreditOrderStatus.PENDING, reloaded.getStatus());
        assertNull(reloaded.getGrantId());
        assertNull(reloaded.getRazorpayPaymentId());
        assertNull(reloaded.getPaidAt());
        assertNull(reloaded.getCreditedAt());
        assertFalse(
                grantRepository.findSpendable(CREATOR_B, clock.instant()).stream()
                        .findAny()
                        .isPresent(),
                "creator B must have no grant -- A's failed IDOR attempt must never credit anything");
        org.mockito.Mockito.verifyNoInteractions(signatureVerifier, razorpayClient);
    }
}
