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

/** Coarse status from {@link Deal.contractStatus} when full contract is not loaded yet. */
export function mapDealApiContractStatus(
  status?: ContractStatus,
  escrowFunded = false,
): DealContractStatus | undefined {
  if (!status) return undefined;
  switch (status) {
    case 'DRAFT':
      return 'generated';
    case 'PENDING_SIGNATURES':
      return escrowFunded ? 'active' : 'brand_signed';
    case 'ACTIVE':
    case 'COMPLETED':
      return escrowFunded ? 'active' : 'creator_signed';
    default:
      return undefined;
  }
}

export function canCreatorSignDealStatus(status: DealContractStatus): boolean {
  return status === 'brand_signed';
}

export function dealHasContractFromApi(deal: {
  contractId?: string;
  status: string;
}): boolean {
  if (deal.contractId) return true;
  return ['contracted', 'in_progress', 'review', 'completed'].includes(deal.status);
}
