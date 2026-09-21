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

    /**
     * SPEC.md &sect;3.8 — {@code POST /creator/briefs}. The cap matches
     * {@code CreatorBrief.MAX_RAW_TEXT_LENGTH}, and the entity re-caps AFTER sanitising, so stripped
     * markup in a pasted email body does not eat into the creator's real 8000 characters.
     */
    public record PasteBriefRequest(
            @jakarta.validation.constraints.NotBlank
                    @jakarta.validation.constraints.Size(max = 8000)
                    String text) {}

    /**
     * SPEC.md &sect;3.8 step 6 — one analysed brief as the creator reads it.
     *
     * <p><b>{@code degradedReason} is why this record is not just the extraction.</b> influora-ai's
     * brief route returns the SAME 200 shape when a creator is over her monthly brief allowance and
     * when the provider is down (SPEC.md &sect;14.4.a), so the two are distinguished by the error code
     * in that body and carried here as {@code "cap"} or {@code "ai_unavailable"}. They must reach the
     * creator as different sentences: one says her allowance is spent and names what still works, the
     * other says Influora could not read the brief this time. Null when the AI extraction succeeded.
     *
     * <p>{@code extractionSource} ({@code AI} | {@code FALLBACK}) and {@code degradedReason} are
     * redundant only in the success case. A FALLBACK with no reason would leave the UI unable to say
     * anything useful about why, which is how a degraded reading gets rendered as a real one.
     *
     * <p><b>This response is CREATOR-ONLY and {@code quote} may carry her floor.</b>
     * {@code CreatorToolDtos.PackageQuote} includes {@code floor_total}, {@code anchor},
     * {@code range_*} and {@code provenance}, and it is returned whole on purpose — she must see the
     * same numbers she was shown before. Nothing here may be handed to a brand-facing path; the strip
     * for that lives at Phase B1's secure-link boundary.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BriefAnalysisResponse(
            @JsonProperty("brief_id") String briefId,
            @JsonProperty("source") String source,
            @JsonProperty("status") String status,
            @JsonProperty("deal_id") String dealId,
            @JsonProperty("extraction") BriefExtraction extraction,
            @JsonProperty("flags")
                    List<com.influora.web.dto.meera.CreatorToolDtos.RiskFlag> flags,
            @JsonProperty("quote") com.influora.web.dto.meera.CreatorToolDtos.PackageQuote quote,
            @JsonProperty("extraction_source") String extractionSource,
            @JsonProperty("degraded_reason") String degradedReason,
            @JsonProperty("summary_lines") List<String> summaryLines,
            @JsonProperty("created_at") String createdAt) {

        /** {@link #degradedReason} — the creator's own monthly brief allowance is spent. */
        public static final String DEGRADED_CAP = "cap";

        /** {@link #degradedReason} — the model or the provider could not produce an extraction. */
        public static final String DEGRADED_AI_UNAVAILABLE = "ai_unavailable";
    }

    /**
     * SPEC.md &sect;3.8 — one row of "my recent briefs". Deliberately NOT a
     * {@link BriefAnalysisResponse}: the list is a chooser, and sending every brief's full extraction,
     * flags and quote (floor included) to render a list of names is a payload nobody asked for and a
     * barrier surface nobody needs.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BriefListItem(
            @JsonProperty("brief_id") String briefId,
            @JsonProperty("source") String source,
            @JsonProperty("status") String status,
            @JsonProperty("brand_name_guess") String brandNameGuess,
            @JsonProperty("extraction_source") String extractionSource,
            @JsonProperty("deal_id") String dealId,
            @JsonProperty("created_at") String createdAt) {}
}
