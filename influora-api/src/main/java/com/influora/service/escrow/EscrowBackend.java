package com.influora.service.escrow;

import com.influora.domain.enums.TxnReferenceType;
import java.math.BigDecimal;

/**
 * Escrow money-movement backend abstraction — fund / release / refund. {@link LedgerEscrowBackend}
 * is the only implementation today: today's real behavior (brand wallet -> platform clearing
 * wallet -> creator wallet via {@code WalletLedgerService.post} and {@code PlatformFeeService})
 * moved here verbatim out of {@code EscrowService}.
 *
 * <p>[Audit, 2026-07-27] The CEO's payments decision doc
 * (wiki/decisions/influora-launch-payments-2026-07-25.md) assumed a future Razorpay Route swap-in
 * behind an ESCROW_ENABLED flag would be "zero rework." That is false: Route needs a {@code
 * transfer} pinned to one specific captured payment plus a Route "linked account" (separate KYC) —
 * architecturally incompatible with today's pooled-wallet-balance model. This interface exists so a
 * future {@code RouteEscrowBackend} is a real, swappable implementation of fund/release/refund, not
 * a boolean branch inside {@code EscrowService}. Building that Route implementation is explicitly
 * out of scope here — only this interface + the ledger implementation exist today.
 *
 * <p>Every amount / idempotency key / description is supplied explicitly by the caller
 * ({@code EscrowService}) — never derived or templated inside a backend implementation. Proven
 * necessary by {@code adminSplitForDispute}: ONE hold can need both a partial release AND a partial
 * refund posted in the same call, each with its own amount and its own idempotency-scoped ledger
 * entry, so no backend method can assume "the whole hold amount" or "one canonical description."
 */
public interface EscrowBackend {

    FundOutcome fund(FundCommand command);

    ReleaseOutcome release(ReleaseCommand command);

    RefundOutcome refund(RefundCommand command);

    /**
     * @param workspaceId brand workspace whose wallet funds this hold
     * @param escrowHoldId server-generated {@code EscrowHold} id — never the client-supplied
     *     {@code Idempotency-Key} header (that header is shared, unscoped input)
     * @param gatewayRef verified gateway payment id, or {@code null} when funded directly from an
     *     already-topped-up wallet balance (no gateway round trip)
     */
    record FundCommand(
            String workspaceId,
            String escrowHoldId,
            BigDecimal amount,
            String currency,
            String description,
            String idempotencyKey,
            String gatewayRef) {}

    /** @param fundTxnId the funding leg's transaction id, passed to {@code EscrowHold#markFunded} */
    record FundOutcome(String fundTxnId) {}

    /**
     * @param feeReferenceId reference id passed through for platform-fee traceability/invoicing
     *     (today: the milestone id when a milestone exists, else the escrow hold id) — deliberately
     *     distinct from {@code ledgerReferenceType}/{@code ledgerReferenceId}: for admin dispute
     *     releases the fee reference still prefers the milestone id when present, but the ledger
     *     leg itself always references the escrow hold
     */
    record ReleaseCommand(
            String escrowHoldId,
            String payeeUserId,
            BigDecimal grossAmount,
            String currency,
            String feeReferenceId,
            TxnReferenceType ledgerReferenceType,
            String ledgerReferenceId,
            String description,
            String idempotencyKey) {}

    /** @param releaseTxnId the net-release credit leg's transaction id */
    record ReleaseOutcome(String releaseTxnId) {}

    record RefundCommand(
            String workspaceId,
            String escrowHoldId,
            BigDecimal amount,
            String currency,
            String description,
            String idempotencyKey) {}

    /** @param refundTxnId the refund credit leg's transaction id */
    record RefundOutcome(String refundTxnId) {}
}
