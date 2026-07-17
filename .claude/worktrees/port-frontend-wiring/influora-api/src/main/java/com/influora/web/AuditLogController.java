package com.influora.web;

import com.influora.security.AuthPrincipal;
import com.influora.service.admin.AdminAuditLogService;
import com.influora.web.dto.admin.AdminAuditLogDtos.AuditLogEntryDto;
import com.influora.web.dto.admin.AdminAuditLogDtos.PagedAuditLogDto;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Audit trail read API (src/admin/TASK_ASSIGNMENTS.md P2: "Audit log API" — Vikram, cycle 7, the
 * last controller on his TASK_ASSIGNMENTS.md list). This is the READ side only — every write to
 * {@code admin_audit_log} already goes through {@code AdminAuditLogService#record}, called
 * server-internally from {@code AdminBrandController}/{@code AdminCreatorController}/{@code
 * AdminSupportController} (cycle 4 onward); this controller adds no new writer. Mounted at {@code
 * /admin/audit} (full path {@code /api/v1/admin/audit/...} given {@code
 * server.servlet.context-path=/api/v1} — same convention/caveat as every other {@code
 * Admin*Controller}, see {@code AdminBrandController}'s class javadoc for the path-mismatch note
 * that still applies here unchanged).
 *
 * <p>Path/verb shape matches {@code auditApi} in {@code src/admin/services/api-contracts.ts}
 * (Priya) exactly: {@code list} -> {@code GET /audit?adminId&entityType&action&startDate&endDate
 * &page&pageSize}, {@code getByEntity} -> {@code GET /audit/entity/{entityType}/{entityId}}.
 *
 * <p><b>Role-gating (the one decision this controller pins down):</b> audit logs are gated to
 * {@code SUPER_ADMIN} ONLY — NOT {@code SUPER_ADMIN}+{@code ADMIN} like most of the rest of the
 * admin surface. This is explicit, not a default: {@code
 * src/admin/__tests__/role-permission-matrix.md}'s "Audit Logs" section marks both "List Audit
 * Logs" and "View Audit by Entity" as SUPER_ADMIN ✅ / ADMIN ❌ / SUPPORT ❌ ("SUPER_ADMIN only
 * (admin oversight)"), and {@code ROLE_CAPABILITIES[AdminRole.ADMIN]} in {@code admin.types.ts}
 * carries an explicit comment confirming {@code 'audit.view'} is deliberately withheld from
 * {@code ADMIN}. The actual gate lives in {@code AdminAuditLogService#list}/{@code #getByEntity}
 * via {@code AdminContextService#requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN)} —
 * this codebase has zero {@code @PreAuthorize} usage anywhere (manual per-call role checks in the
 * service layer is the established pattern, see {@code AdminContextService} class javadoc), so the
 * controller itself carries no annotation-level enforcement, same as every other {@code
 * Admin*Controller}.
 *
 * <p>Returns raw DTOs (unwrapped, no {@link com.influora.common.ApiResponse} envelope) — same
 * deliberate deviation as every other {@code Admin*Controller}, to match {@code apiRequest()}'s
 * client contract (it wraps whatever JSON body it gets into {@code ApiResponse<T>} itself).
 */
@RestController
@RequestMapping("/admin/audit")
public class AuditLogController {

    private final AdminAuditLogService adminAuditLogService;

    public AuditLogController(AdminAuditLogService adminAuditLogService) {
        this.adminAuditLogService = adminAuditLogService;
    }

    @GetMapping
    public PagedAuditLogDto list(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false) String adminId,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        return adminAuditLogService.list(
                principal, adminId, entityType, action, startDate, endDate, page, pageSize);
    }

    @GetMapping("/entity/{entityType}/{entityId}")
    public List<AuditLogEntryDto> getByEntity(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String entityType,
            @PathVariable String entityId) {
        return adminAuditLogService.getByEntity(principal, entityType, entityId);
    }
}
