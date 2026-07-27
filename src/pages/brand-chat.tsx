import * as React from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  MessageCircle,
  Send,
  Paperclip,
  Search,
  MoreVertical,
  Check,
  CheckCheck,
  FileText,
  Image as ImageIcon,
  Clock,
  ChevronRight,
  Shield,
  IndianRupee,
  Calendar,
  Video,
  Upload,
  CheckCircle2,
  AlertCircle,
  Pen,
  Eye,
  Plus,
  Package,
  CreditCard,
  FileSignature,
  Loader2,
} from 'lucide-react';

import { cn, formatINR } from '@/lib/utils';
import { uniqueId } from '@/lib/unique-id';
import {
  api,
  messages as messagesApi,
  deliverables as deliverablesApi,
  deals as dealsApi,
  isApiLive,
  ApiError,
  type DealMessage,
  type Deal,
  type ContractApiRecord,
  type ContractStatus,
} from '@/lib/api';
import type { CollaborationStatus } from '@/lib/types';
import { useToast } from '@/hooks/use-toast';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent } from '@/components/ui/card';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Textarea } from '@/components/ui/textarea';
import { Progress } from '@/components/ui/progress';
import { ProposalForm, type ProposalFormData } from '@/components/brand/deal-room/proposal-form';
import { ShipmentForm, type ShipmentData } from '@/components/brand/deal-room/shipment-form';
import { ShipmentCard, type ShipmentStatus } from '@/components/shared/shipment-card';
import type { ShippingAddressData } from '@/components/creator/deal-room/shipping-address-form';
import { Truck, MapPin } from 'lucide-react';
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet';
import {
  DealRoomStepProgress,
  getDealPhase,
  type DealPhase,
} from '@/components/brand/deal-room/deal-room-step-progress';
import {
  DealContractTab,
  type DealContractStatus,
} from '@/components/brand/deal-room/deal-contract-tab';
import {
  DealContractGenerate,
  type MilestoneDraft,
} from '@/components/brand/deal-room/deal-contract-generate';
import {
  DealDeliverablesTab,
  type DealDeliverableItem,
} from '@/components/brand/deal-room/deal-deliverables-tab';
import { DealPaymentsTab } from '@/components/brand/deal-room/deal-payments-tab';
import {
  mapApiContractToDealStatus,
  mapDealApiContractStatus,
} from '@/lib/creator-contract-mappers';

/**
 * Deal Room is now chat-first. The 4-tab layout (Messages / Contract /
 * Deliverables / Payments) is replaced with:
 *   - Always-visible chat feed (already inlines contract / deliverable /
 *     payment cards as system messages)
 *   - A single "Tools panel" Sheet that the user opens via the phase
 *     progress bar or the toolbar buttons — it hosts the structured
 *     contract / deliverables / payments views as one scrollable column.
 */
type ToolsPanel = 'contract' | 'deliverables' | 'payments' | null;

const INITIAL_CONTRACT_STATUS: Record<string, DealContractStatus> = {
  'deal-1': 'active',
  'deal-3': 'generated',
  'deal-4': 'active',
};

const CONTRACT_IDS: Record<string, string> = {
  'deal-1': 'CTR-2024-001',
  'deal-3': 'CTR-2024-003',
  'deal-4': 'CTR-2024-004',
};

const phaseToPanel: Record<DealPhase, ToolsPanel> = {
  negotiate: null,
  contract: 'contract',
  escrow: 'payments',
  deliver: 'deliverables',
  pay: 'payments',
};

// Status configurations
const dealStatusConfig = {
  negotiating: { label: 'Negotiating', color: 'border bg-stage-negotiating text-stage-negotiating-fg border-stage-negotiating-border', icon: MessageCircle },
  contracted: { label: 'Contracted', color: 'border bg-stage-contracted text-stage-contracted-fg border-stage-contracted-border', icon: FileText },
  in_progress: { label: 'In Progress', color: 'border bg-stage-progress text-stage-progress-fg border-stage-progress-border', icon: Upload },
  review: { label: 'Under Review', color: 'border bg-stage-review text-stage-review-fg border-stage-review-border', icon: Eye },
  completed: { label: 'Completed', color: 'border bg-stage-approved text-stage-approved-fg border-stage-approved-border', icon: CheckCircle2 },
};

// Deal room list row shape — shared by mock and live-mode (I6) rendering.
interface ChatDealRoom {
  id: string;
  creatorId: string;
  creatorName: string;
  creatorHandle: string;
  creatorAvatar: string;
  campaignName: string;
  dealStatus: keyof typeof dealStatusConfig;
  dealValue: number;
  lastMessage: string;
  lastMessageTime: Date;
  unreadCount: number;
  progress: number;
  deliverablesDone: number;
  deliverablesTotal: number;
  nextDeadline: Date | null;
  /**
   * Real backend fields (DealDtos.java:38-40, DealService.java:732-758) — the
   * honest source for contract state in live mode. `rawStatus` is the full
   * `CollaborationStatus` (unbucketed), needed to gate "Review & send
   * contract" precisely on `TERMS_AGREED` rather than the coarse
   * `dealStatus` bucket, which also covers CONTRACT_PENDING/CONTRACTED.
   */
  rawStatus: CollaborationStatus;
  contractId?: string;
  contractStatus?: ContractStatus;
  escrowFunded: boolean;
}

// ---------------------------------------------------------------------------
// Live-mode mapping (I6) — Deal (src/lib/api.ts) -> ChatDealRoom (this file's
// deal-room-list shape). Deals with no equivalent dealStatus (CANCELLED,
// DISPUTED) are filtered out — this list has no matching status chip for them.
// ---------------------------------------------------------------------------
function mapDealStatusToChatStatus(status: Deal['status']): ChatDealRoom['dealStatus'] | null {
  switch (status) {
    case 'INVITED':
    case 'APPLIED':
    case 'SHORTLISTED':
    case 'IN_NEGOTIATION':
      return 'negotiating';
    case 'TERMS_AGREED':
    case 'CONTRACT_PENDING':
    case 'CONTRACTED':
      return 'contracted';
    case 'IN_PROGRESS':
      return 'in_progress';
    case 'REVIEW_PENDING':
    case 'REVISION_REQUESTED':
      return 'review';
    case 'COMPLETED':
      return 'completed';
    case 'CANCELLED':
    case 'DISPUTED':
    default:
      return null;
  }
}

function mapDealToChatRoom(deal: Deal): ChatDealRoom | null {
  const dealStatus = mapDealStatusToChatStatus(deal.status);
  if (!dealStatus) return null;
  return {
    id: deal.id,
    creatorId: deal.counterpartyId,
    creatorName: deal.counterpartyName,
    creatorHandle: deal.counterpartyHandle || '',
    creatorAvatar: deal.counterpartyAvatar || '',
    campaignName: deal.campaignName,
    dealStatus,
    dealValue: deal.dealValue,
    lastMessage: deal.lastMessage || 'No messages yet',
    lastMessageTime: deal.lastMessageAt ? new Date(deal.lastMessageAt) : new Date(0),
    unreadCount: deal.unreadCount,
    progress:
      deal.deliverablesTotal > 0
        ? Math.round((deal.deliverablesDone / deal.deliverablesTotal) * 100)
        : 0,
    deliverablesDone: deal.deliverablesDone,
    deliverablesTotal: deal.deliverablesTotal,
    nextDeadline: deal.nextDeadline ? new Date(deal.nextDeadline) : null,
    rawStatus: deal.status,
    contractId: deal.contractId,
    contractStatus: deal.contractStatus,
    escrowFunded: deal.escrowFunded,
  };
}

// Mock conversations / deal rooms
const mockDealRooms: ChatDealRoom[] = [
  {
    id: 'deal-1',
    creatorId: 'cr-1',
    creatorName: 'Priya Sharma',
    creatorHandle: '@priyasharma',
    creatorAvatar: '',
    campaignName: 'Summer Fashion Campaign',
    dealStatus: 'in_progress',
    dealValue: 50000,
    lastMessage: 'Thanks for the feedback! I\'ll make those changes.',
    lastMessageTime: new Date(Date.now() - 15 * 60 * 1000),
    unreadCount: 2,
    progress: 65,
    deliverablesDone: 2,
    deliverablesTotal: 3,
    nextDeadline: new Date(Date.now() + 3 * 24 * 60 * 60 * 1000),
    rawStatus: 'IN_PROGRESS',
    escrowFunded: true,
  },
  {
    id: 'deal-2',
    creatorId: 'cr-2',
    creatorName: 'Arjun Kapoor',
    creatorHandle: '@arjunkapoor',
    creatorAvatar: '',
    campaignName: 'Tech Review Campaign',
    dealStatus: 'negotiating',
    dealValue: 75000,
    lastMessage: 'Here\'s my counter proposal for the deliverables.',
    lastMessageTime: new Date(Date.now() - 2 * 60 * 60 * 1000),
    unreadCount: 0,
    progress: 0,
    deliverablesDone: 0,
    deliverablesTotal: 4,
    nextDeadline: null,
    rawStatus: 'IN_NEGOTIATION',
    escrowFunded: false,
  },
  {
    id: 'deal-3',
    creatorId: 'cr-3',
    creatorName: 'Sneha Reddy',
    creatorHandle: '@snehareddy',
    creatorAvatar: '',
    campaignName: 'Wellness Series',
    dealStatus: 'contracted',
    dealValue: 85000,
    lastMessage: 'Contract signed! Looking forward to working together.',
    lastMessageTime: new Date(Date.now() - 24 * 60 * 60 * 1000),
    unreadCount: 0,
    progress: 0,
    deliverablesDone: 0,
    deliverablesTotal: 5,
    nextDeadline: new Date(Date.now() + 7 * 24 * 60 * 60 * 1000),
    rawStatus: 'CONTRACTED',
    escrowFunded: false,
  },
  {
    id: 'deal-4',
    creatorId: 'cr-4',
    creatorName: 'Rahul Verma',
    creatorHandle: '@rahulverma',
    creatorAvatar: '',
    campaignName: 'Product Launch',
    dealStatus: 'review',
    dealValue: 45000,
    lastMessage: 'I\'ve submitted all deliverables for review.',
    lastMessageTime: new Date(Date.now() - 3 * 24 * 60 * 60 * 1000),
    unreadCount: 0,
    progress: 100,
    deliverablesDone: 2,
    deliverablesTotal: 2,
    nextDeadline: null,
    rawStatus: 'REVIEW_PENDING',
    escrowFunded: true,
  },
];

// Mock timeline events for active deal
const mockTimelineEvents = [
  {
    id: 'e1',
    type: 'message',
    sender: 'creator',
    senderName: 'Priya Sharma',
    content: 'Hi! Thank you for reaching out about the Summer Fashion Campaign. I\'m very interested!',
    timestamp: new Date(Date.now() - 5 * 24 * 60 * 60 * 1000),
    status: 'read',
  },
  {
    id: 'e2',
    type: 'message',
    sender: 'brand',
    senderName: 'Brand',
    content: 'Great to hear! We loved your previous work on lifestyle content. Here\'s our proposal.',
    timestamp: new Date(Date.now() - 5 * 24 * 60 * 60 * 1000 + 30 * 60 * 1000),
    status: 'read',
  },
  {
    id: 'e3',
    type: 'proposal',
    sender: 'brand',
    data: {
      amount: 45000,
      deliverables: [
        { type: 'Instagram Reel', count: 2 },
        { type: 'Story Set', count: 3 },
        { type: 'Feed Post', count: 1 },
      ],
      deadline: new Date(Date.now() + 14 * 24 * 60 * 60 * 1000),
      usageRights: '6 months',
      exclusivity: false,
      status: 'countered',
    },
    timestamp: new Date(Date.now() - 5 * 24 * 60 * 60 * 1000 + 35 * 60 * 1000),
  },
  {
    id: 'e4',
    type: 'message',
    sender: 'creator',
    senderName: 'Priya Sharma',
    content: 'Thanks for the offer! I\'d like to counter with a slightly higher rate given the exclusivity ask.',
    timestamp: new Date(Date.now() - 4 * 24 * 60 * 60 * 1000),
    status: 'read',
  },
  {
    id: 'e5',
    type: 'proposal',
    sender: 'creator',
    data: {
      amount: 50000,
      deliverables: [
        { type: 'Instagram Reel', count: 2 },
        { type: 'Story Set', count: 3 },
        { type: 'Feed Post', count: 1 },
      ],
      deadline: new Date(Date.now() + 14 * 24 * 60 * 60 * 1000),
      usageRights: '6 months',
      exclusivity: false,
      status: 'accepted',
    },
    timestamp: new Date(Date.now() - 4 * 24 * 60 * 60 * 1000 + 15 * 60 * 1000),
  },
  {
    id: 'e6',
    type: 'message',
    sender: 'brand',
    senderName: 'Brand',
    content: 'That works for us! Let\'s proceed with the contract.',
    timestamp: new Date(Date.now() - 3 * 24 * 60 * 60 * 1000),
    status: 'read',
  },
  {
    id: 'e7',
    type: 'contract',
    sender: 'system',
    data: {
      contractId: 'CTR-2024-001',
      amount: 50000,
      status: 'both_signed',
      brandSigned: true,
      brandSignedAt: new Date(Date.now() - 3 * 24 * 60 * 60 * 1000 + 1 * 60 * 60 * 1000),
      creatorSigned: true,
      creatorSignedAt: new Date(Date.now() - 3 * 24 * 60 * 60 * 1000 + 2 * 60 * 60 * 1000),
      terms: {
        paymentTerms: '50% advance, 50% on completion',
        revisions: 2,
        cancellation: '7 days notice',
      },
    },
    timestamp: new Date(Date.now() - 3 * 24 * 60 * 60 * 1000 + 2 * 60 * 60 * 1000),
  },
  {
    id: 'e8',
    type: 'payment',
    sender: 'system',
    data: {
      type: 'escrow_funded',
      amount: 50000,
      description: 'Full amount secured in escrow',
    },
    timestamp: new Date(Date.now() - 3 * 24 * 60 * 60 * 1000 + 3 * 60 * 60 * 1000),
  },
  {
    id: 'e9',
    type: 'message',
    sender: 'creator',
    senderName: 'Priya Sharma',
    content: 'Contract signed and escrow confirmed! I\'ll start working on the first reel today.',
    timestamp: new Date(Date.now() - 2 * 24 * 60 * 60 * 1000),
    status: 'read',
  },
  {
    id: 'e10',
    type: 'deliverable',
    sender: 'creator',
    data: {
      id: 'del-1',
      title: 'Instagram Reel #1',
      type: 'video',
      status: 'approved',
      submittedAt: new Date(Date.now() - 1 * 24 * 60 * 60 * 1000),
      approvedAt: new Date(Date.now() - 12 * 60 * 60 * 1000),
      thumbnail: null,
    },
    timestamp: new Date(Date.now() - 1 * 24 * 60 * 60 * 1000),
  },
  {
    id: 'e11',
    type: 'message',
    sender: 'brand',
    senderName: 'Brand',
    content: 'Reel #1 looks amazing! Approved. The product placement is perfect.',
    timestamp: new Date(Date.now() - 12 * 60 * 60 * 1000),
    status: 'read',
  },
  {
    id: 'e12',
    type: 'deliverable',
    sender: 'creator',
    data: {
      id: 'del-2',
      title: 'Instagram Reel #2 - Draft',
      type: 'video',
      status: 'pending_review',
      submittedAt: new Date(Date.now() - 4 * 60 * 60 * 1000),
      thumbnail: null,
    },
    timestamp: new Date(Date.now() - 4 * 60 * 60 * 1000),
  },
  {
    id: 'e13',
    type: 'message',
    sender: 'brand',
    senderName: 'Brand',
    content: 'Great work on Reel #2! Just a small suggestion - can you add more focus on the product features in the first 3 seconds?',
    timestamp: new Date(Date.now() - 1 * 60 * 60 * 1000),
    status: 'delivered',
  },
  {
    id: 'e14',
    type: 'message',
    sender: 'creator',
    senderName: 'Priya Sharma',
    content: 'Thanks for the feedback! I\'ll make those changes.',
    timestamp: new Date(Date.now() - 15 * 60 * 1000),
    status: 'read',
  },
];

function getDeliverablesForDeal(dealId: string): DealDeliverableItem[] {
  if (dealId === 'deal-1') {
    return mockTimelineEvents
      .filter((e) => e.type === 'deliverable')
      .map((e) => ({
        id: e.data?.id || e.id,
        title: e.data?.title || 'Deliverable',
        type: (e.data?.type === 'video' ? 'video' : 'image') as 'video' | 'image',
        status:
          e.data?.status === 'approved'
            ? 'approved'
            : e.data?.status === 'pending_review'
              ? 'pending_review'
              : 'pending',
        submittedAt: e.data?.submittedAt,
      }));
  }
  const deal = mockDealRooms.find((d) => d.id === dealId);
  if (!deal || deal.deliverablesTotal === 0) return [];
  return Array.from({ length: deal.deliverablesTotal }, (_, i) => ({
    id: `${dealId}-del-${i + 1}`,
    title: `Deliverable ${i + 1}`,
    type: 'video' as const,
    status: (i < deal.deliverablesDone ? 'approved' : 'pending') as DealDeliverableItem['status'],
  }));
}

function formatTime(date: Date): string {
  const now = new Date();
  const diff = now.getTime() - date.getTime();
  const days = Math.floor(diff / (24 * 60 * 60 * 1000));
  
  if (days === 0) {
    return date.toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit' });
  } else if (days === 1) {
    return 'Yesterday';
  } else if (days < 7) {
    return date.toLocaleDateString('en-IN', { weekday: 'short' });
  } else {
    return date.toLocaleDateString('en-IN', { day: 'numeric', month: 'short' });
  }
}

function formatDate(date: Date): string {
  return date.toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
}

export default function BrandChatPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const creatorIdFromUrl = searchParams.get('creator');
  const dealIdFromUrl = searchParams.get('deal');
  const tabFromUrl = searchParams.get('tab');

  // I6: live deal-room list (GET /deals?role=brand). Live mode only; demo mode
  // keeps the static mockDealRooms list untouched.
  const [liveDealRooms, setLiveDealRooms] = React.useState<ChatDealRoom[]>([]);
  const [dealsLoading, setDealsLoading] = React.useState(false);
  const [dealsError, setDealsError] = React.useState<string | null>(null);

  const dealRooms = isApiLive() ? liveDealRooms : mockDealRooms;

  const [selectedDeal, setSelectedDeal] = React.useState<ChatDealRoom | null>(
    isApiLive() ? null : mockDealRooms[0],
  );
  const [message, setMessage] = React.useState('');
  const [chatMessages, setChatMessages] = React.useState<Array<{
    id: string; sender: 'brand' | 'creator'; content: string; timestamp: Date; status: 'sent' | 'delivered' | 'read';
  }>>([]);
  const [searchQuery, setSearchQuery] = React.useState('');
  const [openPanel, setOpenPanel] = React.useState<ToolsPanel>(null);
  const [contractStatusByDeal, setContractStatusByDeal] =
    React.useState<Record<string, DealContractStatus>>(INITIAL_CONTRACT_STATUS);
  const [showProposalForm, setShowProposalForm] = React.useState(false);
  const [isSubmittingProposal, setIsSubmittingProposal] = React.useState(false);
  /**
   * Real workspace platform fee for the proposal form's cost breakdown. The form used to
   * hardcode 10%; the platform default is 15% (application.yml PLATFORM_FEE_PERCENT), so brands
   * were quoted a total below what they'd actually be charged. 15 is the fallback if the fetch
   * fails, matching the server default rather than re-introducing a made-up number.
   */
  const [platformFeePercent, setPlatformFeePercent] = React.useState(15);

  React.useEffect(() => {
    if (!isApiLive()) return;
    let cancelled = false;
    api.wallet
      .brandPlatformFee()
      .then((fee) => {
        if (!cancelled) setPlatformFeePercent(fee.feePercent);
      })
      .catch(() => {
        /* keep the 15% default — a failed fee read must not block sending a proposal */
      });
    return () => {
      cancelled = true;
    };
  }, []);
  const [deliverableStatuses, setDeliverableStatuses] = React.useState<Record<string, DealDeliverableItem['status']>>({});

  // FE-1..FE-5: live contract state for the selected deal — GET /contracts/:id
  // (full milestones + brandSignedAt/creatorSignedAt), plus the create-trigger
  // (POST /contracts). Live mode only; demo mode keeps using
  // contractStatusByDeal/CONTRACT_IDS untouched.
  const [liveContract, setLiveContract] = React.useState<ContractApiRecord | null>(null);
  const [liveContractLoading, setLiveContractLoading] = React.useState(false);
  const [liveContractError, setLiveContractError] = React.useState<string | null>(null);
  const [isGeneratingContract, setIsGeneratingContract] = React.useState(false);
  const { toast } = useToast();

  // B-1: live deal-room messages (GET/POST /deals/:id/messages). Live mode only;
  // demo mode keeps the scripted mock feed below untouched.
  const [liveMessages, setLiveMessages] = React.useState<DealMessage[]>([]);
  const [messagesLoading, setMessagesLoading] = React.useState(false);
  const [messagesError, setMessagesError] = React.useState<string | null>(null);

  // B-1: live deliverables (GET /deals/:id/deliverables). Live mode only.
  const [liveDeliverables, setLiveDeliverables] = React.useState<DealDeliverableItem[]>([]);
  const [deliverablesLoading, setDeliverablesLoading] = React.useState(false);
  const [deliverablesError, setDeliverablesError] = React.useState<string | null>(null);

  const syncUrl = React.useCallback(
    (dealId: string, panel: ToolsPanel) => {
      const next = new URLSearchParams(searchParams);
      next.set('deal', dealId);
      next.delete('creator');
      if (!panel) next.delete('tab');
      else next.set('tab', panel);
      setSearchParams(next, { replace: true });
    },
    [searchParams, setSearchParams],
  );

  const selectDeal = (deal: ChatDealRoom) => {
    setSelectedDeal(deal);
    setOpenPanel(null);
    syncUrl(deal.id, null);
  };

  const openTool = (panel: ToolsPanel) => {
    setOpenPanel(panel);
    if (selectedDeal) syncUrl(selectedDeal.id, panel);
  };

  const loadDealRooms = React.useCallback(async () => {
    setDealsLoading(true);
    setDealsError(null);
    try {
      const remote = await dealsApi.list('brand');
      setLiveDealRooms(
        Array.isArray(remote)
          ? remote.map(mapDealToChatRoom).filter((d): d is ChatDealRoom => d !== null)
          : [],
      );
    } catch {
      setLiveDealRooms([]);
      setDealsError('Could not load deal rooms. Check your connection and retry.');
    } finally {
      setDealsLoading(false);
    }
  }, []);

  React.useEffect(() => {
    if (isApiLive()) void loadDealRooms();
  }, [loadDealRooms]);

  // Keep `selectedDeal` (plain state, not derived) in sync whenever the live
  // list refreshes — e.g. right after POST /contracts, so the newly-real
  // deal.contractId/contractStatus reach the selected room without requiring
  // the user to reselect it.
  React.useEffect(() => {
    if (!isApiLive() || !selectedDeal) return;
    const updated = liveDealRooms.find((d) => d.id === selectedDeal.id);
    if (updated && updated !== selectedDeal) setSelectedDeal(updated);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [liveDealRooms]);

  // FE-2/FE-3 (mirrored for brand side): fetch the full contract — milestones,
  // brandSignedAt, creatorSignedAt — once a real contractId exists. Coarse
  // deal.contractStatus already renders an honest state before this resolves.
  const fetchLiveContract = React.useCallback(async () => {
    if (!isApiLive() || !selectedDeal?.contractId) {
      setLiveContract(null);
      return;
    }
    setLiveContractLoading(true);
    setLiveContractError(null);
    try {
      const record = await api.contracts.get('brand', selectedDeal.contractId);
      setLiveContract(record);
    } catch (err) {
      setLiveContract(null);
      setLiveContractError(err instanceof ApiError ? err.message : 'Could not load contract details.');
    } finally {
      setLiveContractLoading(false);
    }
  }, [selectedDeal?.contractId]);

  React.useEffect(() => {
    void fetchLiveContract();
  }, [fetchLiveContract]);

  // FE-4: the create trigger. POST /contracts { collaborationId, milestones }
  // — the deal/negotiation flow never called this before (architecture doc
  // §0/§2), so contractId stayed null forever. After it succeeds, refresh the
  // deal list so `selectedDeal.contractId`/`contractStatus` become real and
  // the panel flips straight into the sign step.
  const handleGenerateContract = async (milestones: MilestoneDraft[]) => {
    if (!selectedDeal) return;
    setIsGeneratingContract(true);
    try {
      await api.contracts.generate({
        collaborationId: selectedDeal.id,
        milestones: milestones.map((m, i) => ({
          sequenceNo: i + 1,
          description: m.description,
          amount: m.amount,
          dueDate: m.dueDate || undefined,
        })),
      });
      toast({
        title: 'Contract sent',
        description: `Review and sign it below, then ${selectedDeal.creatorName} will be notified.`,
      });
      await loadDealRooms();
    } catch (err) {
      toast({
        title: 'Could not create contract',
        description: err instanceof ApiError ? err.message : 'Please try again.',
        variant: 'destructive',
      });
    } finally {
      setIsGeneratingContract(false);
    }
  };

  // Demo mode: URL param selection against the static mock list (unchanged).
  React.useEffect(() => {
    if (isApiLive()) return;
    if (dealIdFromUrl) {
      const deal = mockDealRooms.find((d) => d.id === dealIdFromUrl);
      if (deal) setSelectedDeal(deal);
    } else if (creatorIdFromUrl) {
      const deal = mockDealRooms.find((d) => d.creatorId === creatorIdFromUrl);
      if (deal) setSelectedDeal(deal);
    }
  }, [dealIdFromUrl, creatorIdFromUrl]);

  // Live mode (I6): select from the live list once it arrives — via URL param
  // if present, else the first deal room.
  React.useEffect(() => {
    if (!isApiLive() || selectedDeal || liveDealRooms.length === 0) return;
    const match = dealIdFromUrl
      ? liveDealRooms.find((d) => d.id === dealIdFromUrl)
      : creatorIdFromUrl
        ? liveDealRooms.find((d) => d.creatorId === creatorIdFromUrl)
        : undefined;
    setSelectedDeal(match || liveDealRooms[0]);
  }, [liveDealRooms, selectedDeal, dealIdFromUrl, creatorIdFromUrl]);

  React.useEffect(() => {
    const valid: ToolsPanel[] = ['contract', 'deliverables', 'payments'];
    if (tabFromUrl && (valid as string[]).includes(tabFromUrl)) {
      setOpenPanel(tabFromUrl as ToolsPanel);
    }
  }, [tabFromUrl]);

  const openContractTab = () => openTool('contract');

  // Phase 5: Shipping flow state (brand side)
  // Demo: creator has provided an address; brand needs to ship.
  const [shippingAddress] = React.useState<ShippingAddressData | null>({
    fullName: 'Priya Sharma',
    phone: '9876543210',
    addressLine1: '402, Sea View Apartments, Carter Road',
    addressLine2: 'Bandra West',
    city: 'Mumbai',
    state: 'Maharashtra',
    pincode: '400050',
    landmark: 'Opposite Joggers Park',
  });
  const [shipment, setShipment] = React.useState<(ShipmentData & { status: ShipmentStatus }) | null>(null);
  const [showShipmentForm, setShowShipmentForm] = React.useState(false);
  const [isSubmittingShipment, setIsSubmittingShipment] = React.useState(false);

  const handleSubmitShipment = async (data: ShipmentData) => {
    setIsSubmittingShipment(true);
    await new Promise((r) => setTimeout(r, 800));
    setShipment({ ...data, status: 'created' });
    setShowShipmentForm(false);
    setIsSubmittingShipment(false);
  };
  const messagesEndRef = React.useRef<HTMLDivElement>(null);

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  };

  React.useEffect(() => {
    scrollToBottom();
  }, [selectedDeal]);

  const loadMessages = React.useCallback(async (dealId: string) => {
    setMessagesLoading(true);
    setMessagesError(null);
    try {
      const list = await messagesApi.list('brand', dealId);
      setLiveMessages(list);
      // fire-and-forget read receipt; failure here must not surface as a load error
      void messagesApi.markRead('brand', dealId).catch(() => {});
    } catch {
      setLiveMessages([]); // clear stale rows from a previously-selected deal
      setMessagesError('Could not load messages. Check your connection and retry.');
    } finally {
      setMessagesLoading(false);
    }
  }, []);

  const loadDeliverables = React.useCallback(async (dealId: string) => {
    setDeliverablesLoading(true);
    setDeliverablesError(null);
    try {
      const rows = await deliverablesApi.list('brand', dealId);
      // Kavya M-2: guard the shape at runtime so a non-array (e.g. an error
      // envelope) surfaces as an error state, not a silent empty list.
      if (!Array.isArray(rows)) {
        throw new Error('Unexpected deliverables response shape');
      }
      const mapped: DealDeliverableItem[] = (rows as Array<Record<string, unknown>>).map((d) => {
        const raw = String(d.status ?? '').toUpperCase();
        // Kavya LOW-3: METRICS_REPORTED is a post-approval audit state → 'approved'.
        // REJECTED / PENDING / DRAFT fall to 'pending' (no distinct UI state;
        // REJECTED is ideally filtered server-side before reaching the brand list).
        const status: DealDeliverableItem['status'] =
          raw === 'APPROVED' || raw === 'VERIFIED' || raw === 'POSTED' || raw === 'METRICS_REPORTED'
            ? 'approved'
            : raw === 'SUBMITTED' || raw === 'RESUBMITTED'
              ? 'pending_review'
              : raw === 'REVISION_REQUESTED'
                ? 'revision'
                : 'pending';
        return {
          id: String(d.id ?? ''),
          title: String(d.title ?? 'Deliverable'),
          type: 'image', // backend DTO carries no media type; icon-only, cosmetic
          status,
        };
      });
      setLiveDeliverables(mapped);
    } catch {
      setLiveDeliverables([]); // clear stale rows from a previously-selected deal
      setDeliverablesError('Could not load deliverables. Check your connection and retry.');
    } finally {
      setDeliverablesLoading(false);
    }
  }, []);

  const handleApproveLive = async (id: string) => {
    // Kavya M-1: snapshot the id — selectedDeal can change between guard and reload.
    const dealId = selectedDeal?.id;
    if (!dealId) return;
    setDeliverablesError(null);
    try {
      await deliverablesApi.approve(id);
      await loadDeliverables(dealId);
    } catch {
      setDeliverablesError('Could not approve. Try again.');
    }
  };

  const handleReviseLive = async (id: string) => {
    const dealId = selectedDeal?.id;
    if (!dealId) return;
    setDeliverablesError(null);
    try {
      await deliverablesApi.requestRevision(id, '');
      await loadDeliverables(dealId);
    } catch {
      setDeliverablesError('Could not request revision. Try again.');
    }
  };

  React.useEffect(() => {
    if (isApiLive() && selectedDeal) {
      void loadMessages(selectedDeal.id);
      void loadDeliverables(selectedDeal.id);
    }
  }, [selectedDeal, loadMessages, loadDeliverables]);

  // Realtime messaging (Priya direct assignment, SHARED_CONTEXT.md "Realtime
  // messaging for brand-chat"): opens the deal-message SSE stream after the
  // initial messagesApi.list load, live mode only. Dedupe by message id is
  // mandatory — sendMessage's publish-on-send fires to every open emitter
  // for the deal including the sender's own, and handleSendMessage already
  // appends the message returned by messagesApi.send, so without this guard
  // the sender would see every own-message twice.
  //
  // Keyed on selectedDeal?.id (not the selectedDeal object) so a deal-room
  // list refresh that returns a new object for the same deal doesn't tear
  // down and reopen the connection. The effect cleanup — which fires on
  // both id change and unmount — closes the stream, so switching deals or
  // navigating away never leaves a previous deal's stream writing into the
  // newly selected one.
  React.useEffect(() => {
    if (!isApiLive() || !selectedDeal) return;
    const dealId = selectedDeal.id;

    const handle = messagesApi.stream('brand', dealId, {
      onMessage: (incoming) => {
        setLiveMessages((prev) => {
          if (prev.some((m) => m.id === incoming.id)) return prev;
          return [...prev, incoming];
        });
        setTimeout(scrollToBottom, 50);
      },
      onError: () => {
        // Graceful degrade: a dropped/failed stream is silent and non-fatal —
        // the existing fetch-on-load path (loadMessages) already covers
        // message delivery, so send/render must never be blocked by this.
        console.debug('[brand-chat] deal message stream error/closed for deal', dealId);
      },
    });

    return () => {
      handle.close();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedDeal?.id]);

  const handleSendMessage = async () => {
    if (!message.trim()) return;
    const content = message.trim();

    // Live mode: persist via POST /deals/:id/messages; preserve input on failure.
    if (isApiLive() && selectedDeal) {
      const dealId = selectedDeal.id;
      setMessage('');
      setMessagesError(null);
      try {
        const sent = await messagesApi.send('brand', dealId, content);
        setLiveMessages((prev) => [...prev, sent]);
        setTimeout(scrollToBottom, 50);
      } catch {
        setMessage(content); // restore so the user can retry — no silent loss
        setMessagesError('Message failed to send. Try again.');
      }
      return;
    }

    // Demo mode: local-only append (unchanged behavior).
    const newMsg = {
      id: uniqueId('msg'),
      sender: 'brand' as const,
      content,
      timestamp: new Date(),
      status: 'sent' as const,
    };
    setChatMessages((prev) => [...prev, newMsg]);
    setMessage('');
    setTimeout(scrollToBottom, 50);
  };

  const handleApproveDeliverable = (itemId: string, _feedback: string) => {
    setDeliverableStatuses((prev) => ({ ...prev, [itemId]: 'approved' }));
  };

  const handleRequestRevision = (itemId: string, _feedback: string) => {
    setDeliverableStatuses((prev) => ({ ...prev, [itemId]: 'revision' }));
  };

  /**
   * Composes the note that accompanies a proposal.
   *
   * `deadline` and `usageRights` are NOT in here — since CounterRequest was aligned with
   * CreateDealRequest (2026-07-26) they travel as real fields the server reads and persists,
   * rather than as prose the server can only store verbatim. What remains are the two terms with
   * no column anywhere: usage-rights add-ons and free-text custom clauses.
   */
  const composeProposalMessage = (data: ProposalFormData): string => {
    const lines = [`Updated proposal — ₹${data.budget.toLocaleString('en-IN')}`];
    if (data.usageRightsAddOns.length) {
      lines.push(`Add-ons: ${data.usageRightsAddOns.join(', ')}`);
    }
    if (data.customClauses.trim()) lines.push(`Additional terms: ${data.customClauses.trim()}`);
    return lines.join('\n');
  };

  /**
   * Sends the deal-room proposal as a COUNTER, not a new deal.
   *
   * By the time a deal room exists the Collaboration already exists, so `POST /deals` would 409
   * `COLLABORATION_EXISTS` — `POST /deals/:id/counter` is the correct verb here (it's the same
   * endpoint the counter modal on brand-campaign-detail uses). Until 2026-07-26 this handler was
   * a `setTimeout(1500)` stub with a comment reading "In real app: ... call API": the brand
   * filled in five steps, waited, and the modal closed having sent nothing at all.
   */
  const handleSendProposal = async (data: ProposalFormData) => {
    if (!selectedDeal) return;

    if (!isApiLive()) {
      // Demo mode keeps the original simulated delay — there is no server to counter against.
      setIsSubmittingProposal(true);
      await new Promise((resolve) => setTimeout(resolve, 1500));
      setShowProposalForm(false);
      setIsSubmittingProposal(false);
      return;
    }

    const dealId = selectedDeal.id;
    setIsSubmittingProposal(true);
    try {
      await api.deals.counter(
        dealId,
        {
          amount: data.budget,
          message: composeProposalMessage(data),
          // Local shape is {id, type, count}; DealDtos.DeliverableSlot expects {type, qty}.
          deliverables: data.deliverables
            .filter((d) => d.type && d.count > 0)
            .map((d) => ({ type: d.type, qty: d.count })),
          deadline: data.deadline || undefined,
          // '6-months' → '6 months', matching the human-readable form createProposal persists.
          usageRights: data.usageRightsDuration.replace(/-/g, ' '),
        },
        'brand',
        // Fresh key per submit — without it the server derives one from dealId + amount, so
        // re-proposing at the same figure is swallowed as a replay (same fix as
        // brand-campaign-detail.tsx).
        `${dealId}-proposal-${Date.now()}`,
      );
      setShowProposalForm(false);
      // Pull the new proposal message into the thread the brand is looking at.
      await loadMessages(dealId);
      toast({
        title: 'Proposal sent',
        description: `${selectedDeal.creatorName} received your updated terms.`,
      });
    } catch (e) {
      const message =
        e instanceof ApiError && e.code === 'DEAL_NOT_NEGOTIABLE'
          ? 'This deal has moved past negotiation — terms can no longer be changed here.'
          : e instanceof ApiError
            ? e.message
            : 'Could not send the proposal. Try again.';
      toast({ title: 'Proposal failed', description: message, variant: 'destructive' });
    } finally {
      setIsSubmittingProposal(false);
    }
  };
  
  const filteredDeals = dealRooms.filter(deal =>
    deal.creatorName.toLowerCase().includes(searchQuery.toLowerCase()) ||
    deal.campaignName.toLowerCase().includes(searchQuery.toLowerCase())
  );

  const statusConfig = selectedDeal ? dealStatusConfig[selectedDeal.dealStatus as keyof typeof dealStatusConfig] : null;

  const liveApiMode = isApiLive();

  // FE-2/FE-3 (brand mirror): live mode reads the REAL deal.contractId /
  // deal.contractStatus / deal.escrowFunded (DealDtos.java:38-40) — never the
  // CTR-2024-<dealid> / contractStatusByDeal demo fabrication. Coarse status
  // (mapDealApiContractStatus) renders immediately from the deal-list row;
  // once the full contract loads (liveContract, with real
  // brandSignedAt/creatorSignedAt) mapApiContractToDealStatus takes over.
  const contractStatus = liveApiMode
    ? selectedDeal?.contractId
      ? liveContract
        ? mapApiContractToDealStatus(liveContract, selectedDeal.escrowFunded)
        : mapDealApiContractStatus(selectedDeal.contractStatus, selectedDeal.escrowFunded)
      : undefined
    : selectedDeal
      ? contractStatusByDeal[selectedDeal.id]
      : undefined;
  const contractId = liveApiMode
    ? selectedDeal?.contractId
    : selectedDeal
      ? CONTRACT_IDS[selectedDeal.id]
      : undefined;
  const hasContract = liveApiMode
    ? Boolean(selectedDeal?.contractId)
    : Boolean(
        selectedDeal &&
          (contractStatus !== undefined ||
            ['contracted', 'in_progress', 'review', 'completed'].includes(selectedDeal.dealStatus)),
      );
  // FE-4: show "Review & send contract" only once terms are agreed and no
  // contract exists yet — gated on the real, unbucketed collaboration status
  // (architecture doc §2), not the coarse dealStatus bucket which also
  // covers CONTRACT_PENDING/CONTRACTED.
  const canGenerateContract = Boolean(
    liveApiMode && selectedDeal && !selectedDeal.contractId && selectedDeal.rawStatus === 'TERMS_AGREED',
  );

  /**
   * Mirrors `Collaboration.canCounter()` (= `canAccept()`) so the "Send Proposal" control only
   * appears while the server would actually accept a counter. In demo mode there is no
   * rawStatus to read and no server to reject, so the form stays available.
   */
  const canSendProposal =
    !liveApiMode ||
    (selectedDeal
      ? ['INVITED', 'APPLIED', 'SHORTLISTED', 'IN_NEGOTIATION'].includes(
          selectedDeal.rawStatus ?? '',
        )
      : false);

  const dealPhase = selectedDeal
    ? getDealPhase(selectedDeal.dealStatus, contractStatus)
    : 'negotiate';
  const deliverableItems = selectedDeal
    ? getDeliverablesForDeal(selectedDeal.id).map((item) => ({
        ...item,
        status: deliverableStatuses[item.id] ?? item.status,
      }))
    : [];

  return (
    <div className="flex h-[calc(100vh-3.5rem)]">
      {/* Deal Rooms List */}
      <div className="w-80 border-r border-border flex flex-col bg-muted/30">
        <div className="p-4 border-b border-border">
          <h1 className="text-lg font-semibold mb-3">Deal Room</h1>
          <div className="relative">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
            <Input
              placeholder="Search deals..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="pl-9 h-9"
            />
          </div>
        </div>
        
        <ScrollArea className="flex-1">
          <div className="p-2">
            {isApiLive() && dealsLoading && (
              <div className="flex items-center justify-center py-12">
                <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
              </div>
            )}
            {isApiLive() && !dealsLoading && dealsError && (
              <div className="p-6 text-center">
                <AlertCircle className="h-8 w-8 text-destructive-foreground mx-auto mb-2" />
                <p className="text-sm text-destructive-foreground">{dealsError}</p>
              </div>
            )}
            {isApiLive() && !dealsLoading && !dealsError && filteredDeals.length === 0 && (
              <div className="p-6 text-center">
                <MessageCircle className="h-8 w-8 text-muted-foreground mx-auto mb-2" />
                <p className="text-sm text-muted-foreground">No deal rooms found</p>
              </div>
            )}
            {(!isApiLive() || (!dealsLoading && !dealsError)) && filteredDeals.map((deal) => {
              const status = dealStatusConfig[deal.dealStatus as keyof typeof dealStatusConfig];
              return (
                <button
                  key={deal.id}
                  onClick={() => selectDeal(deal)}
                  className={cn(
                    'w-full flex flex-col gap-2 p-3 rounded-lg text-left transition-colors mb-1',
                    selectedDeal?.id === deal.id
                      ? 'bg-primary/10 border border-primary/20'
                      : 'hover:bg-muted border border-transparent'
                  )}
                >
                  <div className="flex items-start gap-3">
                    <Avatar className="h-10 w-10 shrink-0">
                      <AvatarImage src={deal.creatorAvatar} />
                      <AvatarFallback className="bg-gradient-to-br from-violet-100 to-purple-100 text-stage-contracted-fg font-semibold text-sm">
                        {deal.creatorName.split(' ').map(n => n[0]).join('')}
                      </AvatarFallback>
                    </Avatar>
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center justify-between gap-2">
                        <span className="font-medium text-sm truncate">{deal.creatorName}</span>
                        <span className="text-xs text-muted-foreground shrink-0">
                          {formatTime(deal.lastMessageTime)}
                        </span>
                      </div>
                      <p className="text-xs text-muted-foreground truncate">{deal.campaignName}</p>
                    </div>
                    {deal.unreadCount > 0 && (
                      <Badge className="bg-primary text-primary-foreground h-5 w-5 p-0 flex items-center justify-center text-xs shrink-0">
                        {deal.unreadCount}
                      </Badge>
                    )}
                  </div>
                  
                  {/* Deal Status & Progress */}
                  <div className="flex items-center gap-2">
                    <Badge className={cn('text-xs', status.color)}>
                      {status.label}
                    </Badge>
                    <span className="text-xs font-medium text-stage-approved-fg">{formatINR(deal.dealValue)}</span>
                  </div>
                  
                  {deal.progress > 0 && (
                    <div className="space-y-1">
                      <div className="flex items-center justify-between text-xs">
                        <span className="text-muted-foreground">Progress</span>
                        <span>{deal.deliverablesDone}/{deal.deliverablesTotal} deliverables</span>
                      </div>
                      <Progress value={deal.progress} className="h-1.5" />
                    </div>
                  )}
                </button>
              );
            })}
          </div>
        </ScrollArea>
      </div>

      {/* Deal Room Chat Area */}
      {selectedDeal ? (
        <div className="flex-1 flex flex-col">
          {/* Header with Deal Info */}
          <div className="border-b border-border p-4">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <Avatar className="h-12 w-12">
                  <AvatarImage src={selectedDeal.creatorAvatar} />
                  <AvatarFallback className="bg-gradient-to-br from-violet-100 to-purple-100 text-stage-contracted-fg font-semibold">
                    {selectedDeal.creatorName.split(' ').map(n => n[0]).join('')}
                  </AvatarFallback>
                </Avatar>
                <div>
                  <div className="flex items-center gap-2">
                    <h2 className="font-semibold">{selectedDeal.creatorName}</h2>
                    {statusConfig && (
                      <Badge className={cn('text-xs', statusConfig.color)}>
                        {statusConfig.label}
                      </Badge>
                    )}
                  </div>
                  <p className="text-sm text-muted-foreground">{selectedDeal.campaignName}</p>
                </div>
              </div>
              
              <div className="flex items-center gap-4">
                {/* Deal Summary */}
                <div className="hidden md:flex items-center gap-6 text-sm">
                  <div className="flex items-center gap-1.5">
                    <IndianRupee className="h-4 w-4 text-stage-approved-fg" />
                    <span className="font-semibold text-stage-approved-fg">{formatINR(selectedDeal.dealValue)}</span>
                  </div>
                  {selectedDeal.nextDeadline && (
                    <div className="flex items-center gap-1.5 text-muted-foreground">
                      <Calendar className="h-4 w-4" />
                      <span>Due {formatDate(selectedDeal.nextDeadline)}</span>
                    </div>
                  )}
                  <div className="flex items-center gap-1.5 text-muted-foreground">
                    <CheckCircle2 className="h-4 w-4" />
                    <span>{selectedDeal.deliverablesDone}/{selectedDeal.deliverablesTotal} done</span>
                  </div>
                </div>
                
                <div className="flex items-center gap-2">
                  {selectedDeal.dealStatus === 'in_progress' && shippingAddress && !shipment && (
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => setShowShipmentForm(true)}
                      className="gap-1.5"
                    >
                      <Truck className="h-4 w-4" />
                      <span className="hidden sm:inline">Ship Product</span>
                    </Button>
                  )}
                  {/* Counter is only legal in INVITED/APPLIED/SHORTLISTED/IN_NEGOTIATION
                      (Collaboration.canCounter). Ungated, this button opened a five-step form on
                      a contracted or completed deal that could only ever end in a 409. */}
                  {canSendProposal && (
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => setShowProposalForm(true)}
                    className="gap-1.5"
                  >
                    <Plus className="h-4 w-4" />
                    <span className="hidden sm:inline">Send Proposal</span>
                  </Button>
                  )}
                  <Button variant="ghost" size="icon" className="h-9 w-9">
                    <MoreVertical className="h-4 w-4" />
                  </Button>
                </div>
              </div>
            </div>
            
            {/* Progress Bar */}
            {selectedDeal.progress > 0 && (
              <div className="mt-3">
                <Progress value={selectedDeal.progress} className="h-2" />
              </div>
            )}
            <DealRoomStepProgress
              className="mt-4"
              currentPhase={dealPhase}
              onPhaseClick={(phase) => {
                const panel = phaseToPanel[phase];
                if (panel) openTool(panel);
              }}
            />

            {/* Tools toolbar — open structured panels as needed */}
            <div className="mt-3 flex flex-wrap items-center gap-2">
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
              {canGenerateContract && (
                <Button
                  size="sm"
                  onClick={() => openTool('contract')}
                  className="h-7 gap-1.5 text-xs"
                >
                  <FileSignature className="h-3.5 w-3.5" />
                  Review &amp; send contract
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
          </div>

          {/* Always-visible chat feed — message events, proposals, contracts, deliverables, payments all render inline */}
          <div className="flex-1 flex flex-col min-h-0 overflow-hidden">
          <ScrollArea className="flex-1 p-4">
            <div className="space-y-4 max-w-3xl mx-auto">
              {/* LIVE MODE (B-1): real messages from GET /deals/:id/messages. */}
              {isApiLive() && (
                <>
                  {messagesLoading && (
                    <div className="flex items-center justify-center py-8 text-sm text-muted-foreground">
                      <Clock className="h-4 w-4 mr-2 animate-spin" /> Loading messages…
                    </div>
                  )}
                  {messagesError && !messagesLoading && (
                    <div className="rounded-lg border border-destructive-foreground/30 bg-destructive/5 p-4 text-center">
                      <AlertCircle className="h-5 w-5 mx-auto mb-2 text-destructive-foreground" />
                      <p className="text-sm text-foreground">{messagesError}</p>
                      {selectedDeal && (
                        <Button
                          variant="outline"
                          size="sm"
                          className="mt-2"
                          onClick={() => loadMessages(selectedDeal.id)}
                        >
                          Retry
                        </Button>
                      )}
                    </div>
                  )}
                  {!messagesLoading && !messagesError && liveMessages.length === 0 && (
                    <div className="py-8 text-center text-sm text-muted-foreground">
                      No messages yet. Start the conversation below.
                    </div>
                  )}
                  {!messagesLoading &&
                    !messagesError &&
                    liveMessages.map((m) => {
                      const isOwn = m.senderType === 'brand';
                      return (
                        <div key={m.id} className={cn('flex', isOwn ? 'justify-end' : 'justify-start')}>
                          <div className={cn('flex gap-2 max-w-[70%]', isOwn && 'flex-row-reverse')}>
                            <div>
                              <div
                                className={cn(
                                  'rounded-2xl px-4 py-2.5',
                                  isOwn
                                    ? 'bg-primary text-primary-foreground rounded-br-md'
                                    : 'bg-muted rounded-bl-md',
                                )}
                              >
                                <p className="text-sm whitespace-pre-wrap">{m.content}</p>
                              </div>
                              <div className={cn('flex items-center gap-1 mt-1', isOwn ? 'justify-end' : 'justify-start')}>
                                <span className="text-xs text-muted-foreground">
                                  {formatTime(new Date(m.createdAt))}
                                </span>
                              </div>
                            </div>
                          </div>
                        </div>
                      );
                    })}
                </>
              )}

              {/* DEMO MODE: scripted mock timeline (no live-endpoint equivalent for
                  the inline proposal/contract/deliverable/payment cards — honest gap). */}
              {!isApiLive() && (
              <>
              {mockTimelineEvents.map((event) => {
                // Regular Message
                if (event.type === 'message') {
                  const isOwn = event.sender === 'brand';
                  return (
                    <div
                      key={event.id}
                      className={cn('flex', isOwn ? 'justify-end' : 'justify-start')}
                    >
                      <div className={cn('flex gap-2 max-w-[70%]', isOwn && 'flex-row-reverse')}>
                        {!isOwn && (
                          <Avatar className="h-8 w-8 shrink-0">
                            <AvatarFallback className="text-xs bg-gradient-to-br from-violet-100 to-purple-100 text-stage-contracted-fg">
                              {event.senderName?.split(' ').map(n => n[0]).join('')}
                            </AvatarFallback>
                          </Avatar>
                        )}
                        <div>
                          <div
                            className={cn(
                              'rounded-2xl px-4 py-2.5',
                              isOwn
                                ? 'bg-primary text-primary-foreground rounded-br-md'
                                : 'bg-muted rounded-bl-md'
                            )}
                          >
                            <p className="text-sm">{event.content}</p>
                          </div>
                          <div className={cn('flex items-center gap-1 mt-1', isOwn && 'justify-end')}>
                            <span className="text-xs text-muted-foreground">
                              {formatTime(event.timestamp)}
                            </span>
                            {isOwn && (
                              event.status === 'read' ? (
                                <CheckCheck className="h-3.5 w-3.5 text-primary" />
                              ) : (
                                <Check className="h-3.5 w-3.5 text-muted-foreground" />
                              )
                            )}
                          </div>
                        </div>
                      </div>
                    </div>
                  );
                }

                // Proposal Card
                if (event.type === 'proposal') {
                  const isOwn = event.sender === 'brand';
                  const isAccepted = event.data?.status === 'accepted';
                  const isCountered = event.data?.status === 'countered';
                  
                  return (
                    <div key={event.id} className={cn('flex', isOwn ? 'justify-end' : 'justify-start')}>
                      <Card className={cn(
                        'w-full max-w-md',
                        isAccepted ? 'border-stage-approved-border bg-stage-approved' : 
                        isCountered ? 'border-stage-negotiating-border bg-amber-50/50' :
                        'border-stage-contracted-border bg-stage-contracted'
                      )}>
                        <CardContent className="p-4">
                          <div className="flex items-center gap-2 mb-3">
                            <div className={cn(
                              'h-8 w-8 rounded-full flex items-center justify-center',
                              isAccepted ? 'bg-stage-approved' : 'bg-stage-contracted'
                            )}>
                              <FileText className={cn('h-4 w-4', isAccepted ? 'text-stage-approved-fg' : 'text-stage-contracted-fg')} />
                            </div>
                            <div className="flex-1">
                              <p className="font-medium text-sm">
                                {isOwn ? 'Your Proposal' : 'Counter Proposal'}
                              </p>
                              <p className="text-xs text-muted-foreground">{formatTime(event.timestamp)}</p>
                            </div>
                            <Badge className={cn(
                              isAccepted ? 'bg-stage-approved text-stage-approved-fg' : 
                              isCountered ? 'bg-stage-negotiating text-stage-negotiating-fg' :
                              'bg-stage-contracted text-stage-contracted-fg'
                            )}>
                              {isAccepted ? 'Accepted' : isCountered ? 'Countered' : 'Pending'}
                            </Badge>
                          </div>
                          
                          <div className="space-y-2 text-sm">
                            <div className="flex justify-between">
                              <span className="text-muted-foreground">Amount</span>
                              <span className="font-semibold text-stage-approved-fg">{formatINR(event.data?.amount || 0)}</span>
                            </div>
                            <div className="flex justify-between">
                              <span className="text-muted-foreground">Deliverables</span>
                              <span>{event.data?.deliverables?.length} items</span>
                            </div>
                            <div className="flex justify-between">
                              <span className="text-muted-foreground">Usage Rights</span>
                              <span>{event.data?.usageRights ?? 'Not specified'}</span>
                            </div>
                          </div>
                          
                          {!isAccepted && !isCountered && (
                            <div className="flex gap-2 mt-3">
                              <Button size="sm" className="flex-1 h-8 text-xs bg-stage-approved-fg hover:opacity-90 text-white">
                                Accept
                              </Button>
                              <Button size="sm" variant="outline" className="flex-1 h-8 text-xs">
                                Counter
                              </Button>
                            </div>
                          )}
                        </CardContent>
                      </Card>
                    </div>
                  );
                }

                // Contract Card
                if (event.type === 'contract') {
                  return (
                    <div key={event.id} className="flex justify-center">
                      <Card className="w-full max-w-md border-stage-approved-border bg-stage-approved">
                        <CardContent className="p-4">
                          <div className="flex items-center gap-2 mb-4">
                            <div className="h-10 w-10 rounded-full bg-stage-approved flex items-center justify-center">
                              <FileText className="h-5 w-5 text-stage-approved-fg" />
                            </div>
                            <div className="flex-1">
                              <p className="font-semibold">Contract Signed</p>
                              <p className="text-xs text-muted-foreground">{event.data?.contractId}</p>
                            </div>
                            <Badge className="bg-stage-approved text-stage-approved-fg">Active</Badge>
                          </div>
                          
                          <div className="space-y-3">
                            {/* Signature Status */}
                            <div className="flex items-center gap-3">
                              <div className="flex items-center gap-1.5">
                                <CheckCircle2 className="h-4 w-4 text-stage-approved-fg" />
                                <span className="text-xs">Brand Signed</span>
                              </div>
                              <div className="flex items-center gap-1.5">
                                <CheckCircle2 className="h-4 w-4 text-stage-approved-fg" />
                                <span className="text-xs">Creator Signed</span>
                              </div>
                            </div>
                            
                            <div className="flex justify-between text-sm p-2 bg-white/50 rounded-lg">
                              <span className="text-muted-foreground">Contract Value</span>
                              <span className="font-bold text-stage-approved-fg">{formatINR(event.data?.amount || 0)}</span>
                            </div>
                            
                            <Button 
                              variant="outline" 
                              size="sm" 
                              className="w-full"
                              onClick={openContractTab}
                            >
                              <Eye className="h-4 w-4 mr-2" />
                              View Full Contract
                            </Button>
                          </div>
                        </CardContent>
                      </Card>
                    </div>
                  );
                }

                // Payment Event
                if (event.type === 'payment') {
                  return (
                    <div key={event.id} className="flex justify-center">
                      <div className="flex items-center gap-2 px-4 py-2 rounded-full bg-stage-approved text-stage-approved-fg text-sm">
                        <Shield className="h-4 w-4" />
                        <span>{event.data?.description}</span>
                        <span className="font-semibold">{formatINR(event.data?.amount || 0)}</span>
                      </div>
                    </div>
                  );
                }

                // Deliverable Card
                if (event.type === 'deliverable') {
                  const isApproved = event.data?.status === 'approved';
                  const isPending = event.data?.status === 'pending_review';
                  
                  return (
                    <div key={event.id} className="flex justify-start">
                      <div className="flex gap-2 max-w-[80%]">
                        <Avatar className="h-8 w-8 shrink-0">
                          <AvatarFallback className="text-xs bg-gradient-to-br from-violet-100 to-purple-100 text-stage-contracted-fg">
                            {selectedDeal.creatorName.split(' ').map(n => n[0]).join('')}
                          </AvatarFallback>
                        </Avatar>
                        <Card className={cn(
                          'flex-1',
                          isApproved ? 'border-stage-approved-border bg-stage-approved' : 'border-stage-outreach-border bg-blue-50/50'
                        )}>
                          <CardContent className="p-3">
                            <div className="flex items-start gap-3">
                              <div className={cn(
                                'h-12 w-12 rounded-lg flex items-center justify-center shrink-0',
                                isApproved ? 'bg-stage-approved' : 'bg-stage-outreach'
                              )}>
                                {event.data?.type === 'video' ? (
                                  <Video className={cn('h-6 w-6', isApproved ? 'text-stage-approved-fg' : 'text-stage-outreach-fg')} />
                                ) : (
                                  <ImageIcon className={cn('h-6 w-6', isApproved ? 'text-stage-approved-fg' : 'text-stage-outreach-fg')} />
                                )}
                              </div>
                              <div className="flex-1 min-w-0">
                                <div className="flex items-center gap-2">
                                  <p className="font-medium text-sm truncate">{event.data?.title}</p>
                                  {isApproved && (
                                    <Badge className="bg-stage-approved text-stage-approved-fg text-xs shrink-0">Approved</Badge>
                                  )}
                                  {isPending && (
                                    <Badge className="bg-stage-negotiating text-stage-negotiating-fg text-xs shrink-0">Pending Review</Badge>
                                  )}
                                </div>
                                <p className="text-xs text-muted-foreground mt-0.5">
                                  Submitted {formatTime(event.data?.submittedAt || event.timestamp)}
                                </p>
                              </div>
                            </div>
                            
                            {isPending && (
                              <div className="flex gap-2 mt-3">
                                <Button size="sm" className="h-8 text-xs bg-stage-approved-fg hover:opacity-90 text-white">
                                  <CheckCircle2 className="h-3.5 w-3.5 mr-1" />
                                  Approve
                                </Button>
                                <Button size="sm" variant="outline" className="h-8 text-xs">
                                  <Pen className="h-3.5 w-3.5 mr-1" />
                                  Request Changes
                                </Button>
                              </div>
                            )}
                          </CardContent>
                        </Card>
                      </div>
                    </div>
                  );
                }

                return null;
              })}

              {/* Phase 5: Shipping Address received notice */}
              {selectedDeal.dealStatus === 'in_progress' && shippingAddress && !shipment && (
                <div className="flex justify-center">
                  <div className="w-full max-w-md border-2 border-dashed border-stage-outreach-border bg-stage-outreach rounded-lg p-4">
                    <div className="flex items-start gap-3">
                      <MapPin className="h-5 w-5 text-stage-outreach-fg mt-0.5" />
                      <div className="flex-1">
                        <p className="font-medium text-sm mb-1">Shipping address received</p>
                        <p className="text-xs text-muted-foreground mb-1">
                          {shippingAddress.fullName} · {shippingAddress.phone}
                        </p>
                        <p className="text-xs text-muted-foreground mb-3">
                          {shippingAddress.addressLine1}
                          {shippingAddress.addressLine2 ? `, ${shippingAddress.addressLine2}` : ''}
                          , {shippingAddress.city} - {shippingAddress.pincode}
                        </p>
                        <Button size="sm" onClick={() => setShowShipmentForm(true)} className="gap-1.5">
                          <Truck className="h-3.5 w-3.5" /> Ship Product
                        </Button>
                      </div>
                    </div>
                  </div>
                </div>
              )}

              {/* Phase 5: Shipment Card */}
              {shipment && (
                <ShipmentCard
                  perspective="brand"
                  status={shipment.status}
                  items={shipment.items}
                  courier={shipment.courier}
                  trackingNumber={shipment.trackingNumber}
                  trackingUrl={shipment.trackingUrl}
                  estimatedDelivery={shipment.estimatedDelivery}
                  notes={shipment.notes}
                  onUpdateTracking={() =>
                    setShipment((prev) =>
                      prev ? { ...prev, status: prev.status === 'created' ? 'in_transit' : 'delivered' } : prev,
                    )
                  }
                />
              )}

              {/* Newly sent messages appended live */}
              {chatMessages
                .filter((m) => m.sender === 'brand')
                .map((m) => (
                  <div key={m.id} className="flex justify-end">
                    <div className="flex gap-2 max-w-[70%] flex-row-reverse">
                      <div>
                        <div className="rounded-2xl px-4 py-2.5 bg-primary text-primary-foreground rounded-br-md">
                          <p className="text-sm">{m.content}</p>
                        </div>
                        <div className="flex items-center gap-1 mt-1 justify-end">
                          <span className="text-xs text-muted-foreground">
                            {formatTime(m.timestamp)}
                          </span>
                        </div>
                      </div>
                    </div>
                  </div>
                ))}
              </>
              )}

              <div ref={messagesEndRef} />
            </div>
          </ScrollArea>

          {/* Message Input */}
          <div className="border-t border-border p-4">
            <div className="max-w-3xl mx-auto flex items-end gap-2">
              <Button variant="ghost" size="icon" className="h-10 w-10 shrink-0">
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
                className="h-10 w-10 shrink-0"
                size="icon"
              >
                <Send className="h-5 w-5" />
              </Button>
            </div>
          </div>
          </div>

          {/* Tools panel — slides in from the right when the user clicks a phase
              chip or one of the toolbar buttons. Hosts the structured contract,
              deliverables, and payments views that used to live in tabs. */}
          <Sheet
            open={openPanel !== null}
            onOpenChange={(open) => !open && setOpenPanel(null)}
          >
            <SheetContent side="right" className="w-full sm:max-w-xl p-0">
              <SheetHeader className="border-b border-border px-5 py-4">
                <SheetTitle className="text-base">
                  {openPanel === 'contract' && 'Contract'}
                  {openPanel === 'deliverables' && 'Deliverables'}
                  {openPanel === 'payments' && 'Payments'}
                </SheetTitle>
              </SheetHeader>
              <div className="h-[calc(100vh-4rem)] overflow-y-auto">
                {openPanel === 'contract' && (
                  hasContract && contractId && contractStatus ? (
                    liveApiMode && liveContractLoading && !liveContract ? (
                      <div className="flex items-center justify-center h-full p-8 text-sm text-muted-foreground">
                        <Loader2 className="h-4 w-4 mr-2 animate-spin" /> Loading contract…
                      </div>
                    ) : (
                      <DealContractTab
                        dealId={selectedDeal.id}
                        creatorName={selectedDeal.creatorName}
                        campaignName={selectedDeal.campaignName}
                        dealValue={selectedDeal.dealValue}
                        contractId={contractId}
                        status={contractStatus}
                        onStatusChange={(status) => {
                          if (liveApiMode) {
                            void fetchLiveContract();
                            void loadDealRooms();
                            return;
                          }
                          setContractStatusByDeal((prev) => ({
                            ...prev,
                            [selectedDeal.id]: status,
                          }));
                        }}
                      />
                    )
                  ) : canGenerateContract ? (
                    <DealContractGenerate
                      creatorName={selectedDeal.creatorName}
                      campaignName={selectedDeal.campaignName}
                      dealValue={selectedDeal.dealValue}
                      isGenerating={isGeneratingContract}
                      onGenerate={handleGenerateContract}
                    />
                  ) : (
                    <div className="flex items-center justify-center h-full p-8 text-center text-sm text-muted-foreground">
                      <div>
                        <FileSignature className="h-10 w-10 mx-auto mb-3 opacity-40" />
                        <p className="font-medium text-foreground">No contract yet</p>
                        <p className="mt-1">
                          {liveApiMode
                            ? 'A contract can be sent once terms are agreed.'
                            : 'Accept a proposal in the chat to generate a contract.'}
                        </p>
                        {liveApiMode && liveContractError && (
                          <p className="mt-2 text-xs text-destructive-foreground">{liveContractError}</p>
                        )}
                      </div>
                    </div>
                  )
                )}

                {openPanel === 'deliverables' && (
                  isApiLive() ? (
                    deliverablesLoading ? (
                      <div className="flex items-center justify-center h-full p-8 text-sm text-muted-foreground">
                        <Clock className="h-4 w-4 mr-2 animate-spin" /> Loading deliverables…
                      </div>
                    ) : deliverablesError ? (
                      <div className="flex items-center justify-center h-full p-8 text-center">
                        <div>
                          <AlertCircle className="h-8 w-8 mx-auto mb-3 text-destructive-foreground" />
                          <p className="text-sm text-foreground">{deliverablesError}</p>
                          {selectedDeal && (
                            <Button
                              variant="outline"
                              size="sm"
                              className="mt-2"
                              onClick={() => loadDeliverables(selectedDeal.id)}
                            >
                              Retry
                            </Button>
                          )}
                        </div>
                      </div>
                    ) : (
                      <DealDeliverablesTab
                        done={liveDeliverables.filter((i) => i.status === 'approved').length}
                        total={liveDeliverables.length}
                        dealValue={selectedDeal.dealValue}
                        items={liveDeliverables}
                        onApprove={handleApproveLive}
                        onRequestRevision={handleReviseLive}
                      />
                    )
                  ) : (
                    <DealDeliverablesTab
                      done={deliverableItems.filter((i) => i.status === 'approved').length}
                      total={selectedDeal.deliverablesTotal}
                      dealValue={selectedDeal.dealValue}
                      items={deliverableItems}
                      onApprove={(id) => handleApproveDeliverable(id, '')}
                      onRequestRevision={(id) => handleRequestRevision(id, '')}
                    />
                  )
                )}

                {openPanel === 'payments' && (
                  <DealPaymentsTab
                    dealValue={selectedDeal.dealValue}
                    contractStatus={contractStatus ?? null}
                    deliverablesDone={selectedDeal.deliverablesDone}
                    deliverablesTotal={selectedDeal.deliverablesTotal}
                  />
                )}
              </div>
            </SheetContent>
          </Sheet>
        </div>
      ) : (
        <div className="flex-1 flex items-center justify-center text-muted-foreground">
          <div className="text-center">
            <MessageCircle className="h-12 w-12 mx-auto mb-4 opacity-50" />
            <p>Select a deal room to view the conversation</p>
          </div>
        </div>
      )}

      {/* Proposal Form Modal */}
      {showProposalForm && selectedDeal && (
        <ProposalForm
          creatorName={selectedDeal.creatorName}
          onSubmit={handleSendProposal}
          onClose={() => setShowProposalForm(false)}
          isSubmitting={isSubmittingProposal}
          platformFeePercent={platformFeePercent}
        />
      )}

      {/* Phase 5: Shipment Form Modal */}
      {shippingAddress && (
        <ShipmentForm
          open={showShipmentForm}
          onOpenChange={setShowShipmentForm}
          shippingAddress={shippingAddress}
          onSubmit={handleSubmitShipment}
          isSubmitting={isSubmittingShipment}
        />
      )}
    </div>
  );
}
