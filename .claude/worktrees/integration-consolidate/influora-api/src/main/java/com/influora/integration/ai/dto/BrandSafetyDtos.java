package com.influora.integration.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Wire DTOs for influora-ai's {@code POST /internal/brand-safety} (Wave C task C2 contract,
 * consumed by C3). Field names/shape mirror {@code app/routes/brand_safety.py} and {@code
 * app/tools/schemas.py::ANALYZE_CREATOR_CONTENT_SCHEMA} exactly — see that file for the
 * authoritative contract (10 fixed GARM categories, 4 risk levels, 3 sentiment values).
 */
public final class BrandSafetyDtos {

    private BrandSafetyDtos() {}

    /** Request body — {@code {workspace_id, items: [{content_id, caption, media_type?, posted_at?}]}}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BrandSafetyRequest(
            @JsonProperty("workspace_id") String workspaceId, List<ContentItem> items) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ContentItem(
            @JsonProperty("content_id") String contentId,
            String caption,
            @JsonProperty("media_type") String mediaType,
            @JsonProperty("posted_at") String postedAt) {}

    /** Response envelope — {@code {success: true, data: {items: [...]}}} on 200. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BrandSafetyResponse(boolean success, Data data) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Data(List<ClassifiedItem> items) {}
    }

    /**
     * One classified result. {@code garmFlags} always covers all 10 GARM categories (Python
     * validates this server-side before returning, per {@code _validate_model_result} —
     * {@code app/routes/brand_safety.py}), {@code brandSafetyScore} 0-100, {@code sentimentScore}
     * -1.0..1.0.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ClassifiedItem(
            @JsonProperty("content_id") String contentId,
            @JsonProperty("garm_flags") List<GarmFlag> garmFlags,
            @JsonProperty("content_sentiment") String contentSentiment,
            @JsonProperty("sentiment_score") Double sentimentScore,
            @JsonProperty("brand_safety_score") Double brandSafetyScore,
            @JsonProperty("overall_rationale") String overallRationale) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GarmFlag(String category, String risk, String rationale) {}

    /** Error envelope influora-ai returns on 4xx/5xx — {@code {detail: {code, message}}} (FastAPI
     * HTTPException convention used by every route in that service). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ErrorResponse(Detail detail) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Detail(String code, String message) {}
    }
}
