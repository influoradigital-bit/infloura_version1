package com.influora.service.meera;

import com.influora.common.SensitiveTextRedactor;
import com.influora.common.Ulids;
import com.influora.domain.entity.MeeraInteractionLog;
import com.influora.domain.enums.MeeraInteractionEventType;
import com.influora.repository.MeeraInteractionLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single fire-and-forget entry point for Phase 2 item 2.3's flywheel logging (Meera:
 * Label-to-Moat build plan §2.3). Every write point in the codebase ({@code
 * CreateCampaignExecutor}, {@code ConfirmLaunchExecutor}, {@code BrandDeliverableService}, the
 * {@code /internal/meera/interaction-log} and {@code
 * /workspaces/{workspaceId}/meera/interactions/option-tapped} controllers) calls ONLY {@link
 * #record}, never builds/saves a {@link MeeraInteractionLog} directly — that is what makes the
 * redaction-before-persist guarantee structural rather than a per-call-site discipline.
 */
@Service
public class MeeraInteractionLogService {

    private static final Logger log = LoggerFactory.getLogger(MeeraInteractionLogService.class);

    private final MeeraInteractionLogRepository repository;

    public MeeraInteractionLogService(MeeraInteractionLogRepository repository) {
        this.repository = repository;
    }

    /**
     * Records one flywheel event. NEVER throws — a logging failure must not fail the turn/request
     * that triggered it (fire-and-forget guardrail, plan §2.3). Runs in its OWN {@code
     * REQUIRES_NEW} transaction so a caller's later rollback doesn't erase a logged event, and so
     * this method's own persistence failure can never roll back the caller's transaction either —
     * mirrors {@link com.influora.service.AuditLogService}'s documented rationale for the same
     * propagation choice.
     *
     * <p>{@code revisionReason} is redacted via {@link SensitiveTextRedactor#redact} as this
     * method's own first line — no call site can accidentally skip it. Callers pass {@code null}
     * for every parameter that doesn't apply to their event (e.g. {@code toolName}/{@code
     * recommendedFlag} are typically null outside {@code OPTIONS_PRESENTED}/{@code OPTION_TAPPED}).
     *
     * <p><b>SR-2 forward-invariant (Ash's B4):</b> redaction here is a PII/secret backstop, not a
     * prompt-injection defense. This table is write-only today (no read query exists) and {@code
     * revisionReason} never re-enters a prompt — if that ever changes, the re-surfacing code path
     * must additionally wrap the text via Python's {@code _safe()}/{@code wrap_untrusted()} before
     * it reaches a prompt. See {@link SensitiveTextRedactor}'s class javadoc for the full note.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            String workspaceId,
            String sessionId,
            MeeraInteractionEventType eventType,
            String toolName,
            Boolean recommendedFlag,
            String campaignId,
            String revisionReason,
            String promptVersion) {
        try {
            String redactedReason = revisionReason == null ? null : SensitiveTextRedactor.redact(revisionReason);
            repository.save(
                    MeeraInteractionLog.builder()
                            .id(Ulids.newUlid())
                            .workspaceId(workspaceId)
                            .sessionId(sessionId)
                            .eventType(eventType)
                            .toolName(toolName)
                            .recommendedFlag(recommendedFlag)
                            .campaignId(campaignId)
                            .revisionReason(redactedReason)
                            .promptVersion(promptVersion)
                            .build());
        } catch (RuntimeException e) {
            // Never rethrow — a flywheel-logging failure must not fail the turn/request that
            // triggered it. The event is simply lost, not the campaign action/turn itself.
            log.warn(
                    "meera_interaction_log write failed for workspace {} event {} — dropping event",
                    workspaceId,
                    eventType,
                    e);
        }
    }
}
