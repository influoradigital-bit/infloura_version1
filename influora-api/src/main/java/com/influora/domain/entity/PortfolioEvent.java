package com.influora.domain.entity;

import com.influora.domain.enums.PortfolioEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A single portfolio-analytics event (V20260718120000 {@code portfolio_events}) — Priya CTO ruling
 * 2026-07-18. One typed, append-only row per tracked interaction (a public view, a media-kit
 * download, later a link click); counted per {@link PortfolioEventType} to populate
 * {@code PortfolioAnalyticsResponse}.
 *
 * <p><b>Immutable append-only rows</b>, matching {@link AudienceDemographics}/{@code CreatorScore}
 * (not an upsert-latest counter like {@code CouponCode.usageCount}). There is deliberately no
 * mutator — an event is a fact, never edited.
 *
 * <p>{@code creatorProfileId} is the {@code creator_profiles.id} (NOT {@code users.id}): the service
 * records with {@code profile.getId()} and reads back with the same key. {@code visitorHash} is
 * nullable and reserved for a future unique-visitor/dedup pass — v1 records raw events.
 */
@Entity
@Table(name = "portfolio_events")
public class PortfolioEvent {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "creator_profile_id", nullable = false, length = 26)
    private String creatorProfileId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 24)
    private PortfolioEventType eventType;

    @Column(name = "occurred_at", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant occurredAt;

    @Column(name = "visitor_hash", length = 64)
    private String visitorHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PortfolioEvent() {}

    public String getId() {
        return id;
    }

    public String getCreatorProfileId() {
        return creatorProfileId;
    }

    public PortfolioEventType getEventType() {
        return eventType;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getVisitorHash() {
        return visitorHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final PortfolioEvent e = new PortfolioEvent();

        public Builder id(String id) {
            e.id = id;
            return this;
        }

        public Builder creatorProfileId(String creatorProfileId) {
            e.creatorProfileId = creatorProfileId;
            return this;
        }

        public Builder eventType(PortfolioEventType eventType) {
            e.eventType = eventType;
            return this;
        }

        public Builder occurredAt(Instant occurredAt) {
            e.occurredAt = occurredAt;
            return this;
        }

        public Builder visitorHash(String visitorHash) {
            e.visitorHash = visitorHash;
            return this;
        }

        public PortfolioEvent build() {
            if (e.occurredAt == null) {
                e.occurredAt = Instant.now();
            }
            e.createdAt = Instant.now();
            return e;
        }
    }
}
