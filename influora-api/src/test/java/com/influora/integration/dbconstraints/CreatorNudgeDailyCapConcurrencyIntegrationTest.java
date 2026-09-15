package com.influora.integration.dbconstraints;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.influora.common.Ulids;
import com.influora.integration.ai.CreatorSuggestionAiClient;
import com.influora.service.creatorcopilot.CreatorNudgeService;
import com.influora.service.creatorcopilot.CreatorNudgeService.SuggestionResult;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * F-0785 — REAL {@code @SpringBootTest} + Testcontainers-MySQL proof that two simultaneous {@code
 * getSuggestion} calls for ONE creator both return the SAME row cleanly, instead of one clean row
 * and one uncaught HTTP 500.
 *
 * <p><b>Why this cannot be a Mockito test, and why it cannot be H2 either.</b> The defect was never
 * about the mapping inside {@code catch (DataIntegrityViolationException)} — it was about WHERE the
 * exception is thrown. {@code CreatorNudgeLog} has a caller-assigned {@code @Id}, no {@code
 * @Version} and no {@code Persistable}, so {@code SimpleJpaRepository.save()} routes through {@code
 * em.merge()}, which only SCHEDULES the INSERT; under the old {@code @Transactional getSuggestion}
 * the {@code uq_creator_nudge_day} violation therefore fired at the proxy's commit, AFTER the try
 * block had exited, and the catch was unreachable dead code. A Mockito test stubbing {@code save()}
 * to throw proves the catch body compiles and would read as evidence the race is handled while it
 * demonstrably was not. Only a real commit against a real engine can falsify that. H2 cannot
 * substitute either: the constraint is a MySQL 8 generated-STORED-column trick ({@code shown_day
 * DATE GENERATED ALWAYS AS (DATE(shown_at)) STORED} plus {@code UNIQUE (creator_profile_id,
 * shown_day)}, V20260721140000) — the same reason {@link FestivalBoxCouponConstraintIntegrationTest}
 * and {@link DatabaseConstraintIntegrationTest} are Testcontainers-backed rather than slice tests.
 * This class follows their convention exactly ({@link AbstractIntegrationTest}, MySQL 8.0.40
 * singleton container, {@code DockerAvailableCondition} skipping where Docker is unreachable).
 *
 * <p><b>Why this class is deliberately NOT {@code @Transactional}</b> (unlike its two sibling
 * constraint tests, which are). A test-managed transaction binds to the TEST thread only. The two
 * racing worker threads below run in their own transactions and would not see rows this thread had
 * merely written-but-not-committed — the seeded {@code creator_profiles}/{@code trends} parents
 * would be invisible to them and the FKs would fail, so the test would go red for a reason that has
 * nothing to do with F-0785. More fundamentally, a unique-constraint race can only happen between
 * two genuine COMMITS; a test that never commits cannot produce one. Cleanup is therefore explicit
 * in {@link #cleanUp()}.
 *
 * <p><b>[READ BEFORE TRUSTING A GREEN OR SKIPPED RUN LOCALLY]</b> Like both sibling classes, this
 * one is skipped (not failed) wherever the Docker daemon is unreachable — which includes this
 * repo's Windows sandbox, where {@code docker ps} fails outright. <b>It was written and compiled,
 * NOT run, in that environment.</b> A "Skipped" line for this class is NOT evidence that F-0785 is
 * fixed; it is evidence that nothing was checked.
 */
class CreatorNudgeDailyCapConcurrencyIntegrationTest extends AbstractIntegrationTest {

    /** How many independent races to attempt. Each uses a FRESH creator, because the constraint
     * under test permits exactly one row per creator per day — a second attempt against the same
     * creator would be served by the idempotent read and could never reach the write at all. */
    private static final int RACE_ATTEMPTS = 10;

    private static final String THEME_A = "strength";
    private static final String THEME_B = "action";
    private static final String THEMES_JSON = "[\"" + THEME_A + "\",\"" + THEME_B + "\"]";

    @Autowired private CreatorNudgeService nudgeService;
    @Autowired private JdbcTemplate jdbcTemplate;

    /**
     * Stubbed so the phrasing step is instant and deterministic. Two reasons, both about making the
     * race REAL rather than about convenience: a live call to influora-ai would (a) not be
     * reachable from CI anyway, and (b) put a multi-second, highly variable network round trip
     * between the two threads' idempotent reads and their writes, which is exactly the window that
     * would let them serialise and quietly stop racing.
     */
    @MockBean private CreatorSuggestionAiClient aiClient;

    private final List<String> seededCreatorProfileIds = new ArrayList<>();
    private final List<String> seededUserIds = new ArrayList<>();
    private final List<String> seededTrendIds = new ArrayList<>();

    private Logger serviceLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachLogAppender() {
        serviceLogger = (Logger) LoggerFactory.getLogger(CreatorNudgeService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        serviceLogger.addAppender(logAppender);
    }

    @AfterEach
    void cleanUp() {
        if (serviceLogger != null) {
            serviceLogger.detachAppender(logAppender);
        }
        // Child-first, so the FKs stay satisfied throughout.
        for (String creatorProfileId : seededCreatorProfileIds) {
            jdbcTemplate.update(
                    "DELETE FROM creator_nudge_log WHERE creator_profile_id = ?", creatorProfileId);
        }
        for (String creatorProfileId : seededCreatorProfileIds) {
            jdbcTemplate.update("DELETE FROM creator_profiles WHERE id = ?", creatorProfileId);
        }
        for (String userId : seededUserIds) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
        for (String trendId : seededTrendIds) {
            jdbcTemplate.update("DELETE FROM trends WHERE id = ?", trendId);
        }
    }

    @Test
    @DisplayName(
            "F-0785: two simultaneous getSuggestion calls for one creator both return the SAME row"
                    + " and neither 500s — the uq_creator_nudge_day violation is recovered, not"
                    + " propagated")
    void twoConcurrentFirstOfDayCallsBothReturnTheSameRow() throws Exception {
        String trendId = seedTrend();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int racesActuallyObserved = 0;

        try {
            for (int attempt = 0; attempt < RACE_ATTEMPTS; attempt++) {
                String creatorProfileId = seedCreatorWithThemeTags();
                logAppender.list.clear();

                // Both threads park here and are released together, so neither has a head start.
                CyclicBarrier startTogether = new CyclicBarrier(2);
                Callable<SuggestionResult> call =
                        () -> {
                            startTogether.await(10, TimeUnit.SECONDS);
                            return nudgeService.getSuggestion(creatorProfileId);
                        };

                Future<SuggestionResult> first = pool.submit(call);
                Future<SuggestionResult> second = pool.submit(call);

                // THE CORE ASSERTION. Before the fix, exactly one of these two Future.get() calls
                // raised the uncaught DataIntegrityViolationException that reached the client as an
                // HTTP 500. Any throw here fails the test with the original stack trace attached.
                SuggestionResult firstResult = first.get(30, TimeUnit.SECONDS);
                SuggestionResult secondResult = second.get(30, TimeUnit.SECONDS);

                assertThat(firstResult.status())
                        .as("attempt %s: first caller must get a suggestion", attempt)
                        .isEqualTo("ready");
                assertThat(secondResult.status())
                        .as("attempt %s: second caller must get a suggestion, not an error", attempt)
                        .isEqualTo("ready");
                assertThat(firstResult.suggestion().id())
                        .as("attempt %s: both racers must converge on the SAME row", attempt)
                        .isEqualTo(secondResult.suggestion().id());
                assertThat(firstResult.suggestion().headline())
                        .as("attempt %s: same row means same copy", attempt)
                        .isEqualTo(secondResult.suggestion().headline());

                // The DB agrees: the per-day cap held, so the losing racer's row was never written.
                Integer rowCount =
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM creator_nudge_log WHERE creator_profile_id = ?",
                                Integer.class,
                                creatorProfileId);
                assertThat(rowCount)
                        .as("attempt %s: uq_creator_nudge_day must permit exactly one row", attempt)
                        .isEqualTo(1);

                if (raceRecoveryWasLogged()) {
                    racesActuallyObserved++;
                }
            }
        } finally {
            pool.shutdownNow();
        }

        // ANTI-VACUITY GUARD — the most important assertion in this class.
        //
        // Every assertion above is ALSO satisfied by the boring interleaving where thread A
        // finishes completely before thread B starts: B's idempotent-read-first then returns A's
        // row, same id, one DB row, no exception, all green — while never once reaching the write
        // that F-0785 is about. If that happened on all RACE_ATTEMPTS, this class verified NOTHING
        // and must say so out loud rather than report a green run.
        assertThat(racesActuallyObserved)
                .as(
                        "none of the %s attempts produced real contention: every pair serialised, so"
                                + " the uq_creator_nudge_day recovery path was never exercised and this"
                                + " run did NOT verify F-0785. Re-run; if this persists, the machine is"
                                + " too slow or too serial to create the race and the fix needs to be"
                                + " verified another way.",
                        RACE_ATTEMPTS)
                .isPositive();
    }

    /** True when the service logged that it lost the per-day-cap race — i.e. this attempt actually
     * reached the {@code DataIntegrityViolationException} recovery path rather than serialising. */
    private boolean raceRecoveryWasLogged() {
        return logAppender.list.stream()
                .anyMatch(
                        event ->
                                event.getLevel() == Level.INFO
                                        && event.getFormattedMessage().contains("lost the per-day-cap race"));
    }

    /** A creator whose {@code theme_tags} overlap {@link #THEMES_JSON} by 2, which is the default
     * {@code influora.creator-copilot.score-threshold} — so the trend below is guaranteed to clear
     * the bar and the service is guaranteed to reach the write. */
    private String seedCreatorWithThemeTags() {
        String userId = Ulids.newUlid();
        String creatorProfileId = Ulids.newUlid();

        jdbcTemplate.update(
                "INSERT INTO users (id, user_type, status) VALUES (?, 'CREATOR', 'ACTIVE')", userId);
        jdbcTemplate.update(
                "INSERT INTO creator_profiles (id, user_id, display_name, currency, is_verified,"
                        + " is_discoverable, total_followers, theme_tags, created_at, updated_at) VALUES"
                        + " (?, ?, 'Race Creator', 'INR', false, true, 0, ?, NOW(6), NOW(6))",
                creatorProfileId,
                userId,
                THEMES_JSON);

        seededUserIds.add(userId);
        seededCreatorProfileIds.add(creatorProfileId);
        return creatorProfileId;
    }

    /** One active trend whose themes match the seeded creators'. {@code expires_at} is a day out so
     * {@code TrendRepository.findActive} returns it. */
    private String seedTrend() {
        String trendId = Ulids.newUlid();
        jdbcTemplate.update(
                "INSERT INTO trends (id, trend_text, source, region, detected_date, peak_window_days,"
                        + " expires_at, themes, campaign_type, created_at, updated_at) VALUES"
                        + " (?, 'Morning mobility routine everyone is trying', ?, 'IN', CURRENT_DATE, 3,"
                        + " DATE_ADD(NOW(6), INTERVAL 1 DAY), ?, 'HYPE', NOW(6), NOW(6))",
                trendId,
                "{\"name\":\"integration-test\"}",
                THEMES_JSON);
        seededTrendIds.add(trendId);
        return trendId;
    }
}
