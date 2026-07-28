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
 * Two brand surfaces were split out of CR-24 as **CR-30**, and the Wave 6 decision record
 * (§10.3 of `wiki/errors/CREATOR-BUG-TRACKER.md`) then ruled on them separately — they are
 * NOT one job:
 *   - `brand-pipeline.tsx` splits INVITED/APPLIED/SHORTLISTED into `OUTREACH`, separate from
 *     `NEGOTIATING` (IN_NEGOTIATION only). `DealStage` folds APPLIED/SHORTLISTED into
 *     'negotiating', so deriving would silently move deals between pipeline columns.
 *     **Still outstanding.** `OUTREACH` was ruled worth keeping, so this migrates by deriving
 *     from `DealStage` plus that one documented delta. Note it also still maps
 *     `TERMS_AGREED -> CONTRACTED` — the last surviving copy of the CR-05/CR-24 defect, which
 *     means brand-chat and brand-pipeline currently disagree about the same deal.
 *   - `deal-room-dashboard.tsx` uses a 4-state proposal vocabulary
 *     ('proposed'/'accepted'/'rejected'/'negotiating') that is not a lifecycle at all.
 *     **Ruled NOT a defect and closed** — under a proposal vocabulary `TERMS_AGREED ->
 *     'accepted'` is literally correct. It keeps its local switch on purpose.
 *
 * So: do not read this module's existence as evidence the job is finished, and do not
 * "finish" it by collapsing `deal-room-dashboard` onto this. (CR-33 — this paragraph
 * previously pointed at CR-24 for both, which stopped being true when CR-30 was split out.)
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

/**
 * CR-34 — the exact `CollaborationStatus` set `Collaboration.canAccept()` permits
 * (`Collaboration.java:185-190`). `canCounter()` delegates to `canAccept()` verbatim, so this
 * one list governs Accept **and** Counter on every surface.
 *
 * Anything outside it makes `POST /deals/:id/accept` fail 409 `DEAL_NOT_ACCEPTABLE`
 * (`DealService.doAccept`) and `POST /deals/:id/counter` fail 409 `DEAL_NOT_NEGOTIABLE`.
 *
 * **Why it lives here and not in the rooms.** Both deal rooms used to keep private copies.
 * The creator comment deferred merging them to "when CR-07 wires the brand room up" — CR-07
 * shipped in Wave 2 and the lift never happened — while the brand comment claimed it was
 * "tracked separately", which was false. Two copies of one backend precondition is the exact
 * shape of CR-05, CR-13 and CR-24: when `canAccept()` moves and only one copy follows, the
 * two sides of a single negotiation disagree about whether an offer is still live, which is
 * CR-02's symptom reached by a different route. `deal-stage.ts` is a plain lib module, so
 * unlike a route module it can export this without disabling Fast Refresh
 * (`react-refresh/only-export-components`) for a whole page.
 *
 * ⚠️ **This mirrors a server precondition — it is not a UI preference.** Widening it to make
 * a button appear does not make the server accept the request; it re-creates CR-02. If it
 * needs to change, `Collaboration.canAccept()` is the thing that changes first.
 *
 * Note `TERMS_AGREED` is deliberately absent. It maps to the `negotiating` STAGE (see above),
 * which makes it tempting to treat the two as interchangeable — they are not. A `TERMS_AGREED`
 * deal reads "Negotiating" and cannot be accepted again. Gating any action on the stage rather
 * than on this list is the CR-27 trap.
 */
export const ACCEPTABLE_COLLABORATION_STATUSES: readonly CollaborationStatus[] = [
  'INVITED',
  'APPLIED',
  'SHORTLISTED',
  'IN_NEGOTIATION',
];

/**
 * CR-02 / CR-34 — may an offer on this deal still be accepted or countered?
 *
 * Takes the RAW backend status, never a {@link DealStage}: the stage vocabulary is lossy by
 * design and folds `TERMS_AGREED` in with genuinely-actionable states. Undefined/null answers
 * `false` — a room with no backend status behind it must not offer an action the server has
 * not agreed to.
 */
export function allowsProposalResponse(
  status: CollaborationStatus | null | undefined,
): boolean {
  if (!status) return false;
  return ACCEPTABLE_COLLABORATION_STATUSES.includes(status);
}

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
