package com.influora.service.credits;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.influora.config.CreatorCreditProperties;
import com.influora.domain.entity.CreatorCreditAccount;
import com.influora.domain.entity.CreatorCreditGrant;
import com.influora.domain.enums.ChargeKind;
import com.influora.domain.enums.CreditBucket;
import com.influora.repository.CreatorCreditAccountRepository;
import com.influora.repository.CreatorCreditGrantRepository;
import com.influora.repository.CreatorCreditLedgerRepository;
import com.influora.repository.CreatorCreditWelcomeClaimRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.service.IdempotencyService;
import java.time.Instant;
import java.time.ZoneId;
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
 * T-CREATOR-CREDITS-V2 (SPEC.md §12, K-14) — A4/A5: proves {@code charge}/{@code release} roll the
 * daily cap on the Asia/Kolkata calendar day (00:00 IST == 18:30 UTC), not the UTC day. Split out of
 * {@link CreatorCreditServiceTest} (review finding #5/#9/#17) purely to match the spec §12
 * acceptance table's exact class name — the two {@code @Test} methods themselves are unchanged
 * (they were already real, passing assertions, never stubs), and this class reuses that class's
 * {@code MutableClock}/{@code ClockTestConfig} test harness verbatim rather than duplicating it.
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
                    + "jdbc:h2:mem:creator_credit_ist_boundary_test;DB_CLOSE_DELAY=-1;MODE=MySQL",
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
class CreatorCreditIstBoundaryTest {

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
    }

    private CreatorCreditGrant seedGrant(int credits) {
        accountRepository
                .findById(CREATOR)
                .orElseGet(() -> accountRepository.saveAndFlush(CreatorCreditAccount.newAccount(CREATOR)));
        return grantRepository.saveAndFlush(
                CreatorCreditGrant.of(
                        com.influora.common.Ulids.newUlid(), CREATOR, CreditBucket.PAID, credits, clock.instant(), null, "order:big"));
    }

    @Test
    @DisplayName("A4: a charge at IST 23:59:59 and one at 00:00:00 IST count on different days")
    void dailyCapUsesAsiaKolkataDay() {
        seedGrant(100);

        // 2026-09-10T23:59:59+05:30 == 2026-09-10T18:29:59Z
        clock.setInstant(Instant.parse("2026-09-10T18:29:59Z"));
        creditService.charge(CREATOR, ChargeKind.TURN, "turn-late-night");
        CreatorCreditAccount afterFirst = accountRepository.findById(CREATOR).orElseThrow();
        assertEquals(1, afterFirst.getDailyUsed());

        // 2026-09-11T00:00:00+05:30 == 2026-09-10T18:30:00Z -- one second later, new IST day.
        clock.setInstant(Instant.parse("2026-09-10T18:30:00Z"));
        creditService.charge(CREATOR, ChargeKind.TURN, "turn-just-after-midnight");
        CreatorCreditAccount afterSecond = accountRepository.findById(CREATOR).orElseThrow();
        assertEquals(1, afterSecond.getDailyUsed(), "the counter must have rolled to a fresh day, not accumulated to 2");
    }

    @Test
    @DisplayName("A5: a release after midnight does not decrement the new day's daily_used")
    void releaseOnlyRefundsChargeDay() {
        seedGrant(100);

        clock.setInstant(Instant.parse("2026-09-10T12:00:00Z")); // day D
        creditService.charge(CREATOR, ChargeKind.TURN, "turn-day-d");

        clock.setInstant(Instant.parse("2026-09-11T12:00:00Z")); // day D+1 -- a new charge rolls the account
        creditService.charge(CREATOR, ChargeKind.TURN, "turn-day-d-plus-1");
        CreatorCreditAccount beforeRelease = accountRepository.findById(CREATOR).orElseThrow();
        assertEquals(1, beforeRelease.getDailyUsed(), "day D+1 has its own fresh counter");

        creditService.release(CREATOR, "turn-day-d", ReleaseScope.TURN);

        CreatorCreditAccount afterRelease = accountRepository.findById(CREATOR).orElseThrow();
        assertEquals(
                1,
                afterRelease.getDailyUsed(),
                "releasing day D's charge must never touch day D+1's daily_used counter");
    }
}
