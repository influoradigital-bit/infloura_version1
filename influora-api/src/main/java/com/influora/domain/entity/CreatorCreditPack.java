package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * T-CREATOR-CREDITS-SEARCH K1 [vikram] -- CREDITS-SPEC.md §2.3, §2.5.
 *
 * <p>Credit pack catalogue row, DB-seeded (V20260912100200) by precedent -- {@code plans} is
 * seeded by {@code V55__seed_billing_plans.sql}; there is no yml plan config for either
 * catalogue.
 *
 * <p>{@code credits} is TENTHS of a credit (§0 rule 11): the seeded rows are 500 / 1000 / 3000,
 * which a creator sees as 50.0 / 100.0 / 300.0. {@code pricePaise} is unrelated to the credit
 * unit -- it was already the smallest INR unit.
 */
@Entity
@Table(name = "creator_credit_packs")
public class CreatorCreditPack {

    @Id
    @Column(length = 26)
    private String id;

    @Column(length = 20)
    private String code;

    @Column(length = 60)
    private String name;

    /** TENTHS of a credit (§0 rule 11). */
    @Column(nullable = false)
    private int credits;

    /** GST-inclusive, what the creator pays. Unrelated to the credit unit. */
    @Column(name = "price_paise", nullable = false)
    private int pricePaise;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CreatorCreditPack() {}

    public String getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public int getCredits() {
        return credits;
    }

    public int getPricePaise() {
        return pricePaise;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final CreatorCreditPack p = new CreatorCreditPack();

        public Builder id(String id) {
            p.id = id;
            return this;
        }

        public Builder code(String code) {
            p.code = code;
            return this;
        }

        public Builder name(String name) {
            p.name = name;
            return this;
        }

        public Builder credits(int credits) {
            p.credits = credits;
            return this;
        }

        public Builder pricePaise(int pricePaise) {
            p.pricePaise = pricePaise;
            return this;
        }

        public Builder sortOrder(int sortOrder) {
            p.sortOrder = sortOrder;
            return this;
        }

        public Builder active(boolean active) {
            p.active = active;
            return this;
        }

        public CreatorCreditPack build() {
            Instant now = Instant.now();
            p.createdAt = now;
            p.updatedAt = now;
            return p;
        }
    }
}
