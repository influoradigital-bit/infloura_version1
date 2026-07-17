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
                    String action, // "REMOVE" / "REJECT" / "ESCALATE"
            String reason // optional — audit trail only
            ) {}
}
