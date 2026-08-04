package com.influora.web.dto.admin;

import com.influora.domain.enums.ContentFlagSource;
import com.influora.domain.enums.ContentFlagStatus;
import com.influora.domain.enums.ContentFlagType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

/**
 * DTOs for {@link com.influora.web.AdminModerationController} — content flag moderation queue
 * (P2-6). Matches {@code moderationApi} and {@code ContentFlag} in {@code
 * src/admin/types/admin.types.ts} (Priya).
 */
public final class AdminModerationDtos {

    private AdminModerationDtos() {}

    /**
     * Single content flag in the moderation queue. Mirrors {@code ContentFlag} in {@code
     * admin.types.ts}.
     */
    public record FlagDto(
            String id,
            ContentFlagType contentType,
            String contentId,
            String contentPreview,
            String flagReason,
            ContentFlagSource flaggedBy,
            String flaggedByUserId,
            ContentFlagStatus status,
            String actionTaken,
            String reviewedBy,
            Instant reviewedAt,
            Instant createdAt) {}

    /**
     * Paged response for flag queue listing. Matches {@code moderationApi.listFlags} return shape.
     */
    public record PagedFlagsDto(List<FlagDto> items, int total, int page, int pageSize) {}

    /**
     * Request body for {@code actionFlag} — moderation action taken on a flag. Matches {@code
     * ModerationAction} in {@code admin.types.ts} (subset: only action field used for content flags
     * this cycle; entityId/entityType/reason are optional/future).
     */
    public record ActionFlagRequest(
            String entityId, // optional — not used for content flags
            String entityType, // optional — not used for content flags
            @NotNull(message = "Action is required")
                    @NotBlank(message = "Action cannot be blank")
                    String action, // "REMOVE" / "REJECT" / "WARN" / "ESCALATE"
            String reason // optional for REMOVE/REJECT/WARN (audit trail only); mandatory for
            // ESCALATE — enforced service-side in AdminModerationService (REASON_REQUIRED/400)
            ) {}

    /**
     * {@code GET /admin/moderation/suspensions} — one row per currently-suspended account. Mirrors
     * {@code AccountSuspension} in {@code admin.types.ts}. Every field is live off the account's own
     * row ({@code Workspace} for brands/agencies, {@code CreatorProfile} for creators — the two
     * distinct suspend paths, {@code AdminBrandService}/{@code AdminCreatorService}), so there is no
     * fabricated data. Two <b>declared limitations</b>, honest about what the schema does NOT hold:
     *
     * <ul>
     *   <li>{@code appealStatus} is always {@code "NONE"} and {@code appealNotes} always {@code
     *       null}: there is no appeal entity/column anywhere in the schema yet (the appeal endpoint
     *       {@code POST /suspensions/{id}/appeal} is a separate, still-unbuilt queue item). Reporting
     *       any other value would be invented.
     *   <li>{@code reinstatedAt}/{@code reinstatedBy} are always {@code null} <b>by deliberate
     *       choice, not for lack of a column</b>: {@code reinstated_at}/{@code reinstated_by} DO exist
     *       on both entities ({@code getReinstatedAt()}/{@code getReinstatedBy()}), but {@code
     *       suspend()} does not clear them, so on a re-suspended account (suspend→reinstate→suspend)
     *       they hold a STALE value from the prior reinstate cycle. This list contains only currently
     *       {@code suspended} accounts, for which "reinstated" is false by definition, so surfacing a
     *       prior-cycle timestamp would be misleading — {@code null} is the correct semantic here.
     *   <li>{@code id} is the account's own id (there is no separate suspension-record entity); it is
     *       what the appeal endpoint would key on. {@code userType} is {@code "BRAND"} for every
     *       {@code Workspace} suspension (a {@code Workspace} is {@code BRAND} or {@code AGENCY} —
     *       both brand-side, never a creator) and {@code "CREATOR"} for every {@code CreatorProfile}.
     * </ul>
     */
    public record AccountSuspensionDto(
            String id,
            String userId,
            String userType,
            String userName,
            String reason,
            String suspendedBy,
            String suspendedAt,
            String appealStatus,
            String appealNotes,
            String reinstatedAt,
            String reinstatedBy) {}
}
