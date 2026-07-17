package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Generic, tool-agnostic idempotency store (V15 {@code idempotency_keys}) backing
 * {@code IdempotencyService.executeOnce(key, supplier)}. {@code meera_tool_calls} (V14) already
 * carries its own {@code UNIQUE(idempotency_key)} for the 5 Meera tools and remains the primary
 * dedupe/audit ledger for tool-call executors; this table exists for any write path that needs
 * the same insert-first-wins guarantee without a dedicated domain table.
 *
 * <p>The {@code UNIQUE} constraint on {@code idempotencyKey} is the concurrency arbiter — the
 * first concurrent writer to insert successfully proceeds, every other concurrent writer hits a
 * constraint violation and is treated as "already in progress / already done", never as license
 * to re-run the effect.
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKeyRecord {

    public enum Status {
        IN_PROGRESS,
        COMPLETED,
        FAILED
    }

    @Id
    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    @Column(name = "workspace_id", length = 26)
    private String workspaceId;

    @Column(nullable = false, length = 64)
    private String scope;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "result_digest", length = 128)
    private String resultDigest;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected IdempotencyKeyRecord() {}

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public String getScope() {
        return scope;
    }

    public Status getStatus() {
        return status;
    }

    public String getResultDigest() {
        return resultDigest;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void markCompleted(String resultDigest) {
        this.status = Status.COMPLETED;
        this.resultDigest = resultDigest;
        this.completedAt = Instant.now();
    }

    public void markFailed() {
        this.status = Status.FAILED;
        this.completedAt = Instant.now();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final IdempotencyKeyRecord k = new IdempotencyKeyRecord();

        public Builder idempotencyKey(String idempotencyKey) {
            k.idempotencyKey = idempotencyKey;
            return this;
        }

        public Builder workspaceId(String workspaceId) {
            k.workspaceId = workspaceId;
            return this;
        }

        public Builder scope(String scope) {
            k.scope = scope;
            return this;
        }

        public IdempotencyKeyRecord build() {
            k.createdAt = Instant.now();
            if (k.status == null) {
                k.status = Status.IN_PROGRESS;
            }
            return k;
        }
    }
}
