/**
 * CR-30 — the brand pipeline board's column vocabulary, and the one mapping onto it.
 *
 * This board does NOT use {@link DealStage}'s words, and that is deliberate: it separates
 * "we reached out / they applied" (`OUTREACH`) from "we are actively negotiating terms"
 * (`NEGOTIATING`), a distinction `DealStage` folds away. The Wave 6 ruling (§10.3 of
 * `wiki/errors/CREATOR-BUG-TRACKER.md`) kept that distinction — a brand pipeline that cannot
 * express it is a worse board.
 *
 * What it does NOT get to keep is its own switch over `CollaborationStatus`. It used to have
 * one, and it held the last surviving copy of `TERMS_AGREED -> CONTRACTED` — the mapping CR-05
 * deleted from `creator-chat.tsx` and CR-24 deleted from `brand-chat.tsx`. The result was that
 * one deal read "Negotiating" in the brand deal room while sitting in the **Contracted** column
 * here, for the same brand in the same session. So the vocabulary stays and the derivation is
 * shared: `DealStage` first, then exactly one documented delta.
 *
 * **Why this is a lib module and not a helper inside `brand-pipeline.tsx`.** It was local, and
 * being local is what let it drift unnoticed and untested. A route module cannot export a
 * non-component without disabling Fast Refresh for the whole page
 * (`react-refresh/only-export-components`), so "make it testable" and "leave it in the page"
 * are mutually exclusive. This is the same reasoning that gave `deal-stage.ts` its own home.
 */
import type { CollaborationStatus } from './types';
import { mapCollaborationStatusToDealStage, type DealStage } from './deal-stage';

/** The pipeline board's six columns, in board order. */
export type BrandPipelineStage =
  | 'OUTREACH'
  | 'NEGOTIATING'
  | 'CONTRACTED'
  | 'IN_PROGRESS'
  | 'REVIEW'
  | 'SETTLED';

/**
 * Raw `CollaborationStatus` -> pipeline column, or `null` for a deal this board declines to
 * place.
 *
 * ⚠️ **`TERMS_AGREED` belongs in `NEGOTIATING`, not `CONTRACTED`.** It is structurally
 * pre-contract: `DealService.doAccept` produces it and no contract row exists at that point.
 * Putting it under Contracted claims a contract that does not exist, which is what this
 * mapping was doing until CR-30.
 *
 * The single delta from {@link DealStage}: `APPLIED`/`SHORTLISTED` are `negotiating` there but
 * `OUTREACH` here. It is applied inside the `negotiating` arm — precisely where the conflict
 * is — rather than by re-deriving everything from the raw status, so every other column stays
 * honestly downstream of the shared switch. This is the pattern `brand-chat.tsx` uses.
 *
 * `CANCELLED`/`DISPUTED` return `null` and the caller filters the row out: this 6-stage board
 * has no honest place for them. Expressed as a stage the board declines rather than a
 * `default:`, so a future `CollaborationStatus` cannot be silently swallowed — `DealStage`'s
 * own switch has to classify it first. Giving this board a Disputed column (CR-26 gave the
 * creator side one) is a separate product call.
 */
export function mapCollaborationStatusToPipelineStage(
  status: CollaborationStatus,
): BrandPipelineStage | null {
  const stage: DealStage = mapCollaborationStatusToDealStage(status);
  switch (stage) {
    case 'new':
      return 'OUTREACH';
    case 'negotiating':
      return status === 'APPLIED' || status === 'SHORTLISTED' ? 'OUTREACH' : 'NEGOTIATING';
    case 'contracted':
      return 'CONTRACTED';
    case 'in_progress':
      return 'IN_PROGRESS';
    case 'review':
      return 'REVIEW';
    case 'completed':
      return 'SETTLED';
    case 'disputed':
      return null;
  }
}
