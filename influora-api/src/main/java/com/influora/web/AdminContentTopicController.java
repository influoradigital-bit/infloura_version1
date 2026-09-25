package com.influora.web;

import com.influora.security.AuthPrincipal;
import com.influora.service.creatorcopilot.ContentTopicService;
import com.influora.service.creatorcopilot.ContentTopicService.DroppedTopic;
import com.influora.service.creatorcopilot.ContentTopicService.ScreeningResult;
import com.influora.service.creatorcopilot.ContentTopicService.ServableTopic;
import com.influora.service.creatorcopilot.ContentTopicService.UnmatchedCategoryTopic;
import com.influora.service.creatorcopilot.CreatorCategoryMap;
import com.influora.web.dto.admin.AdminContentTopicDtos.ContentTopicPreviewResponse;
import com.influora.web.dto.admin.AdminContentTopicDtos.DroppedTopicView;
import com.influora.web.dto.admin.AdminContentTopicDtos.TopicView;
import com.influora.web.dto.admin.AdminContentTopicDtos.UnmatchedCategoryView;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * T-CONTENT-TOPICS -- {@code GET /admin/content-topics/preview}. This is the ONLY check a {@code
 * content_topics} row typed in by hand over a SQL client gets: there is no admin write UI and no
 * ingest-time screen (see the V75 migration comment and {@link ContentTopicService}'s class
 * javadoc), so this endpoint is how an operator finds out whether a row she just inserted would
 * actually reach a creator, and if not, why.
 *
 * <p>Mounted under {@code /admin/**}, so admin auth is enforced structurally by {@code
 * SecurityConfig}'s {@code hasRole("ADMIN")} matcher, same as every other {@code Admin*Controller}
 * (see {@link AdminCreatorAgentController}'s class javadoc, whose pattern this copies verbatim) --
 * no per-method check needed here. Returns a raw DTO (no {@link com.influora.common.ApiResponse}
 * envelope), matching that same controller's deliberate deviation for the admin console's client
 * contract.
 *
 * <p>{@code category}/{@code userId} are both optional and mutually exclusive in effect: a {@code
 * userId} resolves the SAME creator-categories lookup the real tool uses ({@link
 * ContentTopicService#resolveCreatorCategories}), so this previews exactly what that creator would
 * see; a bare {@code category} previews a creator holding that category directly, useful for
 * checking a row before any creator is assigned that category. It is split on commas by the same
 * {@link CreatorCategoryMap#splitTopicCategories} a row's category goes through, so {@code
 * ?category=Fashion, Culture} previews a creator holding both words, not one category literally
 * named "Fashion, Culture". Neither param previews the {@code ALL}-only view a category-less
 * creator gets.
 *
 * <p>Whatever the params, {@code unmatched_categories} lists every part of a servable-today row
 * that will not do what it looks like ({@link ContentTopicService#unmatchedCategories}): a part
 * that is not a known category (a typo or off-table word like "Snacks" reaches only creators who
 * typed that exact word), a calendar category no onboarding option reaches ("Gardening"), or a part
 * sitting next to {@code ALL}. Id, raw category, part and reason only, never title or angles.
 */
@RestController
@RequestMapping("/admin/content-topics")
public class AdminContentTopicController {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    static final String UNMATCHED_CATEGORIES_NOTE =
            "Each entry is one part of a row live today that will not do what it looks like. 'not a"
                    + " known category': a typo or off-table word, which reaches only creators who"
                    + " typed that exact word. 'no creator group reaches it': a calendar category no"
                    + " onboarding option maps to. 'ALL with other categories': the row already"
                    + " reaches everyone, so drop the other parts. Use calendar categories"
                    + " (comma-separated) or ALL alone.";

    private final ContentTopicService contentTopicService;

    public AdminContentTopicController(ContentTopicService contentTopicService) {
        this.contentTopicService = contentTopicService;
    }

    @GetMapping("/preview")
    public ContentTopicPreviewResponse preview(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "userId", required = false) String userId) {
        LocalDate today = LocalDate.now(IST);
        List<String> resolvedCategories = resolveCategories(category, userId);

        ScreeningResult result = contentTopicService.screen(resolvedCategories, today);

        return new ContentTopicPreviewResponse(
                today.toString(),
                resolvedCategories,
                result.servable().stream().map(AdminContentTopicController::toView).toList(),
                result.dropped().stream().map(AdminContentTopicController::toView).toList(),
                contentTopicService.unmatchedCategories(today).stream()
                        .map(AdminContentTopicController::toView)
                        .toList(),
                UNMATCHED_CATEGORIES_NOTE);
    }

    private List<String> resolveCategories(String category, String userId) {
        if (userId != null && !userId.isBlank()) {
            return contentTopicService.resolveCreatorCategories(userId);
        }
        if (category != null && !category.isBlank()) {
            return CreatorCategoryMap.splitTopicCategories(category);
        }
        return List.of();
    }

    private static TopicView toView(ServableTopic topic) {
        return new TopicView(
                topic.id(),
                topic.category(),
                topic.title(),
                topic.angles(),
                topic.liveUntil().toString(),
                topic.sensitivity());
    }

    private static DroppedTopicView toView(DroppedTopic dropped) {
        return new DroppedTopicView(dropped.id(), dropped.rejectionCategory());
    }

    private static UnmatchedCategoryView toView(UnmatchedCategoryTopic unmatched) {
        return new UnmatchedCategoryView(
                unmatched.id(), unmatched.category(), unmatched.part(), unmatched.reason());
    }
}
