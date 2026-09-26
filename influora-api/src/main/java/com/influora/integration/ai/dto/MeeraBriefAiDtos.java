package com.influora.integration.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.influora.web.dto.brief.BriefDtos.BriefExtraction;

/**
 * Wire DTOs for influora-ai's {@code POST /internal/brief-extract}
 * (T-MEERA-CREATOR-PHASE-B, SPEC.md &sect;7.5).
 *
 * <p><b>Three request fields, and {@code workspace_id} is deliberately NOT one of them.</b> The
 * Python route authenticates with {@code verify_creator_token}, which is keyed on {@code
 * creator_profile_id}; a creator has no workspace, and sending one would be a field the route reads
 * as nothing while implying the caller thinks this is a brand surface.
 *
 * <p>{@code creator_profile_id} is a {@code creator_profiles.id} (SPEC.md &sect;0.8), not a {@code
 * users.id}. The Python side equality-checks it against the token's own {@code creator_profile_id}
 * claim, so a user id here fails auth outright rather than extracting the wrong creator's brief.
 *
 * <p><b>The response envelope is the whole reason this route returns HTTP 200 on failure.</b>
 * {@link ExtractResponse#success()} false with {@code error.code = CREATOR_MONTHLY_CAP_REACHED}
 * means the creator is over her own brief allowance; any other code means the model or the provider
 * could not produce an extraction. Those two must reach the creator as different sentences, and the
 * only thing distinguishing them is this code — see {@code CreatorBriefService} and SPEC.md
 * &sect;14.4.a.
 *
 * <p>{@code data} is the shared {@link BriefExtraction} contract of SPEC.md &sect;2.11 verbatim, so
 * the Python route's field names and this record's {@code @JsonProperty} names are one contract with
 * two implementations. {@code @JsonIgnoreProperties(ignoreUnknown = true)} on the envelope, not on
 * the extraction: a field the route adds to the envelope must not break parsing, but a field added
 * to the extraction is a contract change that should be made deliberately on both sides.
 */
public final class MeeraBriefAiDtos {

    private MeeraBriefAiDtos() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ExtractRequest(
            @JsonProperty("creator_profile_id") String creatorProfileId,
            @JsonProperty("raw_text") String rawText,
            @JsonProperty("creator_language") String creatorLanguage,
            /**
             * T-CREATOR-CREDITS-V2 (SPEC.md B20, C22) — the $12.00 brief-extract USD backstop,
             * sent only when {@code CREATOR_CREDITS_ENABLED} is on; omitted (NON_NULL) when it is
             * off, so influora-ai falls through to its own process-wide {@code
             * BRIEF_EXTRACT_MONTHLY_CAP_USD} default exactly as before this field existed.
             */
            @JsonProperty("brief_monthly_cap_usd") java.math.BigDecimal briefMonthlyCapUsd) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExtractResponse(
            @JsonProperty("success") boolean success,
            @JsonProperty("data") BriefExtraction data,
            @JsonProperty("error") ErrorDetail error) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record ErrorDetail(
                @JsonProperty("code") String code, @JsonProperty("message") String message) {}
    }
}
