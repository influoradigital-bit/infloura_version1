package com.influora.web;

import com.influora.common.ApiException;
import com.influora.security.AuthPrincipal;
import com.influora.service.admin.PlatformFeeAdminService;
import com.influora.web.dto.admin.PlatformFeeConfigDtos.PlatformFeeConfigDto;
import com.influora.web.dto.admin.PlatformFeeConfigDtos.PlatformFeeHistoryEntryDto;
import com.influora.web.dto.admin.PlatformFeeConfigDtos.UpdatePlatformFeeConfigRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin surface for {@code platform_fee_config} (wiki/decisions/admin-pending-tasks-directive.md
 * item #5 — CEO-approved: 10% brand fee, 15% creator fee, Option A / platform absorbs Razorpay
 * processing costs). Mounted at {@code /admin/finance/fee-config} (full path {@code
 * /api/v1/admin/finance/fee-config} given {@code server.servlet.context-path=/api/v1} and {@code
 * API_BASE = '/api/v1/admin'} in {@code src/admin/services/api-contracts.ts}) — deliberately
 * under the existing {@code /finance/*} namespace ({@code financeApi.getRevenue}/{@code
 * getEscrowSummary}/{@code getPayoutQueue}/{@code getReconciliation}, all {@code
 * /finance/...}-mounted, even though none of those have a backend controller yet — Phase 2 per
 * the CEO directive), not a standalone {@code /admin/platform-fee-config} path, so that Priya's
 * planned {@code financeApi.getFeeConfig}/{@code .updateFeeConfig}/{@code .getFeeConfigHistory}
 * additions (named in {@code src/admin/hooks/useFeeConfig.ts}'s TODO — that hook already returns
 * mock {@code PlatformFeeConfig}/{@code PlatformFeeHistoryEntry} data shaped for exactly this
 * response) can point straight at this controller without a path renegotiation.
 *
 * <p><b>Scope, explicit per the directive:</b> this is the admin CRUD/visibility surface ONLY —
 * it does not wire brand escrow-funding or creator escrow-release to actually charge these fees.
 * See {@code PlatformFeeAdminService} class javadoc for how this relates to the already-existing
 * {@code PlatformFeeService} (creator-side escrow-release deduction, separate scope, untouched by
 * this controller).
 *
 * <p><b>Role-gating:</b> SUPER_ADMIN only, not SUPER_ADMIN+ADMIN like most of the admin surface —
 * this table controls platform revenue take-rate, a strictly higher blast radius than brand/
 * creator moderation actions. Enforced in {@code PlatformFeeAdminService} via {@code
 * AdminContextService#requireRoleWithMfaSatisfied}, same manual per-call pattern as every other
 * {@code Admin*Controller} (no {@code @PreAuthorize} usage anywhere in this codebase). Matches
 * {@code FeeControlPanel.tsx}'s client-side role gate (real enforcement lives here, not there).
 *
 * <p>Returns raw DTOs (unwrapped, no {@link com.influora.common.ApiResponse} envelope) — same
 * deliberate deviation as every other {@code Admin*Controller}.
 */
@RestController
@RequestMapping("/admin/finance/fee-config")
public class PlatformFeeAdminController {

    private final PlatformFeeAdminService platformFeeAdminService;

    public PlatformFeeAdminController(PlatformFeeAdminService platformFeeAdminService) {
        this.platformFeeAdminService = platformFeeAdminService;
    }

    @GetMapping
    public PlatformFeeConfigDto getCurrent(@AuthenticationPrincipal AuthPrincipal principal) {
        return platformFeeAdminService.getCurrent(principal);
    }

    /**
     * Optimistic-locking guard (Kabir security review, {@code PlatformFeeConfig#version}, V44
     * migration): this singleton row has no repository-level pessimistic locking, so a concurrent
     * SUPER_ADMIN PUT (or a client-side retry racing the original request) can race this one. Both
     * requests load the same {@code version}; whichever commits second fails Hibernate's {@code
     * WHERE version = ?} check and throws {@link ObjectOptimisticLockingFailureException} instead
     * of silently overwriting the first admin's change. Translated here to 409 so the caller knows
     * to refetch and retry rather than treating it as a generic 500.
     */
    @PutMapping
    public PlatformFeeConfigDto update(
            @AuthenticationPrincipal AuthPrincipal principal,
            HttpServletRequest request,
            @Valid @RequestBody UpdatePlatformFeeConfigRequest body) {
        try {
            return platformFeeAdminService.update(principal, request, body);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new ApiException(
                    "FEE_CONFIG_CONFLICT",
                    "Fee config was modified by another admin, refresh and retry",
                    HttpStatus.CONFLICT);
        }
    }

    @GetMapping("/history")
    public List<PlatformFeeHistoryEntryDto> history(@AuthenticationPrincipal AuthPrincipal principal) {
        return platformFeeAdminService.history(principal);
    }
}
