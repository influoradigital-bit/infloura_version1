package com.influora.web;

import com.influora.common.ApiException;
import com.influora.common.ApiResponse;
import com.influora.domain.enums.UserType;
import com.influora.security.AuthPrincipal;
import com.influora.service.BrandContextService;
import com.influora.service.CreatorContextService;
import com.influora.service.WalletService;
import com.influora.service.WalletTopUpService;
import com.influora.web.dto.money.MoneyDtos.CreatorWithdrawRequest;
import com.influora.web.dto.money.MoneyDtos.CreatorWithdrawResponse;
import com.influora.web.dto.money.MoneyDtos.WalletBalanceResponse;
import com.influora.web.dto.money.MoneyDtos.WalletSummaryResponse;
import com.influora.web.dto.money.MoneyDtos.WalletTopUpRequest;
import com.influora.web.dto.money.MoneyDtos.WalletTopUpResponse;
import com.influora.web.dto.money.MoneyDtos.WalletTransactionRowResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/wallet")
public class WalletController {

    private final WalletService walletService;
    private final WalletTopUpService walletTopUpService;
    private final BrandContextService brandContext;
    private final CreatorContextService creatorContext;

    public WalletController(
            WalletService walletService,
            WalletTopUpService walletTopUpService,
            BrandContextService brandContext,
            CreatorContextService creatorContext) {
        this.walletService = walletService;
        this.walletTopUpService = walletTopUpService;
        this.brandContext = brandContext;
        this.creatorContext = creatorContext;
    }

    @GetMapping("/balance")
    public ApiResponse<WalletBalanceResponse> getBalance(@AuthenticationPrincipal AuthPrincipal principal) {
        if (principal.getUserType() == UserType.CREATOR) {
            creatorContext.requireCreator(principal);
            return ApiResponse.ok(walletService.getBalanceForUser(principal.getUserId()));
        }
        var workspace = brandContext.requireBrandWorkspace(principal);
        brandContext.requireMember(principal, workspace.getId());
        return ApiResponse.ok(walletService.getBalance(workspace.getId()));
    }

    /** Brand dashboard wallet summary (BACKEND-API-SPEC §33.5.5) — see {@code WalletSummaryResponse}. */
    @GetMapping
    public ApiResponse<WalletSummaryResponse> getSummary(@AuthenticationPrincipal AuthPrincipal principal) {
        if (principal.getUserType() == UserType.CREATOR) {
            creatorContext.requireCreator(principal);
            return ApiResponse.ok(walletService.getSummaryForUser(principal.getUserId()));
        }
        var workspace = brandContext.requireBrandWorkspace(principal);
        brandContext.requireMember(principal, workspace.getId());
        return ApiResponse.ok(walletService.getSummary(workspace.getId()));
    }

    /**
     * Brand wallet top-up — POST /wallet/topup (B0, wiki/tech/BRAND_EXECUTION_PLAN.md LOCKED
     * ruling item 3). OWNER/ADMIN only (enforced inside {@code WalletTopUpService} via {@code
     * BrandContextService.requireRole} — same brand-role gate as {@code EscrowController#fund}).
     * Idempotency-Key is mandatory, mirroring {@code EscrowController#fund}: a top-up is a
     * money-moving mutation reachable by client retry, not a read.
     *
     * <p>Top-up only — no fee is deducted here. The 10% publish-time fee cut is B1, a separate
     * gated wave (Kabir OWASP PASS on this endpoint required first, per the LOCKED ruling's COO
     * gate).
     */
    @PostMapping("/topup")
    public ApiResponse<WalletTopUpResponse> topUp(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody WalletTopUpRequest body) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(
                    "IDEMPOTENCY_KEY_REQUIRED",
                    "Idempotency-Key header is required",
                    HttpStatus.BAD_REQUEST);
        }
        WalletTopUpResponse response =
                walletTopUpService.initiateTopUp(
                        principal, body.amount(), body.pan(), body.gstin(), idempotencyKey);
        return ApiResponse.ok(response);
    }

    /**
     * Creator withdrawal — POST /wallet/withdraw. Brand accounts are rejected; owner id is always
     * {@code principal.getUserId()} (Kabir Task #10 GO pattern).
     */
    @PostMapping("/withdraw")
    public ResponseEntity<ApiResponse<CreatorWithdrawResponse>> withdraw(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody CreatorWithdrawRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        creatorContext.requireCreator(principal);
        CreatorWithdrawResponse response =
                walletService.requestCreatorWithdrawal(
                        principal.getUserId(), body.amount(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    /**
     * Creator-scoped ledger history — GET /wallet/transactions. Paginated; wallet is resolved from
     * {@code principal.getUserId()} only.
     */
    @GetMapping("/transactions")
    public ResponseEntity<ApiResponse<List<WalletTransactionRowResponse>>> transactions(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit) {
        creatorContext.requireCreator(principal);
        var result = walletService.getTransactionsForUser(principal.getUserId(), page, limit);
        return ResponseEntity.ok(ApiResponse.ok(result.items(), result.meta()));
    }
}
