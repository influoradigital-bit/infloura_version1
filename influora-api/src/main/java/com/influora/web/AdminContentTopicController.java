package com.influora.web;

import com.influora.security.AuthPrincipal;
import com.influora.service.creatorcopilot.ContentTopicService;
import com.influora.service.creatorcopilot.ContentTopicService.DroppedTopic;
import com.influora.service.creatorcopilot.ContentTopicService.ScreeningResult;
import com.influora.service.creatorcopilot.ContentTopicService.ServableTopic;
import com.influora.web.dto.admin.AdminContentTopicDtos.ContentTopicPreviewResponse;
import com.influora.web.dto.admin.AdminContentTopicDtos.DroppedTopicView;
import com.influora.web.dto.admin.AdminContentTopicDtos.TopicView;
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
 * see; a bare {@code category} previews a single category directly, useful for checking a row
 * before any creator is assigned that category; neither param previews the {@code ALL}-only view a
 * category-less creator gets.
 */
@RestController
@RequestMapping("/admin/content-topics")
public class AdminContentTopicController {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

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
                result.dropped().stream().map(AdminContentTopicController::toView).toList());
    }

    private List<String> resolveCategories(String category, String userId) {
        if (userId != null && !userId.isBlank()) {
            return contentTopicService.resolveCreatorCategories(userId);
        }
        if (category != null && !category.isBlank()) {
            return List.of(category);
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
}
