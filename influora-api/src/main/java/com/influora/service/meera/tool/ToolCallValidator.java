package com.influora.service.meera.tool;

import com.influora.domain.enums.MeeraToolName;
import com.influora.domain.enums.MeeraToolTier;
import com.influora.service.AuditLogService;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Single choke point that every {@code /internal/meera/*} call passes through before an executor
 * runs ([SEC: 5.3 schema + matrix, LB-6]). Validates:
 * <ol>
 *   <li><b>Name-whitelist</b> — the tool name must be one of the 5 defined in
 *       {@link MeeraToolName}. Anything else (typo, hallucinated tool, injected instruction
 *       claiming a 6th capability) is rejected before any executor code runs.</li>
 *   <li><b>Tier gate</b> — the resolved tier must not be {@link MeeraToolTier#FORBIDDEN}. There
 *       is structurally no {@code MeeraToolName} entry that maps to FORBIDDEN (payment-method
 *       changes, payout config, code/config endpoints have no tool name at all — the matrix's
 *       "absent, not blocked" guarantee) but this method still defends the invariant explicitly
 *       so a future accidental addition cannot silently wire a forbidden capability.</li>
 * </ol>
 *
 * <p>Every rejection is logged via {@link AuditLogService} with the reason code — "unknown tool
 * name → reject+log" and "a tool mapping to Forbidden → dropped+logged" are both DoD requirements
 * from 16-VIKRAM-REMAINING-TASKS.md Phase 4 P4.3.
 */
@Component
public class ToolCallValidator {

    /**
     * The tool whitelist + tier. 06-MEERA-PERMISSIONS-MATRIX.md's original 5; {@code
     * get_campaign_performance} (Phase 2 item 2.2, R-tier) is the first tool added after that
     * matrix was written — see {@code wiki/ai-review/meera-label-to-moat-build-plan.md} §2.2.
     */
    private static final Map<MeeraToolName, MeeraToolTier> TIER_BY_TOOL = new EnumMap<>(MeeraToolName.class);

    static {
        TIER_BY_TOOL.put(MeeraToolName.show_creators, MeeraToolTier.R);
        TIER_BY_TOOL.put(MeeraToolName.calculate_budget, MeeraToolTier.R);
        TIER_BY_TOOL.put(MeeraToolName.create_campaign, MeeraToolTier.D);
        TIER_BY_TOOL.put(MeeraToolName.request_payment, MeeraToolTier.C);
        TIER_BY_TOOL.put(MeeraToolName.confirm_launch, MeeraToolTier.C);
        TIER_BY_TOOL.put(MeeraToolName.get_campaign_performance, MeeraToolTier.R);
    }

    private final AuditLogService auditLogService;

    public ToolCallValidator(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    public static final class ToolCallRejectedException extends RuntimeException {
        private final String reasonCode;

        public ToolCallRejectedException(String reasonCode, String message) {
            super(message);
            this.reasonCode = reasonCode;
        }

        public String getReasonCode() {
            return reasonCode;
        }
    }

    /**
     * Resolves and validates a raw tool-name string against the whitelist + matrix. Throws
     * {@link ToolCallRejectedException} (and records an audit row) for an unknown name or a
     * Forbidden-tier mapping — callers (the controller) must not invoke any executor if this
     * throws.
     */
    public MeeraToolName validateAndResolve(String rawToolName, String workspaceId) {
        MeeraToolName toolName;
        try {
            toolName = MeeraToolName.valueOf(rawToolName);
        } catch (IllegalArgumentException | NullPointerException e) {
            auditLogService.recordToolCall(
                    workspaceId,
                    rawToolName,
                    null,
                    AuditLogService.OUTCOME_REJECTED,
                    "UNKNOWN_TOOL_NAME",
                    null,
                    null,
                    Map.of("rawToolName", String.valueOf(rawToolName)));
            throw new ToolCallRejectedException(
                    "UNKNOWN_TOOL_NAME", "Tool name is not in the 5-tool whitelist: " + rawToolName);
        }

        MeeraToolTier tier = TIER_BY_TOOL.get(toolName);
        if (tier == null || tier == MeeraToolTier.FORBIDDEN) {
            auditLogService.recordToolCall(
                    workspaceId,
                    toolName.name(),
                    tier == null ? null : tier.name(),
                    AuditLogService.OUTCOME_REJECTED,
                    "FORBIDDEN_TIER",
                    null,
                    null,
                    Map.of());
            throw new ToolCallRejectedException(
                    "FORBIDDEN_TIER", "Tool maps to a Forbidden-tier capability: " + toolName);
        }

        return toolName;
    }

    public MeeraToolTier tierOf(MeeraToolName toolName) {
        return TIER_BY_TOOL.get(toolName);
    }
}
