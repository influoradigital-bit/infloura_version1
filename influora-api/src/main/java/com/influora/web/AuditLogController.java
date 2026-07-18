package com.influora.web;

import com.influora.security.AuthPrincipal;
import com.influora.service.admin.AdminAuditLogService;
import com.influora.web.dto.admin.AdminAuditLogDtos.AuditLogEntryDto;
import com.influora.web.dto.admin.AdminAuditLogDtos.CreateAuditLogEntryRequest;
import com.influora.web.dto.admin.AdminAuditLogDtos.PagedAuditLogDto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Audit trail API (src/admin/TASK_ASSIGNMENTS.md P2: "Audit log API" — Vikram, cycle 7, the last
 * controller on his TASK_ASSIGNMENTS.md list). The GET endpoints are the compliance-critical read
 * side — every server-internal mutation already writes to {@code admin_audit_log} via {@code
 * AdminAuditLogService#record}, called from {@code AdminBrandController}/{@code
 * AdminCreatorController}/{@code AdminSupportController}/{@code PlatformFeeAdminService} (cycle 4
 * onward). {@link #create} (added later, per Priya's admin-panel audit routing) is a second,
 * client-facing write path backing {@code src/admin/utils/auditLogger.ts} — that utility's own
 * header comment marks it a best-effort "convenience/queue trail," NOT a replacement for the
 * server-internal writes above; see {@code AdminAuditLogService#recordClientEntry} javadoc.
 * Mounted at {@code /admin/audit} (full path {@code /api/v1/admin/audit/...} given {@code
 * server.servlet.context-path=/api/v1} — same convention/caveat as every other {@code
 * Admin*Controller}, see {@code AdminBrandController}'s class javadoc for the path-mismatch note
 * that still applies here unchanged).
 *
 * <p>Path/verb shape matches {@code auditApi} in {@code src/admin/services/api-contracts.ts}
 * (Priya) exactly: {@code list} -> {@code GET /audit?adminId&entityType&action&startDate&endDate
 * &page&pageSize}, {@code getByEntity} -> {@code GET /audit/entity/{entityType}/{entityId}}; {@code
 * create} -> {@code POST /audit}, matching {@code auditLogger.ts}'s {@code AUDIT_ENDPOINT}.
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

    /**
     * {@code POST /admin/audit} — client-submitted audit entry, backs {@code
     * src/admin/utils/auditLogger.ts}'s {@code logAdminAction}/{@code auditAction}. See {@code
     * AdminAuditLogService#recordClientEntry} for why {@code body.adminId()} is accepted (matches
     * the client's payload shape) but never trusted, and why this never surfaces a write failure
     * as anything other than the SUPER_ADMIN role-gate 403. No response body — same "fire and
     * forget" contract the client already expects.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public void create(
            @AuthenticationPrincipal AuthPrincipal principal,
            HttpServletRequest request,
            @Valid @RequestBody CreateAuditLogEntryRequest body) {
        adminAuditLogService.recordClientEntry(
                principal,
                request,
                body.action(),
                body.entityType(),
                body.entityId(),
                body.oldValue(),
                body.newValue(),
                body.reason());
    }
}
