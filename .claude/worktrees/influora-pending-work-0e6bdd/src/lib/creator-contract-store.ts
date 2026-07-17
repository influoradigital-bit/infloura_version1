import type { DealContractStatus } from '@/components/brand/deal-room/deal-contract-tab';
import { toDealRoomId } from '@/lib/creator-deal-messages';

const STORAGE_KEY = 'influora_creator_contract_status';

/** Known demo contracts by deal room id */
export const DEAL_CONTRACT_IDS: Record<string, string> = {
  '1': 'CTR-2024-001',
  '4': 'CTR-2024-004',
  '5': 'CTR-2024-005',
};

const DEFAULT_STATUS_BY_DEAL: Record<string, DealContractStatus> = {
  '1': 'brand_signed',
  '4': 'brand_signed',
  '5': 'active',
};

function readAll(): Record<string, DealContractStatus> {
  if (typeof sessionStorage === 'undefined') return {};
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    return raw ? (JSON.parse(raw) as Record<string, DealContractStatus>) : {};
  } catch {
    return {};
  }
}

function writeAll(data: Record<string, DealContractStatus>) {
  if (typeof sessionStorage === 'undefined') return;
  sessionStorage.setItem(STORAGE_KEY, JSON.stringify(data));
}

export function resolveDealId(id: string): string {
  return toDealRoomId(id);
}

export function resolveContractId(dealId: string): string {
  const roomId = resolveDealId(dealId);
  return DEAL_CONTRACT_IDS[roomId] ?? `CTR-2024-${roomId.padStart(3, '0')}`;
}

export function getDefaultContractStatus(dealId: string): DealContractStatus | undefined {
  return DEFAULT_STATUS_BY_DEAL[resolveDealId(dealId)];
}

export function inferContractStatusFromDeal(
  dealStatus?: string,
): DealContractStatus | undefined {
  if (dealStatus === 'contracted') return 'brand_signed';
  if (dealStatus === 'in_progress' || dealStatus === 'review') return 'active';
  if (dealStatus === 'completed') return 'active';
  return undefined;
}

export function getContractStatus(
  dealId: string,
  dealStatus?: string,
): DealContractStatus | undefined {
  const roomId = resolveDealId(dealId);
  const stored = readAll()[roomId];
  if (stored) return stored;
  const demoDefault = getDefaultContractStatus(roomId);
  if (demoDefault) return demoDefault;
  return inferContractStatusFromDeal(dealStatus);
}

export function getAllContractStatuses(): Record<string, DealContractStatus> {
  return { ...DEFAULT_STATUS_BY_DEAL, ...readAll() };
}

export function setContractStatus(dealId: string, status: DealContractStatus): void {
  const roomId = resolveDealId(dealId);
  const all = readAll();
  all[roomId] = status;
  writeAll(all);
}

export function dealHasContract(
  dealId: string,
  dealStatus?: string,
): boolean {
  const roomId = resolveDealId(dealId);
  if (getContractStatus(roomId) || DEAL_CONTRACT_IDS[roomId]) return true;
  return ['contracted', 'in_progress', 'review', 'completed'].includes(dealStatus ?? '');
}

/** After creator signs, escrow funds and contract becomes active. */
export function statusAfterCreatorSign(): DealContractStatus {
  return 'active';
}
