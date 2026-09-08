package com.influora.web.dto.brief;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

/**
 * {@code BriefExtraction} is the shared wire contract (SPEC.md 2.11) produced by influora-ai's
 * brief-extraction route ({@code app/routes/brief_extract.py}) or, on AI failure, by the
 * deterministic {@code BriefFallbackExtractor} in this service. Consumed by both {@code
 * CreatorBriefService} (stored as {@code CreatorBrief.extractedJson}) and the frontend's brief
 * detail views, so every field name below is a wire contract, not an internal convenience — do
 * not rename without updating influora-ai and {@code src/}.
 *
 * <p>{@code deliverables[].type} values are the {@code
 * com.influora.service.rates.QuoteDeliverableType} pricing vocabulary (REEL, STATIC_POST,
 * STORY_SET, SHORT, YT_INTEGRATION, YT_DEDICATED, UGC_ONLY, OTHER), not the persisted, platform-
 * shaped {@code com.influora.domain.enums.DeliverableType}. {@code usage_channels} and {@code
 * exclusivity_scope} carry {@code UsageChannel}/{@code ExclusivityScope} enum names as plain
 * strings (kept as {@code String} here, not the enum type, so a value the extractor cannot map
 * confidently does not fail JSON deserialization of the whole brief). {@code category} keys off
 * {@code RateEstimationService.CATEGORY_MULTIPLIERS}. {@code regulated_category} is one of
 * {@code FINANCE, HEALTH, RMG, CRYPTO, ALCOHOL, TOBACCO} or {@code null}.
 */
public final class BriefDtos {

    private BriefDtos() {}

    /** One requested deliverable line, e.g. {@code {"type": "REEL", "qty": 1}}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DeliverableLine(
            @JsonProperty("type") String type,
            @JsonProperty("qty") int qty) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BriefExtraction(
            @JsonProperty("brand_name") String brandName,
            @JsonProperty("product") String product,
            @JsonProperty("category") String category,
            @JsonProperty("deliverables") List<DeliverableLine> deliverables,
            @JsonProperty("budget_inr") BigDecimal budgetInr,
            @JsonProperty("budget_stated") boolean budgetStated,
            @JsonProperty("barter_only") boolean barterOnly,
            @JsonProperty("barter_mrp_inr") BigDecimal barterMrpInr,
            @JsonProperty("deadline") String deadline,
            @JsonProperty("usage_months") Integer usageMonths,
            @JsonProperty("usage_perpetual") boolean usagePerpetual,
            @JsonProperty("usage_channels") List<String> usageChannels,
            @JsonProperty("exclusivity_days") Integer exclusivityDays,
            @JsonProperty("exclusivity_scope") String exclusivityScope,
            @JsonProperty("exclusivity_brands") List<String> exclusivityBrands,
            @JsonProperty("max_revisions") Integer maxRevisions,
            @JsonProperty("payment_terms") String paymentTerms,
            @JsonProperty("off_platform_payment_hint") boolean offPlatformPaymentHint,
            @JsonProperty("disclosure_hidden_hint") boolean disclosureHiddenHint,
            @JsonProperty("claims") List<String> claims,
            @JsonProperty("regulated_category") String regulatedCategory,
            @JsonProperty("vague_deliverables") boolean vagueDeliverables,
            @JsonProperty("summary_lines") List<String> summaryLines) {}
}
