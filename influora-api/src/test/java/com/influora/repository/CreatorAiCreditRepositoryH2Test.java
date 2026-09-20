package com.influora.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.influora.domain.entity.CreatorAiCredit;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;

/**
 * T-CREATOR-CREDITS-SEARCH K1 [vikram] -- real, executing proof of {@link
 * CreatorAiCreditRepository}'s five atomic {@code @Modifying} queries against a REAL H2 database
 * (MySQL compatibility mode) via {@code @DataJpaTest}, not mocked. No Docker on this machine (see
 * {@code BrandAiCreditRepositoryQueryTest} / {@code AICreditClockScenarioTest} javadocs for the
 * same precedent) -- H2 is the blocking, load-bearing proof, same as that class.
 *
 * <p>Precedent and harness shape: {@code AICreditClockScenarioTest}. {@code CreatorAiCredit} has
 * no {@code @ManyToOne} relation to {@code User} (a plain {@code @Column} FK, matching {@code
 * BrandAiCredit}'s shape to {@code Workspace}), so Hibernate's {@code ddl-auto=create-drop} schema
 * needs no {@code users} row to satisfy a foreign key -- only the entity's own table is created.
 *
 * <p>These queries all bind {@code updatedAt} via a passed-in {@code :now Instant} parameter
 * (see {@link CreatorAiCreditRepository}'s class javadoc for why that is a deliberate deviation
 * from CREDITS-SPEC.md §2.6's literal {@code CURRENT_TIMESTAMP} text), which is exactly what lets
 * this test execute them for real instead of only pinning their JPQL by reflection.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@EntityScan(basePackageClasses = CreatorAiCredit.class)
@EnableJpaRepositories(
        basePackageClasses = CreatorAiCreditRepository.class,
        excludeFilters =
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = "com\\.influora\\.repository\\.(?!CreatorAiCreditRepository$).*"))
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:creator_ai_credits_test;DB_CLOSE_DELAY=-1;MODE=MySQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
        })
class CreatorAiCreditRepositoryH2Test {

    @Autowired private CreatorAiCreditRepository repository;

    private static final String CREATOR_ID = "01HCREATOR000000000000001";

    private CreatorAiCredit save(int monthlyRemaining, int monthlyAllotment, int purchasedBalance) {
        return repository.save(
                CreatorAiCredit.builder()
                        .creatorUserId(CREATOR_ID)
                        .monthlyRemaining(monthlyRemaining)
                        .monthlyAllotment(monthlyAllotment)
                        .purchasedBalance(purchasedBalance)
                        .build());
    }

    // -----------------------------------------------------------------------------------------
    // tryDebit
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("tryDebit: a chat turn (1.0 credit = 10 tenths) from monthly succeeds and decrements exactly")
    void tryDebitChatTurnFromMonthlySucceeds() {
        save(400, 400, 300); // 40.0 monthly, 30.0 purchased

        int rows = repository.tryDebit(CREATOR_ID, 10, 0, Instant.now());

        assertEquals(1, rows, "sufficient monthly balance must debit exactly one row");
        CreatorAiCredit after = repository.findById(CREATOR_ID).orElseThrow();
        assertEquals(390, after.getMonthlyRemaining());
        assertEquals(300, after.getPurchasedBalance());
    }

    @Test
    @DisplayName("tryDebit: a 2.5-credit search (25 tenths) split across both buckets debits both exactly")
    void tryDebitSplitAcrossBothBucketsDebitsExactly() {
        save(10, 400, 300); // only 1.0 credit left monthly; the rest must come from purchased

        int rows = repository.tryDebit(CREATOR_ID, 10, 15, Instant.now());

        assertEquals(1, rows);
        CreatorAiCredit after = repository.findById(CREATOR_ID).orElseThrow();
        assertEquals(0, after.getMonthlyRemaining());
        assertEquals(285, after.getPurchasedBalance());
    }

    @Test
    @DisplayName("tryDebit: insufficient monthly balance is a no-op (0 rows), balance unchanged")
    void tryDebitInsufficientMonthlyIsNoOp() {
        save(5, 400, 0); // 0.5 credit left, cannot cover a 1.0-credit chat turn

        int rows = repository.tryDebit(CREATOR_ID, 10, 0, Instant.now());

        assertEquals(0, rows, "must refuse when monthlyRemaining < requested monthly part");
        CreatorAiCredit after = repository.findById(CREATOR_ID).orElseThrow();
        assertEquals(5, after.getMonthlyRemaining(), "balance must be untouched by a refused debit");
    }

    @Test
    @DisplayName("tryDebit: insufficient purchased balance is a no-op (0 rows) even if monthly alone would cover it")
    void tryDebitInsufficientPurchasedIsNoOp() {
        save(400, 400, 5); // plenty monthly, but the split asks for more purchased than exists

        int rows = repository.tryDebit(CREATOR_ID, 0, 10, Instant.now());

        assertEquals(0, rows);
        CreatorAiCredit after = repository.findById(CREATOR_ID).orElseThrow();
        assertEquals(400, after.getMonthlyRemaining());
        assertEquals(5, after.getPurchasedBalance());
    }

    @Test
    @DisplayName("tryDebit: exact-balance debit (down to zero) succeeds -- the guard is >=, not >")
    void tryDebitExactBalanceSucceeds() {
        save(10, 400, 0);

        int rows = repository.tryDebit(CREATOR_ID, 10, 0, Instant.now());

        assertEquals(1, rows);
        assertEquals(0, repository.findById(CREATOR_ID).orElseThrow().getMonthlyRemaining());
    }

    // -----------------------------------------------------------------------------------------
    // refund
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("refund: adds back to monthly and purchased when under the allotment")
    void refundAddsBackUnderAllotment() {
        save(390, 400, 285);

        repository.refund(CREATOR_ID, 10, 15, Instant.now());

        CreatorAiCredit after = repository.findById(CREATOR_ID).orElseThrow();
        assertEquals(400, after.getMonthlyRemaining());
        assertEquals(300, after.getPurchasedBalance());
    }

    @Test
    @DisplayName("refund: clamps the monthly bucket to monthlyAllotment -- a reset between charge and release must not overshoot")
    void refundClampsMonthlyToAllotment() {
        // Simulates: charged 10, then a monthly reset already refilled monthlyRemaining to the
        // full 400 allotment before the release/refund runs.
        save(400, 400, 0);

        repository.refund(CREATOR_ID, 10, 0, Instant.now());

        CreatorAiCredit after = repository.findById(CREATOR_ID).orElseThrow();
        assertEquals(
                400,
                after.getMonthlyRemaining(),
                "refund must clamp to monthlyAllotment, never push monthlyRemaining above it");
    }

    @Test
    @DisplayName("refund: the purchased bucket is never clamped -- it can exceed the monthly allotment number")
    void refundPurchasedNeverClamped() {
        save(0, 400, 590); // already holds more than the monthly allotment's worth

        repository.refund(CREATOR_ID, 0, 25, Instant.now());

        assertEquals(615, repository.findById(CREATOR_ID).orElseThrow().getPurchasedBalance());
    }

    // -----------------------------------------------------------------------------------------
    // addPurchased
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("addPurchased: adds to purchasedBalance only, never touches monthlyRemaining")
    void addPurchasedTouchesOnlyPurchasedBalance() {
        save(400, 400, 300);

        repository.addPurchased(CREATOR_ID, 500, Instant.now()); // a Starter pack, 50.0 credits

        CreatorAiCredit after = repository.findById(CREATOR_ID).orElseThrow();
        assertEquals(800, after.getPurchasedBalance());
        assertEquals(400, after.getMonthlyRemaining(), "addPurchased must never touch monthlyRemaining");
    }

    // -----------------------------------------------------------------------------------------
    // refundDailyActions
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("refundDailyActions: decrements when the date matches today's charge")
    void refundDailyActionsDecrementsOnMatchingDate() {
        LocalDate today = LocalDate.now();
        CreatorAiCredit credit = save(400, 400, 0);
        credit = CreatorAiCredit.builder()
                .creatorUserId(CREATOR_ID)
                .monthlyRemaining(400)
                .monthlyAllotment(400)
                .purchasedBalance(0)
                .dailyActionsUsed(3)
                .dailyActionsDate(today)
                .build();
        repository.save(credit);

        int rows = repository.refundDailyActions(CREATOR_ID, 1, today, Instant.now());

        assertEquals(1, rows);
        assertEquals(2, repository.findById(CREATOR_ID).orElseThrow().getDailyActionsUsed());
    }

    @Test
    @DisplayName("refundDailyActions: floors at 0, never goes negative")
    void refundDailyActionsFloorsAtZero() {
        LocalDate today = LocalDate.now();
        CreatorAiCredit credit =
                CreatorAiCredit.builder()
                        .creatorUserId(CREATOR_ID)
                        .monthlyRemaining(400)
                        .monthlyAllotment(400)
                        .purchasedBalance(0)
                        .dailyActionsUsed(1)
                        .dailyActionsDate(today)
                        .build();
        repository.save(credit);

        repository.refundDailyActions(CREATOR_ID, 5, today, Instant.now());

        assertEquals(0, repository.findById(CREATOR_ID).orElseThrow().getDailyActionsUsed());
    }

    @Test
    @DisplayName("refundDailyActions: a stale date (day already rolled over) is a no-op")
    void refundDailyActionsNoOpOnStaleDate() {
        CreatorAiCredit credit =
                CreatorAiCredit.builder()
                        .creatorUserId(CREATOR_ID)
                        .monthlyRemaining(400)
                        .monthlyAllotment(400)
                        .purchasedBalance(0)
                        .dailyActionsUsed(3)
                        .dailyActionsDate(LocalDate.now().minusDays(1))
                        .build();
        repository.save(credit);

        int rows = repository.refundDailyActions(CREATOR_ID, 1, LocalDate.now(), Instant.now());

        assertEquals(0, rows);
        assertEquals(3, repository.findById(CREATOR_ID).orElseThrow().getDailyActionsUsed());
    }

    // -----------------------------------------------------------------------------------------
    // tryClaimFreeSearch -- the NULL-handling defect this whole method exists to avoid
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "tryClaimFreeSearch: the FIRST free search of a creator's life claims successfully"
                    + " despite freeSearchWeekStart starting NULL (the exact bug the IS NULL term guards)")
    void tryClaimFreeSearchFirstEverSearchClaimsDespiteNullWeekStart() {
        save(400, 400, 0); // freeSearchWeekStart is NULL on a fresh row

        LocalDate weekStart = LocalDate.of(2026, 9, 21); // a Monday
        int rows = repository.tryClaimFreeSearch(CREATOR_ID, weekStart, 2, Instant.now());

        assertEquals(
                1,
                rows,
                "the very first free search must claim successfully -- if this is 0, the IS NULL"
                        + " term was dropped and every creator's first free search is silently billed");
        CreatorAiCredit after = repository.findById(CREATOR_ID).orElseThrow();
        assertEquals(1, after.getFreeSearchesUsed());
        assertEquals(weekStart, after.getFreeSearchWeekStart());
    }

    @Test
    @DisplayName("tryClaimFreeSearch: a second search in the same week, under the cap, claims and increments")
    void tryClaimFreeSearchSecondInSameWeekClaims() {
        LocalDate weekStart = LocalDate.of(2026, 9, 21);
        CreatorAiCredit credit =
                CreatorAiCredit.builder()
                        .creatorUserId(CREATOR_ID)
                        .monthlyRemaining(400)
                        .monthlyAllotment(400)
                        .purchasedBalance(0)
                        .freeSearchesUsed(1)
                        .freeSearchWeekStart(weekStart)
                        .build();
        repository.save(credit);

        int rows = repository.tryClaimFreeSearch(CREATOR_ID, weekStart, 2, Instant.now());

        assertEquals(1, rows);
        assertEquals(2, repository.findById(CREATOR_ID).orElseThrow().getFreeSearchesUsed());
    }

    @Test
    @DisplayName("tryClaimFreeSearch: the third search in the same week, at the cap, is refused (0 rows)")
    void tryClaimFreeSearchThirdInSameWeekRefused() {
        LocalDate weekStart = LocalDate.of(2026, 9, 21);
        CreatorAiCredit credit =
                CreatorAiCredit.builder()
                        .creatorUserId(CREATOR_ID)
                        .monthlyRemaining(400)
                        .monthlyAllotment(400)
                        .purchasedBalance(0)
                        .freeSearchesUsed(2)
                        .freeSearchWeekStart(weekStart)
                        .build();
        repository.save(credit);

        int rows = repository.tryClaimFreeSearch(CREATOR_ID, weekStart, 2, Instant.now());

        assertEquals(0, rows, "the 2-per-week free cap must refuse a third claim in the same week");
        assertEquals(
                2,
                repository.findById(CREATOR_ID).orElseThrow().getFreeSearchesUsed(),
                "a refused claim must not bump the counter");
    }

    @Test
    @DisplayName("tryClaimFreeSearch: a new week resets the counter to 1 regardless of last week's count")
    void tryClaimFreeSearchNewWeekResets() {
        LocalDate lastWeek = LocalDate.of(2026, 9, 14);
        LocalDate thisWeek = LocalDate.of(2026, 9, 21);
        CreatorAiCredit credit =
                CreatorAiCredit.builder()
                        .creatorUserId(CREATOR_ID)
                        .monthlyRemaining(400)
                        .monthlyAllotment(400)
                        .purchasedBalance(0)
                        .freeSearchesUsed(2) // spent last week's cap
                        .freeSearchWeekStart(lastWeek)
                        .build();
        repository.save(credit);

        int rows = repository.tryClaimFreeSearch(CREATOR_ID, thisWeek, 2, Instant.now());

        assertEquals(1, rows, "a new week must claim even though last week was fully spent");
        CreatorAiCredit after = repository.findById(CREATOR_ID).orElseThrow();
        assertEquals(1, after.getFreeSearchesUsed());
        assertEquals(thisWeek, after.getFreeSearchWeekStart());
    }
}
