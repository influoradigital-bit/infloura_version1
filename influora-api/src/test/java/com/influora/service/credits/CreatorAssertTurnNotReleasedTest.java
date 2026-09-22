package com.influora.service.credits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.influora.common.ApiException;
import com.influora.config.CreatorCreditProperties;
import com.influora.domain.entity.CreatorCreditAccount;
import com.influora.domain.entity.CreatorCreditGrant;
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
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md §12, K-15) — A18/K-15 fix (review finding #2, round 2): proves the
 * REAL {@link CreatorCreditService#assertTurnNotReleased} and {@link
 * CreatorCreditService#markWritebackPersisted} bodies against a real H2 database, using the same
 * {@code @DataJpaTest} harness as {@link CreatorTurnReleaseRoutingTest}/{@link
 * CreatorCreditServiceTest} — not a mock.
 *
 * <p>Before this test existed, the ONLY test exercising {@code assertTurnNotReleased} (in {@code
 * CreatorWritebackAfterReleaseTest}) drove a fully-mocked {@link CreatorCreditService} with {@code
 * doThrow(...).when(creatorCreditService).assertTurnNotReleased(...)} — replacing the real method
 * body with a no-op left every existing test green, so the K-15 guard had zero real coverage.
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
                    + "jdbc:h2:mem:creator_assert_turn_not_released_test;DB_CLOSE_DELAY=-1;MODE=MySQL",
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
class CreatorAssertTurnNotReleasedTest {

    private static final String CREATOR_USER_ID = "01HCREATORUSER0000000A";
    private static final String TURN_ID = "turn-assert-not-released-1";

    @Autowired private CreatorCreditService creatorCreditService;
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

    private long refundRowCount(String turnId) {
        return ledgerRepository
                .findByCreatorUserIdAndReferenceIdIn(CREATOR_USER_ID, List.of(CreatorCreditService.turnRef(turnId)))
                .stream()
                .filter(e -> e.getReason() == CreditLedgerReason.REFUND)
                .count();
    }

    @Test
    @DisplayName(
            "K-15 (real, not mocked): charge a turn, release it, then assertTurnNotReleased throws a"
                    + " REAL 409 TURN_RELEASED against the real ledger -- neutering"
                    + " assertTurnNotReleased's body turns this RED")
    void assertTurnNotReleasedThrowsAfterRealRelease() {
        seedGrant(10);
        creatorCreditService.charge(CREATOR_USER_ID, ChargeKind.TURN, TURN_ID);
        assertEquals(9, totalRemaining(), "sanity: the charge actually debited a real grant");

        creatorCreditService.release(CREATOR_USER_ID, TURN_ID, ReleaseScope.TURN);
        assertEquals(10, totalRemaining(), "sanity: the release actually refunded the real grant");

        ApiException thrown =
                assertThrows(
                        ApiException.class,
                        () -> creatorCreditService.assertTurnNotReleased(CREATOR_USER_ID, TURN_ID));

        assertEquals("TURN_RELEASED", thrown.getCode());
        assertEquals(HttpStatus.CONFLICT, thrown.getStatus());
    }

    @Test
    @DisplayName(
            "K-15 (real, not mocked): a turn never released never throws from assertTurnNotReleased")
    void assertTurnNotReleasedIsSilentWhenNeverReleased() {
        seedGrant(10);
        creatorCreditService.charge(CREATOR_USER_ID, ChargeKind.TURN, TURN_ID);

        creatorCreditService.assertTurnNotReleased(CREATOR_USER_ID, TURN_ID);
    }

    @Test
    @DisplayName(
            "K-15 (round 2, real, not mocked): write-back marker present -> release() is a no-op --"
                    + " the balance stays charged and NO REFUND row is ever written")
    void releaseNoOpsWhenWritebackMarkerPresent() {
        seedGrant(10);
        creatorCreditService.charge(CREATOR_USER_ID, ChargeKind.TURN, TURN_ID);
        assertEquals(9, totalRemaining());

        // Simulates the write-back's own transaction: markWritebackPersisted runs under the SAME
        // account lock, atomically with the (not modeled here) ASSISTANT insert.
        creatorCreditService.markWritebackPersisted(CREATOR_USER_ID, TURN_ID);

        creatorCreditService.release(CREATOR_USER_ID, TURN_ID, ReleaseScope.TURN);

        assertEquals(
                9,
                totalRemaining(),
                "the turn must stay charged -- a write-back that already persisted must never be"
                        + " refunded (refund-and-keep-reply)");
        assertEquals(0, refundRowCount(TURN_ID), "no REFUND row must ever be written for this turn");
    }
}
