import type { Deal, DealMessage, MessageKind } from '@/lib/api';
import type { CollaborationStatus, ContractStatus } from '@/lib/types';
import { formatTimeAgo, getInitials } from '@/lib/helpers';
import { formatMessageTimestamp } from '@/lib/creator-deal-messages';

/** UI status for the unified creator-deals list page. */
export type CreatorDealsPageStatus =
  | 'new'
  | 'negotiating'
  | 'contracted'
  | 'in_progress'
  | 'review'
  | 'completed';

/** UI status for creator-chat deal room sidebar. */
export type CreatorChatDealStatus =
  | 'new_proposal'
  | 'negotiating'
  | 'contracted'
  | 'in_progress'
  | 'review'
  | 'completed';

export function parseDealAmount(value: unknown): number {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
}

export function mapCollaborationStatusToDealsPage(
  status: CollaborationStatus,
): CreatorDealsPageStatus {
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
    case 'CANCELLED':
    case 'DISPUTED':
      return 'completed';
    default:
      return 'negotiating';
  }
}

export function mapCollaborationStatusToChatPage(
  status: CollaborationStatus,
): CreatorChatDealStatus {
  switch (status) {
    case 'INVITED':
      return 'new_proposal';
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
    case 'CANCELLED':
    case 'DISPUTED':
      return 'completed';
    default:
      return 'negotiating';
  }
}

export interface CreatorDealsPageRow {
  id: string;
  brandId: string;
  brandName: string;
  brandLogo?: string;
  brandVerified: boolean;
  brandRating?: number;
  brandPaymentSpeed?: string;
  campaignTitle: string;
  status: CreatorDealsPageStatus;
  budget: number;
  deliverables: Array<{ type: string; count: number }>;
  deadline: string;
  lastMessage?: string;
  lastMessageAt?: Date;
  unreadCount: number;
  deliverablesDone: number;
  deliverablesTotal: number;
  receivedAt?: Date;
  expiresAt?: Date;
  escrowFunded: boolean;
}

export interface CreatorChatDealRoom {
  id: string;
  brandName: string;
  brandLogo: string;
  brandInitials: string;
  campaignName: string;
  status: CreatorChatDealStatus;
  dealAmount: number;
  lastMessage: string;
  lastMessageTime: string;
  unreadCount: number;
  deliverablesDone: number;
  deliverablesTotal: number;
  contractId?: string;
  contractStatus?: ContractStatus;
  escrowFunded?: boolean;
}

export interface CreatorChatTimelineEvent {
  id: string;
  type:
    | 'message'
    | 'proposal'
    | 'counter_proposal'
    | 'contract'
    | 'deliverable'
    | 'payment'
    | 'system';
  sender: 'brand' | 'creator' | 'system';
  timestamp: string;
  content?: string;
  metadata?: Record<string, unknown>;
}

export function mapDealToDealsPageRow(deal: Deal): CreatorDealsPageRow {
  const receivedAt = deal.lastMessageAt ? new Date(deal.lastMessageAt) : undefined;
  return {
    id: deal.id,
    brandId: deal.counterpartyId,
    brandName: deal.counterpartyName,
    brandLogo: deal.counterpartyAvatar ?? undefined,
    brandVerified: true,
    campaignTitle: deal.campaignName,
    status: mapCollaborationStatusToDealsPage(deal.status),
    budget: parseDealAmount(deal.dealValue),
    deliverables: [],
    deadline: deal.nextDeadline ?? '',
    lastMessage: deal.lastMessage,
    lastMessageAt: receivedAt,
    receivedAt: deal.status === 'INVITED' ? receivedAt : undefined,
    unreadCount: deal.unreadCount,
    deliverablesDone: deal.deliverablesDone,
    deliverablesTotal: deal.deliverablesTotal,
    escrowFunded: deal.escrowFunded,
    // Explicitly undefined: the `GET /deals` summary payload does not carry these.
    // The page renders each behind a truthy guard, so they simply don't show — an
    // honest empty state, never a fabricated rating/badge. If the deals summary API
    // later returns brand rating / payout-speed / an offer-expiry, populate here.
    brandRating: undefined,
    brandPaymentSpeed: undefined,
    expiresAt: undefined,
  };
}

export function mapDealToChatRoom(deal: Deal): CreatorChatDealRoom {
  return {
    id: deal.id,
    brandName: deal.counterpartyName,
    brandLogo: deal.counterpartyAvatar ?? '',
    brandInitials: getInitials(deal.counterpartyName),
    campaignName: deal.campaignName,
    status: mapCollaborationStatusToChatPage(deal.status),
    dealAmount: parseDealAmount(deal.dealValue),
    lastMessage: deal.lastMessage ?? '',
    lastMessageTime: deal.lastMessageAt ? formatTimeAgo(deal.lastMessageAt) : '',
    unreadCount: deal.unreadCount,
    deliverablesDone: deal.deliverablesDone,
    deliverablesTotal: deal.deliverablesTotal,
    contractId: deal.contractId,
    contractStatus: deal.contractStatus,
    escrowFunded: deal.escrowFunded,
  };
}

function mapMessageKindToEventType(
  kind: MessageKind,
  metadata?: Record<string, unknown>,
): CreatorChatTimelineEvent['type'] {
  if (kind === 'proposal') {
    return metadata?.proposalType === 'counter' ? 'counter_proposal' : 'proposal';
  }
  const map: Partial<Record<MessageKind, CreatorChatTimelineEvent['type']>> = {
    text: 'message',
    system: 'system',
    contract: 'contract',
    deliverable: 'deliverable',
    payment: 'payment',
    shipment: 'system',
  };
  return map[kind] ?? 'message';
}

export function mapDealMessageToTimelineEvent(msg: DealMessage): CreatorChatTimelineEvent {
  return {
    id: msg.id,
    type: mapMessageKindToEventType(msg.kind, msg.metadata),
    sender: msg.senderType,
    timestamp: formatMessageTimestamp(msg.createdAt),
    content: msg.content,
    metadata: msg.metadata,
  };
}
