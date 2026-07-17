package com.influora.web.dto.admin;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Request/response records for {@code PlatformFeeAdminController} (wiki/decisions/
 * admin-pending-tasks-directive.md item #5). Field names/shapes match {@code PlatformFeeConfig}/
 * {@code PlatformFeeUpdateRequest}/{@code PlatformFeeHistoryEntry} in {@code
 * src/admin/types/admin.types.ts} (Priya/Ananya) exactly — {@code src/admin/hooks/useFeeConfig.ts}
 * and {@code src/admin/components/finance/FeeControlPanel.tsx} (Ananya, already shipped to Kavya
 * for QA) were built against those types with mock data ahead of this controller; this is the
 * real-integration follow-up their TODOs name. {@code PlatformFeeConfigDto} carries one extra
 * field beyond the frontend interface ({@code approvedBy}) — harmless, the frontend just ignores
 * JSON keys it doesn't declare — kept because the admin-pending-tasks-directive explicitly asks
 * for a "who-approved" field distinct from "who last edited via the panel" ({@code updatedBy}).
 *
 * <p>Percent fields are the admin-facing unit (e.g. {@code 10.00} = 10%); {@code
 * PlatformFeeAdminService} converts to/from the entity's basis-point columns ({@code
 * brandFeeBps}/{@code defaultFeeBps}) at the boundary so this DTO layer never leaks the storage
 * representation.
 */
public final class PlatformFeeConfigDtos {

    private PlatformFeeConfigDtos() {}

    public record PlatformFeeConfigDto(
            String id,
            BigDecimal brandFeePercent,
            BigDecimal creatorFeePercent,
            Instant effectiveDate,
            boolean razorpayAbsorbedByPlatform,
            String updatedBy,
            String approvedBy) {}

    /**
     * {@code reason} matches {@code PlatformFeeUpdateRequest.reason} in admin.types.ts exactly
     * (NOT a separate "who approved" field — {@code FeeControlPanel.tsx}'s form has a single
     * free-text box, zod-enforced client-side at a 10-char minimum per the shipped component;
     * {@code @Size(min = 10, ...)} below mirrors that server-side, since client-side validation
     * alone is never trusted, per TECH-STACK.md's security-first discipline). This text is both
     * the audit-log entry's {@code reason} AND becomes the new {@code approved_by}/attribution
     * value stored on the config row — see {@code PlatformFeeAdminService} for why one string
     * serves both purposes.
     */
    public record UpdatePlatformFeeConfigRequest(
            @NotNull @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal brandFeePercent,
            @NotNull @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal creatorFeePercent,
            @NotNull Boolean razorpayAbsorbedByPlatform,
            @NotBlank @Size(min = 10, max = 2000) String reason) {}

    public record PlatformFeeHistoryEntryDto(
            String id,
            BigDecimal brandFeePercent,
            BigDecimal creatorFeePercent,
            BigDecimal previousBrandFeePercent,
            BigDecimal previousCreatorFeePercent,
            boolean razorpayAbsorbedByPlatform,
            String reason,
            String changedBy,
            Instant changedAt) {}
}
