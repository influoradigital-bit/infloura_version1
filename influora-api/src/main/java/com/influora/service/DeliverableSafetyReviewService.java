package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.SensitiveTextRedactor;
import com.influora.domain.entity.Deliverable;
import com.influora.domain.entity.Workspace;
import com.influora.integration.ai.BrandSafetyAiClient;
import com.influora.integration.ai.BrandSafetyAiException;
import com.influora.integration.ai.dto.BrandSafetyDtos.ClassifiedItem;
import com.influora.integration.ai.dto.BrandSafetyDtos.ContentItem;
import com.influora.integration.ai.dto.BrandSafetyDtos.GarmFlag;
import com.influora.repository.DeliverableRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.deliverable.DeliverableSafetyDtos.DeliverableSafetyReviewResponse;
import com.influora.web.dto.deliverable.DeliverableSafetyDtos.SafetyCheck;
import com.influora.web.dto.deliverable.DeliverableSafetyDtos.SafetyCheckStatus;
import com.influora.web.dto.deliverable.DeliverableSafetyDtos.SafetyVerdict;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [Fix: brand-feature-audit.md #3] Deliverable-level brand-safety advisory review — {@code GET
 * /deliverables/{id}/safety-review}. Before this fix, no code path scored SUBMITTED deliverable
 * content at all: {@link BrandDeliverableService} had zero reference to {@link BrandSafetyAiClient}
 * / {@code BrandSafetyScoreService} — GARM only scored creators' already-published Meta posts via
 * the batch {@code ScoreCalculationJob}.
 *
 * <p><b>Reuses the existing hardened classifier verbatim</b> — the same forced-tool-choice GARM
 * call {@link BrandSafetyAiClient#classify} already makes to influora-ai's {@code POST
 * /internal/brand-safety} for polled Meta captions (Wave C task C2/C3: 10 fixed GARM categories,
 * "never omit a category" server-side validation, caption-never-logged discipline). This service
 * just supplies a different content source (one submitted deliverable's caption instead of a batch
 * of {@code MediaMetric} rows) and interprets the SAME {@link ClassifiedItem} shape into a
 * brand-facing verdict.
 *
 * <p><b>Server-interpreted, advisory only, NEVER blocks submit/approve</b> (R-tier discipline, same
 * as {@code BrandSafetyScoreService}'s "unscored, not unsafe" rule): the model returns only raw
 * per-category risk + sentiment + a 0-100 score — {@code overallVerdict} is entirely computed here
 * ({@link #deriveVerdict}), never trusted from the model. Nothing in the submit/approve/reject flow
 * calls or awaits this service; it exists solely for the brand's optional read.
 *
 * <p><b>SECURITY — info-barrier note (flagged for Kabir):</b> a deliverable's caption is
 * creator-authored free text and therefore a prompt-injection payload reaching an LLM call, exactly
 * like every other caption this classifier already ingests. It is redacted for PII via {@link
 * SensitiveTextRedactor} before the call (defense-in-depth on top of influora-ai's own untrusted-
 * input handling in {@code app/prompt/brand_safety.py}). The verdict itself is derived SOLELY from
 * enum-validated GARM categories + a numeric score (server-computed in {@code deriveVerdict}), so a
 * hostile caption cannot flip the verdict. Note the per-category {@code rationale} IS model-generated
 * free text and IS surfaced to the brand in {@code SafetyCheck.detail} (Kabir F1) — but it is prose
 * about the brand's OWN commissioned deliverable, not cross-party data, and the review UI renders it
 * as escaped plain text (never innerHTML), so it is neither a leak nor an injection sink.
 * This deliverable belongs to the brand's own paid campaign (the brand is the workspace that
 * commissioned it), so this call does NOT cross the brand/creator info barrier the same way an
 * unrelated cross-tenant read would. Kabir red-teamed this path 2026-07-22 (all 4 invariants PASS,
 * {@code wiki/build/brand-fixes-kabir-review.md}) — cleared to ship live.
 */
@Service
public class DeliverableSafetyReviewService {

    private static final Logger log = LoggerFactory.getLogger(DeliverableSafetyReviewService.class);

    /** Human-readable labels for {@code app/tools/schemas.py::GARM_CATEGORIES} — kept in sync
     * manually since this is a display concern only; the enum values themselves are validated
     * server-side by influora-ai, not by this map. */
    private static final Map<String, String> GARM_CATEGORY_LABELS = buildCategoryLabels();

    private static final String NO_CONCERN_RISK_LEVEL = "floor";
    private static final String HIGH_RISK_LEVEL = "high";

    private final BrandContextService brandContext;
    private final DeliverableRepository deliverableRepository;
    private final BrandSafetyAiClient brandSafetyAiClient;

    public DeliverableSafetyReviewService(
            BrandContextService brandContext,
            DeliverableRepository deliverableRepository,
            BrandSafetyAiClient brandSafetyAiClient) {
        this.brandContext = brandContext;
        this.deliverableRepository = deliverableRepository;
        this.brandSafetyAiClient = brandSafetyAiClient;
    }

    /**
     * Scores the deliverable's caption on demand (not cached/persisted — every call is a fresh
     * classification; see class javadoc for why this is an accepted trade-off for a first pass).
     *
     * @throws ApiException {@code DELIVERABLE_NOT_FOUND} (404) if the deliverable doesn't belong to
     *     the caller's workspace; {@code SAFETY_REVIEW_NO_CONTENT} (404) if the deliverable has no
     *     caption to score; {@code SAFETY_REVIEW_UNAVAILABLE} (503) if influora-ai is unreachable,
     *     errors, or returns a malformed result. The FE hook ({@code
     *     useDeliverableSafetyReview.ts}) treats ANY of these uniformly as "no review available
     *     yet" and never surfaces them as a blocking error.
     */
    @Transactional(readOnly = true)
    public DeliverableSafetyReviewResponse getReview(AuthPrincipal principal, String deliverableId) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        Deliverable deliverable =
                deliverableRepository
                        .findByIdAndWorkspaceId(deliverableId, workspace.getId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "DELIVERABLE_NOT_FOUND",
                                                "Deliverable not found",
                                                HttpStatus.NOT_FOUND));

        String caption = deliverable.getCaption();
        if (caption == null || caption.isBlank()) {
            throw new ApiException(
                    "SAFETY_REVIEW_NO_CONTENT",
                    "This deliverable has no caption text to review",
                    HttpStatus.NOT_FOUND);
        }

        // Defense-in-depth PII redaction before the free-text caption leaves this service, on top
        // of influora-ai's own untrusted-input handling (Kabir C1 MED-1 caption-egress discipline
        // this call path already carries).
        String redactedCaption = SensitiveTextRedactor.redact(caption);

        ContentItem item = new ContentItem(deliverable.getId(), redactedCaption, null, null);

        List<ClassifiedItem> classified;
        try {
            classified = brandSafetyAiClient.classify(workspace.getId(), List.of(item));
        } catch (BrandSafetyAiException e) {
            log.warn(
                    "DeliverableSafetyReviewService: classify call failed for deliverable {} "
                            + "(workspace {}): {}",
                    deliverableId,
                    workspace.getId(),
                    e.getMessage());
            throw new ApiException(
                    "SAFETY_REVIEW_UNAVAILABLE",
                    "Safety review is not available right now",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }

        if (classified.isEmpty() || classified.get(0) == null) {
            throw new ApiException(
                    "SAFETY_REVIEW_UNAVAILABLE",
                    "Safety review is not available right now",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }

        return toResponse(classified.get(0));
    }

    private DeliverableSafetyReviewResponse toResponse(ClassifiedItem item) {
        List<SafetyCheck> checks = toChecks(item.garmFlags());
        SafetyVerdict verdict = deriveVerdict(checks);
        BigDecimal score = toScaledScore(item.brandSafetyScore());
        return new DeliverableSafetyReviewResponse(verdict, checks, score, Instant.now());
    }

    /**
     * One check per GARM category, ALWAYS all 10 — same "never omit a category to imply safety"
     * discipline the Python classifier enforces server-side (an incomplete {@code garmFlags} list
     * here would silently read as "everything else passed", which is exactly the false-negative
     * shape this whole feature exists to avoid).
     */
    private List<SafetyCheck> toChecks(List<GarmFlag> garmFlags) {
        Map<String, GarmFlag> byCategory = new LinkedHashMap<>();
        if (garmFlags != null) {
            for (GarmFlag flag : garmFlags) {
                if (flag != null && flag.category() != null) {
                    byCategory.put(flag.category(), flag);
                }
            }
        }

        List<SafetyCheck> checks = new ArrayList<>(GARM_CATEGORY_LABELS.size());
        for (Map.Entry<String, String> entry : GARM_CATEGORY_LABELS.entrySet()) {
            String category = entry.getKey();
            String label = entry.getValue();
            GarmFlag flag = byCategory.get(category);
            if (flag == null) {
                // Defensive only — influora-ai validates every category is present before
                // returning; a missing category here means "unscored", not "safe".
                checks.add(new SafetyCheck(category, label, SafetyCheckStatus.WARNING, "Not scored"));
                continue;
            }
            checks.add(new SafetyCheck(category, label, toCheckStatus(flag.risk()), flag.rationale()));
        }
        return checks;
    }

    private static SafetyCheckStatus toCheckStatus(String risk) {
        if (HIGH_RISK_LEVEL.equals(risk)) {
            return SafetyCheckStatus.FAIL;
        }
        if (NO_CONCERN_RISK_LEVEL.equals(risk)) {
            return SafetyCheckStatus.PASS;
        }
        // "low" or "medium" (or any future/unexpected value) — a real concern flagged by the
        // model but not the most severe tier; surfaced for brand judgment, never auto-failed.
        return SafetyCheckStatus.WARNING;
    }

    /** FAIL if any check FAILs, else REVIEW if any check WARNINGs, else PASS. Worst-check-driven,
     * same principle {@code BrandSafetyScoreService} uses for its creator-level aggregate. */
    private static SafetyVerdict deriveVerdict(List<SafetyCheck> checks) {
        boolean anyFail = false;
        boolean anyWarning = false;
        for (SafetyCheck check : checks) {
            if (check.status() == SafetyCheckStatus.FAIL) {
                anyFail = true;
            } else if (check.status() == SafetyCheckStatus.WARNING) {
                anyWarning = true;
            }
        }
        if (anyFail) {
            return SafetyVerdict.FAIL;
        }
        if (anyWarning) {
            return SafetyVerdict.REVIEW;
        }
        return SafetyVerdict.PASS;
    }

    private static BigDecimal toScaledScore(Double value) {
        if (value == null) {
            return null;
        }
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private static Map<String, String> buildCategoryLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("adult_explicit_sexual_content", "Adult / explicit sexual content");
        labels.put("arms_ammunition", "Arms & ammunition");
        labels.put("crime_harmful_acts_to_individuals", "Crime / harmful acts to individuals");
        labels.put("death_injury_military_conflict", "Death, injury & military conflict");
        labels.put("hate_speech_acts_of_aggression", "Hate speech & acts of aggression");
        labels.put("illegal_drugs_tobacco_alcohol", "Illegal drugs, tobacco & alcohol");
        labels.put("obscenity_profanity", "Obscenity & profanity");
        labels.put("spam_or_harmful_content", "Spam or harmful content");
        labels.put("terrorism", "Terrorism");
        labels.put("debated_sensitive_social_issues", "Debated sensitive social issues");
        return labels;
    }
}
