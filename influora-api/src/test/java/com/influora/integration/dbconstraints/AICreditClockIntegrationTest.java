package com.influora.integration.dbconstraints;

import static org.assertj.core.api.Assertions.assertThat;

import com.influora.common.Ulids;
import com.influora.domain.entity.BrandAiCredit;
import com.influora.domain.entity.Plan;
import com.influora.domain.entity.Subscription;
import com.influora.domain.enums.SubscriptionStatus;
import com.influora.repository.BrandAiCreditRepository;
import com.influora.repository.SubscriptionRepository;
import com.influora.service.billing.PlanService;
import com.influora.service.billing.SubscriptionService;
import com.influora.service.meera.AICreditService;
import com.influora.testsupport.AbstractIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T-GOLIVE-0918-R2 CREDITS-2 [vikram · 2026-09-18] -- the Testcontainers MySQL twin
 * {@code wiki/decisions/2026-09-18-ai-credit-clock.md} §4/§5 scenario 11 requires, closing round-1
 * defect "MEDIUM (influora-api/src/test -- missing AICreditClockIntegrationTest)". H2's {@code
 * MODE=MySQL} (the harness {@code AICreditClockScenarioTest}/{@code AICreditClockRepairRoundTest}
 * both use) emulates MySQL's SQL dialect but not its storage engine -- specifically, it does not
 * reproduce a real MySQL {@code TIMESTAMP} column's silent truncation of fractional seconds on
 * write. Only a genuine MySQL engine can prove the §4 "precision" requirement: that {@link
 * AICreditService#refillForBillingPeriod}'s {@code periodEnd.truncatedTo(ChronoUnit.SECONDS)} call
 * actually produces the SAME value MySQL itself stores for a {@code TIMESTAMP} column, so a repeat
 * call binding the original (nanosecond-bearing) in-memory {@code Instant} still matches the
 * stored, rounded guard marker -- an H2 {@code TIMESTAMP} column does not implicitly round away
 * fractional seconds the way MySQL's does, so a Java-side truncation bug could pass every H2 test
 * in this lane and still silently re-grant on every call against production MySQL.
 *
 * <p>Follows the established convention exactly: extends {@link AbstractIntegrationTest} (MySQL
 * 8.0.40 singleton container, real Flyway migrations, {@code DockerAvailableCondition} skipping
 * where Docker is unreachable) -- same precedent as {@code AICreditRaceIntegrationTest}.
 *
 * <p><b>[READ BEFORE TRUSTING A GREEN OR SKIPPED RUN LOCALLY]</b> Like its sibling classes, this
 * one is SKIPPED (not failed) wherever the Docker daemon is unreachable -- including this repo's
 * Windows sandbox, where {@code docker ps} fails outright. This class was written and compiled,
 * NOT run, in that environment -- <b>NOT PROVEN locally.</b> A "Skipped" result here is not
 * evidence the precision handling is correct; a CI runner with Docker is what actually proves it.
 *   Source: wiki/decisions/2026-09-18-ai-credit-clock.md §4, §5 (scenario 11); go-live round 2
 *   CREDITS-2 defect (d)
 */
class AICreditClockIntegrationTest extends AbstractIntegrationTest {

    @Autowired private AICreditService aiCreditService;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private BrandAiCreditRepository creditRepository;
    @Autowired private PlanService planService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<String> seededWorkspaceIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (String workspaceId : seededWorkspaceIds) {
            jdbcTemplate.update("DELETE FROM brand_ai_credits WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM subscriptions WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM workspaces WHERE id = ?", workspaceId);
        }
    }

    private String seedWorkspace() {
        String workspaceId = Ulids.newUlid();
        jdbcTemplate.update(
                "INSERT INTO workspaces (id, name, slug) VALUES (?, 'AICreditClock IT Brand', ?)",
                workspaceId,
                "aicc-it-" + workspaceId.toLowerCase());
        seededWorkspaceIds.add(workspaceId);
        return workspaceId;
    }

    @Test
    @DisplayName(
            "scenario 11: refillForBillingPeriod truncates a nanosecond-bearing periodEnd to whole"
                    + " seconds, and a repeat call with the SAME in-memory Instant is still a no-op"
                    + " against a REAL MySQL TIMESTAMP column -- not just against H2's")
    void refillForBillingPeriodMatchesRealMysqlTimestampRoundingOfANanosecondPeriodEnd() {
        String workspaceId = seedWorkspace();
        Plan proPlan = planService.getProPlan();

        // A comp-path-style periodEnd built from Instant.now() carries sub-second precision, per
        // the ruling's §4 note on SubscriptionService's comp horizon (Instant.now() has
        // nanoseconds; subscriptions.current_period_end / brand_ai_credits.credit_grant_period_end
        // do not).
        Instant periodEndWithNanos = Instant.now().plusSeconds(2_592_000).plusNanos(123_456_789);

        subscriptionRepository.save(
                Subscription.builder()
                        .id(Ulids.newUlid())
                        .workspaceId(workspaceId)
                        .planId(proPlan.getId())
                        .status(SubscriptionStatus.ACTIVE)
                        .razorpaySubscriptionId("sub_" + workspaceId)
                        .currentPeriodStart(Instant.now())
                        .currentPeriodEnd(periodEndWithNanos)
                        .cancelAtPeriodEnd(false)
                        .build());

        creditRepository.save(
                BrandAiCredit.builder()
                        .workspaceId(workspaceId)
                        .planAllotment(400)
                        .creditsRemaining(0)
                        .cycleStart(LocalDate.now())
                        .lastReset(LocalDate.now())
                        .build());

        assertThat(subscriptionService.creditClockFor(workspaceId))
                .isEqualTo(SubscriptionService.CreditClock.BILLING_PERIOD);

        // First refill -- grants the full 400, and stores the guard marker as MySQL rounds it (no
        // fractional seconds on a real TIMESTAMP column).
        aiCreditService.refillForBillingPeriod(workspaceId, periodEndWithNanos);
        assertThat(creditsRemainingOf(workspaceId)).isEqualTo(400);

        // Spend, then call AGAIN with the exact SAME nanosecond-bearing Instant (e.g. a duplicate
        // webhook redelivery or a repeat reconcile call reusing the in-memory Subscription's own
        // currentPeriodEnd). If AICreditService#refillForBillingPeriod's truncation to whole
        // seconds were ever removed or done wrong, the bound :periodEnd (still carrying nanos)
        // would never equal the value MySQL actually stored for the first call, and the guard's
        // `creditGrantPeriodEnd < :periodEnd` would keep matching -- re-granting the full allotment
        // on every call instead of holding at 37.
        jdbcTemplate.update(
                "UPDATE brand_ai_credits SET credits_remaining = 37 WHERE workspace_id = ?", workspaceId);

        aiCreditService.refillForBillingPeriod(workspaceId, periodEndWithNanos);
        assertThat(creditsRemainingOf(workspaceId))
                .as(
                        "a repeat call with the SAME (nanosecond-bearing) periodEnd must be a no-op"
                                + " against a REAL MySQL TIMESTAMP column -- proves the Java-side"
                                + " truncation to whole seconds actually matches what MySQL stored")
                .isEqualTo(37);
    }

    private int creditsRemainingOf(String workspaceId) {
        Integer credits =
                jdbcTemplate.queryForObject(
                        "SELECT credits_remaining FROM brand_ai_credits WHERE workspace_id = ?",
                        Integer.class,
                        workspaceId);
        return credits == null ? -1 : credits;
    }
}
