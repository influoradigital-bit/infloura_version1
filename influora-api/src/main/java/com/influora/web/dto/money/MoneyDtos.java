package com.influora.web.dto.money;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.influora.domain.enums.ContractStatus;
import com.influora.domain.enums.EscrowStatus;
import com.influora.domain.enums.MilestoneStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Money-domain request/response records (escrow, contracts, milestones, wallet, payouts,
 * webhooks). Grouped in one file, matching the {@code CampaignDtos} convention.
 *
 * <p><b>[SEC: MF-1 / Guardrail 1]</b> {@link EscrowFundRequest} intentionally carries NO
 * {@code amount} field. The fund amount is always re-derived server-side from the campaign's
 * persisted budget / milestone row — never accepted from the caller.
 */
public final class MoneyDtos {

    private MoneyDtos() {}

    // ---------------------------------------------------------------------
    // Wallet
    // ---------------------------------------------------------------------

    /**
     * {@code runwayDays} is {@code null} whenever it cannot be honestly computed (no trailing
     * spend, e.g. a brand new or dormant wallet) — the frontend must render an explicit
     * "healthy" / "—" state and must NEVER substitute a fabricated placeholder number. See
     * {@code WalletService#computeRunwayDays} for the calc.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WalletBalanceResponse(
            String walletId,
            BigDecimal balance,
            BigDecimal escrowBalance,
            String currency,
            Integer runwayDays) {}

    /**
     * Brand dashboard wallet summary (canonical {@code GET /wallet} per BACKEND-API-SPEC §33.5.5).
     * Field names are the brand-client aliases: {@code availableBalance} ≡ {@code balance},
     * {@code escrowLocked} ≡ {@code escrowBalance}. {@code pendingPayouts} is the sum of FUNDED (held,
     * not-yet-released) milestone amounts across the workspace's collaborations. {@code runwayDays}
     * follows the same honest-null contract as {@link WalletBalanceResponse}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WalletSummaryResponse(
            BigDecimal availableBalance,
            BigDecimal escrowLocked,
            BigDecimal pendingPayouts,
            Integer runwayDays) {}

    /** Creator withdrawal request — amount is validated server-side against ledger balance. */
    public record CreatorWithdrawRequest(@NotNull @DecimalMin("1.00") BigDecimal amount) {}

    /**
     * Brand wallet top-up request (B0). {@code amount} IS caller-supplied here — unlike escrow's
     * fund amount (always re-derived from a campaign/milestone), a top-up is the brand depositing
     * their own chosen amount into their own wallet, not a movement of already-persisted platform
     * money. It is validated positive here and re-cross-checked against Razorpay's actual captured
     * amount at webhook time ({@code WalletTopUpService#confirmCredited}) before any credit is
     * applied — same discipline as {@code EscrowService#validateWebhookAmount}.
     *
     * <p>{@code pan}/{@code gstin} are optional, CFO-required for later TDS reconciliation (LOCKED
     * ruling item 6) — persisted onto {@code Workspace.pan}/{@code Workspace.gstin} (the existing
     * KYC field, fill-if-blank only; see {@code Workspace#applyTopUpTaxIds}). Patterns mirror
     * {@code OnboardingDtos.KycRequest} exactly.
     */
    public record WalletTopUpRequest(
            // [SEC: Kabir Option-1 audit P1 must-fix] Defense-in-depth only — this hardcoded
            // 1000000.00 MUST match influora.wallet.max-topup-amount (WalletProperties). Bean
            // validation runs before Spring config is available to a record component, so it
            // can't read the config value; the AUTHORITATIVE ceiling is the config-driven check
            // in WalletTopUpService#initiateTopUp, right after the positive-amount check.
            @NotNull @DecimalMin("1.00") @DecimalMax("1000000.00") @Digits(integer = 12, fraction = 2)
                    BigDecimal amount,
            @Pattern(regexp = "^[A-Z]{5}[0-9]{4}[A-Z]{1}$", message = "Invalid PAN format") String pan,
            @Pattern(
                            regexp = "^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1}$",
                            message = "Invalid GSTIN format")
                    String gstin) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WalletTopUpResponse(
            String topUpId, BigDecimal amount, String currency, String razorpayOrderId, String status) {}

    /** Response for POST /wallet/withdraw (creator). {@code payoutId} is the ledger reference id. */
    public record CreatorWithdrawResponse(String payoutId) {}

    /**
     * Ledger row for GET /wallet/transactions. {@code type} is the {@code WalletTransactionType}
     * enum name; {@code amount} is always positive — use {@code direction} sign in UI if needed.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WalletTransactionRowResponse(
            String id,
            String type,
            String direction,
            BigDecimal amount,
            String currency,
            String description,
            String status,
            Instant createdAt,
            BigDecimal balanceAfter) {}

    // ---------------------------------------------------------------------
    // Escrow
    // ---------------------------------------------------------------------

    /**
     * [SEC: MF-1] No {@code amount} field by design — the server derives the fund amount from
     * the campaign's persisted budget (or the named milestone's amount). A caller-supplied
     * amount would be a Guardrail 1 violation and is not accepted anywhere in this DTO.
     */
    public record EscrowFundRequest(@NotBlank String campaignId, String milestoneId) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EscrowFundResponse(
            String escrowHoldId,
            BigDecimal amount,
            String currency,
            String razorpayOrderId,
            EscrowStatus status) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EscrowStatusResponse(
            String escrowHoldId,
            String workspaceId,
            String campaignId,
            String milestoneId,
            BigDecimal amount,
            String currency,
            EscrowStatus status,
            Instant fundedAt,
            Instant releasedAt) {}

    public record EscrowReleaseRequest(@NotBlank String milestoneId) {}

    public record EscrowRefundRequest(@NotBlank String escrowHoldId, String reason) {}

    // ---------------------------------------------------------------------
    // Contracts + milestones
    // ---------------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ContractResponse(
            String id,
            String collaborationId,
            String workspaceId,
            int version,
            ContractStatus status,
            BigDecimal totalAmount,
            String currency,
            String pdfR2Key,
            Instant brandSignedAt,
            Instant creatorSignedAt,
            LocalDate effectiveDate,
            LocalDate expirationDate,
            List<MilestoneDto> milestones,
            Instant createdAt,
            Instant updatedAt) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MilestoneDto(
            String id,
            String contractId,
            String collaborationId,
            int sequenceNo,
            String description,
            BigDecimal amount,
            String currency,
            LocalDate dueDate,
            MilestoneStatus status,
            String escrowHoldId) {}

    /**
     * [SEC: Kabir MEDIUM-2, contract-flow-architecture-2026-07-23 review] {@code amount} was
     * previously unconstrained -- {@code generate} only checked the SUMMED total was positive, so
     * a negative milestone (e.g. {@code [500, -200]}) could slip through as long as the net was
     * positive. {@code @DecimalMin("0.01")} rejects zero/negative amounts at the request-validation
     * boundary (`@Valid` cascades via {@link ContractGenerateRequest#milestones}); {@code
     * ContractService#generate} additionally re-checks per-milestone defensively (same
     * belt-and-suspenders discipline as {@code EscrowService#adminSplitForDispute}'s own
     * boundary re-check) in case a caller ever bypasses bean validation.
     */
    public record MilestoneWriteRequest(
            int sequenceNo,
            String description,
            @NotNull @DecimalMin("0.01") BigDecimal amount,
            LocalDate dueDate) {}

    public record ContractGenerateRequest(
            @NotBlank String collaborationId, @Valid List<MilestoneWriteRequest> milestones) {}

    /**
     * Presigned, time-limited GET link for downloading the generated contract PDF. Requested
     * on-demand (not baked into {@link ContractResponse}) so the expiry is always fresh at click
     * time rather than a URL that may have already expired by the time the frontend renders it.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ContractPdfDownloadResponse(String downloadUrl, Instant expiresAt) {}

    /**
     * `role` is now OPTIONAL (brand-feature-audit.md #2 fix). The FE's real call path
     * (`api.ts:1466` -> `signContract` -> {@code POST /contracts/:id/sign}) sends only
     * {@code {name, agreedAt}} -- no `role` -- for a brand principal self-signing their own
     * contract. Requiring `role` here 400'd every brand signature. {@link
     * com.influora.web.ContractController#sign} now server-derives the default from the
     * authenticated principal's own userType and only falls back to this field for the
     * (currently FE-unused) elevated-member relay path documented on {@link
     * com.influora.service.ContractService#recordSignature}.
     */
    public record ContractSignRequest(String role) {}

    // ---------------------------------------------------------------------
    // Payouts (Razorpay)
    // ---------------------------------------------------------------------

    public record PayoutRequest(@NotBlank String milestoneId) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PayoutResponse(
            String payoutId, String milestoneId, BigDecimal amount, String currency, String status) {}

    // ---------------------------------------------------------------------
    // Webhooks (Razorpay)
    // ---------------------------------------------------------------------

    public record WebhookPayload(String event, String payloadJson, String signatureHeader) {}
}
