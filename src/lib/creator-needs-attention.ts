import { api, type CreatorDeliverableListItem } from '@/lib/api';
import type { CreatorDealsPageRow } from '@/lib/creator-deal-mappers';

/**
 * F-0631 (two-queries-can-disagree) — the ONE definition of "needs your attention" shared by
 * the creator dashboard (`creator-dashboard.tsx`) and the Meera chat desk
 * (`components/creator/meera/MeeraDesk.tsx`).
 *
 * Round 2 QA (MEERA-CHAT-DESIGN-SPEC.md follow-up): the desk's "Deals needing you" tile shipped
 * in round 1 only summed unread messages + unsigned contracts, silently omitting the
 * dashboard's third figure (submittable deliverables) — a second, narrower definition of the
 * same idea, exactly the bug class F-0631 already named once. Extracted here so BOTH surfaces
 * call the SAME functions on the SAME inputs and cannot independently drift again.
 *
 * Every function below is a straight relocation of what `creator-dashboard.tsx` already did —
 * behaviour is unchanged, only the file it lives in. `computeDealAttentionCounts` takes an
 * already-fetched `deals` array rather than fetching one itself, so a caller with its own deals
 * source (the dashboard's mock-mode `mockDeals` fixture, or the desk's live
 * `api.deals.list` call) reuses the exact same counting without this module dictating where the
 * deals came from.
 */

/**
 * The `DealStage` buckets a deal counts as "active" for the deliverables-due rollup. Deliberately
 * NOT "has anything outstanding" — a 'new'/'negotiating' deal has nothing to submit yet.
 */
export function isActiveDeal(row: CreatorDealsPageRow): boolean {
  return row.status === 'contracted' || row.status === 'in_progress' || row.status === 'review';
}

export function countSubmittableDeliverables(items: CreatorDeliverableListItem[]): number {
  return items.filter(
    (d) =>
      !d.completed &&
      (d.status === 'PENDING' || d.status === 'DRAFT' || d.status === 'REVISION_REQUESTED'),
  ).length;
}

/**
 * CR-51 — one batched `listForDeals` call covering every active deal id at once, rather than one
 * `listForDeal` HTTP call per deal (an N+1 waterfall on every dashboard/desk load). See
 * `CreatorDeliverableService#listForCollaborations` for the server-side query-count reasoning.
 */
export async function loadDeliverablePendingCount(dealIds: string[]): Promise<number> {
  if (dealIds.length === 0) return 0;
  const byDeal = await api.creatorDeliverables
    .listForDeals(dealIds)
    .catch(() => ({}) as Record<string, CreatorDeliverableListItem[]>);
  return dealIds.reduce((sum, id) => sum + countSubmittableDeliverables(byDeal[id] ?? []), 0);
}

export interface DealAttentionCounts {
  /** Sum of `unreadCount` over EVERY deal — NOT filtered to active deals. The dashboard has
   *  never filtered this figure; matched here as-is (Round 2 QA note: "the dashboard filters
   *  unread by isActiveDeal. It does not — match the dashboard as it is."). */
  unreadMessages: number;
  /** Deliverables actually submittable, over ACTIVE deals only (`isActiveDeal`). */
  submittableDeliverables: number;
}

/**
 * The two `GET /deals`-derived figures of "needs your attention": unread messages (all deals)
 * and submittable deliverables (active deals only, batched). Does not fetch `deals` itself —
 * the caller already has them (mapped through `mapDealToDealsPageRow`, live or mock).
 */
export async function computeDealAttentionCounts(
  deals: CreatorDealsPageRow[],
): Promise<DealAttentionCounts> {
  const unreadMessages = deals.reduce((sum, d) => sum + d.unreadCount, 0);
  const activeIds = deals.filter(isActiveDeal).map((d) => d.id);
  const submittableDeliverables = await loadDeliverablePendingCount(activeIds);
  return { unreadMessages, submittableDeliverables };
}

/**
 * `GET /contracts/unsigned` (F-0623) — the same query the dashboard's own "awaiting signature"
 * tile and its "Contracts awaiting your signature" list both read, collapsed to just the count.
 * A caller that also needs the full list (to render rows, e.g. the dashboard) should call
 * `api.contracts.listUnsigned('creator')` directly instead — this wrapper exists for callers
 * (the desk) that only need the number.
 */
export async function fetchAwaitingSignatureCount(): Promise<number> {
  const unsigned = await api.contracts.listUnsigned('creator');
  return unsigned.length;
}

/** The full "needs your attention" total — unread + submittable deliverables + unsigned
 *  contracts. Same 3-figure sum `creator-dashboard.tsx`'s `pendingTotal` computes. */
export function totalAttentionCount(
  counts: DealAttentionCounts,
  awaitingSignatureCount: number,
): number {
  return counts.unreadMessages + counts.submittableDeliverables + awaitingSignatureCount;
}
