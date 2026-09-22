package com.influora.service.credits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import com.influora.repository.CreatorVoiceSpeakRepository;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md §12, K-15) — review finding #3 (round 2): proves {@link
 * CreatorCreditService#releaseVoiceIfUndelivered}/{@link CreatorCreditService#markVoiceDelivered}
 * against a REAL H2 database (same harness as {@link CreatorTurnReleaseRoutingTest}), not mocks —
 * specifically the interleaved-claims race the old process-local {@code deliveredVoiceTurns} map
 * in {@code CreatorMeeraController} could not close: two parallel {@code speak} calls for the SAME
 * paid turn, one slow-and-succeeding, one fast-and-failing.
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
            "spring.datasource.url=" + "jdbc:h2:mem:creator_voice_refund_race_test;DB_CLOSE_DELAY=-1;MODE=MySQL",
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
class CreatorVoiceRefundRaceTest {

    private static final String CREATOR_USER_ID = "01HCREATORUSER0000000A";
    private static final String TURN_ID = "turn-voice-race-1";

    @Autowired private CreatorCreditService creatorCreditService;
    @Autowired private CreatorCreditAccountRepository accountRepository;
    @Autowired private CreatorCreditGrantRepository grantRepository;
    @Autowired private CreatorCreditLedgerRepository ledgerRepository;
    @Autowired private CreatorCreditWelcomeClaimRepository welcomeClaimRepository;
    @Autowired private CreatorVoiceSpeakRepository voiceSpeakRepository;
    @Autowired private CreatorCreditServiceTest.MutableClock clock;

    @MockBean private CreatorProfileRepository creatorProfileRepository;
    @MockBean private MetaOAuthTokenRepository metaOAuthTokenRepository;
    @MockBean private IdempotencyService idempotencyService;

    @BeforeEach
    void resetClockAndData() {
        voiceSpeakRepository.deleteAll();
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

    private long refundRowCount() {
        return ledgerRepository
                .findByCreatorUserIdAndReferenceIdIn(CREATOR_USER_ID, List.of(CreatorCreditService.ttsRef(TURN_ID)))
                .stream()
                .filter(e -> e.getReason() == CreditLedgerReason.REFUND)
                .count();
    }

    /** Charges both the TURN and VOICE_TURN legs, like a real paid voice send. */
    private void chargeVoiceTurn() {
        creatorCreditService.charge(CREATOR_USER_ID, ChargeKind.VOICE_TURN, TURN_ID);
    }

    @Test
    @DisplayName(
            "K-15 round 2: two claims already recorded (both A and B claimed BEFORE either resolves,"
                    + " modeling A being slow and B being fast) -- B's failure must NOT refund the tts:"
                    + " surcharge even though A has not delivered yet; A's later success then correctly"
                    + " marks the turn delivered with nothing left to refund")
    void interleavedClaimsFastFailureDoesNotRefundWhileSlowClaimStillInFlight() {
        seedGrant(10);
        chargeVoiceTurn();
        int afterCharge = totalRemaining();

        // Both A and B claim before either's Sarvam call resolves -- this is the realistic ordering
        // (claimVoiceSpeak is a fast, non-networked DB increment; the Sarvam call is what's slow),
        // and exactly the state CreatorMeeraController#speak would have produced for two concurrent
        // requests by the time either one reaches its refund/delivery decision.
        boolean claimA = creatorCreditService.claimVoiceSpeak(CREATOR_USER_ID, TURN_ID);
        boolean claimB = creatorCreditService.claimVoiceSpeak(CREATOR_USER_ID, TURN_ID);
        assertTrue(claimA);
        assertTrue(claimB);
        assertEquals(2, voiceSpeakRepository.findByIdCreatorUserIdAndIdTurnId(CREATOR_USER_ID, TURN_ID).orElseThrow().getSpeakCount());

        // B is FAST and fails first, while A is still "in flight" (has claimed, has not yet
        // delivered or failed).
        creatorCreditService.releaseVoiceIfUndelivered(CREATOR_USER_ID, TURN_ID);

        assertEquals(
                afterCharge,
                totalRemaining(),
                "B's failure must not refund the surcharge while A's claim is still unresolved");
        assertEquals(0, refundRowCount(), "no REFUND row for tts: while A might still deliver");

        // A is SLOW and eventually succeeds.
        creatorCreditService.markVoiceDelivered(CREATOR_USER_ID, TURN_ID);

        assertEquals(
                afterCharge,
                totalRemaining(),
                "the surcharge must still be charged (never refunded) once audio was actually delivered");
        assertEquals(0, refundRowCount());
        assertEquals(
                true,
                voiceSpeakRepository.findByIdCreatorUserIdAndIdTurnId(CREATOR_USER_ID, TURN_ID).orElseThrow().isDelivered());
    }

    @Test
    @DisplayName(
            "K-15 round 2: a single, non-concurrent failure (speakCount == 1) still refunds the tts:"
                    + " surcharge exactly as before -- the concurrent-claim guard must not regress the"
                    + " ordinary single-failure case")
    void singleUnconcurrentFailureStillRefunds() {
        seedGrant(10);
        chargeVoiceTurn();
        int afterCharge = totalRemaining();

        boolean claimed = creatorCreditService.claimVoiceSpeak(CREATOR_USER_ID, TURN_ID);
        assertTrue(claimed);

        creatorCreditService.releaseVoiceIfUndelivered(CREATOR_USER_ID, TURN_ID);

        assertEquals(afterCharge + 1, totalRemaining(), "the sole, unconcurrent failure must still refund");
        assertEquals(1, refundRowCount());
    }

    @Test
    @DisplayName(
            "K-15 round 2: releaseVoiceIfUndelivered is idempotent -- a second call for an already"
                    + " refunded turn never double-refunds")
    void releaseVoiceIfUndeliveredNeverDoubleRefunds() {
        seedGrant(10);
        chargeVoiceTurn();
        creatorCreditService.claimVoiceSpeak(CREATOR_USER_ID, TURN_ID);

        creatorCreditService.releaseVoiceIfUndelivered(CREATOR_USER_ID, TURN_ID);
        int afterFirstRefund = totalRemaining();
        creatorCreditService.releaseVoiceIfUndelivered(CREATOR_USER_ID, TURN_ID);

        assertEquals(afterFirstRefund, totalRemaining(), "a second release call must not refund twice");
        assertEquals(1, refundRowCount(), "exactly one REFUND row, ever, for this turn's tts: surcharge");
    }

    @Test
    @DisplayName(
            "K-15 round 2: markVoiceDelivered before any failure means a later failed retry never"
                    + " refunds a surcharge that already bought delivered audio (known-open-bug #7,"
                    + " now persisted instead of process-local)")
    void deliveredBeforeFailureBlocksTheRefund() {
        seedGrant(10);
        chargeVoiceTurn();
        int afterCharge = totalRemaining();

        creatorCreditService.claimVoiceSpeak(CREATOR_USER_ID, TURN_ID);
        creatorCreditService.markVoiceDelivered(CREATOR_USER_ID, TURN_ID);

        // A second, later claim for the same turn (a legitimate creator retry) that then fails.
        creatorCreditService.claimVoiceSpeak(CREATOR_USER_ID, TURN_ID);
        creatorCreditService.releaseVoiceIfUndelivered(CREATOR_USER_ID, TURN_ID);

        assertEquals(afterCharge, totalRemaining(), "a turn that already delivered audio must never be refunded");
        assertEquals(0, refundRowCount());
    }

    @Test
    @DisplayName(
            "K-06: the ONE attempt for a turn delivered audio and then failed afterwards (speakCount"
                    + " == 1, delivered == true) -- only the delivered check stops this refund, so this"
                    + " pins it on its own (speakCount alone would allow it)")
    void deliveredThenFailedSameAttemptIsNeverRefunded() {
        seedGrant(10);
        chargeVoiceTurn();
        int afterCharge = totalRemaining();

        assertTrue(creatorCreditService.claimVoiceSpeak(CREATOR_USER_ID, TURN_ID));
        creatorCreditService.markVoiceDelivered(CREATOR_USER_ID, TURN_ID);
        // Same attempt, no second claim: e.g. the audio was produced and then the response broke.
        creatorCreditService.releaseVoiceIfUndelivered(CREATOR_USER_ID, TURN_ID);

        assertEquals(afterCharge, totalRemaining(), "delivered audio must never be refunded");
        assertEquals(0, refundRowCount());
    }
}
