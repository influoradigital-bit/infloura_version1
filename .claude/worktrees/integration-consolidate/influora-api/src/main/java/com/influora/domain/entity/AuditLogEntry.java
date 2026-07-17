package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Append-only audit row (V15 {@code audit_log}) — every money-affecting tool call, every
 * rejected/forbidden tool-call, and every internal-auth rejection is recorded here by
 * {@code AuditLogService}. No service in this codebase issues an UPDATE or DELETE against this
 * table; rows are insert-only by construction (no setters below except the builder).
 *
 * <p>{@code detailJson} must never contain PII, secrets, or full prompt/transcript text — only
 * shapes/ids/counts per the redaction discipline in 09-ADVANCED-SECURITY-MEASURES.md.
 */
@Entity
@Table(name = "audit_log")
public class AuditLogEntry {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "workspace_id", length = 26)
    private String workspaceId;

    @Column(name = "actor_type", nullable = false, length = 32)
    private String actorType;

    @Column(name = "actor_id", length = 64)
    private String actorId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "tool_name", length = 32)
    private String toolName;

    @Column(name = "tool_tier", length = 16)
    private String toolTier;

    @Column(nullable = false, length = 32)
    private String outcome;

    @Column(name = "reason_code", length = 64)
    private String reasonCode;

    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    @Column(name = "server_amount", precision = 12, scale = 2)
    private BigDecimal serverAmount;

    @Column(name = "before_balance", precision = 14, scale = 2)
    private BigDecimal beforeBalance;

    @Column(name = "after_balance", precision = 14, scale = 2)
    private BigDecimal afterBalance;

    @Column(name = "request_digest", length = 128)
    private String requestDigest;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail_json", columnDefinition = "json")
    private String detailJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuditLogEntry() {}

    public String getId() {
        return id;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public String getActorType() {
        return actorType;
    }

    public String getActorId() {
        return actorId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getToolName() {
        return toolName;
    }

    public String getToolTier() {
        return toolTier;
    }

    public String getOutcome() {
        return outcome;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public BigDecimal getServerAmount() {
        return serverAmount;
    }

    public BigDecimal getBeforeBalance() {
        return beforeBalance;
    }

    public BigDecimal getAfterBalance() {
        return afterBalance;
    }

    public String getRequestDigest() {
        return requestDigest;
    }

    public String getDetailJson() {
        return detailJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final AuditLogEntry e = new AuditLogEntry();

        public Builder id(String id) {
            e.id = id;
            return this;
        }

        public Builder workspaceId(String workspaceId) {
            e.workspaceId = workspaceId;
            return this;
        }

        public Builder actorType(String actorType) {
            e.actorType = actorType;
            return this;
        }

        public Builder actorId(String actorId) {
            e.actorId = actorId;
            return this;
        }

        public Builder eventType(String eventType) {
            e.eventType = eventType;
            return this;
        }

        public Builder toolName(String toolName) {
            e.toolName = toolName;
            return this;
        }

        public Builder toolTier(String toolTier) {
            e.toolTier = toolTier;
            return this;
        }

        public Builder outcome(String outcome) {
            e.outcome = outcome;
            return this;
        }

        public Builder reasonCode(String reasonCode) {
            e.reasonCode = reasonCode;
            return this;
        }

        public Builder idempotencyKey(String idempotencyKey) {
            e.idempotencyKey = idempotencyKey;
            return this;
        }

        public Builder serverAmount(BigDecimal serverAmount) {
            e.serverAmount = serverAmount;
            return this;
        }

        public Builder beforeBalance(BigDecimal beforeBalance) {
            e.beforeBalance = beforeBalance;
            return this;
        }

        public Builder afterBalance(BigDecimal afterBalance) {
            e.afterBalance = afterBalance;
            return this;
        }

        public Builder requestDigest(String requestDigest) {
            e.requestDigest = requestDigest;
            return this;
        }

        public Builder detailJson(String detailJson) {
            e.detailJson = detailJson;
            return this;
        }

        public AuditLogEntry build() {
            e.createdAt = Instant.now();
            return e;
        }
    }
}
