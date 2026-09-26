package com.influora.service.credits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.config.CreatorCreditProperties;
import com.influora.domain.entity.CreatorCreditAccount;
import com.influora.domain.entity.CreatorCreditGrant;
import com.influora.domain.entity.CreatorCreditLedgerEntry;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.MetaOAuthToken;
import com.influora.domain.enums.ChargeKind;
import com.influora.domain.enums.CreditBucket;
import com.influora.domain.enums.CreditLedgerReason;
import com.influora.repository.CreatorCreditAccountRepository;
import com.influora.repository.CreatorCreditGrantRepository;
import com.influora.repository.CreatorCreditLedgerRepository;
import com.influora.repository.CreatorCreditWelcomeClaimRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.service.IdempotencyService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
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
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md §12) — real Hibernate + H2 proof of the core charge/release money
 * math (A2, A3, A8, A9, A23), not a Mockito approximation of it. See {@code
 * AdminEmailSendLockRepositoryConcurrencyTest} for why this codebase prefers a real, scoped {@code
 * @DataJpaTest} over mocked repositories for exactly this class of correctness question.
 *
 * <p>{@code CreatorProfileRepository}/{@code MetaOAuthTokenRepository}/{@code IdempotencyService}
 * are mocked — they cross into unrelated domains (creator profiles, Meta OAuth, the shared
 * idempotency ledger) that this class does not own and does not need real JPA behaviour from.
 * Every credit repository is real.
 *
 * <p><b>Review finding #5/#9/#17 fix:</b> A4/A5 (the Asia/Kolkata day-boundary tests) used to live
 * here too, with real assertions, but under this class's name rather than the exact
 * {@code CreatorCreditIstBoundaryTest#dailyCapUsesAsiaKolkataDay}/{@code #releaseOnlyRefundsChargeDay}
 * the spec §12 acceptance table names — a name mismatch a by-name test runner cannot see past. They
 * now live in {@link CreatorCreditIstBoundaryTest}, which reuses this same {@code @DataJpaTest} +
 * {@code MutableClock} harness verbatim.
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
                    + "jdbc:h2:mem:creator_credit_service_test;DB_CLOSE_DELAY=-1;MODE=MySQL",
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
class CreatorCreditServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String CREATOR = "01HCREATORUSER0000000A";

    @Autowired private CreatorCreditService creditService;
    @Autowired private CreatorCreditAccountRepository accountRepository;
    @Autowired private CreatorCreditGrantRepository grantRepository;
    @Autowired private CreatorCreditLedgerRepository ledgerRepository;
    @Autowired private CreatorCreditWelcomeClaimRepository welcomeClaimRepository;
    @Autowired private MutableClock clock;

    @MockBean private CreatorProfileRepository creatorProfileRepository;
    @MockBean private MetaOAuthTokenRepository metaOAuthTokenRepository;
    @MockBean private IdempotencyService idempotencyService;

    @BeforeEach
    void resetClockAndData() {
        // T-CREATOR-CREDITS-V2 test note: @Transactional(propagation = NOT_SUPPORTED) at the class
        // level (see class javadoc / AdminEmailSendLockRepositoryConcurrencyTest precedent) means
        // each test's writes are REAL, committed transactions against the same H2 database, not
        // rolled back by @DataJpaTest's usual per-test wrapper. Every table is wiped before each
        // test so the shared CREATOR id starts from zero every time.
        ledgerRepository.deleteAll();
        grantRepository.deleteAll();
        welcomeClaimRepository.deleteAll();
        accountRepository.deleteAll();

        clock.setInstant(Instant.parse("2026-09-10T10:00:00Z"));
        // No Meta connection by default -- grantWelcome()'s backstop (called from every charge())
        // is then a guaranteed no-op unless a test explicitly wires a connection.
        when(creatorProfileRepository.findByUserId(any())).thenReturn(Optional.empty());
    }

    /** Directly seeds a grant + its parent account row, bypassing the service (so tests can set up an exact starting balance). */
    private CreatorCreditGrant seedGrant(
            CreditBucket bucket, int credits, Instant grantedAt, Instant expiresAt, String sourceRef) {
        accountRepository
                .findById(CREATOR)
                .orElseGet(() -> accountRepository.saveAndFlush(CreatorCreditAccount.newAccount(CREATOR)));
        CreatorCreditGrant grant =
                CreatorCreditGrant.of(
                        com.influora.common.Ulids.newUlid(),
                        CREATOR,
                        bucket,
                        credits,
                        grantedAt,
                        expiresAt,
                        sourceRef);
        return grantRepository.saveAndFlush(grant);
    }

    private int totalRemaining() {
        return grantRepository.findSpendable(CREATOR, clock.instant()).stream()
                .mapToInt(CreatorCreditGrant::getCreditsRemaining)
                .sum();
    }

    // ------------------------------------------------------------------
    // A2 — multi-grant debit, release restores exactly those grants
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A2: a 3-credit brief spanning two grants writes two DEBIT rows; release restores exactly those grants")
    void multiGrantDebitRefundsToSameGrants() {
        CreatorCreditGrant soonExpiring =
                seedGrant(CreditBucket.PAID, 2, clock.instant(), clock.instant().plusSeconds(3600), "order:a");
        CreatorCreditGrant laterExpiring =
                seedGrant(CreditBucket.PAID, 8, clock.instant(), clock.instant().plusSeconds(7200), "order:b");

        ChargeResult result = creditService.charge(CREATOR, ChargeKind.BRIEF, "brief-1");
        assertTrue(result.charged(), "expected a successful charge, got " + result.outcome());
        assertEquals(7, totalRemaining()); // 10 - 3

        CreatorCreditGrant soonAfter = grantRepository.findById(soonExpiring.getId()).orElseThrow();
        CreatorCreditGrant laterAfter = grantRepository.findById(laterExpiring.getId()).orElseThrow();
        assertEquals(0, soonAfter.getCreditsRemaining(), "the soonest-expiring grant should be exhausted first");
        assertEquals(7, laterAfter.getCreditsRemaining());

        List<CreatorCreditLedgerEntry> debitRows =
                ledgerRepository.findByCreatorUserIdAndReferenceIdIn(
                        CREATOR, List.of(CreatorCreditService.briefRef("brief-1")));
        assertEquals(2, debitRows.size(), "expected exactly two DEBIT_BRIEF rows (one per touched grant)");

        creditService.release(CREATOR, "brief-1", ReleaseScope.BRIEF);

        assertEquals(10, totalRemaining(), "release must restore the full 3 credits");
        CreatorCreditGrant soonRestored = grantRepository.findById(soonExpiring.getId()).orElseThrow();
        CreatorCreditGrant laterRestored = grantRepository.findById(laterExpiring.getId()).orElseThrow();
        assertEquals(2, soonRestored.getCreditsRemaining(), "restored to the SAME grant it was debited from");
        assertEquals(8, laterRestored.getCreditsRemaining(), "restored to the SAME grant it was debited from");
    }

    // ------------------------------------------------------------------
    // A3 — an expiring grant is spendable right up to, never at, its expiry
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A3: a debit at T-1s from a grant expiring at T succeeds; at T it is not spendable")
    void expiredGrantNotSpendable() {
        Instant expiryT = clock.instant().plusSeconds(100);
        seedGrant(CreditBucket.PAID, 5, clock.instant(), expiryT, "order:expiring");

        clock.setInstant(expiryT.minusSeconds(1));
        ChargeResult beforeExpiry = creditService.charge(CREATOR, ChargeKind.TURN, "turn-before");
        assertTrue(beforeExpiry.charged(), "a charge 1s before expiry must succeed");

        clock.setInstant(expiryT);
        ChargeResult atExpiry = creditService.charge(CREATOR, ChargeKind.TURN, "turn-at-expiry");
        assertTrue(atExpiry.refused(), "a charge exactly at expiry must be refused (INSUFFICIENT)");
        assertEquals(ChargeResult.Outcome.INSUFFICIENT, atExpiry.outcome());
    }

    // ------------------------------------------------------------------
    // A4/A5 moved to CreatorCreditIstBoundaryTest (finding #5/#9/#17) — see this class's javadoc.
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // A8 — a refused charge still commits the lazily materialised monthly grant
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A8: a refused charge (daily cap) still commits the lazily materialised monthly grant")
    void refusedChargeKeepsMonthlyGrant() {
        // Welcome granted in an earlier IST month -> this IST month is due its 15.
        Instant welcomeGrantedAt = Instant.parse("2026-08-01T10:00:00Z");
        CreatorCreditAccount account = CreatorCreditAccount.newAccount(CREATOR);
        account.markWelcomeGranted(welcomeGrantedAt);
        accountRepository.saveAndFlush(account);

        clock.setInstant(Instant.parse("2026-09-10T10:00:00Z"));
        // Already at the daily cap before this charge is even attempted.
        CreatorCreditAccount reloaded = accountRepository.findById(CREATOR).orElseThrow();
        reloaded.rollDayIfNeeded(java.time.LocalDate.now(clock.withZone(IST)));
        reloaded.addDailyUsed(30);
        accountRepository.saveAndFlush(reloaded);

        ChargeResult result = creditService.charge(CREATOR, ChargeKind.TURN, "turn-refused");
        assertEquals(ChargeResult.Outcome.DAILY_CAP, result.outcome());

        List<CreatorCreditLedgerEntry> monthlyGrantRows =
                ledgerRepository.findByCreatorUserIdAndReferenceIdIn(
                        CREATOR, List.of(CreatorCreditService.monthlyRef("2026-09")));
        assertEquals(
                1,
                monthlyGrantRows.stream().filter(e -> e.getReason() == CreditLedgerReason.GRANT_MONTHLY).count(),
                "the monthly 15 must still have been granted even though the charge itself was refused");
    }

    // ------------------------------------------------------------------
    // A9 — a voice turn at daily_used=29 is refused whole
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A9: at daily_used=29 a 2-credit voice turn is refused whole, balance and daily_used unchanged")
    void voiceTurnAt29RefusedWhole() {
        seedGrant(CreditBucket.PAID, 100, clock.instant(), null, "order:big");
        CreatorCreditAccount account = accountRepository.findById(CREATOR).orElseThrow();
        account.rollDayIfNeeded(java.time.LocalDate.now(clock.withZone(IST)));
        account.addDailyUsed(29);
        accountRepository.saveAndFlush(account);

        ChargeResult result = creditService.charge(CREATOR, ChargeKind.VOICE_TURN, "voice-turn-1");

        assertEquals(ChargeResult.Outcome.DAILY_CAP, result.outcome());
        assertEquals(100, totalRemaining(), "a refused charge must never touch the balance");
        CreatorCreditAccount after = accountRepository.findById(CREATOR).orElseThrow();
        assertEquals(29, after.getDailyUsed(), "a refused charge must never touch daily_used");
    }

    // ------------------------------------------------------------------
    // A23 (partial — see CreatorCreditWelcomeGrantTest note) — once per user AND per IG id, forever
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A23: connect grants 40 once; a second call for the same creator is a no-op; a null IG id grants nothing")
    void welcomeOncePerUserAndIgForever() {
        CreatorProfile profile = testProfile("profile-1");
        when(creatorProfileRepository.findByUserId(CREATOR)).thenReturn(Optional.of(profile));

        MetaOAuthToken connectedToken =
                MetaOAuthToken.builder()
                        .id("token-1")
                        .creatorProfileId("profile-1")
                        .igBusinessAccountId("ig-123")
                        .encryptedAccessToken("enc")
                        .expiresAt(clock.instant().plusSeconds(3600))
                        .build();
        when(metaOAuthTokenRepository.findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse("profile-1"))
                .thenReturn(Optional.of(connectedToken));

        creditService.grantWelcome(CREATOR);
        assertEquals(40, totalRemaining());
        assertTrue(welcomeClaimRepository.existsByCreatorUserId(CREATOR));

        // A second call (e.g. a reconnect, or the charge() backstop firing again) must not re-grant.
        creditService.grantWelcome(CREATOR);
        assertEquals(40, totalRemaining(), "a second grantWelcome call must be a no-op");

        List<CreatorCreditLedgerEntry> grantRows =
                ledgerRepository.findByCreatorUserIdAndReferenceIdIn(
                        CREATOR, List.of(CreatorCreditService.welcomeRef()));
        assertEquals(1, grantRows.size(), "exactly one GRANT_SIGNUP row, ever");
    }

    @Test
    @DisplayName("A23: a null/blank Instagram business account id grants nothing (C4)")
    void nullIgIdGrantsNothing() {
        CreatorProfile profile = testProfile("profile-2");
        when(creatorProfileRepository.findByUserId(CREATOR)).thenReturn(Optional.of(profile));
        MetaOAuthToken tokenNoIg =
                MetaOAuthToken.builder()
                        .id("token-2")
                        .creatorProfileId("profile-2")
                        .encryptedAccessToken("enc")
                        .expiresAt(clock.instant().plusSeconds(3600))
                        .build();
        when(metaOAuthTokenRepository.findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse("profile-2"))
                .thenReturn(Optional.of(tokenNoIg));

        creditService.grantWelcome(CREATOR);

        assertEquals(0, totalRemaining());
        assertFalse(accountRepository.findById(CREATOR).map(a -> a.getWelcomeGrantedAt() != null).orElse(false));
    }

    /** Anonymous subclass, same technique as {@code PlatformStatsAggregationJobTest#testProfile}. */
    private static CreatorProfile testProfile(String id) {
        return new CreatorProfile() {
            @Override
            public String getId() {
                return id;
            }
        };
    }

    // ------------------------------------------------------------------
    // refusal() template
    // ------------------------------------------------------------------

    @Test
    @DisplayName("refusal(): DAILY_CAP maps to 429 CREATOR_DAILY_CAP_REACHED, INSUFFICIENT to 402 CREATOR_CREDITS_EXHAUSTED")
    void refusalMapsOutcomesToCodes() {
        ChargeResult cap = ChargeResult.dailyCap(ChargeKind.TURN, 1, 5, 30);
        ApiException capEx = CreatorCreditService.refusal(cap, "en-US");
        assertEquals("CREATOR_DAILY_CAP_REACHED", capEx.getCode());
        assertEquals(429, capEx.getStatus().value());

        ChargeResult insufficient = ChargeResult.insufficient(ChargeKind.TURN, 1, 0, 3);
        ApiException exhaustedEx = CreatorCreditService.refusal(insufficient, "en-US");
        assertEquals("CREATOR_CREDITS_EXHAUSTED", exhaustedEx.getCode());
        assertEquals(402, exhaustedEx.getStatus().value());

        ApiException hindiEx = CreatorCreditService.refusal(insufficient, "hi-IN");
        assertTrue(hindiEx.getMessage().contains("क्रेडिट्स"), "hi-IN must render the Hindi template");
    }

    /** A settable {@link Clock} — see {@code ClockTestConfig}. */
    static final class MutableClock extends Clock {
        private volatile Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @Configuration
    static class ClockTestConfig {
        @org.springframework.context.annotation.Bean
        MutableClock clock() {
            return new MutableClock(Instant.parse("2026-09-10T10:00:00Z"), ZoneId.of("UTC"));
        }
    }
}
