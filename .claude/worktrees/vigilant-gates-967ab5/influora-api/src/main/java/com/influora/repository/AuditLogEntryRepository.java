package com.influora.repository;

import com.influora.domain.entity.AuditLogEntry;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Insert-only access to the append-only audit_log table (V15). No method here performs an
 * UPDATE or DELETE — {@code save()} on a fresh {@link AuditLogEntry} is always an INSERT since
 * the id is a freshly-minted ULID, never re-used.
 */
public interface AuditLogEntryRepository extends JpaRepository<AuditLogEntry, String> {

    /** Tenant-scoped listing (Guardrail 4) — never call findAll() for this table. */
    List<AuditLogEntry> findByWorkspaceIdOrderByCreatedAtDesc(String workspaceId);
}
