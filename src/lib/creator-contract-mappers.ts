import type { DealContractStatus } from '@/components/brand/deal-room/deal-contract-tab';
import type { ContractApiRecord } from '@/lib/api';
import type { ContractStatus } from '@/lib/types';

/** Map Task #23 {@link ContractApiRecord} to deal-room UI status. */
export function mapApiContractToDealStatus(
  contract: Pick<ContractApiRecord, 'status' | 'brandSignedAt' | 'creatorSignedAt'>,
  escrowFunded = false,
): DealContractStatus {
  const brandSigned = Boolean(contract.brandSignedAt);
  const creatorSigned = Boolean(contract.creatorSignedAt);

  if (contract.status === 'ACTIVE' || contract.status === 'COMPLETED') {
    return escrowFunded || contract.status === 'ACTIVE' ? 'active' : 'creator_signed';
  }

  if (creatorSigned && brandSigned) {
    return escrowFunded ? 'active' : 'creator_signed';
  }

  if (brandSigned && !creatorSigned) {
    return 'brand_signed';
  }

  return 'generated';
}

/**
 * Coarse status from {@link Deal.contractStatus} when full contract is not loaded yet.
 *
 * F-0250 — `PENDING_SIGNATURES` is reached by the backend after exactly ONE signature from
 * EITHER party, with no ordering enforced (see `Contract#advanceIfFullySigned`,
 * influora-api/src/main/java/com/influora/domain/entity/Contract.java:148-154, and the
 * creator-authenticated self-sign path `ContractService#recordSignatureForCreator`,
 * influora-api/src/main/java/com/influora/service/ContractService.java:576). This function is
 * only ever given the bare enum + `escrowFunded` — never `brandSignedAt`/`creatorSignedAt` — so
 * for `PENDING_SIGNATURES` it CANNOT know which party signed.
 *
 * F-0250 follow-up — returning `'generated'` unconditionally (the first fix) was itself a
 * regression on the more common brand-first path: `CreatorDealContractTab.canSign` and
 * `CreatorContractPanel.shouldShowSignButton` gate on `status === 'brand_signed'`, so
 * `'generated'` hides the CREATOR's Sign control whenever the brand actually signed first,
 * and `awaitingBrandSignature`/`creatorSigningStatus()` then assert "awaiting brand signature" —
 * false, since the brand already signed. That is a deadlock on the common path, just moved to
 * the other party.
 *
 * `'pending_signature'` is the honest member for this case: "exactly one party has signed,
 * which one is unknown." Every consumer treats it as "either party's Sign control may still be
 * live" and never labels a specific party as having signed while in this state — see
 * `DealContractTab.canBrandSign`, `CreatorDealContractTab.canSign`,
 * `CreatorContractPanel.shouldShowSignButton`, and `CreatorContractCard`'s status switch. Callers
 * already replace this coarse guess with the accurate, timestamp-driven
 * `mapApiContractToDealStatus` once the full contract record loads (brand-chat.tsx:1619-1623,
 * creator-chat.tsx:1707-1711), so the window in which this under-informative value is shown is
 * bounded by that fetch.
 */
export function mapDealApiContractStatus(
  status?: ContractStatus,
  escrowFunded = false,
): DealContractStatus | undefined {
  if (!status) return undefined;
  switch (status) {
    case 'DRAFT':
      return 'generated';
    case 'PENDING_SIGNATURES':
      return escrowFunded ? 'active' : 'pending_signature';
    case 'ACTIVE':
    case 'COMPLETED':
      return escrowFunded ? 'active' : 'creator_signed';
    default:
      return undefined;
  }
}
