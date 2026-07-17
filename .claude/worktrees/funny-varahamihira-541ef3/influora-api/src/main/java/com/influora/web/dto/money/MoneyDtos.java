package com.influora.web.dto.money;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.influora.domain.enums.ContractStatus;
import com.influora.domain.enums.EscrowStatus;
import com.influora.domain.enums.MilestoneStatus;
import jakarta.validation.constraints.NotBlank;
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

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WalletBalanceResponse(
            String walletId, BigDecimal balance, BigDecimal escrowBalance, String currency) {}

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

    public record MilestoneWriteRequest(
            int sequenceNo, String description, BigDecimal amount, LocalDate dueDate) {}

    public record ContractGenerateRequest(
            @NotBlank String collaborationId, List<MilestoneWriteRequest> milestones) {}

    public record ContractSignRequest(@NotBlank String role) {}

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
