/**
 * Single source of truth for creator-facing application/collaboration status
 * labels, badge styling, and filter-bucket membership.
 *
 * Canonical map — CTO arbitration (wiki/build/my-applications-plan-2026-07-24.md):
 * there is NO "Rejected" status; a withdrawn/declined application is
 * `CANCELLED` and must render as "Closed", never "Rejected" (Kabir R5 —
 * never surface brand-internal triage as a decision the brand hasn't
 * finalized).
 *
 * Backend is the source of truth for `statusLabel` (server computes it), but
 * the FE needs this map anywhere a label/badge/bucket is derived client-side
 * (filter tabs, retrofit of the older ad-hoc APPLICATION_STATUS_LABELS in
 * CreatorBrowseCampaignCard) — this file is that one place.
 */

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

/** Raw CollaborationStatus -> creator-facing label. Must match CreatorApplicationMapper.java. */
const STATUS_LABELS: Record<string, string> = {
  APPLIED: 'Applied',
  SHORTLISTED: 'Shortlisted',
  IN_NEGOTIATION: 'In negotiation',
  TERMS_AGREED: 'In negotiation',
  CONTRACT_PENDING: 'Contract pending',
  CONTRACTED: 'Active',
  IN_PROGRESS: 'Active',
  REVIEW_PENDING: 'Active',
  REVISION_REQUESTED: 'Active',
  COMPLETED: 'Completed',
  CANCELLED: 'Closed',
  DISPUTED: 'In dispute',
  // Not a real CollaborationStatus value — `CreatorCampaignListItem.applicationStatus` uses
  // this literal for brand-initiated invites on the browse-campaigns path. The "My
  // applications" page never sees it (its source is `Collaboration.source = APPLICATION`
  // only, which excludes invites) but CreatorBrowseCampaignCard needs a label for it too.
  INVITED: 'Invited',
};

/** Raw CollaborationStatus -> filter bucket. */
const STATUS_BUCKETS: Record<string, ApplicationBucket> = {
  APPLIED: 'applied',
  SHORTLISTED: 'shortlisted',
  IN_NEGOTIATION: 'in_negotiation',
  TERMS_AGREED: 'in_negotiation',
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
 */
export function getApplicationStatusLabel(status: string): string {
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
 *   used elsewhere in this codebase, e.g. BrandSafetyBadge.tsx)
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
