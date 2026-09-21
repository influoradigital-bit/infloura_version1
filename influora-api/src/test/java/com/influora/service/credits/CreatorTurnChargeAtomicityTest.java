package com.influora.service.credits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md &sect;12, K-01) &mdash; A12: proves the compensating-release fix
 * for every throw site AFTER {@code CreatorCreditService#charge} inside {@code
 * MeeraSessionService#doSendTurn} (the USER-row save, the stream-token mint, the on-behalf-token
 * mint). This runs a REAL, Spring-managed {@link CreatorCreditService} against a real H2 database
 * (the exact {@code @DataJpaTest} + {@code MutableClock} harness {@link CreatorCreditServiceTest}
 * and {@link CreatorTurnReleaseRoutingTest} use) &mdash; a Mockito-only test that also mocks {@code
 * CreatorCreditService} could not tell a genuine, transactionally-correct release from a stub that
 * merely records a call, which is exactly the gap K-01 flags. {@code MeeraSessionService} itself is
 * a plain object wired by hand (not a container bean) with Mockito mocks/spies for everything it
 * needs besides the credit service, the same technique {@link CreatorTurnReleaseRoutingTest} uses.
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
                    + "jdbc:h2:mem:creator_turn_charge_atomicity_test;DB_CLOSE_DELAY=-1;MODE=MySQL",
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
class CreatorTurnChargeAtomicityTest {

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

    @BeforeEach
    void resetClockAndData() {
        ledgerRepository.deleteAll();
        grantRepository.deleteAll();
        welcomeClaimRepository.deleteAll();
        accountRepository.deleteAll();
        clock.setInstant(Instant.parse("2026-09-10T10:00:00Z"));
        when(creatorProfileRepository.findByUserId(any())).thenReturn(Optional.empty());
        when(creatorAgentPreferencesService.getOrCreatePreferences(CREATOR_USER_ID))
                .thenReturn(preferences());

        // executeOnce invokes the supplier directly -- same stub technique MeeraSessionServiceTest
        // uses (mockIdempotencyExecuteOnceWithResultRef).
        when(idempotencyService.executeOnce(anyString(), eq(CREATOR_USER_ID), anyString(), any()))
                .thenAnswer(
                        invocation -> {
                            Supplier<?> supplier = invocation.getArgument(3);
                            return supplier.get();
                        });
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

    private static PreferencesResponse preferences() {
        return new PreferencesResponse(
                null, null, null, null, List.of(), List.of(), 0, "en-IN", null, null, null, null, List.of(), null,
                false, null, true, null, false, null, false, 0, false);
    }

    /** Every DEBIT row for this creator, filtered to a single throw-site scenario's own turn ids, must have an exact matching REFUND row -- proving the compensating release actually ran, not merely that the balance happens to net out. */
    private void assertDebitsFullyRefunded() {
        List<CreatorCreditLedgerEntry> all = ledgerRepository.findAll();
        long debitCount =
                all.stream()
                        .filter(
                                e ->
                                        e.getReason() == CreditLedgerReason.DEBIT_TURN
                                                || e.getReason() == CreditLedgerReason.DEBIT_VOICE)
                        .count();
        long refundCount = all.stream().filter(e -> e.getReason() == CreditLedgerReason.REFUND).count();
        assertEquals(
                debitCount,
                refundCount,
                "every DEBIT row from a failed post-charge turn must have exactly one matching REFUND row");
    }

    /**
     * A12: {@code messageRepository.save} throwing (the FIRST statement after the charge) must
     * release the charge -- exception propagates, balance and daily_used are back to their
     * pre-charge values, and every DEBIT row this scenario wrote has a matching REFUND.
     */
    @Test
    @DisplayName(
            "A12: creator sendTurn where messageRepository.save / onBehalfTokenService.mint /"
                    + " streamTokenService.mint throws -> exception, balance unchanged, a REFUND row"
                    + " matches every DEBIT")
    void releasesOnEveryPostChargeThrowSite() {
        messageRepositorySaveThrowsReleasesCharge();
        streamTokenMintThrowsReleasesCharge();
        onBehalfTokenMintThrowsReleasesCharge();
    }

    private void messageRepositorySaveThrowsReleasesCharge() {
        resetTablesAndSeed(5);
        AiMessageRepository messageRepository = mock(AiMessageRepository.class);
        when(messageRepository.save(any(AiMessage.class))).thenThrow(new RuntimeException("db blip on USER save"));
        MeeraSessionService service =
                buildService(messageRepository, mock(StreamTokenService.class), mock(OnBehalfTokenService.class));

        assertThrows(
                RuntimeException.class,
                () ->
                        service.sendTurn(
                                CREATOR_USER_ID,
                                CREATOR_USER_ID,
                                UserType.CREATOR,
                                CONVERSATION_ID,
                                "hi",
                                "idem-save-throws"));

        assertEquals(5, totalRemaining(), "a charge that never got past messageRepository.save must be fully released");
        assertEquals(0, accountRepository.findById(CREATOR_USER_ID).orElseThrow().getDailyUsed());
        assertDebitsFullyRefunded();
    }

    private void streamTokenMintThrowsReleasesCharge() {
        resetTablesAndSeed(5);
        AiMessageRepository messageRepository = mock(AiMessageRepository.class);
        when(messageRepository.save(any(AiMessage.class))).thenAnswer(inv -> inv.getArgument(0));
        StreamTokenService streamTokenService = mock(StreamTokenService.class);
        when(streamTokenService.mint(anyString(), anyString(), anyString(), anyString(), eq(UserType.CREATOR)))
                .thenThrow(new RuntimeException("stream token signing key unavailable"));
        MeeraSessionService service = buildService(messageRepository, streamTokenService, mock(OnBehalfTokenService.class));

        assertThrows(
                RuntimeException.class,
                () ->
                        service.sendTurn(
                                CREATOR_USER_ID,
                                CREATOR_USER_ID,
                                UserType.CREATOR,
                                CONVERSATION_ID,
                                "hi",
                                "idem-stream-throws"));

        assertEquals(5, totalRemaining(), "a charge that never got past streamTokenService.mint must be fully released");
        assertEquals(0, accountRepository.findById(CREATOR_USER_ID).orElseThrow().getDailyUsed());
        assertDebitsFullyRefunded();
    }

    private void onBehalfTokenMintThrowsReleasesCharge() {
        resetTablesAndSeed(5);
        AiMessageRepository messageRepository = mock(AiMessageRepository.class);
        when(messageRepository.save(any(AiMessage.class))).thenAnswer(inv -> inv.getArgument(0));
        StreamTokenService streamTokenService = mock(StreamTokenService.class);
        when(streamTokenService.mint(anyString(), anyString(), anyString(), anyString(), eq(UserType.CREATOR)))
                .thenReturn("stream-token-ok");
        OnBehalfTokenService onBehalfTokenService = mock(OnBehalfTokenService.class);
        when(onBehalfTokenService.mint(anyString(), anyString(), anyString(), anyString(), eq(UserType.CREATOR), anyString()))
                .thenThrow(new RuntimeException("onbehalf signing failure"));
        MeeraSessionService service = buildService(messageRepository, streamTokenService, onBehalfTokenService);

        assertThrows(
                RuntimeException.class,
                () ->
                        service.sendTurn(
                                CREATOR_USER_ID,
                                CREATOR_USER_ID,
                                UserType.CREATOR,
                                CONVERSATION_ID,
                                "hi",
                                "idem-onbehalf-throws"));

        assertEquals(5, totalRemaining(), "a charge that never got past onBehalfTokenService.mint must be fully released");
        assertEquals(0, accountRepository.findById(CREATOR_USER_ID).orElseThrow().getDailyUsed());
        assertDebitsFullyRefunded();
    }

    private void resetTablesAndSeed(int credits) {
        ledgerRepository.deleteAll();
        grantRepository.deleteAll();
        welcomeClaimRepository.deleteAll();
        accountRepository.deleteAll();
        seedGrant(credits);
    }

    private MeeraSessionService buildService(
            AiMessageRepository messageRepository,
            StreamTokenService streamTokenService,
            OnBehalfTokenService onBehalfTokenService) {
        AiConversationRepository conversationRepository = mock(AiConversationRepository.class);
        when(conversationRepository.findByIdAndWorkspaceId(CONVERSATION_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(creatorConversation()));
        when(conversationRepository.save(any(AiConversation.class))).thenAnswer(inv -> inv.getArgument(0));

        return new MeeraSessionService(
                conversationRepository,
                messageRepository,
                mock(WorkspaceRepository.class),
                mock(BrandProfileRepository.class),
                mock(AICreditService.class),
                mock(BrandContextAssembler.class),
                streamTokenService,
                onBehalfTokenService,
                idempotencyService,
                mock(CreatorAgentConversationService.class),
                creatorAgentPreferencesService,
                creatorCreditService,
                mock(PlatformTransactionManager.class));
    }
}
