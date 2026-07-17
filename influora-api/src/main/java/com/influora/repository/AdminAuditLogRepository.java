package com.influora.repository;

import com.influora.domain.entity.AdminAuditLog;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Insert-only repository for {@code admin_audit_log}. The only writer is {@code
 * AdminAuditLogService} — no other service should call {@code save()} on this repository
 * directly (bypasses the field-allowlist + server-derived-identity rules the service enforces).
 *
 * <p>Read side (P2, cycle 7, {@code AuditLogController}, SUPER_ADMIN-only per {@code
 * src/admin/__tests__/role-permission-matrix.md}): {@link JpaSpecificationExecutor} backs the
 * multi-filter {@code GET /admin/audit} list ({@link AdminAuditLogSpecs}); {@link
 * #findByEntityTypeAndEntityIdOrderByCreatedAtDesc} backs {@code GET
 * /admin/audit/entity/{entityType}/{entityId}} directly since that lookup is a plain two-column
 * equality match with no optional filters, same "finder method vs. Specification" split {@code
 * SupportTicketMessageRepository}/{@code SupportTicketRepository} already use elsewhere in this
 * codebase.
 */
public interface AdminAuditLogRepository
        extends JpaRepository<AdminAuditLog, String>, JpaSpecificationExecutor<AdminAuditLog> {

    List<AdminAuditLog> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(
            String entityType, String entityId);
}
