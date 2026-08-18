/**
 * Single source of truth for creator-facing application/collaboration status
 * labels, badge styling, and filter-bucket membership.
 *
 * Canonical map — CTO arbitration (wiki/build/my-applications-plan-2026-07-24.md),
 * later reviewed and UPHELD by CEO ruling 2026-08-18
 * (.proof-os/tasks/T-RULING-0818/SWAPNIL-RULING.md, Decision 1): a
 * withdrawn/declined application is `CANCELLED` and renders as "Closed", never
 * "Rejected" (by default — see DECLINE_WORDING_VARIANT below for the sanctioned
 * override).
 *
 * The ORIGINAL justification for that (Kabir R5: never surface "brand-internal
 * triage as a decision the brand hasn't finalized") was reviewed by the CEO and
 * found WRONG — a brand's decline through this product IS finalized, not
 * unfinalized triage. The ruling upheld the same conclusion for a different,
 * correct reason: the word "Rejected" carries emotional weight that harms
 * creator retention without adding clarity a creator doesn't already get from
 * the label "Closed" plus its description (which says who closed it and, if
 * given, why). Do not restate the old "unfinalized triage" reasoning as fact
 * anywhere in this codebase — it was the part of the arbitration the CEO
 * explicitly overruled.
 *
 * Backend is the source of truth for `statusLabel` (server computes it), but
 * the FE needs this map anywhere a label/badge/bucket is derived client-side
 * (filter tabs, retrofit of the older ad-hoc APPLICATION_STATUS_LABELS in
 * CreatorBrowseCampaignCard) — this file is that one place.
 *
 * CAVEAT (review condition 3, 2026-08-18): since Decision 2/1 above, that sentence no longer
 * means the server's `statusLabel` is safe to render directly — CreatorApplicationMapper still
 * computes "In negotiation" for TERMS_AGREED and doesn't know about DECLINE_WORDING_VARIANT
 * either, so it now disagrees with this file on both; nothing renders the server field today, but
 * anything that starts to must reconcile with this file first (see the backend's own fuller note).
 */

import { DECLINE_WORDING_VARIANT, type DeclineWordingVariant } from './decline-wording-variant';

export type ApplicationBucket =
  | 'applied'
  | 'shortlisted'
  | 'in_negotiation'
  | 'active'
  | 'completed'
  | 'closed';

export interface ApplicationBucketDef {
  id: ApplicationBucket;
  label: string;
}

/** Filter tabs, in display order. "All" is handled separately by callers (it's not a real bucket). */
export const APPLICATION_BUCKETS: ApplicationBucketDef[] = [
  { id: 'applied', label: 'Applied' },
  { id: 'shortlisted', label: 'Shortlisted' },
  { id: 'in_negotiation', label: 'In negotiation' },
  { id: 'active', label: 'Active' },
  { id: 'completed', label: 'Completed' },
  { id: 'closed', label: 'Closed' },
];

/**
 * F-0287/F-0303 decline wording — CEO ruling 2026-08-18, Decision 1
 * (.proof-os/tasks/T-RULING-0818/SWAPNIL-RULING.md).
 *
 * The ruling UPHOLDS the shipped default ("arbitration": label "Closed", a
 * neutral icon, no bare "Rejected" anywhere) but explicitly authorizes the
 * customer to override to the requirements document's literal wording
 * ("spec": "Rejected"/"Application Rejected"), and asks that BOTH variants be
 * fully built so switching is this one line, not a hunt through label maps.
 *
 * This is the single source of truth for that wording. Every surface that
 * renders decline wording — the application-status badge (`getApplicationStatusLabel`,
 * consumed by CreatorApplicationCard, CreatorBrowseCampaignCard, and
 * creator-campaign-detail.tsx) AND the ApplicationHistoryTimeline event
 * label/icon (`getDeclineWording`, consumed via `eventTypeLabel`/`eventTypeIcon`
 * in ApplicationHistoryTimeline.tsx) — reads off this one constant. That is the
 * actual fix for F-0303: its bug was exactly these two surfaces disagreeing
 * because each had grown its own hardcoded copy of the word.
 *
 * The switch itself (`DECLINE_WORDING_VARIANT`) lives in its own module,
 * decline-wording-variant.ts, and is re-exported below — see that file's doc
 * comment for why (review condition C1: a test needs to flip only the constant
 * and still run the real functions on top of it, which a same-module constant
 * cannot support without also mocking the functions).
 */
export type { DeclineWordingVariant };
export { DECLINE_WORDING_VARIANT };

export interface DeclineWording {
  /** Short badge/status word — the CANCELLED entry point for getApplicationStatusLabel/getApplicationStatusBadgeProps. */
  statusLabel: string;
  /** Fuller timeline event-title wording for the APPLICATION_REJECTED history event. */
  eventLabel: string;
  /**
   * Which icon family the timeline should use for the decline event. Kept as a
   * discriminator (not a component reference) so this module stays free of a
   * lucide-react/React dependency — ApplicationHistoryTimeline.tsx resolves it
   * to an actual icon component.
   */
  icon: 'neutral' | 'explicit';
}

/** Both fully-built variants. Pinned directly by tests regardless of which is currently the default. */
export const DECLINE_WORDING: Record<DeclineWordingVariant, DeclineWording> = {
  arbitration: { statusLabel: 'Closed', eventLabel: 'Closed', icon: 'neutral' },
  spec: { statusLabel: 'Rejected', eventLabel: 'Application Rejected', icon: 'explicit' },
};

/** The decline wording for whichever variant is currently live (DECLINE_WORDING_VARIANT). */
export function getDeclineWording(): DeclineWording {
  return DECLINE_WORDING[DECLINE_WORDING_VARIANT];
}

/**
 * Raw CollaborationStatus -> creator-facing label. Must match CreatorApplicationMapper.java.
 *
 * CANCELLED is deliberately absent here — its label is decline wording, and
 * decline wording has exactly one source (DECLINE_WORDING above); see
 * `getApplicationStatusLabel`, which resolves it from there so this map can't
 * silently drift into its own competing copy of the word again.
 */
const STATUS_LABELS: Record<string, string> = {
  APPLIED: 'Applied',
  SHORTLISTED: 'Shortlisted',
  IN_NEGOTIATION: 'In negotiation',
  // F-0327 — CEO ruling Decision 2: a creator seeing "In negotiation" for an
  // application the brand just accepted reads as "did they accept, or are we
  // still haggling?" TERMS_AGREED means the brand said yes; the card badge now
  // says so. IN_NEGOTIATION (a genuinely still-being-negotiated state) is
  // untouched — the two are distinct statuses and only this one changes.
  TERMS_AGREED: 'Accepted',
  CONTRACT_PENDING: 'Contract pending',
  CONTRACTED: 'Active',
  IN_PROGRESS: 'Active',
  REVIEW_PENDING: 'Active',
  REVISION_REQUESTED: 'Active',
  COMPLETED: 'Completed',
  DISPUTED: 'In dispute',
  // Not a real CollaborationStatus value — `CreatorCampaignListItem.applicationStatus` uses
  // this literal for brand-initiated invites on the browse-campaigns path. The "My
  // applications" page never sees it (its source is `Collaboration.source = APPLICATION`
  // only, which excludes invites) but CreatorBrowseCampaignCard needs a label for it too.
  INVITED: 'Invited',
};

/**
 * Raw CollaborationStatus -> filter bucket.
 *
 * TERMS_AGREED sits in `active`, not `in_negotiation` — CEO ruling Decision 5.
 * The technical objection is real (TERMS_AGREED is genuinely pre-contract; every
 * backend mapper agrees it's structurally "negotiating") but doesn't win: CONTRACT_PENDING
 * is already in `active` and is equally pre-signed, so the bucket boundary was
 * never "a signed contract exists" — it's "has the brand said yes". Leaving
 * TERMS_AGREED under "In negotiation" with an "Accepted" badge (Decision 2) would
 * actively mislead a creator into thinking they're still waiting for a decision.
 * `deal-stage.ts` keeps TERMS_AGREED as 'negotiating' on purpose — it answers a
 * different question (can this deal still be renegotiated) — do not change it
 * to match this bucket.
 */
const STATUS_BUCKETS: Record<string, ApplicationBucket> = {
  APPLIED: 'applied',
  SHORTLISTED: 'shortlisted',
  IN_NEGOTIATION: 'in_negotiation',
  TERMS_AGREED: 'active',
  CONTRACT_PENDING: 'active',
  CONTRACTED: 'active',
  IN_PROGRESS: 'active',
  REVIEW_PENDING: 'active',
  REVISION_REQUESTED: 'active',
  DISPUTED: 'active',
  COMPLETED: 'completed',
  CANCELLED: 'closed',
};

/**
 * Label to display for a raw status. Prefer the server-provided `statusLabel`
 * when available (it's the contract source of truth) — this is the fallback
 * for anywhere only the raw `status` enum value is on hand.
 *
 * CANCELLED is special-cased to the live decline-wording variant rather than
 * living in STATUS_LABELS, so this badge text and the ApplicationHistoryTimeline
 * event label can never disagree (see DECLINE_WORDING's own doc comment).
 */
export function getApplicationStatusLabel(status: string): string {
  if (status === 'CANCELLED') return getDeclineWording().statusLabel;
  return STATUS_LABELS[status] ?? status;
}

/** Which filter tab a raw status belongs to. Unknown statuses fall back to "closed" (never silently dropped). */
export function bucketOf(status: string): ApplicationBucket {
  return STATUS_BUCKETS[status] ?? 'closed';
}

export interface ApplicationStatusBadgeProps {
  /** shadcn Badge variant, when the styling is a plain variant rather than a custom className. */
  variant?: 'default' | 'secondary' | 'destructive' | 'outline';
  /** Custom className for statuses that need a specific color pairing (e.g. success). Wins over `variant` when set. */
  className?: string;
  label: string;
}

/**
 * Badge styling per the plan's color rules:
 * - Closed -> muted/destructive-ish (outline, not a loud red — it's not an error, just done)
 * - Active / Completed -> success (bg-success/text-success-foreground — WCAG AA pair already
 *   used elsewhere in this codebase, e.g. BrandSafetyBadge.tsx). TERMS_AGREED is bucketed
 *   `active` (F-0327/Decision 5 above) so it gets this same success styling.
 * - Applied / Shortlisted / In negotiation -> secondary
 * - In dispute -> destructive (needs to stand out — it's the one state that needs attention)
 */
export function getApplicationStatusBadgeProps(status: string): ApplicationStatusBadgeProps {
  const label = getApplicationStatusLabel(status);
  const bucket = bucketOf(status);

  if (status === 'DISPUTED') {
    return { variant: 'destructive', label };
  }
  if (status === 'INVITED') {
    return { variant: 'secondary', label };
  }

  switch (bucket) {
    case 'active':
    case 'completed':
      return { className: 'border-transparent bg-success text-success-foreground', label };
    case 'closed':
      return { variant: 'outline', className: 'text-muted-foreground', label };
    case 'applied':
    case 'shortlisted':
    case 'in_negotiation':
    default:
      return { variant: 'secondary', label };
  }
}
