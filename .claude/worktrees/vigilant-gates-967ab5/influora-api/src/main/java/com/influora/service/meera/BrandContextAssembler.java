package com.influora.service.meera;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.common.JsonLists;
import com.influora.domain.entity.BrandProfile;
import com.influora.domain.entity.Workspace;
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
}
