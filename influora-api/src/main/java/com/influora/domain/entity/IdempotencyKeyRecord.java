package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import org.springframework.data.domain.Persistable;

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
 *
 * <p><b>[SEC: Priya round-2 review fix, foundational] {@code Persistable<String>}.</b> {@code
 * idempotencyKey} is a manually-assigned, non-null {@code @Id} with no {@code @Version}. Without
 * this interface, Spring Data's default {@code isNew()} check ({@code getId() != null &&
 * @Version == null} is false for us since we have no version field, so it falls back to "is the
 * id null" which is NEVER true here) always reports {@code false} for a brand-new instance —
 * {@code SimpleJpaRepository.save()} then ALWAYS routes to {@code entityManager.merge()}
 * (SELECT-then-UPSERT) instead of {@code entityManager.persist()} (a genuine INSERT), so a second
 * reservation for an already-reserved key silently overwrote the first instead of hitting the
 * {@code UNIQUE} constraint this class's own javadoc claims is the arbiter — {@code
 * IdempotencyService#tryReserveTransactional}'s {@code DataIntegrityViolationException} catch
 * was unreachable in practice. The {@code isNew} flag below defaults {@code true} for every
 * object built via {@link Builder} (a genuinely new reservation) and is flipped to {@code false}
 * by {@link #markNotNew()} once Hibernate has loaded (or just persisted) the entity, so {@code
 * save()} on an object freshly returned by {@code find}/{@code findBy*} still correctly routes to
 * {@code merge()} — this repository's only other write paths ({@code
 * IdempotencyReservationReaperJob}, {@code IdempotencyService}) never re-{@code save()} a loaded
 * instance (they use {@code @Modifying} UPDATE queries instead — see {@link
 * com.influora.repository.IdempotencyKeyRecordRepository#markCompleted}/{@code #markFailed}), so
 * that path is untested here but kept correct-by-construction.
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKeyRecord implements Persistable<String> {

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

    /**
     * [SEC: Priya round-2 review fix] Not persisted — governs {@link #isNew()}. {@code true} for
     * every instance created via {@link Builder} (a fresh reservation attempt that must go through
     * {@code persist()}/INSERT); flipped to {@code false} once Hibernate has loaded this row from
     * the DB or just persisted it (see {@link #markNotNew()}).
     */
    @Transient
    private boolean isNew = true;

    protected IdempotencyKeyRecord() {}

    /**
     * [SEC: Priya round-2 review fix] Runs after Hibernate hydrates an entity from a SELECT (a
     * genuinely existing row) and again right after this entity is INSERTed (defensive — a save()
     * call reusing the same Java instance a second time in the same persistence context must not
     * be misread as "still new"). Standard Spring Data recipe for a manually-assigned {@code @Id}
     * with no {@code @Version} column implementing {@link Persistable}.
     */
    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public String getId() {
        return idempotencyKey;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

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
