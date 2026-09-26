package com.influora.web.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.web.dto.analytics.AnalyticsDtos;
import com.influora.web.dto.auth.AuthDtos;
import com.influora.web.dto.auth.EmailOtpDtos;
import com.influora.web.dto.campaign.CampaignDtos;
import com.influora.web.dto.creator.CreatorAgentDtos;
import com.influora.web.dto.creator.CreatorDtos;
import com.influora.web.dto.creatorcopilot.CreatorCopilotDtos;
import com.influora.web.dto.meera.CreatorToolDtos;
import com.influora.web.dto.meera.MeeraContextDtos;
import com.influora.web.dto.meera.MeeraDtos;
import com.influora.web.dto.meera.MeeraInteractionDtos;
import com.influora.web.dto.meera.MeeraToolDtos;
import com.influora.web.dto.meta.MetaDtos;
import com.influora.web.dto.money.MoneyDtos;
import com.influora.web.dto.notification.NotificationDtos;
import com.influora.web.dto.onboarding.OnboardingDtos;
import com.influora.web.dto.tracking.TrackingDtos;
import com.influora.web.dto.tracking.WebhookDtos;
import com.influora.web.dto.user.UserDtos;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Wave C task C1 (wiki/decisions/2026-07-06-brand-safety-caption-storage.md, LOCKED) structural
 * guardrail: {@code MediaMetric.caption} is internal BrandSafety-pipeline input only and must
 * NEVER be surfaced through any brand-facing DTO/response.
 *
 * <p>Rather than asserting against one hand-picked DTO instance (which a future edit could
 * silently bypass by adding a new record), this test reflects over every top-level DTO container
 * class in {@code com.influora.web.dto.*} — including every nested record/class declared inside
 * each — and fails if ANY field or record component anywhere is literally named {@code caption}
 * (case-insensitive) or {@code mediaCaption}. New DTO container classes must be added to {@link
 * #DTO_CONTAINER_CLASSES} to stay covered by this guardrail (see the {@code @Test} javadoc below
 * for the failure-mode explanation if that list drifts out of date).
 *
 * <p>Today ({@code AnalyticsDtos}, the one brand-facing surface that reads {@code MediaMetric}
 * rows via {@code AnalyticsService}) exposes no record at all shaped around individual media/posts
 * — only aggregated {@code CreatorMetricsResponse}/{@code CreatorScoresResponse}/{@code
 * CreatorDemographicsResponse} — so it is structurally impossible for caption text to leak through
 * today's DTO surface. This test pins that invariant so it cannot regress silently when C4's
 * {@code BrandSafetyBadge} DTO (or any future per-post DTO) is added.
 *
 * <p><b>Amendment 2026-09-26 (wiki/decisions/2026-09-26-creator-own-caption-to-meera.md,
 * Swapnil).</b> Creator Meera may see the cleaned FIRST LINE of the creator's OWN caption on her
 * best and weak posts. That is one record component, and it is allowed here by name, not by
 * dodging the pattern: the match is now "contains caption" (it was "ends with caption", which
 * {@code captionFirstLine} would have slipped past), the Meera DTO containers that were never
 * scanned ({@code CreatorToolDtos}, {@code MeeraContextDtos}, {@code MeeraInteractionDtos},
 * {@code CreatorAgentDtos}, {@code CreatorCopilotDtos}) are now scanned, and {@link
 * #CREATOR_ONLY_CAPTION_FIELDS} names exactly the one allowed component. {@code
 * CreatorOwnCaptionReachabilityTest} proves that record is reachable only from the
 * creator-scoped {@code get_my_content_patterns} route and from no brand route or DTO.
 */
class NoBrandFacingCaptionExposureTest {

    /**
     * Every top-level DTO container class under {@code com.influora.web.dto}. If a new DTO file is
     * added under this package, add it here too — omission would silently narrow this guardrail's
     * coverage rather than fail loudly, which is why each entry is listed explicitly rather than
     * classpath-scanned (no classpath-scanning library is available as a dependency here).
     */
    private static final Class<?>[] DTO_CONTAINER_CLASSES = {
        AnalyticsDtos.class,
        AuthDtos.class,
        EmailOtpDtos.class,
        CampaignDtos.class,
        CreatorDtos.class,
        MeeraDtos.class,
        MeeraToolDtos.class,
        MetaDtos.class,
        MoneyDtos.class,
        NotificationDtos.class,
        OnboardingDtos.class,
        TrackingDtos.class,
        WebhookDtos.class,
        UserDtos.class,
        // 2026-09-26 amendment: the Meera/creator containers, previously unscanned.
        CreatorToolDtos.class,
        MeeraContextDtos.class,
        MeeraInteractionDtos.class,
        CreatorAgentDtos.class,
        CreatorCopilotDtos.class,
    };

    /**
     * The ONLY caption-named components allowed anywhere in the scanned DTO surface, as
     * {@code <binary class name>#<component>}. Each is creator-only, the creator's own caption,
     * first line only (ADR 2026-09-26-creator-own-caption-to-meera). Adding an entry is a
     * decision, not a fix: it needs an ADR and a reachability proof like
     * {@code CreatorOwnCaptionReachabilityTest}.
     */
    static final Set<String> CREATOR_ONLY_CAPTION_FIELDS =
            Set.of("com.influora.web.dto.meera.CreatorToolDtos$PostReading#captionFirstLine");

    @Test
    @DisplayName(
            "C1: no field or record component named 'caption'/'mediaCaption' exists anywhere in "
                    + "web/dto (including nested records) -- MediaMetric.caption is internal-pipeline-only")
    void testNoDtoExposesCaptionField() {
        List<String> offenders = new ArrayList<>();

        for (Class<?> container : DTO_CONTAINER_CLASSES) {
            scanClassTree(container, offenders);
        }
        offenders.removeIf(o -> CREATOR_ONLY_CAPTION_FIELDS.contains(o.substring(0, o.indexOf(' '))));

        assertTrue(
                offenders.isEmpty(),
                "Found caption-named field(s) in brand-facing DTO surface (violates "
                        + "wiki/decisions/2026-07-06-brand-safety-caption-storage.md): "
                        + offenders);
    }

    /** Recursively scans a class and every class nested inside it (records, static classes, enums). */
    private void scanClassTree(Class<?> clazz, List<String> offenders) {
        scanOneClass(clazz, offenders);
        for (Class<?> nested : clazz.getDeclaredClasses()) {
            scanClassTree(nested, offenders);
        }
    }

    private void scanOneClass(Class<?> clazz, List<String> offenders) {
        // Record components (e.g. `record Foo(String caption)`) — this is how every DTO in this
        // codebase's web/dto package is actually declared.
        for (RecordComponent rc : clazz.getRecordComponents() == null ? new RecordComponent[0] : safeRecordComponents(clazz)) {
            if (isCaptionName(rc.getName())) {
                offenders.add(clazz.getName() + "#" + rc.getName() + " (record component)");
            }
        }
        // Plain fields, in case any DTO is a regular class rather than a record.
        for (Field f : clazz.getDeclaredFields()) {
            if (isCaptionName(f.getName())) {
                offenders.add(clazz.getName() + "#" + f.getName() + " (field)");
            }
        }
    }

    private RecordComponent[] safeRecordComponents(Class<?> clazz) {
        RecordComponent[] components = clazz.getRecordComponents();
        return components == null ? new RecordComponent[0] : components;
    }

    /**
     * Any name that CONTAINS "caption" (case-insensitive): {@code caption}, {@code mediaCaption},
     * {@code captionFirstLine}, {@code captionSnippet}. It used to be "ends with", which let a
     * caption-bearing field through by naming it {@code captionSomething}.
     */
    static boolean isCaptionName(String name) {
        return name.toLowerCase().contains("caption");
    }

    @Test
    @DisplayName(
            "2026-09-26: the allow-list names exactly one component, it exists, and the matcher"
                    + " catches it (so the allow-list is what lets it through, not the pattern)")
    void creatorOnlyAllowListIsExactAndLive() {
        assertEquals(1, CREATOR_ONLY_CAPTION_FIELDS.size(), "one creator-only caption field, by ADR");
        List<String> found = new ArrayList<>();
        for (Class<?> container : DTO_CONTAINER_CLASSES) {
            scanClassTree(container, found);
        }
        List<String> allowedFound =
                found.stream()
                        .map(o -> o.substring(0, o.indexOf(' ')))
                        .filter(CREATOR_ONLY_CAPTION_FIELDS::contains)
                        .distinct() // a record component and its private field share the name
                        .toList();
        assertEquals(
                List.copyOf(CREATOR_ONLY_CAPTION_FIELDS),
                allowedFound,
                "every allow-listed component must exist in a scanned container and match the"
                        + " caption pattern; a stale entry would silently allow a future field");
        assertTrue(isCaptionName("captionFirstLine"));
        assertTrue(isCaptionName("mediaCaption"));
        assertTrue(isCaptionName("caption_first_line"));
    }
}
