package com.influora.web;

import com.influora.common.ApiException;
import com.influora.common.ApiResponse;
import com.influora.domain.enums.UserType;
import com.influora.security.AuthPrincipal;
import com.influora.service.BrandContextService;
import com.influora.service.EscrowService;
import com.influora.service.PayoutService;
import com.influora.web.dto.money.MoneyDtos.EscrowFundRequest;
import com.influora.web.dto.money.MoneyDtos.EscrowFundResponse;
import com.influora.web.dto.money.MoneyDtos.EscrowRefundRequest;
import com.influora.web.dto.money.MoneyDtos.EscrowReleaseRequest;
import com.influora.web.dto.money.MoneyDtos.EscrowStatusResponse;
import com.influora.web.dto.money.MoneyDtos.PayoutRequest;
import com.influora.web.dto.money.MoneyDtos.PayoutResponse;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * [SEC: MF-1 / Guardrail 1] {@link EscrowFundRequest} carries NO amount field. The fund amount is
 * always re-derived server-side ({@code EscrowService.deriveFundAmount}) from the campaign's
 * persisted budget or the named milestone's amount — never accepted from the request body.
 */
@RestController
@RequestMapping("/wallet/escrow")
public class EscrowController {

    private final EscrowService escrowService;
    private final PayoutService payoutService;
    private final BrandContextService brandContext;

    public EscrowController(
            EscrowService escrowService, PayoutService payoutService, BrandContextService brandContext) {
        this.escrowService = escrowService;
        this.payoutService = payoutService;
        this.brandContext = brandContext;
    }

    /**
     * Brand-scoped, paginated escrow hold list — GET /wallet/escrow. Backs the brand-wallet page's
     * escrow-items panel, which previously had no live endpoint and rendered mock data (see
     * src/pages/brand-wallet.tsx's "No GET /wallet/escrow endpoint yet" comment). Each item is the
     * exact same shape {@link #status} returns for a single hold — {@link EscrowStatusResponse}.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<EscrowStatusResponse>>> list(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit) {
        var workspace = brandContext.requireBrandWorkspace(principal);
        var result = escrowService.listForWorkspace(principal, workspace.getId(), page, limit);
        return ResponseEntity.ok(ApiResponse.ok(result.items(), result.meta()));
    }

    @PostMapping("/fund")
    public ApiResponse<EscrowFundResponse> fund(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody EscrowFundRequest body) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(
                    "IDEMPOTENCY_KEY_REQUIRED",
                    "Idempotency-Key header is required",
                    HttpStatus.BAD_REQUEST);
        }
        var workspace = brandContext.requireBrandWorkspace(principal);

        // The ONLY place a fund amount is computed — from persisted state, never from `body`.
        BigDecimal amount =
                escrowService.deriveFundAmount(workspace.getId(), body.campaignId(), body.milestoneId());

        EscrowFundResponse response =
                escrowService.initiateFund(
                        principal, workspace.getId(), body.campaignId(), body.milestoneId(), amount, "INR", idempotencyKey);
        return ApiResponse.ok(response);
    }

    @GetMapping("/{escrowHoldId}")
    public ApiResponse<EscrowStatusResponse> status(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String escrowHoldId) {
        if (principal.getUserType() == UserType.CREATOR) {
            return ApiResponse.ok(escrowService.getStatusForCreator(principal, escrowHoldId));
        }
        var workspace = brandContext.requireBrandWorkspace(principal);
        return ApiResponse.ok(escrowService.getStatus(principal, workspace.getId(), escrowHoldId));
    }

    @PostMapping("/release")
    public ApiResponse<EscrowStatusResponse> release(
            @AuthenticationPrincipal AuthPrincipal principal, @Valid @RequestBody EscrowReleaseRequest body) {
        var workspace = brandContext.requireBrandWorkspace(principal);
        // Payee is resolved inside EscrowService from the milestone's collaboration — not
        // accepted from the request, so an attacker cannot redirect a release to another user.
        return ApiResponse.ok(escrowService.release(principal, workspace.getId(), body.milestoneId()));
    }

    @PostMapping("/refund")
    public ApiResponse<EscrowStatusResponse> refund(
            @AuthenticationPrincipal AuthPrincipal principal, @Valid @RequestBody EscrowRefundRequest body) {
        var workspace = brandContext.requireBrandWorkspace(principal);
        return ApiResponse.ok(escrowService.refund(principal, workspace.getId(), body.escrowHoldId()));
    }

    @PostMapping("/payout")
    public ApiResponse<PayoutResponse> queuePayout(
            @AuthenticationPrincipal AuthPrincipal principal, @Valid @RequestBody PayoutRequest body) {
        var workspace = brandContext.requireBrandWorkspace(principal);
        return ApiResponse.ok(payoutService.queuePayout(principal, workspace.getId(), body.milestoneId()));
    }
}
