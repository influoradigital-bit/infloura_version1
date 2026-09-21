package com.influora.service.credits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.influora.config.CreatorCreditProperties;
import com.influora.domain.entity.CreatorCreditAccount;
import com.influora.domain.entity.CreatorCreditGrant;
import com.influora.domain.entity.CreatorCreditLedgerEntry;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.MetaOAuthToken;
import com.influora.domain.enums.ChargeKind;
import com.influora.domain.enums.CreditLedgerReason;
import com.influora.repository.CreatorCreditAccountRepository;
import com.influora.repository.CreatorCreditGrantRepository;
import com.influora.repository.CreatorCreditLedgerRepository;
import com.influora.repository.CreatorCreditWelcomeClaimRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.service.IdempotencyService;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md §12, K-12) — A23: the welcome-40 grant is once per user AND once
 * per Instagram id, forever, and its backstop fires from {@code charge()}. Reuses the {@code
 * CreatorCreditServiceTest}/{@code CreatorCreditIstBoundaryTest} harness (real H2, mocked
 * profile/token/idempotency repositories).
 *
 * <p>Also proves review findings #12/#14/#15: an outer-transaction rollback AFTER the welcome
 * claim rows already committed (in {@link CreatorCreditWelcomeClaimWriter}'s {@code REQUIRES_NEW}
 * transaction) must not permanently strand the creator without their 40 credits on retry, and
 * {@code balance()}'s {@code welcomeEligible} projection must agree with what {@code
 * grantWelcome} will actually do for an IG id another creator already claimed.
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
            "spring.datasource.url=" + "jdbc:h2:mem:creator_welcome_grant_test;DB_CLOSE_DELAY=-1;MODE=MySQL",
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
class CreatorWelcomeGrantTest {

    private static final String CREATOR_A = "01HCREATORUSERAAAAAAAA";
    private static final String CREATOR_B = "01HCREATORUSERBBBBBBBB";
    private static final String IG_ID = "ig-shared-123";

    @Autowired private CreatorCreditService creditService;
    @Autowired private CreatorCreditAccountRepository accountRepository;
    @Autowired private CreatorCreditGrantRepository grantRepository;
    @Autowired private CreatorCreditLedgerRepository ledgerRepository;
    @Autowired private CreatorCreditWelcomeClaimRepository welcomeClaimRepository;
    @Autowired private CreatorCreditServiceTest.MutableClock clock;

    @MockBean private CreatorProfileRepository creatorProfileRepository;
    @MockBean private MetaOAuthTokenRepository metaOAuthTokenRepository;
    @MockBean private IdempotencyService idempotencyService;

    @BeforeEach
    void resetClockAndData() {
        ledgerRepository.deleteAll();
        grantRepository.deleteAll();
        welcomeClaimRepository.deleteAll();
        accountRepository.deleteAll();
        clock.setInstant(Instant.parse("2026-09-10T10:00:00Z"));
    }

    private int totalRemaining(String creatorUserId) {
        return grantRepository.findSpendable(creatorUserId, clock.instant()).stream()
                .mapToInt(CreatorCreditGrant::getCreditsRemaining)
                .sum();
    }

    private void wireConnectedToken(String creatorUserId, String profileId, String igId) {
        CreatorProfile profile = testProfile(profileId);
        when(creatorProfileRepository.findByUserId(creatorUserId)).thenReturn(Optional.of(profile));
        MetaOAuthToken token =
                MetaOAuthToken.builder()
                        .id("token-" + profileId)
                        .creatorProfileId(profileId)
                        .igBusinessAccountId(igId)
                        .encryptedAccessToken("enc")
                        .expiresAt(clock.instant().plusSeconds(3600))
                        .build();
        when(metaOAuthTokenRepository.findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(profileId))
                .thenReturn(Optional.of(token));
    }

    private static CreatorProfile testProfile(String id) {
        return new CreatorProfile() {
            @Override
            public String getId() {
                return id;
            }
        };
    }

    @Test
    @DisplayName("A23: connect gives 40 once; a second event for the same creator+IG is a no-op")
    void welcomeGrantedOnceOnFirstConnectEvent() {
        wireConnectedToken(CREATOR_A, "profile-a", IG_ID);

        creditService.grantWelcome(CREATOR_A);
        assertEquals(40, totalRemaining(CREATOR_A));

        creditService.grantWelcome(CREATOR_A); // simulates the event firing twice
        assertEquals(40, totalRemaining(CREATOR_A), "a repeated connect event must not re-grant");

        List<CreatorCreditLedgerEntry> grantRows =
                ledgerRepository.findByCreatorUserIdAndReferenceIdIn(CREATOR_A, List.of(CreatorCreditService.welcomeRef()));
        assertEquals(1, grantRows.size(), "exactly one GRANT_SIGNUP row, ever");
    }

    @Test
    @DisplayName("A23: disconnect + reconnect never re-arms the grant — the claim is permanent")
    void disconnectReconnectDoesNotReArmGrant() {
        wireConnectedToken(CREATOR_A, "profile-a", IG_ID);
        creditService.grantWelcome(CREATOR_A);
        assertEquals(40, totalRemaining(CREATOR_A));

        // Simulate disconnect: token lookup now returns empty.
        when(metaOAuthTokenRepository.findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse("profile-a"))
                .thenReturn(Optional.empty());
        creditService.grantWelcome(CREATOR_A);
        assertEquals(40, totalRemaining(CREATOR_A), "disconnect must not touch the balance");

        // Reconnect with the SAME IG id.
        wireConnectedToken(CREATOR_A, "profile-a", IG_ID);
        creditService.grantWelcome(CREATOR_A);
        assertEquals(40, totalRemaining(CREATOR_A), "reconnect must never re-grant — the claim is permanent");
    }

    @Test
    @DisplayName("A23: a second creator claiming the SAME Instagram id gets nothing")
    void secondUserSameIgIdGetsNothing() {
        wireConnectedToken(CREATOR_A, "profile-a", IG_ID);
        creditService.grantWelcome(CREATOR_A);
        assertEquals(40, totalRemaining(CREATOR_A));

        wireConnectedToken(CREATOR_B, "profile-b", IG_ID); // same ig-shared-123
        creditService.grantWelcome(CREATOR_B);
        assertEquals(0, totalRemaining(CREATOR_B), "the IG id is already claimed by a different creator");
        assertFalse(accountRepository.findById(CREATOR_B).map(a -> a.getWelcomeGrantedAt() != null).orElse(false));
    }

    @Test
    @DisplayName("A23: a null Instagram business account id grants nothing (C4)")
    void nullIgIdGrantsNothing() {
        CreatorProfile profile = testProfile("profile-c");
        when(creatorProfileRepository.findByUserId(CREATOR_A)).thenReturn(Optional.of(profile));
        MetaOAuthToken tokenNoIg =
                MetaOAuthToken.builder()
                        .id("token-c")
                        .creatorProfileId("profile-c")
                        .encryptedAccessToken("enc")
                        .expiresAt(clock.instant().plusSeconds(3600))
                        .build();
        when(metaOAuthTokenRepository.findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse("profile-c"))
                .thenReturn(Optional.of(tokenNoIg));

        creditService.grantWelcome(CREATOR_A);
        assertEquals(0, totalRemaining(CREATOR_A));
    }

    @Test
    @DisplayName("A23: a pre-flag creator (never charged before) gets the welcome 40 via the charge() backstop")
    void backstopGrantsWelcomeOnFirstCharge() {
        wireConnectedToken(CREATOR_A, "profile-a", IG_ID);
        seedPaidNothing(); // no seeded balance at all -- the welcome grant IS the balance

        ChargeResult result = creditService.charge(CREATOR_A, ChargeKind.TURN, "turn-1");
        assertTrue(result.charged(), "the backstop must grant 40 and then charge 1 from it");
        assertEquals(39, totalRemaining(CREATOR_A));
    }

    private void seedPaidNothing() {
        // no-op — documents intent that this test relies solely on the welcome backstop.
    }

    @Test
    @DisplayName(
            "Review findings #12/#14: an outer-transaction rollback AFTER the welcome claim rows"
                    + " already committed must not permanently strand the creator's 40 credits")
    void claimSurvivingAnOuterRollbackIsRetriedSuccessfully() {
        wireConnectedToken(CREATOR_A, "profile-a", IG_ID);

        // Simulate exactly the CreatorCreditOrderService#confirmPaid scenario from findings #12/#14:
        // insertClaims commits its own REQUIRES_NEW transaction (the permanent claim rows), but the
        // grant itself never lands because the OUTER transaction rolled back for an unrelated
        // reason. We reproduce that end-state directly against the real writer bean.
        CreatorCreditWelcomeClaimWriter writer =
                new CreatorCreditWelcomeClaimWriter(welcomeClaimRepository);
        boolean claimedFirstAttempt = writer.insertClaims(CREATOR_A, IG_ID, null);
        assertTrue(claimedFirstAttempt, "the first attempt must insert the claim rows fresh");
        assertTrue(welcomeClaimRepository.existsByCreatorUserId(CREATOR_A));
        // No account row, no grant, no ledger row exists yet -- exactly what an outer rollback
        // after insertClaims leaves behind.
        assertEquals(0, totalRemaining(CREATOR_A));

        // Retry — the SAME creator's grantWelcome must now succeed instead of permanently refusing
        // because "a claim already exists".
        creditService.grantWelcome(CREATOR_A);
        assertEquals(40, totalRemaining(CREATOR_A), "the retry must grant the 40 credits this creator never received");

        List<CreatorCreditLedgerEntry> grantRows =
                ledgerRepository.findByCreatorUserIdAndReferenceIdIn(CREATOR_A, List.of(CreatorCreditService.welcomeRef()));
        assertEquals(1, grantRows.size());
        assertEquals(CreditLedgerReason.GRANT_SIGNUP, grantRows.get(0).getReason());
    }

    @Test
    @DisplayName(
            "A23: welcome once per user AND per Instagram id, forever — connect/reconnect, a"
                    + " cross-user IG collision, a null IG id, the pre-flag charge() backstop, and no"
                    + " monthly-15 in the welcome month itself (the NEXT IST month materialises it)")
    void welcomeOncePerUserAndIgForever() {
        wireConnectedToken(CREATOR_A, "profile-a", IG_ID);

        // Connect gives 40 once; a second event for the same creator+IG is a no-op.
        creditService.grantWelcome(CREATOR_A);
        assertEquals(40, totalRemaining(CREATOR_A));
        creditService.grantWelcome(CREATOR_A);
        assertEquals(40, totalRemaining(CREATOR_A), "a repeated connect event must not re-grant");

        // Disconnect + reconnect never re-arms it — the claim is permanent.
        when(metaOAuthTokenRepository.findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse("profile-a"))
                .thenReturn(Optional.empty());
        creditService.grantWelcome(CREATOR_A);
        assertEquals(40, totalRemaining(CREATOR_A), "disconnect must not touch the balance");
        wireConnectedToken(CREATOR_A, "profile-a", IG_ID);
        creditService.grantWelcome(CREATOR_A);
        assertEquals(40, totalRemaining(CREATOR_A), "reconnect must never re-grant — the claim is permanent");

        // A second creator claiming the SAME Instagram id gets nothing.
        wireConnectedToken(CREATOR_B, "profile-b", IG_ID);
        creditService.grantWelcome(CREATOR_B);
        assertEquals(0, totalRemaining(CREATOR_B), "the IG id is already claimed by a different creator");

        // A null Instagram business account id grants nothing (C4).
        String creatorC = "01HCREATORUSERCCCCCCC";
        CreatorProfile profileC = testProfile("profile-c-null-ig");
        when(creatorProfileRepository.findByUserId(creatorC)).thenReturn(Optional.of(profileC));
        MetaOAuthToken tokenNoIg =
                MetaOAuthToken.builder()
                        .id("token-c-null-ig")
                        .creatorProfileId("profile-c-null-ig")
                        .encryptedAccessToken("enc")
                        .expiresAt(clock.instant().plusSeconds(3600))
                        .build();
        when(metaOAuthTokenRepository.findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(
                        "profile-c-null-ig"))
                .thenReturn(Optional.of(tokenNoIg));
        creditService.grantWelcome(creatorC);
        assertEquals(0, totalRemaining(creatorC), "a null Instagram id must never grant");

        // A pre-flag creator (never explicitly granted) gets the welcome 40 via charge()'s backstop.
        String creatorD = "01HCREATORUSERDDDDDDD";
        wireConnectedToken(creatorD, "profile-d-backstop", "ig-d-backstop");
        ChargeResult backstop = creditService.charge(creatorD, ChargeKind.TURN, "turn-backstop-1");
        assertTrue(backstop.charged(), "the backstop must grant 40 and then charge 1 from it");
        assertEquals(39, totalRemaining(creatorD));

        // The welcome MONTH itself gets no monthly-15 — CREATOR_A was welcomed in September
        // (clock still 2026-09-10) — a charge() in the SAME IST month must not materialise it.
        ChargeResult stillSeptember = creditService.charge(CREATOR_A, ChargeKind.TURN, "turn-sept-1");
        assertTrue(stillSeptember.charged());
        assertEquals(
                39,
                totalRemaining(CREATOR_A),
                "40 - 1 turn, no monthly-15 yet -- still the same IST month as the welcome grant");

        // The NEXT IST month materialises the 15 the first time charge() runs in it (Q4).
        clock.setInstant(Instant.parse("2026-10-02T10:00:00Z")); // IST = UTC+5:30 -> October
        ChargeResult october = creditService.charge(CREATOR_A, ChargeKind.TURN, "turn-oct-1");
        assertTrue(october.charged());
        assertEquals(
                53,
                totalRemaining(CREATOR_A),
                "39 + 15 monthly - 1 turn = 53, the next IST month's 15 lands");

        List<CreatorCreditLedgerEntry> monthlyGrantRows =
                ledgerRepository.findByCreatorUserIdAndReferenceIdIn(
                        CREATOR_A, List.of(CreatorCreditService.monthlyRef("2026-10")));
        assertEquals(1, monthlyGrantRows.size(), "exactly one GRANT_MONTHLY row for 2026-10");
        assertEquals(CreditLedgerReason.GRANT_MONTHLY, monthlyGrantRows.get(0).getReason());
    }

    @Test
    @DisplayName("Review finding #15: balance() never projects a pending 40 for an IG id another creator already claimed")
    void balanceProjectionAgreesWithClaimOwnership() {
        wireConnectedToken(CREATOR_A, "profile-a", IG_ID);
        creditService.grantWelcome(CREATOR_A);

        wireConnectedToken(CREATOR_B, "profile-b", IG_ID);
        BalanceView balance = creditService.balance(CREATOR_B);
        assertFalse(balance.welcomeEligible(), "an IG id already claimed by a different creator must never show as pending");
        assertEquals(0, balance.pendingWelcome());
    }
}
