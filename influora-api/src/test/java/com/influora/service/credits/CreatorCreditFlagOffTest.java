package com.influora.service.credits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.config.CreatorCreditProperties;
import com.influora.domain.entity.CreatorCreditAccount;
import com.influora.domain.enums.ChargeKind;
import com.influora.repository.CreatorCreditAccountRepository;
import com.influora.repository.CreatorCreditGrantRepository;
import com.influora.repository.CreatorCreditLedgerRepository;
import com.influora.repository.CreatorCreditWelcomeClaimRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.service.IdempotencyService;
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
 * T-CREATOR-CREDITS-V2 (SPEC.md §12, R8, K-24) — A34: with {@code CREATOR_CREDITS_ENABLED=false},
 * every flag-gated entry point (§2's table) behaves as a true no-op, touching no repository:
 * {@code charge} returns DISABLED without creating an account row; {@code grantWelcome} is a
 * no-op; {@code release} performs zero writes when no DEBIT rows exist (which is always true if
 * the flag was never on for this creator, per C19). {@code confirmPaid}/{@code release} are
 * explicitly NOT flag-gated by design (K-24) and are covered by {@code CreatorCreditOrderServiceTest}
 * / {@code CreatorTurnReleaseRoutingTest} instead — this class only proves the flag-gated half.
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
            "spring.datasource.url=" + "jdbc:h2:mem:creator_credit_flag_off_test;DB_CLOSE_DELAY=-1;MODE=MySQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
            "influora.creator-credits.enabled=false"
        })
@Import({
    CreatorCreditService.class,
    CreatorCreditAccountInitializer.class,
    CreatorCreditWelcomeClaimWriter.class,
    CreatorCreditProperties.class,
    CreatorCreditServiceTest.ClockTestConfig.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CreatorCreditFlagOffTest {

    private static final String CREATOR = "01HCREATORUSERFLAGOFFA";

    @Autowired private CreatorCreditService creditService;
    @Autowired private CreatorCreditAccountRepository accountRepository;
    @Autowired private CreatorCreditGrantRepository grantRepository;
    @Autowired private CreatorCreditLedgerRepository ledgerRepository;
    @Autowired private CreatorCreditWelcomeClaimRepository welcomeClaimRepository;

    @MockBean private CreatorProfileRepository creatorProfileRepository;
    @MockBean private MetaOAuthTokenRepository metaOAuthTokenRepository;
    @MockBean private IdempotencyService idempotencyService;

    @BeforeEach
    void wipe() {
        ledgerRepository.deleteAll();
        grantRepository.deleteAll();
        welcomeClaimRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    @DisplayName("A34: charge() returns DISABLED and creates NO creator_credit_accounts row")
    void chargeIsDisabledAndCreatesNoAccountRow() {
        ChargeResult result = creditService.charge(CREATOR, ChargeKind.TURN, "turn-flag-off-1");

        assertEquals(ChargeResult.Outcome.DISABLED, result.outcome());
        assertFalse(result.refused(), "DISABLED must never be treated as a refusal (no 402/429)");
        assertFalse(result.charged());
        assertEquals(0, accountRepository.count(), "no lock-anchor row may be created while the flag is off");
        assertEquals(0, grantRepository.count());
        assertEquals(0, ledgerRepository.count());
    }

    @Test
    @DisplayName("A34: grantWelcome() is a no-op while the flag is off, even for an eligible creator")
    void grantWelcomeIsNoOpWhileDisabled() {
        // No profile/token stubbing needed — grantWelcome must return before ever consulting them.
        creditService.grantWelcome(CREATOR);

        assertEquals(0, accountRepository.count());
        assertEquals(0, grantRepository.count());
        assertEquals(0, welcomeClaimRepository.count());
    }

    @Test
    @DisplayName("A34 (K-24): release() is NOT flag-gated but writes nothing when no DEBIT rows exist for this ulid")
    void releaseWritesNothingWhenNeverCharged() {
        // The flag was off for this creator's whole lifetime, so no DEBIT rows exist for this ulid
        // — release() must hit its empty-rows early return before ever locking the account.
        creditService.release(CREATOR, "turn-never-charged", ReleaseScope.TURN);

        assertEquals(0, accountRepository.count(), "release() must never create the account row on its own");
        assertEquals(0, ledgerRepository.count());
    }

    @Test
    @DisplayName("A34: balance() for a creator with no account row reports a clean, all-zero, ineligible view")
    void balanceIsZeroedWithoutWritingWhenDisabled() {
        BalanceView view = creditService.balance(CREATOR);

        assertEquals(0, view.total());
        assertEquals(0, view.free());
        assertEquals(0, view.paid());
        assertFalse(view.welcomeGranted());
        assertNull(view.welcomeGrantedAt());
        assertEquals(0, accountRepository.count(), "balance() must never write, flag on or off (A7)");
    }

    // ------------------------------------------------------------------------------------------
    // A34 (exact acceptance wording): "Flag off: creator sendTurn response equals today's
    // (creditsRemaining:0); no creator_credit_accounts row created; speak ignores turnId; GET ->
    // {enabled:false}; POST orders -> 404; brief paste needs no key; release writes nothing."
    //
    // The service-level pieces (no account row, release-writes-nothing, balance-never-writes) are
    // already proved above against the REAL CreatorCreditService/repositories. This method covers
    // the remaining controller-level half — CreatorMeeraController, CreatorBriefController and
    // CreatorCreditController — which none of this file's @DataJpaTest context reaches. No
    // @WebMvcTest harness exists for this controller family (see AuditLogControllerTest's javadoc),
    // so each controller is constructed directly with mocked collaborators, same pattern as every
    // other controller test in this module. These sub-assertions do not touch the @DataJpaTest
    // Spring context at all.
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "A34 (exact wording): flag off — creator sendTurn's creditsRemaining is the literal 0"
                    + " (today's contract); speak ignores turnId entirely (no credit-service call"
                    + " regardless of what turnId carries); GET /creator/credits -> {enabled:false}; POST"
                    + " /creator/credits/orders -> 404 FEATURE_DISABLED; brief paste needs no"
                    + " Idempotency-Key at all")
    void flagOffIsTodayExactly() {
        com.influora.config.CreatorCreditProperties disabledProps =
                mock(com.influora.config.CreatorCreditProperties.class);
        when(disabledProps.isEnabled()).thenReturn(false);

        // -- creator sendTurn: creditsRemaining is the literal 0, byte-identical to before B7
        // (MeeraSessionService.TurnResult#creditsRemaining is null for a flag-off CREATOR turn —
        // see that record's own javadoc — and CreatorMeeraController#sendTurn maps null -> 0).
        com.influora.service.meera.MeeraSessionService sessionService =
                mock(com.influora.service.meera.MeeraSessionService.class);
        com.influora.service.CreatorContextService creatorContext =
                mock(com.influora.service.CreatorContextService.class);
        com.influora.config.MeeraStreamProperties streamProperties =
                mock(com.influora.config.MeeraStreamProperties.class);
        com.influora.service.CreatorAgentPreferencesService preferencesService =
                mock(com.influora.service.CreatorAgentPreferencesService.class);
        com.influora.integration.ai.MeeraVoiceAiClient voiceAiClient =
                mock(com.influora.integration.ai.MeeraVoiceAiClient.class);
        com.influora.config.MeeraCreatorFeatureProperties featureProperties =
                mock(com.influora.config.MeeraCreatorFeatureProperties.class);
        CreatorCreditService creatorCreditServiceMock = mock(CreatorCreditService.class);

        com.influora.web.CreatorMeeraController meeraController =
                new com.influora.web.CreatorMeeraController(
                        sessionService,
                        creatorContext,
                        streamProperties,
                        preferencesService,
                        voiceAiClient,
                        featureProperties,
                        creatorCreditServiceMock,
                        disabledProps);

        when(featureProperties.isCreatorEnabled()).thenReturn(true);
        com.influora.security.AuthPrincipal principal = mock(com.influora.security.AuthPrincipal.class);
        when(principal.getUserId()).thenReturn(CREATOR);
        com.influora.domain.entity.CreatorProfile profile = mock(com.influora.domain.entity.CreatorProfile.class);
        when(profile.getUserId()).thenReturn(CREATOR);
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        when(preferencesService.isConsentAccepted(CREATOR)).thenReturn(true);

        com.influora.service.meera.MeeraSessionService.TurnResult flagOffResult =
                new com.influora.service.meera.MeeraSessionService.TurnResult(
                        "msg-1", null, "stream-token", "onbehalf-token", java.util.Map.of(), null, null);
        when(sessionService.sendTurn(
                        eq(CREATOR), eq(CREATOR), eq(com.influora.domain.enums.UserType.CREATOR),
                        eq("conv-1"), org.mockito.ArgumentMatchers.anyString(), eq("idem-1"), eq(false)))
                .thenReturn(flagOffResult);

        var sendTurnResponse =
                meeraController.sendTurn(
                        principal,
                        "conv-1",
                        "idem-1",
                        new com.influora.web.dto.meera.MeeraDtos.SendTurnRequest("hi", null));
        assertEquals(0, sendTurnResponse.getBody().data().creditsRemaining());

        // -- speak ignores turnId entirely: even a non-blank turnId never reaches
        // hasVoiceCharge/claimVoiceSpeak/release when the flag is off — the whole `if
        // (creditProperties.isEnabled())` gating block is skipped structurally.
        when(voiceAiClient.speak(CREATOR, "hello", null))
                .thenReturn(
                        com.influora.integration.ai.MeeraVoiceAiClient.SpeakResult.audio(
                                new byte[] {1, 2, 3}, "audio/wav"));
        var speakBody =
                new com.influora.web.CreatorMeeraController.VoiceSpeakRequest(
                        "hello", null, "some-turn-id-should-be-ignored");
        meeraController.speak(principal, speakBody);
        verify(creatorCreditServiceMock, never()).hasVoiceCharge(any(), any());
        verify(creatorCreditServiceMock, never()).claimVoiceSpeak(any(), any());
        verify(creatorCreditServiceMock, never()).release(any(), any(), any());
        verify(voiceAiClient).speak(CREATOR, "hello", null);

        // -- GET /creator/credits -> {enabled:false}, no service/order-service call at all.
        CreatorCreditOrderService orderService = mock(CreatorCreditOrderService.class);
        com.influora.integration.razorpay.CheckoutSignatureVerifier signatureVerifier =
                mock(com.influora.integration.razorpay.CheckoutSignatureVerifier.class);
        com.influora.integration.razorpay.RazorpayClient razorpayClient =
                mock(com.influora.integration.razorpay.RazorpayClient.class);
        com.influora.web.CreatorCreditController creditController =
                new com.influora.web.CreatorCreditController(
                        creatorContext, creatorCreditServiceMock, orderService, disabledProps, signatureVerifier, razorpayClient);

        var balanceResponse = creditController.balance(principal);
        assertFalse(balanceResponse.getBody().data().enabled());
        assertNull(balanceResponse.getBody().data().total());
        verify(orderService, never()).findActivePack(any());

        // -- POST /creator/credits/orders -> 404 FEATURE_DISABLED (orderService.createOrder is the
        // ONLY flag check on this path — CreatorCreditController.createOrder does not re-check the
        // flag itself, it delegates; CreatorCreditOrderServiceTest#createOrder_flagOff_refused
        // proves the real service's own behaviour, this proves the controller propagates it).
        when(orderService.createOrder(eq(CREATOR), eq("PACK_60"), eq("key-1")))
                .thenThrow(
                        new com.influora.common.ApiException(
                                "FEATURE_DISABLED",
                                "Creator credits are currently disabled",
                                org.springframework.http.HttpStatus.NOT_FOUND));
        com.influora.common.ApiException orderEx =
                assertThrows(
                        com.influora.common.ApiException.class,
                        () ->
                                creditController.createOrder(
                                        principal,
                                        "key-1",
                                        new com.influora.web.dto.credits.CreatorCreditDtos.CreateOrderRequest(
                                                "PACK_60")));
        assertEquals("FEATURE_DISABLED", orderEx.getCode());
        assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, orderEx.getStatus());

        // -- brief paste needs no Idempotency-Key at all when the flag is off (a null header is
        // accepted, never rejected, and idempotencyService is never consulted).
        com.influora.service.CreatorBriefService briefService = mock(com.influora.service.CreatorBriefService.class);
        com.influora.service.IdempotencyService idempotencyService =
                mock(com.influora.service.IdempotencyService.class);
        com.influora.web.CreatorBriefController briefController =
                new com.influora.web.CreatorBriefController(
                        briefService, creatorContext, preferencesService, featureProperties, disabledProps, idempotencyService);
        com.influora.web.dto.brief.BriefDtos.BriefAnalysisResponse pasted =
                new com.influora.web.dto.brief.BriefDtos.BriefAnalysisResponse(
                        "brief-1", "PASTE", "OK", null, null, java.util.List.of(), null, null, null,
                        java.util.List.of(), "2026-09-21T10:00:00Z");
        when(briefService.paste(CREATOR, "some brief text")).thenReturn(pasted);

        var pasteResponse =
                briefController.paste(
                        principal,
                        null, // no Idempotency-Key header at all
                        new com.influora.web.dto.brief.BriefDtos.PasteBriefRequest("some brief text"));

        assertEquals(org.springframework.http.HttpStatus.CREATED, pasteResponse.getStatusCode());
        assertEquals("brief-1", pasteResponse.getBody().data().briefId());
        verify(idempotencyService, never())
                .executeOnce(any(), any(), any(), org.mockito.ArgumentMatchers.any(java.util.function.Supplier.class));
    }

    private static <T> T eq(T value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }

    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
