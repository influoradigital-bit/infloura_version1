package com.influora.service.meera;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.common.JsonLists;
import com.influora.domain.entity.BrandProfile;
import com.influora.domain.entity.Workspace;
import com.influora.web.dto.meera.MeeraContextDtos.ContextResponse;
import com.influora.web.dto.meera.MeeraContextDtos.CreditState;
import com.influora.web.dto.meera.MeeraContextDtos.PastCampaignEntry;
import com.influora.web.dto.meera.MeeraContextDtos.TemplateDigestEntry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Builds the SANITIZED brand-context object handed to the Python/Meera service — the
 * implementation of Guardrail 3 (03-SECURITY-SPEC.md §G3): a deny-by-default, explicit
 * field allow-list. New columns on {@link Workspace} or {@link BrandProfile} do NOT
 * automatically flow into the prompt; a field must be added here explicitly to ever
 * reach the LLM.
 *
 * <p><b>Allow-listed (safe to send):</b> workspace name, industry, company size, website URL,
 * public description, {@link BrandProfile}'s product catalog / brand aesthetic / tone profile /
 * niche tags / competitor URLs, and live AI-credit state (handled separately by
 * {@link AICreditService} — not this class).
 *
 * <p><b>NEVER included (PII — Guardrail 3), read directly off {@link Workspace}/{@code User}
 * and explicitly excluded here:</b>
 * <ul>
 *   <li>{@code Workspace.billingEmail}, {@code Workspace.gstin}, {@code Workspace.pan}</li>
 *   <li>{@code Workspace.kycGstinDocUrl}, {@code Workspace.kycPanDocUrl}</li>
 *   <li>{@code User.email}, {@code User.phoneNumber}, {@code User.passwordHash}</li>
 *   <li>Any creator PII (phone/email/bank) — that allow-list lives in the {@code show_creators}
 *       executor (Phase 4), out of scope here, but the same rule applies: public stats only.</li>
 * </ul>
 */
@Service
public class BrandContextAssembler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Assemble the sanitized context. {@code brandProfile} may be null (analysis not yet ready) —
     * callers should gate on {@code analysisStatus == READY} before relying on catalog/tone data.
     */
    public Map<String, Object> assemble(Workspace workspace, BrandProfile brandProfile) {
        Map<String, Object> context = new LinkedHashMap<>();

        // --- Allow-listed workspace fields only ---
        context.put("workspaceId", workspace.getId());
        context.put("brandName", workspace.getName());
        context.put("industry", workspace.getIndustry());
        context.put("websiteUrl", workspace.getWebsiteUrl());
        // Explicitly NOT included: billingEmail, gstin, pan, kycGstinDocUrl, kycPanDocUrl.

        if (brandProfile != null) {
            context.put("analysisStatus", brandProfile.getAnalysisStatus().name());
            context.put("productCatalog", parseJsonOrNull(brandProfile.getProductCatalogJson()));
            context.put("brandAesthetic", parseJsonOrNull(brandProfile.getBrandAestheticJson()));
            context.put("toneProfile", parseJsonOrNull(brandProfile.getToneProfileJson()));
            context.put("nicheTags", nicheTags(brandProfile));
            context.put("competitorUrls", parseJsonOrNull(brandProfile.getCompetitorUrlsJson()));
        } else {
            context.put("analysisStatus", "PENDING");
        }

        return context;
    }

    private List<String> nicheTags(BrandProfile profile) {
        return JsonLists.stringListFromJson(profile.getNicheTagsJson());
    }

    private Object parseJsonOrNull(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------
    // POST /internal/meera/context (Platform-AI Phase 1, Wave 1a — Priya ruling A2/A1).
    // Same allow-list discipline as #assemble above, extended with the two NEW digests and
    // wire-formatted for Python per the Wave-1c field-name seam (MeeraContextDtos javadoc).
    // ---------------------------------------------------------------------------------------

    /**
     * A1: {@code product_catalog} entries carry ONLY these fields into the prompt.
     *
     * <p>{@code price_source} (C1, Kabir P1-B audit) travels alongside {@code price} so Block B /
     * Meera can see whether a price is a verified scraped fact ({@code "scraped"}) or a model guess
     * ({@code "inferred"}) — without it, {@code calculate_budget} and Meera's "quote the real
     * price" rule have no way to tell an inferred price from a scraped one, and the money-safety
     * guard in {@code merge_known_products} (influora-ai) becomes cosmetic once it reaches this
     * layer.
     */
    private static final List<String> PRODUCT_CATALOG_ALLOWED_FIELDS =
            List.of("name", "price", "currency", "price_source");

    /**
     * Assembles the BRAND-audience {@code ContextResponse} for the new context endpoint.
     * {@code templates} and {@code recentCampaigns} are pre-fetched by {@link MeeraContextService}
     * (tenant-scoped repository reads live there, not in this pure mapper) and formatted here into
     * the {@code template_digest} / {@code past_campaign_summary} slots per the P1 fix in
     * {@code wiki/ai-review/campaign-templates-knowledge-ai-review.md}. {@code credit} is the
     * live {@link AICreditService} state, formatted into {@code credit_state{mode,credits_remaining}}
     * — never wallet/escrow balances (A1 NEVER list).
     */
    public ContextResponse assembleBrandContext(
            Workspace workspace,
            BrandProfile brandProfile,
            List<com.influora.domain.entity.CampaignTemplate> templates,
            List<PastCampaignEntry> recentCampaigns,
            String creditMode,
            int creditsRemaining) {
        List<String> nicheTags = brandProfile != null ? nicheTags(brandProfile) : null;
        Object toneDial = brandProfile != null ? parseJsonOrNull(brandProfile.getToneProfileJson()) : null;
        Object brandAesthetic = brandProfile != null ? parseJsonOrNull(brandProfile.getBrandAestheticJson()) : null;
        String brandColor = extractBrandColor(brandAesthetic);
        Object competitorUrls = brandProfile != null ? parseJsonOrNull(brandProfile.getCompetitorUrlsJson()) : null;
        List<Map<String, Object>> productCatalog =
                brandProfile != null ? filteredProductCatalog(brandProfile.getProductCatalogJson()) : null;
        String analysisStatus =
                brandProfile != null ? brandProfile.getAnalysisStatus().name() : "PENDING";

        return new ContextResponse(
                workspace.getId(),
                workspace.getName(),
                workspace.getIndustry(),
                workspace.getWebsiteUrl(),
                nicheTags,
                toneDial,
                brandColor,
                brandAesthetic,
                productCatalog,
                competitorUrls,
                analysisStatus,
                templateDigest(templates),
                recentCampaigns,
                new CreditState(creditMode, creditsRemaining));
    }

    /**
     * {@code brand_color} (Ash's canonical seam key) lives inside the raw {@code brand_aesthetic}
     * blob as {@code accent_color} — see {@code AnalyzeSiteTriggerService#toCallback}, which is the
     * only writer of {@code brandAestheticJson} and always shapes it as {@code {accent_color: hex}}
     * when a color is present. Pulled out to a top-level field so Python's {@code build_block_b}
     * (which reads {@code brand.get("brand_color")} directly, not nested) can find it.
     */
    @SuppressWarnings("unchecked")
    private String extractBrandColor(Object brandAesthetic) {
        if (!(brandAesthetic instanceof Map<?, ?> map)) {
            return null;
        }
        Object color = ((Map<String, Object>) map).get("accent_color");
        return color instanceof String ? (String) color : null;
    }

    /**
     * A1: filters each raw catalog entry down to name/price/currency/price_source only — never the
     * full scraped row.
     *
     * <p>C1 fail-safe (defense in depth alongside {@code AnalyzeSiteTriggerService}'s write-path
     * normalization): if a persisted row somehow carries no {@code price_source} at all (a row
     * written before this field existed, or any other write path that skipped normalization), it
     * is defaulted to {@code "inferred"} here too — unknown provenance is never treated as
     * "scraped" by omission.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> filteredProductCatalog(String productCatalogJson) {
        Object raw = parseJsonOrNull(productCatalogJson);
        if (!(raw instanceof List<?> rawList)) {
            return null;
        }
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Object entry : rawList) {
            if (!(entry instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> allowed = new LinkedHashMap<>();
            for (String field : PRODUCT_CATALOG_ALLOWED_FIELDS) {
                if (((Map<String, Object>) map).containsKey(field)) {
                    allowed.put(field, ((Map<String, Object>) map).get(field));
                }
            }
            Object priceSource = allowed.get("price_source");
            if (!(priceSource instanceof String s) || s.isBlank()) {
                allowed.put("price_source", "inferred");
            }
            filtered.add(allowed);
        }
        return filtered;
    }

    /** ~1 line per template: name, campaign_type, budget band, key requirements — SYSTEM always + this workspace's CUSTOM. */
    private List<TemplateDigestEntry> templateDigest(List<com.influora.domain.entity.CampaignTemplate> templates) {
        if (templates == null) {
            return List.of();
        }
        List<TemplateDigestEntry> digest = new ArrayList<>();
        for (com.influora.domain.entity.CampaignTemplate template : templates) {
            digest.add(
                    new TemplateDigestEntry(
                            template.getName(),
                            template.getCampaignType() != null ? template.getCampaignType().name() : null,
                            budgetBand(template.getBudgetMin(), template.getBudgetMax()),
                            keyRequirements(template.getRequirementsJson())));
        }
        return digest;
    }

    private String budgetBand(java.math.BigDecimal min, java.math.BigDecimal max) {
        if (min == null && max == null) {
            return null;
        }
        String low = min != null ? min.stripTrailingZeros().toPlainString() : "0";
        String high = max != null ? max.stripTrailingZeros().toPlainString() : "?";
        return "₹" + low + "–₹" + high;
    }

    /** First few requirement lines, comma-joined — keeps the digest at ~1 line per template (Ash's cost note). */
    private static final int MAX_DIGEST_REQUIREMENTS = 3;

    private String keyRequirements(String requirementsJson) {
        List<String> requirements = JsonLists.stringListFromJson(requirementsJson);
        if (requirements == null || requirements.isEmpty()) {
            return null;
        }
        return String.join(", ", requirements.subList(0, Math.min(MAX_DIGEST_REQUIREMENTS, requirements.size())));
    }
}
