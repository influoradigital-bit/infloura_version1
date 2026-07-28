package com.influora.service.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.common.ApiException;
import com.influora.common.Ulids;
import com.influora.domain.entity.AdminAuditLog;
import com.influora.domain.entity.AdminUser;
import com.influora.domain.enums.AdminAuditLogSource;
import com.influora.domain.enums.AdminRole;
import com.influora.repository.AdminAuditLogRepository;
import com.influora.repository.AdminAuditLogSpecs;
import com.influora.repository.AdminUserRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.admin.AdminAuditLogDtos.AuditLogEntryDto;
import com.influora.web.dto.admin.AdminAuditLogDtos.PagedAuditLogDto;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code admin_audit_log} writer (V34__admin_tables.sql) — first real implementation, built
 * per {@code wiki/admin-progress/AUDIT-LOG-WRITE-SPEC.md} (Kabir). Read that spec before touching
 * this class. Every rule below is annotated with the spec rule it implements.
 *
 * <p><b>Rule 1 (server-derived identity, never client-supplied):</b> this is deliberately a
 * server-internal service method — NOT a {@code POST /admin/audit} HTTP endpoint the client can
 * call — per the spec's stated preference ("Prefer... converting this to a server-internal call...
 * since the client has no legitimate need to supply anything but action/entityType/entityId/
 * reason/diff-fields"). {@link #record} takes {@link AuthPrincipal} + {@link HttpServletRequest}
 * (never a client-supplied adminId/adminEmail/ipAddress DTO field) and re-resolves {@code
 * admin_email} fresh from {@code admin_users} by id at write time, exactly as the spec requires —
 * never trusts {@code principal.getEmail()} from the JWT claim as a shortcut.
 *
 * <p><b>Rule 2 (field-allowlisted snapshots, never raw entity dumps):</b> {@link #record} accepts
 * only pre-built {@code Map<String,Object>} snapshots from the caller, then filters them AGAIN
 * here against {@link #FIELD_ALLOWLIST} keyed by {@code entityType} — defense in depth in case a
 * future call site forgets to pre-filter. Any field not on the allow-list for its entity type is
 * dropped (logged, not persisted), never stored.
 *
 * <p><b>Rule 3 (action/entity_type allow-lists):</b> {@link #ALLOWED_ACTIONS} mirrors {@code
 * AuditAction} in {@code src/admin/utils/auditLogger.ts}; {@link #ALLOWED_ENTITY_TYPES} mirrors
 * the spec's "safe to log" table. An action or entity type outside these sets fails the write
 * (caught by {@link #record}'s outer try/catch per Rule 5) rather than being stored as an
 * unbounded free-form string.
 *
 * <p><b>Rule 4 (reason is free text but capped):</b> {@link #MAX_REASON_LENGTH} truncates rather
 * than rejects — losing the tail of an overlong reason is preferable to losing the audit row
 * entirely for what is otherwise a legitimate admin action.
 *
 * <p><b>Rule 5 (failures must not block the underlying action, but must not be silent):</b> {@link
 * #record} never throws — every failure path is caught and logged via {@link #log} at ERROR level
 * (application log, not a silently swallowed exception), matching {@code auditLogger.ts}'s
 * client-side "must never block or break the admin action it is recording" contract on the server
 * side too. Callers (e.g. {@code AdminBrandService}) call this AFTER the underlying mutation has
 * already been persisted, so an audit-write failure never rolls back or blocks the real action.
 *
 * <p><b>Read side (P2, cycle 7, {@code AuditLogController} — the last controller on Vikram's
 * TASK_ASSIGNMENTS.md list):</b> {@link #list} and {@link #getByEntity} back {@code GET
 * /admin/audit} and {@code GET /admin/audit/entity/{entityType}/{entityId}} respectively, matching
 * {@code auditApi.list}/{@code .getByEntity} in {@code src/admin/services/api-contracts.ts}
 * exactly. Both are gated to {@code SUPER_ADMIN} only via {@link
 * AdminContextService#requireRoleWithMfaSatisfied} — per {@code
 * src/admin/__tests__/role-permission-matrix.md}'s Audit Logs section ("List Audit Logs" / "View
 * Audit by Entity" are both SUPER_ADMIN-only, ADMIN and SUPPORT both excluded, "admin oversight" —
 * note this is stricter than most of the admin surface, which is SUPER_ADMIN+ADMIN) and confirmed
 * against {@code ROLE_CAPABILITIES} in {@code src/admin/types/admin.types.ts} ({@code
 * 'audit.view'} appears only under {@code AdminRole.SUPER_ADMIN}, with an explicit comment on the
 * {@code ADMIN} block noting it is deliberately excluded). Reads never write to the audit trail
 * themselves — that would recurse.
 */
@Service
public class AdminAuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuditLogService.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int MAX_REASON_LENGTH = 2000;

    /** Length cap for client-submitted {@code oldValue}/{@code newValue} strings — see {@link
     * #recordClientEntry}'s javadoc for why these aren't run through {@link #allowlistedJson}. */
    private static final int MAX_CLIENT_VALUE_LENGTH = 4000;

    /** Mirrors {@code AuditAction} in src/admin/utils/auditLogger.ts. */
    private static final Set<String> ALLOWED_ACTIONS =
            Set.of(
                    "LOGIN",
                    "LOGOUT",
                    "SESSION_EXPIRED",
                    "VIEW",
                    "CREATE",
                    "UPDATE",
                    "DELETE",
                    "APPROVE",
                    "REJECT",
                    "SUSPEND",
                    "REINSTATE",
                    "ESCROW_RELEASE",
                    "ESCROW_HOLD",
                    "ESCROW_REFUND",
                    "PAYOUT_RETRY",
                    "BUDGET_OVERRIDE",
                    "TIER_ADJUST",
                    "PERMISSION_DENIED");

    /** Mirrors the spec's "safe to log" entity_type table, extended as real writers get built. */
    private static final Set<String> ALLOWED_ENTITY_TYPES =
            Set.of(
                    "ADMIN_USER",
                    "SUPPORT_TICKET",
                    "CONTENT_FLAG",
                    "BRAND",
                    "CREATOR",
                    "CAMPAIGN",
                    "ESCROW",
                    "WALLET",
                    "PLATFORM_FEE_CONFIG",
                    // Kabir security review, phase1-admin-panel-escrow-security.md High finding #2:
                    // DisputeService#resolveDispute previously only wrote per-settled-escrow-hold
                    // audit rows, so an empty settlement list left zero trace of the resolution
                    // decision itself. This entity type backs that unconditional top-level entry.
                    "DISPUTE",
                    // Task 25 (AdminBillingController) — first real caller of TIER_ADJUST (comp
                    // grant) and BUDGET_OVERRIDE (plan override) against a real entity type.
                    // Deliberately NOT reusing the pre-existing WALLET type: these actions mutate a
                    // Subscription row (plan/status/comp fields), not a wallet balance — WALLET
                    // would be a shoehorned, inaccurate label for what's actually being logged. See
                    // AdminBillingController class javadoc for the full reasoning.
                    "SUBSCRIPTION");

    /**
     * Per-entity_type field allow-list for {@code old_value}/{@code new_value} snapshots — NEVER
     * log {@code mfa_secret}/{@code password_hash}/{@code token_hash} or any raw entity dump,
     * per the spec's hard-banned list. {@code BRAND} rows are backed by the {@code workspaces}
     * table (see {@code AdminBrandService} — {@code Workspace} is the Brand entity), so its
     * allow-list uses the KYC/suspension columns that table actually carries, not a fabricated
     * shape.
     */
    private static final Map<String, Set<String>> FIELD_ALLOWLIST =
            Map.of(
                    "BRAND",
                            Set.of(
                                    "id",
                                    "name",
                                    // Vikram, brand profile-edit path (PUT /admin/brands/{id}):
                                    // industry/size/email are non-secret contact/profile fields
                                    // deliberately added to log old->new on an admin profile edit.
                                    // Kabir: confirm these three are safe to persist in the audit
                                    // trail (they are plain contact/profile values already returned
                                    // in BrandDetailDto — no PII beyond the billing email).
                                    "industry",
                                    "size",
                                    "email",
                                    "verificationStatus",
                                    "isSuspended",
                                    "suspendedReason",
                                    "kycRejectionReason"),
                    "ADMIN_USER", Set.of("id", "email", "role", "isActive", "mfaEnabled"),
                    "SUPPORT_TICKET", Set.of("id", "status", "priority", "assignedTo", "category"),
                    "CONTENT_FLAG",
                            Set.of("id", "status", "actionTaken", "contentType", "contentId"),
                    // "tier" backs the TIER_ADJUST action (PUT /admin/creators/{id}/tier);
                    // "name"/"niche" back the creator profile-edit path (PUT /admin/creators/{id})
                    // — Vikram. Both are non-secret profile fields already returned in
                    // CreatorDetailDto. Kabir: confirm safe to persist in the audit trail.
                    "CREATOR", Set.of("id", "isSuspended", "applicationStatus", "tier", "name", "niche"),
                    // Vikram, campaign budget-override path (POST /admin/brands/{id}/campaigns/
                    // {campaignId}/budget-override) — MONEY PATH. No secrets on the campaign budget
                    // fields; explicit allow-list per Rule 2 rather than a raw entity dump. The
                    // override writes "budget" (== budgetMax); budgetMin/budgetMax/status are listed
                    // for any future campaign audit writer. Kabir: money-path — verify.
                    "CAMPAIGN", Set.of("id", "budget", "budgetMin", "budgetMax", "status"),
                    // PlatformFeeAdminService (wiki/decisions/admin-pending-tasks-directive.md
                    // item #5) — no secrets on this entity, but still explicit allow-list per
                    // Rule 2 discipline rather than a raw entity dump.
                    "PLATFORM_FEE_CONFIG",
                            Set.of(
                                    "id",
                                    "brandFeePercent",
                                    "creatorFeePercent",
                                    "razorpayAbsorbedByPlatform",
                                    "approvedBy",
                                    "effectiveAt"),
                    // Dispute resolution decision only — no secrets on this entity. resolutionNotes
                    // is passed separately as the `reason` free-text field on record(), not here.
                    "DISPUTE", Set.of("id", "status", "resolvedByAdminId"),
                    // AdminBillingService (Task 25) comp/override snapshots. No secrets on this
                    // entity — razorpaySubscriptionId is intentionally EXCLUDED even though it's
                    // not a secret, because AdminBillingService#grantAdminPlan refuses to touch a
                    // row that has one set (see that method's ALREADY_PAID_SUBSCRIBER guard), so it
                    // is never a meaningful before/after value for this specific action.
                    "SUBSCRIPTION",
                            Set.of(
                                    "subscriptionId",
                                    "workspaceId",
                                    "planCode",
                                    "status",
                                    "isComp",
                                    "compReason",
                                    "compExpiresAt",
                                    "currentPeriodEnd"));

    private final AdminAuditLogRepository adminAuditLogRepository;
    private final AdminUserRepository adminUserRepository;
    private final AdminContextService adminContext;

    public AdminAuditLogService(
            AdminAuditLogRepository adminAuditLogRepository,
            AdminUserRepository adminUserRepository,
            AdminContextService adminContext) {
        this.adminAuditLogRepository = adminAuditLogRepository;
        this.adminUserRepository = adminUserRepository;
        this.adminContext = adminContext;
    }

    /**
     * Records one admin_audit_log row. Never throws — see class javadoc Rule 5. {@code
     * oldValueAllowed}/{@code newValueAllowed} should already be pre-filtered by the caller to the
     * fields it actually intends to snapshot (this method re-filters defensively against {@link
     * #FIELD_ALLOWLIST} regardless — never pass a raw entity or DTO here).
     */
    public void record(
            AuthPrincipal actingAdmin,
            HttpServletRequest request,
            String action,
            String entityType,
            String entityId,
            Map<String, Object> oldValueAllowed,
            Map<String, Object> newValueAllowed,
            String reason) {
        try {
            doRecord(
                    actingAdmin,
                    request,
                    action,
                    entityType,
                    entityId,
                    oldValueAllowed,
                    newValueAllowed,
                    reason);
        } catch (Exception e) {
            // Rule 5: a gap in the audit trail is itself security-relevant — log loudly, but never
            // propagate. The caller's already-committed mutation must not be affected by this.
            log.error(
                    "admin_audit_log write FAILED — action={} entityType={} entityId={} adminUserId={}",
                    action,
                    entityType,
                    entityId,
                    actingAdmin != null ? actingAdmin.getUserId() : null,
                    e);
        }
    }

    /**
     * {@code POST /admin/audit} write path (backs {@code AuditLogController#create} and the
     * client-side best-effort logger {@code src/admin/utils/auditLogger.ts}). That utility's own
     * header comment marks this as a "convenience/queue trail," explicitly NOT the
     * compliance-critical path — the source-of-truth writes are {@link #record}, called
     * server-internally right after each real mutation (e.g. {@code PlatformFeeAdminService
     * #update}). So this method mirrors {@link #record}'s Rule 5 ("never throws for a write
     * problem, log and drop instead") for everything EXCEPT the role gate below — a lost
     * convenience-trail row is not a compliance gap, but an unauthorized caller still gets a
     * clean 403, same as {@link #list}/{@link #getByEntity}.
     *
     * <p><b>Role-gating:</b> SUPER_ADMIN only — same gate as the read side on this controller
     * (see class javadoc "Read side" note), not the broader SUPER_ADMIN+ADMIN allow-list used
     * elsewhere in the admin surface.
     *
     * <p><b>Rule 1 (server-derived identity) still applies in full:</b> the caller-supplied
     * {@code adminId} on {@code CreateAuditLogEntryRequest} is never read here — {@code id}/
     * {@code adminEmail}/{@code ipAddress}/{@code timestamp} are always re-derived from {@code
     * actingAdmin} and {@code request}, exactly like {@link #doRecord}, so a compromised or
     * careless client can't forge attribution on its own audit rows.
     *
     * <p>Unlike {@link #record}, {@code oldValue}/{@code newValue} here are the client's own
     * pre-serialized strings ({@code AuditLogEntry.oldValue}/{@code .newValue}: {@code string},
     * not a structured diff — see {@code auditLogger.ts}'s {@code serializeAuditValue}). Rule 2's
     * per-entity-type field allow-list is a map-shaped filter that doesn't apply to an opaque
     * client string, so instead of routing through {@link #allowlistedJson}, this just
     * length-caps both fields defensively via {@link #truncate}.
     */
    public void recordClientEntry(
            AuthPrincipal actingAdmin,
            HttpServletRequest request,
            String action,
            String entityType,
            String entityId,
            String oldValue,
            String newValue,
            String reason) {
        adminContext.requireRoleWithMfaSatisfied(actingAdmin, AdminRole.SUPER_ADMIN);

        try {
            // Kabir 2.3 (log injection): action/entityType are rejected/unrecognized-input values
            // (the very case where a client is most likely to be probing), so strip CR/LF before
            // they ever reach a log line — otherwise an attacker can forge fake log entries by
            // embedding newlines in either field.
            if (!ALLOWED_ACTIONS.contains(action)) {
                log.warn(
                        "admin_audit_log (client POST) dropped — unrecognized action: {}",
                        sanitizeForLog(action));
                return;
            }
            if (!ALLOWED_ENTITY_TYPES.contains(entityType)) {
                log.warn(
                        "admin_audit_log (client POST) dropped — unrecognized entityType: {}",
                        sanitizeForLog(entityType));
                return;
            }

            AdminUser admin =
                    adminUserRepository
                            .findById(actingAdmin.getUserId())
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "admin_audit_log: acting admin not found: "
                                                            + actingAdmin.getUserId()));

            // Kabir 2.2 (defense-in-depth): strip raw ASCII control chars from the client-supplied
            // free-text fields before persisting, in addition to the existing length caps — these
            // render back out in the admin audit viewer, and a control char (e.g. a terminal
            // escape sequence) has no legitimate reason to be in an audit note.
            AdminAuditLog entry =
                    AdminAuditLog.builder()
                            .id(Ulids.newUlid())
                            .adminId(admin.getId())
                            .adminEmail(admin.getEmail())
                            .action(action)
                            .entityType(entityType)
                            .entityId(entityId)
                            .oldValueJson(truncate(stripControlChars(oldValue), MAX_CLIENT_VALUE_LENGTH))
                            .newValueJson(truncate(stripControlChars(newValue), MAX_CLIENT_VALUE_LENGTH))
                            .reason(truncate(stripControlChars(reason), MAX_REASON_LENGTH))
                            .ipAddress(clientIp(request))
                            // Kabir 2.1: this is the client-submitted POST /admin/audit path — the
                            // row is NOT proof a mutation actually happened, unlike #record below.
                            .source(AdminAuditLogSource.CLIENT_REPORTED)
                            .build();

            adminAuditLogRepository.save(entry);
        } catch (Exception e) {
            // Rule 5: same never-propagate contract as record() — see javadoc above for why this
            // convenience trail's write failures don't need to be compliance-critical.
            log.error(
                    "admin_audit_log (client POST) write FAILED — action={} entityType={} entityId={} adminUserId={}",
                    action,
                    entityType,
                    entityId,
                    actingAdmin != null ? actingAdmin.getUserId() : null,
                    e);
        }
    }

    private static String truncate(String value, int maxLength) {
        return value != null && value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    /** Kabir 2.3: replaces CR/LF with '_' so a client-supplied value can never forge a fake log line. */
    private static String sanitizeForLog(String value) {
        return value == null ? null : value.replaceAll("[\\r\\n]", "_");
    }

    /** Kabir 2.2: strips raw ASCII control chars (0x00-0x1F, 0x7F, includes CR/LF) — audit
     * free-text fields have no legitimate reason to carry them. Applied on top of, not instead
     * of, the existing length caps. */
    private static String stripControlChars(String value) {
        return value == null ? null : value.replaceAll("[\\x00-\\x1F\\x7F]", "");
    }

    /**
     * {@code GET /admin/audit} — filtered, paginated audit trail. SUPER_ADMIN only (see class
     * javadoc "Read side" note). {@code startDate}/{@code endDate} are ISO-8601 instants, same
     * parse convention as {@code AnalyticsController#parseInstant} (400 {@code
     * INVALID_DATE_RANGE} on an unparseable value, {@code null} means "no bound").
     */
    @Transactional(readOnly = true)
    public PagedAuditLogDto list(
            AuthPrincipal principal,
            String adminId,
            String entityType,
            String action,
            String startDate,
            String endDate,
            int page,
            int pageSize) {
        adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN);

        Instant start = parseInstant(startDate, "startDate");
        Instant end = parseInstant(endDate, "endDate");

        int safePage = Math.max(page, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), 100);

        Specification<AdminAuditLog> spec =
                AdminAuditLogSpecs.withFilters(adminId, entityType, action, start, end);
        Page<AdminAuditLog> result =
                adminAuditLogRepository.findAll(
                        spec,
                        PageRequest.of(safePage - 1, safePageSize, Sort.by(Sort.Direction.DESC, "createdAt")));

        List<AuditLogEntryDto> data = result.getContent().stream().map(AdminAuditLogService::toDto).toList();
        return new PagedAuditLogDto(
                data, result.getTotalElements(), safePage, safePageSize, result.getTotalPages());
    }

    /**
     * {@code GET /admin/audit/entity/{entityType}/{entityId}} — full unpaginated action history
     * for one entity (e.g. every action ever taken on a given brand). SUPER_ADMIN only (see class
     * javadoc "Read side" note). Unpaginated deliberately: this mirrors {@code
     * auditApi.getByEntity}'s {@code AuditLogEntry[]} (non-paginated) return type in
     * api-contracts.ts — per-entity history is expected to stay small enough for a single page in
     * practice (one brand/creator/ticket's lifetime of admin actions), unlike the global list.
     */
    @Transactional(readOnly = true)
    public List<AuditLogEntryDto> getByEntity(AuthPrincipal principal, String entityType, String entityId) {
        adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN);
        return adminAuditLogRepository
                .findByEntityTypeAndEntityIdOrderByCreatedAtDesc(entityType, entityId)
                .stream()
                .map(AdminAuditLogService::toDto)
                .toList();
    }

    private static AuditLogEntryDto toDto(AdminAuditLog entry) {
        return new AuditLogEntryDto(
                entry.getId(),
                entry.getAdminId(),
                entry.getAdminEmail(),
                entry.getAction(),
                entry.getEntityType(),
                entry.getEntityId(),
                entry.getOldValueJson(),
                entry.getNewValueJson(),
                entry.getReason(),
                entry.getIpAddress(),
                entry.getSource() != null ? entry.getSource().name() : null,
                entry.getCreatedAt());
    }

    /** Same parse/error convention as {@code AnalyticsController#parseInstant}. */
    private static Instant parseInstant(String value, String paramName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            throw new ApiException(
                    "INVALID_DATE_RANGE",
                    "Invalid " + paramName + " — must be an ISO-8601 instant",
                    HttpStatus.BAD_REQUEST);
        }
    }

    private void doRecord(
            AuthPrincipal actingAdmin,
            HttpServletRequest request,
            String action,
            String entityType,
            String entityId,
            Map<String, Object> oldValueAllowed,
            Map<String, Object> newValueAllowed,
            String reason) {
        if (!ALLOWED_ACTIONS.contains(action)) {
            throw new IllegalArgumentException("Unrecognized admin_audit_log action: " + action);
        }
        if (!ALLOWED_ENTITY_TYPES.contains(entityType)) {
            throw new IllegalArgumentException(
                    "Unrecognized admin_audit_log entity_type: " + entityType);
        }

        // Rule 1: admin_id/admin_email are ALWAYS re-resolved server-side from admin_users by id —
        // never trust actingAdmin.getEmail() (a JWT claim) as a shortcut, and there is no
        // client-supplied identity field anywhere in this method's signature to begin with.
        AdminUser admin =
                adminUserRepository
                        .findById(actingAdmin.getUserId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "admin_audit_log: acting admin not found: "
                                                        + actingAdmin.getUserId()));

        String ipAddress = clientIp(request);

        String reasonTruncated =
                reason != null && reason.length() > MAX_REASON_LENGTH
                        ? reason.substring(0, MAX_REASON_LENGTH)
                        : reason;

        AdminAuditLog entry =
                AdminAuditLog.builder()
                        .id(Ulids.newUlid())
                        .adminId(admin.getId())
                        .adminEmail(admin.getEmail())
                        .action(action)
                        .entityType(entityType)
                        .entityId(entityId)
                        .oldValueJson(allowlistedJson(entityType, oldValueAllowed))
                        .newValueJson(allowlistedJson(entityType, newValueAllowed))
                        .reason(reasonTruncated)
                        .ipAddress(ipAddress)
                        // Kabir 2.1: this is the server-internal path, always called right after
                        // the real mutation it documents has already been persisted — the
                        // authoritative source, as opposed to recordClientEntry's CLIENT_REPORTED.
                        .source(AdminAuditLogSource.SERVER_INTERNAL)
                        .build();

        adminAuditLogRepository.save(entry);
    }

    /** Rule 2: filters {@code raw} to only the entity type's allow-listed field names, then serializes. */
    private String allowlistedJson(String entityType, Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        Set<String> allowed = FIELD_ALLOWLIST.getOrDefault(entityType, Set.of());
        Map<String, Object> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, Object> field : raw.entrySet()) {
            if (allowed.contains(field.getKey())) {
                filtered.put(field.getKey(), field.getValue());
            } else {
                log.warn(
                        "admin_audit_log: dropped non-allowlisted field '{}' for entityType={} — "
                                + "expand FIELD_ALLOWLIST deliberately if this field is actually safe, "
                                + "never bypass the filter",
                        field.getKey(),
                        entityType);
            }
        }
        if (filtered.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(filtered);
        } catch (Exception e) {
            throw new IllegalStateException("admin_audit_log: failed to serialize snapshot", e);
        }
    }

    /**
     * Per {@code AUDIT-LOG-WRITE-SPEC.md} Rule 1a: do NOT trust {@code X-Forwarded-For} for the
     * forensic audit trail — it is caller-supplied and any admin (including a compromised or
     * malicious one) can set it on their own request to record a false IP for exactly the
     * actions (KYC reject, suspend, reinstate) this table exists to make accountable. Unlike
     * {@code AuthRateLimitFilter#clientIp} (rate-limit bucket keying, where the worst case of
     * trusting the header is a bypass), a spoofed forensic record here is worse than an absent one.
     *
     * <p>[SEC: Kabir CR-11 endpoint red-team, Blocker-1] The sentence that used to end this
     * paragraph — "use {@code getRemoteAddr()} only until infra documents a trusted-proxy topology
     * and wires a {@code ForwardedHeaderFilter}/equivalent" — had quietly become the description of
     * a live vulnerability rather than a safe holding position. Infra DID wire the equivalent:
     * the deploys set {@code SERVER_FORWARD_HEADERS_STRATEGY=framework}, whose
     * {@code ForwardedHeaderFilter} rewrote {@code getRemoteAddr()} to the client-spoofable
     * left-most {@code X-Forwarded-For} entry with no trusted-proxy validation. **Every IP written
     * to this audit table was attacker-forgeable** — the exact outcome this comment set out to
     * prevent, reached by satisfying its own stated precondition.
     *
     * <p>{@code getRemoteAddr()} is now correct again, and for a real reason rather than by
     * accident: {@code forward-headers-strategy: native} resolves the peer through Tomcat's
     * {@code RemoteIpValve}, which validates against {@code internal-proxies} and walks XFF
     * right-to-left. Keep reading {@code getRemoteAddr()}; do NOT reintroduce header parsing here.
     */
    private static String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
