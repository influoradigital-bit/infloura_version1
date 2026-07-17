/**
 * Production stage palette (Lilac Mist) — single source of truth for status badges.
 * CSS variables are defined in src/app/globals.css.
 */

export type StageKey =
  | 'draft'
  | 'outreach'
  | 'negotiating'
  | 'contracted'
  | 'in_progress'
  | 'review'
  | 'approved'
  | 'disputed'
  | 'paused'
  | 'cancelled'
  | 'expired';

export const STAGE_BADGE_CLASS: Record<StageKey, string> = {
  draft: 'bg-stage-draft text-stage-draft-fg border-stage-draft-border',
  outreach: 'bg-stage-outreach text-stage-outreach-fg border-stage-outreach-border',
  negotiating: 'bg-stage-negotiating text-stage-negotiating-fg border-stage-negotiating-border',
  contracted: 'bg-stage-contracted text-stage-contracted-fg border-stage-contracted-border',
  in_progress: 'bg-stage-progress text-stage-progress-fg border-stage-progress-border',
  review: 'bg-stage-review text-stage-review-fg border-stage-review-border',
  approved: 'bg-stage-approved text-stage-approved-fg border-stage-approved-border',
  disputed: 'bg-stage-disputed text-stage-disputed-fg border-stage-disputed-border',
  paused: 'bg-stage-paused text-stage-paused-fg border-stage-paused-border',
  cancelled: 'bg-stage-cancelled text-stage-cancelled-fg border-stage-cancelled-border',
  expired: 'bg-stage-expired text-stage-expired-fg border-stage-expired-border',
};

/** Tailwind classes for a bordered status badge */
export function stageBadgeClass(stage: StageKey): string {
  return `border ${STAGE_BADGE_CLASS[stage]}`;
}

/** Map API / UI status strings to stage keys */
const STATUS_TO_STAGE: Record<string, StageKey> = {
  // Draft
  DRAFT: 'draft',
  draft: 'draft',
  // Outreach / pending
  OUTREACH: 'outreach',
  PENDING: 'outreach',
  SHORTLISTED: 'outreach',
  FUNDED: 'outreach',
  new_proposal: 'outreach',
  // Negotiating
  NEGOTIATING: 'negotiating',
  negotiating: 'negotiating',
  COUNTERED: 'negotiating',
  // Contracted
  CONTRACTED: 'contracted',
  CONTRACT_SIGNING: 'contracted',
  contracted: 'contracted',
  pending_signature: 'contracted',
  // In progress
  IN_PROGRESS: 'in_progress',
  IN_PRODUCTION: 'in_progress',
  LIVE: 'in_progress',
  MATCHED: 'in_progress',
  in_progress: 'in_progress',
  INITIATED: 'in_progress',
  PROCESSING: 'in_progress',
  submitted: 'in_progress',
  // Review
  IN_REVIEW: 'review',
  REVIEW_PENDING: 'review',
  REVISION_REQUESTED: 'review',
  review: 'review',
  in_review: 'review',
  revision_requested: 'review',
  pending_review: 'review',
  // Approved / settled
  ACTIVE: 'approved',
  APPROVED: 'approved',
  SETTLED: 'approved',
  SETTLING: 'approved',
  COMPLETED: 'approved',
  completed: 'approved',
  ACCEPTED: 'approved',
  signed: 'approved',
  PAID: 'approved',
  // Disputed
  DISPUTED: 'disputed',
  REJECTED: 'disputed',
  rejected: 'disputed',
  FAILED: 'disputed',
  expired: 'expired',
  // Lifecycle
  PAUSED: 'paused',
  CANCELLED: 'cancelled',
  QUEUED: 'draft',
};

export function statusToStage(status: string): StageKey {
  return STATUS_TO_STAGE[status] ?? STATUS_TO_STAGE[status.toUpperCase()] ?? 'draft';
}

export function statusBadgeClass(status: string): string {
  return stageBadgeClass(statusToStage(status));
}
