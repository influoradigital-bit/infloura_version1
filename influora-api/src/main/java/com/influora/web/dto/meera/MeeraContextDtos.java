package com.influora.web.dto.meera;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * Wire DTOs for {@code POST /internal/meera/context} (Platform-AI Phase 1, Wave 1a — Priya
 * ruling A2). Consumed directly by influora-ai's {@code app/prompt/assembler.py} (Wave 2), which
 * is a Python service expecting {@code snake_case} keys — every field here is explicitly
 * {@code @JsonProperty}-annotated to snake_case rather than relying on Jackson's default
 * camelCase record-component naming that the REST of this package's DTOs use for the browser.
 *
 * <p><b>This is the ONE reconciliation point for the Spring (camelCase) <-> Python (snake_case)
 * field-name seam (Wave 1c, Priya A1 change-req; DECIDED by Ash's field-name-seam ruling).</b>
 * {@link com.influora.service.meera.BrandContextAssembler#assemble} still emits
 * {@code brandName}/{@code toneProfile}/{@code nicheTags} for its existing (currently-dead)
 * caller ({@code MeeraSessionService#doSendTurn}'s {@code TurnResult#sanitizedContext}, never
 * touched here) — that method is UNCHANGED. This new response type's wire keys are the canonical
 * vocabulary Python's {@code influora-ai/app/prompt/assembler.py::build_block_b} ALREADY consumes
 * (battle-tested, baked into the cached Block-B format + eval fixtures) — Ash ruled Spring adapts
 * to Python's names, not the other way around: {@code display_name}, {@code tone_dial}, {@code
 * brand_color}, {@code niche_tags}, {@code product_catalog}, {@code past_campaign_summary}, {@code
 * credit_state{mode,credits_remaining}}. {@code brand_color} is extracted here from {@code
 * brand_aesthetic}'s {@code accent_color} sub-field (see {@code AnalyzeSiteTriggerService#toCallback}
 * — that's where {@code accent_color} is written into {@code brandAestheticJson}); {@code
 * brand_aesthetic} itself stays as an extra (non-canonical but harmless) field for anything reading
 * the raw aesthetic blob.
 */
public final class MeeraContextDtos {

    private MeeraContextDtos() {}

    /** {@code audience} is currently restricted to {@code "BRAND"} — CREATOR is Phase 3, guarded not populated. */
    public record ContextRequest(
            @JsonProperty("workspace_id") @NotBlank String workspaceId,
            @JsonProperty("audience") @NotBlank String audience) {}

    /** One line of the SYSTEM+CUSTOM template digest — see A1's {@code template_digest} allow-list entry. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TemplateDigestEntry(
            @JsonProperty("name") String name,
            @JsonProperty("campaign_type") String campaignType,
            @JsonProperty("budget_band") String budgetBand,
            @JsonProperty("key_requirements") String keyRequirements) {}

    /** One line of the last-N-campaigns summary — see A1's {@code past_campaign_summary} allow-list entry. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PastCampaignEntry(
            @JsonProperty("type") String type,
            @JsonProperty("creator_count") int creatorCount,
            @JsonProperty("funded") boolean funded) {}

    /** Live AI-credit state — never wallet/escrow balances, only the AI-turn budget (A1 allow-list). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreditState(
            @JsonProperty("mode") String mode, @JsonProperty("credits_remaining") int creditsRemaining) {}

    /**
     * The BRAND-audience allow-listed context payload (Priya A1's locked BRAND allow-list,
     * verbatim). {@code product_catalog} entries are pre-filtered to {@code name}/{@code price}/
     * {@code currency} only (A1: "productCatalogJson — name/price/currency only") — never the raw
     * scraped catalog JSON, which may carry extra fields not vetted for prompt exposure.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ContextResponse(
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("industry") String industry,
            @JsonProperty("website_url") String websiteUrl,
            @JsonProperty("niche_tags") List<String> nicheTags,
            @JsonProperty("tone_dial") Object toneDial,
            @JsonProperty("brand_color") String brandColor,
            @JsonProperty("brand_aesthetic") Object brandAesthetic,
            @JsonProperty("product_catalog") List<java.util.Map<String, Object>> productCatalog,
            @JsonProperty("competitor_urls") Object competitorUrls,
            @JsonProperty("analysis_status") String analysisStatus,
            @JsonProperty("template_digest") List<TemplateDigestEntry> templateDigest,
            @JsonProperty("past_campaign_summary") List<PastCampaignEntry> pastCampaignSummary,
            @JsonProperty("credit_state") CreditState creditState) {}
}
