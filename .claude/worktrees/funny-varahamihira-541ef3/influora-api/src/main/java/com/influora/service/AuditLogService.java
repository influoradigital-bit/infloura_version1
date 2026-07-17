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
