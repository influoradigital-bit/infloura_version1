package com.influora.web.dto.admin;

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
}
