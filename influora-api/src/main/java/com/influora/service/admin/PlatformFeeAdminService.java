package com.influora.service.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.common.ApiException;
import com.influora.domain.entity.AdminUser;
import com.influora.domain.entity.PlatformFeeConfig;
import com.influora.domain.enums.AdminRole;
import com.influora.repository.AdminUserRepository;
import com.influora.repository.PlatformFeeConfigRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.admin.AdminAuditLogDtos.AuditLogEntryDto;
import com.influora.web.dto.admin.PlatformFeeConfigDtos.PlatformFeeConfigDto;
import com.influora.web.dto.admin.PlatformFeeConfigDtos.PlatformFeeHistoryEntryDto;
import com.influora.web.dto.admin.PlatformFeeConfigDtos.UpdatePlatformFeeConfigRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin CRUD/read surface for {@code platform_fee_config} (wiki/decisions/
 * admin-pending-tasks-directive.md item #5, CEO-approved: 10% brand fee, 15% creator fee, Option
 * A — platform absorbs Razorpay processing costs). Backs {@code PlatformFeeAdminController}.
 *
 * <p><b>Frontend contract:</b> response/request shapes match {@code PlatformFeeConfig}/{@code
 * PlatformFeeUpdateRequest}/{@code PlatformFeeHistoryEntry} in {@code
 * src/admin/types/admin.types.ts} exactly — {@code FeeControlPanel.tsx}/{@code useFeeConfig.ts}
 * (Ananya) already shipped against those types with mock data; this is their real-integration
 * follow-up. See {@code PlatformFeeConfigDtos} class javadoc for the one intentional extra field.
 *
 * <p><b>Scope (per the directive, explicit):</b> this service only reads/writes the admin-facing
 * config row. It does NOT charge brand escrow-funding or creator escrow-release against these
 * percentages — that wiring ({@code EscrowService} extension, {@code PlatformFeeService.FeeScope
 * .BRAND}) is separate, not-yet-built scope. The creator-side release deduction that already
 * exists ({@code PlatformFeeService.deductAtRelease}) reads {@link PlatformFeeConfig#getDefaultFeeBps()}
 * directly and is untouched by this class — {@link #update} writes that same column (so the two
 * stay in sync), but this class never itself moves money.
 *
 * <p><b>RBAC:</b> every method gates {@code AdminRole.SUPER_ADMIN} only — this table controls
 * platform revenue take-rate, a strictly higher blast radius than the brand/creator
 * moderation actions that are SUPER_ADMIN+ADMIN elsewhere in the admin surface (matches the
 * directive's "this controls platform revenue, should NOT be ADMIN or SUPPORT tier").
 *
 * <p><b>Audit trail:</b> {@link #update} calls {@link AdminAuditLogService#record} with a
 * before/after allow-listed snapshot (entityType {@code PLATFORM_FEE_CONFIG}) per
 * AUDIT-LOG-WRITE-SPEC.md — fee changes are exactly the "high-stakes action" that spec calls out
 * as mandatory to audit. {@link #history} reads that same audit trail back via {@link
 * AdminAuditLogService#getByEntity} and reshapes each entry's before/after JSON snapshot into
 * {@code PlatformFeeHistoryEntryDto} (matching the frontend's previous/current-value shape)
 * rather than maintaining a separate versioned table — see
 * V42__platform_fee_config_brand_fee_razorpay.sql's header for why this table is a singleton row,
 * not one row per version.
 */
@Service
public class PlatformFeeAdminService {

    private static final String ENTITY_TYPE = "PLATFORM_FEE_CONFIG";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AdminContextService adminContext;
    private final AdminAuditLogService adminAuditLogService;
    private final PlatformFeeConfigRepository platformFeeConfigRepository;
    private final AdminUserRepository adminUserRepository;

    public PlatformFeeAdminService(
            AdminContextService adminContext,
            AdminAuditLogService adminAuditLogService,
            PlatformFeeConfigRepository platformFeeConfigRepository,
            AdminUserRepository adminUserRepository) {
        this.adminContext = adminContext;
        this.adminAuditLogService = adminAuditLogService;
        this.platformFeeConfigRepository = platformFeeConfigRepository;
        this.adminUserRepository = adminUserRepository;
    }

    @Transactional(readOnly = true)
    public PlatformFeeConfigDto getCurrent(AuthPrincipal principal) {
        adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN);
        return toDto(requireConfig());
    }

    @Transactional
    public PlatformFeeConfigDto update(
            AuthPrincipal principal, HttpServletRequest request, UpdatePlatformFeeConfigRequest body) {
        AdminUser admin = adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN);
        PlatformFeeConfig config = requireConfig();

        Map<String, Object> before = snapshot(config);

        int brandFeeBps = toBps(body.brandFeePercent(), "brandFeePercent");
        int creatorFeeBps = toBps(body.creatorFeePercent(), "creatorFeePercent");
        validateRange(config, brandFeeBps, "brandFeePercent");
        validateRange(config, creatorFeeBps, "creatorFeePercent");

        Instant effectiveAt = Instant.now();
        // body.reason() populates BOTH the audit log's `reason` (below) and the config row's
        // approved_by/attribution column — see PlatformFeeConfigDtos.UpdatePlatformFeeConfigRequest
        // javadoc for why the frontend's single free-text box serves both purposes.
        config.applyAdminFeeUpdate(
                brandFeeBps, creatorFeeBps, body.razorpayAbsorbedByPlatform(), body.reason(), effectiveAt, admin.getId());
        platformFeeConfigRepository.save(config);

        adminAuditLogService.record(
                principal, request, "UPDATE", ENTITY_TYPE, config.getId(), before, snapshot(config), body.reason());

        return toDto(config);
    }

    /**
     * "History of past fee configs" (directive item #5.2) reads the admin_audit_log trail for
     * this entity and reshapes each entry into {@code PlatformFeeHistoryEntryDto} (matching {@code
     * PlatformFeeHistoryEntry} in admin.types.ts, with explicit previous/current values) rather
     * than a separate versioned table — see class javadoc. Gated inside {@link
     * AdminAuditLogService#getByEntity} itself (SUPER_ADMIN only), so no redundant gate call here.
     */
    @Transactional(readOnly = true)
    public List<PlatformFeeHistoryEntryDto> history(AuthPrincipal principal) {
        List<AuditLogEntryDto> entries =
                adminAuditLogService.getByEntity(principal, ENTITY_TYPE, PlatformFeeConfig.SINGLETON_ID);
        return entries.stream().map(PlatformFeeAdminService::toHistoryEntryDto).toList();
    }

    private PlatformFeeConfig requireConfig() {
        return platformFeeConfigRepository
                .findById(PlatformFeeConfig.SINGLETON_ID)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "PLATFORM_FEE_CONFIG_MISSING",
                                        "Platform fee configuration is not initialized",
                                        HttpStatus.INTERNAL_SERVER_ERROR));
    }

    /**
     * Reuses the entity's existing {@code minFeeBps}/{@code maxFeeBps}/{@code allowHighFee}
     * range-guard columns for BOTH the brand and creator legs — those columns were originally
     * scoped to the creator fee only (wiki/tech/creator/10_CREATOR_PAYMENTS_SPEC.md §1A.2's
     * "0 ≤ fee ≤ 3000 bps... above 30% requires allow_high_fee"), but no separate brand-fee bounds
     * exist and the same platform-economics judgement call applies to either leg — a >30% take
     * rate on either side is exactly as suspicious. Deliberately does not add new columns for a
     * second set of bounds.
     */
    private void validateRange(PlatformFeeConfig config, int feeBps, String fieldName) {
        if (feeBps < config.getMinFeeBps()) {
            throw new ApiException(
                    "INVALID_FEE_RANGE",
                    fieldName + " is below the configured minimum (" + config.getMinFeeBps() + " bps)",
                    HttpStatus.BAD_REQUEST);
        }
        int cap = config.isAllowHighFee() ? 10_000 : config.getMaxFeeBps();
        if (feeBps > cap) {
            throw new ApiException(
                    "INVALID_FEE_RANGE",
                    fieldName + " exceeds the configured maximum (" + cap + " bps) without allow_high_fee",
                    HttpStatus.BAD_REQUEST);
        }
    }

    private static int toBps(BigDecimal percent, String fieldName) {
        try {
            return percent.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).intValueExact();
        } catch (ArithmeticException e) {
            throw new ApiException(
                    "INVALID_FEE_VALUE", fieldName + " is not a valid percentage", HttpStatus.BAD_REQUEST);
        }
    }

    private static BigDecimal toPercent(int bps) {
        return BigDecimal.valueOf(bps, 2);
    }

    /**
     * {@code updatedBy} resolves to the acting admin's email (matches admin.types.ts's doc
     * comment "Admin id/email who last set this configuration" and {@code useFeeConfig.ts}'s mock
     * data, e.g. {@code 'swapnil@influora.ai'}) — falls back to {@link
     * PlatformFeeConfig#getApprovedBy()} for the seeded row, which has no acting-admin id (V42
     * migration: the CEO's approval happened via written directive, not a panel action).
     */
    private PlatformFeeConfigDto toDto(PlatformFeeConfig config) {
        String updatedBy =
                config.getUpdatedBy() != null
                        ? adminUserRepository.findById(config.getUpdatedBy()).map(AdminUser::getEmail).orElse(config.getApprovedBy())
                        : config.getApprovedBy();
        return new PlatformFeeConfigDto(
                config.getId(),
                toPercent(config.getBrandFeeBps()),
                toPercent(config.getDefaultFeeBps()),
                config.getEffectiveAt(),
                config.isRazorpayAbsorbedByPlatform(),
                updatedBy,
                config.getApprovedBy());
    }

    /** Field-allowlisted snapshot for {@code AdminAuditLogService#record} — no secrets on this
     * entity, but kept explicit (rather than a raw entity dump) per AUDIT-LOG-WRITE-SPEC.md Rule 2
     * discipline regardless. Keys here MUST match {@code AdminAuditLogService.FIELD_ALLOWLIST}'s
     * {@code PLATFORM_FEE_CONFIG} entry and {@link #toHistoryEntryDto}'s parse keys. */
    private Map<String, Object> snapshot(PlatformFeeConfig config) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", config.getId());
        map.put("brandFeePercent", toPercent(config.getBrandFeeBps()));
        map.put("creatorFeePercent", toPercent(config.getDefaultFeeBps()));
        map.put("razorpayAbsorbedByPlatform", config.isRazorpayAbsorbedByPlatform());
        map.put("approvedBy", config.getApprovedBy());
        map.put("effectiveAt", config.getEffectiveAt() != null ? config.getEffectiveAt().toString() : null);
        return map;
    }

    /**
     * Reshapes one {@code admin_audit_log} row into {@code PlatformFeeHistoryEntryDto} — parses
     * the before/after JSON snapshots {@link #snapshot} wrote to pull out
     * brand/creatorFeePercent on each side. Tolerant of a missing/unparseable old-value snapshot
     * (e.g. a hypothetical future entry type reuse) by falling back to the new value rather than
     * throwing, since this is a read path and a best-effort history row beats a 500.
     */
    private static PlatformFeeHistoryEntryDto toHistoryEntryDto(AuditLogEntryDto entry) {
        JsonNode oldValue = parseJson(entry.oldValue());
        JsonNode newValue = parseJson(entry.newValue());
        return new PlatformFeeHistoryEntryDto(
                entry.id(),
                decimalField(newValue, "brandFeePercent"),
                decimalField(newValue, "creatorFeePercent"),
                decimalField(oldValue != null ? oldValue : newValue, "brandFeePercent"),
                decimalField(oldValue != null ? oldValue : newValue, "creatorFeePercent"),
                booleanField(newValue, "razorpayAbsorbedByPlatform"),
                entry.reason(),
                entry.adminEmail(),
                entry.timestamp());
    }

    private static JsonNode parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private static BigDecimal decimalField(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(node.get(field).asText());
    }

    private static boolean booleanField(JsonNode node, String field) {
        return node != null && node.path(field).asBoolean(false);
    }
}
