package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * T-CREATOR-CREDITS-SEARCH K1 [vikram] -- CREDITS-SPEC.md §2.1, §2.5.
 *
 * <p>1:1 with {@code users} (creator rows only) -- the PK IS the FK, no surrogate id, same shape
 * as {@link BrandAiCredit}'s 1:1-with-workspaces PK. R1: the whole creator Meera path keys on the
 * creator's own {@code users.id}, not {@code creator_profiles.id} (CREDITS-SPEC §1 R1).
 *
 * <p><b>Every credit column here is TENTHS of a credit</b> (CREDITS-SPEC §0 rule 11): {@code
 * monthlyRemaining}, {@code monthlyAllotment} and {@code purchasedBalance} hold 400 for 40.0
 * credits, 25 for 2.5, etc. {@code dailyActionsUsed} and {@code freeSearchesUsed} are NOT tenths
 * -- they count events (R7; §4A.3).
 *
 * <p><b>No setters for the balance/counter columns on purpose.</b> {@code monthlyRemaining},
 * {@code purchasedBalance}, {@code dailyActionsUsed} and {@code freeSearchesUsed} are mutated
 * ONLY through {@link com.influora.repository.CreatorAiCreditRepository}'s atomic {@code
 * @Modifying} UPDATE queries (tryDebit / refund / addPurchased / refundDailyActions /
 * tryClaimFreeSearch), never via a full-row {@code save()} of a managed instance -- that blind
 * full-row-save shape is exactly the class of bug {@code BrandAiCreditRepository#bumpDailyActions}
 * 's javadoc documents as a real production incident (a stale in-memory {@code creditsRemaining}
 * silently reverting a concurrent decrement). Giving this entity a {@code setMonthlyRemaining}
 * would invite the same mistake here, so it is deliberately not provided.
 */
@Entity
@Table(name = "creator_ai_credits")
public class CreatorAiCredit {

    @Id
    @Column(name = "creator_user_id", length = 26)
    private String creatorUserId;

    @Column(name = "monthly_remaining", nullable = false)
    private int monthlyRemaining;

    @Column(name = "monthly_allotment", nullable = false)
    private int monthlyAllotment;

    @Column(name = "purchased_balance", nullable = false)
    private int purchasedBalance;

    @Column(name = "cycle_start", nullable = false)
    private LocalDate cycleStart;

    @Column(name = "last_reset", nullable = false)
    private LocalDate lastReset;

    @Column(name = "signup_grant_at")
    private Instant signupGrantAt;

    /** R7 daily hard-cap counter (500 actions/day), mirroring the brand ledger abuse guard. NOT tenths. */
    @Column(name = "daily_actions_used", nullable = false)
    private int dailyActionsUsed;

    @Column(name = "daily_actions_date")
    private LocalDate dailyActionsDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Amendment A4 (§4A.3): weekly free-search counter. NOT tenths -- counts searches. */
    @Column(name = "free_searches_used", nullable = false)
    private int freeSearchesUsed;

    /**
     * NULL on a fresh row, and that NULL is load-bearing: {@code
     * CreatorAiCreditRepository#tryClaimFreeSearch}'s WHERE clause has a dedicated {@code IS NULL}
     * term because in SQL {@code NULL <> :weekStart} evaluates to {@code NULL}, not {@code TRUE} --
     * without it the very first free search of every creator's life would match nothing and be
     * silently billed. See that method's javadoc.
     */
    @Column(name = "free_search_week_start")
    private LocalDate freeSearchWeekStart;

    protected CreatorAiCredit() {}

    public String getCreatorUserId() {
        return creatorUserId;
    }

    public int getMonthlyRemaining() {
        return monthlyRemaining;
    }

    public int getMonthlyAllotment() {
        return monthlyAllotment;
    }

    public int getPurchasedBalance() {
        return purchasedBalance;
    }

    public LocalDate getCycleStart() {
        return cycleStart;
    }

    public LocalDate getLastReset() {
        return lastReset;
    }

    public Instant getSignupGrantAt() {
        return signupGrantAt;
    }

    public int getDailyActionsUsed() {
        return dailyActionsUsed;
    }

    public LocalDate getDailyActionsDate() {
        return dailyActionsDate;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public int getFreeSearchesUsed() {
        return freeSearchesUsed;
    }

    public LocalDate getFreeSearchWeekStart() {
        return freeSearchWeekStart;
    }

    /** Total spendable balance across both buckets, in TENTHS. */
    public int total() {
        return monthlyRemaining + purchasedBalance;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final CreatorAiCredit c = new CreatorAiCredit();

        public Builder creatorUserId(String creatorUserId) {
            c.creatorUserId = creatorUserId;
            return this;
        }

        public Builder monthlyRemaining(int monthlyRemaining) {
            c.monthlyRemaining = monthlyRemaining;
            return this;
        }

        public Builder monthlyAllotment(int monthlyAllotment) {
            c.monthlyAllotment = monthlyAllotment;
            return this;
        }

        public Builder purchasedBalance(int purchasedBalance) {
            c.purchasedBalance = purchasedBalance;
            return this;
        }

        public Builder cycleStart(LocalDate cycleStart) {
            c.cycleStart = cycleStart;
            return this;
        }

        public Builder lastReset(LocalDate lastReset) {
            c.lastReset = lastReset;
            return this;
        }

        public Builder signupGrantAt(Instant signupGrantAt) {
            c.signupGrantAt = signupGrantAt;
            return this;
        }

        public Builder dailyActionsUsed(int dailyActionsUsed) {
            c.dailyActionsUsed = dailyActionsUsed;
            return this;
        }

        public Builder dailyActionsDate(LocalDate dailyActionsDate) {
            c.dailyActionsDate = dailyActionsDate;
            return this;
        }

        public Builder freeSearchesUsed(int freeSearchesUsed) {
            c.freeSearchesUsed = freeSearchesUsed;
            return this;
        }

        public Builder freeSearchWeekStart(LocalDate freeSearchWeekStart) {
            c.freeSearchWeekStart = freeSearchWeekStart;
            return this;
        }

        /**
         * Priya's correction to CREDITS-SPEC §2.5(a): use UTC everywhere on this entity, do NOT
         * copy {@code BrandAiCredit.Builder}'s system-zone {@code LocalDate.now()} -- {@code
         * cycleStart == null -> LocalDate.now(ZoneOffset.UTC)}, {@code lastReset == null ->
         * cycleStart}.
         */
        public CreatorAiCredit build() {
            Instant now = Instant.now();
            c.createdAt = now;
            c.updatedAt = now;
            if (c.cycleStart == null) {
                c.cycleStart = LocalDate.now(ZoneOffset.UTC);
            }
            if (c.lastReset == null) {
                c.lastReset = c.cycleStart;
            }
            return c;
        }
    }
}
