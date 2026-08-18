package com.influora.service;

import com.influora.domain.enums.CollaborationStatus;

/**
 * Server-side mirror of two frontend functions, so {@code ApplicationHistoryMapper} can attach an
 * explicit {@code dealPhase} to each history event instead of asking the client to guess it from
 * {@code eventType}: {@code mapCollaborationStatusToDealStage} ({@code src/lib/deal-stage.ts}) and
 * {@code getDealPhase} ({@code src/components/brand/deal-room/deal-room-step-progress.tsx}).
 *
 * <p>There is no shared codegen between the Java and TypeScript sides of this repo — this is a
 * hand-kept mirror. {@link #stage} and {@link #phase} are deliberately literal, line-for-line
 * transliterations of those two functions (same branch order, same string literals) rather than a
 * "simplified, equivalent" rewrite, so a future diff against the originals is easy to eyeball.
 *
 * <p><b>contractStatus.</b> {@code CONTRACT_GENERATED}/{@code CONTRACT_SIGNED} are now wired
 * ({@code ContractService#generate}/{@code #doRecordSignature}) and DO pass a real value —
 * derived from which event type is being computed (see {@link
 * ApplicationHistoryMapper#toItem}), not from a live re-read of {@code Contract.status} at
 * request time, which would answer with whatever the contract's status happens to be NOW rather
 * than what it truthfully was at the moment this historical event was recorded. Every other
 * wired event (apply/view/accept/reject, and the five money-path events wired alongside the
 * contract ones) resolves through {@link #stage} to {@code negotiating}, {@code contracted},
 * {@code in_progress}, or {@code disputed} — none of which consult {@code contractStatus} in
 * {@link #phase} (see below) — so passing {@code null} for those remains correct and is not a
 * gap. Concretely, for the two contract events this makes no OUTPUT difference today either:
 * {@link #phase}'s branch order checks {@code dealStatus === 'contracted'} before it ever
 * consults {@code contractStatus}, and both {@code CONTRACT_GENERATED} ({@code eventStatus =
 * CONTRACT_PENDING}) and {@code CONTRACT_SIGNED} ({@code eventStatus = CONTRACTED}) resolve to
 * the {@code contracted} bucket, which already short-circuits to {@code contract} regardless of
 * what {@code contractStatus} says. Wired anyway, honestly, rather than left as a documented gap
 * — it is the passed-through value, not the current no-op-ness of that value, that is correct.
 *
 * <p><b>Why {@code disputed} returns {@code null} instead of {@link #phase}'s own default.</b>
 * {@code getDealPhase} has no branch for a disputed/cancelled bucket and falls through to its
 * final {@code return 'negotiate'} — a false "still negotiating" statement for a dead deal.
 * Neither live call site ({@code brand-chat.tsx}, {@code creator-chat.tsx}) ever actually passes
 * a disputed bucket into {@code getDealPhase}; both filter those rows out upstream instead. {@link
 * #computeDealPhase} reproduces that same "don't ask" behaviour as an explicit {@code null} rather
 * than silently reproducing the default it was never meant to receive.
 */
final class DealPhaseCalculator {

    private DealPhaseCalculator() {}

    /** Literal mirror of {@code mapCollaborationStatusToDealStage} (src/lib/deal-stage.ts). */
    static String stage(CollaborationStatus status) {
        if (status == null) {
            return "negotiating";
        }
        switch (status) {
            case INVITED:
                return "new";
            case APPLIED:
            case SHORTLISTED:
            case IN_NEGOTIATION:
            case TERMS_AGREED:
                return "negotiating";
            case CONTRACT_PENDING:
            case CONTRACTED:
                return "contracted";
            case IN_PROGRESS:
                return "in_progress";
            case REVIEW_PENDING:
            case REVISION_REQUESTED:
                return "review";
            case COMPLETED:
                return "completed";
            case CANCELLED:
            case DISPUTED:
                return "disputed";
            default:
                return "negotiating";
        }
    }

    /**
     * Literal mirror of {@code getDealPhase} (deal-room-step-progress.tsx). {@code dealStatus} is
     * a {@link #stage} bucket string, not a raw {@link CollaborationStatus}.
     */
    static String phase(String dealStatus, String contractStatus) {
        if ("negotiating".equals(dealStatus)) {
            return "negotiate";
        }
        if ("contracted".equals(dealStatus)
                || "generated".equals(contractStatus)
                || "pending_signature".equals(contractStatus)
                || "brand_signed".equals(contractStatus)) {
            return "contract";
        }
        if ("creator_signed".equals(contractStatus) && !"in_progress".equals(dealStatus)) {
            return "escrow";
        }
        if ("in_progress".equals(dealStatus) || "review".equals(dealStatus)) {
            return "deliver";
        }
        if ("completed".equals(dealStatus)) {
            return "pay";
        }
        return "negotiate";
    }

    /**
     * Entry point for {@code ApplicationHistoryMapper}. See class javadoc for the null-on-disputed
     * rule and for what {@code contractStatus} means here (a value derived from the event TYPE,
     * not a live re-read of the contract).
     */
    static String computeDealPhase(CollaborationStatus eventStatus, String contractStatus) {
        String bucket = stage(eventStatus);
        if ("disputed".equals(bucket)) {
            return null;
        }
        return phase(bucket, contractStatus);
    }
}
