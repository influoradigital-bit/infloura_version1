package com.influora.service.meera.tool.creator;

import com.influora.domain.enums.CreatorToolName;
import com.influora.domain.enums.MeeraToolTier;
import com.influora.service.AuditLogService;
import com.influora.service.meera.tool.ToolCallValidator;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;3.2) — the CREATOR-audience counterpart to
 * {@link ToolCallValidator}: the single choke point every {@code /internal/meera/creator/*} call
 * passes through before an executor runs.
 *
 * <p>Mirrors the brand validator's contract deliberately, including its two rejection reasons
 * ({@code UNKNOWN_TOOL_NAME}, {@code FORBIDDEN_TIER}) and its audit-on-rejection behaviour. Two
 * things it does NOT mirror:
 *
 * <ul>
 *   <li>It <b>reuses</b> {@link ToolCallValidator.ToolCallRejectedException} rather than declaring a
 *       parallel exception type. The controller's translation of a rejection into an HTTP status is
 *       one {@code catch}, and a second exception class with the same shape would mean every future
 *       handler has to remember to catch both — the kind of asymmetry that ends with one route
 *       returning 500 where its neighbour returns 403.
 *   <li>Its first argument to {@link AuditLogService#recordToolCall} is a {@code users.id}, not a
 *       {@code workspaces.id}. A CREATOR turn has no workspace row at all (see
 *       {@code MeeraContextService#assembleCreatorContext}), and the audit column is an opaque
 *       tenant key, so the creator's user id is the correct and only available value.
 * </ul>
 *
 * <p><b>{@link AuditLogService#recordToolCall} takes Strings, not enums</b>, and its outcome
 * constants are {@code OUTCOME_ALLOWED}/{@code OUTCOME_REJECTED}/{@code OUTCOME_FAILED} — there is
 * no {@code OUTCOME_OK}. Both the tool and the tier are passed as {@code .name()}.
 */
@Service
public class CreatorToolCallValidator {

    /**
     * The whitelist and its tiers (SPEC.md &sect;3.1). Five reads are {@link MeeraToolTier#R};
     * {@code draft_reply} is {@link MeeraToolTier#D} because it persists a reversible, non-binding
     * {@code MeeraDraft} that a human must tap before anything reaches a brand.
     *
     * <p>No creator tool is {@link MeeraToolTier#C} in B0 and none is ever a money tool: the money
     * capabilities have no creator tool name at all, which is the matrix's "absent, not blocked"
     * guarantee rather than a soft check.
     */
    private static final Map<CreatorToolName, MeeraToolTier> TIER_BY_TOOL =
            new EnumMap<>(CreatorToolName.class);

    static {
        TIER_BY_TOOL.put(CreatorToolName.get_my_deals, MeeraToolTier.R);
        TIER_BY_TOOL.put(CreatorToolName.get_brief, MeeraToolTier.R);
        TIER_BY_TOOL.put(CreatorToolName.estimate_my_rate, MeeraToolTier.R);
        TIER_BY_TOOL.put(CreatorToolName.get_my_metrics, MeeraToolTier.R);
        TIER_BY_TOOL.put(CreatorToolName.check_deal_risks, MeeraToolTier.R);
        TIER_BY_TOOL.put(CreatorToolName.draft_reply, MeeraToolTier.D);
        // T-CONTENT-TOPICS -- a plain read over a hand-typed catalogue, never a money tool.
        TIER_BY_TOOL.put(CreatorToolName.get_todays_topics, MeeraToolTier.R);
    }

    private final AuditLogService auditLogService;

    public CreatorToolCallValidator(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /**
     * Resolves and validates a raw tool-name string. Writes an audit row and throws
     * {@link ToolCallValidator.ToolCallRejectedException} for an unknown name or a
     * Forbidden-tier mapping; callers must not invoke any executor if this throws.
     *
     * @param creatorUserId the {@code users.id} of the creator the on-behalf token was minted for —
     *     already JWT-verified by the resolver before this runs, never a body value
     */
    public CreatorToolName validateAndResolve(String rawToolName, String creatorUserId) {
        Optional<CreatorToolName> parsed = CreatorToolName.parse(rawToolName);
        if (parsed.isEmpty()) {
            auditLogService.recordToolCall(
                    creatorUserId,
                    rawToolName,
                    null,
                    AuditLogService.OUTCOME_REJECTED,
                    "UNKNOWN_TOOL_NAME",
                    null,
                    null,
                    Map.of("rawToolName", String.valueOf(rawToolName)));
            throw new ToolCallValidator.ToolCallRejectedException(
                    "UNKNOWN_TOOL_NAME",
                    "Tool name is not in the creator tool whitelist: " + rawToolName);
        }

        CreatorToolName toolName = parsed.get();
        MeeraToolTier tier = TIER_BY_TOOL.get(toolName);
        if (tier == null || tier == MeeraToolTier.FORBIDDEN) {
            auditLogService.recordToolCall(
                    creatorUserId,
                    toolName.name(),
                    tier == null ? null : tier.name(),
                    AuditLogService.OUTCOME_REJECTED,
                    "FORBIDDEN_TIER",
                    null,
                    null,
                    Map.of());
            throw new ToolCallValidator.ToolCallRejectedException(
                    "FORBIDDEN_TIER", "Tool maps to a Forbidden-tier capability: " + toolName);
        }

        return toolName;
    }

    /** @return null only for a constant added to the enum without a tier entry above. */
    public MeeraToolTier tierOf(CreatorToolName toolName) {
        return TIER_BY_TOOL.get(toolName);
    }
}
