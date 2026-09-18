package com.influora.service.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.lenient;

import com.influora.common.Ulids;
import com.influora.domain.entity.BrandAiCredit;
import com.influora.domain.entity.Plan;
import com.influora.domain.entity.Subscription;
import com.influora.domain.enums.PlanCode;
import com.influora.domain.enums.SubscriptionStatus;
import com.influora.integration.razorpay.RazorpayClient;
import com.influora.repository.BrandAiCreditRepository;
import com.influora.repository.PlanRepository;
import com.influora.repository.SubscriptionRepository;
import com.influora.service.IdempotencyService;
import com.influora.service.meera.AICreditService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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

/**
 * T-CREDITCLOCK-0918 REPAIR ROUND [vikram · 2026-09-18] -- H2 `@DataJpaTest` regression tests for
 * the repair-round findings on commit b83a36f, run through the REAL {@link AICreditService} and
 * {@link SubscriptionService} (not hand-simulated), same harness precedent as {@code
 * AICreditClockScenarioTest} / {@code AdminEmailSendLockRepositoryConcurrencyTest}.
 *
 * <p>Covers, by review finding:
 *
 * <ul>
 *   <li>HIGH: {@link #probeA_fundedLaunchOncePerBillingPeriod()} -- a second funded launch in the
 *       same billing period must leave credits unchanged; a funded launch in a NEW period refills.
 *   <li>MEDIUM: {@link #probeB_pastDueFundedLaunchKeepsProAllotment()} -- a PAST_DUE Pro brand's
 *       funded launch must not be resynced down toward Free mid-grace.
 *   <li>MEDIUM: {@link #probeC_reconcileDefersRefillWithoutConfirmedPeriod()} -- an ACTIVE webhook
 *       delivery with no period must not trigger a billing refill against the row's stale
 *       (pre-upgrade) {@code currentPeriodEnd}; the LATER period-carrying delivery refills exactly
 *       once.
 *   <li>LOW: {@link #probeD_refillForBillingPeriodGuardIsForwardOnly()} -- refilling for an OLDER
 *       period than the one already granted must be a no-op.
 * </ul>
 *
 * <p><b>Falsification (hand-verified for this submission, see finalCommit's build log):</b>
 * reverting {@link BrandAiCreditRepository#applyEscrowFundedResetOncePerPeriod}'s WHERE guard to
 * always-true (or reverting {@link AICreditService#applyEscrowFundedReset} to call the old
 * unconditional {@link BrandAiCreditRepository#applyEscrowFundedReset} unconditionally) turns
 * {@link #probeA_fundedLaunchOncePerBillingPeriod()} red on its "second launch same period"
 * assertion. Reverting {@link BrandAiCreditRepository#refillForBillingPeriod}'s guard back to
 * {@code <>} turns {@link #probeD_refillForBillingPeriodGuardIsForwardOnly()} red.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@EntityScan(basePackageClasses = {BrandAiCredit.class, Subscription.class, Plan.class})
@EnableJpaRepositories(
        basePackageClasses = {
            BrandAiCreditRepository.class,
            SubscriptionRepository.class,
            PlanRepository.class
        },
        excludeFilters =
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern =
                                "com\\.influora\\.repository\\."
                                        + "(?!BrandAiCreditRepository$|SubscriptionRepository$|PlanRepository$).*"))
@Import({AICreditService.class, SubscriptionService.class, PlanService.class})
@TestPropertySource(
        properties = {
            "spring.datasource.url="
                    + "jdbc:h2:mem:ai_credit_clock_repair_test;DB_CLOSE_DELAY=-1;MODE=MySQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
            "spring.jpa.properties.hibernate.query.startup-check=false"
        })
class AICreditClockRepairRoundTest {

    @Autowired private AICreditService aiCreditService;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private BrandAiCreditRepository creditRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private PlanRepository planRepository;

    @MockBean private RazorpayClient razorpayClient;
    @MockBean private IdempotencyService idempotencyService;

    private Plan freePlan;
    private Plan proPlan;

    @BeforeEach
    void setUp() {
        lenient().when(razorpayClient.isConfigured()).thenReturn(false);
        freePlan =
                planRepository.save(
                        Plan.builder()
                                .id(Ulids.newUlid())
                                .code(PlanCode.FREE)
                                .name("Free")
                                .priceInr(0)
                                .aiMonthlyAllotment(100)
                                .seatLimit(1)
                                .active(true)
                                .build());
        proPlan =
                planRepository.save(
                        Plan.builder()
                                .id(Ulids.newUlid())
                                .code(PlanCode.PRO)
                                .name("Pro")
                                .priceInr(499900)
                                .aiMonthlyAllotment(400)
                                .seatLimit(5)
                                .active(true)
                                .build());
    }

    private String newWorkspaceId() {
        return Ulids.newUlid();
    }

    private void saveCredit(String workspaceId, int planAllotment, int creditsRemaining, LocalDate lastReset) {
        creditRepository.save(
                BrandAiCredit.builder()
                        .workspaceId(workspaceId)
                        .planAllotment(planAllotment)
                        .creditsRemaining(creditsRemaining)
                        .cycleStart(lastReset)
                        .lastReset(lastReset)
                        .build());
    }

    private void saveSubscription(
            String workspaceId, String planId, SubscriptionStatus status, Instant periodStart, Instant periodEnd) {
        subscriptionRepository.save(
                Subscription.builder()
                        .id(Ulids.newUlid())
                        .workspaceId(workspaceId)
                        .planId(planId)
                        .status(status)
                        .razorpaySubscriptionId(planId.equals(proPlan.getId()) ? "sub_" + workspaceId : null)
                        .currentPeriodStart(periodStart)
                        .currentPeriodEnd(periodEnd)
                        .cancelAtPeriodEnd(false)
                        .build());
    }

    private int creditsOf(String workspaceId) {
        return creditRepository.findByWorkspaceId(workspaceId).orElseThrow().getCreditsRemaining();
    }

    private void spend(String workspaceId, int amount) {
        BrandAiCredit credit = creditRepository.findByWorkspaceId(workspaceId).orElseThrow();
        credit.setCreditsRemaining(credit.getCreditsRemaining() - amount);
        creditRepository.save(credit);
    }

    private static Instant truncated(String isoInstant) {
        return Instant.parse(isoInstant).truncatedTo(ChronoUnit.SECONDS);
    }

    // ------------------------------------------------------------------------------------
    // HIGH: funded launch, at most once per billing period.
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "HIGH probeA: ACTIVE Pro, funded twice in the SAME period -> second launch is a no-op;"
                    + " a NEW period refills again")
    void probeA_fundedLaunchOncePerBillingPeriod() {
        String ws = newWorkspaceId();
        Instant periodEnd = truncated("2026-10-15T00:00:00Z");
        saveSubscription(ws, proPlan.getId(), SubscriptionStatus.ACTIVE, truncated("2026-09-15T00:00:00Z"), periodEnd);
        saveCredit(ws, 400, 450, LocalDate.of(2026, 9, 15));

        assertEquals(SubscriptionService.CreditClock.BILLING_PERIOD, subscriptionService.creditClockFor(ws));

        Instant unlimitedUntil = Instant.now().plusSeconds(604800);
        aiCreditService.applyEscrowFundedReset(ws, unlimitedUntil);
        assertEquals(450, creditsOf(ws), "first funded launch: 400 planAllotment + 50 loyalty = 450");

        spend(ws, 300);
        assertEquals(150, creditsOf(ws), "spend 300 -> 150");

        aiCreditService.applyEscrowFundedReset(ws, unlimitedUntil);
        assertEquals(
                150,
                creditsOf(ws),
                "a SECOND funded launch in the SAME billing period must be a no-op, not refill back to 450");

        // A funded launch in a NEW billing period refills again.
        Instant newPeriodEnd = truncated("2026-11-15T00:00:00Z");
        Subscription sub = subscriptionRepository.findByWorkspaceId(ws).orElseThrow();
        sub.renewPeriod(truncated("2026-10-15T00:00:00Z"), newPeriodEnd);
        subscriptionRepository.save(sub);

        aiCreditService.applyEscrowFundedReset(ws, unlimitedUntil);
        assertEquals(450, creditsOf(ws), "a funded launch in a NEW billing period refills again");
    }

    @Test
    @DisplayName(
            "MEDIUM probeH (round 2, kabir): a Free (CALENDAR_MONTH) workspace refills from a"
                    + " funded launch AT MOST ONCE per UTC calendar month, matching Swapnil's"
                    + " once-per-period ruling -- this REVERSES round 1's 'refills on every launch'"
                    + " behavior for this workspace class, closing the fund-then-refund loop kabir"
                    + " found (EscrowService#refund is reachable by a brand OWNER/ADMIN)")
    void probeH_calendarClockFundedLaunchOncePerCalendarMonth() {
        String ws = newWorkspaceId();
        saveSubscription(ws, freePlan.getId(), SubscriptionStatus.ACTIVE, Instant.now(), Instant.now().plusSeconds(2592000));
        saveCredit(ws, 100, 100, LocalDate.now());

        assertEquals(SubscriptionService.CreditClock.CALENDAR_MONTH, subscriptionService.creditClockFor(ws));

        Instant unlimitedUntil = Instant.now().plusSeconds(604800);
        aiCreditService.applyEscrowFundedReset(ws, unlimitedUntil);
        assertEquals(150, creditsOf(ws), "first funded launch this month: Free(100) + loyalty(50) = 150");

        spend(ws, 100);
        assertEquals(50, creditsOf(ws));

        aiCreditService.applyEscrowFundedReset(ws, unlimitedUntil);
        assertEquals(
                50,
                creditsOf(ws),
                "a SECOND funded launch in the SAME UTC calendar month must be a no-op, not refill"
                        + " back to 150");

        // A funded launch in a NEW UTC calendar month refills again -- exercised at the repository
        // layer directly (BrandAiCreditRepositoryQueryTest / stubAtomicWrites cover the service
        // seam; this class does not fast-forward the wall clock across a month boundary).
    }

    // ------------------------------------------------------------------------------------
    // MEDIUM: PAST_DUE funded launch must not be resynced toward Free.
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("MEDIUM probeB: PAST_DUE Pro brand funds a launch -> keeps the Pro allotment, not resynced to Free")
    void probeB_pastDueFundedLaunchKeepsProAllotment() {
        String ws = newWorkspaceId();
        Instant periodEnd = truncated("2026-10-15T00:00:00Z");
        saveSubscription(ws, proPlan.getId(), SubscriptionStatus.PAST_DUE, truncated("2026-09-15T00:00:00Z"), periodEnd);
        saveCredit(ws, 400, 350, LocalDate.of(2026, 9, 15));

        assertEquals(SubscriptionService.CreditClock.BILLING_PERIOD, subscriptionService.creditClockFor(ws));
        assertEquals(
                freePlan.getId(),
                subscriptionService.getActivePlanForWorkspace(ws).getId(),
                "getActivePlanForWorkspace still (correctly) maps PAST_DUE to Free for OTHER callers");
        assertEquals(
                proPlan.getId(),
                subscriptionService.getPlanForCreditSync(ws).getId(),
                "getPlanForCreditSync must NOT map a BILLING_PERIOD/PAST_DUE workspace to Free");

        aiCreditService.applyEscrowFundedReset(ws, Instant.now().plusSeconds(604800));

        BrandAiCredit after = creditRepository.findByWorkspaceId(ws).orElseThrow();
        assertEquals(400, after.getPlanAllotment(), "planAllotment must stay Pro's 400 while PAST_DUE, not resynced to Free's 100");
        assertEquals(450, after.getCreditsRemaining(), "400 planAllotment + 50 loyalty = 450, not cut down toward Free");
    }

    // ------------------------------------------------------------------------------------
    // MEDIUM: reconcile must not refill against an unconfirmed/stale period.
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "MEDIUM probeC: ACTIVE webhook with NO period on an upgrading Free row does not refill;"
                    + " the later period-carrying webhook refills exactly once")
    void probeC_reconcileDefersRefillWithoutConfirmedPeriod() {
        String ws = newWorkspaceId();
        // Free-anchor period, exactly as SubscriptionService#createFreeSubscription would leave it.
        Instant freeAnchorStart = truncated("2026-09-01T00:00:00Z");
        Instant freeAnchorEnd = truncated("2026-10-01T00:00:00Z");
        saveSubscription(ws, freePlan.getId(), SubscriptionStatus.ACTIVE, freeAnchorStart, freeAnchorEnd);
        saveCredit(ws, 100, 30, LocalDate.of(2026, 9, 1));

        // Simulate the webhook write applySubscriptionWebhookUpdate would do for an `activated`
        // event carrying NO period: plan changes to Pro, status ACTIVE, but renewPeriod is never
        // called (both period fields null in the payload) so currentPeriodEnd stays the stale
        // Free anchor. Then reconcile is called with confirmedPeriodEnd = null (the raw payload
        // value), exactly as the fixed applySubscriptionWebhookUpdate now does.
        Subscription sub = subscriptionRepository.findByWorkspaceId(ws).orElseThrow();
        sub.changePlan(proPlan.getId());
        sub.setStatus(SubscriptionStatus.ACTIVE);
        subscriptionRepository.save(sub);

        assertEquals(SubscriptionService.CreditClock.BILLING_PERIOD, subscriptionService.creditClockFor(ws));

        subscriptionService.reconcileAiCreditAllotment(ws, null);
        assertEquals(
                30,
                creditsOf(ws),
                "no confirmed period on this delivery -- billing refill must be deferred, not fired"
                        + " against the stale Free-anchor currentPeriodEnd");

        // The later `charged` webhook carries the real, genuine first period.
        Instant realPeriodEnd = truncated("2026-09-28T00:00:00Z");
        sub = subscriptionRepository.findByWorkspaceId(ws).orElseThrow();
        sub.renewPeriod(truncated("2026-08-28T00:00:00Z"), realPeriodEnd);
        subscriptionRepository.save(sub);

        subscriptionService.reconcileAiCreditAllotment(ws, realPeriodEnd);
        assertEquals(400, creditsOf(ws), "the period-carrying delivery refills exactly once, to the full Pro allotment");

        // A redelivery of the SAME event/period must not refill again.
        subscriptionService.reconcileAiCreditAllotment(ws, realPeriodEnd);
        spend(ws, 50);
        subscriptionService.reconcileAiCreditAllotment(ws, realPeriodEnd);
        assertEquals(350, creditsOf(ws), "a redelivery for the SAME period must not re-refill after a spend");
    }

    // ------------------------------------------------------------------------------------
    // LOW: refillForBillingPeriod's guard must be forward-only.
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("LOW probeD: refilling for an OLDER period than the one already granted is a no-op")
    void probeD_refillForBillingPeriodGuardIsForwardOnly() {
        String ws = newWorkspaceId();
        Instant p1 = truncated("2026-08-15T00:00:00Z");
        Instant p2 = truncated("2026-09-15T00:00:00Z");
        saveCredit(ws, 400, 400, LocalDate.of(2026, 9, 15));

        aiCreditService.refillForBillingPeriod(ws, p2);
        assertEquals(400, creditsOf(ws));
        spend(ws, 300);
        assertEquals(100, creditsOf(ws));

        aiCreditService.refillForBillingPeriod(ws, p1); // OLDER period -- must be a no-op
        assertEquals(100, creditsOf(ws), "refilling for an OLDER period than the granted one must not re-grant");
    }

    // ------------------------------------------------------------------------------------
    // GO-LIVE ROUND 2 [vikram · 2026-09-18] -- T-GOLIVE-0918-R2 lane CREDITS-2, kabir round-1
    // findings on 27ca63b:
    //   (a) MEDIUM: a status-only PAST_DUE webhook must not sync a grace-period Pro brand's
    //       allotment down to Free (wiki/decisions/2026-09-18-ai-credit-clock.md §1: hold the
    //       balance) -- see probeF below.
    //   (b) MEDIUM: a second funded launch in the same billing period must still extend the
    //       per-campaign unlimited window even though it must not refill credits -- see probeG.
    //   (c) MEDIUM: calendar-month brands must refill from campaign funding at most once per
    //       calendar month -- see probeH above (added to the existing probeA-calendar test, which
    //       asserted the OLD, now-reversed, ruling).
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "MEDIUM probeF (round 2, kabir p1): a status-only PAST_DUE webhook reconcile must not"
                    + " sync a grace-period Pro brand's allotment down to Free -- a later 1-credit"
                    + " refund must add 1, not get clamped down against a wrongly-synced Free"
                    + " monthlyAllotment")
    void probeF_statusOnlyPastDueReconcileHoldsProAllotment() {
        String ws = newWorkspaceId();
        Instant periodEnd = truncated("2026-10-15T00:00:00Z");
        saveSubscription(ws, proPlan.getId(), SubscriptionStatus.ACTIVE, truncated("2026-09-15T00:00:00Z"), periodEnd);
        saveCredit(ws, 400, 200, LocalDate.of(2026, 9, 15));

        // Status-only PAST_DUE transition -- no period change, exactly like a Razorpay status-only
        // webhook (RazorpayWebhookController carries no period fields for these events).
        Subscription sub = subscriptionRepository.findByWorkspaceId(ws).orElseThrow();
        sub.setStatus(SubscriptionStatus.PAST_DUE);
        subscriptionRepository.save(sub);
        assertEquals(SubscriptionService.CreditClock.BILLING_PERIOD, subscriptionService.creditClockFor(ws));

        subscriptionService.reconcileAiCreditAllotment(ws, periodEnd);

        BrandAiCredit afterReconcile = creditRepository.findByWorkspaceId(ws).orElseThrow();
        assertEquals(
                400,
                afterReconcile.getPlanAllotment(),
                "PAST_DUE grace: planAllotment must stay Pro's 400, not resynced to Free's 100");
        assertEquals(
                200, afterReconcile.getCreditsRemaining(), "reconcile itself must not touch creditsRemaining while PAST_DUE");

        // The real-world consequence kabir's probe p1 found: a 1-credit refund (e.g.
        // AICreditService#doRelease refunding a failed Meera turn) clamps its add to
        // c.monthlyAllotment -- if that got wrongly synced to Free's 100 above, a 1-credit refund
        // on a 200-balance would be clamped DOWN to 100 instead of becoming 201.
        creditRepository.refundCredits(ws, 1, Instant.now());
        assertEquals(
                201,
                creditsOf(ws),
                "a 1-credit refund during PAST_DUE grace must add 1, not clamp the balance down to"
                        + " Free's allotment");
    }

    @Test
    @DisplayName(
            "MEDIUM probeG (round 2, kabir): a second funded launch in the SAME billing period must"
                    + " still extend the per-campaign unlimited window, even though credits are NOT"
                    + " refilled -- the ruling caps the refill, not the window")
    void probeG_secondFundedLaunchSamePeriodStillExtendsWindow() {
        String ws = newWorkspaceId();
        Instant periodEnd = truncated("2026-10-15T00:00:00Z");
        saveSubscription(ws, proPlan.getId(), SubscriptionStatus.ACTIVE, truncated("2026-09-15T00:00:00Z"), periodEnd);
        saveCredit(ws, 400, 450, LocalDate.of(2026, 9, 15));

        // Truncated to millis: H2's TIMESTAMP column round-trips at millisecond precision, coarser
        // than Instant.now()'s full nanosecond precision -- comparing the exact stored value later
        // needs an in-memory Instant that survives that round-trip unchanged.
        Instant firstWindow =
                Instant.now().plusSeconds(86_400 * 7).truncatedTo(ChronoUnit.MILLIS); // a 7-day campaign
        aiCreditService.applyEscrowFundedReset(ws, firstWindow);
        spend(ws, 300);
        assertEquals(150, creditsOf(ws));

        Instant secondWindow =
                Instant.now().plusSeconds(86_400 * 21).truncatedTo(ChronoUnit.MILLIS); // a longer, 21-day campaign
        aiCreditService.applyEscrowFundedReset(ws, secondWindow);

        BrandAiCredit after = creditRepository.findByWorkspaceId(ws).orElseThrow();
        assertEquals(
                150,
                after.getCreditsRemaining(),
                "a second funded launch in the SAME billing period must still be a credit no-op");
        assertEquals(
                secondWindow,
                after.getUnlimitedUntil(),
                "but the unlimited window must still extend to cover the new campaign's end");
    }
}
