package com.influora.service.credits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.influora.config.CreatorCreditProperties;
import com.influora.domain.entity.CreatorCreditAccount;
import com.influora.domain.entity.CreatorCreditGrant;
import com.influora.domain.entity.CreatorCreditLedgerEntry;
import com.influora.domain.entity.CreatorProfile;
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
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md &sect;12, K-13) &mdash; A6/A7: proves {@code
 * CreatorCreditService#charge}'s lazy monthly materialisation rolls on the Asia/Kolkata calendar
 * month (2026-09-30T18:30:00Z == 2026-10-01T00:00:00 IST), grants the monthly 15 exactly once per
 * IST {@code YearMonth}, EXPIREs an unused September leftover rather than letting it stack with
 * October's fresh 15 (K-13's "not carried over" rule &mdash; SET, never ADD), and that {@link
 * CreatorCreditService#balance} is a pure projection that performs zero writes. Reuses the {@code
 * CreatorCreditServiceTest} H2 + {@code MutableClock} harness verbatim, same as {@link
 * CreatorCreditIstBoundaryTest} and {@link CreatorTurnReleaseRoutingTest}.
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
                    + "jdbc:h2:mem:creator_credit_monthly_grant_test;DB_CLOSE_DELAY=-1;MODE=MySQL",
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
class CreatorCreditMonthlyGrantTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String CREATOR = "01HCREATORUSER0000000A";

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
        // No Meta connection by default: grantWelcome()'s backstop (fired from every charge()) is
        // a guaranteed no-op unless a test wires a connection, exactly like CreatorCreditServiceTest.
        when(creatorProfileRepository.findByUserId(any())).thenReturn(Optional.empty());
    }

    private int totalRemaining() {
        return grantRepository.findSpendable(CREATOR, clock.instant()).stream()
                .mapToInt(CreatorCreditGrant::getCreditsRemaining)
                .sum();
    }

    /**
     * Directly seeds an account whose welcome grant landed in an EARLIER IST month than every
     * clock value this test uses (2026-08-01), so the monthly-15 eligibility check in {@code
     * materializeMonthly} (SPEC.md &sect;6 step 3: "welcome_granted_at falls in an earlier IST
     * month") is satisfied from the very first charge onward &mdash; without ever exercising
     * {@code grantWelcome} itself (which is covered separately by {@code CreatorWelcomeGrantTest}
     * / A23).
     */
    private void seedAccountWithEarlierWelcome() {
        CreatorCreditAccount account = CreatorCreditAccount.newAccount(CREATOR);
        account.markWelcomeGranted(Instant.parse("2026-08-01T10:00:00Z"));
        accountRepository.saveAndFlush(account);
    }

    /**
     * Maxes out today's (IST) daily cap so the NEXT {@code charge()} call is refused for
     * DAILY_CAP without touching any grant &mdash; exactly {@code CreatorCreditServiceTest}'s A8
     * technique for observing {@code materializeMonthly}'s side effects in isolation, since SPEC.md
     * &sect;5.1 steps 4-5 (day roll, monthly materialisation, welcome backstop) commit even when
     * the charge itself is refused.
     */
    private void maxOutDailyCapForToday() {
        CreatorCreditAccount account = accountRepository.findById(CREATOR).orElseThrow();
        account.rollDayIfNeeded(java.time.LocalDate.now(clock.withZone(IST)));
        account.addDailyUsed(30);
        accountRepository.saveAndFlush(account);
    }

    // ------------------------------------------------------------------
    // A6 — the month rolls at 2026-09-30T18:30:00Z; 15 granted once; September's unused leftover
    // is EXPIREd, never stacked with October's fresh 15.
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "A6: the month rolls at 2026-09-30T18:30:00Z; 15 is granted once; September's leftover"
                    + " is EXPIREd (balance 15, not 30)")
    void monthRollsAtIstMidnightNoCarryOver() {
        seedAccountWithEarlierWelcome();

        // September, well before the boundary: materialize September's 15 without spending it
        // (a refused, daily-cap-maxed charge still commits the lazy monthly grant -- A8's proven
        // mechanism), leaving the full, unused 15 sitting in a FREE_MONTHLY grant.
        clock.setInstant(Instant.parse("2026-09-15T10:00:00Z"));
        maxOutDailyCapForToday();
        ChargeResult septemberAttempt = creditService.charge(CREATOR, ChargeKind.TURN, "turn-sep");
        assertEquals(ChargeResult.Outcome.DAILY_CAP, septemberAttempt.outcome());
        assertEquals(15, totalRemaining(), "September's monthly 15 must have materialized, untouched");
        List<CreatorCreditLedgerEntry> septemberGrantRows =
                ledgerRepository.findByCreatorUserIdAndReferenceIdIn(
                        CREATOR, List.of(CreatorCreditService.monthlyRef("2026-09")));
        assertEquals(
                1,
                septemberGrantRows.stream().filter(e -> e.getReason() == CreditLedgerReason.GRANT_MONTHLY).count(),
                "exactly one September GRANT_MONTHLY row");

        // The last instant that is STILL IST September 30th (23:59:59 IST == 18:29:59Z) -- period
        // must stay 2026-09, no second September grant, balance unchanged at 15.
        clock.setInstant(Instant.parse("2026-09-30T18:29:59Z"));
        maxOutDailyCapForToday();
        creditService.charge(CREATOR, ChargeKind.TURN, "turn-sep-last-second");
        assertEquals(15, totalRemaining(), "still IST September -- no re-grant, no expiry yet");
        assertEquals(
                1,
                ledgerRepository
                        .findByCreatorUserIdAndReferenceIdIn(CREATOR, List.of(CreatorCreditService.monthlyRef("2026-09")))
                        .stream()
                        .filter(e -> e.getReason() == CreditLedgerReason.GRANT_MONTHLY)
                        .count(),
                "still exactly one September GRANT_MONTHLY row");

        // Exactly the boundary: 2026-09-30T18:30:00Z == 2026-10-01T00:00:00+05:30. September's
        // unused 15 must be EXPIREd (never carried over, K-13) and October's fresh 15 granted --
        // balance must land on 15, NOT 30 (the bug this test falsifies: ADD instead of SET/expire).
        clock.setInstant(Instant.parse("2026-09-30T18:30:00Z"));
        maxOutDailyCapForToday();
        creditService.charge(CREATOR, ChargeKind.TURN, "turn-oct-boundary");

        assertEquals(
                15,
                totalRemaining(),
                "the month rolled: October's fresh 15 must replace, not stack with, September's leftover");

        List<CreatorCreditLedgerEntry> septemberRowsAfterRoll =
                ledgerRepository.findByCreatorUserIdAndReferenceIdIn(
                        CREATOR, List.of(CreatorCreditService.monthlyRef("2026-09")));
        assertTrue(
                septemberRowsAfterRoll.stream().anyMatch(e -> e.getReason() == CreditLedgerReason.EXPIRE && e.getDelta() == -15),
                "September's unused 15 must have been written off with an EXPIRE(-15) row");

        List<CreatorCreditLedgerEntry> octoberRows =
                ledgerRepository.findByCreatorUserIdAndReferenceIdIn(
                        CREATOR, List.of(CreatorCreditService.monthlyRef("2026-10")));
        assertEquals(
                1,
                octoberRows.stream().filter(e -> e.getReason() == CreditLedgerReason.GRANT_MONTHLY).count(),
                "exactly one October GRANT_MONTHLY row");

        // "15 is granted once": a second charge in the same new IST period (October) must not
        // grant a second October row.
        clock.setInstant(Instant.parse("2026-09-30T18:31:00Z"));
        maxOutDailyCapForToday();
        creditService.charge(CREATOR, ChargeKind.TURN, "turn-oct-again");
        assertEquals(15, totalRemaining());
        assertEquals(
                1,
                ledgerRepository
                        .findByCreatorUserIdAndReferenceIdIn(CREATOR, List.of(CreatorCreditService.monthlyRef("2026-10")))
                        .stream()
                        .filter(e -> e.getReason() == CreditLedgerReason.GRANT_MONTHLY)
                        .count(),
                "October's 15 is granted exactly once per IST period, never twice");
    }

    // ------------------------------------------------------------------
    // A7 — balance() is a pure read: it projects pending welcome/monthly but performs zero writes.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A7: GET balance projects pending welcome/monthly and performs zero writes")
    void balanceProjectsWithoutWriting() {
        // Case 1: no account row has EVER been touched for this creator, but the creator is
        // eligible for the welcome 40 (connected Instagram). balance() must report the pending 40
        // WITHOUT creating an account row, a grant row, or a ledger row.
        CreatorProfile profile = testProfile("profile-a6a7");
        when(creatorProfileRepository.findByUserId(CREATOR)).thenReturn(Optional.of(profile));
        com.influora.domain.entity.MetaOAuthToken connectedToken =
                com.influora.domain.entity.MetaOAuthToken.builder()
                        .id("token-a6a7")
                        .creatorProfileId("profile-a6a7")
                        .igBusinessAccountId("ig-a6a7")
                        .encryptedAccessToken("enc")
                        .expiresAt(clock.instant().plusSeconds(3600))
                        .build();
        when(metaOAuthTokenRepository.findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse("profile-a6a7"))
                .thenReturn(Optional.of(connectedToken));

        BalanceView beforeAnyWrite = creditService.balance(CREATOR);

        assertEquals(0, beforeAnyWrite.total());
        assertTrue(beforeAnyWrite.welcomeEligible());
        assertFalse(beforeAnyWrite.welcomeGranted());
        assertEquals(40, beforeAnyWrite.pendingWelcome());
        assertEquals(0, beforeAnyWrite.pendingMonthly());
        assertEquals(0, accountRepository.count(), "balance() must never create the account row");
        assertEquals(0, grantRepository.count(), "balance() must never create a grant row");
        assertEquals(0, ledgerRepository.count(), "balance() must never write a ledger row");

        // Case 2: welcome already granted in an earlier IST month, monthly not yet materialised
        // this period -- balance() must project the pending 15 WITHOUT materialising it (no
        // GRANT_MONTHLY row, no change to monthly_period on the account row).
        seedAccountWithEarlierWelcome();
        BalanceView pendingMonthly = creditService.balance(CREATOR);

        assertFalse(pendingMonthly.monthlyGrantedThisPeriod());
        assertEquals(15, pendingMonthly.pendingMonthly());
        assertEquals(0, grantRepository.count(), "balance() must never materialise the monthly grant");
        assertEquals(0, ledgerRepository.count(), "balance() must never write a GRANT_MONTHLY row");
        CreatorCreditAccount reread = accountRepository.findById(CREATOR).orElseThrow();
        assertEquals(null, reread.getMonthlyPeriod(), "balance() must never set monthly_period on the account row");

        // Calling balance() again (repeatedly) is still a pure read.
        creditService.balance(CREATOR);
        creditService.balance(CREATOR);
        assertEquals(0, grantRepository.count());
        assertEquals(0, ledgerRepository.count());
    }

    /** Anonymous subclass, same technique as {@code CreatorCreditServiceTest#testProfile}. */
    private static CreatorProfile testProfile(String id) {
        return new CreatorProfile() {
            @Override
            public String getId() {
                return id;
            }
        };
    }
}
