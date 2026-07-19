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
  Building2,
  Reply,
  MessageSquare,
  FileSignature,
  Package,
  CreditCard,
  Loader2,
} from 'lucide-react';

import { cn, formatINR } from '@/lib/utils';
import { api, isApiLive, type Deal, type DealMessage, type MessageKind } from '@/lib/api';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent } from '@/components/ui/card';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Textarea } from '@/components/ui/textarea';
import { Progress } from '@/components/ui/progress';
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from '@/components/ui/dialog';
import { Label } from '@/components/ui/label';
import { CounterProposalForm, type CounterProposalFormData } from '@/components/creator/deal-room/counter-proposal-form';
import { CounterProposalCard } from '@/components/creator/deal-room/counter-proposal-card';
import { CreatorContractPanel } from '@/components/creator/deal-room/creator-contract-panel';
import { CreatorContractCard } from '@/components/creator/deal-room/creator-contract-card';
import { DeliverableSubmission, type DeliverableSubmissionData } from '@/components/creator/deal-room/deliverable-submission';
import { DeliverableCard } from '@/components/creator/deal-room/deliverable-card';
import { RevisionHandler } from '@/components/creator/deal-room/revision-handler';
import { ShippingAddressForm, type ShippingAddressData } from '@/components/creator/deal-room/shipping-address-form';
import { ReceiptConfirmation, type ReceiptData } from '@/components/creator/deal-room/receipt-confirmation';
import { ShipmentCard, type ShipmentStatus } from '@/components/shared/shipment-card';
import { MapPin, Truck } from 'lucide-react';
import type { TimelineEvent as LibTimelineEvent, TimelineEventMetadata, CollaborationStatus } from '@/lib/types';
import {
  addPersistedMessage,
  formatMessageTimestamp,
  getPersistedMessages,
} from '@/lib/creator-deal-messages';
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

// ============================================================================
// Types
// ============================================================================

interface DealRoom {
  id: string;
  brandName: string;
  brandLogo: string;
  brandInitials: string;
  campaignName: string;
  status: 'new_proposal' | 'negotiating' | 'contracted' | 'in_progress' | 'review' | 'completed';
  dealAmount: number;
  lastMessage: string;
  lastMessageTime: string;
  unreadCount: number;
  deliverablesDone: number;
  deliverablesTotal: number;
}

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

const mockDealRooms: DealRoom[] = [
  {
    id: '1',
    brandName: 'StyleCo Fashion',
    brandLogo: '',
    brandInitials: 'SC',
    campaignName: 'Summer Fashion 2024',
    status: 'in_progress',
    dealAmount: 50000,
    lastMessage: 'Great work on the first reel! Ready for the next one?',
    lastMessageTime: '2 hours ago',
    unreadCount: 2,
    deliverablesDone: 1,
    deliverablesTotal: 3,
  },
  {
    id: '2',
    brandName: 'TechGadgets Pro',
    brandLogo: '',
    brandInitials: 'TG',
    campaignName: 'Product Launch Q1',
    status: 'new_proposal',
    dealAmount: 75000,
    lastMessage: 'We loved your tech reviews! Here is our proposal...',
    lastMessageTime: '5 hours ago',
    unreadCount: 1,
    deliverablesDone: 0,
    deliverablesTotal: 2,
  },
  {
    id: '3',
    brandName: 'FitLife Nutrition',
    brandLogo: '',
    brandInitials: 'FL',
    campaignName: 'Wellness Campaign',
    status: 'negotiating',
    dealAmount: 35000,
    lastMessage: 'Can we discuss the timeline for deliverables?',
    lastMessageTime: '1 day ago',
    unreadCount: 0,
    deliverablesDone: 0,
    deliverablesTotal: 4,
  },
  {
    id: '4',
    brandName: 'BeautyGlow',
    brandLogo: '',
    brandInitials: 'BG',
    campaignName: 'Skincare Series',
    status: 'contracted',
    dealAmount: 45000,
    lastMessage: 'Contract signed! Looking forward to working together.',
    lastMessageTime: '2 days ago',
    unreadCount: 0,
    deliverablesDone: 0,
    deliverablesTotal: 2,
  },
  {
    id: '5',
    brandName: 'HomeDecor Plus',
    brandLogo: '',
    brandInitials: 'HD',
    campaignName: 'Interior Design Tips',
    status: 'review',
    dealAmount: 28000,
    lastMessage: 'Submitted the final video for review.',
    lastMessageTime: '3 days ago',
    unreadCount: 0,
    deliverablesDone: 2,
    deliverablesTotal: 2,
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

function mapDealStatusToRoomStatus(status: CollaborationStatus): DealRoom['status'] {
  switch (status) {
    case 'INVITED':
    case 'APPLIED':
    case 'SHORTLISTED':
      return 'new_proposal';
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
    case 'DISPUTED':
      return 'review';
    case 'COMPLETED':
    case 'CANCELLED':
      return 'completed';
    default:
      return 'new_proposal';
  }
}

function initialsFromName(name: string): string {
  return (name || '?')
    .split(' ')
    .filter(Boolean)
    .map((w) => w[0])
    .join('')
    .slice(0, 2)
    .toUpperCase();
}

function relativeTimeFromIso(iso?: string): string {
  if (!iso) return '';
  const diffMs = Date.now() - new Date(iso).getTime();
  const mins = Math.round(diffMs / 60000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  const hours = Math.round(mins / 60);
  if (hours < 24) return `${hours}h ago`;
  return `${Math.round(hours / 24)}d ago`;
}

/** Deal (api.deals.list) -> local DealRoom shape this page already renders. */
function mapDealToRoom(d: Deal): DealRoom {
  return {
    id: d.id,
    brandName: d.counterpartyName,
    brandLogo: d.counterpartyAvatar ?? '',
    brandInitials: initialsFromName(d.counterpartyName),
    campaignName: d.campaignName,
    status: mapDealStatusToRoomStatus(d.status),
    dealAmount: d.dealValue,
    lastMessage: d.lastMessage ?? '',
    lastMessageTime: relativeTimeFromIso(d.lastMessageAt),
    unreadCount: d.unreadCount,
    deliverablesDone: d.deliverablesDone,
    deliverablesTotal: d.deliverablesTotal,
  };
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
    new_proposal: { label: 'New Proposal', variant: 'outline' as const, className: 'border bg-stage-outreach text-stage-outreach-fg border-stage-outreach-border' },
    negotiating: { label: 'Negotiating', variant: 'outline' as const, className: 'border bg-stage-negotiating text-stage-negotiating-fg border-stage-negotiating-border' },
    contracted: { label: 'Contracted', variant: 'outline' as const, className: 'border bg-stage-contracted text-stage-contracted-fg border-stage-contracted-border' },
    in_progress: { label: 'In Progress', variant: 'outline' as const, className: 'border bg-stage-progress text-stage-progress-fg border-stage-progress-border' },
    review: { label: 'Under Review', variant: 'outline' as const, className: 'border bg-stage-review text-stage-review-fg border-stage-review-border' },
    completed: { label: 'Completed', variant: 'outline' as const, className: 'border bg-stage-approved text-stage-approved-fg border-stage-approved-border' },
  };
  const config = statusConfig[status];
  return <Badge className={config.className}>{config.label}</Badge>;
};

const calculateEarnings = (grossAmount: number) => {
  const platformFee = grossAmount * 0.10;
  const gstOnFee = platformFee * 0.18;
  const tds = grossAmount * 0.10;
  const netEarnings = grossAmount - platformFee - gstOnFee - tds;
  return { platformFee, gstOnFee, tds, netEarnings };
};

// ============================================================================
// Main Component
// ============================================================================

export default function CreatorChatPage() {
  const liveApi = isApiLive();
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
      setDealRooms(remote.map(mapDealToRoom));
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

  const selectedDeal = React.useMemo(
    () => dealRooms.find((d) => d.id === selectedDealId) ?? dealRooms[0] ?? null,
    [dealRooms, selectedDealId],
  );

  // Message thread for the selected deal — GET/POST /deals/:id/messages (api.messages.*).
  const [liveMessages, setLiveMessages] = React.useState<DealMessage[]>([]);
  const [messagesLoading, setMessagesLoading] = React.useState(false);
  const [messagesError, setMessagesError] = React.useState<string | null>(null);

  React.useEffect(() => {
    if (!liveApi || !selectedDeal) return;
    let cancelled = false;
    setMessagesLoading(true);
    setMessagesError(null);
    (async () => {
      try {
        const remote = await api.messages.list('creator', selectedDeal.id);
        if (!cancelled) setLiveMessages(remote);
      } catch (err) {
        console.error('Failed to load messages', err);
        if (!cancelled) setMessagesError('Failed to load messages for this deal.');
      } finally {
        if (!cancelled) setMessagesLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [liveApi, selectedDeal?.id]);

  // Mark the thread read once opened (mirrors creator-deals.tsx openDeal flow).
  React.useEffect(() => {
    if (!liveApi || !selectedDeal || selectedDeal.unreadCount === 0) return;
    api.messages.markRead('creator', selectedDeal.id).catch((err) => {
      console.error('Failed to mark messages read', err);
    });
  }, [liveApi, selectedDeal?.id, selectedDeal?.unreadCount]);

  const [message, setMessage] = React.useState('');
  const [messageRefreshKey, setMessageRefreshKey] = React.useState(0);
  const messagesEndRef = React.useRef<HTMLDivElement>(null);
  const [searchQuery, setSearchQuery] = React.useState('');
  const [openPanel, setOpenPanel] = React.useState<CreatorToolsPanel>(null);
  const [contractStatusByDeal, setContractStatusByDeal] =
    React.useState<Record<string, DealContractStatus>>(() => getAllContractStatuses());
  const [showContractPanel, setShowContractPanel] = React.useState(false);

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

  const openContractTab = () => openTool('contract');

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
  const [selectedDeliverableForRevision, setSelectedDeliverableForRevision] = React.useState<string | null>(null);
  const [isSubmittingDeliverable, setIsSubmittingDeliverable] = React.useState(false);

  // Phase 5: Shipping flow state
  const [showShippingAddressForm, setShowShippingAddressForm] = React.useState(false);
  const [showReceiptConfirmation, setShowReceiptConfirmation] = React.useState(false);
  const [shippingAddress, setShippingAddress] = React.useState<ShippingAddressData | null>(null);
  const [shipmentStatus, setShipmentStatus] = React.useState<ShipmentStatus>('created');
  // Demo: pretend brand has dispatched a shipment after address is given
  const [hasShipment, setHasShipment] = React.useState(true);
  const [isSavingAddress, setIsSavingAddress] = React.useState(false);
  const [isConfirmingReceipt, setIsConfirmingReceipt] = React.useState(false);

  const handleSubmitShippingAddress = async (data: ShippingAddressData) => {
    setIsSavingAddress(true);
    await new Promise((r) => setTimeout(r, 600));
    setShippingAddress(data);
    setIsSavingAddress(false);
    setShowShippingAddressForm(false);
  };

  const handleMarkReceived = () => {
    setShowReceiptConfirmation(true);
  };

  const handleConfirmReceipt = async (data: ReceiptData) => {
    setIsConfirmingReceipt(true);
    await new Promise((r) => setTimeout(r, 600));
    setShipmentStatus(data.condition === 'good' ? 'received' : 'damaged');
    setIsConfirmingReceipt(false);
    setShowReceiptConfirmation(false);
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

  const handleAcceptProposal = (proposalId: string) => {
    // In production: call API to accept proposal
  };

  const handleDeclineProposal = (proposalId: string) => {
    // In production: call API to decline proposal
  };

  const handleSendCounter = () => {
    setShowCounterDialog(false);
    setCounterAmount('');
    setCounterMessage('');
  };

  const handleSubmitCounterForm = async (data: CounterProposalFormData) => {
    setIsSubmittingCounter(true);
    // Simulate API call
    await new Promise((resolve) => setTimeout(resolve, 1500));
    setShowCounterForm(false);
    setIsSubmittingCounter(false);
    // In production: add counter proposal to timeline, update deal status
  };

  const handleSubmitDeliverable = () => {
    setShowDeliverableDialog(false);
  };

  const handleSubmitDeliverableForm = async (data: DeliverableSubmissionData) => {
    setIsSubmittingDeliverable(true);
    // Simulate API call
    await new Promise((resolve) => setTimeout(resolve, 1500));
    setShowDeliverableDialog(false);
    setIsSubmittingDeliverable(false);
    // In production: add deliverable to timeline, update deal status
  };

  const handleStartRevision = async (revisionNotes: string) => {
    setShowRevisionHandler(false);
    // In production: switch to submission form for revision
    setShowDeliverableDialog(true);
  };

  const updateContractStatus = React.useCallback((dealId: string, status: DealContractStatus) => {
    setContractStatus(dealId, status);
    setContractStatusByDeal((prev) => ({ ...prev, [dealId]: status }));
  }, []);

  const resolveDealContractStatus = (deal: DealRoom): DealContractStatus | undefined =>
    contractStatusByDeal[deal.id] ??
    getContractStatus(deal.id, deal.status);

  const enrichContractEvent = React.useCallback(
    (event: ChatTimelineEvent, deal: DealRoom): ChatTimelineEvent => {
      if (event.type !== 'contract') return event;
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
    [contractStatusByDeal],
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

  const baseEventsForDeal = !selectedDeal
    ? []
    : selectedDeal.id === '1'
      ? mockTimelineEvents
      : mockTimelineEvents.slice(0, 3);

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

  React.useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [events, openPanel]);

  // --- Guards (after all hooks, before dereferencing selectedDeal below) ---
  if (dealsLoading) {
    return (
      <CreatorLayout>
        <div className="flex h-[calc(100vh-4rem)] items-center justify-center bg-background">
          <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
        </div>
      </CreatorLayout>
    );
  }
  if (dealsError) {
    return (
      <CreatorLayout>
        <div className="flex h-[calc(100vh-4rem)] flex-col items-center justify-center gap-3 bg-background text-center">
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
        <div className="flex h-[calc(100vh-4rem)] flex-col items-center justify-center gap-2 bg-background text-center">
          <MessageCircle className="h-10 w-10 text-muted-foreground/50" />
          <p className="font-medium">No deals yet</p>
          <p className="text-sm text-muted-foreground max-w-xs">
            Brands you're talking to will show up here as deal rooms.
          </p>
        </div>
      </CreatorLayout>
    );
  }

  const contractStatus = resolveDealContractStatus(selectedDeal);
  const contractId = dealHasContract(selectedDeal.id, selectedDeal.status)
    ? resolveContractId(selectedDeal.id)
    : undefined;
  const dealPhase = getDealPhase(selectedDeal.status, contractStatus);
  const hasContract = dealHasContract(selectedDeal.id, selectedDeal.status);

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
    <div className="flex h-[calc(100vh-4rem)] bg-background">
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
                <Badge variant="outline" className="text-[10px]">
                  <Building2 className="h-3 w-3 mr-1" />
                  Verified Brand
                </Badge>
              </div>
              <p className="text-sm text-muted-foreground">{selectedDeal.campaignName}</p>
            </div>
          </div>
          
          <div className="flex items-center gap-2">
            {selectedDeal.status === 'in_progress' && !shippingAddress && (
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
            {selectedDeal.status === 'in_progress' && shippingAddress && shipmentStatus !== 'received' && (
              <Button
                variant="outline"
                size="sm"
                onClick={() => setShowShippingAddressForm(true)}
                className="gap-1.5"
              >
                <Truck className="h-4 w-4" />
                <span className="hidden sm:inline">Shipping</span>
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
        <ScrollArea className="flex-1 p-4">
          <div className="space-y-4 max-w-3xl mx-auto">
            {liveApi && messagesLoading && (
              <div className="flex justify-center py-8">
                <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
              </div>
            )}
            {liveApi && messagesError && !messagesLoading && (
              <div className="text-center py-8 text-sm text-destructive-foreground">{messagesError}</div>
            )}
            {liveApi && !messagesLoading && !messagesError && events.length === 0 && (
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
                            <span>{String(event.metadata?.usageRights)}</span>
                          </div>
                        </div>

                        {/* Earnings Breakdown for Creator */}
                        <div className="mt-3 pt-3 border-t border-stage-outreach-border">
                          <p className="text-xs text-muted-foreground mb-2">Your Earnings Breakdown</p>
                          <div className="space-y-1 text-xs">
                            <div className="flex justify-between">
                              <span className="text-muted-foreground">Gross Amount</span>
                              <span>{formatINR(Number(event.metadata?.amount))}</span>
                            </div>
                            <div className="flex justify-between text-stage-disputed-fg">
                              <span>Platform Fee (10%)</span>
                              <span>-{formatINR(Number(event.metadata?.platformFee))}</span>
                            </div>
                            <div className="flex justify-between text-stage-disputed-fg">
                              <span>GST on Fee (18%)</span>
                              <span>-{formatINR(Number(event.metadata?.gstOnFee))}</span>
                            </div>
                            <div className="flex justify-between text-stage-disputed-fg">
                              <span>TDS (10%)</span>
                              <span>-{formatINR(Number(event.metadata?.tds))}</span>
                            </div>
                            <div className="flex justify-between font-semibold text-stage-approved-fg pt-1 border-t">
                              <span>You Receive</span>
                              <span>{formatINR(Number(event.metadata?.netEarnings))}</span>
                            </div>
                          </div>
                        </div>

                        {event.metadata?.status === 'pending' && (
                          <div className="flex gap-2 mt-4">
                            <Button 
                              size="sm" 
                              className="flex-1"
                              onClick={() => handleAcceptProposal(event.id)}
                            >
                              Accept
                            </Button>
                            <Button 
                              size="sm" 
                              variant="outline" 
                              className="flex-1"
                              onClick={() => setShowCounterForm(true)}
                            >
                              Counter
                            </Button>
                            <Button 
                              size="sm" 
                              variant="ghost"
                              onClick={() => handleDeclineProposal(event.id)}
                            >
                              Decline
                            </Button>
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
                            <span>{String(event.metadata?.usageRights)}</span>
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
            {selectedDeal.status === 'in_progress' && !shippingAddress && (
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

            {/* Phase 5: Shipment Card */}
            {selectedDeal.status === 'in_progress' && shippingAddress && hasShipment && (
              <ShipmentCard
                perspective="creator"
                status={shipmentStatus}
                items={[{ name: 'Summer Dress (Floral, Size M)', quantity: 1 }, { name: 'Brand Lookbook', quantity: 1 }]}
                courier="Delhivery"
                trackingNumber="DLV789456123"
                trackingUrl="https://www.delhivery.com/track"
                estimatedDelivery={new Date(Date.now() + 3 * 24 * 60 * 60 * 1000).toISOString()}
                notes="Please unbox on camera if you can. Care: dry-clean only."
                onMarkReceived={handleMarkReceived}
              />
            )}

            {/* Demo: simulate shipment status progression */}
            {selectedDeal.status === 'in_progress' && shippingAddress && hasShipment && shipmentStatus === 'created' && (
              <div className="flex justify-center">
                <Button variant="ghost" size="sm" className="text-xs text-muted-foreground" onClick={() => setShipmentStatus('delivered')}>
                  (Demo) Simulate delivery
                </Button>
              </div>
            )}
            <div ref={messagesEndRef} />
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
                  <CreatorDealContractTab
                    contractId={contractId}
                    brandName={selectedDeal.brandName}
                    campaignName={selectedDeal.campaignName}
                    amount={selectedDeal.dealAmount}
                    status={contractStatus}
                    onStatusChange={(status) => updateContractStatus(selectedDeal.id, status)}
                  />
                ) : (
                  <div className="flex items-center justify-center h-full p-8 text-center text-sm text-muted-foreground">
                    <p>Contract will appear here once the brand sends it after negotiation.</p>
                  </div>
                )
              )}

              {openPanel === 'deliverables' && (
                <DealDeliverablesTab
                  done={selectedDeal.deliverablesDone}
                  total={selectedDeal.deliverablesTotal}
                  dealValue={selectedDeal.dealAmount}
                  items={deliverableItems}
                />
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

      {/* Contract Details Sheet */}
      {false && (
      <Sheet open={false} onOpenChange={() => {}}>
        <SheetContent className="w-full sm:max-w-lg">
          <SheetHeader>
            <SheetTitle>Contract Details</SheetTitle>
          </SheetHeader>
          <div className="mt-6 space-y-6">
            <div>
              <p className="text-sm text-muted-foreground mb-1">Contract ID</p>
              <p className="font-mono">CTR-2024-001</p>
            </div>
            <div>
              <p className="text-sm text-muted-foreground mb-1">Brand</p>
              <p className="font-medium">{selectedDeal.brandName}</p>
            </div>
            <div>
              <p className="text-sm text-muted-foreground mb-1">Campaign</p>
              <p className="font-medium">{selectedDeal.campaignName}</p>
            </div>
            <div>
              <p className="text-sm text-muted-foreground mb-1">Contract Value</p>
              <p className="text-2xl font-bold">{formatINR(selectedDeal.dealAmount)}</p>
            </div>
            <div className="pt-4 border-t">
              <p className="text-sm font-medium mb-3">Your Earnings</p>
              <div className="space-y-2 text-sm">
                {(() => {
                  const earnings = calculateEarnings(selectedDeal.dealAmount);
                  return (
                    <>
                      <div className="flex justify-between">
                        <span className="text-muted-foreground">Contract Value</span>
                        <span>{formatINR(selectedDeal.dealAmount)}</span>
                      </div>
                      <div className="flex justify-between text-stage-disputed-fg">
                        <span>Platform Fee (10%)</span>
                        <span>-{formatINR(earnings.platformFee)}</span>
                      </div>
                      <div className="flex justify-between text-stage-disputed-fg">
                        <span>GST on Fee (18%)</span>
                        <span>-{formatINR(earnings.gstOnFee)}</span>
                      </div>
                      <div className="flex justify-between text-stage-disputed-fg">
                        <span>TDS (10%)</span>
                        <span>-{formatINR(earnings.tds)}</span>
                      </div>
                      <div className="flex justify-between font-semibold text-stage-approved-fg pt-2 border-t">
                        <span>You Receive</span>
                        <span>{formatINR(earnings.netEarnings)}</span>
                      </div>
                    </>
                  );
                })()}
              </div>
            </div>
            <Button className="w-full">
              <FileText className="h-4 w-4 mr-2" />
              Download Contract PDF
            </Button>
          </div>
        </SheetContent>
      </Sheet>
      )}

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
        deliverables={[
          { id: 'reel-1', title: 'Instagram Reel #1', description: 'High-quality reel with trending audio', completed: true },
          { id: 'reel-2', title: 'Instagram Reel #2', description: 'Follow-up reel with product showcase', completed: false },
        ]}
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
