import type { CreatorDeliverableRowStatus } from '@/lib/api';
import type { DealDeliverableItem } from '@/components/brand/deal-room/deal-deliverables-tab';
import type { TimelineEventMetadata } from '@/lib/types';

/** Strict gate per spec §11.4 — brand may review only SUBMITTED/RESUBMITTED. */
export function isBrandReviewableApiStatus(
  status: CreatorDeliverableRowStatus | string | undefined,
): boolean {
  if (!status) return false;
  const normalized = String(status).toUpperCase().replace(/-/g, '_');
  return normalized === 'SUBMITTED' || normalized === 'RESUBMITTED';
}

/** Brand may approve or request revision only when creator has submitted for review. */
export function isBrandReviewableStatus(
  status: CreatorDeliverableRowStatus | string | undefined,
): boolean {
  if (isBrandReviewableApiStatus(status)) return true;
  if (!status) return false;
  const ui = String(status).toLowerCase();
  return ui === 'under_review' || ui === 'submitted' || ui === 'resubmitted' || ui === 'pending_review';
}

export function mapApiStatusToTabStatus(
  status: CreatorDeliverableRowStatus,
): DealDeliverableItem['status'] {
  switch (status) {
    case 'SUBMITTED':
    case 'RESUBMITTED':
      return 'pending_review';
    case 'APPROVED':
    case 'VERIFIED':
    case 'POSTED':
    case 'METRICS_REPORTED':
      return 'approved';
    case 'REVISION_REQUESTED':
      return 'revision';
    default:
      return 'pending';
  }
}

export function mapApiStatusToTimelineUi(
  status: CreatorDeliverableRowStatus,
): NonNullable<TimelineEventMetadata['deliverableStatus']> {
  switch (status) {
    case 'SUBMITTED':
    case 'RESUBMITTED':
      return 'under_review';
    case 'REVISION_REQUESTED':
      return 'revision_requested';
    case 'APPROVED':
      return 'approved';
    default:
      return 'submitted';
  }
}

export function mapTabStatusToApiStatus(
  status: DealDeliverableItem['status'],
): CreatorDeliverableRowStatus | null {
  switch (status) {
    case 'pending_review':
      return 'SUBMITTED';
    case 'approved':
      return 'APPROVED';
    case 'revision':
      return 'REVISION_REQUESTED';
    default:
      return null;
  }
}

const ULID_PATTERN = /^[0-9A-HJKMNP-TV-Z]{26}$/i;
const MOCK_DELIVERABLE_ID_PATTERN = /^(del-\d+|del_[^_]+_\d+|[\w-]+-del-\d+)$/i;

/** True when id is a server ULID or present in a live API deliverable list. */
export function isApiBackedDeliverableId(
  id: string,
  apiRows: ReadonlyArray<{ id: string }> = [],
): boolean {
  if (apiRows.some((row) => row.id === id)) return true;
  return ULID_PATTERN.test(id) && !MOCK_DELIVERABLE_ID_PATTERN.test(id);
}

/** Inline brand-chat timeline status strings (distinct from tab status). */
export function mapApiStatusToInlineTimelineStatus(
  status: CreatorDeliverableRowStatus,
): 'approved' | 'pending_review' | 'revision_requested' | 'pending' {
  const tab = mapApiStatusToTabStatus(status);
  if (tab === 'approved') return 'approved';
  if (tab === 'revision') return 'revision_requested';
  if (tab === 'pending_review') return 'pending_review';
  return 'pending';
}
