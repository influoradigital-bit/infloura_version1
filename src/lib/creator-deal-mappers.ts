import type { Deal, DealMessage, MessageKind } from '@/lib/api';
import type { CollaborationStatus, ContractStatus } from '@/lib/types';
import { formatTimeAgo, getInitials } from '@/lib/helpers';
import { formatMessageTimestamp } from '@/lib/creator-deal-messages';

/**
 * The one coarse UI bucketing of a collaboration's lifecycle stage.
 *
 * CR-05 — there used to be two derivations of this: `mapCollaborationStatusToDealsPage`
 * here (used by the deals list) and a private `mapDealStatusToRoomStatus` inside
 * `creator-chat.tsx` (used by the deal room). They disagreed on APPLIED, SHORTLISTED,
 * TERMS_AGREED and DISPUTED, so one collaboration rendered "Negotiating" on
 * /creator/deals and "Contracted" inside its own room. The captured live payload in
 * `creator-deal-mappers.test.ts` is exactly that case (status TERMS_AGREED).
 *
 * The fix is structural rather than a copied table: this is the ONLY switch over
 * `CollaborationStatus` across the CREATOR surfaces, and both of them read it. Adding a
 * creator surface means importing this, never re-deriving it.
 *
 * Scoped to "creator" deliberately, because it is not yet true of the app as a whole. Three
 * more switches over `CollaborationStatus` live on the brand side and are NOT migrated:
 *   - `src/pages/brand-chat.tsx:164`                          — TERMS_AGREED -> 'contracted'
 *   - `src/pages/brand-pipeline.tsx:83`                       — TERMS_AGREED -> 'CONTRACTED'
 *   - `src/components/brand/deals/deal-room-dashboard.tsx:81`  — own vocabulary
 *                                                               ('proposed'/'rejected')
 * The `brand-chat.tsx` one is character-for-character the mapping deleted from
 * `creator-chat.tsx` as the CR-05 defect, so the same collaboration still reads
 * "Negotiating" in the creator list and room and "Contracted" in the brand room. That is a
 * known, ticketed gap, not an oversight: the brand vocabulary feeds that page's chips and
 * filters and needs its own QA pass, so the migration was ruled out of this wave. Do not
 * read this module's existence as evidence the job is finished.
 *
 * ---------------------------------------------------------------------------
 * Why TERMS_AGREED is pre-contract — and where the server contradicts itself
 * ---------------------------------------------------------------------------
 * `CollaborationStatus` (influora-api/.../domain/enums/CollaborationStatus.java) has 13
 * states. Three backend DISPLAY mappers collapse them, and all three agree TERMS_AGREED is
 * still pre-contract:
 *   - `DashboardService.bucketFor:126`  — INVITED, APPLIED, SHORTLISTED, IN_NEGOTIATION,
 *                                         TERMS_AGREED -> NEGOTIATING;
 *                                         CONTRACT_PENDING, CONTRACTED -> CONTRACTED
 *   - `AdminCampaignService:307`        — IN_NEGOTIATION, TERMS_AGREED -> "NEGOTIATING"
 *   - `CreatorApplicationMapper:40`     — IN_NEGOTIATION, TERMS_AGREED -> "In negotiation"
 *
 * Corroborated structurally: `DealService.doAccept` transitions to TERMS_AGREED and no
 * contract row exists at that point — `CollaborationLifecycleService.BEFORE_CONTRACT_PENDING`
 * lists TERMS_AGREED as a state a contract may still advance FROM. The deal room's old
 * mapper claimed a contract existed the instant the creator pressed Accept.
 *
 * The server does NOT speak with one voice, though, and pretending otherwise is how the next
 * CR-05 gets written. Two sites put TERMS_AGREED on the far side of the line:
 *   - `DealService.statusesForFilter:1030` — the FILTER path, also user-facing (it backs the
 *     deal-list chips): "contracted" is [TERMS_AGREED, CONTRACT_PENDING, CONTRACTED] and
 *     "negotiating" is only [SHORTLISTED, IN_NEGOTIATION].
 *   - `AdminBrandService:94`               — javadoc defines the pre-agreement set as ending
 *     at IN_NEGOTIATION; TERMS_AGREED counts as a committed financial obligation.
 *
 * That contradiction has a live consequence on the page CR-05 is about. `creator-deals.tsx`
 * passes its active chip straight to `GET /deals?status=`, so a TERMS_AGREED deal is badged
 * "Negotiating" here but is NOT returned by the "Negotiating" chip — and since this page
 * exposes no "Contracted" chip at all, it is reachable only under "All". Known, ticketed as
 * CR-13, and deliberately NOT patched around in the frontend: the badge follows the three
 * display mappers, and the filter path is the side that has to move.
 *
 * `'new'` is the one UI-only subdivision, and the filter path is the authority that makes it
 * legitimate: `statusesForFilter("new")` is exactly `[INVITED]` for the creator role, so the
 * "New" chip is server-backed rather than an invention.
 *
 * CANCELLED / DISPUTED have no server display bucket at all (`bucketFor` returns null —
 * they are excluded from the pipeline, and no status filter ever selects them). They are
 * parked in 'completed' because that is what the deals page has always done and what its
 * chips/empty states are built around. Known wart: the badge then reads "Done"/"Completed"
 * for a disputed deal. Fixing that needs a 7th bucket plus chip/filter/empty-state work
 * across both pages — tracked separately, deliberately not folded into CR-05.
 */
export type DealStage =
  | 'new'
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

/** The single source of truth for coarse deal stage. See {@link DealStage}. */
export function mapCollaborationStatusToDealStage(status: CollaborationStatus): DealStage {
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

/** UI status for the unified creator-deals list page. */
export type CreatorDealsPageStatus = DealStage;

/** UI status for the creator deal room. Same stage vocabulary — see {@link DealStage}. */
export type CreatorChatDealStatus = DealStage;

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
  /**
   * Real backend fields (DealDtos.java:38-40) — the honest source for contract state in
   * live mode; undefined/false in mock mode (mock rooms keep using the
   * creator-contract-store demo backbone instead).
   */
  contractId?: string;
  contractStatus?: ContractStatus;
  escrowFunded: boolean;
  /**
   * Raw backend collaboration status, straight off `Deal.status`. `status` above is lossy
   * on purpose (13 backend states collapsed into 6 UI stages), so anything that has to
   * agree with a backend precondition — `Collaboration.canAccept()`, above all — reads
   * this instead. Undefined only where there is no backend row behind the room.
   */
  collaborationStatus?: CollaborationStatus;
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
    status: mapCollaborationStatusToDealStage(deal.status),
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
    status: mapCollaborationStatusToDealStage(deal.status),
    dealAmount: parseDealAmount(deal.dealValue),
    lastMessage: deal.lastMessage ?? '',
    lastMessageTime: deal.lastMessageAt ? formatTimeAgo(deal.lastMessageAt) : '',
    unreadCount: deal.unreadCount,
    deliverablesDone: deal.deliverablesDone,
    deliverablesTotal: deal.deliverablesTotal,
    contractId: deal.contractId,
    contractStatus: deal.contractStatus,
    escrowFunded: deal.escrowFunded,
    collaborationStatus: deal.status,
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
