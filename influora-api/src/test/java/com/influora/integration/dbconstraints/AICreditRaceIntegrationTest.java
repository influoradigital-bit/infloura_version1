package com.influora.integration.dbconstraints;

import static org.assertj.core.api.Assertions.assertThat;

import com.influora.common.ApiException;
import com.influora.common.Ulids;
import com.influora.service.meera.AICreditService;
import com.influora.testsupport.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T-S3-F0879-0917 [vikram · 2026-09-17] — REAL {@code @SpringBootTest} + Testcontainers-MySQL
 * proof for the "Credit race" finding (assignments-0917-subscription.md S3, tech N4/T-5): two
 * concurrent {@code AICreditService#tryConsume} calls against a workspace with exactly 1 credit
 * remaining must never BOTH succeed.
 *
 * <p><b>Why this cannot be a Mockito test.</b> {@code AICreditServiceTest} (unit-level, same
 * commit) proves the decrement itself is a conditional {@code UPDATE ... WHERE credits_remaining
 * >= :cost} — but a mocked repository can only prove {@code tryDecrement} is CALLED, never that
 * two REAL, concurrently-committing transactions against the SAME row actually serialize through
 * it correctly. The defect this test targets was never in {@code tryDecrement}'s own SQL (which
 * was always correctly conditional) — it was that {@code tryConsume} used to ALSO issue a blind,
 * full-row {@code creditRepository.save(credit)} for the P4 daily-action counter bump, using the
 * managed entity's creditsRemaining as read at the START of the transaction. Under real
 * concurrent commits, that blind write could land on the DB AFTER a concurrent transaction's
 * atomic decrement had already landed, silently reverting creditsRemaining back to its
 * pre-decrement value and letting a second turn spend a credit that was already spent. Only a
 * real MySQL engine running two genuinely concurrent transactions can falsify that class of bug;
 * an H2/mock substitute proves nothing about commit-order interleaving. Same rationale as {@link
 * CreatorNudgeDailyCapConcurrencyIntegrationTest} and the sibling classes it cites.
 *
 * <p>Follows the established convention exactly: {@link AbstractIntegrationTest} (MySQL 8.0.40
 * singleton container, {@code DockerAvailableCondition} skipping where Docker is unreachable),
 * deliberately NOT {@code @Transactional} (the two racing worker threads need their own real
 * commits — a test-managed transaction would only be visible to the test thread).
 *
 * <p><b>[READ BEFORE TRUSTING A GREEN OR SKIPPED RUN LOCALLY]</b> Like its sibling classes, this
 * one is skipped (not failed) wherever the Docker daemon is unreachable — including this repo's
 * Windows sandbox, where {@code docker ps} fails outright. <b>This class was written and compiled,
 * NOT run, in that environment — NOT PROVEN locally.</b> A "Skipped" result is not evidence the
 * race is fixed; S7 (Meera, Docker CI) is what actually runs this.
 */
class AICreditRaceIntegrationTest extends AbstractIntegrationTest {

    /** Independent races to attempt — each against a FRESH workspace, since a workspace already
     * drained to 0 by a prior attempt would trivially reject both callers without exercising the
     * "exactly one succeeds while credits are still available to race over" boundary. */
    private static final int RACE_ATTEMPTS = 10;

    @Autowired private AICreditService aiCreditService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<String> seededWorkspaceIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (String workspaceId : seededWorkspaceIds) {
            jdbcTemplate.update("DELETE FROM brand_ai_credits WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM workspaces WHERE id = ?", workspaceId);
        }
    }

    @Test
    @DisplayName(
            "T-5/tech N4: two concurrent tryConsume(cost=1) calls against a workspace with exactly 1"
                    + " credit remaining -- exactly ONE succeeds, the other gets CREDITS_EXHAUSTED, and"
                    + " creditsRemaining never goes negative or double-spends")
    void twoConcurrentTurnsAtOneCreditNeverBothSucceed() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            for (int attempt = 0; attempt < RACE_ATTEMPTS; attempt++) {
                String workspaceId = seedWorkspaceWithOneCredit();

                CyclicBarrier startTogether = new CyclicBarrier(2);
                Callable<Boolean> call =
                        () -> {
                            startTogether.await(10, TimeUnit.SECONDS);
                            try {
                                aiCreditService.tryConsume(workspaceId, 1);
                                return true; // succeeded
                            } catch (ApiException e) {
                                if (!"CREDITS_EXHAUSTED".equals(e.getCode())) {
                                    throw e;
                                }
                                return false; // correctly rejected
                            }
                        };

                Future<Boolean> first = pool.submit(call);
                Future<Boolean> second = pool.submit(call);

                boolean firstSucceeded = first.get(30, TimeUnit.SECONDS);
                boolean secondSucceeded = second.get(30, TimeUnit.SECONDS);

                // THE CORE ASSERTION. Before the fix, both racers could observe success (double
                // spend of the single remaining credit) because tryConsume's blind full-row
                // save(credit) for the daily-action bump could clobber a concurrent tryDecrement.
                int successCount = (firstSucceeded ? 1 : 0) + (secondSucceeded ? 1 : 0);
                assertThat(successCount)
                        .as(
                                "attempt %s: exactly one of the two racers must succeed against a single"
                                        + " remaining credit -- 0 means a false rejection, 2 means the"
                                        + " credit was double-spent",
                                attempt)
                        .isEqualTo(1);

                Integer creditsRemaining =
                        jdbcTemplate.queryForObject(
                                "SELECT credits_remaining FROM brand_ai_credits WHERE workspace_id = ?",
                                Integer.class,
                                workspaceId);
                assertThat(creditsRemaining)
                        .as("attempt %s: creditsRemaining must land at exactly 0, never negative", attempt)
                        .isEqualTo(0);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private String seedWorkspaceWithOneCredit() {
        String workspaceId = Ulids.newUlid();
        jdbcTemplate.update(
                "INSERT INTO workspaces (id, name, slug) VALUES (?, 'Race Brand', ?)",
                workspaceId,
                "race-brand-" + workspaceId.toLowerCase());
        seededWorkspaceIds.add(workspaceId);

        // ensureInitialized would default to 100 -- seed directly at exactly 1 so the very next
        // decrement of cost 1 is the last one either racer could win.
        jdbcTemplate.update(
                "INSERT INTO brand_ai_credits (workspace_id, credits_remaining, monthly_allotment,"
                        + " plan_allotment, loyalty_bonus, cycle_start, last_reset) VALUES"
                        + " (?, 1, 100, 100, 0, CURRENT_DATE, CURRENT_DATE)",
                workspaceId);
        return workspaceId;
    }
}
