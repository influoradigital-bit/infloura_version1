package com.influora.service;

import com.influora.common.JsonLists;
import com.influora.common.Ulids;
import com.influora.domain.entity.AuditLogEntry;
import com.influora.repository.AuditLogEntryRepository;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Append-only audit trail (Domain E / [SEC: Layer 9, LB-8]) for every money-affecting tool call,
 * every rejected/forbidden tool-call, and every internal-auth rejection. Writes always run in
 * their own {@code REQUIRES_NEW} transaction so an audit record survives even if the caller's
 * transaction later rolls back (a rejected/failed action is exactly the case we most need on
 * record).
 *
 * <p>{@code detail} must never contain PII, secrets, full prompts, or transcripts — only
 * shapes/ids/counts, matching the redaction discipline used elsewhere in the codebase
 * (09-ADVANCED-SECURITY-MEASURES.md).
 */
@Service
public class AuditLogService {

    public static final String ACTOR_MEERA_AI = "MEERA_AI";
    public static final String ACTOR_HUMAN = "HUMAN";
    public static final String ACTOR_SYSTEM = "SYSTEM";
    public static final String ACTOR_SERVICE = "SERVICE";

    public static final String OUTCOME_ALLOWED = "ALLOWED";
    public static final String OUTCOME_REJECTED = "REJECTED";
    public static final String OUTCOME_FAILED = "FAILED";

    private final AuditLogEntryRepository repository;

    public AuditLogService(AuditLogEntryRepository repository) {
        this.repository = repository;
    }

    /** Records a tool-call outcome (allowed, rejected, or failed) for a Meera executor. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordToolCall(
            String workspaceId,
            String toolName,
            String toolTier,
            String outcome,
            String reasonCode,
            String idempotencyKey,
            BigDecimal serverAmount,
            Map<String, Object> detail) {
        repository.save(
                AuditLogEntry.builder()
                        .id(Ulids.newUlid())
                        .workspaceId(workspaceId)
                        .actorType(ACTOR_MEERA_AI)
                        .eventType("TOOL_CALL_" + outcome)
                        .toolName(toolName)
                        .toolTier(toolTier)
                        .outcome(outcome)
                        .reasonCode(reasonCode)
                        .idempotencyKey(idempotencyKey)
                        .serverAmount(serverAmount)
                        .detailJson(JsonLists.toJsonObject(detail))
                        .build());
    }

    /** Records an internal-auth rejection (dual-credential failure) — no workspace may be known yet. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAuthRejection(String workspaceId, String eventType, String reasonCode, String actorId) {
        repository.save(
                AuditLogEntry.builder()
                        .id(Ulids.newUlid())
                        .workspaceId(workspaceId)
                        .actorType(ACTOR_SERVICE)
                        .actorId(actorId)
                        .eventType(eventType)
                        .outcome(OUTCOME_REJECTED)
                        .reasonCode(reasonCode)
                        .build());
    }

    /**
     * Records a general admin-panel action that isn't a Meera tool call, an auth rejection, or a
     * money-mutation event — e.g. T-ADMINMAIL-0903's admin custom email send (control #4: "who
     * sent what to how many" must be answerable from the database after a restart). Not
     * workspace-scoped ({@code workspaceId} left null) since a custom send targets a
     * cross-workspace audience, not one workspace. {@code detail} follows the same redaction
     * discipline as every other write here — shapes/ids/counts only (e.g. {@code campaignId},
     * {@code recipientCount}), never the admin-authored subject/body text itself; that content's
     * source-of-truth record is the dedicated {@code admin_email_campaigns} row, not this table.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAdminAction(
            String actorId, String eventType, String outcome, Map<String, Object> detail) {
        repository.save(
                AuditLogEntry.builder()
                        .id(Ulids.newUlid())
                        .actorType(ACTOR_HUMAN)
                        .actorId(actorId)
                        .eventType(eventType)
                        .outcome(outcome)
                        .detailJson(JsonLists.toJsonObject(detail))
                        .build());
    }

    /**
     * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;14.1.f, B0-33) — records a creator-initiated,
     * non-money, non-tool-call event: currently {@code RATE_QUOTE_ISSUED}, later the secure-link
     * funnel rows. Not workspace-scoped ({@code workspaceId} left null) because a creator acting
     * on her own behalf belongs to no brand workspace — the same shape
     * {@link #recordAdminAction} already uses, and {@code V15__audit_log.sql}'s own comment
     * ("NULL for pre-auth rejections") already precedents a null workspace on this table.
     *
     * <p><b>Modelled on {@link #recordAdminAction}, deliberately NOT on
     * {@link #recordAuthRejection}.</b> &sect;14.1.f originally named {@code recordAuthRejection}
     * as the model and that was unbuildable and dangerous: it takes no detail map — which is the
     * entire point of this method — and it HARD-CODES {@code actorType = ACTOR_SERVICE} and
     * {@code outcome = OUTCOME_REJECTED} with no parameter for either. Every quote would have
     * landed in this append-only table as a rejected service-auth event, poisoning any query or
     * alert built on auth rejections, permanently. {@code outcome} is therefore an EXPLICIT
     * argument here (it is {@code NOT NULL} on the table) and is normally
     * {@link #OUTCOME_ALLOWED}.
     *
     * <p>Not a schema change: {@code audit_log.detail_json} is {@code JSON NULL} mapped
     * {@code columnDefinition = "json"}, {@code event_type} is {@code VARCHAR(64) NOT NULL} and
     * accepts any string, and {@code actor_id} is {@code VARCHAR(64)} against a 26-character ULID.
     *
     * <p>{@code detail} follows the same redaction discipline as every other write here. For
     * {@code RATE_QUOTE_ISSUED} specifically, the caller must never put a floor, a floor total or
     * a range bound in the map — a floor is exactly the number the creator-side info barrier
     * exists to contain, and this table is readable by operators who are not that creator.
     * {@code InfoBarrierRuntimeTest} asserts the key set against an allow-list.
     *
     * @param creatorUserId the acting creator's {@code users.id} — never a
     *     {@code creator_profiles.id}, so this column stays join-compatible with every other
     *     {@code actor_id} on the table
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordCreatorEvent(
            String creatorUserId, String eventType, String outcome, Map<String, Object> detail) {
        repository.save(
                AuditLogEntry.builder()
                        .id(Ulids.newUlid())
                        .actorType(ACTOR_HUMAN)
                        .actorId(creatorUserId)
                        .eventType(eventType)
                        .outcome(outcome)
                        .detailJson(JsonLists.toJsonObject(detail))
                        .build());
    }

    /**
     * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;5.2) — records a SHADOW-MODE risk signal: something the
     * platform observed and deliberately did not act on. Today that is exactly one caller,
     * {@code DealRiskService}'s {@code OFF_PLATFORM_HINT}.
     *
     * <p>Its own method rather than {@link #recordToolCall} because the eventType there is always
     * {@code TOOL_CALL_<outcome>}: a signal raised by a REST read of {@code GET /deals/{id}/risks}
     * is not a tool call, and filing it as one would make "which brands keep steering creators
     * off-platform" unanswerable without a reasonCode scan of every tool-call row ever written.
     * {@code actorType} is {@code SYSTEM} for the same reason — no agent decided anything here.
     *
     * <p>{@code outcome} is {@code ALLOWED} and that is the point: shadow mode means the thing was
     * seen and permitted. A future decision to start blocking would write {@code REJECTED} rows
     * through a different call, leaving the shadow period legible in the trail rather than
     * retroactively ambiguous.
     *
     * <p>{@code detail} follows the same redaction discipline as every other write on this class,
     * and for this caller more strictly than most: the brand's workspace id and a shape, never the
     * creator's identity and never the message that matched.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRiskSignal(
            String workspaceId, String eventType, String reasonCode, Map<String, Object> detail) {
        repository.save(
                AuditLogEntry.builder()
                        .id(Ulids.newUlid())
                        .workspaceId(workspaceId)
                        .actorType(ACTOR_SYSTEM)
                        .eventType(eventType)
                        .outcome(OUTCOME_ALLOWED)
                        .reasonCode(reasonCode)
                        .detailJson(JsonLists.toJsonObject(detail))
                        .build());
    }

    /** Records a money-mutation event with before/after balances (escrow, wallet, payout state changes). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordMoneyEvent(
            String workspaceId,
            String eventType,
            BigDecimal serverAmount,
            BigDecimal beforeBalance,
            BigDecimal afterBalance,
            String idempotencyKey,
            Map<String, Object> detail) {
        repository.save(
                AuditLogEntry.builder()
                        .id(Ulids.newUlid())
                        .workspaceId(workspaceId)
                        .actorType(ACTOR_SYSTEM)
                        .eventType(eventType)
                        .outcome(OUTCOME_ALLOWED)
                        .idempotencyKey(idempotencyKey)
                        .serverAmount(serverAmount)
                        .beforeBalance(beforeBalance)
                        .afterBalance(afterBalance)
                        .detailJson(JsonLists.toJsonObject(detail))
                        .build());
    }
}
