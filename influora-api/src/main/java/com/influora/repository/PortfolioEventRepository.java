package com.influora.repository;

import com.influora.domain.entity.PortfolioEvent;
import com.influora.domain.enums.PortfolioEventType;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PortfolioEventRepository extends JpaRepository<PortfolioEvent, String> {

    /** All-time count of one event type for a creator (e.g. media-kit downloads). */
    long countByCreatorProfileIdAndEventType(String creatorProfileId, PortfolioEventType eventType);

    /**
     * Events of one type in the trailing window — {@code occurredAt > after}. Powers
     * {@code PortfolioAnalyticsResponse.PageViews.last30Days}. Keyed by {@code creator_profiles.id}
     * (the same id the service writes), backed by {@code idx_portfolio_events_profile_type_time}.
     */
    long countByCreatorProfileIdAndEventTypeAndOccurredAtAfter(
            String creatorProfileId, PortfolioEventType eventType, Instant after);

    /**
     * Events of one type in the preceding window {@code [start, end)} — the denominator for the
     * period-over-period {@code deltaPercent}.
     */
    long countByCreatorProfileIdAndEventTypeAndOccurredAtBetween(
            String creatorProfileId, PortfolioEventType eventType, Instant start, Instant end);
}
