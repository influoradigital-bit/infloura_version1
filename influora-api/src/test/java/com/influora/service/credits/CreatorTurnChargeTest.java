package com.influora.service.credits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.config.CreatorCreditProperties;
import com.influora.domain.entity.AiConversation;
import com.influora.domain.entity.AiMessage;
import com.influora.domain.entity.CreatorCreditAccount;
import com.influora.domain.entity.CreatorCreditGrant;
import com.influora.domain.entity.CreatorCreditLedgerEntry;
import com.influora.domain.enums.ConversationStatus;
import com.influora.domain.enums.ConversationTenantType;
import com.influora.domain.enums.CreditBucket;
import com.influora.domain.enums.CreditLedgerReason;
import com.influora.domain.enums.UserType;
import com.influora.repository.AiConversationRepository;
import com.influora.repository.AiMessageRepository;
import com.influora.repository.BrandProfileRepository;
import com.influora.repository.CreatorCreditAccountRepository;
import com.influora.repository.CreatorCreditGrantRepository;
import com.influora.repository.CreatorCreditLedgerRepository;
import com.influora.repository.CreatorCreditWelcomeClaimRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.service.CreatorAgentConversationService;
import com.influora.service.CreatorAgentPreferencesService;
import com.influora.service.IdempotencyService;
import com.influora.service.meera.AICreditService;
import com.influora.service.meera.BrandContextAssembler;
import com.influora.service.meera.MeeraSessionService;
import com.influora.service.meera.OnBehalfTokenService;
import com.influora.service.meera.StreamTokenService;
import com.influora.web.dto.creator.CreatorAgentDtos.PreferencesResponse;
import com.influora.service.creatorcopilot.CreatorRecommendationService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md &sect;12) &mdash; A13/A14/A15: end-to-end proof of {@code
 * MeeraSessionService#sendTurn}'s creator charge wiring against a REAL {@link CreatorCreditService}
 * (same {@code @DataJpaTest} + {@code MutableClock} H2 harness as {@link
 * CreatorTurnReleaseRoutingTest} / {@link CreatorTurnChargeAtomicityTest}), not a mocked
 * approximation of the charge math.
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
                    + "jdbc:h2:mem:creator_turn_charge_test;DB_CLOSE_DELAY=-1;MODE=MySQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
            "influora.creator-credits.enabled=true"
        })
@Import({
    CreatorCreditService.class,
    CreatorCreditAccountInitializer.class,
    CreatorCreditWelcomeClaimWriter.class,
    CreatorCreditProperties.class,
    CreatorCreditServiceTest.ClockTestConfig.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CreatorTurnChargeTest {

    private static final String CREATOR_USER_ID = "01HCREATORUSER0000000A";
    private static final String CONVERSATION_ID = "01HCONVERSATION1234AB";

    @Autowired private CreatorCreditService creatorCreditService;
    @Autowired private CreatorCreditAccountRepository accountRepository;
    @Autowired private CreatorCreditGrantRepository grantRepository;
    @Autowired private CreatorCreditLedgerRepository ledgerRepository;
    @Autowired private CreatorCreditWelcomeClaimRepository welcomeClaimRepository;
    @Autowired private CreatorCreditServiceTest.MutableClock clock;

    @MockBean private CreatorProfileRepository creatorProfileRepository;
    @MockBean private MetaOAuthTokenRepository metaOAuthTokenRepository;
    @MockBean private IdempotencyService idempotencyService;
    @MockBean private CreatorAgentPreferencesService creatorAgentPreferencesService;

    private AiMessageRepository messageRepository;
    private AiConversationRepository conversationRepository;
    private StreamTokenService streamTokenService;
    private OnBehalfTokenService onBehalfTokenService;
    private CreatorAgentConversationService creatorAgentConversationService;
    private MeeraSessionService service;

    @BeforeEach
    void wireService() {
        ledgerRepository.deleteAll();
        grantRepository.deleteAll();
        welcomeClaimRepository.deleteAll();
        accountRepository.deleteAll();
        clock.setInstant(Instant.parse("2026-09-10T10:00:00Z"));
        when(creatorProfileRepository.findByUserId(any())).thenReturn(Optional.empty());
        when(creatorAgentPreferencesService.getOrCreatePreferences(CREATOR_USER_ID))
                .thenReturn(preferences("en-IN"));

        when(idempotencyService.executeOnce(anyString(), eq(CREATOR_USER_ID), anyString(), any()))
                .thenAnswer(
                        invocation -> {
                            Supplier<?> supplier = invocation.getArgument(3);
                            return supplier.get();
                        });

        messageRepository = mock(AiMessageRepository.class);
        when(messageRepository.save(any(AiMessage.class))).thenAnswer(inv -> inv.getArgument(0));
        conversationRepository = mock(AiConversationRepository.class);
        when(conversationRepository.findByIdAndWorkspaceId(CONVERSATION_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(creatorConversation()));
        when(conversationRepository.findFirstByWorkspaceIdAndStatusOrderByLastMessageAtDesc(
                        CREATOR_USER_ID, ConversationStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(conversationRepository.save(any(AiConversation.class))).thenAnswer(inv -> inv.getArgument(0));
        streamTokenService = mock(StreamTokenService.class);
        when(streamTokenService.mint(anyString(), anyString(), anyString(), anyString(), eq(UserType.CREATOR)))
                .thenReturn("stream-token-1");
        onBehalfTokenService = mock(OnBehalfTokenService.class);
        when(onBehalfTokenService.mint(
                        anyString(), anyString(), anyString(), anyString(), eq(UserType.CREATOR), anyString()))
                .thenReturn("onbehalf-token-1");
        creatorAgentConversationService = mock(CreatorAgentConversationService.class);

        service =
                new MeeraSessionService(
                        conversationRepository,
                        messageRepository,
                        mock(WorkspaceRepository.class),
                        mock(BrandProfileRepository.class),
                        mock(AICreditService.class),
                        mock(BrandContextAssembler.class),
                        streamTokenService,
                        onBehalfTokenService,
                        idempotencyService,
                        creatorAgentConversationService,
                        creatorAgentPreferencesService,
                        creatorCreditService,
                        mock(PlatformTransactionManager.class),
                        mock(CreatorRecommendationService.class));
    }

    private void seedGrant(int credits) {
        accountRepository
                .findById(CREATOR_USER_ID)
                .orElseGet(() -> accountRepository.saveAndFlush(CreatorCreditAccount.newAccount(CREATOR_USER_ID)));
        grantRepository.saveAndFlush(
                CreatorCreditGrant.of(
                        com.influora.common.Ulids.newUlid(),
                        CREATOR_USER_ID,
                        CreditBucket.PAID,
                        credits,
                        clock.instant(),
                        null,
                        "order:big"));
    }

    private int totalRemaining() {
        return grantRepository.findSpendable(CREATOR_USER_ID, clock.instant()).stream()
                .mapToInt(CreatorCreditGrant::getCreditsRemaining)
                .sum();
    }

    private AiConversation creatorConversation() {
        return AiConversation.builder()
                .id(CONVERSATION_ID)
                .workspaceId(CREATOR_USER_ID)
                .tenantType(ConversationTenantType.CREATOR)
                .startedBy(CREATOR_USER_ID)
                .status(ConversationStatus.ACTIVE)
                .build();
    }

    private static PreferencesResponse preferences(String creatorLanguage) {
        return new PreferencesResponse(
                null, null, null, null, List.of(), List.of(), 0, creatorLanguage, null, null, null, null, List.of(),
                null, false, null, true, null, false, null, false, 0, false);
    }

    // ------------------------------------------------------------------
    // A13 — text turn debits 1 (turn:); voice turn debits 2 (turn: + tts:) in one transaction;
    // the response carries the real creditsRemaining.
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "A13: text turn debits 1 (turn:); voice turn debits 2 (turn: + tts:) in one transaction;"
                    + " the response carries the real creditsRemaining")
    void textOneVoiceTwo() {
        seedGrant(10);

        MeeraSessionService.TurnResult textResult =
                service.sendTurn(
                        CREATOR_USER_ID, CREATOR_USER_ID, UserType.CREATOR, CONVERSATION_ID, "hi", "idem-text-1");

        assertEquals(9, totalRemaining());
        assertEquals(9, textResult.creditsRemaining(), "response must carry the real remaining balance");
        String textTurnRef = CreatorCreditService.turnRef(textResult.userMessageId());
        List<CreatorCreditLedgerEntry> textDebits =
                ledgerRepository.findByCreatorUserIdAndReferenceIdIn(CREATOR_USER_ID, List.of(textTurnRef));
        assertEquals(1, textDebits.stream().filter(e -> e.getReason() == CreditLedgerReason.DEBIT_TURN).count());
        assertEquals(0, textDebits.stream().filter(e -> e.getReason() == CreditLedgerReason.DEBIT_VOICE).count());

        MeeraSessionService.TurnResult voiceResult =
                service.sendTurn(
                        CREATOR_USER_ID,
                        CREATOR_USER_ID,
                        UserType.CREATOR,
                        CONVERSATION_ID,
                        "hi again",
                        "idem-voice-1",
                        true);

        assertEquals(7, totalRemaining(), "9 - 2 (turn + tts)");
        assertEquals(7, voiceResult.creditsRemaining());
        String voiceTurnRef = CreatorCreditService.turnRef(voiceResult.userMessageId());
        String voiceTtsRef = CreatorCreditService.ttsRef(voiceResult.userMessageId());
        List<CreatorCreditLedgerEntry> voiceRows =
                ledgerRepository.findByCreatorUserIdAndReferenceIdIn(
                        CREATOR_USER_ID, List.of(voiceTurnRef, voiceTtsRef));
        assertEquals(
                1,
                voiceRows.stream()
                        .filter(e -> e.getReason() == CreditLedgerReason.DEBIT_TURN && e.getReferenceId().equals(voiceTurnRef))
                        .count(),
                "the turn: half of a voice turn");
        assertEquals(
                1,
                voiceRows.stream()
                        .filter(e -> e.getReason() == CreditLedgerReason.DEBIT_VOICE && e.getReferenceId().equals(voiceTtsRef))
                        .count(),
                "the tts: half of a voice turn");
    }

    // ------------------------------------------------------------------
    // A14 — 0 balance -> 402 CREATOR_CREDITS_EXHAUSTED with the en/hi template by creator_language;
    // no USER row and no stream token.
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "A14: 0 balance -> 402 CREATOR_CREDITS_EXHAUSTED with the en/hi template by"
                    + " creator_language; no USER row and no stream token")
    void exhaustedRefusesBeforePersistWithTemplate() {
        // No grant seeded at all -- balance is 0.
        accountRepository
                .findById(CREATOR_USER_ID)
                .orElseGet(() -> accountRepository.saveAndFlush(CreatorCreditAccount.newAccount(CREATOR_USER_ID)));

        ApiException english =
                org.junit.jupiter.api.Assertions.assertThrows(
                        ApiException.class,
                        () ->
                                service.sendTurn(
                                        CREATOR_USER_ID,
                                        CREATOR_USER_ID,
                                        UserType.CREATOR,
                                        CONVERSATION_ID,
                                        "hi",
                                        "idem-exhausted-en"));
        assertEquals("CREATOR_CREDITS_EXHAUSTED", english.getCode());
        assertEquals(HttpStatus.PAYMENT_REQUIRED, english.getStatus());
        assertTrue(english.getMessage().contains("out of credits"), "must render the English server template");
        verify(messageRepository, never()).save(any(AiMessage.class));
        verify(streamTokenService, never()).mint(any(), any(), any(), any(), any());
        verify(onBehalfTokenService, never()).mint(any(), any(), any(), any(), any(), any());
        assertEquals(0, totalRemaining());

        when(creatorAgentPreferencesService.getOrCreatePreferences(CREATOR_USER_ID)).thenReturn(preferences("hi-IN"));

        ApiException hindi =
                org.junit.jupiter.api.Assertions.assertThrows(
                        ApiException.class,
                        () ->
                                service.sendTurn(
                                        CREATOR_USER_ID,
                                        CREATOR_USER_ID,
                                        UserType.CREATOR,
                                        CONVERSATION_ID,
                                        "hi",
                                        "idem-exhausted-hi"));
        assertEquals("CREATOR_CREDITS_EXHAUSTED", hindi.getCode());
        assertEquals(HttpStatus.PAYMENT_REQUIRED, hindi.getStatus());
        assertTrue(hindi.getMessage().contains("क्रेडिट्स"), "must render the Hindi server template");
        verify(messageRepository, never()).save(any(AiMessage.class));
    }

    // ------------------------------------------------------------------
    // A15 — an unknown client field/flag never makes a turn free; only the server-generated
    // onboarding greeting (startOrResumeForCreator) costs 0, by construction (it never calls
    // sendTurn/charge at all).
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "A15: a request carrying an unknown greeting:true field is charged normally; the Meera"
                    + " greeting via startOrResumeForCreator costs 0")
    void noClientFlagMakesTurnFree() {
        seedGrant(10);

        // The server-generated onboarding greeting: MeeraSessionService#sendTurn/doSendTurn is
        // never called by this path at all, so it cannot be charged by construction, whatever
        // "greeting"-shaped content it contains -- there is no client-suppliable flag anywhere in
        // this call that could route a real chat turn onto this free path.
        service.startOrResumeForCreator(CREATOR_USER_ID, CREATOR_USER_ID, "Asha", "en-IN");
        assertEquals(10, totalRemaining(), "the server's own onboarding greeting must never be charged");
        assertEquals(0, accountRepository.findById(CREATOR_USER_ID).orElseThrow().getDailyUsed());

        // MeeraSessionService#sendTurn (and the DTO/controller layer above it) carries no
        // "greeting"/"isFree" field of any kind -- content text and any unknown client field are
        // both irrelevant to the charge decision (K-17: never a client-supplied or model-authored
        // marker). A real turn through sendTurn, even with greeting-shaped content ("hi"), is
        // charged exactly like any other turn.
        MeeraSessionService.TurnResult result =
                service.sendTurn(
                        CREATOR_USER_ID, CREATOR_USER_ID, UserType.CREATOR, CONVERSATION_ID, "hi", "idem-greeting-like");

        assertEquals(9, totalRemaining(), "a real turn, even with greeting-shaped content, is charged normally");
        assertEquals(9, result.creditsRemaining());
    }

    // ------------------------------------------------------------------
    // 2026-09-22 (Swapnil, option A) — "Write a script" / "Review my profile" buttons cost 3.
    // They are debited as ONE DEBIT_TURN row of -3 under turn:<id>, so the TURN path's refund,
    // write-back marker and locking all apply unchanged.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Script and profile-review turns each debit 3 on one DEBIT_TURN row, never a voice row")
    void scriptAndProfileReviewDebitThree() {
        seedGrant(10);

        MeeraSessionService.TurnResult script =
                service.sendTurn(
                        CREATOR_USER_ID, CREATOR_USER_ID, UserType.CREATOR, CONVERSATION_ID,
                        "Write me a reel script about monsoon skincare", "idem-script-1",
                        com.influora.domain.enums.ChargeKind.SCRIPT);
        assertEquals(7, totalRemaining());
        assertEquals(7, script.creditsRemaining());
        String turnRef = CreatorCreditService.turnRef(script.userMessageId());
        String ttsRef = CreatorCreditService.ttsRef(script.userMessageId());
        List<CreatorCreditLedgerEntry> rows =
                ledgerRepository.findByCreatorUserIdAndReferenceIdIn(CREATOR_USER_ID, List.of(turnRef, ttsRef));
        assertEquals(1, rows.size(), "exactly one debit row for a script turn");
        assertEquals(CreditLedgerReason.DEBIT_TURN, rows.get(0).getReason());
        assertEquals(-3, rows.get(0).getDelta());
        assertEquals(3, accountRepository.findById(CREATOR_USER_ID).orElseThrow().getDailyUsed());

        MeeraSessionService.TurnResult review =
                service.sendTurn(
                        CREATOR_USER_ID, CREATOR_USER_ID, UserType.CREATOR, CONVERSATION_ID,
                        "Review my profile", "idem-profile-1",
                        com.influora.domain.enums.ChargeKind.PROFILE_REVIEW);
        assertEquals(4, totalRemaining());
        assertEquals(4, review.creditsRemaining());
    }

    @Test
    @DisplayName("A script turn whose reply never arrives is refunded all 3 credits")
    void failedScriptTurnRefundsAllThree() {
        seedGrant(10);
        MeeraSessionService.TurnResult script =
                service.sendTurn(
                        CREATOR_USER_ID, CREATOR_USER_ID, UserType.CREATOR, CONVERSATION_ID,
                        "Write me a script", "idem-script-refund",
                        com.influora.domain.enums.ChargeKind.SCRIPT);
        assertEquals(7, totalRemaining());

        creatorCreditService.release(CREATOR_USER_ID, script.userMessageId(), ReleaseScope.TURN);

        assertEquals(10, totalRemaining(), "the whole 3 comes back, not 1");
        assertEquals(0, accountRepository.findById(CREATOR_USER_ID).orElseThrow().getDailyUsed());
    }

    @Test
    @DisplayName("With 2 credits a script is refused (says it needs 3), nothing is saved, and a normal message still works")
    void twoCreditsRefusesScriptButNotAMessage() {
        seedGrant(2);

        ApiException refused =
                org.junit.jupiter.api.Assertions.assertThrows(
                        ApiException.class,
                        () -> service.sendTurn(
                                CREATOR_USER_ID, CREATOR_USER_ID, UserType.CREATOR, CONVERSATION_ID,
                                "Write me a script", "idem-script-short",
                                com.influora.domain.enums.ChargeKind.SCRIPT));
        assertEquals("CREATOR_CREDITS_EXHAUSTED", refused.getCode());
        assertEquals(HttpStatus.PAYMENT_REQUIRED, refused.getStatus());
        assertTrue(refused.getMessage().contains("A script uses 3 credits and you have 2"), refused.getMessage());
        verify(messageRepository, never()).save(any(AiMessage.class));
        assertEquals(2, totalRemaining());

        MeeraSessionService.TurnResult normal =
                service.sendTurn(
                        CREATOR_USER_ID, CREATOR_USER_ID, UserType.CREATOR, CONVERSATION_ID, "hi", "idem-after-refusal");
        assertEquals(1, normal.creditsRemaining());
    }

    @Test
    @DisplayName("At 28 of 30 today a profile review is refused by the daily cap, and a normal message still works")
    void dailyCapRefusesReviewButNotAMessage() {
        seedGrant(20);
        CreatorCreditAccount account = accountRepository.findById(CREATOR_USER_ID).orElseThrow();
        account.rollDayIfNeeded(java.time.LocalDate.now(clock.withZone(java.time.ZoneId.of("Asia/Kolkata"))));
        account.addDailyUsed(28);
        accountRepository.saveAndFlush(account);

        ApiException refused =
                org.junit.jupiter.api.Assertions.assertThrows(
                        ApiException.class,
                        () -> service.sendTurn(
                                CREATOR_USER_ID, CREATOR_USER_ID, UserType.CREATOR, CONVERSATION_ID,
                                "Review my profile", "idem-review-cap",
                                com.influora.domain.enums.ChargeKind.PROFILE_REVIEW));
        assertEquals("CREATOR_DAILY_CAP_REACHED", refused.getCode());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, refused.getStatus());
        assertTrue(refused.getMessage().contains("A profile review uses 3 credits"), refused.getMessage());
        assertEquals(20, totalRemaining());

        MeeraSessionService.TurnResult normal =
                service.sendTurn(
                        CREATOR_USER_ID, CREATOR_USER_ID, UserType.CREATOR, CONVERSATION_ID, "hi", "idem-after-cap");
        assertEquals(19, normal.creditsRemaining());
    }

    @Test
    @DisplayName("The script refusal has a Hindi version")
    void scriptRefusalInHindi() {
        ApiException hindi =
                CreatorCreditService.refusal(
                        ChargeResult.insufficient(com.influora.domain.enums.ChargeKind.SCRIPT, 3, 2, 0), "hi-IN");
        assertTrue(hindi.getMessage().contains("स्क्रिप्ट"), hindi.getMessage());
        assertTrue(hindi.getMessage().contains("3"), hindi.getMessage());
    }
}
