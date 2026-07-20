/** Session-persisted creator deal messages (demo until API is wired). */
import { uniqueId } from '@/lib/unique-id';

export interface PersistedDealMessage {
  id: string;
  dealId: string;
  sender: 'brand' | 'creator';
  content: string;
  timestamp: string;
}

const STORAGE_KEY = 'influora_creator_deal_messages';

function readAll(): Record<string, PersistedDealMessage[]> {
  if (typeof sessionStorage === 'undefined') return {};
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    return raw ? (JSON.parse(raw) as Record<string, PersistedDealMessage[]>) : {};
  } catch {
    return {};
  }
}

function writeAll(data: Record<string, PersistedDealMessage[]>) {
  if (typeof sessionStorage === 'undefined') return;
  sessionStorage.setItem(STORAGE_KEY, JSON.stringify(data));
}

/** Normalize inbox proposal id or `collab-*` id to deal room id. */
export function toDealRoomId(collaborationOrProposalId: string): string {
  if (collaborationOrProposalId.startsWith('collab-')) {
    return collaborationOrProposalId.replace('collab-', '');
  }
  return collaborationOrProposalId;
}

export function getPersistedMessages(dealId: string): PersistedDealMessage[] {
  const roomId = toDealRoomId(dealId);
  return readAll()[roomId] ?? [];
}

export function addPersistedMessage(
  dealId: string,
  content: string,
  sender: 'brand' | 'creator' = 'creator',
): PersistedDealMessage {
  const message: PersistedDealMessage = {
    id: uniqueId('msg'),
    dealId,
    sender,
    content: content.trim(),
    timestamp: new Date().toISOString(),
  };
  const roomId = toDealRoomId(dealId);
  message.dealId = roomId;
  const all = readAll();
  all[roomId] = [...(all[roomId] ?? []), message];
  writeAll(all);
  return message;
}

export function formatMessageTimestamp(isoOrDate: string | Date): string {
  const d = typeof isoOrDate === 'string' ? new Date(isoOrDate) : isoOrDate;
  const date = d.toLocaleDateString('en-CA');
  const time = d.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', hour12: false });
  return `${date} ${time}`;
}
