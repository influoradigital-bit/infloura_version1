package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md §4) — the creator credit catalogue. One active pack for v1
 * (owner ruling R2): {@code PACK_60}, 60 credits for Rs 249 (24900 paise), GST-inclusive.
 */
@Entity
@Table(name = "creator_credit_packs")
public class CreatorCreditPack {

    @Id
    @Column(length = 26)
    private String id;

    @Column(nullable = false, length = 32)
    private String code;

    @Column(nullable = false)
    private int credits;

    @Column(name = "price_paise", nullable = false)
    private int pricePaise;

    @Column(name = "gst_inclusive", nullable = false)
    private boolean gstInclusive;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

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

    public int getCredits() {
        return credits;
    }

    public int getPricePaise() {
        return pricePaise;
    }

    public boolean isGstInclusive() {
        return gstInclusive;
    }

    public boolean isActive() {
        return active;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
