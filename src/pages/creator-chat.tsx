'use client';

import * as React from 'react';
import { useSearchParams } from 'react-router-dom';
import { CreatorLayout } from '@/components/creator/creator-layout';
import {
  MessageCircle,
  Send,
  Paperclip,
  Search,
  MoreVertical,
  CheckCheck,
  FileText,
  IndianRupee,
  Video,
  Upload,
  CheckCircle2,
  AlertCircle,
  Building2,
  Reply,
  FileSignature,
  Package,
  CreditCard,
  Loader2,
} from 'lucide-react';

import { cn, formatINR } from '@/lib/utils';
import {
  api,
  isApiLive,
  ApiError,
  type DealMessage,
  type DealMessageStreamStatus,
  type MessageKind,
  type ContractApiRecord,
  type CreatorDeliverableListItem,
  type ShipmentApiRecord,
  type ShipmentApiStatus,
  type ShipmentCondition,
} from '@/lib/api';
import { useToast } from '@/hooks/use-toast';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent } from '@/components/ui/card';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Textarea } from '@/components/ui/textarea';
import { Progress } from '@/components/ui/progress';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from '@/components/ui/dialog';
import { Label } from '@/components/ui/label';
import { CounterProposalForm, type CounterProposalFormData } from '@/components/creator/deal-room/counter-proposal-form';
import { CreatorContractPanel } from '@/components/creator/deal-room/creator-contract-panel';
import { CreatorContractCard } from '@/components/creator/deal-room/creator-contract-card';
import { DeliverableSubmission, type DeliverableSubmissionData } from '@/components/creator/deal-room/deliverable-submission';
import { DeliverableLifecyclePanel } from '@/components/creator/deal-room/deliverable-lifecycle-panel';
import { RevisionHandler } from '@/components/creator/deal-room/revision-handler';
import { ShippingAddressForm, type ShippingAddressData } from '@/components/creator/deal-room/shipping-address-form';
import { ReceiptConfirmation, type ReceiptData } from '@/components/creator/deal-room/receipt-confirmation';
import { ShipmentCard, type ShipmentStatus } from '@/components/shared/shipment-card';
import { MapPin } from 'lucide-react';
// CR-34 — `CollaborationStatus` is no longer imported here: the only value-level use was the
// local canAccept() mirror, which now lives in lib/deal-stage.ts. It survives in prose below.
import type { TimelineEvent as LibTimelineEvent, TimelineEventMetadata } from '@/lib/types';
import {
  addPersistedMessage,
  formatMessageTimestamp,
  getPersistedMessages,
} from '@/lib/creator-deal-messages';
import {
  mapDealToChatRoom,
  type CreatorChatDealRoom,
} from '@/lib/creator-deal-mappers';
// CR-34 — the shared mirror of Collaboration.canAccept(), previously duplicated here and in
// brand-chat.tsx. See lib/deal-stage.ts.
import { allowsProposalResponse } from '@/lib/deal-stage';
import {
  dealHasContract,
  getAllContractStatuses,
  getContractStatus,
  resolveContractId,
  setContractStatus,
} from '@/lib/creator-contract-store';
import {
  Sheet as ToolsSheet,
  SheetContent as ToolsSheetContent,
  SheetHeader as ToolsSheetHeader,
  SheetTitle as ToolsSheetTitle,
} from '@/components/ui/sheet';
import {
  DealRoomStepProgress,
  getDealPhase,
  type DealPhase,
} from '@/components/brand/deal-room/deal-room-step-progress';
import type { DealContractStatus } from '@/components/brand/deal-room/deal-contract-tab';
import { DealDeliverablesTab } from '@/components/brand/deal-room/deal-deliverables-tab';
import { DealPaymentsTab } from '@/components/brand/deal-room/deal-payments-tab';
import { CreatorDealContractTab } from '@/components/creator/deal-room/creator-deal-contract-tab';
import {
  mapApiContractToDealStatus,
  mapDealApiContractStatus,
} from '@/lib/creator-contract-mappers';

/**
 * Deal Room is chat-first. The 4 tabs (Messages / Contract / Deliverables /
 * Payments) collapse into a single Tools panel Sheet that the user opens
 * from the phase progress bar or quick-action toolbar.
 */
type CreatorToolsPanel = 'contract' | 'deliverables' | 'payments' | null;

const CREATOR_VALID_PANELS: CreatorToolsPanel[] = ['contract', 'deliverables', 'payments'];

const creatorPhaseToPanel: Record<DealPhase, CreatorToolsPanel> = {
  negotiate: null,
  contract: 'contract',
  escrow: 'payments',
  deliver: 'deliverables',
  pay: 'payments',
};

/**
 * Maps the backend `ShipmentApiStatus` (5 states, `wiki/decisions/shipment-backend-design-2026-07-24.md`)
 * onto the local `ShipmentStatus` UI enum from `components/shared/shipment-card.tsx` (6 states,
 * includes an `in_transit`/`out_for_delivery` split the backend doesn't model). Since the backend
 * only exposes SHIPPED as a single "it's on its way" state, it maps to the UI's `delivered` state —
 * that's the one that surfaces the "Mark as Received" action, and the backend's own transition rule
 * (`SHIPPED → RECEIVED/DAMAGED` is exactly "confirm receipt while shipped") matches that UI trigger.
 */
function mapShipmentApiStatusToUiStatus(status: ShipmentApiStatus): ShipmentStatus {
  switch (status) {
    case 'AWAITING_ADDRESS':
    case 'ADDRESS_PROVIDED':
      return 'created';
    case 'SHIPPED':
      return 'delivered';
    case 'RECEIVED':
      return 'received';
    case 'DAMAGED':
      return 'damaged';
    default:
      return 'created';
  }
}

// ============================================================================
// Types
// ============================================================================

/**
 * CR-05 — this room's view model and, critically, its `status` derivation now come from
 * `lib/creator-deal-mappers.ts`, the same module `creator-deals.tsx` reads. This file used
 * to declare its own shape AND its own `CollaborationStatus -> status` switch, which
 * disagreed with the deals list on four statuses (TERMS_AGREED above all) — so accepting a
 * proposal made the list say "Negotiating" while the room said "Contracted".
 */
type DealRoom = CreatorChatDealRoom;

interface ChatTimelineEvent {
  id: string;
  type: 'message' | 'proposal' | 'counter_proposal' | 'contract' | 'deliverable' | 'payment' | 'system';
  sender: 'brand' | 'creator' | 'system';
  timestamp: string;
  content?: string;
  metadata?: Record<string, unknown>;
}

function toLibTimelineEvent(event: ChatTimelineEvent): LibTimelineEvent {
  const tagMap: Record<ChatTimelineEvent['type'], LibTimelineEvent['tag']> = {
    message: 'message',
    proposal: 'proposal',
    counter_proposal: 'proposal',
    contract: 'contract',
    deliverable: 'deliverable',
    payment: 'payment',
    system: 'system',
  };

  return {
    id: event.id,
    collaborationId: 'chat-collab',
    timestamp: new Date(event.timestamp),
    senderId: event.sender,
    senderType: event.sender,
    tag: tagMap[event.type],
    content: event.content,
    metadata: event.metadata as TimelineEventMetadata | undefined,
    status: 'read',
  };
}

// ============================================================================
// Mock Data - Creator Perspective (Brands they work with)
// ============================================================================

/**
 * Every mock room carries an explicit `collaborationStatus` alongside its coarse `status`,
 * exactly as a live room does. Without it `dealAllowsProposalResponse` (CR-02) would refuse
 * every mock proposal, and demo mode would stop exercising the real gate. The pairs below
 * are the ones the shared mapper produces, so mock and live cannot drift apart either.
 */
const mockDealRooms: DealRoom[] = [
  {
    id: '1',
    brandName: 'StyleCo Fashion',
    brandLogo: '',
    brandInitials: 'SC',
    brandVerified: true,
    campaignName: 'Summer Fashion 2024',
    status: 'in_progress',
    collaborationStatus: 'IN_PROGRESS',
    dealAmount: 50000,
    lastMessage: 'Great work on the first reel! Ready for the next one?',
    lastMessageTime: '2 hours ago',
    unreadCount: 2,
    deliverablesDone: 1,
    deliverablesTotal: 3,
    escrowFunded: false,
  },
  {
    id: '2',
    brandName: 'TechGadgets Pro',
    brandLogo: '',
    brandInitials: 'TG',
    brandVerified: true,
    campaignName: 'Product Launch Q1',
    status: 'new',
    collaborationStatus: 'INVITED',
    dealAmount: 75000,
    lastMessage: 'We loved your tech reviews! Here is our proposal...',
    lastMessageTime: '5 hours ago',
    unreadCount: 1,
    deliverablesDone: 0,
    deliverablesTotal: 2,
    escrowFunded: false,
  },
  {
    id: '3',
    brandName: 'FitLife Nutrition',
    brandLogo: '',
    brandInitials: 'FL',
    brandVerified: true,
    campaignName: 'Wellness Campaign',
    status: 'negotiating',
    collaborationStatus: 'IN_NEGOTIATION',
    dealAmount: 35000,
    lastMessage: 'Can we discuss the timeline for deliverables?',
    lastMessageTime: '1 day ago',
    unreadCount: 0,
    deliverablesDone: 0,
    deliverablesTotal: 4,
    escrowFunded: false,
  },
  {
    id: '4',
    brandName: 'BeautyGlow',
    brandLogo: '',
    brandInitials: 'BG',
    brandVerified: true,
    campaignName: 'Skincare Series',
    status: 'contracted',
    collaborationStatus: 'CONTRACTED',
    dealAmount: 45000,
    lastMessage: 'Contract signed! Looking forward to working together.',
    lastMessageTime: '2 days ago',
    unreadCount: 0,
    deliverablesDone: 0,
    deliverablesTotal: 2,
    escrowFunded: false,
  },
  {
    id: '5',
    brandName: 'HomeDecor Plus',
    brandLogo: '',
    brandInitials: 'HD',
    brandVerified: true,
    campaignName: 'Interior Design Tips',
    status: 'review',
    collaborationStatus: 'REVIEW_PENDING',
    dealAmount: 28000,
    lastMessage: 'Submitted the final video for review.',
    lastMessageTime: '3 days ago',
    unreadCount: 0,
    deliverablesDone: 2,
    deliverablesTotal: 2,
    escrowFunded: false,
  },
];

// Timeline events for selected deal (creator perspective)
const mockTimelineEvents: ChatTimelineEvent[] = [
  {
    id: '1',
    type: 'message',
    sender: 'brand',
    timestamp: '2024-01-15 10:00',
    content: 'Hi! We loved your content style and would like to collaborate on our Summer Fashion Campaign.',
  },
  {
    id: '2',
    type: 'message',
    sender: 'creator',
    timestamp: '2024-01-15 10:30',
    content: 'Thank you so much! I am excited about this opportunity. Could you share more details about the campaign?',
  },
  {
    id: '3',
    type: 'proposal',
    sender: 'brand',
    timestamp: '2024-01-15 11:00',
    content: 'Here is our proposal for your review.',
    metadata: {
      proposalType: 'initial',
      status: 'countered',
      amount: 45000,
      deliverables: [
        { type: 'Instagram Reel', quantity: 2 },
        { type: 'Instagram Story', quantity: 1 },
      ],
      usageRights: '6 months',
      deadline: '2024-02-15',
      // Earnings breakdown for creator
      platformFee: 4500,
      gstOnFee: 810,
      tds: 4500,
      netEarnings: 35190,
    },
  },
  {
    id: '4',
    type: 'message',
    sender: 'creator',
    timestamp: '2024-01-15 14:00',
    content: 'Thank you for the proposal! I have reviewed the terms and would like to counter with a slightly higher rate given my engagement metrics.',
  },
  {
    id: '5',
    type: 'counter_proposal',
    sender: 'creator',
    timestamp: '2024-01-15 14:15',
    content: 'My counter proposal',
    metadata: {
      proposalType: 'counter',
      status: 'accepted',
      amount: 50000,
      deliverables: [
        { type: 'Instagram Reel', quantity: 2 },
        { type: 'Instagram Story', quantity: 1 },
      ],
      usageRights: '6 months',
      deadline: '2024-02-15',
      // Updated earnings breakdown
      platformFee: 5000,
      gstOnFee: 900,
      tds: 5000,
      netEarnings: 39100,
    },
  },
  {
    id: '6',
    type: 'message',
    sender: 'brand',
    timestamp: '2024-01-15 16:00',
    content: 'We accept your counter proposal! Let us proceed with the contract.',
  },
  {
    id: '7',
    type: 'contract',
    sender: 'system',
    timestamp: '2024-01-16 10:00',
    content: 'Contract generated',
    metadata: {
      contractId: 'CTR-2024-001',
      contractStatus: 'brand_signed',
      amount: 50000,
      brandSigned: true,
      creatorSigned: false,
      escrowStatus: 'funded',
      escrowAmount: 50000,
    },
  },
  {
    id: '8',
    type: 'payment',
    sender: 'system',
    timestamp: '2024-01-16 10:05',
    content: 'Escrow funded',
    metadata: {
      type: 'escrow_funded',
      amount: 50000,
    },
  },
  {
    id: '9',
    type: 'message',
    sender: 'brand',
    timestamp: '2024-01-18 09:00',
    content: 'Contract is signed and escrow is funded. You can start working on the deliverables now!',
  },
  {
    id: '10',
    type: 'deliverable',
    sender: 'creator',
    timestamp: '2024-01-20 14:00',
    content: 'Submitted Instagram Reel #1',
    metadata: {
      deliverableType: 'Instagram Reel',
      deliverableNumber: 1,
      status: 'approved',
      fileUrl: '/placeholder-video.mp4',
      caption: 'Summer vibes with @StyleCoFashion! Check out their amazing new collection.',
      revision: 1,
      maxRevisions: 2,
      paymentAmount: 25000,
    },
  },
  {
    id: '11',
    type: 'message',
    sender: 'brand',
    timestamp: '2024-01-20 16:00',
    content: 'Approved! Great work on the first reel! The energy and styling are perfect. Ready for the next one?',
  },
  {
    id: '12',
    type: 'deliverable',
    sender: 'creator',
    timestamp: '2024-01-22 11:00',
    content: 'Submitted Instagram Reel #2',
    metadata: {
      deliverableType: 'Instagram Reel',
      deliverableNumber: 2,
      status: 'pending_review',
      fileUrl: '/placeholder-video-2.mp4',
      caption: 'Styling tips for summer with @StyleCoFashion new arrivals!',
      revision: 1,
      maxRevisions: 2,
    },
  },
];

// ============================================================================
// Live-mode mappers — api.deals.list('creator', ...) / api.messages.list(...)
// map onto the DealRoom / ChatTimelineEvent shapes this page already renders.
// ============================================================================

/**
 * CR-02 — whether the DEAL is still in a state where an offer may be accepted or
 * countered. The proposal card's own `metadata.status === 'pending'` is not
 * enough on its own: it describes the message, not the collaboration, and a row
 * written before Vikram's `settleLatestProposal` fix (or one moved along by any
 * other path — brand-side accept, contract creation, cancellation) still says
 * "pending" long after the deal itself has left the negotiating stage. Gating on
 * message status alone is what let a CONTRACTED deal render Accept and 409.
 *
 * CR-34 — the status list itself now lives in `lib/deal-stage.ts` and is shared with
 * `brand-chat.tsx`, which used to keep its own copy. This stays as a thin, named
 * `DealRoom`-shaped wrapper because the call site reads better for it; the rule it
 * enforces is not defined here. **Do not reintroduce a local status list** — two
 * copies of one backend precondition is the shape of CR-05/CR-13/CR-24, and it is
 * what CR-34 was opened to remove.
 *
 * Note the deals LIST is deliberately not a third caller — per the CR-27 ruling it
 * offers actions on `new` only and does not need this predicate at all.
 *
 * CR-05 — this reads `collaborationStatus` and nothing else. It used to fall back to
 * the coarse `status` when the raw one was absent, which only worked because this file's
 * private mapper put TERMS_AGREED in 'contracted'. The shared mapper (correctly, per the
 * server) puts TERMS_AGREED in 'negotiating', so that fallback would now answer "yes, you
 * may still accept" for an already-accepted deal and re-open CR-02. Mock rooms carry an
 * explicit `collaborationStatus` instead, so demo mode exercises the identical gate.
 */
function dealAllowsProposalResponse(deal: DealRoom | null | undefined): boolean {
  return allowsProposalResponse(deal?.collaborationStatus);
}

/**
 * Might this incoming frame mean the COLLABORATION's status changed, and not just the
 * message list?
 *
 * The message registry only carries message-shaped events — no frame states the
 * collaboration's own status — while the CR-02 gate reads `collaborationStatus` off the
 * deal. So arrival of a frame that *implies* a transition has to trigger a deal refetch.
 *
 * The obvious predicate — "a settled proposal card arrived" — is not enough, and the gap is
 * not hypothetical. `DealService.settleLatestProposal` no-ops when the deal has no proposal
 * card at all, and an INVITED deal built by `Collaboration.invite(...)` has none:
 * `persistProposalMessage` only runs from `createProposal` and `doCounter`. So
 * invite-then-accept moves INVITED -> TERMS_AGREED, invite-then-reject moves it to
 * CANCELLED, and a first counter against an invite moves it to IN_NEGOTIATION — each with no
 * settled card published. Gating the refetch on one would leave Accept/Counter/Decline
 * rendering on a deal that can no longer take them: CR-02 again, through a different door.
 *
 * Every transition does, however, publish at least one `proposal` or `system` frame — accept
 * and reject always append a system message (`appendSystemMessage`), counter always publishes
 * the new proposal card. So that is the trigger. It costs at most one extra `GET /deals/:id`
 * per frame and stays inside the existing registry contract, rather than widening it to carry
 * collaboration status.
 */
function mayIndicateDealStatusChange(message: DealMessage): boolean {
  return message.kind === 'proposal' || message.kind === 'system';
}

const MESSAGE_KIND_TO_EVENT_TYPE: Record<MessageKind, ChatTimelineEvent['type']> = {
  text: 'message',
  system: 'system',
  proposal: 'proposal',
  contract: 'contract',
  deliverable: 'deliverable',
  payment: 'payment',
  shipment: 'system',
};

/** DealMessage (api.messages.list/send) -> local ChatTimelineEvent shape. */
function dealMessageToEvent(m: DealMessage): ChatTimelineEvent {
  return {
    id: m.id,
    type: MESSAGE_KIND_TO_EVENT_TYPE[m.kind],
    sender: m.senderType,
    timestamp: formatMessageTimestamp(m.createdAt),
    content: m.content,
    metadata: m.metadata,
  };
}

// ============================================================================
// Helper Functions
// ============================================================================

const getStatusBadge = (status: DealRoom['status']) => {
  const statusConfig = {
    new: { label: 'New Proposal', variant: 'outline' as const, className: 'border bg-stage-outreach text-stage-outreach-fg border-stage-outreach-border' },
    negotiating: { label: 'Negotiating', variant: 'outline' as const, className: 'border bg-stage-negotiating text-stage-negotiating-fg border-stage-negotiating-border' },
    contracted: { label: 'Contracted', variant: 'outline' as const, className: 'border bg-stage-contracted text-stage-contracted-fg border-stage-contracted-border' },
    in_progress: { label: 'In Progress', variant: 'outline' as const, className: 'border bg-stage-progress text-stage-progress-fg border-stage-progress-border' },
    review: { label: 'Under Review', variant: 'outline' as const, className: 'border bg-stage-review text-stage-review-fg border-stage-review-border' },
    completed: { label: 'Completed', variant: 'outline' as const, className: 'border bg-stage-approved text-stage-approved-fg border-stage-approved-border' },
    // CR-26 — CANCELLED/DISPUTED used to fall into 'completed' and render the green
    // "Completed" badge on a deal the creator was contesting. This is the second surface
    // that needed the 7th stage; the shared mapper's new 'disputed' return made the
    // typechecker point straight at it.
    disputed: { label: 'Disputed', variant: 'outline' as const, className: 'border bg-stage-disputed text-stage-disputed-fg border-stage-disputed-border' },
  };
  const config = statusConfig[status];
  return <Badge className={config.className}>{config.label}</Badge>;
};

// Single platform fee of 15% (feeBps 1500) — matches api.wallet.platformFee, the
// counter-proposal form, and the actual escrow payout (creator nets 85% of gross).
const PLATFORM_FEE_RATE = 0.15;
const calculateEarnings = (grossAmount: number) => {
  const platformFee = grossAmount * PLATFORM_FEE_RATE;
  const netEarnings = grossAmount - platformFee;
  return { platformFee, netEarnings };
};

/**
 * CR-03 — outcome of the last accept/decline attempt, pinned to the proposal card
 * that produced it. A toast alone is the wrong instrument here: it is transient,
 * capped at one on screen, and the creator is very likely looking at the button
 * they just pressed rather than the corner of the viewport. This state is never
 * auto-cleared on a timer — only a new attempt replaces it, and switching rooms
 * hides it (see `dealId`).
 */
interface ProposalActionFeedback {
  /**
   * Deal room the attempt happened in. The render is gated on this matching the
   * currently-selected deal, which scopes the banner to one room WITHOUT an
   * effect that clears state on `selectedDeal.id` — clearing in an effect costs a
   * second render pass and trips react-hooks/set-state-in-effect.
   */
  dealId: string;
  /** `event.id` of the proposal card the attempt originated from. */
  proposalId: string;
  tone: 'success' | 'error';
  message: string;
  /** True for the 409s that mean "stale card" — those get a Refresh affordance. */
  stale: boolean;
}

/**
 * Turns an accept/decline rejection into copy the creator can act on.
 *
 * A 409 is emphatically not "please try again" — the deal has genuinely moved on
 * and retrying will fail identically forever, so each server code from
 * DealService.doAccept/reject gets its own explanation. Anything else keeps the
 * server's own message when there is one (`ApiError.message`) and falls back to a
 * retry prompt only for genuinely transient failures.
 */
function describeProposalActionError(
  err: unknown,
  action: 'accept' | 'decline',
): { message: string; stale: boolean } {
  if (err instanceof ApiError && err.status === 409) {
    switch (err.code) {
      case 'DEAL_NOT_ACCEPTABLE':
        return {
          message:
            'This deal has already moved past the offer stage — it can no longer be accepted. Refresh to see where it stands now.',
          stale: true,
        };
      // Currently UNREACHABLE from this screen and kept deliberately: the Decline
      // button is gated on canAccept(), which is strictly narrower than the
      // backend's canReject(), so any deal that could produce this code has
      // already had its button hidden (see the gate in the timeline below).
      // Retained as defence in depth — the gate depends on `collaborationStatus`
      // being fresh, and a stale deals-list response is exactly the case where a
      // request slips through. Delete this only alongside the gate itself.
      case 'DEAL_NOT_REJECTABLE':
        return {
          message:
            'This deal is already closed, so it can no longer be declined. Refresh to see its current state.',
          stale: true,
        };
      case 'CANNOT_ACCEPT_OWN_OFFER':
        return {
          message:
            'You made the last offer, so the brand has to accept it. Send a new counter if you want to change the terms.',
          stale: false,
        };
      default:
        return { message: err.message, stale: true };
    }
  }
  if (err instanceof ApiError) {
    return { message: err.message, stale: false };
  }
  return {
    message:
      action === 'accept'
        ? 'Could not accept this proposal. Check your connection and try again.'
        : 'Could not decline this proposal. Check your connection and try again.',
    stale: false,
  };
}

// ============================================================================
// Main Component
// ============================================================================

export default function CreatorChatPage() {
  const liveApi = isApiLive();
  const { toast } = useToast();
  const [searchParams, setSearchParams] = useSearchParams();
  const dealId = searchParams.get('deal');
  const tabFromUrl = searchParams.get('tab');

  // Deal/conversation list — GET /deals?role=creator (api.deals.list). Mock mode
  // keeps the original hardcoded mockDealRooms so the demo still works.
  const [dealRooms, setDealRooms] = React.useState<DealRoom[]>(liveApi ? [] : mockDealRooms);
  const [dealsLoading, setDealsLoading] = React.useState(liveApi);
  const [dealsError, setDealsError] = React.useState<string | null>(null);
  const [selectedDealId, setSelectedDealId] = React.useState<string | null>(dealId);

  const loadDeals = React.useCallback(async () => {
    if (!liveApi) return;
    setDealsLoading(true);
    setDealsError(null);
    try {
      const remote = await api.deals.list('creator', 'all');
      setDealRooms(remote.map(mapDealToChatRoom));
    } catch (err) {
      console.error('Failed to load deal rooms', err);
      setDealsError('Failed to load your deals. Please try again.');
    } finally {
      setDealsLoading(false);
    }
  }, [liveApi]);

  React.useEffect(() => {
    loadDeals();
  }, [loadDeals]);

  /**
   * Per-deal monotonic tokens for {@link refreshDeal}. Same "latest request wins" contract
   * as `messagesRequestRef`, but keyed by deal id: two refreshes of DIFFERENT deals are
   * both legitimate and must both land, while a slow response for one deal must never
   * overwrite a newer response for that same deal. A single shared counter would drop the
   * former; no counter at all would let the latter happen.
   */
  const dealRefreshRefs = React.useRef(new Map<string, number>());

  /**
   * Re-read ONE deal's metadata — `GET /deals/:id` — and upsert it into the list.
   *
   * CR-09 needs the accept/decline/counter handlers to refresh the deal so the CR-02 gate
   * (`collaborationStatus`) reflects the transition the server just made. `loadDeals()` does
   * that, but it also flips `dealsLoading`, and the render guard below returns a full-page
   * spinner whenever that is true — so acting on a proposal blanked the entire room for the
   * duration of the round-trip. That is CR-21, and this closes it for every mutation path:
   * nothing here touches `dealsLoading` or `dealsError`, so the room stays on screen and
   * simply updates in place.
   *
   * `loadDeals()` survives for the two things it is actually for — first paint and the
   * error-state Retry — where a page-level spinner is the honest thing to show.
   */
  const refreshDeal = React.useCallback(
    async (id: string) => {
      if (!liveApi) return;
      const token = (dealRefreshRefs.current.get(id) ?? 0) + 1;
      dealRefreshRefs.current.set(id, token);
      /** Has a newer refresh of THIS deal started since we issued ours? */
      const isSupersededRefresh = (dealId: string) =>
        dealRefreshRefs.current.get(dealId) !== token;
      try {
        const fresh = await api.deals.get('creator', id);
        if (isSupersededRefresh(id)) return;
        if (!fresh) return;
        const room = mapDealToChatRoom(fresh);
        setDealRooms((prev) =>
          prev.some((d) => d.id === room.id)
            ? prev.map((d) => (d.id === room.id ? room : d))
            : [room, ...prev],
        );
      } catch (err) {
        // Log unconditionally — a request really did fail and that is worth diagnosing even
        // if its result is no longer wanted.
        console.error('Failed to refresh deal', id, err);
        // W2-L1 — the success path's staleness guard applies to the failure path too, and
        // used to be missing here. A slow refresh that fails AFTER a newer refresh of the
        // same deal already succeeded has nothing to report: what is on screen is current,
        // and "Couldn't refresh this deal" would flatly contradict it.
        if (isSupersededRefresh(id)) return;
        // Otherwise never silent: the visible consequence of a failed refresh is that the
        // Accept/Counter/Decline buttons keep rendering on a deal that has already moved on,
        // which is precisely the confusion CR-02 was about. Say so rather than leaving the
        // creator to discover it via a 409.
        toast({
          title: 'Couldn’t refresh this deal',
          description:
            err instanceof ApiError
              ? err.message
              : 'Your action went through, but this deal’s status may be out of date. Refresh to be sure.',
          variant: 'destructive',
        });
      }
    },
    [liveApi, toast],
  );

  const selectedDeal = React.useMemo(
    () => dealRooms.find((d) => d.id === selectedDealId) ?? dealRooms[0] ?? null,
    [dealRooms, selectedDealId],
  );

  // Message thread for the selected deal — GET/POST /deals/:id/messages (api.messages.*).
  const [liveMessages, setLiveMessages] = React.useState<DealMessage[]>([]);
  const [messagesLoading, setMessagesLoading] = React.useState(false);
  const [messagesError, setMessagesError] = React.useState<string | null>(null);
  /**
   * CR-31 — realtime transport state for the selected room's stream.
   *
   * Seeded optimistically to 'open' rather than a 'connecting' value, for two reasons: the
   * banner renders only when this is NOT 'open', so a truthful initial 'connecting' would
   * flash "Reconnecting…" on every deal switch over a perfectly healthy connection; and
   * `api.messages.stream` deliberately emits no status synchronously, so there is nothing to
   * seed it from inside the effect without a setState in the effect body. A genuinely failed
   * first connect corrects this within one backoff tick.
   */
  const [streamStatus, setStreamStatus] = React.useState<DealMessageStreamStatus>('open');

  /**
   * Monotonic token guarding "latest request wins". This replaces the per-effect
   * `cancelled` closure flag, which could only protect the effect that created
   * it — now that the Refresh affordance can also trigger a load, two in-flight
   * fetches can race, and a slow response for a previously-selected deal must
   * never overwrite a newer one.
   */
  const messagesRequestRef = React.useRef(0);

  /**
   * CR-20 — unmount guard.
   *
   * The `cancelled` closure flag that the old inline fetch used did two jobs:
   * ignore stale responses, and stop work after unmount. `messagesRequestRef`
   * replaced the first (better — it also orders concurrent loads), but silently
   * dropped the second. No leak is observable under React 18+, so this is
   * restoring a capability rather than fixing a live defect; it is cheap and it
   * stops the gap widening if a future `loadMessages` caller does more than
   * `setState`.
   */
  const isMountedRef = React.useRef(true);
  React.useEffect(() => {
    isMountedRef.current = true;
    return () => {
      isMountedRef.current = false;
    };
  }, []);

  /**
   * Reload the message thread for one deal. Extracted from the mount effect so
   * mutations can refresh the timeline, not just a deal switch — mirrors
   * brand-chat.tsx's `loadMessages`. CR-09 is now closed: accept, decline and counter all
   * call this alongside `refreshDeal`, so the timeline moves when the creator acts.
   */
  const loadMessages = React.useCallback(
    async (dealId: string) => {
      if (!liveApi) return;
      const token = ++messagesRequestRef.current;
      // CR-20 — a response is applied only if it is both the latest request
      // (token) and still wanted by a mounted component (isMountedRef).
      const isCurrent = () => isMountedRef.current && messagesRequestRef.current === token;
      setMessagesLoading(true);
      setMessagesError(null);
      try {
        const remote = await api.messages.list('creator', dealId);
        if (isCurrent()) setLiveMessages(remote);
      } catch (err) {
        // Unconditional, matching the refreshDeal convention (W2-L1): a failed
        // request is worth diagnosing whether or not its result is still wanted.
        console.error('Failed to load messages', err);
        if (isCurrent()) {
          setMessagesError('Failed to load messages for this deal.');
        }
      } finally {
        if (isCurrent()) setMessagesLoading(false);
      }
    },
    [liveApi],
  );

  /**
   * CR-09 — what every deal mutation (accept / decline / counter) does afterwards.
   *
   * The bug: these handlers called `loadDeals()` and nothing else. The deal row updated but
   * the message list did not, so the creator pressed Accept and the timeline sat there —
   * no settled proposal card, no "Creator accepted the proposal" system message
   * (DealService.appendSystemMessage writes one), no visible movement at all.
   *
   * Two independent reads because they are two independent resources, and each half fixes a
   * different half of the symptom:
   *   - `refreshDeal`  — the deal's `collaborationStatus`, which drives the CR-02
   *                      Accept/Counter/Decline gate. Narrow, no page spinner (CR-21).
   *   - `loadMessages` — the timeline itself, which is what CR-09 is actually about.
   *
   * This is the FALLBACK path, not the mechanism. Once CR-08 publishes accept/decline/counter
   * over SSE the stream will usually win the race and this becomes a no-op that confirms what
   * is already on screen — which is fine, and the reason the merge is an idempotent upsert
   * rather than an append. Neither call rejects (both surface their own failures), so
   * Promise.all here is a concurrency device, not an error path.
   */
  const afterDealMutation = React.useCallback(
    async (dealId: string) => {
      await Promise.all([refreshDeal(dealId), loadMessages(dealId)]);
    },
    [refreshDeal, loadMessages],
  );

  React.useEffect(() => {
    if (!liveApi || !selectedDeal) return;
    // F-0192 — the thread render has never been gated on messagesLoading here (events.map is
    // unconditional), so nothing else hides the PREVIOUS deal's messages during a deal-switch
    // round-trip: without this, switching deals renders deal A's thread under deal B's header
    // until the new deal's fetch resolves. Clearing synchronously here — not inside
    // loadMessages, which every same-deal resync caller (SSE onReconnect, mutation refresh)
    // also shares and must NOT clear — scopes the reset to a genuine deal switch only.
    setLiveMessages([]);
    void loadMessages(selectedDeal.id);
  }, [liveApi, selectedDeal?.id, loadMessages]);

  // Mark the thread read once opened (mirrors creator-deals.tsx openDeal flow).
  React.useEffect(() => {
    if (!liveApi || !selectedDeal || selectedDeal.unreadCount === 0) return;
    api.messages.markRead('creator', selectedDeal.id).catch((err) => {
      console.error('Failed to mark messages read', err);
    });
  }, [liveApi, selectedDeal?.id, selectedDeal?.unreadCount]);

  // Realtime messaging — mirrors brand-chat.tsx's stream effect against the same
  // GET /deals/:dealId/messages/stream endpoint (DealController.java:132). Without this the
  // creator only saw the brand's replies after a reload or a deal switch, so during a live
  // negotiation the brand appeared to have gone silent while its messages were already
  // persisted server-side.
  //
  // Merging is UPSERT BY ID — replace when the id is already present, append when it is not.
  // Neither half is optional:
  //
  //   - Append-only would duplicate. DealService publishes on send to every open emitter for
  //     the deal including the sender's own, and handleSendMessage below already appends what
  //     api.messages.send returns, so the creator would see own-messages twice.
  //   - Ignore-if-present (what this used to do) drops updates. Accept and counter do not only
  //     create messages, they MUTATE the proposal card: DealService.settleLatestProposal
  //     rewrites the ORIGINAL card's metadata.status to 'accepted'/'countered'/'rejected' and
  //     CR-08 republishes it under its original id. Skipping it because "I already have that
  //     id" would silently discard exactly the update that retires the Accept buttons — a
  //     straight reintroduction of CR-02.
  //
  // The whole merge is therefore idempotent, which is required regardless of CR-08: SSE
  // redelivers on reconnect, and a full refetch replaces the array wholesale. Both paths
  // converge on the same list.
  //
  // Frames are applied one at a time, in arrival order, with no buffering — deliberately.
  // CR-08 publishes the settled/superseded card FIRST and the system message (or the new
  // pending card, on a counter) second, so that the room never shows an "accepted"/"countered"
  // line above a card still rendering live buttons. Batching or reordering here would throw
  // that guarantee away. Each `setLiveMessages` below is a functional update, so React may
  // coalesce the renders but the updates still run in the order the frames arrived.
  //
  // Keyed on selectedDeal?.id (not the object) so a deals-list refresh returning a new object
  // for the same deal doesn't tear down and reopen the connection. Cleanup fires on both id
  // change and unmount, so a previous deal's stream can never write into the newly selected one.
  React.useEffect(() => {
    if (!liveApi || !selectedDeal) return;
    const dealId = selectedDeal.id;

    const handle = api.messages.stream('creator', dealId, {
      onMessage: (incoming) => {
        setLiveMessages((prev) => {
          const idx = prev.findIndex((m) => m.id === incoming.id);
          if (idx === -1) return [...prev, incoming];
          const next = prev.slice();
          next[idx] = incoming;
          return next;
        });
        // No frame carries the collaboration's own status, so re-read the deal whenever one
        // arrives that could imply a transition — see `mayIndicateDealStatusChange` for why
        // "a settled card arrived" is the wrong, too-narrow trigger. Fired per frame and NOT
        // coalesced: a counter publishes two frames and therefore two GETs, which the
        // per-deal token in `refreshDeal` resolves last-write-wins. Narrow by construction —
        // one `GET /deals/:id`, and nothing here touches `dealsLoading`, so a frame never
        // blanks the room (CR-21).
        if (mayIndicateDealStatusChange(incoming)) {
          void refreshDeal(dealId);
        }
        // No manual scroll here — the `events` effect below already scrolls whenever the
        // derived timeline changes, and `events` is computed from `liveMessages`.
      },
      onError: (err) => {
        // Still non-fatal and still not a toast — rendering and sending never depend on the
        // stream. But CR-31: this now fires per failed ATTEMPT and the transport retries, so
        // it is a diagnostic line, not the room's state. `streamStatus` is the state.
        if (import.meta.env.DEV) console.warn('[creator-chat] deal message stream error for deal', dealId, err);
      },
      onStatusChange: setStreamStatus,
      onReconnect: () => {
        // CR-31 — the gap is unrecoverable from the transport (no Last-Event-ID replay), so
        // anything published while we were down has to be re-read. Both halves are required
        // for the same reason the SSE handler above does both: `loadMessages` restores the
        // thread, and `refreshDeal` restores `collaborationStatus`, which gates
        // Accept/Counter/Decline. Refetching only messages would leave the CR-02 buttons
        // rendering on a deal that moved on while the stream was down.
        void loadMessages(dealId);
        void refreshDeal(dealId);
      },
    });

    return () => {
      handle.close();
    };
  }, [liveApi, selectedDeal?.id, refreshDeal, loadMessages]);

  // CR-93/F-0110 — a backgrounded tab is the one dead-connection shape CR-31's reconnect
  // logic does not reliably catch: browsers throttle/suspend timers (and can stall an
  // in-flight `fetch` read) for a hidden tab, but the underlying TCP connection can look
  // perfectly fine to the transport, so no error/close ever fires to trigger `onReconnect`.
  // The counterparty's accept/reject/message then simply never appears until something else
  // (a manual reload, a deal switch) forces a refetch. Foregrounding the tab is an
  // unconditional resync, independent of what `streamStatus` currently believes — cheap
  // (two GETs) and correct even if the stream was secretly fine the whole time.
  React.useEffect(() => {
    if (!liveApi || !selectedDeal) return;
    const dealId = selectedDeal.id;
    const onVisible = () => {
      if (document.visibilityState !== 'visible') return;
      void loadMessages(dealId);
      void refreshDeal(dealId);
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => document.removeEventListener('visibilitychange', onVisible);
  }, [liveApi, selectedDeal?.id, refreshDeal, loadMessages]);

  const [message, setMessage] = React.useState('');
  const [messageRefreshKey, setMessageRefreshKey] = React.useState(0);
  // CR-04 — the Radix ScrollArea viewport for the message thread. The
  // auto-scroll effect drives this element's scrollTop directly instead of
  // calling scrollIntoView, which would also scroll the overflow-hidden
  // wrapper above it and clip the top of the room permanently out of reach.
  const messagesViewportRef = React.useRef<HTMLDivElement>(null);
  const [searchQuery, setSearchQuery] = React.useState('');
  const [openPanel, setOpenPanel] = React.useState<CreatorToolsPanel>(null);
  const [contractStatusByDeal, setContractStatusByDeal] =
    React.useState<Record<string, DealContractStatus>>(() => getAllContractStatuses());
  const [showContractPanel, setShowContractPanel] = React.useState(false);

  // FE-2/FE-3: real contract for the selected deal — GET /contracts/:id,
  // fetched once `selectedDeal.contractId` is real (never a fabricated
  // CTR-2024-<id>). Live mode only.
  const [liveContract, setLiveContract] = React.useState<ContractApiRecord | null>(null);
  const [liveContractLoading, setLiveContractLoading] = React.useState(false);
  const [liveContractError, setLiveContractError] = React.useState<string | null>(null);

  const syncUrl = React.useCallback(
    (id: string, panel: CreatorToolsPanel) => {
      const next = new URLSearchParams(searchParams);
      next.set('deal', id);
      if (!panel) next.delete('tab');
      else next.set('tab', panel);
      setSearchParams(next, { replace: true });
    },
    [searchParams, setSearchParams],
  );

  const selectDeal = (deal: DealRoom) => {
    setSelectedDealId(deal.id);
    setOpenPanel(null);
    syncUrl(deal.id, null);
  };

  const openTool = (panel: CreatorToolsPanel) => {
    setOpenPanel(panel);
    if (selectedDeal) syncUrl(selectedDeal.id, panel);
  };

  // FE-2: fetch the full contract once a real contractId exists on the
  // selected deal. Coarse status (deal.contractStatus) already renders an
  // honest state before this resolves.
  const fetchLiveContract = React.useCallback(async () => {
    if (!liveApi || !selectedDeal?.contractId) {
      setLiveContract(null);
      return;
    }
    setLiveContractLoading(true);
    setLiveContractError(null);
    try {
      const record = await api.contracts.get('creator', selectedDeal.contractId);
      setLiveContract(record);
    } catch {
      setLiveContract(null);
      setLiveContractError('Could not load contract details.');
    } finally {
      setLiveContractLoading(false);
    }
  }, [liveApi, selectedDeal?.contractId]);

  React.useEffect(() => {
    void fetchLiveContract();
  }, [fetchLiveContract]);

  // C19: real deliverable rows feed the submission dialog (was a hardcoded fake array).
  // The chat page can only LIST them — rows are created server-side when the contract is
  // generated, so an empty list here means "no contract yet, nothing to submit".
  // Real deliverable rows for the selected collaboration — GET /creator/deliverables?collaboration_id=.
  // Empty until a contract materializes the rows (ContractService.materializeDeliverables).
  const [liveDeliverables, setLiveDeliverables] = React.useState<CreatorDeliverableListItem[]>([]);
  const loadDeliverables = React.useCallback(async () => {
    if (!liveApi || !selectedDeal) {
      setLiveDeliverables([]);
      return;
    }
    try {
      setLiveDeliverables(await api.creatorDeliverables.listForDeal(selectedDeal.id));
    } catch (err) {
      console.error('Failed to load deliverables', err);
      setLiveDeliverables([]);
    }
  }, [liveApi, selectedDeal?.id]);

  React.useEffect(() => {
    void loadDeliverables();
  }, [loadDeliverables]);

  React.useEffect(() => {
    if (dealId) {
      setSelectedDealId(dealId);
    }
  }, [dealId]);

  React.useEffect(() => {
    if (tabFromUrl && (CREATOR_VALID_PANELS as string[]).includes(tabFromUrl)) {
      setOpenPanel(tabFromUrl as CreatorToolsPanel);
    }
  }, [tabFromUrl]);
  const [selectedContractEvent, setSelectedContractEvent] = React.useState<ChatTimelineEvent | null>(null);
  const [showCounterDialog, setShowCounterDialog] = React.useState(false);
  const [showCounterForm, setShowCounterForm] = React.useState(false);
  const [isSubmittingCounter, setIsSubmittingCounter] = React.useState(false);
  const [showDeliverableDialog, setShowDeliverableDialog] = React.useState(false);
  const [counterAmount, setCounterAmount] = React.useState('');
  const [counterMessage, setCounterMessage] = React.useState('');
  const [showRevisionHandler, setShowRevisionHandler] = React.useState(false);
  const [selectedDeliverableForRevision] = React.useState<string | null>(null);
  const [isSubmittingDeliverable, setIsSubmittingDeliverable] = React.useState(false);
  // CR-53 — if upload succeeds but the submit step fails, remember the exact
  // (deliverableId, file) pair that already made it to the server so a retry can
  // skip re-uploading and go straight to submit. Ref, not state: it must survive
  // across retries without triggering a re-render of its own.
  const uploadedDeliverableRef = React.useRef<{ deliverableId: string; file: File } | null>(null);
  const [isAcceptingProposal, setIsAcceptingProposal] = React.useState(false);
  const [isDecliningProposal, setIsDecliningProposal] = React.useState(false);
  // CR-03 — persistent per-card outcome of the last accept/decline. See
  // ProposalActionFeedback for why a toast on its own was not enough.
  const [proposalFeedback, setProposalFeedback] =
    React.useState<ProposalActionFeedback | null>(null);

  // Phase 5: Shipping flow state
  const [showShippingAddressForm, setShowShippingAddressForm] = React.useState(false);
  const [showReceiptConfirmation, setShowReceiptConfirmation] = React.useState(false);
  const [shippingAddress, setShippingAddress] = React.useState<ShippingAddressData | null>(null);
  const [shipmentStatus, setShipmentStatus] = React.useState<ShipmentStatus>('created');
  const [isSavingAddress, setIsSavingAddress] = React.useState(false);
  const [isConfirmingReceipt, setIsConfirmingReceipt] = React.useState(false);

  // Real shipment record — GET /deals/:id/shipment (Priya's design 2026-07-24,
  // wiki/decisions/shipment-backend-design-2026-07-24.md). `shippingAddress` and
  // `shipmentStatus` above are synced FROM this once liveApi is on (effect below);
  // in mock/demo mode they stay purely local, set by the handlers directly.
  const [liveShipment, setLiveShipment] = React.useState<ShipmentApiRecord | null>(null);
  // Whether this backend actually serves the shipment endpoints. A 404 from
  // GET /deals/:id/shipment means the shipment feature isn't deployed here — in
  // that case we degrade gracefully: hide all shipment UI and never gate
  // deliverable submission on a shipment that can never be created.
  const [shipmentSupported, setShipmentSupported] = React.useState(true);

  const fetchLiveShipment = React.useCallback(async () => {
    if (!liveApi || !selectedDeal) {
      setLiveShipment(null);
      return;
    }
    try {
      const record = await api.shipments.get('creator', selectedDeal.id);
      setLiveShipment(record);
      setShipmentSupported(true);
    } catch (err) {
      // 404 = shipment endpoints not deployed on this backend → hide the shipment
      // step entirely rather than blocking the creator behind a gate they can't clear.
      // Other errors (transient/5xx) leave the feature enabled but with no record.
      if (err instanceof ApiError && err.status === 404) {
        setShipmentSupported(false);
      }
      console.error('Failed to load shipment status', err);
      setLiveShipment(null);
    }
  }, [liveApi, selectedDeal?.id]);

  React.useEffect(() => {
    void fetchLiveShipment();
  }, [fetchLiveShipment]);

  // Reflect the real record into the address/status state the render tree already
  // reads. AWAITING_ADDRESS (no row yet) keeps shippingAddress null so the "Add
  // Shipping Address" prompt still shows.
  React.useEffect(() => {
    if (!liveApi || !liveShipment) return;
    if (liveShipment.status === 'AWAITING_ADDRESS') {
      setShippingAddress(null);
    } else {
      setShippingAddress({
        fullName: liveShipment.recipientName ?? '',
        phone: liveShipment.phone ?? '',
        addressLine1: liveShipment.addressLine1 ?? '',
        addressLine2: liveShipment.addressLine2 ?? '',
        city: liveShipment.city ?? '',
        state: liveShipment.state ?? '',
        pincode: liveShipment.pincode ?? '',
      });
    }
    setShipmentStatus(mapShipmentApiStatusToUiStatus(liveShipment.status));
  }, [liveApi, liveShipment]);

  // Whether the brand has actually dispatched the product (vs. only an address being
  // on file) — derived from the real status rather than tracked as separate state.
  const hasShipment = liveApi
    ? liveShipment?.status === 'SHIPPED' || liveShipment?.status === 'RECEIVED' || liveShipment?.status === 'DAMAGED'
    : true; // mock/demo mode: keep the prior "brand always already shipped" assumption so the click-through demo still works

  // Display fields ShipmentCard needs beyond status/address. In live mode `items` is derived
  // from the real `productName` (flattened to qty 1), and `estimatedDelivery` is now the real
  // brand-supplied ETA (creator-shipment-eta-0804) — null until the brand marks it shipped with
  // a date. Courier/tracking/notes come from the real record once liveApi is on.
  const shipmentDisplay = React.useMemo(() => {
    if (liveApi) {
      return {
        items: [{ name: liveShipment?.productName || 'Product', quantity: 1 }],
        courier: liveShipment?.carrier || 'Courier pending',
        trackingNumber: liveShipment?.trackingNumber || 'Pending',
        trackingUrl: liveShipment?.trackingUrl || undefined,
        estimatedDelivery: liveShipment?.estimatedDelivery ?? null,
        notes: liveShipment?.conditionNote || undefined,
      };
    }
    return {
      items: [{ name: 'Summer Dress (Floral, Size M)', quantity: 1 }, { name: 'Brand Lookbook', quantity: 1 }],
      courier: 'Delhivery',
      trackingNumber: 'DLV789456123',
      trackingUrl: 'https://www.delhivery.com/track',
      estimatedDelivery: '2026-08-10',
      notes: 'Please unbox on camera if you can. Care: dry-clean only.',
    };
  }, [liveApi, liveShipment]);

  // Wired to POST /deals/:id/shipping-address (creator-only; backend rejects with
  // 409 SHIPMENT_ALREADY_SHIPPED once the brand has shipped). Mock mode keeps the
  // old client-only simulation so the demo flow still works offline.
  const handleSubmitShippingAddress = async (data: ShippingAddressData) => {
    if (!selectedDeal) return;
    setIsSavingAddress(true);
    try {
      if (liveApi) {
        // ShippingAddressData carries a `landmark` field the backend DTO doesn't
        // model (Priya's doc has no landmark column) — fold it into addressLine2
        // rather than drop it.
        const addressLine2 =
          [data.addressLine2, data.landmark ? `Landmark: ${data.landmark}` : null]
            .filter(Boolean)
            .join(' · ') || undefined;
        const record = await api.shipments.submitAddress(selectedDeal.id, {
          recipientName: data.fullName,
          phone: data.phone,
          addressLine1: data.addressLine1,
          addressLine2,
          city: data.city,
          state: data.state,
          pincode: data.pincode,
        });
        setLiveShipment(record);
      } else {
        await new Promise((r) => setTimeout(r, 600));
      }
      setShippingAddress(data);
      setShowShippingAddressForm(false);
    } catch (err) {
      console.error('Failed to submit shipping address', err);
      toast({
        title: 'Could not save shipping address',
        description: err instanceof ApiError ? err.message : 'Please try again.',
        variant: 'destructive',
      });
    } finally {
      setIsSavingAddress(false);
    }
  };

  const handleMarkReceived = () => {
    setShowReceiptConfirmation(true);
  };

  // Wired to POST /deals/:id/shipment/confirm-receipt (creator-only; backend rejects
  // with 409 SHIPMENT_NOT_SHIPPED unless currently SHIPPED). Mock mode keeps the old
  // client-only simulation so the demo flow still works offline.
  const handleConfirmReceipt = async (data: ReceiptData) => {
    if (!selectedDeal) return;
    setIsConfirmingReceipt(true);
    try {
      if (liveApi) {
        // Backend models exactly two conditions (GOOD/DAMAGED, doc §4); this dialog
        // additionally offers "wrong_item" with no server equivalent — fold it into
        // DAMAGED and preserve the distinction in the note rather than dropping it.
        const condition: ShipmentCondition = data.condition === 'good' ? 'GOOD' : 'DAMAGED';
        const note =
          data.condition === 'wrong_item' ? `Wrong item received: ${data.notes}` : data.notes || undefined;
        const record = await api.shipments.confirmReceipt(selectedDeal.id, { condition, note });
        setLiveShipment(record);
        setShipmentStatus(mapShipmentApiStatusToUiStatus(record.status));
      } else {
        await new Promise((r) => setTimeout(r, 600));
        setShipmentStatus(data.condition === 'good' ? 'received' : 'damaged');
      }
      setShowReceiptConfirmation(false);
    } catch (err) {
      console.error('Failed to confirm receipt', err);
      toast({
        title: 'Could not confirm receipt',
        description: err instanceof ApiError ? err.message : 'Please try again.',
        variant: 'destructive',
      });
    } finally {
      setIsConfirmingReceipt(false);
    }
  };

  const handleSendMessage = async () => {
    const text = message.trim();
    if (!text || !selectedDeal) return;
    setMessage('');
    if (liveApi) {
      try {
        const sent = await api.messages.send('creator', selectedDeal.id, text);
        setLiveMessages((prev) => [...prev, sent]);
      } catch (err) {
        console.error('Failed to send message', err);
        setMessagesError('Failed to send your message. Please try again.');
      }
    } else {
      addPersistedMessage(selectedDeal.id, text, 'creator');
      setMessageRefreshKey((k) => k + 1);
    }
  };

  const handleAcceptProposal = async (proposalId: string) => {
    if (!selectedDeal) return;
    setProposalFeedback(null);
    setIsAcceptingProposal(true);
    try {
      if (liveApi) {
        // POST /deals/:id/accept acts on the deal's current offer, not the
        // individual message — proposalId (event.id) is only used for the
        // click origin, the deal itself carries the state the backend needs.
        await api.deals.accept(selectedDeal.id, 'creator');
        await afterDealMutation(selectedDeal.id);
      } else {
        await new Promise((resolve) => setTimeout(resolve, 800));
      }
      // CR-03 — success previously produced no feedback whatsoever: the buttons
      // just vanished, which on a slow deals refresh is indistinguishable from a
      // dead button. Confirm explicitly, on the card and in a toast.
      setProposalFeedback({
        dealId: selectedDeal.id,
        proposalId,
        tone: 'success',
        message: 'Proposal accepted. The brand can now send the contract for signing.',
        stale: false,
      });
      toast({
        title: 'Proposal accepted',
        description: 'Terms are agreed — the brand will send the contract next.',
      });
    } catch (err) {
      console.error('Failed to accept proposal', err);
      const { message, stale } = describeProposalActionError(err, 'accept');
      setProposalFeedback({ dealId: selectedDeal.id, proposalId, tone: 'error', message, stale });
      toast({
        title: 'Could not accept proposal',
        description: message,
        variant: 'destructive',
      });
    } finally {
      setIsAcceptingProposal(false);
    }
  };

  const handleDeclineProposal = async (proposalId: string) => {
    if (!selectedDeal) return;
    setProposalFeedback(null);
    setIsDecliningProposal(true);
    try {
      if (liveApi) {
        // POST /deals/:id/reject — same deal-level scoping as accept above.
        await api.deals.reject(selectedDeal.id, undefined, 'creator');
        await afterDealMutation(selectedDeal.id);
      } else {
        await new Promise((resolve) => setTimeout(resolve, 800));
      }
      setProposalFeedback({
        dealId: selectedDeal.id,
        proposalId,
        tone: 'success',
        message: 'Proposal declined. The brand has been notified.',
        stale: false,
      });
      toast({
        title: 'Proposal declined',
        description: 'The brand has been notified that you are not taking this deal.',
      });
    } catch (err) {
      console.error('Failed to decline proposal', err);
      const { message, stale } = describeProposalActionError(err, 'decline');
      setProposalFeedback({ dealId: selectedDeal.id, proposalId, tone: 'error', message, stale });
      toast({
        title: 'Could not decline proposal',
        description: message,
        variant: 'destructive',
      });
    } finally {
      setIsDecliningProposal(false);
    }
  };

  const handleSendCounter = () => {
    setShowCounterDialog(false);
    setCounterAmount('');
    setCounterMessage('');
  };

  const handleSubmitCounterForm = async (data: CounterProposalFormData) => {
    if (!selectedDeal) return;
    setIsSubmittingCounter(true);
    try {
      if (liveApi) {
        // `deadline` is a real CounterRequest field since the DTO was aligned with
        // CreateDealRequest (2026-07-26), so it no longer rides in the message prose.
        //
        // `terms` deliberately stays in the message: the form asks "Any Changes to Terms?" as
        // free text, so it is NOT the usage-rights term. Mapping it onto `usageRights` would
        // overwrite the deal's actual rights with a sentence like "can we do 3 reels instead".
        const message = [data.message, data.terms && `Terms: ${data.terms}`]
          .filter(Boolean)
          .join('\n\n');
        await api.deals.counter(
          selectedDeal.id,
          {
            amount: data.proposedAmount,
            message: message || undefined,
            deadline: data.deadline || undefined,
          },
          'creator',
          // Fresh key per submit so a same-amount re-counter is a real event, not a no-op (Kabir).
          `${selectedDeal.id}-counter-${Date.now()}`,
        );
        await afterDealMutation(selectedDeal.id);
      } else {
        await new Promise((resolve) => setTimeout(resolve, 1500));
      }
      setShowCounterForm(false);
    } catch (err) {
      console.error('Failed to submit counter offer', err);
      toast({
        title: 'Could not send counter offer',
        description: err instanceof ApiError ? err.message : 'Please try again.',
        variant: 'destructive',
      });
    } finally {
      setIsSubmittingCounter(false);
    }
  };

  const handleSubmitDeliverableForm = async (data: DeliverableSubmissionData) => {
    setIsSubmittingDeliverable(true);
    try {
      if (liveApi) {
        // Two steps: upload the file to the deliverable-specific multipart route (part name
        // `files`), THEN submit. Submitting with no uploaded version returns 400 NO_CONTENT.
        //
        // CR-53: if a prior attempt for this exact (deliverableId, file) already uploaded
        // successfully and only submit failed, skip the upload again on retry — re-upload
        // would waste the round trip and can create a duplicate version server-side. The
        // dialog keeps the same File object in state across a failed attempt (it does not
        // reset on error), so a reference-equal match here reliably means "already uploaded".
        const alreadyUploaded =
          uploadedDeliverableRef.current?.deliverableId === data.deliverableId &&
          uploadedDeliverableRef.current?.file === data.file;

        if (!alreadyUploaded) {
          await api.creatorDeliverables.upload(data.deliverableId, [data.file], { caption: data.caption });
          uploadedDeliverableRef.current = { deliverableId: data.deliverableId, file: data.file };
        }

        try {
          await api.deliverables.submit(data.deliverableId, { finalCaption: data.caption });
        } catch (submitErr) {
          // Tag the error so DeliverableSubmission can tell "upload also failed" apart from
          // "upload is done, only submit needs a retry" and show the right recovery message.
          if (submitErr && typeof submitErr === 'object') {
            (submitErr as { uploaded?: boolean }).uploaded = true;
          }
          throw submitErr;
        }

        uploadedDeliverableRef.current = null;
        await loadDeliverables();
      } else {
        await new Promise((resolve) => setTimeout(resolve, 1500));
      }
      // Note: do NOT close the dialog or catch here — DeliverableSubmission awaits this
      // handler, closes itself on success, and shows its own destructive toast on failure.
    } finally {
      setIsSubmittingDeliverable(false);
    }
  };

  const handleStartRevision = async (_revisionNotes: string) => {
    // No dedicated "start revision" endpoint exists (or is needed) — a revision
    // is just a re-submission of the same deliverable row via
    // creatorDeliverables.upload + deliverables.submit, which
    // handleSubmitDeliverableForm already wires to the real API. This handler
    // stays a pure local UI transition: close the feedback dialog and open the
    // real submission form so the creator can upload the revised file.
    // revisionNotes isn't sent anywhere today — there's no field for it in
    // DeliverableSubmissionData/the submit DTO, so it's dropped here.
    setShowRevisionHandler(false);
    setShowDeliverableDialog(true);
  };

  // FE-3: after a sign call in live mode, re-fetch the real contract instead
  // of writing an optimistic status into the demo store — the honest state
  // must come from `GET /contracts/:id`'s real brandSignedAt/creatorSignedAt,
  // not a client-assumed transition.
  const updateContractStatus = React.useCallback(
    (dealId: string, status: DealContractStatus) => {
      if (liveApi) {
        void fetchLiveContract();
        return;
      }
      setContractStatus(dealId, status);
      setContractStatusByDeal((prev) => ({ ...prev, [dealId]: status }));
    },
    [liveApi, fetchLiveContract],
  );

  const resolveDealContractStatus = (deal: DealRoom): DealContractStatus | undefined =>
    contractStatusByDeal[deal.id] ??
    getContractStatus(deal.id, deal.status);

  const enrichContractEvent = React.useCallback(
    (event: ChatTimelineEvent, deal: DealRoom): ChatTimelineEvent => {
      if (event.type !== 'contract') return event;

      if (liveApi) {
        // Live mode: never fabricate a contract id/status for a chat card —
        // use only the real fields the backend already returns on the deal
        // (deal.contractId/contractStatus/escrowFunded, DealDtos.java:38-40).
        // Architecture doc §FE-2/FE-3: no CTR-2024-<id> synthesis.
        const coarseStatus = mapDealApiContractStatus(deal.contractStatus, deal.escrowFunded);
        return {
          ...event,
          metadata: {
            ...event.metadata,
            contractId: deal.contractId ?? event.metadata?.contractId,
            contractStatus: coarseStatus ?? event.metadata?.contractStatus,
            brandName: deal.brandName,
            campaignName: deal.campaignName,
            amount: deal.dealAmount,
            brandSigned: coarseStatus
              ? ['brand_signed', 'creator_signed', 'active'].includes(coarseStatus)
              : Boolean(event.metadata?.brandSigned),
            creatorSigned: coarseStatus
              ? ['creator_signed', 'active'].includes(coarseStatus)
              : Boolean(event.metadata?.creatorSigned),
            escrowStatus: deal.escrowFunded ? 'funded' : event.metadata?.escrowStatus,
          },
        };
      }

      const status = resolveDealContractStatus(deal);
      return {
        ...event,
        metadata: {
          ...event.metadata,
          contractId: resolveContractId(deal.id),
          contractStatus: status ?? event.metadata?.contractStatus ?? 'generated',
          brandName: deal.brandName,
          campaignName: deal.campaignName,
          amount: deal.dealAmount,
          brandSigned: status
            ? ['brand_signed', 'creator_signed', 'active'].includes(status)
            : Boolean(event.metadata?.brandSigned),
          creatorSigned: status
            ? ['creator_signed', 'active'].includes(status)
            : Boolean(event.metadata?.creatorSigned),
          escrowStatus: status === 'active' ? 'funded' : event.metadata?.escrowStatus,
        },
      };
    },
    [contractStatusByDeal, liveApi],
  );

  const handleViewContract = (event: ChatTimelineEvent) => {
    setSelectedContractEvent(event);
    setShowContractPanel(true);
  };

  const handleSignContract = (event: ChatTimelineEvent) => {
    setSelectedContractEvent(event);
    setShowContractPanel(true);
  };
  
  const filteredDeals = dealRooms.filter(deal =>
    deal.brandName.toLowerCase().includes(searchQuery.toLowerCase()) ||
    deal.campaignName.toLowerCase().includes(searchQuery.toLowerCase())
  );

  /**
   * CR-04 — memoized on the deal id.
   *
   * This was a bare expression, so it produced a new array identity on every
   * render. That array is a dependency of the `events` useMemo below, which is
   * in turn a dependency of the auto-scroll effect — so both re-ran on EVERY
   * render, including every keystroke in the message composer, and the effect
   * re-scrolled the thread each time.
   *
   * `mockTimelineEvents.slice()` is only reachable in demo mode, but the
   * identity churn it caused was not: the `events` memo and the scroll effect
   * are shared by both modes.
   */
  // Deliberately the RESOLVED deal's id, not the `selectedDealId` state above:
  // that one holds whatever the URL asked for, which may not correspond to a
  // deal that actually loaded. `undefined` here correctly yields no events.
  const resolvedDealId = selectedDeal?.id;
  const baseEventsForDeal = React.useMemo(
    () =>
      !resolvedDealId
        ? []
        : resolvedDealId === '1'
          ? mockTimelineEvents
          : mockTimelineEvents.slice(0, 3),
    [resolvedDealId],
  );

  const events = React.useMemo(() => {
    if (!selectedDeal) return [];

    if (liveApi) {
      return liveMessages
        .map(dealMessageToEvent)
        .sort((a, b) => {
          const ta = new Date(a.timestamp.replace(' ', 'T')).getTime();
          const tb = new Date(b.timestamp.replace(' ', 'T')).getTime();
          return ta - tb;
        })
        .map((event) => enrichContractEvent(event, selectedDeal));
    }

    const persisted: ChatTimelineEvent[] = getPersistedMessages(selectedDeal.id).map((m) => ({
      id: m.id,
      type: 'message' as const,
      sender: m.sender,
      timestamp: formatMessageTimestamp(m.timestamp),
      content: m.content,
    }));

    const merged = [...baseEventsForDeal, ...persisted];
    const seen = new Set<string>();
    const unique = merged.filter((e) => {
      if (seen.has(e.id)) return false;
      seen.add(e.id);
      return true;
    });

    const sorted = unique.sort((a, b) => {
      const ta = new Date(a.timestamp.replace(' ', 'T')).getTime();
      const tb = new Date(b.timestamp.replace(' ', 'T')).getTime();
      return ta - tb;
    });

    return sorted.map((event) => enrichContractEvent(event, selectedDeal));
  }, [liveApi, liveMessages, baseEventsForDeal, selectedDeal, messageRefreshKey, enrichContractEvent]);

  /**
   * CR-04 — pin the message thread to the bottom by scrolling exactly one
   * element: the Radix viewport.
   *
   * This used to call `messagesEndRef.current.scrollIntoView()`. That scrolls
   * every scrollable ancestor of the target, and the deal room's flex wrapper
   * is `overflow-hidden` — it has no scrollbar and ignores the wheel, so the
   * ~106px it got scrolled by were permanently unreachable by the user
   * (measured live: scrollHeight 616, clientHeight 510, scrollTop 104.8, with
   * the top of the room clipped off). Assigning `scrollTop` on the viewport
   * cannot move an ancestor.
   */
  React.useEffect(() => {
    const viewport = messagesViewportRef.current;
    if (!viewport) return;
    // `scrollTo` is feature-detected, with a plain `scrollTop` assignment as the fallback —
    // which is what CR-04 prescribed in the first place; `scrollTo` is only preferred because
    // it can animate. jsdom implements neither smooth behaviour nor `Element.scrollTo`, so the
    // unguarded call threw inside this effect and took the whole page down under test. Caught
    // by `creator-chat-refresh.test.tsx` the first time this page was ever rendered in a test.
    // Both branches scroll exactly one element, which is the property CR-04 is actually about.
    if (typeof viewport.scrollTo === 'function') {
      viewport.scrollTo({ top: viewport.scrollHeight, behavior: 'smooth' });
    } else {
      viewport.scrollTop = viewport.scrollHeight;
    }
  }, [events, openPanel]);

  // --- Guards (after all hooks, before dereferencing selectedDeal below) ---
  if (dealsLoading) {
    return (
      <CreatorLayout>
        <div className="flex h-[calc(100vh-var(--app-header-h))] items-center justify-center bg-background">
          <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
        </div>
      </CreatorLayout>
    );
  }
  if (dealsError) {
    return (
      <CreatorLayout>
        <div className="flex h-[calc(100vh-var(--app-header-h))] flex-col items-center justify-center gap-3 bg-background text-center">
          <AlertCircle className="h-8 w-8 text-muted-foreground" />
          <p className="text-sm text-muted-foreground max-w-xs">{dealsError}</p>
          <Button variant="outline" size="sm" onClick={() => loadDeals()}>
            Retry
          </Button>
        </div>
      </CreatorLayout>
    );
  }
  if (!selectedDeal) {
    return (
      <CreatorLayout>
        <div className="flex h-[calc(100vh-var(--app-header-h))] flex-col items-center justify-center gap-2 bg-background text-center">
          <MessageCircle className="h-10 w-10 text-muted-foreground/50" />
          <p className="font-medium">No deals yet</p>
          <p className="text-sm text-muted-foreground max-w-xs">
            Brands you're talking to will show up here as deal rooms.
          </p>
        </div>
      </CreatorLayout>
    );
  }

  // FE-2/FE-3: live mode reads the REAL deal.contractId/contractStatus/
  // escrowFunded (DealDtos.java:38-40) — never the CTR-2024-<dealid> /
  // creator-contract-store demo fabrication. Coarse status renders
  // immediately from the deal-list row; once the full contract loads
  // (liveContract, with real brandSignedAt/creatorSignedAt)
  // mapApiContractToDealStatus takes over so "awaiting brand" vs "your turn"
  // is derived from the two real timestamps, not the enum alone.
  const contractStatus = liveApi
    ? selectedDeal.contractId
      ? liveContract
        ? mapApiContractToDealStatus(liveContract, selectedDeal.escrowFunded)
        : mapDealApiContractStatus(selectedDeal.contractStatus, selectedDeal.escrowFunded)
      : undefined
    : resolveDealContractStatus(selectedDeal);
  const contractId = liveApi
    ? selectedDeal.contractId
    : dealHasContract(selectedDeal.id, selectedDeal.status)
      ? resolveContractId(selectedDeal.id)
      : undefined;
  const dealPhase = getDealPhase(selectedDeal.status, contractStatus);
  const hasContract = liveApi
    ? Boolean(selectedDeal.contractId)
    : dealHasContract(selectedDeal.id, selectedDeal.status);
  // CR-02 — the second half of the Accept/Counter/Decline gate. The card's own
  // metadata.status only says whether THIS message was settled; this says whether
  // the DEAL will still take the action. Both must hold.
  const canRespondToProposal = dealAllowsProposalResponse(selectedDeal);
  // CR-03 — scope the accept/decline banner to the room that produced it. Derived
  // rather than cleared in an effect on selectedDeal.id: same result, one render
  // pass instead of two, and no setState-in-effect.
  const feedbackForThisRoom =
    proposalFeedback?.dealId === selectedDeal.id ? proposalFeedback : null;

  /**
   * Bare-invite dead-end fix (Vikram root-cause, routed via Arjun).
   *
   * A deal invited straight into INVITED status (`Collaboration.invite()`) has no
   * priced offer, so `persistProposalMessage` never runs — only `deals.create`/
   * `doCounter` create one — and `events` is genuinely empty even though
   * `canRespondToProposal` correctly says the deal IS still acceptable. The
   * `type === 'proposal'` card a few hundred lines below never gets an event to
   * render, so the creator saw the plain "No messages yet" empty state with no
   * way to act. Gated on `events.length === 0` specifically (not "no proposal
   * event exists") so a deal that has ANY message — chat, a counter-offer, a
   * settled/stale proposal card — keeps its normal timeline untouched; this is
   * strictly the zero-messages fallback.
   *
   * CR-02 fresh-context reject (Priya): `canRespondToProposal` is also true for
   * APPLIED — the creator's OWN pending application to a campaign, not a brand
   * invite — and an APPLIED collaboration has zero messages for the identical
   * reason a bare invite does (`persistProposalMessage` never ran). Gating on
   * `canRespondToProposal` alone rendered this card, with its brand-invited-you
   * copy, on the creator's own application, and its Accept button became a
   * self-accept: `DealService.doAccept`'s `CANNOT_ACCEPT_OWN_OFFER` guard only
   * fires when a `proposal` DealMessage exists to read `senderType` off of, and
   * an APPLIED collaboration has none — so nothing on the server stopped a
   * creator from accepting their own application straight into TERMS_AGREED.
   * This card is BRAND-invite-only, so it is additionally scoped to the one
   * status a bare invite actually produces: `INVITED`.
   */
  const showBareInviteResponse =
    events.length === 0 &&
    canRespondToProposal &&
    selectedDeal.collaborationStatus === 'INVITED';
  /**
   * There is no real message id for a bare invite to key off — the deal has no
   * messages at all — so this is a stable per-deal pseudo-id used ONLY to pin
   * the CR-03 accept/decline feedback banner to this card. It is never sent to
   * the API: `handleAcceptProposal`/`handleDeclineProposal` below call
   * `api.deals.accept`/`api.deals.reject(selectedDeal.id, ...)`, which are
   * deal-scoped and never touch the id argument they're passed here.
   */
  const bareInviteFeedbackId = `bare-invite-${selectedDeal.id}`;

  const deliverableItems =
    selectedDeal.deliverablesTotal > 0
      ? Array.from({ length: selectedDeal.deliverablesTotal }, (_, i) => ({
          id: `${selectedDeal.id}-del-${i + 1}`,
          title: `Deliverable ${i + 1}`,
          type: 'video' as const,
          status: (i < selectedDeal.deliverablesDone ? 'approved' : 'pending') as
            | 'approved'
            | 'pending',
        }))
      : [];

  return (
    <CreatorLayout>
    {/* CR-17 — height is derived from the shared --app-header-h token rather than a
        hardcoded 4rem. The layout header is 3.5rem, so the old value made the deal
        room 8px taller than the space available to it (measured live: main 664px in
        a 720px viewport, deal room 656px), pushing the message composer off-screen. */}
    <div className="flex h-[calc(100vh-var(--app-header-h))] bg-background">
      {/* Left Panel - Deal List */}
      <div className="w-80 border-r flex flex-col">
        <div className="p-4 border-b">
          <h2 className="text-lg font-semibold mb-3">Deal Room</h2>
          <div className="relative">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
            <Input
              placeholder="Search deals..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="pl-9"
            />
          </div>
        </div>
        
        <ScrollArea className="flex-1">
          <div className="p-2 space-y-1">
            {filteredDeals.map((deal) => (
              <div
                key={deal.id}
                onClick={() => selectDeal(deal)}
                className={cn(
                  'p-3 rounded-lg cursor-pointer transition-colors',
                  selectedDeal.id === deal.id
                    ? 'bg-accent'
                    : 'hover:bg-muted/50'
                )}
              >
                <div className="flex items-start gap-3">
                  <Avatar className="h-10 w-10">
                    <AvatarImage src={deal.brandLogo} />
                    <AvatarFallback className="bg-primary/10 text-primary text-sm">
                      {deal.brandInitials}
                    </AvatarFallback>
                  </Avatar>
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center justify-between mb-1">
                      <span className="font-medium truncate">{deal.brandName}</span>
                      {deal.unreadCount > 0 && (
                        <span className="flex h-5 w-5 items-center justify-center rounded-full bg-primary text-[10px] font-medium text-primary-foreground">
                          {deal.unreadCount}
                        </span>
                      )}
                    </div>
                    <p className="text-xs text-muted-foreground truncate mb-1">
                      {deal.campaignName}
                    </p>
                    <div className="flex items-center justify-between">
                      {getStatusBadge(deal.status)}
                      <span className="text-xs font-medium text-muted-foreground">
                        {formatINR(deal.dealAmount)}
                      </span>
                    </div>
                    {deal.deliverablesTotal > 0 && (
                      <div className="mt-2">
                        <div className="flex items-center justify-between text-[10px] text-muted-foreground mb-1">
                          <span>Deliverables</span>
                          <span>{deal.deliverablesDone}/{deal.deliverablesTotal}</span>
                        </div>
                        <Progress 
                          value={(deal.deliverablesDone / deal.deliverablesTotal) * 100} 
                          className="h-1"
                        />
                      </div>
                    )}
                  </div>
                </div>
              </div>
            ))}
          </div>
        </ScrollArea>
      </div>

      {/* Right Panel - Deal Room */}
      <div className="flex-1 flex flex-col min-h-0 overflow-hidden">
        {/* Chat Header */}
        <div className="h-16 border-b flex items-center justify-between px-4">
          <div className="flex items-center gap-3">
            <Avatar className="h-10 w-10">
              <AvatarImage src={selectedDeal.brandLogo} />
              <AvatarFallback className="bg-primary/10 text-primary">
                {selectedDeal.brandInitials}
              </AvatarFallback>
            </Avatar>
            <div>
              <div className="flex items-center gap-2">
                <h3 className="font-semibold">{selectedDeal.brandName}</h3>
                {/* M-1 (BrandF.md §83c/§87) — was unconditional JSX, so every deal showed
                    "Verified Brand" regardless of the brand's real verification state.
                    Now gated on the real `counterpartyVerificationStatus` field; renders
                    nothing for PENDING/UNVERIFIED/REJECTED, matching the honest
                    conditional-badge pattern already used for verification elsewhere
                    (e.g. `invite.brandVerified && <VerifiedBadge />` in
                    `hype-inbox-card.tsx`) rather than inventing new copy for those states. */}
                {selectedDeal.brandVerified && (
                  <Badge variant="outline" className="text-[10px]">
                    <Building2 className="h-3 w-3 mr-1" />
                    Verified Brand
                  </Badge>
                )}
              </div>
              <p className="text-sm text-muted-foreground">{selectedDeal.campaignName}</p>
            </div>
          </div>
          
          <div className="flex items-center gap-2">
            {selectedDeal.status === 'in_progress' && shipmentSupported && !shippingAddress && (
              <Button
                variant="outline"
                size="sm"
                onClick={() => setShowShippingAddressForm(true)}
                className="gap-1.5"
              >
                <MapPin className="h-4 w-4" />
                <span className="hidden sm:inline">Add Shipping Address</span>
              </Button>
            )}
            {selectedDeal.status === 'in_progress' && shipmentSupported && shippingAddress && shipmentStatus !== 'received' && (
              <Button
                variant="outline"
                size="sm"
                onClick={() => setShowShippingAddressForm(true)}
                className="gap-1.5"
              >
                <MapPin className="h-4 w-4" />
                <span className="hidden sm:inline">Edit shipping address</span>
              </Button>
            )}
            {selectedDeal.status === 'in_progress' && (
              <Button
                variant="outline"
                size="sm"
                onClick={() => setShowDeliverableDialog(true)}
                disabled={hasShipment && shipmentStatus !== 'received'}
                title={hasShipment && shipmentStatus !== 'received' ? 'Confirm shipment receipt before submitting' : ''}
                className="gap-1.5"
              >
                <Upload className="h-4 w-4" />
                <span className="hidden sm:inline">Submit Deliverable</span>
              </Button>
            )}
            <Button variant="ghost" size="icon" className="h-9 w-9">
              <MoreVertical className="h-4 w-4" />
            </Button>
          </div>
        </div>

        <DealRoomStepProgress
          className="px-4 pb-2 border-b"
          currentPhase={dealPhase}
          onPhaseClick={(phase) => {
            const panel = creatorPhaseToPanel[phase];
            if (panel) openTool(panel);
          }}
        />

        {/* Tools toolbar — chat is the always-visible primary surface; these
            buttons open structured panels in a side Sheet. */}
        <div className="px-4 py-2 flex flex-wrap items-center gap-2 border-b">
          {hasContract && (
            <Button
              variant="outline"
              size="sm"
              onClick={() => openTool('contract')}
              className="h-7 gap-1.5 text-xs"
            >
              <FileSignature className="h-3.5 w-3.5" />
              Contract
            </Button>
          )}
          <Button
            variant="outline"
            size="sm"
            onClick={() => openTool('deliverables')}
            className="h-7 gap-1.5 text-xs"
          >
            <Package className="h-3.5 w-3.5" />
            Deliverables
            <Badge variant="secondary" className="ml-0.5 h-4 px-1.5 text-[10px]">
              {selectedDeal.deliverablesDone}/{selectedDeal.deliverablesTotal}
            </Badge>
          </Button>
          <Button
            variant="outline"
            size="sm"
            onClick={() => openTool('payments')}
            className="h-7 gap-1.5 text-xs"
          >
            <CreditCard className="h-3.5 w-3.5" />
            Payments
          </Button>
        </div>

        <div className="flex-1 flex flex-col min-h-0 overflow-hidden">
        {/* CR-31 — say it out loud when the room has stopped receiving. The whole defect was
            that a dropped stream looked identical to a quiet conversation: no reconnect, no
            callback, no log. The transport retries on its own now, so 'reconnecting' is
            informational and deliberately understated; 'closed' means it gave up (a 401/403/404
            verdict) and the creator has to act, so that one gets the destructive treatment.
            `text-destructive-foreground`, not `text-destructive` — the latter is a pale
            background token in this theme and renders invisible on the banner. */}
        {liveApi && streamStatus !== 'open' && (
          <div
            role="status"
            className={cn(
              'px-4 py-1.5 border-b text-xs text-center',
              streamStatus === 'closed'
                ? 'bg-destructive/10 text-destructive-foreground'
                : 'bg-muted/50 text-muted-foreground',
            )}
          >
            {streamStatus === 'closed'
              ? 'Live updates are off for this deal. Use Refresh to see new messages.'
              : 'Reconnecting to live updates…'}
          </div>
        )}
        {/* CR-04 — `p-4` moved OFF the ScrollArea root and onto the inner content
            div, and `min-h-0` added.
            Padding on the Radix Root inflates the scroll container itself: the root
            measured 539px inside a 510px parent, guaranteeing the overflow that the
            scrollIntoView call then scrolled into the unreachable wrapper above.
            Padding belongs to the scrolled CONTENT, not to the scroll viewport. */}
        <ScrollArea className="flex-1 min-h-0" viewportRef={messagesViewportRef}>
          <div className="space-y-4 max-w-3xl mx-auto p-4">
            {liveApi && messagesLoading && (
              <div className="flex justify-center py-8">
                <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
              </div>
            )}
            {liveApi && messagesError && !messagesLoading && (
              <div className="text-center py-8 text-sm text-destructive-foreground">{messagesError}</div>
            )}
            {liveApi && !messagesLoading && !messagesError && showBareInviteResponse && (
              <div className="flex justify-start gap-2">
                <Avatar className="h-8 w-8">
                  <AvatarFallback className="bg-primary/10 text-primary text-xs">
                    {selectedDeal.brandInitials}
                  </AvatarFallback>
                </Avatar>
                <Card className="max-w-md border-stage-outreach-border bg-blue-50/50">
                  <CardContent className="p-4">
                    <div className="flex items-center gap-2 mb-3">
                      <FileText className="h-4 w-4 text-stage-outreach-fg" />
                      <span className="font-medium text-sm">Campaign Invite</span>
                    </div>
                    {/* No amount, no deliverables, no earnings breakdown here — a bare
                        invite has no offer terms to show. Fabricating a ₹0/breakdown off
                        an absent amount is exactly the UI-honesty defect this fix avoids;
                        see the priced `type === 'proposal'` card below for that surface.
                        Counter is intentionally absent too: there is no price to counter. */}
                    <p className="text-sm text-muted-foreground mb-4">
                      <span className="font-medium text-foreground">{selectedDeal.brandName}</span>{' '}
                      invited you to collaborate
                      {selectedDeal.campaignName ? (
                        <>
                          {' '}
                          on{' '}
                          <span className="font-medium text-foreground">
                            {selectedDeal.campaignName}
                          </span>
                        </>
                      ) : null}
                      . No offer amount has been set yet — accept to start discussing terms,
                      or decline if it's not a fit.
                    </p>
                    <div className="flex gap-2">
                      <Button
                        size="sm"
                        className="flex-1"
                        onClick={() => handleAcceptProposal(bareInviteFeedbackId)}
                        disabled={isAcceptingProposal || isDecliningProposal}
                      >
                        {isAcceptingProposal ? (
                          <>
                            <Loader2 className="h-4 w-4 mr-1.5 animate-spin" />
                            Accepting...
                          </>
                        ) : (
                          'Accept'
                        )}
                      </Button>
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={() => handleDeclineProposal(bareInviteFeedbackId)}
                        disabled={isAcceptingProposal || isDecliningProposal}
                      >
                        {isDecliningProposal ? (
                          <>
                            <Loader2 className="h-4 w-4 mr-1.5 animate-spin" />
                            Declining...
                          </>
                        ) : (
                          'Decline'
                        )}
                      </Button>
                    </div>
                    {feedbackForThisRoom?.proposalId === bareInviteFeedbackId && (
                      <div
                        className={cn(
                          'mt-3 flex items-start gap-2 rounded-md border p-2.5 text-xs',
                          feedbackForThisRoom.tone === 'error'
                            ? 'border-destructive/40 bg-destructive/10 text-destructive'
                            : 'border-stage-approved-border bg-stage-approved text-stage-approved-fg',
                        )}
                      >
                        {feedbackForThisRoom.tone === 'error' ? (
                          <AlertCircle className="h-3.5 w-3.5 shrink-0 mt-px" />
                        ) : (
                          <CheckCircle2 className="h-3.5 w-3.5 shrink-0 mt-px" />
                        )}
                        <div className="space-y-1.5">
                          <p>{feedbackForThisRoom.message}</p>
                          {feedbackForThisRoom.stale && (
                            <Button
                              size="sm"
                              variant="outline"
                              className="h-6 px-2 text-[11px]"
                              onClick={() => {
                                void afterDealMutation(selectedDeal.id);
                              }}
                            >
                              Refresh deal
                            </Button>
                          )}
                        </div>
                      </div>
                    )}
                  </CardContent>
                </Card>
              </div>
            )}
            {liveApi && !messagesLoading && !messagesError && events.length === 0 && !showBareInviteResponse && (
              <div className="text-center py-12 text-sm text-muted-foreground">
                No messages yet. Say hello to get the conversation started.
              </div>
            )}
            {events.map((event) => (
              <React.Fragment key={event.id}>
                {/* Regular Messages */}
                {event.type === 'message' && (
                  <div
                    className={cn(
                      'flex gap-2',
                      event.sender === 'creator' ? 'justify-end' : 'justify-start'
                    )}
                  >
                    {event.sender === 'brand' && (
                      <Avatar className="h-8 w-8">
                        <AvatarFallback className="bg-primary/10 text-primary text-xs">
                          {selectedDeal.brandInitials}
                        </AvatarFallback>
                      </Avatar>
                    )}
                    <div
                      className={cn(
                        'max-w-[70%] rounded-2xl px-4 py-2',
                        event.sender === 'creator'
                          ? 'bg-primary text-primary-foreground'
                          : 'bg-muted'
                      )}
                    >
                      <p className="text-sm">{event.content}</p>
                      <div
                        className={cn(
                          'flex items-center gap-1 mt-1',
                          event.sender === 'creator' ? 'justify-end' : 'justify-start'
                        )}
                      >
                        <span className={cn(
                          'text-[10px]',
                          event.sender === 'creator' ? 'text-primary-foreground/70' : 'text-muted-foreground'
                        )}>
                          {event.timestamp.split(' ')[1]}
                        </span>
                        {event.sender === 'creator' && (
                          <CheckCheck className="h-3 w-3 text-primary-foreground/70" />
                        )}
                      </div>
                    </div>
                  </div>
                )}

                {/* Proposal Card (from Brand) */}
                {event.type === 'proposal' && (
                  <div className="flex justify-start gap-2">
                    <Avatar className="h-8 w-8">
                      <AvatarFallback className="bg-primary/10 text-primary text-xs">
                        {selectedDeal.brandInitials}
                      </AvatarFallback>
                    </Avatar>
                    <Card className="max-w-md border-stage-outreach-border bg-blue-50/50">
                      <CardContent className="p-4">
                        <div className="flex items-center justify-between mb-3">
                          <div className="flex items-center gap-2">
                            <FileText className="h-4 w-4 text-stage-outreach-fg" />
                            <span className="font-medium text-sm">Brand Proposal</span>
                          </div>
                          <Badge 
                            variant="outline" 
                            className={cn(
                              event.metadata?.status === 'accepted' && 'border-green-500 text-stage-approved-fg',
                              event.metadata?.status === 'countered' && 'border-amber-500 text-stage-negotiating-fg',
                              // 'rejected' is written by DealService.settleLatestProposal
                              // on the reject path (CR-02, backend half) — without this it
                              // rendered as an unstyled default badge.
                              event.metadata?.status === 'rejected' && 'border-destructive text-destructive',
                              event.metadata?.status === 'pending' && 'border-blue-500 text-stage-outreach-fg'
                            )}
                          >
                            {String(event.metadata?.status || 'pending').charAt(0).toUpperCase() + String(event.metadata?.status || 'pending').slice(1)}
                          </Badge>
                        </div>
                        
                        <div className="space-y-2 text-sm">
                          <div className="flex justify-between">
                            <span className="text-muted-foreground">Amount</span>
                            <span className="font-semibold">{formatINR(Number(event.metadata?.amount))}</span>
                          </div>
                          <div className="flex justify-between">
                            <span className="text-muted-foreground">Deliverables</span>
                            <span>{(event.metadata?.deliverables as Array<{type: string; quantity: number}>)?.length || 0} items</span>
                          </div>
                          <div className="flex justify-between">
                            <span className="text-muted-foreground">Usage Rights</span>
                            <span>{String(event.metadata?.usageRights ?? 'Not specified')}</span>
                          </div>
                        </div>

                        {/* Earnings Breakdown for Creator */}
                        <div className="mt-3 pt-3 border-t border-stage-outreach-border">
                          <p className="text-xs text-muted-foreground mb-2">Your Earnings Breakdown</p>
                          <div className="space-y-1 text-xs">
                            {(() => {
                              const gross = Number(event.metadata?.amount);
                              const earnings = calculateEarnings(gross);
                              return (
                                <>
                                  <div className="flex justify-between">
                                    <span className="text-muted-foreground">Gross Amount</span>
                                    <span>{formatINR(gross)}</span>
                                  </div>
                                  <div className="flex justify-between text-stage-disputed-fg">
                                    <span>Platform Fee (15%)</span>
                                    <span>-{formatINR(earnings.platformFee)}</span>
                                  </div>
                                  <div className="flex justify-between font-semibold text-stage-approved-fg pt-1 border-t">
                                    <span>You Receive</span>
                                    <span>{formatINR(earnings.netEarnings)}</span>
                                  </div>
                                </>
                              );
                            })()}
                          </div>
                        </div>

                        {/* CR-02 — both conditions are required. `metadata.status`
                            alone described the message, not the collaboration, so a
                            CONTRACTED deal whose proposal row was never settled still
                            offered all three buttons and 409'd on Accept.

                            All THREE buttons — including Decline — are scoped to the
                            live offer via canAccept(), even though the backend's
                            canReject() is broader (it permits rejecting anything short
                            of COMPLETED/CANCELLED/DISPUTED). That asymmetry is
                            deliberate, per CTO ruling on this ticket: declining a
                            PROPOSAL is meaningless once that offer is off the table,
                            whereas canReject() governs withdrawing from the DEAL — a
                            categorically different action that does not belong on a
                            proposal card. Deal-level withdrawal is intentionally not
                            surfaced here and is pending its own flow. Do not "fix"
                            this by widening the Decline gate to canReject(). */}
                        {event.metadata?.status === 'pending' && canRespondToProposal && (
                          <div className="flex gap-2 mt-4">
                            <Button
                              size="sm"
                              className="flex-1"
                              onClick={() => handleAcceptProposal(event.id)}
                              disabled={isAcceptingProposal || isDecliningProposal}
                            >
                              {isAcceptingProposal ? (
                                <>
                                  <Loader2 className="h-4 w-4 mr-1.5 animate-spin" />
                                  Accepting...
                                </>
                              ) : (
                                'Accept'
                              )}
                            </Button>
                            <Button
                              size="sm"
                              variant="outline"
                              className="flex-1"
                              onClick={() => setShowCounterForm(true)}
                              disabled={isAcceptingProposal || isDecliningProposal}
                            >
                              Counter
                            </Button>
                            <Button
                              size="sm"
                              variant="ghost"
                              onClick={() => handleDeclineProposal(event.id)}
                              disabled={isAcceptingProposal || isDecliningProposal}
                            >
                              {isDecliningProposal ? (
                                <>
                                  <Loader2 className="h-4 w-4 mr-1.5 animate-spin" />
                                  Declining...
                                </>
                              ) : (
                                'Decline'
                              )}
                            </Button>
                          </div>
                        )}

                        {/* Stale card: the message still claims to be open but the deal
                            has moved on. Say so instead of leaving a silent gap where
                            three buttons used to be. */}
                        {event.metadata?.status === 'pending' && !canRespondToProposal && (
                          <p className="mt-4 text-xs text-muted-foreground">
                            This offer is no longer open — the deal has already moved on.
                            Nothing more to do here.
                          </p>
                        )}

                        {/* CR-03 — persistent outcome of the last accept/decline on THIS
                            card. Survives until the next attempt, unlike the toast that
                            accompanies it, so the creator never has to have been looking
                            at the corner of the viewport to learn what happened.

                            Deliberately NOT a live region, matching the share block in
                            creator-portfolio-public.tsx. Both paths here also fire a
                            toast, and a Radix toast is structurally a live region — so
                            "banner announces, toast stays silent" is not achievable
                            without deleting the toast, which is the immediate feedback
                            CR-03 exists to provide. The choice is therefore "both
                            announce" or "toast announces", and it is the latter
                            app-wide: one announcer pattern, so banner-plus-toast does
                            not get relitigated per surface. This banner keeps its
                            visual and navigational value and is still read on demand. */}
                        {feedbackForThisRoom?.proposalId === event.id && (
                          <div
                            className={cn(
                              'mt-3 flex items-start gap-2 rounded-md border p-2.5 text-xs',
                              feedbackForThisRoom.tone === 'error'
                                ? 'border-destructive/40 bg-destructive/10 text-destructive'
                                : 'border-stage-approved-border bg-stage-approved text-stage-approved-fg',
                            )}
                          >
                            {feedbackForThisRoom.tone === 'error' ? (
                              <AlertCircle className="h-3.5 w-3.5 shrink-0 mt-px" />
                            ) : (
                              <CheckCircle2 className="h-3.5 w-3.5 shrink-0 mt-px" />
                            )}
                            <div className="space-y-1.5">
                              <p>{feedbackForThisRoom.message}</p>
                              {feedbackForThisRoom.stale && (
                                <Button
                                  size="sm"
                                  variant="outline"
                                  className="h-6 px-2 text-[11px]"
                                  // Both halves are required, and `afterDealMutation` is
                                  // exactly both: the deal refresh restores
                                  // collaborationStatus, which self-heals the action gate,
                                  // and the message refresh restores metadata.status,
                                  // without which this card keeps its "Pending" badge
                                  // directly above the "no longer open" notice — the exact
                                  // contradiction CR-03 exists to remove.
                                  //
                                  // Was `loadDeals()` here, which flips dealsLoading and so
                                  // replaced the whole room — including this button and the
                                  // message it belongs to — with a full-page spinner
                                  // (CR-21). Refreshing one deal keeps the card on screen.
                                  onClick={() => {
                                    void afterDealMutation(selectedDeal.id);
                                  }}
                                >
                                  Refresh deal
                                </Button>
                              )}
                            </div>
                          </div>
                        )}
                      </CardContent>
                    </Card>
                  </div>
                )}

                {/* Counter Proposal Card (from Creator) */}
                {event.type === 'counter_proposal' && (
                  <div className="flex justify-end gap-2">
                    <Card className="max-w-md border-stage-negotiating-border bg-amber-50/50">
                      <CardContent className="p-4">
                        <div className="flex items-center justify-between mb-3">
                          <div className="flex items-center gap-2">
                            <Reply className="h-4 w-4 text-stage-negotiating-fg" />
                            <span className="font-medium text-sm">Your Counter Proposal</span>
                          </div>
                          <Badge 
                            variant="outline" 
                            className={cn(
                              event.metadata?.status === 'accepted' && 'border-green-500 text-stage-approved-fg',
                              event.metadata?.status === 'pending' && 'border-amber-500 text-stage-negotiating-fg'
                            )}
                          >
                            {String(event.metadata?.status || 'pending').charAt(0).toUpperCase() + String(event.metadata?.status || 'pending').slice(1)}
                          </Badge>
                        </div>
                        
                        <div className="space-y-2 text-sm">
                          <div className="flex justify-between">
                            <span className="text-muted-foreground">Amount</span>
                            <span className="font-semibold">{formatINR(Number(event.metadata?.amount))}</span>
                          </div>
                          <div className="flex justify-between">
                            <span className="text-muted-foreground">Deliverables</span>
                            <span>{(event.metadata?.deliverables as Array<{type: string; quantity: number}>)?.length || 0} items</span>
                          </div>
                          <div className="flex justify-between">
                            <span className="text-muted-foreground">Usage Rights</span>
                            <span>{String(event.metadata?.usageRights ?? 'Not specified')}</span>
                          </div>
                        </div>

                        {/* Earnings Breakdown */}
                        <div className="mt-3 pt-3 border-t border-stage-negotiating-border">
                          <div className="flex justify-between text-sm font-semibold text-stage-approved-fg">
                            <span>You Receive</span>
                            <span>{formatINR(Number(event.metadata?.netEarnings))}</span>
                          </div>
                        </div>
                      </CardContent>
                    </Card>
                  </div>
                )}

                {/* Contract Card */}
                {event.type === 'contract' && (
                  <div className="flex justify-center">
                    <CreatorContractCard
                      event={toLibTimelineEvent(event)}
                      onViewClick={() => handleViewContract(event)}
                      onSignClick={() => handleSignContract(event)}
                    />
                  </div>
                )}

                {/* Payment Event */}
                {event.type === 'payment' && (
                  <div className="flex justify-center">
                    <div className="flex items-center gap-2 px-4 py-2 bg-stage-approved rounded-full">
                      <IndianRupee className="h-4 w-4 text-stage-approved-fg" />
                      <span className="text-sm text-stage-approved-fg font-medium">
                        {event.metadata?.type === 'escrow_funded' 
                          ? `Escrow funded: ${formatINR(Number(event.metadata?.amount))}` 
                          : `Payment received: ${formatINR(Number(event.metadata?.amount))}`
                        }
                      </span>
                    </div>
                  </div>
                )}

                {/* Deliverable Card */}
                {event.type === 'deliverable' && (
                  <div className="flex justify-end gap-2">
                    <Card className="max-w-md border-stage-contracted-border bg-stage-contracted">
                      <CardContent className="p-4">
                        <div className="flex items-center justify-between mb-3">
                          <div className="flex items-center gap-2">
                            <Video className="h-4 w-4 text-purple-600" />
                            <span className="font-medium text-sm">
                              {String(event.metadata?.deliverableType)} #{String(event.metadata?.deliverableNumber)}
                            </span>
                          </div>
                          <Badge 
                            variant="outline" 
                            className={cn(
                              event.metadata?.status === 'approved' && 'border-green-500 text-stage-approved-fg',
                              event.metadata?.status === 'pending_review' && 'border-amber-500 text-stage-negotiating-fg',
                              event.metadata?.status === 'revision_requested' && 'border-red-500 text-stage-disputed-fg'
                            )}
                          >
                            {String(event.metadata?.status).replace(/_/g, ' ').replace(/\b\w/g, l => l.toUpperCase())}
                          </Badge>
                        </div>

                        <div className="aspect-video bg-muted rounded-lg flex items-center justify-center mb-3">
                          <Video className="h-8 w-8 text-muted-foreground" />
                        </div>

                        <p className="text-sm text-muted-foreground mb-2">
                          {String(event.metadata?.caption)}
                        </p>

                        <div className="flex items-center justify-between text-xs text-muted-foreground">
                          <span>Revision {String(event.metadata?.revision)}/{String(event.metadata?.maxRevisions)}</span>
                          {event.metadata?.status === 'approved' && event.metadata?.paymentAmount != null && (
                            <span className="text-stage-approved-fg font-medium">
                              Payment: {formatINR(Number(event.metadata.paymentAmount))}
                            </span>
                          )}
                        </div>
                      </CardContent>
                    </Card>
                  </div>
                )}
              </React.Fragment>
            ))}

            {/* Phase 5: Shipping Address prompt (after contract, before shipment) */}
            {selectedDeal.status === 'in_progress' && shipmentSupported && !shippingAddress && (
              <div className="flex justify-center">
                <div className="w-full max-w-md border-2 border-dashed border-stage-outreach-border bg-stage-outreach rounded-lg p-4 text-center">
                  <MapPin className="h-6 w-6 mx-auto text-stage-outreach-fg mb-2" />
                  <p className="font-medium text-sm mb-1">Share your shipping address</p>
                  <p className="text-xs text-muted-foreground mb-3">
                    The brand needs your address to send the product before you start production.
                  </p>
                  <Button size="sm" onClick={() => setShowShippingAddressForm(true)}>
                    Add Shipping Address
                  </Button>
                </div>
              </div>
            )}

            {/* Phase 5: Shipment Card — status/address are real (GET /deals/:id/shipment)
                when liveApi is on; items/estimatedDelivery stay placeholders (no backend
                field for either) and courier/tracking/notes come from the real record
                once the brand has shipped. See shipmentDisplay above. */}
            {selectedDeal.status === 'in_progress' && shipmentSupported && shippingAddress && hasShipment && (
              <ShipmentCard
                perspective="creator"
                status={shipmentStatus}
                items={shipmentDisplay.items}
                courier={shipmentDisplay.courier}
                trackingNumber={shipmentDisplay.trackingNumber}
                trackingUrl={shipmentDisplay.trackingUrl}
                estimatedDelivery={shipmentDisplay.estimatedDelivery}
                notes={shipmentDisplay.notes}
                onMarkReceived={handleMarkReceived}
              />
            )}

            {/* Demo-only: simulate shipment status progression. Real mode has no client-side
                "mark delivered" action — status only advances via the real GET/brand-ship flow. */}
            {!liveApi && selectedDeal.status === 'in_progress' && shippingAddress && hasShipment && shipmentStatus === 'created' && (
              <div className="flex justify-center">
                <Button variant="ghost" size="sm" className="text-xs text-muted-foreground" onClick={() => setShipmentStatus('delivered')}>
                  (Demo) Simulate delivery
                </Button>
              </div>
            )}
            {/* CR-04 — the old scroll-anchor div is gone; the auto-scroll effect
                now drives the viewport's scrollTop directly (see messagesViewportRef). */}
          </div>
        </ScrollArea>

        {/* Message Input */}
        <div className="p-4 border-t">
          <div className="flex items-end gap-2 max-w-3xl mx-auto">
            <Button variant="ghost" size="icon" className="shrink-0">
              <Paperclip className="h-5 w-5" />
            </Button>
            <Textarea
              placeholder="Type a message..."
              value={message}
              onChange={(e) => setMessage(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault();
                  handleSendMessage();
                }
              }}
              className="min-h-[44px] max-h-32 resize-none"
              rows={1}
            />
            <Button
              onClick={handleSendMessage}
              disabled={!message.trim()}
              className="shrink-0"
            >
              <Send className="h-5 w-5" />
            </Button>
          </div>
        </div>
        </div>

        {/* Tools panel — opens via phase chip click or toolbar button */}
        <ToolsSheet
          open={openPanel !== null}
          onOpenChange={(open) => !open && setOpenPanel(null)}
        >
          <ToolsSheetContent side="right" className="w-full sm:max-w-xl p-0">
            <ToolsSheetHeader className="border-b border-border px-5 py-4">
              <ToolsSheetTitle className="text-base">
                {openPanel === 'contract' && 'Contract'}
                {openPanel === 'deliverables' && 'Deliverables'}
                {openPanel === 'payments' && 'Payments'}
              </ToolsSheetTitle>
            </ToolsSheetHeader>
            <div className="h-[calc(100vh-4rem)] overflow-y-auto">
              {openPanel === 'contract' && (
                hasContract && contractId && contractStatus ? (
                  liveApi && liveContractLoading && !liveContract ? (
                    <div className="flex items-center justify-center h-full p-8 text-sm text-muted-foreground">
                      <Loader2 className="h-4 w-4 mr-2 animate-spin" /> Loading contract…
                    </div>
                  ) : (
                    <CreatorDealContractTab
                      contractId={contractId}
                      brandName={selectedDeal.brandName}
                      campaignName={selectedDeal.campaignName}
                      amount={selectedDeal.dealAmount}
                      // C16: real signed contract total (server-summed) — takes
                      // priority over the deal's dealValue, which can be null/stale.
                      contractAmount={liveContract?.totalAmount ?? null}
                      status={contractStatus}
                      onStatusChange={(status) => updateContractStatus(selectedDeal.id, status)}
                    />
                  )
                ) : (
                  // FE-3 honest empty state: no real contractId on the deal yet.
                  <div className="flex items-center justify-center h-full p-8 text-center text-sm text-muted-foreground">
                    <div>
                      <FileSignature className="h-10 w-10 mx-auto mb-3 opacity-40" />
                      <p className="font-medium text-foreground">No contract yet</p>
                      <p className="mt-1">Contract will appear here once the brand sends it after negotiation.</p>
                      {liveApi && liveContractError && (
                        <p className="mt-2 text-xs text-destructive-foreground">{liveContractError}</p>
                      )}
                    </div>
                  </div>
                )
              )}

              {openPanel === 'deliverables' && (
                <div className="space-y-4">
                  <DealDeliverablesTab
                    done={selectedDeal.deliverablesDone}
                    total={selectedDeal.deliverablesTotal}
                    dealValue={selectedDeal.dealAmount}
                    items={deliverableItems}
                  />
                  {/* Post-approval lifecycle: mark-posted → report metrics / upload proof.
                      Wires the four previously-orphan CreatorDeliverableController routes
                      (PROJECT-DEEP-AUDIT-2026-08-04.md §5). Live mode only. */}
                  {liveApi &&
                    liveDeliverables
                      .filter((d) =>
                        (['APPROVED', 'POSTED', 'METRICS_REPORTED', 'VERIFIED'] as const).includes(
                          d.status as 'APPROVED' | 'POSTED' | 'METRICS_REPORTED' | 'VERIFIED',
                        ),
                      )
                      .map((d) => (
                        <DeliverableLifecyclePanel
                          key={d.id}
                          deliverableId={d.id}
                          title={d.title}
                          status={d.status}
                          onChanged={() => void loadDeliverables()}
                        />
                      ))}
                </div>
              )}

              {openPanel === 'payments' && (
                <DealPaymentsTab
                  dealValue={selectedDeal.dealAmount}
                  contractStatus={contractStatus ?? null}
                  deliverablesDone={selectedDeal.deliverablesDone}
                  deliverablesTotal={selectedDeal.deliverablesTotal}
                />
              )}
            </div>
          </ToolsSheetContent>
        </ToolsSheet>
      </div>

      {/* Counter Proposal Dialog */}
      <Dialog open={showCounterDialog} onOpenChange={setShowCounterDialog}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Send Counter Proposal</DialogTitle>
          </DialogHeader>
          <div className="space-y-4 py-4">
            <div className="space-y-2">
              <Label htmlFor="counter-amount">Your Proposed Amount</Label>
              <div className="relative">
                <IndianRupee className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
                <Input
                  id="counter-amount"
                  type="number"
                  placeholder="50000"
                  value={counterAmount}
                  onChange={(e) => setCounterAmount(e.target.value)}
                  className="pl-9"
                />
              </div>
              {counterAmount && (
                <div className="text-xs text-muted-foreground">
                  You will receive: {formatINR(calculateEarnings(Number(counterAmount)).netEarnings)} after deductions
                </div>
              )}
            </div>
            <div className="space-y-2">
              <Label htmlFor="counter-message">Message (Optional)</Label>
              <Textarea
                id="counter-message"
                placeholder="Explain your counter proposal..."
                value={counterMessage}
                onChange={(e) => setCounterMessage(e.target.value)}
                rows={3}
              />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setShowCounterDialog(false)}>
              Cancel
            </Button>
            <Button onClick={handleSendCounter} disabled={!counterAmount}>
              Send Counter
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Phase 5: Shipping Address Form */}
      <ShippingAddressForm
        open={showShippingAddressForm}
        onOpenChange={setShowShippingAddressForm}
        defaultValues={shippingAddress ?? undefined}
        onSubmit={handleSubmitShippingAddress}
        isSubmitting={isSavingAddress}
      />

      {/* Phase 5: Receipt Confirmation */}
      <ReceiptConfirmation
        open={showReceiptConfirmation}
        onOpenChange={setShowReceiptConfirmation}
        onConfirm={handleConfirmReceipt}
        isSubmitting={isConfirmingReceipt}
      />

      {/* Deliverable Submission Form */}
      <DeliverableSubmission
        open={showDeliverableDialog}
        onOpenChange={setShowDeliverableDialog}
        deliverables={
          liveApi
            ? liveDeliverables.map((d) => ({
                id: d.id,
                title: d.title,
                description: d.description,
                completed: d.completed,
                currentRevision: d.currentRevision ?? undefined,
                maxRevisions: d.maxRevisions ?? undefined,
              }))
            : [
                { id: 'reel-1', title: 'Instagram Reel #1', description: 'High-quality reel with trending audio', completed: true },
                { id: 'reel-2', title: 'Instagram Reel #2', description: 'Follow-up reel with product showcase', completed: false },
              ]
        }
        onSubmit={handleSubmitDeliverableForm}
        isSubmitting={isSubmittingDeliverable}
      />

      {/* Revision Handler Modal */}
      {showRevisionHandler && (
        <RevisionHandler
          open={showRevisionHandler}
          onOpenChange={setShowRevisionHandler}
          deliverableTitle={selectedDeliverableForRevision ? `Deliverable ${selectedDeliverableForRevision}` : 'Deliverable'}
          currentRevision={1}
          maxRevisions={2}
          brandFeedback="Please make the colors more vibrant and add more movement in the middle section. Also, the audio seems too quiet."
          onStartRevision={handleStartRevision}
        />
      )}

      {/* Counter Proposal Form Modal */}
      {showCounterForm && (
        <CounterProposalForm
          brandName={selectedDeal.brandName}
          originalAmount={selectedDeal.dealAmount}
          deliverables={[
            { title: 'Instagram Reel', quantity: 2 },
            { title: 'Instagram Story', quantity: 1 },
          ]}
          onSubmit={handleSubmitCounterForm}
          onClose={() => setShowCounterForm(false)}
          isSubmitting={isSubmittingCounter}
        />
      )}

      {/* Creator Contract Panel Modal */}
      {selectedContractEvent && hasContract && (
        <CreatorContractPanel
          open={showContractPanel}
          onOpenChange={setShowContractPanel}
          event={toLibTimelineEvent(
            enrichContractEvent(selectedContractEvent, selectedDeal),
          )}
          status={contractStatus ?? 'brand_signed'}
          onStatusChange={(status) => updateContractStatus(selectedDeal.id, status)}
        />
      )}
    </div>
    </CreatorLayout>
  );
}
