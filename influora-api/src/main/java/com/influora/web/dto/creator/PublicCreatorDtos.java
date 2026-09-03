package com.influora.web.dto.creator;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/**
 * T-MEERA-CREATOR-PHASE-A (SPEC.md 2.8, A9) — {@code GET /public/creators/{username}/verified}.
 * NO rates, NO floors, NO PAN/GSTIN — a public, unauthenticated, indexable page. Wire keys are
 * snake_case, matching SPEC.md's literal response example.
 */
public final class PublicCreatorDtos {

    private PublicCreatorDtos() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VerifiedMetrics(
            @JsonProperty("followers") long followers,
            @JsonProperty("reach_30d") Long reach30d,
            @JsonProperty("engagement_rate") java.math.BigDecimal engagementRate,
            @JsonProperty("verified_at") Instant verifiedAt) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VerifiedProfileResponse(
            @JsonProperty("username") String username,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("city") String city,
            @JsonProperty("categories") List<String> categories,
            @JsonProperty("verified_metrics") VerifiedMetrics verifiedMetrics,
            @JsonProperty("platform_deal_count") long platformDealCount,
            @JsonProperty("snapshot_date") Instant snapshotDate) {}
}
