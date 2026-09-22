package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md §3-6) — the per-creator lock anchor. Every credit mutation takes a
 * {@code SELECT ... FOR UPDATE} on this row ({@code CreatorCreditAccountRepository#findByIdForUpdate})
 * before touching any grant or ledger row for that creator. Holds NO balance itself — only lock and
 * roll-forward bookkeeping (the daily cap counter and the lazily-materialised monthly-grant marker).
 */
@Entity
@Table(name = "creator_credit_accounts")
public class CreatorCreditAccount {

    @Id
    @Column(name = "creator_user_id", length = 26)
    private String creatorUserId;

    @Column(name = "welcome_granted_at")
    private Instant welcomeGrantedAt;

    /** IST {@code YearMonth} as {@code "YYYY-MM"} — the last month the monthly 15 was materialised for. */
    @Column(name = "monthly_period", length = 7)
    private String monthlyPeriod;

    /** IST calendar date the {@link #dailyUsed} counter applies to. */
    @Column(name = "daily_date")
    private LocalDate dailyDate;

    @Column(name = "daily_used", nullable = false)
    private int dailyUsed;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CreatorCreditAccount() {}

    public static CreatorCreditAccount newAccount(String creatorUserId) {
        CreatorCreditAccount a = new CreatorCreditAccount();
        a.creatorUserId = creatorUserId;
        Instant now = Instant.now();
        a.createdAt = now;
        a.updatedAt = now;
        return a;
    }

    public String getCreatorUserId() {
        return creatorUserId;
    }

    public Instant getWelcomeGrantedAt() {
        return welcomeGrantedAt;
    }

    public void markWelcomeGranted(Instant when) {
        this.welcomeGrantedAt = when;
        touch();
    }

    public String getMonthlyPeriod() {
        return monthlyPeriod;
    }

    public void setMonthlyPeriod(String monthlyPeriod) {
        this.monthlyPeriod = monthlyPeriod;
        touch();
    }

    public LocalDate getDailyDate() {
        return dailyDate;
    }

    public int getDailyUsed() {
        return dailyUsed;
    }

    /** Rolls the day forward if {@code today} is not the currently tracked day — resets the counter to 0. */
    public void rollDayIfNeeded(LocalDate today) {
        if (!today.equals(dailyDate)) {
            this.dailyDate = today;
            this.dailyUsed = 0;
            touch();
        }
    }

    public void addDailyUsed(int amount) {
        this.dailyUsed += amount;
        touch();
    }

    /** Refunds against today's counter only — floored at 0 (K-14: never go negative, never touch a stale day). */
    public void refundDailyUsed(int amount) {
        this.dailyUsed = Math.max(0, this.dailyUsed - amount);
        touch();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
