import type { CollaborationStatus } from '@/lib/types';

/**
 * The ONE switch over `CollaborationStatus` in this app.
 *
 * CR-05 fixed the creator side by collapsing two disagreeing mappers into one. CR-24 is the
 * brand mirror of the same defect: `brand-chat.tsx` carried a private copy that mapped
 * `TERMS_AGREED -> 'contracted'` — character-for-character the mapping deleted from
 * `creator-chat.tsx` as the CR-05 defect — so the two sides of a single negotiation could
 * disagree about what stage it was in.
 *
 * This module exists so there is a neutral home for it. It previously lived in
 * `creator-deal-mappers.ts` under a comment saying it was "scoped to creator deliberately";
 * that scoping is what let the brand copy drift. `creator-deal-mappers.ts` re-exports both
 * symbols, so existing creator imports are unchanged.
 *
 * ---------------------------------------------------------------------------
 * Consumers keep their own vocabulary — they do not all render these words
 * ---------------------------------------------------------------------------
 * Sharing the SWITCH is the point; sharing the labels is not. A surface whose vocabulary
 * differs should map `DealStage -> its own words` and state why, rather than re-deriving from
 * `CollaborationStatus` and silently drifting. Current deltas, all deliberate:
 *
 *   - `brand-chat.tsx` has no 'new' — from the brand's side an INVITED deal is one they
 *     already reached out on, so it folds into 'negotiating'. It also drops 'disputed'
 *     (returns null and filters the row out) because that list has no disputed chip.
 *
 * Two brand surfaces are deliberately NOT migrated, because their vocabularies encode
 * distinctions `DealStage` cannot express — collapsing them onto this would change what those
 * boards show, which is a product call and needs its own QA pass (Priya's CR-24 scope note):
 *   - `brand-pipeline.tsx` splits INVITED/APPLIED/SHORTLISTED into `OUTREACH`, separate from
 *     `NEGOTIATING` (IN_NEGOTIATION only). `DealStage` folds APPLIED/SHORTLISTED into
 *     'negotiating', so deriving would silently move deals between pipeline columns.
 *   - `deal-room-dashboard.tsx` uses a 4-state proposal vocabulary
 *     ('proposed'/'accepted'/'rejected'/'negotiating') that is not a lifecycle at all.
 * Do not read this module's existence as evidence that job is finished — CR-24 remains open
 * for those two.
 *
 * ---------------------------------------------------------------------------
 * Why TERMS_AGREED is pre-contract
 * ---------------------------------------------------------------------------
 * Every backend DISPLAY mapper agrees: `DashboardService.bucketFor`, `AdminCampaignService`,
 * and `CreatorApplicationMapper` all put TERMS_AGREED in NEGOTIATING. It is structurally
 * pre-contract too — `DealService.doAccept` transitions to TERMS_AGREED and no contract row
 * exists at that point. As of CR-13 the FILTER path (`DealService.statusesForFilter`) agrees
 * as well, so the last server-side contradiction is gone.
 */
export type DealStage =
  | 'new'
  | 'negotiating'
  | 'contracted'
  | 'in_progress'
  | 'review'
  | 'completed'
  | 'disputed';

/** The single source of truth for coarse deal stage. See {@link DealStage}. */
export function mapCollaborationStatusToDealStage(status: CollaborationStatus): DealStage {
  switch (status) {
    case 'INVITED':
      return 'new';
    case 'APPLIED':
    case 'SHORTLISTED':
    case 'IN_NEGOTIATION':
    case 'TERMS_AGREED':
      return 'negotiating';
    case 'CONTRACT_PENDING':
    case 'CONTRACTED':
      return 'contracted';
    case 'IN_PROGRESS':
      return 'in_progress';
    case 'REVIEW_PENDING':
    case 'REVISION_REQUESTED':
      return 'review';
    case 'COMPLETED':
      return 'completed';
    // CR-26 — their own bucket. Folding these into 'completed' rendered "Done" on a deal the
    // creator was actively contesting, which is a false statement rather than a cosmetic one.
    case 'CANCELLED':
    case 'DISPUTED':
      return 'disputed';
    default:
      return 'negotiating';
  }
}
