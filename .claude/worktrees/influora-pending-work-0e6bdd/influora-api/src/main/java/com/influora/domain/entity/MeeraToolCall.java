package com.influora.domain.entity;

import com.influora.domain.enums.MeeraToolName;
import com.influora.domain.enums.ToolCallStatus;
import com.influora.domain.enums.ToolResultRefType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Idempotency + audit ledger for /internal/meera/* tool-call executors.
 *
 * <p>Schema/entity only in this phase — no executor logic. The executors (Phase 4) will write rows
 * here keyed on the Python-supplied {@code idempotency_key} (the Anthropic {@code tool_use.id}), and
 * record {@code server_amount} as proof that any monetary value was re-derived by Spring, never trusted
 * from the AI (Guardrail 1).
 */
@Entity
@Table(name = "meera_tool_calls")
public class MeeraToolCall {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "workspace_id", nullable = false, length = 26)
    private String workspaceId;

    @Column(name = "conversation_id", length = 26)
    private String conversationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tool_name", nullable = false)
    private MeeraToolName toolName;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ToolCallStatus status;

    @Column(name = "request_digest", length = 128)
    private String requestDigest;

    @Enumerated(EnumType.STRING)
    @Column(name = "result_ref_type", nullable = false)
    private ToolResultRefType resultRefType;

    @Column(name = "result_ref_id", length = 26)
    private String resultRefId;

    @Column(name = "server_amount", precision = 12, scale = 2)
    private BigDecimal serverAmount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MeeraToolCall() {}

    public String getId() {
        return id;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public MeeraToolName getToolName() {
        return toolName;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public ToolCallStatus getStatus() {
        return status;
    }

    public String getRequestDigest() {
        return requestDigest;
    }

    public ToolResultRefType getResultRefType() {
        return resultRefType;
    }

    public String getResultRefId() {
        return resultRefId;
    }

    public BigDecimal getServerAmount() {
        return serverAmount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final MeeraToolCall t = new MeeraToolCall();

        public Builder id(String id) {
            t.id = id;
            return this;
        }

        public Builder workspaceId(String workspaceId) {
            t.workspaceId = workspaceId;
            return this;
        }

        public Builder conversationId(String conversationId) {
            t.conversationId = conversationId;
            return this;
        }

        public Builder toolName(MeeraToolName toolName) {
            t.toolName = toolName;
            return this;
        }

        public Builder idempotencyKey(String idempotencyKey) {
            t.idempotencyKey = idempotencyKey;
            return this;
        }

        public Builder status(ToolCallStatus status) {
            t.status = status;
            return this;
        }

        public Builder requestDigest(String requestDigest) {
            t.requestDigest = requestDigest;
            return this;
        }

        public Builder resultRefType(ToolResultRefType resultRefType) {
            t.resultRefType = resultRefType;
            return this;
        }

        public Builder resultRefId(String resultRefId) {
            t.resultRefId = resultRefId;
            return this;
        }

        public Builder serverAmount(BigDecimal serverAmount) {
            t.serverAmount = serverAmount;
            return this;
        }

        public MeeraToolCall build() {
            t.createdAt = Instant.now();
            if (t.status == null) {
                t.status = ToolCallStatus.RECEIVED;
            }
            if (t.resultRefType == null) {
                t.resultRefType = ToolResultRefType.NONE;
            }
            return t;
        }
    }
}
