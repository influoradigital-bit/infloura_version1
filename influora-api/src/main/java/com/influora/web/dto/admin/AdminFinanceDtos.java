package com.influora.web.dto.admin;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * DTOs for the admin Finance/Escrow console (Phase 2 per the CEO directive — see
 * {@code PlatformFeeAdminController} class javadoc for why the whole {@code financeApi} surface is
 * mounted under {@code /admin/finance/*}). Mirrors the wire shapes declared in
 * {@code src/admin/types/admin.types.ts}. Raw DTO returns, no {@code ApiResponse} envelope — same
 * deliberate deviation as every other {@code Admin*Controller}.
 */
public final class AdminFinanceDtos {

    private AdminFinanceDtos() {}

    /**
     * {@code GET /admin/finance/escrow} — platform-wide escrow visibility. Mirrors {@code
     * EscrowSummary} in {@code admin.types.ts}. Every figure is derived LIVE from {@code
     * escrow_holds} (never a stored/cached column); there is no fabricated data.
     *
     * <ul>
     *   <li>{@code totalLocked} — SUM(amount) of holds in {@code FUNDED} (money currently locked),
     *       via {@link com.influora.repository.EscrowHoldRepository#sumAmountByStatusIn}.
     *   <li>{@code pendingRelease} — COUNT of {@code FUNDED} holds. <b>Declared assumption:</b> the
     *       {@code EscrowSummary} type names this field without a unit and {@code EscrowHold} has no
     *       distinct "release-eligible" flag (a FUNDED hold IS awaiting release), so this is
     *       reported as the NUMBER of holds pending release rather than duplicating {@code
     *       totalLocked}'s amount. Revisit if the console expects an amount.
     *   <li>{@code flaggedTransactions} — COUNT of {@code FROZEN} holds. {@code EscrowStatus} has no
     *       {@code FLAGGED} value; {@code FROZEN} is the held-for-review state, so it is the honest
     *       mapping for "flagged".
     *   <li>{@code averageReleaseTime} — AVG(released_at − funded_at) over {@code RELEASED} holds,
     *       in HOURS (repository returns seconds; service divides by 3600). 0 when no releases yet.
     * </ul>
     */
    public record EscrowSummaryDto(
            double totalLocked,
            long pendingRelease,
            long flaggedTransactions,
            double averageReleaseTime) {}

    /**
     * {@code GET /admin/escrow/flagged} — one row per {@code FROZEN} escrow hold (the held-for-review
     * state; {@code EscrowStatus} has no {@code FLAGGED} value). Mirrors the inline array shape in
     * {@code escrowApi.getFlagged()} ({@code src/admin/services/api-contracts.ts}). All fields are
     * live:
     *
     * <ul>
     *   <li>{@code id}/{@code campaignId}/{@code amount}/{@code createdAt} — straight off {@code EscrowHold}.
     *   <li>{@code campaignName} — {@code Campaign.title} joined by {@code campaignId} (batch-loaded, no
     *       N+1); {@code "(unknown campaign)"} if the campaign row is gone.
     *   <li>{@code flagReason} — the {@code reason} of the most-recent {@code Dispute} on the hold's
     *       collaboration (a hold is FROZEN precisely because {@code freezeUnreleasedForDispute} ran).
     *       Falls back to {@code "Frozen — no linked dispute"} when a FROZEN hold has no
     *       collaboration/dispute (e.g. a campaign-scoped freeze), never a fabricated reason.
     * </ul>
     */
    public record FlaggedEscrowDto(
            String id,
            String campaignId,
            String campaignName,
            double amount,
            String flagReason,
            String createdAt) {}

    /**
     * {@code GET /admin/finance/reconciliation?date=YYYY-MM-DD} — one row per {@link
     * com.influora.domain.entity.Payout}/{@link com.influora.domain.entity.WalletTopUp} whose
     * {@code createdAt} falls on {@code date}, internal ledger figures compared against RazorpayX's
     * own record for the same gateway id. Mirrors {@code ReconciliationItem} in {@code
     * admin.types.ts}. Read-only — no write/resolve action; see {@code AdminFinanceService
     * #getReconciliation} javadoc for exactly how each field is derived and why a WRITE action
     * (match/write-off) is explicitly out of scope (no persistence column exists for it).
     *
     * <ul>
     *   <li>{@code id}/{@code internalId} — the {@code Payout}/{@code WalletTopUp} row's own id.
     *       There is no separate stored reconciliation-row entity, so both are the same value.
     *   <li>{@code razorpayId} — the gateway-side id ({@code Payout.razorpayPayoutId} /
     *       {@code WalletTopUp.razorpayOrderId}); empty string if the gateway was never called yet
     *       (queue-time {@code "pending:"} placeholder / no order id on the top-up).
     *   <li>{@code razorpayAmount}/{@code internalAmount} — rupees, straight comparison; {@code
     *       variance = razorpayAmount - internalAmount}.
     *   <li>{@code status} — {@code MATCHED} (amounts agree and both sides report a consistent
     *       terminal outcome), {@code MISMATCH} (amounts differ, or the two sides disagree on
     *       outcome), or {@code PENDING} (gateway record not yet resolvable — no gateway id yet, or
     *       the gateway's own status is still in-flight, or the live gateway lookup itself failed).
     * </ul>
     */
    public record ReconciliationItemDto(
            String id,
            String razorpayId,
            String internalId,
            double razorpayAmount,
            double internalAmount,
            double variance,
            String status,
            String createdAt) {}

    /**
     * {@code POST /admin/finance/payouts/{id}/retry} — result of re-initiating a definitively-failed
     * creator payout. Mirrors {@link com.influora.domain.entity.Payout}'s own state after {@code
     * PayoutReconciliationService#retryFailedPayout} runs (either a fresh gateway confirmation on
     * success, or {@code reversed} again if the retry itself also failed — never a fabricated
     * "success").
     */
    public record PayoutRetryResultDto(
            String payoutId, String status, String razorpayPayoutId, String updatedAt) {}

    /**
     * Request body for {@code POST /admin/finance/payouts/manual}.
     *
     * <p>No {@code currency} field: it is read from the creator's wallet, never accepted from the
     * client, so a recorded payout cannot claim a different currency to the balance it debits.
     *
     * <p>{@code tdsAmount} is nullable on purpose — {@code null} means no TDS was applied, {@code
     * 0.00} means it was considered and none was due. Collapsing the two would make a later filing
     * unable to tell "not handled" from "handled, nothing owed".
     */
    public record ManualPayoutRequest(
            @NotBlank String creatorUserId,
            @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
            @NotBlank String bankReference,
            BigDecimal tdsAmount,
            String note) {}

    /** Result of recording an out-of-band bank transfer. */
    public record ManualPayoutResultDto(
            String payoutId,
            String creatorUserId,
            BigDecimal amount,
            String currency,
            String status,
            String bankReference,
            BigDecimal tdsAmount,
            String confirmedAt) {}
}
