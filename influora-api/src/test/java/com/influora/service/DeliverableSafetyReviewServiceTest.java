package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.Deliverable;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.DeliverableStatus;
import com.influora.domain.enums.DeliverableType;
import com.influora.integration.ai.BrandSafetyAiClient;
import com.influora.integration.ai.BrandSafetyAiException;
import com.influora.integration.ai.dto.BrandSafetyDtos.ClassifiedItem;
import com.influora.integration.ai.dto.BrandSafetyDtos.GarmFlag;
import com.influora.repository.DeliverableRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.deliverable.DeliverableSafetyDtos.DeliverableSafetyReviewResponse;
import com.influora.web.dto.deliverable.DeliverableSafetyDtos.SafetyCheckStatus;
import com.influora.web.dto.deliverable.DeliverableSafetyDtos.SafetyVerdict;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link DeliverableSafetyReviewService} — brand-feature-audit.md fix #3 ("no code
 * path scores submitted deliverable content"). Focus: the same GARM classifier used for creators'
 * published posts is now reachable for a brand-owned deliverable's caption, the verdict is
 * server-derived from the model's structured output (never trusted directly), every one of the 10
 * GARM categories is always present in {@code checks} (never silently omitted), and any classifier
 * failure degrades to a typed exception the FE hook treats as advisory-unavailable — never a 500,
 * never fabricated data.
 */
@ExtendWith(MockitoExtension.class)
class DeliverableSafetyReviewServiceTest {

    private static final String DELIVERABLE_ID = "01HDELIVERABLE1234567";
    private static final String WORKSPACE_ID = "01HWORKSPACE123456789";
    private static final String COLLAB_ID = "01HCOLLAB12345678901";

    @Mock private BrandContextService brandContext;
    @Mock private DeliverableRepository deliverableRepository;
    @Mock private BrandSafetyAiClient brandSafetyAiClient;
    @Mock private AuthPrincipal principal;

    private DeliverableSafetyReviewService service;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        service = new DeliverableSafetyReviewService(brandContext, deliverableRepository, brandSafetyAiClient);
        workspace = Workspace.newBrand(WORKSPACE_ID, "Acme Brand", "acme", "Fashion", "SMB");
    }

    private static Deliverable submittedDeliverableWithCaption(String caption) {
        Deliverable deliverable =
                Deliverable.builder()
                        .id(DELIVERABLE_ID)
                        .collaborationId(COLLAB_ID)
                        .creatorProfileId("profile1")
                        .slotIndex(1)
                        .type(DeliverableType.INSTAGRAM_REEL)
                        .title("Workout Reel 1")
                        .status(DeliverableStatus.DRAFT)
                        .build();
        deliverable.applyUpload(1, "[]", caption, null, null);
        deliverable.applySubmit(null, null, null, DeliverableStatus.SUBMITTED);
        return deliverable;
    }

    private static List<GarmFlag> allFloorExcept(String category, String risk) {
        String[] categories = {
            "adult_explicit_sexual_content",
            "arms_ammunition",
            "crime_harmful_acts_to_individuals",
            "death_injury_military_conflict",
            "hate_speech_acts_of_aggression",
            "illegal_drugs_tobacco_alcohol",
            "obscenity_profanity",
            "spam_or_harmful_content",
            "terrorism",
            "debated_sensitive_social_issues"
        };
        List<GarmFlag> flags = new java.util.ArrayList<>();
        for (String c : categories) {
            if (c.equals(category)) {
                flags.add(new GarmFlag(c, risk, "flagged for test"));
            } else {
                flags.add(new GarmFlag(c, "floor", "no concern"));
            }
        }
        return flags;
    }

    /** All 10 GARM categories at "floor" (no concern) — the fully-clean case. */
    private static List<GarmFlag> allFloor() {
        return allFloorExcept("__none__", "floor");
    }

    @Test
    @DisplayName("getReview: foreign/unowned deliverable is rejected with DELIVERABLE_NOT_FOUND before any classify call")
    void testGetReviewRejectsUnownedDeliverable() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(deliverableRepository.findByIdAndWorkspaceId(DELIVERABLE_ID, WORKSPACE_ID))
                .thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.getReview(principal, DELIVERABLE_ID));

        assertEquals("DELIVERABLE_NOT_FOUND", ex.getCode());
        verifyNoInteractions(brandSafetyAiClient);
    }

    @Test
    @DisplayName("getReview: clean caption -> all-PASS checks, overallVerdict PASS")
    void testGetReviewAllClean() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        Deliverable deliverable = submittedDeliverableWithCaption("Loving this new skincare routine!");
        when(deliverableRepository.findByIdAndWorkspaceId(DELIVERABLE_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(deliverable));

        ClassifiedItem classified =
                new ClassifiedItem(
                        DELIVERABLE_ID,
                        allFloor(),
                        "positive",
                        0.8,
                        98.0,
                        "Clean, on-brand content.");
        when(brandSafetyAiClient.classify(eq(WORKSPACE_ID), any())).thenReturn(List.of(classified));

        DeliverableSafetyReviewResponse response = service.getReview(principal, DELIVERABLE_ID);

        assertNotNull(response);
        assertEquals(SafetyVerdict.PASS, response.overallVerdict());
        assertEquals(10, response.checks().size());
        assertEquals(new java.math.BigDecimal("98.00"), response.score());
        response.checks().forEach(check -> assertEquals(SafetyCheckStatus.PASS, check.status()));
    }

    @Test
    @DisplayName("getReview: one HIGH-risk GARM category -> that check FAILs, overallVerdict FAIL")
    void testGetReviewHighRiskCategoryFailsThatCheckAndOverall() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        Deliverable deliverable = submittedDeliverableWithCaption("Some risky caption");
        when(deliverableRepository.findByIdAndWorkspaceId(DELIVERABLE_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(deliverable));

        ClassifiedItem classified =
                new ClassifiedItem(
                        DELIVERABLE_ID,
                        allFloorExcept("hate_speech_acts_of_aggression", "high"),
                        "negative",
                        -0.5,
                        20.0,
                        "Contains hate speech.");
        when(brandSafetyAiClient.classify(eq(WORKSPACE_ID), any())).thenReturn(List.of(classified));

        DeliverableSafetyReviewResponse response = service.getReview(principal, DELIVERABLE_ID);

        assertEquals(SafetyVerdict.FAIL, response.overallVerdict());
        var flaggedCheck =
                response.checks().stream()
                        .filter(c -> c.id().equals("hate_speech_acts_of_aggression"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(SafetyCheckStatus.FAIL, flaggedCheck.status());
        assertEquals("flagged for test", flaggedCheck.detail());
    }

    @Test
    @DisplayName("getReview: MEDIUM-risk category -> WARNING check, overallVerdict REVIEW (not FAIL)")
    void testGetReviewMediumRiskCategoryIsReviewNotFail() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        Deliverable deliverable = submittedDeliverableWithCaption("Borderline caption");
        when(deliverableRepository.findByIdAndWorkspaceId(DELIVERABLE_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(deliverable));

        ClassifiedItem classified =
                new ClassifiedItem(
                        DELIVERABLE_ID,
                        allFloorExcept("obscenity_profanity", "medium"),
                        "neutral",
                        0.0,
                        65.0,
                        "Mild profanity.");
        when(brandSafetyAiClient.classify(eq(WORKSPACE_ID), any())).thenReturn(List.of(classified));

        DeliverableSafetyReviewResponse response = service.getReview(principal, DELIVERABLE_ID);

        assertEquals(SafetyVerdict.REVIEW, response.overallVerdict());
        var flaggedCheck =
                response.checks().stream()
                        .filter(c -> c.id().equals("obscenity_profanity"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(SafetyCheckStatus.WARNING, flaggedCheck.status());
    }

    @Test
    @DisplayName("getReview: no caption on the deliverable -> SAFETY_REVIEW_NO_CONTENT, no classify call")
    void testGetReviewNoCaptionThrowsNoContent() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        Deliverable deliverable = submittedDeliverableWithCaption(null);
        when(deliverableRepository.findByIdAndWorkspaceId(DELIVERABLE_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(deliverable));

        ApiException ex =
                assertThrows(ApiException.class, () -> service.getReview(principal, DELIVERABLE_ID));

        assertEquals("SAFETY_REVIEW_NO_CONTENT", ex.getCode());
        verifyNoInteractions(brandSafetyAiClient);
    }

    @Test
    @DisplayName("getReview: classifier failure degrades to SAFETY_REVIEW_UNAVAILABLE (503), never a 500")
    void testGetReviewClassifierFailureDegradesGracefully() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        Deliverable deliverable = submittedDeliverableWithCaption("Some caption");
        when(deliverableRepository.findByIdAndWorkspaceId(DELIVERABLE_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(deliverable));
        when(brandSafetyAiClient.classify(eq(WORKSPACE_ID), any()))
                .thenThrow(new BrandSafetyAiException("influora-ai unreachable"));

        ApiException ex =
                assertThrows(ApiException.class, () -> service.getReview(principal, DELIVERABLE_ID));

        assertEquals("SAFETY_REVIEW_UNAVAILABLE", ex.getCode());
        assertEquals(503, ex.getStatus().value());
    }

    @Test
    @DisplayName("getReview: redacts the caption before it reaches the classifier")
    void testGetReviewRedactsCaptionBeforeClassifying() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        Deliverable deliverable =
                submittedDeliverableWithCaption("Reach me at test@example.com for details!");
        when(deliverableRepository.findByIdAndWorkspaceId(DELIVERABLE_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(deliverable));

        ClassifiedItem classified =
                new ClassifiedItem(
                        DELIVERABLE_ID, allFloor(), "neutral", 0.0, 90.0, "ok");
        when(brandSafetyAiClient.classify(eq(WORKSPACE_ID), any())).thenReturn(List.of(classified));

        service.getReview(principal, DELIVERABLE_ID);

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(brandSafetyAiClient).classify(eq(WORKSPACE_ID), captor.capture());
        @SuppressWarnings("unchecked")
        List<com.influora.integration.ai.dto.BrandSafetyDtos.ContentItem> sentItems =
                (List<com.influora.integration.ai.dto.BrandSafetyDtos.ContentItem>) captor.getValue();
        assertEquals(1, sentItems.size());
        // SensitiveTextRedactor must have masked the raw email before this left the service.
        org.junit.jupiter.api.Assertions.assertFalse(
                sentItems.get(0).caption().contains("test@example.com"));
    }
}
