package com.influora.integration.dbconstraints;

import static org.assertj.core.api.Assertions.assertThat;

import com.influora.common.Ulids;
import com.influora.config.CreatorCreditProperties;
import com.influora.domain.entity.CreatorCreditAccount;
import com.influora.domain.entity.CreatorCreditGrant;
import com.influora.domain.entity.CreatorCreditOrder;
import com.influora.domain.entity.CreatorCreditPack;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.MetaOAuthToken;
import com.influora.domain.enums.ChargeKind;
import com.influora.domain.enums.CreatorCreditOrderStatus;
import com.influora.domain.enums.CreditBucket;
import com.influora.repository.CreatorCreditAccountRepository;
import com.influora.repository.CreatorCreditGrantRepository;
import com.influora.repository.CreatorCreditLedgerRepository;
import com.influora.repository.CreatorCreditOrderRepository;
import com.influora.repository.CreatorCreditPackRepository;
import com.influora.repository.CreatorCreditWelcomeClaimRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.service.credits.ChargeResult;
import com.influora.service.credits.CreatorCreditOrderService;
import com.influora.service.credits.CreatorCreditService;
import com.influora.testsupport.AbstractIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
import org.springframework.test.context.TestPropertySource;

/**
 * T-CREATOR-CREDITS-V2 round 2 (SPEC.md §12, A10/A11/A24/A27) — REAL {@code @SpringBootTest} +
 * Testcontainers MySQL proof that {@link CreatorCreditService}'s account-row lock, and {@link
 * CreatorCreditOrderService#confirmPaid}'s order-row lock, actually serialize genuinely concurrent
 * transactions. Same rationale and same "singleton container, deliberately NOT {@code
 * @Transactional}, real committing worker threads" shape as this package's sibling {@code
 * AICreditRaceIntegrationTest} and {@code com.influora.service.ApplicationHistoryLockStallIntegrationTest}
 * — a Mockito test on a mocked repository can only prove a method was CALLED, never that two real,
 * concurrently-committing MySQL transactions against the SAME locked row actually interleave
 * correctly. An H2/mock substitute proves nothing about commit-order interleaving under a real
 * {@code SELECT ... FOR UPDATE}.
 *
 * <p>{@code influora.creator-credits.enabled=true} is set here (the property's own default is
 * {@code false}, R8) — every method in this class is specifically testing the charge/release/
 * grant/purchase machinery that only runs when the flag is on.
 *
 * <p><b>[READ BEFORE TRUSTING A GREEN OR SKIPPED RUN LOCALLY]</b> Like its siblings, this class is
 * SKIPPED (not failed) wherever the Docker daemon is unreachable, which is this repo's Windows
 * sandbox. It was written and compiled, NOT run, in that environment.
 */
@TestPropertySource(
        properties = {
            "influora.creator-credits.enabled=true",
            // Same reasoning as ApplicationHistoryLockStallIntegrationTest: these tests deliberately
            // race real transactions against a real lock; a long default InnoDB lock-wait timeout
            // would only make a genuine regression (deadlock/stall) slow to fail instead of fast.
            "spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout = 10",
            "spring.cache.type=none"
        })
class CreatorCreditConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired private CreatorCreditService creatorCreditService;
    @Autowired private CreatorCreditOrderService creatorCreditOrderService;
    @Autowired private CreatorCreditAccountRepository accountRepository;
    @Autowired private CreatorCreditGrantRepository grantRepository;
    @Autowired private CreatorCreditLedgerRepository ledgerRepository;
    @Autowired private CreatorCreditWelcomeClaimRepository welcomeClaimRepository;
    @Autowired private CreatorCreditOrderRepository orderRepository;
    @Autowired private CreatorCreditPackRepository packRepository;
    @Autowired private CreatorProfileRepository creatorProfileRepository;
    @Autowired private MetaOAuthTokenRepository metaOAuthTokenRepository;
    @Autowired private CreatorCreditProperties properties;
    @Autowired private Clock clock;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<String> seededUserIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (String userId : seededUserIds) {
            jdbcTemplate.update("DELETE FROM creator_credit_orders WHERE creator_user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM creator_credit_ledger WHERE creator_user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM creator_credit_grants WHERE creator_user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM creator_credit_welcome_claims WHERE creator_user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM creator_credit_accounts WHERE creator_user_id = ?", userId);
            jdbcTemplate.update(
                    "DELETE FROM meta_oauth_tokens WHERE creator_profile_id IN"
                            + " (SELECT id FROM creator_profiles WHERE user_id = ?)",
                    userId);
            jdbcTemplate.update("DELETE FROM creator_profiles WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
        seededUserIds.clear();
    }

    // ------------------------------------------------------------------
    // A10 — CreatorCreditConcurrencyIntegrationTest#noOverdraftUnderParallelCharges
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "A10 (K-02): 20 parallel 1-credit charges against a balance of 5 -> exactly 5 CHARGED, 15"
                    + " refused (INSUFFICIENT, the 402 case), and the ledger's DEBIT_TURN sum is exactly"
                    + " -5 -- the account row lock must serialize every racer, never let two overlapping"
                    + " transactions both observe 'enough remaining' for the same credit")
    void noOverdraftUnderParallelCharges() throws Exception {
        String creatorUserId = seedCreatorUser();
        seedAccount(creatorUserId);
        CreatorCreditGrant grant = seedPaidGrant(creatorUserId, 5, null);

        int racers = 20;
        List<String> turnIds = new ArrayList<>();
        for (int i = 0; i < racers; i++) {
            turnIds.add(Ulids.newUlid());
        }

        List<ChargeResult> results = runConcurrently(racers, i -> creatorCreditService.charge(
                creatorUserId, ChargeKind.TURN, turnIds.get(i)));

        long charged = results.stream().filter(ChargeResult::charged).count();
        long insufficient =
                results.stream().filter(r -> r.outcome() == ChargeResult.Outcome.INSUFFICIENT).count();
        long other = results.size() - charged - insufficient;

        assertThat(other).as("every racer must be either CHARGED or INSUFFICIENT, nothing else").isZero();
        assertThat(charged)
                .as("exactly 5 of the 20 racers may succeed against a balance of 5 -- more would be an"
                        + " overdraft, fewer would be a false rejection")
                .isEqualTo(5);
        assertThat(insufficient).as("the remaining 15 must be refused, not silently dropped").isEqualTo(15);

        CreatorCreditGrant reloaded =
                grantRepository.findById(grant.getId()).orElseThrow();
        assertThat(reloaded.getCreditsRemaining())
                .as("the grant must land at exactly 0 -- never negative (overdraft), never positive (a"
                        + " charge that succeeded but never actually debited)")
                .isEqualTo(0);

        Integer debitSum =
                jdbcTemplate.queryForObject(
                        "SELECT COALESCE(SUM(delta), 0) FROM creator_credit_ledger WHERE creator_user_id"
                                + " = ? AND reason = 'DEBIT_TURN'",
                        Integer.class,
                        creatorUserId);
        assertThat(debitSum).as("the ledger's DEBIT_TURN rows must sum to exactly -5").isEqualTo(-5);

        Integer debitRowCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM creator_credit_ledger WHERE creator_user_id = ? AND reason"
                                + " = 'DEBIT_TURN'",
                        Integer.class,
                        creatorUserId);
        assertThat(debitRowCount)
                .as("exactly 5 DEBIT_TURN rows -- one per charged racer, none double-posted")
                .isEqualTo(5);
    }

    // ------------------------------------------------------------------
    // A11 — CreatorCreditConcurrencyIntegrationTest#dailyCapHoldsUnderParallelCharges
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "A11 (K-03): 100 credits available, daily_used already at 29, 10 parallel 1-credit sends"
                    + " -> exactly 1 succeeds (the last credit under the 30/day cap) and daily_used lands"
                    + " at exactly 30 -- the cap check and the debit must happen under the SAME account"
                    + " lock, or two racers can both read daily_used=29 and both squeeze under the cap")
    void dailyCapHoldsUnderParallelCharges() throws Exception {
        String creatorUserId = seedCreatorUser();
        LocalDate today = LocalDate.now(clock.withZone(properties.zoneId()));
        CreatorCreditAccount account = seedAccount(creatorUserId);
        account.rollDayIfNeeded(today);
        account.addDailyUsed(29);
        accountRepository.saveAndFlush(account);
        CreatorCreditGrant grant = seedPaidGrant(creatorUserId, 100, null);

        int racers = 10;
        List<String> turnIds = new ArrayList<>();
        for (int i = 0; i < racers; i++) {
            turnIds.add(Ulids.newUlid());
        }

        List<ChargeResult> results = runConcurrently(racers, i -> creatorCreditService.charge(
                creatorUserId, ChargeKind.TURN, turnIds.get(i)));

        long charged = results.stream().filter(ChargeResult::charged).count();
        long dailyCapped =
                results.stream().filter(r -> r.outcome() == ChargeResult.Outcome.DAILY_CAP).count();
        long other = results.size() - charged - dailyCapped;

        assertThat(other).as("every racer must be either CHARGED or DAILY_CAP, nothing else").isZero();
        assertThat(charged)
                .as("only the single remaining slot under the 30/day cap may succeed")
                .isEqualTo(1);
        assertThat(dailyCapped)
                .as("the other 9 racers must be refused with DAILY_CAP (the 429 case), not silently"
                        + " allowed through or dropped")
                .isEqualTo(9);

        CreatorCreditAccount reloadedAccount =
                accountRepository.findById(creatorUserId).orElseThrow();
        assertThat(reloadedAccount.getDailyUsed())
                .as("daily_used must land at exactly 30 -- never 31+ (cap breached) and never stuck"
                        + " below 30 (a charge that reported CHARGED but never actually bumped the"
                        + " counter)")
                .isEqualTo(30);

        CreatorCreditGrant reloadedGrant = grantRepository.findById(grant.getId()).orElseThrow();
        assertThat(reloadedGrant.getCreditsRemaining())
                .as("exactly 1 credit debited from the 100-credit grant")
                .isEqualTo(99);
    }

    // ------------------------------------------------------------------
    // A24 — CreatorCreditConcurrencyIntegrationTest#welcomeClaimRace
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "A24 (K-12): two DIFFERENT creators both connect the SAME Instagram business account id"
                    + " and both race grantWelcome() at once -> exactly ONE of them gets the 40-credit"
                    + " welcome grant, never both (an IG id is the anti-farming key) and never zero (a"
                    + " genuine first-time connect must not be starved by the other racer's claim)")
    void welcomeClaimRace() throws Exception {
        String creatorA = seedCreatorUser();
        String creatorB = seedCreatorUser();
        String sharedIgId = "ig-race-" + Ulids.newUlid();
        wireConnectedInstagram(creatorA, sharedIgId);
        wireConnectedInstagram(creatorB, sharedIgId);
        // grantWelcome() itself calls ensureAccount/lockAccount -- no need to pre-seed the account row.

        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Void> callA =
                    () -> {
                        startTogether.await(10, TimeUnit.SECONDS);
                        creatorCreditService.grantWelcome(creatorA);
                        return null;
                    };
            Callable<Void> callB =
                    () -> {
                        startTogether.await(10, TimeUnit.SECONDS);
                        creatorCreditService.grantWelcome(creatorB);
                        return null;
                    };
            Future<Void> futureA = pool.submit(callA);
            Future<Void> futureB = pool.submit(callB);
            futureA.get(30, TimeUnit.SECONDS);
            futureB.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        boolean aGranted =
                accountRepository.findById(creatorA).map(a -> a.getWelcomeGrantedAt() != null).orElse(false);
        boolean bGranted =
                accountRepository.findById(creatorB).map(a -> a.getWelcomeGrantedAt() != null).orElse(false);
        assertThat(aGranted ^ bGranted)
                .as("exactly one of the two creators must have welcome_granted_at set, never both and"
                        + " never neither")
                .isTrue();

        Integer freeSignupGrantCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM creator_credit_grants WHERE creator_user_id IN (?, ?) AND"
                                + " bucket = 'FREE_SIGNUP'",
                        Integer.class,
                        creatorA,
                        creatorB);
        assertThat(freeSignupGrantCount)
                .as("exactly one FREE_SIGNUP grant across BOTH creators combined")
                .isEqualTo(1);

        Integer totalWelcomeCredits =
                jdbcTemplate.queryForObject(
                        "SELECT COALESCE(SUM(credits_granted), 0) FROM creator_credit_grants WHERE"
                                + " creator_user_id IN (?, ?) AND bucket = 'FREE_SIGNUP'",
                        Integer.class,
                        creatorA,
                        creatorB);
        assertThat(totalWelcomeCredits)
                .as("the welcome grant must be handed out exactly once (40), never twice (80) for the"
                        + " same Instagram id")
                .isEqualTo(properties.getWelcomeGrant());

        String winner = aGranted ? creatorA : creatorB;
        String claimKey = "ig:" + sharedIgId;
        assertThat(welcomeClaimRepository.findById(claimKey))
                .as("the permanent ig: claim row for the shared Instagram id must belong to the racer"
                        + " that actually won the grant")
                .hasValueSatisfying(claim -> assertThat(claim.getCreatorUserId()).isEqualTo(winner));
    }

    // ------------------------------------------------------------------
    // A27 — CreatorCreditConcurrencyIntegrationTest#webhookVsReconcileCreditsOnce
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "A27 (K-10): a Razorpay webhook delivery and the reconciliation job's repair sweep both"
                    + " call confirmPaid for the SAME order+payment at the same moment -> the order is"
                    + " credited exactly once (60 credits, one PAID grant, one GRANT_PURCHASE ledger"
                    + " row) -- the order row lock plus the CREDITED short-circuit must make the loser"
                    + " a true no-op, not a second grant")
    void webhookVsReconcileCreditsOnce() throws Exception {
        String creatorUserId = seedCreatorUser();
        seedAccount(creatorUserId);
        CreatorCreditPack pack =
                packRepository
                        .findByCodeAndActiveTrue("PACK_60")
                        .orElseThrow(() -> new IllegalStateException("PACK_60 seed row missing (V20260921110400)"));

        CreatorCreditOrder order =
                CreatorCreditOrder.newPending(Ulids.newUlid(), creatorUserId, pack, "idem-" + Ulids.newUlid());
        order.setRazorpayOrderId("order_test_FAKE" + Ulids.newUlid().substring(0, 8));
        orderRepository.saveAndFlush(order);

        String orderId = order.getId();
        String razorpayOrderId = order.getRazorpayOrderId();
        // The public repo never carries a real key -- an obviously-fake payment id, same shape as
        // Razorpay's own ("pay_") but unmistakably a test fixture.
        String paymentId = "pay_test_FAKE" + Ulids.newUlid().substring(0, 8);
        long amountPaise = pack.getPricePaise();

        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<CreatorCreditOrder> confirm =
                    () -> {
                        startTogether.await(10, TimeUnit.SECONDS);
                        return creatorCreditOrderService.confirmPaid(
                                orderId, paymentId, razorpayOrderId, amountPaise, "INR");
                    };
            // Simulates the webhook delivery and the reconciliation job's repair sweep both resolving
            // the SAME already-captured payment for this order at (almost) the same instant.
            Future<CreatorCreditOrder> webhook = pool.submit(confirm);
            Future<CreatorCreditOrder> reconcile = pool.submit(confirm);
            webhook.get(30, TimeUnit.SECONDS);
            reconcile.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        CreatorCreditOrder reloaded = orderRepository.findById(orderId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(CreatorCreditOrderStatus.CREDITED);
        assertThat(reloaded.getRazorpayPaymentId()).isEqualTo(paymentId);

        Integer paidGrantCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM creator_credit_grants WHERE creator_user_id = ? AND bucket"
                                + " = 'PAID'",
                        Integer.class,
                        creatorUserId);
        assertThat(paidGrantCount)
                .as("exactly one PAID grant must exist -- the racing confirmPaid call must be a true"
                        + " no-op, not a second grant")
                .isEqualTo(1);

        Integer totalPaidCredits =
                jdbcTemplate.queryForObject(
                        "SELECT COALESCE(SUM(credits_granted), 0) FROM creator_credit_grants WHERE"
                                + " creator_user_id = ? AND bucket = 'PAID'",
                        Integer.class,
                        creatorUserId);
        assertThat(totalPaidCredits).as("60 credited once, never 120").isEqualTo(60);

        Integer grantPurchaseLedgerRows =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM creator_credit_ledger WHERE creator_user_id = ? AND reason"
                                + " = 'GRANT_PURCHASE'",
                        Integer.class,
                        creatorUserId);
        assertThat(grantPurchaseLedgerRows).as("exactly one GRANT_PURCHASE ledger row").isEqualTo(1);

        int spendable =
                grantRepository.findSpendable(creatorUserId, clock.instant()).stream()
                        .mapToInt(CreatorCreditGrant::getCreditsRemaining)
                        .sum();
        assertThat(spendable).as("the creator's spendable balance is 60, not 120").isEqualTo(60);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** users -> (committed). Every creator_credit_accounts row FKs to users(id) (fk_creator_credit_accounts_user). */
    private String seedCreatorUser() {
        String userId = Ulids.newUlid();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, user_type, status) VALUES (?, ?, 'CREATOR', 'ACTIVE')",
                userId,
                "creator-" + userId.toLowerCase() + "@test.influora");
        seededUserIds.add(userId);
        return userId;
    }

    private CreatorCreditAccount seedAccount(String creatorUserId) {
        return accountRepository
                .findById(creatorUserId)
                .orElseGet(() -> accountRepository.saveAndFlush(CreatorCreditAccount.newAccount(creatorUserId)));
    }

    private CreatorCreditGrant seedPaidGrant(String creatorUserId, int credits, Instant expiresAt) {
        CreatorCreditGrant grant =
                CreatorCreditGrant.of(
                        Ulids.newUlid(),
                        creatorUserId,
                        CreditBucket.PAID,
                        credits,
                        clock.instant(),
                        expiresAt,
                        "order:" + Ulids.newUlid());
        return grantRepository.saveAndFlush(grant);
    }

    /** A real creator_profiles row + a real, non-revoked, workspace-null meta_oauth_tokens row carrying igId. */
    private void wireConnectedInstagram(String creatorUserId, String igId) {
        CreatorProfile profile =
                CreatorProfile.newForUser(Ulids.newUlid(), creatorUserId, "Race Creator " + creatorUserId);
        creatorProfileRepository.saveAndFlush(profile);

        MetaOAuthToken token =
                MetaOAuthToken.builder()
                        .id(Ulids.newUlid())
                        .creatorProfileId(profile.getId())
                        .igBusinessAccountId(igId)
                        .encryptedAccessToken("enc-test-FAKE")
                        .expiresAt(clock.instant().plusSeconds(3600))
                        .build();
        metaOAuthTokenRepository.saveAndFlush(token);
    }

    /** Runs {@code n} racers together via a CyclicBarrier, real threads, real commits -- returns each racer's result in submission order. */
    private <T> List<T> runConcurrently(int n, java.util.function.IntFunction<T> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CyclicBarrier startTogether = new CyclicBarrier(n);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int idx = i;
                futures.add(
                        pool.submit(
                                () -> {
                                    startTogether.await(10, TimeUnit.SECONDS);
                                    return work.apply(idx);
                                }));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
