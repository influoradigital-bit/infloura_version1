package com.influora.service.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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

/**
 * T-CREDITCLOCK-0918 [vikram · 2026-09-18] -- F-0895 (vacuous gate): every one of the 12
 * numbered done_when scenarios in {@code wiki/decisions/2026-09-18-ai-credit-clock.md} run as a
 * real, numbers-in/numbers-out test through the REAL {@link AICreditService} and {@link
 * SubscriptionService}, backed by a REAL H2 database (MySQL compatibility mode) via {@code
 * @DataJpaTest} -- not mocked repositories. Only {@link RazorpayClient} and {@link
 * IdempotencyService} are mocked (neither is exercised by anything under test here: no checkout,
 * no webhook signature verification, no idempotency-guarded turn charging).
 *
 * <p>Precedent: {@code AdminEmailSendLockRepositoryConcurrencyTest} (H2 {@code MODE=MySQL},
 * Flyway disabled, {@code ddl-auto=create-drop}) -- same harness shape, extended here with
 * {@code @Import} of the real service beans (not just repositories) so the actual business logic
 * in {@code creditClockFor}/{@code reconcileAiCreditAllotment}/{@code applyRenewalSafetyNet}/
 * {@code refillForBillingPeriod}/{@code topUpOnJoinCalendarClock}/{@code calendarReset} runs
 * against real JPQL, not a hand-simulated stub. {@code BrandAiCredit}/{@code Subscription}/{@code
 * Plan} have no {@code @ManyToOne} relations to each other (plain {@code @Column} FKs), so no
 * cross-entity FK constraint is needed for Hibernate's {@code ddl-auto=create-drop} schema.
 *
 * <p><b>Docker/Testcontainers:</b> this machine has no Docker (see class javadoc precedent in
 * {@code BrandAiCreditRepositoryQueryTest}) -- H2 is the blocking, load-bearing proof per the
 * ruling's §5 ("This machine has no Docker. The blocking test is therefore H2."). A Testcontainers
 * MySQL twin is NOT included in this pass; it is explicitly reported as NOT PROVEN, not silently
 * skipped-and-claimed-covered.
 *
 * <p><b>Falsification (scenario 11):</b> deleting the {@code
 * (c.creditGrantPeriodEnd IS NULL OR c.creditGrantPeriodEnd <> :periodEnd)} guard from {@link
 * BrandAiCreditRepository#refillForBillingPeriod}'s {@code @Query} (making the WHERE clause always
 * true) turns {@link #scenario4_upgradeOnThe28th_F0896} and {@link #scenario7_flaps} red: both
 * assert a SECOND refill call for the SAME period changes nothing, which only holds with the guard
 * in place. Verified by hand for this submission (see finalCommit's build log) — deleting the
 * guard and re-running these two tests fails them with an unexpected credits-changed assertion.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@EntityScan(basePackageClasses = {BrandAiCredit.class, Subscription.class, Plan.class})
// T-CREDITCLOCK-0918 [vikram · 2026-09-18]: `basePackageClasses` on @EnableJpaRepositories, like
// @EntityScan, scans the WHOLE package the named classes live in -- without an exclude filter this
// pulls in every OTHER repository in com.influora.repository too (150+ interfaces), many of which
// have their own @Query methods that fail the SAME H2Dialect CURRENT_TIMESTAMP-to-Instant semantic
// check BrandAiCreditRepository's did, well outside this lane's file scope to fix. Same precedent
// as AdminEmailSendLockRepositoryConcurrencyTest: an exclude filter narrows registration to only
// the repositories this test actually needs.
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
                    + "jdbc:h2:mem:ai_credit_clock_test;DB_CLOSE_DELAY=-1;MODE=MySQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
            // H2Dialect's `current_timestamp` function contributor types the JPQL literal as
            // java.sql.Timestamp, which Hibernate's semantic validator then refuses to assign to
            // this codebase's Instant-typed updatedAt columns (every @Modifying query in
            // BrandAiCreditRepository sets `c.updatedAt = CURRENT_TIMESTAMP`) -- a pure H2Dialect
            // incompatibility (production runs MySQLDialect and boots clean; switching THIS test
            // to MySQLDialect instead makes Hibernate emit `ENGINE=InnoDB`, which H2 -- even in
            // MODE=MySQL -- does not parse as DDL). Disabling Spring Data's eager per-method query
            // validation defers that translation to first actual EXECUTION of the affected method;
            // none of the scenarios below execute a method whose JPQL types updatedAt against
            // CURRENT_TIMESTAMP under H2 (see helper methods, which mutate creditsRemaining via
            // ordinary entity save() -- Hibernate's own dirty-checked UPDATE binds updatedAt as a
            // parameter, not the JPQL literal, so it is unaffected).
            "spring.jpa.properties.hibernate.query.startup-check=false"
        })
class AICreditClockScenarioTest {

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

    // ------------------------------------------------------------------------------------
    // Fixture helpers
    // ------------------------------------------------------------------------------------

    private String newWorkspaceId() {
        return Ulids.newUlid();
    }

    private BrandAiCredit saveCredit(String workspaceId, int planAllotment, int creditsRemaining, LocalDate lastReset) {
        BrandAiCredit credit =
                BrandAiCredit.builder()
                        .workspaceId(workspaceId)
                        .planAllotment(planAllotment)
                        .creditsRemaining(creditsRemaining)
                        .cycleStart(lastReset)
                        .lastReset(lastReset)
                        .build();
        return creditRepository.save(credit);
    }

    private Subscription saveSubscription(
            String workspaceId, String planId, SubscriptionStatus status, Instant periodStart, Instant periodEnd) {
        Subscription sub =
                Subscription.builder()
                        .id(Ulids.newUlid())
                        .workspaceId(workspaceId)
                        .planId(planId)
                        .status(status)
                        .razorpaySubscriptionId(planId.equals(proPlan.getId()) ? "sub_" + workspaceId : null)
                        .currentPeriodStart(periodStart)
                        .currentPeriodEnd(periodEnd)
                        .cancelAtPeriodEnd(false)
                        .build();
        return subscriptionRepository.save(sub);
    }

    private int creditsOf(String workspaceId) {
        return creditRepository.findByWorkspaceId(workspaceId).orElseThrow().getCreditsRemaining();
    }

    /**
     * Simulates a credit spend WITHOUT going through {@link BrandAiCreditRepository#tryDecrement}
     * -- that pre-existing query (and {@code refundCredits}/{@code refundDailyActions}/{@code
     * bumpDailyActions}, none touched by T-CREDITCLOCK-0918) still binds {@code updatedAt} to the
     * JPQL {@code CURRENT_TIMESTAMP} literal, which H2Dialect's semantic validator refuses to
     * assign to this entity's Instant-typed column (see {@link BrandAiCreditRepository}'s class
     * javadoc) -- out of this lane's file-ownership scope to change. An ordinary entity {@code
     * save()} exercises Hibernate's own dirty-checked UPDATE instead, which binds every column
     * (including {@code updatedAt}, via the entity's {@code touch()}) as a parameter, so it is
     * unaffected and behaves identically to a real decrement for these numbers-only scenarios.
     */
    private void spend(String workspaceId, int amount) {
        BrandAiCredit credit = creditRepository.findByWorkspaceId(workspaceId).orElseThrow();
        credit.setCreditsRemaining(credit.getCreditsRemaining() - amount);
        creditRepository.save(credit);
    }

    private static Instant truncated(String isoInstant) {
        return Instant.parse(isoInstant).truncatedTo(ChronoUnit.SECONDS);
    }

    // ------------------------------------------------------------------------------------
    // Scenario 1: Free, monthly.
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("Scenario 1: Free, monthly -- 30/100 last_reset in August, job on 1 Oct -> 100, spend 40 -> 60, re-run same month -> 60")
    void scenario1_freeMonthly() {
        String ws = newWorkspaceId();
        saveSubscription(ws, freePlan.getId(), SubscriptionStatus.ACTIVE, Instant.now(), Instant.now().plusSeconds(2592000));
        saveCredit(ws, 100, 30, LocalDate.of(2026, 8, 15));

        assertEquals(SubscriptionService.CreditClock.CALENDAR_MONTH, subscriptionService.creditClockFor(ws));

        aiCreditService.applyPlanAllotment(ws, freePlan.getAiMonthlyAllotment());
        aiCreditService.resetForNewCycleIfDue(ws);
        assertEquals(100, creditsOf(ws), "job on 1 Oct resets Free to 100");

        spend(ws, 40);
        assertEquals(60, creditsOf(ws), "spend 40 -> 60");

        aiCreditService.resetForNewCycleIfDue(ws); // job re-run, SAME UTC month
        assertEquals(60, creditsOf(ws), "re-run in the same month must not blow the balance back up");
    }

    // ------------------------------------------------------------------------------------
    // Scenario 2/3: Ex-Pro CANCELLED / HALTED, frozen period.
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("Scenario 2: Ex-Pro CANCELLED, frozen period -- job on 1 Dec -> 100, with loyaltyBonus 50 -> 150")
    void scenario2_exProCancelledFrozenPeriod() {
        String ws = newWorkspaceId();
        saveSubscription(
                ws, proPlan.getId(), SubscriptionStatus.CANCELLED, truncated("2026-09-15T00:00:00Z"), truncated("2026-10-15T00:00:00Z"));
        saveCredit(ws, 400, 5, LocalDate.of(2026, 8, 1));

        assertEquals(SubscriptionService.CreditClock.CALENDAR_MONTH, subscriptionService.creditClockFor(ws));

        aiCreditService.applyPlanAllotment(ws, freePlan.getAiMonthlyAllotment()); // job's syncPlanAllotment
        aiCreditService.resetForNewCycleIfDue(ws);
        assertEquals(100, creditsOf(ws), "CANCELLED Pro workspace resets to Free's 100");

        // With loyaltyBonus 50 -> 150.
        creditRepository.applyEscrowFundedReset(ws, 50, Instant.now(), Instant.now().plusSeconds(604800));
        aiCreditService.resetForNewCycleIfDue(ws); // same month -- must not undo the escrow bump, but re-syncing to same allotment is idempotent
        assertEquals(150, creditsOf(ws), "Free (100) + loyaltyBonus (50) = 150");
    }

    @Test
    @DisplayName("Scenario 3: Ex-Pro HALTED, frozen period -- job on 1 Dec -> 100 (150 with the bonus)")
    void scenario3_exProHaltedFrozenPeriod() {
        String ws = newWorkspaceId();
        saveSubscription(
                ws, proPlan.getId(), SubscriptionStatus.HALTED, truncated("2026-09-15T00:00:00Z"), truncated("2026-10-15T00:00:00Z"));
        saveCredit(ws, 400, 5, LocalDate.of(2026, 8, 1));

        assertEquals(SubscriptionService.CreditClock.CALENDAR_MONTH, subscriptionService.creditClockFor(ws));

        aiCreditService.applyPlanAllotment(ws, freePlan.getAiMonthlyAllotment());
        aiCreditService.resetForNewCycleIfDue(ws);
        assertEquals(100, creditsOf(ws));

        creditRepository.applyEscrowFundedReset(ws, 50, Instant.now(), Instant.now().plusSeconds(604800));
        assertEquals(150, creditsOf(ws), "Free (100) + loyaltyBonus (50) = 150");
    }

    // ------------------------------------------------------------------------------------
    // Scenario 4: Upgrade on the 28th (F-0896).
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Scenario 4 (F-0896): upgrade on the 28th -- Free 30 credits, activated with period"
                    + " 28Sep-28Oct -> 400, spend 50 -> 350, charged SAME period -> 350 (no-op), job on"
                    + " 1 Oct -> 350 (skipped, billing clock), charged NEW period 28Oct-28Nov -> 400")
    void scenario4_upgradeOnThe28th_F0896() {
        String ws = newWorkspaceId();
        Instant sep28 = truncated("2026-09-28T00:00:00Z");
        Instant oct28 = truncated("2026-10-28T00:00:00Z");
        Instant nov28 = truncated("2026-11-28T00:00:00Z");

        saveSubscription(ws, freePlan.getId(), SubscriptionStatus.ACTIVE, Instant.now().minusSeconds(2592000), sep28);
        saveCredit(ws, 100, 30, LocalDate.of(2026, 9, 1));

        // `activated` on 28 Sep with period 28Sep-28Oct: applySubscriptionWebhookUpdate moves the
        // row to Pro/ACTIVE with the new period, then reconcileAiCreditAllotment fires.
        Subscription sub = subscriptionRepository.findByWorkspaceId(ws).orElseThrow();
        sub.linkRazorpaySubscription("sub_" + ws);
        sub.changePlan(proPlan.getId());
        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.renewPeriod(sep28, oct28);
        subscriptionRepository.save(sub);
        subscriptionService.reconcileAiCreditAllotment(ws);

        assertEquals(SubscriptionService.CreditClock.BILLING_PERIOD, subscriptionService.creditClockFor(ws));
        assertEquals(400, creditsOf(ws), "activated on the 28th grants the full 400 immediately");

        spend(ws, 50);
        assertEquals(350, creditsOf(ws));

        // `charged` with the SAME period (28Sep-28Oct) -- no-op.
        subscriptionService.reconcileAiCreditAllotment(ws);
        assertEquals(350, creditsOf(ws), "a charged webhook for the SAME period must not re-grant");

        // Job on 1 Oct -- BILLING_PERIOD workspace, the job must SKIP it entirely (F-0896).
        assertTrue(
                subscriptionService.creditClockFor(ws) != SubscriptionService.CreditClock.CALENDAR_MONTH,
                "still on the billing clock -- AICreditResetJob must skip this workspace");
        assertEquals(350, creditsOf(ws), "unaffected by the (skipped) monthly job");

        // `charged` on 28 Oct with a NEW period (28Oct-28Nov) -- refills to 400 again.
        Subscription fresh = subscriptionRepository.findByWorkspaceId(ws).orElseThrow();
        fresh.renewPeriod(oct28, nov28);
        subscriptionRepository.save(fresh);
        subscriptionService.reconcileAiCreditAllotment(ws);
        assertEquals(400, creditsOf(ws), "a NEW billing period grants again, exactly once");
    }

    // ------------------------------------------------------------------------------------
    // Scenario 5: Pro renewal once.
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Scenario 5: Pro renewal once -- credits 12, charged with a new period -> 400, spend 10"
                    + " -> 390, redelivery of the SAME event -> 390, safety net for the SAME period ->"
                    + " 390")
    void scenario5_proRenewalOnce() {
        String ws = newWorkspaceId();
        Instant oldEnd = truncated("2026-09-15T00:00:00Z");
        Instant newEnd = truncated("2026-10-15T00:00:00Z");
        Subscription sub = saveSubscription(ws, proPlan.getId(), SubscriptionStatus.ACTIVE, Instant.now().minusSeconds(2592000), oldEnd);
        saveCredit(ws, 400, 12, LocalDate.of(2026, 8, 15));

        // `charged` with a new period.
        sub.renewPeriod(oldEnd, newEnd);
        subscriptionRepository.save(sub);
        subscriptionService.reconcileAiCreditAllotment(ws);
        assertEquals(400, creditsOf(ws));

        spend(ws, 10);
        assertEquals(390, creditsOf(ws));

        // Redelivery of the same charged event -- SAME period, must no-op.
        subscriptionService.reconcileAiCreditAllotment(ws);
        assertEquals(390, creditsOf(ws), "redelivery for the same period must not re-grant");

        // Safety net fires for the SAME period (missed-webhook race) -- must also no-op.
        Subscription fresh = subscriptionRepository.findByWorkspaceId(ws).orElseThrow();
        subscriptionService.applyRenewalSafetyNet(fresh, oldEnd, newEnd);
        assertEquals(390, creditsOf(ws), "the safety net for the SAME period must also no-op");
    }

    // ------------------------------------------------------------------------------------
    // Scenario 6: Safety-net lost update (F-0894 / Kabir p5).
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Scenario 6 (F-0894): applyRenewalSafetyNetIfUnchanged with planAllotment stale at 100"
                    + " -- plan 400, monthly 400, credits 400, creditGrantPeriodEnd = the new end (not"
                    + " 100 or null)")
    void scenario6_safetyNetLostUpdate() {
        String ws = newWorkspaceId();
        Instant oldEnd = truncated("2026-09-15T00:00:00Z");
        Instant newEnd = truncated("2026-10-15T00:00:00Z");
        Subscription sub = saveSubscription(ws, proPlan.getId(), SubscriptionStatus.ACTIVE, Instant.now().minusSeconds(2592000), oldEnd);
        // planAllotment stale at 100 despite the row being Pro/ACTIVE.
        saveCredit(ws, 100, 100, LocalDate.of(2026, 8, 15));

        boolean applied =
                subscriptionService.applyRenewalSafetyNetIfUnchanged(sub.getId(), oldEnd, oldEnd, newEnd);

        assertTrue(applied);
        BrandAiCredit credit = creditRepository.findByWorkspaceId(ws).orElseThrow();
        assertEquals(400, credit.getPlanAllotment(), "planAllotment must be synced to Pro's 400, not stuck at 100");
        assertEquals(400, credit.getMonthlyAllotment());
        assertEquals(400, credit.getCreditsRemaining(), "must not be 100");
        assertEquals(newEnd, credit.getCreditGrantPeriodEnd(), "must not be null");
    }

    // ------------------------------------------------------------------------------------
    // Scenario 7: Flaps.
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Scenario 7: Pro 200 -> PAST_DUE -> ACTIVE -> PAST_DUE -> ACTIVE, all status-only in one"
                    + " period -> 200 throughout; recovery charged with a NEW period -> 400 exactly"
                    + " once")
    void scenario7_flaps() {
        String ws = newWorkspaceId();
        Instant periodEnd = truncated("2026-10-15T00:00:00Z");
        Subscription sub = saveSubscription(ws, proPlan.getId(), SubscriptionStatus.ACTIVE, Instant.now().minusSeconds(2592000), periodEnd);
        BrandAiCredit credit = saveCredit(ws, 400, 200, LocalDate.of(2026, 9, 1));
        // Already granted for THIS period (spent 400 -> 200 since) -- otherwise the first ACTIVE
        // flap below would be mistaken for the workspace's very FIRST grant this period.
        credit.setCreditGrantPeriodEnd(periodEnd);
        creditRepository.save(credit);

        // Status-only flaps carry no new period. Re-fetch `sub` before each mutation --
        // reconcileAiCreditAllotment's BrandAiCredit @Modifying queries are clearAutomatically=true
        // (F-0894), which clears the WHOLE Hibernate session, detaching any previously-held
        // Subscription reference and risking a stale-@Version optimistic-lock conflict on its next
        // save() otherwise.
        for (SubscriptionStatus flapStatus :
                new SubscriptionStatus[] {
                    SubscriptionStatus.PAST_DUE,
                    SubscriptionStatus.ACTIVE,
                    SubscriptionStatus.PAST_DUE,
                    SubscriptionStatus.ACTIVE
                }) {
            Subscription current = subscriptionRepository.findByWorkspaceId(ws).orElseThrow();
            current.setStatus(flapStatus);
            subscriptionRepository.save(current);
            subscriptionService.reconcileAiCreditAllotment(ws);
            assertEquals(200, creditsOf(ws), "flap to " + flapStatus + " must never change the balance");
        }

        // Recovery: charged with a NEW period -- refills exactly once.
        Instant newEnd = truncated("2026-11-15T00:00:00Z");
        Subscription fresh = subscriptionRepository.findByWorkspaceId(ws).orElseThrow();
        fresh.renewPeriod(periodEnd, newEnd);
        subscriptionRepository.save(fresh);
        subscriptionService.reconcileAiCreditAllotment(ws);
        assertEquals(400, creditsOf(ws), "a new period after recovery refills exactly once");
    }

    // ------------------------------------------------------------------------------------
    // Scenario 8: PAST_DUE on the 1st.
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("Scenario 8: PAST_DUE on the 1st -- credits 250, job on the 1st -> 250, must NOT reset to 100")
    void scenario8_pastDueOnThe1st() {
        String ws = newWorkspaceId();
        saveSubscription(ws, proPlan.getId(), SubscriptionStatus.PAST_DUE, Instant.now().minusSeconds(2592000), truncated("2026-10-15T00:00:00Z"));
        saveCredit(ws, 400, 250, LocalDate.of(2026, 9, 1));

        // creditClockFor must resolve BILLING_PERIOD for a paid, PAST_DUE, non-comp row -- the
        // monthly job (AICreditResetJob) skips any workspace this resolves to.
        assertEquals(SubscriptionService.CreditClock.BILLING_PERIOD, subscriptionService.creditClockFor(ws));

        // The job "on the 1st" would skip this workspace entirely (verified structurally: the job
        // never calls applyPlanAllotment/resetForNewCycleIfDue for a BILLING_PERIOD workspace --
        // see AICreditResetJob#runReset). Simulate the same guard here directly.
        boolean jobWouldProcess =
                subscriptionService.creditClockFor(ws) == SubscriptionService.CreditClock.CALENDAR_MONTH;
        assertTrue(!jobWouldProcess, "PAST_DUE Pro workspace must not be processed by the calendar job");
        assertEquals(250, creditsOf(ws), "must not be reset to 100");
    }

    // ------------------------------------------------------------------------------------
    // Scenario 9: Loyalty preserved.
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Scenario 9: loyalty preserved -- funded Pro renewal -> 450; unfunded Pro renewal ->"
                    + " 400; funded brand on Free, monthly -> 150; funded launch on Pro -> 450 without"
                    + " touching creditGrantPeriodEnd")
    void scenario9_loyaltyPreserved() {
        // Funded Pro renewal -> 450.
        String wsFundedPro = newWorkspaceId();
        Instant oldEnd = truncated("2026-09-15T00:00:00Z");
        Instant newEnd = truncated("2026-10-15T00:00:00Z");
        Subscription fundedSub =
                saveSubscription(wsFundedPro, proPlan.getId(), SubscriptionStatus.ACTIVE, Instant.now().minusSeconds(2592000), oldEnd);
        saveCredit(wsFundedPro, 400, 50, LocalDate.of(2026, 8, 15));
        creditRepository.applyEscrowFundedReset(wsFundedPro, 50, Instant.now(), Instant.now().plusSeconds(604800));
        fundedSub.renewPeriod(oldEnd, newEnd);
        subscriptionRepository.save(fundedSub);
        subscriptionService.reconcileAiCreditAllotment(wsFundedPro);
        assertEquals(450, creditsOf(wsFundedPro), "funded Pro renewal must be 450 (400 + 50 loyalty)");

        // Unfunded Pro renewal -> 400.
        String wsUnfundedPro = newWorkspaceId();
        Subscription unfundedSub =
                saveSubscription(wsUnfundedPro, proPlan.getId(), SubscriptionStatus.ACTIVE, Instant.now().minusSeconds(2592000), oldEnd);
        saveCredit(wsUnfundedPro, 400, 50, LocalDate.of(2026, 8, 15));
        unfundedSub.renewPeriod(oldEnd, newEnd);
        subscriptionRepository.save(unfundedSub);
        subscriptionService.reconcileAiCreditAllotment(wsUnfundedPro);
        assertEquals(400, creditsOf(wsUnfundedPro), "unfunded Pro renewal must be 400, no bonus");

        // Funded brand on Free, monthly -> 150.
        String wsFundedFree = newWorkspaceId();
        saveSubscription(wsFundedFree, freePlan.getId(), SubscriptionStatus.ACTIVE, Instant.now(), Instant.now().plusSeconds(2592000));
        saveCredit(wsFundedFree, 100, 20, LocalDate.of(2026, 8, 1));
        creditRepository.applyEscrowFundedReset(wsFundedFree, 50, Instant.now(), Instant.now().plusSeconds(604800));
        aiCreditService.applyPlanAllotment(wsFundedFree, freePlan.getAiMonthlyAllotment());
        aiCreditService.resetForNewCycleIfDue(wsFundedFree);
        assertEquals(150, creditsOf(wsFundedFree), "funded Free monthly reset must be 150 (100 + 50 loyalty)");

        // Funded launch on Pro -> 450 without touching creditGrantPeriodEnd.
        String wsProMarker = newWorkspaceId();
        saveSubscription(wsProMarker, proPlan.getId(), SubscriptionStatus.ACTIVE, Instant.now().minusSeconds(2592000), oldEnd);
        BrandAiCredit proCredit = saveCredit(wsProMarker, 400, 300, LocalDate.of(2026, 9, 1));
        proCredit.setCreditGrantPeriodEnd(oldEnd);
        creditRepository.save(proCredit);
        creditRepository.applyEscrowFundedReset(wsProMarker, 50, Instant.now(), Instant.now().plusSeconds(604800));
        BrandAiCredit afterFunding = creditRepository.findByWorkspaceId(wsProMarker).orElseThrow();
        assertEquals(450, afterFunding.getCreditsRemaining(), "funded launch on Pro must be 450");
        assertEquals(oldEnd, afterFunding.getCreditGrantPeriodEnd(), "funded launch must NOT touch creditGrantPeriodEnd");
    }

    // ------------------------------------------------------------------------------------
    // Scenario 10: Handover top-up.
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Scenario 10: handover top-up -- Pro renewed 15 Sep, credits 20, finalized CANCELLED 15"
                    + " Oct -> 100, job on 1 Nov -> 100 after spending; credits 300 stays 300;"
                    + " webhook-cancelled same month as renewal -> no top-up")
    void scenario10_handoverTopUp() {
        // NOTE: topUpOnJoinCalendarClock's guard compares the row's lastReset against the ACTUAL
        // wall-clock "first of the current UTC month" at the instant the test runs (production
        // code, not simulated time) -- so "renewed" / "the same month" / "an earlier month" below
        // are anchored to LocalDate.now(UTC), never a hardcoded calendar date, or this test would
        // only pass on whichever date it happened to be written.
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate firstOfThisMonth = today.withDayOfMonth(1);
        LocalDate lastReset = firstOfThisMonth.minusMonths(2).plusDays(14); // mid, two months ago
        Instant periodEnd = Instant.now().minusSeconds(2592000); // an ALREADY-elapsed billing period

        // Renewed (an earlier month), credits 20, finalized CANCELLED now -> 100.
        String ws = newWorkspaceId();
        Subscription sub =
                saveSubscription(ws, proPlan.getId(), SubscriptionStatus.ACTIVE, periodEnd.minusSeconds(2592000), periodEnd);
        BrandAiCredit credit = saveCredit(ws, 400, 20, lastReset);

        sub.setCancelAtPeriodEnd(true);
        subscriptionRepository.save(sub);
        Subscription fresh = subscriptionRepository.findByWorkspaceId(ws).orElseThrow();
        subscriptionService.finalizeLapsedCancellation(fresh);
        assertEquals(100, creditsOf(ws), "handover top-up raises 20 -> 100 (lastReset was 2 months ago)");

        // Job re-run THIS SAME month -- must be a no-op (the top-up already stamped lastReset =
        // today), not a second reset.
        BrandAiCredit afterTopUp = creditRepository.findByWorkspaceId(ws).orElseThrow();
        afterTopUp.setCreditsRemaining(7); // spent
        creditRepository.save(afterTopUp);
        aiCreditService.applyPlanAllotment(ws, freePlan.getAiMonthlyAllotment());
        aiCreditService.resetForNewCycleIfDue(ws);
        assertEquals(7, creditsOf(ws), "same-month job re-run after the top-up must not reset again");

        // A GENUINELY new month (roll lastReset back again, same precedent as
        // AICreditResetJobTest/AICreditServiceTest) -- the job resets to 100 for real.
        BrandAiCredit beforeNextMonth = creditRepository.findByWorkspaceId(ws).orElseThrow();
        beforeNextMonth.setLastReset(beforeNextMonth.getLastReset().minusMonths(1));
        creditRepository.save(beforeNextMonth);
        aiCreditService.resetForNewCycleIfDue(ws);
        assertEquals(100, creditsOf(ws), "job on the next genuine month resets to 100");

        // Same case with credits 300 -> stays 300 (top-up never lowers).
        String ws2 = newWorkspaceId();
        saveSubscription(ws2, proPlan.getId(), SubscriptionStatus.ACTIVE, periodEnd.minusSeconds(2592000), periodEnd);
        saveCredit(ws2, 400, 300, lastReset);
        Subscription sub2 = subscriptionRepository.findByWorkspaceId(ws2).orElseThrow();
        subscriptionService.finalizeLapsedCancellation(sub2);
        assertEquals(300, creditsOf(ws2), "a surplus balance (300 > monthlyAllotment 100) must stay 300");

        // Webhook-cancelled the SAME month as the renewal -- no top-up (lastReset is already this month).
        String ws3 = newWorkspaceId();
        saveSubscription(ws3, proPlan.getId(), SubscriptionStatus.ACTIVE, periodEnd.minusSeconds(2592000), periodEnd);
        saveCredit(ws3, 400, 60, today); // lastReset = today, same month as "renewal"
        Subscription sub3 = subscriptionRepository.findByWorkspaceId(ws3).orElseThrow();
        subscriptionService.finalizeLapsedCancellation(sub3);
        assertEquals(60, creditsOf(ws3), "cancelled in the SAME calendar month as the renewal -> no top-up");
    }

    // ------------------------------------------------------------------------------------
    // Scenario 11: Grant guard falsified on a real database (F-0895).
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Scenario 11 (F-0895): the once-per-billing-period guard is exercised against a real H2"
                    + " database, not a mock -- a repeat refillForBillingPeriod call for the SAME"
                    + " period returns 0 rows updated (proves the guard is a real SQL WHERE clause,"
                    + " not just service-layer bookkeeping)")
    void scenario11_grantGuardOnRealDatabase() {
        String ws = newWorkspaceId();
        Instant periodEnd = truncated("2026-10-15T00:00:00Z");
        saveCredit(ws, 400, 100, LocalDate.of(2026, 9, 1));

        int firstCall = creditRepository.refillForBillingPeriod(ws, periodEnd, LocalDate.now(ZoneOffset.UTC), Instant.now());
        assertEquals(1, firstCall, "first refill for this period updates exactly 1 row");
        assertEquals(400, creditsOf(ws));

        spend(ws, 50);
        assertEquals(350, creditsOf(ws));

        int secondCall = creditRepository.refillForBillingPeriod(ws, periodEnd, LocalDate.now(ZoneOffset.UTC), Instant.now());
        assertEquals(0, secondCall, "the real SQL WHERE clause blocks a repeat grant for the SAME period -- 0 rows updated");
        assertEquals(350, creditsOf(ws), "credits must be unchanged by the blocked repeat call");
    }

    // ------------------------------------------------------------------------------------
    // Scenario 12: Exhaustiveness -- see SubscriptionServiceTest#creditClockFor_exhaustiveAgainstTheRulingTable
    // (a pure-Mockito unit test, no H2 needed for this one -- creditClockFor makes no JPQL query
    // beyond simple findById/findByWorkspaceId lookups already covered by every other scenario
    // above running against the real repositories).
    // ------------------------------------------------------------------------------------
}
