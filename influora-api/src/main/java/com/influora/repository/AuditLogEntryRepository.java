package com.influora.repository;

import com.influora.domain.entity.AuditLogEntry;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Insert-only access to the append-only audit_log table (V15). No method here performs an
 * UPDATE or DELETE — {@code save()} on a fresh {@link AuditLogEntry} is always an INSERT since
 * the id is a freshly-minted ULID, never re-used.
 */
public interface AuditLogEntryRepository extends JpaRepository<AuditLogEntry, String> {

    /** Tenant-scoped listing (Guardrail 4) — never call findAll() for this table. */
    List<AuditLogEntry> findByWorkspaceIdOrderByCreatedAtDesc(String workspaceId);

    /**
     * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;14.1.g, B0-35) — the {@code detail_json} column ALONE
     * for one event type inside a time window. Served by {@code idx_audit_event_type} /
     * {@code idx_audit_created_at} (V15).
     *
     * <p><b>It selects one column on purpose.</b> The only caller is the admin rate calibration
     * report, which needs the {@code tier} and {@code total} inside the JSON and nothing else.
     * Returning {@link AuditLogEntry} instead would hand that caller {@code actorId} — the
     * creator's user id — and the report's whole contract is that no creator id and no workspace
     * id can reach the payload. Here the identifying columns are never read out of the database at
     * all, so there is nothing on the object graph to leak by accident later.
     *
     * <p>This is a read of an append-only table; it issues no UPDATE or DELETE, so it does not
     * break the insert-only invariant above.
     */
    @Query("select a.detailJson from AuditLogEntry a "
            + "where a.eventType = :eventType and a.createdAt >= :since and a.detailJson is not null")
    List<String> findDetailJsonByEventTypeSince(
            @Param("eventType") String eventType, @Param("since") Instant since);
}
