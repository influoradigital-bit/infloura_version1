package com.influora.service.credits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.config.CreatorCreditProperties;
import com.influora.domain.entity.AiConversation;
import com.influora.domain.entity.AiMessage;
import com.influora.domain.entity.CreatorCreditAccount;
import com.influora.domain.entity.CreatorCreditGrant;
import com.influora.domain.entity.CreatorCreditLedgerEntry;
import com.influora.domain.enums.ChargeKind;
import com.influora.domain.enums.ConversationStatus;
import com.influora.domain.enums.ConversationTenantType;
import com.influora.domain.enums.CreditBucket;
import com.influora.domain.enums.MessageRole;
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
import com.influora.service.creatorcopilot.CreatorRecommendationService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md §12, K-05, K-15) — A16/A17: {@link
 * MeeraSessionService#releaseTurnCredit} routes by the CONVERSATION's own {@code tenantType}
 * (never a client-supplied audience claim), verifies the turn actually belongs to that
 * conversation before refunding anything, and a CREATOR release never reaches {@link
 * AICreditService}. The underlying {@link CreatorCreditService#release} used here is the REAL
 * bean (this class reuses the {@code CreatorCreditServiceTest} H2 harness), so A17's idempotency
 * guards (never-charged is a no-op; a second release is a no-op) are proven against real charge/
 * release behaviour, not a mocked approximation.
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
            "spring.datasource.url=" + "jdbc:h2:mem:creator_turn_release_routing_test;DB_CLOSE_DELAY=-1;MODE=MySQL",
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
class CreatorTurnReleaseRoutingTest {

    private static final String CREATOR_USER_ID = "01HCREATORUSER0000000A";
    private static final String CONVERSATION_ID = "01HCONVERSATION1234AB";
    private static final String TURN_ID = "turn-route-1";

    @Autowired private CreatorCreditService creatorCreditService;
    @Autowired private CreatorCreditAccountRepository accountRepository;
    @Autowired private CreatorCreditGrantRepository grantRepository;
    @Autowired private CreatorCreditLedgerRepository ledgerRepository;
    @Autowired private CreatorCreditWelcomeClaimRepository welcomeClaimRepository;
    @Autowired private CreatorCreditServiceTest.MutableClock clock;

    @MockBean private CreatorProfileRepository creatorProfileRepository;
    @MockBean private MetaOAuthTokenRepository metaOAuthTokenRepository;
    @MockBean private IdempotencyService idempotencyService;

    private AiMessageRepository messageRepository;
    private AiConversationRepository conversationRepository;
    private AICreditService brandCreditService;
    private MeeraSessionService service;

    @BeforeEach
    void wireService() {
        ledgerRepository.deleteAll();
        grantRepository.deleteAll();
        welcomeClaimRepository.deleteAll();
        accountRepository.deleteAll();
        clock.setInstant(Instant.parse("2026-09-10T10:00:00Z"));

        messageRepository = mock(AiMessageRepository.class);
        conversationRepository = mock(AiConversationRepository.class);
        brandCreditService = mock(AICreditService.class);
        // idempotencyService.isCompleted defaults to false (unstubbed mock) unless a test
        // overrides it, which is exactly A17's "never write-back-completed" starting state.

        service =
                new MeeraSessionService(
                        conversationRepository,
                        messageRepository,
                        mock(WorkspaceRepository.class),
                        mock(BrandProfileRepository.class),
                        brandCreditService,
                        mock(BrandContextAssembler.class),
                        mock(StreamTokenService.class),
                        mock(OnBehalfTokenService.class),
                        idempotencyService,
                        mock(CreatorAgentConversationService.class),
                        mock(CreatorAgentPreferencesService.class),
                        creatorCreditService,
                        mock(PlatformTransactionManager.class),
                        mock(CreatorRecommendationService.class));
    }

    private CreatorCreditGrant seedGrant(int credits) {
        accountRepository
                .findById(CREATOR_USER_ID)
                .orElseGet(() -> accountRepository.saveAndFlush(CreatorCreditAccount.newAccount(CREATOR_USER_ID)));
        return grantRepository.saveAndFlush(
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

    private AiMessage userTurnMessage() {
        return AiMessage.builder()
                .id(TURN_ID)
                .conversationId(CONVERSATION_ID)
                .role(MessageRole.USER)
                .content("hi")
                .creditsCharged(0)
                .build();
    }

    @Test
    @DisplayName(
            "A16: a CREATOR-conversation release restores the creator's credits and never touches"
                    + " AICreditService (no brand_ai_credits row is ever created for a creator id)")
    void routesCreatorConversationToCreatorCreditServiceOnly() {
        seedGrant(10);
        creatorCreditService.charge(CREATOR_USER_ID, ChargeKind.TURN, TURN_ID);
        assertEquals(9, totalRemaining());

        when(messageRepository.findById(TURN_ID)).thenReturn(Optional.of(userTurnMessage()));

        service.releaseTurnCredit(creatorConversation(), TURN_ID);

        assertEquals(10, totalRemaining(), "the creator's own ledger must be refunded");
        verify(brandCreditService, never()).release(anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("A16: a turn id from a DIFFERENT conversation is a silent no-op — no refund")
    void turnIdFromAnotherConversationIsNoOp() {
        seedGrant(10);
        creatorCreditService.charge(CREATOR_USER_ID, ChargeKind.TURN, TURN_ID);
        assertEquals(9, totalRemaining());

        AiMessage wrongConversationMessage =
                AiMessage.builder()
                        .id(TURN_ID)
                        .conversationId("some-other-conversation")
                        .role(MessageRole.USER)
                        .content("hi")
                        .creditsCharged(0)
                        .build();
        when(messageRepository.findById(TURN_ID)).thenReturn(Optional.of(wrongConversationMessage));

        service.releaseTurnCredit(creatorConversation(), TURN_ID);

        assertEquals(9, totalRemaining(), "a turn id belonging to a different conversation must never refund");
    }

    @Test
    @DisplayName("A16: the BRAND (WORKSPACE) path is unchanged — routes to AICreditService.release, never CreatorCreditService")
    void routesWorkspaceConversationToAiCreditServiceOnly() {
        AiConversation brandConversation =
                AiConversation.builder()
                        .id(CONVERSATION_ID)
                        .workspaceId("01HWORKSPACE0000000000")
                        .tenantType(ConversationTenantType.WORKSPACE)
                        .startedBy("01HUSER00000000000000A")
                        .status(ConversationStatus.ACTIVE)
                        .build();

        service.releaseTurnCredit(brandConversation, TURN_ID);

        verify(brandCreditService).release("01HWORKSPACE0000000000", 1, TURN_ID);
        verify(messageRepository, never()).findById(anyString());
    }

    @Test
    @DisplayName("A17: release() on a turn that was never charged is a no-op (no rows, no exception)")
    void releaseNeverChargedIsNoOp() {
        when(messageRepository.findById(TURN_ID)).thenReturn(Optional.of(userTurnMessage()));

        service.releaseTurnCredit(creatorConversation(), "turn-never-charged");

        assertEquals(0, ledgerRepository.count());
        assertEquals(0, accountRepository.count());
    }

    @Test
    @DisplayName("A17: release() is idempotent — a second release for the same turn does not double-refund")
    void releaseIsIdempotent() {
        seedGrant(10);
        creatorCreditService.charge(CREATOR_USER_ID, ChargeKind.TURN, TURN_ID);
        when(messageRepository.findById(TURN_ID)).thenReturn(Optional.of(userTurnMessage()));

        service.releaseTurnCredit(creatorConversation(), TURN_ID);
        service.releaseTurnCredit(creatorConversation(), TURN_ID);

        assertEquals(10, totalRemaining(), "a second release must not over-refund past the original charge");
        List<CreatorCreditLedgerEntry> refundRows =
                ledgerRepository.findByCreatorUserIdAndReferenceIdIn(CREATOR_USER_ID, List.of(CreatorCreditService.turnRef(TURN_ID)));
        long refundCount =
                refundRows.stream()
                        .filter(e -> e.getReason() == com.influora.domain.enums.CreditLedgerReason.REFUND)
                        .count();
        assertEquals(1, refundCount, "exactly one REFUND row, ever, for this turn");
    }

    @Test
    @DisplayName(
            "A17/K-15 (round 2): release() no-ops once the write-back has actually persisted a"
                    + " WRITEBACK_MARKER for this turn -- real CreatorCreditService, not a mocked"
                    + " idempotencyService.isCompleted() stub")
    void releaseNoOpsAfterWritebackCompleted() {
        seedGrant(10);
        creatorCreditService.charge(CREATOR_USER_ID, ChargeKind.TURN, TURN_ID);
        // The real write-back path (MeeraSessionService#doPersistAssistantWriteback) calls this,
        // under the account lock, inside the SAME physical transaction as the ASSISTANT insert --
        // simulated directly here since this test drives CreatorCreditService, not the write-back
        // call itself.
        creatorCreditService.markWritebackPersisted(CREATOR_USER_ID, TURN_ID);
        when(messageRepository.findById(TURN_ID)).thenReturn(Optional.of(userTurnMessage()));

        service.releaseTurnCredit(creatorConversation(), TURN_ID);

        assertEquals(9, totalRemaining(), "a turn whose write-back already persisted must never be refunded");
        assertEquals(
                0,
                ledgerRepository
                        .findByCreatorUserIdAndReferenceIdIn(CREATOR_USER_ID, List.of(CreatorCreditService.turnRef(TURN_ID)))
                        .stream()
                        .filter(e -> e.getReason() == com.influora.domain.enums.CreditLedgerReason.REFUND)
                        .count(),
                "no REFUND row must ever be written once the write-back has persisted");
    }

    @Test
    @DisplayName(
            "K-15 (round 2, the exact race #1 flags): write-back commits (marker + reply both"
                    + " persisted), THEN release() lands, before any IdempotencyService completion"
                    + " write -- still no refund, because release() reads the marker under the same"
                    + " account lock the write-back held, never IdempotencyService")
    void releaseAfterWritebackCommitButBeforeIdempotencyMarkedCompletedStillNoOps() {
        seedGrant(10);
        creatorCreditService.charge(CREATOR_USER_ID, ChargeKind.TURN, TURN_ID);
        // Write-back's ASSISTANT row + WRITEBACK_MARKER commit together -- simulated by calling the
        // real marker write directly. Deliberately leave idempotencyService.isCompleted()
        // UNSTUBBED (defaults to false via Mockito), reproducing exactly the window #1 describes:
        // the write-back's own transaction has committed, but IdempotencyService's SEPARATE
        // REQUIRES_NEW completion write has not happened yet.
        creatorCreditService.markWritebackPersisted(CREATOR_USER_ID, TURN_ID);
        when(messageRepository.findById(TURN_ID)).thenReturn(Optional.of(userTurnMessage()));

        service.releaseTurnCredit(creatorConversation(), TURN_ID);

        assertEquals(
                9,
                totalRemaining(),
                "release() must not refund-and-keep-reply even while IdempotencyService still reports"
                        + " isCompleted()==false for this turn");
    }

    // ------------------------------------------------------------------------------------------
    // SPEC.md §12 exact acceptance-table names (K-05, K-15) — these consolidate the same proofs
    // as the tests above, under the EXACT class#method names the acceptance table names, so a
    // by-name test runner (and the falsification gate) can find them. The tests above are kept
    // (not removed): they are real, independently useful regression coverage the round-1 build
    // already had, and removing them would only shrink coverage for no gain.
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "A16: a creator-conversation release restores creator credits and leaves brand_ai_credits"
                    + " row count unchanged; a turn id from another conversation -> no refund; the"
                    + " brand path is unchanged")
    void routesByTenantTypeAndVerifiesTurn() {
        // (1) CREATOR conversation -> CreatorCreditService only, never AICreditService.
        seedGrant(10);
        creatorCreditService.charge(CREATOR_USER_ID, ChargeKind.TURN, TURN_ID);
        assertEquals(9, totalRemaining());
        when(messageRepository.findById(TURN_ID)).thenReturn(Optional.of(userTurnMessage()));

        service.releaseTurnCredit(creatorConversation(), TURN_ID);

        assertEquals(10, totalRemaining(), "the creator's own ledger must be refunded");
        verify(brandCreditService, never()).release(anyString(), anyInt(), anyString());

        // (2) A turn id belonging to a DIFFERENT conversation must never refund.
        creatorCreditService.charge(CREATOR_USER_ID, ChargeKind.TURN, "turn-route-other-conv");
        assertEquals(9, totalRemaining());
        AiMessage wrongConversationMessage =
                AiMessage.builder()
                        .id("turn-route-other-conv")
                        .conversationId("some-other-conversation")
                        .role(MessageRole.USER)
                        .content("hi")
                        .creditsCharged(0)
                        .build();
        when(messageRepository.findById("turn-route-other-conv")).thenReturn(Optional.of(wrongConversationMessage));

        service.releaseTurnCredit(creatorConversation(), "turn-route-other-conv");

        assertEquals(9, totalRemaining(), "a turn id belonging to a different conversation must never refund");

        // (3) BRAND (WORKSPACE) conversation -> AICreditService only, CreatorCreditService/message
        // lookup never touched, so the brand path is provably unchanged by this routing.
        AiConversation brandConversation =
                AiConversation.builder()
                        .id(CONVERSATION_ID)
                        .workspaceId("01HWORKSPACE0000000000")
                        .tenantType(ConversationTenantType.WORKSPACE)
                        .startedBy("01HUSER00000000000000A")
                        .status(ConversationStatus.ACTIVE)
                        .build();

        service.releaseTurnCredit(brandConversation, "brand-turn-1");

        verify(brandCreditService).release("01HWORKSPACE0000000000", 1, "brand-turn-1");
    }

    @Test
    @DisplayName(
            "A17: release is idempotent; never-charged -> no-op; after write-back completed -> no-op")
    void releaseGuards() {
        // (1) Never charged -> no-op (no rows, no exception).
        when(messageRepository.findById("turn-never-charged")).thenReturn(Optional.of(userTurnMessage()));
        service.releaseTurnCredit(creatorConversation(), "turn-never-charged");
        assertEquals(0, ledgerRepository.count());
        assertEquals(0, accountRepository.count());

        // (2) Idempotent: a second release for the same charged turn must not double-refund.
        seedGrant(10);
        creatorCreditService.charge(CREATOR_USER_ID, ChargeKind.TURN, TURN_ID);
        when(messageRepository.findById(TURN_ID)).thenReturn(Optional.of(userTurnMessage()));

        service.releaseTurnCredit(creatorConversation(), TURN_ID);
        service.releaseTurnCredit(creatorConversation(), TURN_ID);

        assertEquals(10, totalRemaining(), "a second release must not over-refund past the original charge");
        long refundCount =
                ledgerRepository
                        .findByCreatorUserIdAndReferenceIdIn(
                                CREATOR_USER_ID, List.of(CreatorCreditService.turnRef(TURN_ID)))
                        .stream()
                        .filter(e -> e.getReason() == com.influora.domain.enums.CreditLedgerReason.REFUND)
                        .count();
        assertEquals(1, refundCount, "exactly one REFUND row, ever, for this turn");

        // (3) After write-back has persisted (real WRITEBACK_MARKER, K-15 round 2) for a turn,
        // release() is a no-op -- checked against the real marker, not a mocked
        // idempotencyService.isCompleted() stub.
        creatorCreditService.charge(CREATOR_USER_ID, ChargeKind.TURN, "turn-writeback-done");
        assertEquals(9, totalRemaining());
        when(messageRepository.findById("turn-writeback-done"))
                .thenReturn(
                        Optional.of(
                                AiMessage.builder()
                                        .id("turn-writeback-done")
                                        .conversationId(CONVERSATION_ID)
                                        .role(MessageRole.USER)
                                        .content("hi")
                                        .creditsCharged(0)
                                        .build()));
        creatorCreditService.markWritebackPersisted(CREATOR_USER_ID, "turn-writeback-done");

        service.releaseTurnCredit(creatorConversation(), "turn-writeback-done");

        assertEquals(9, totalRemaining(), "a turn whose write-back already persisted must never be refunded");
    }
}
