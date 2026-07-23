import * as React from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { Card } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Textarea } from '@/components/ui/textarea';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Separator } from '@/components/ui/separator';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import {
  MessageCircle,
  FileText,
  Plus,
  Search,
  Calendar,
  CheckCircle2,
  Clock,
  AlertCircle,
  Send,
  Paperclip,
  ArrowRight,
  IndianRupee,
  X,
  History,
  Eye,
  Lock,
  PenTool,
  Loader2,
  XCircle,
} from 'lucide-react';
import {
  deals as dealsApi,
  messages as messagesApi,
  isApiLive,
  type Deal,
  type DealMessage,
} from '@/lib/api';

interface DealRoom {
  id: string;
  campaignName: string;
  creatorName: string;
  creatorAvatar: string;
  /** No DTO source on GET /deals — brand-side Deal carries no follower count. Never rendered today, kept for the mock shape. */
  creatorFollowers?: string;
  status: 'negotiating' | 'proposed' | 'accepted' | 'rejected';
  budget: number;
  deliverables: number;
  timeline: string;
  lastMessage: string;
  lastMessageTime: string;
  /** Total message count has no DTO source; live mode substitutes Deal.unreadCount (closest honest signal). */
  messages: number;
  /** No DTO source — Deal has no proposal-versioning concept. Only ever set in mock data. */
  proposalVersion?: number;
}

// ---------------------------------------------------------------------------
// Live-mode mapping helpers — Deal (src/lib/api.ts) -> DealRoom (this file's
// rendered shape). Fields with no DTO source are left undefined/derived
// honestly rather than fabricated (see DealRoom comments above).
// ---------------------------------------------------------------------------

function mapDealStatus(status: Deal['status']): DealRoom['status'] {
  switch (status) {
    case 'IN_NEGOTIATION':
      return 'negotiating';
    case 'INVITED':
    case 'APPLIED':
    case 'SHORTLISTED':
      return 'proposed';
    case 'CANCELLED':
    case 'DISPUTED':
      return 'rejected';
    default:
      // TERMS_AGREED, CONTRACT_PENDING, CONTRACTED, IN_PROGRESS, REVIEW_PENDING,
      // REVISION_REQUESTED, COMPLETED all read as "accepted" in this 4-state UI.
      return 'accepted';
  }
}

function formatRelativeTime(date: Date): string {
  if (Number.isNaN(date.getTime())) return '—';
  const minutes = Math.floor((Date.now() - date.getTime()) / 60000);
  if (minutes < 1) return 'just now';
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} hour${hours === 1 ? '' : 's'} ago`;
  const days = Math.floor(hours / 24);
  return `${days} day${days === 1 ? '' : 's'} ago`;
}

function formatTimeline(nextDeadline?: string): string {
  if (!nextDeadline) return 'No deadline set';
  const deadline = new Date(nextDeadline);
  if (Number.isNaN(deadline.getTime())) return 'No deadline set';
  const days = Math.ceil((deadline.getTime() - Date.now()) / (1000 * 60 * 60 * 24));
  if (days < 0) return 'Overdue';
  if (days === 0) return 'Due today';
  return `${days} day${days === 1 ? '' : 's'} left`;
}

function mapDealToRoom(deal: Deal): DealRoom {
  return {
    id: deal.id,
    campaignName: deal.campaignName,
    creatorName: deal.counterpartyName,
    creatorAvatar: deal.counterpartyAvatar || '',
    status: mapDealStatus(deal.status),
    budget: deal.dealValue,
    deliverables: deal.deliverablesTotal,
    timeline: formatTimeline(deal.nextDeadline),
    lastMessage: deal.lastMessage || 'No messages yet',
    lastMessageTime: deal.lastMessageAt ? formatRelativeTime(new Date(deal.lastMessageAt)) : '—',
    messages: deal.unreadCount,
  };
}

const mockDealRooms: DealRoom[] = [
  {
    id: '1',
    campaignName: 'Summer Collection Launch',
    creatorName: 'Priya Sharma',
    creatorAvatar: 'https://api.dicebear.com/7.x/avataaars/svg?seed=Priya',
    creatorFollowers: '2.3L',
    status: 'negotiating',
    budget: 50000,
    deliverables: 3,
    timeline: '30 days',
    lastMessage: 'I can do the reels but would need extra for stories...',
    lastMessageTime: '2 hours ago',
    messages: 12,
    proposalVersion: 2,
  },
  {
    id: '2',
    campaignName: 'Seasonal Campaign Q3',
    creatorName: 'Arjun Patel',
    creatorAvatar: 'https://api.dicebear.com/7.x/avataaars/svg?seed=Arjun',
    creatorFollowers: '5.1L',
    status: 'proposed',
    budget: 80000,
    deliverables: 5,
    timeline: '45 days',
    lastMessage: 'Sent initial proposal - awaiting review',
    lastMessageTime: '1 day ago',
    messages: 3,
    proposalVersion: 1,
  },
  {
    id: '3',
    campaignName: 'Holiday Promo 2024',
    creatorName: 'Maya Gupta',
    creatorAvatar: 'https://api.dicebear.com/7.x/avataaars/svg?seed=Maya',
    creatorFollowers: '1.8L',
    status: 'accepted',
    budget: 120000,
    deliverables: 8,
    timeline: '60 days',
    lastMessage: 'Contract signed - moving to deliverables',
    lastMessageTime: '3 days ago',
    messages: 28,
    proposalVersion: 3,
  },
];

const statusConfig = {
  negotiating: {
    label: 'Negotiating',
    color: 'bg-stage-negotiating text-stage-negotiating-fg border-stage-negotiating-border',
    icon: Clock,
  },
  proposed: {
    label: 'Proposed',
    color: 'bg-stage-outreach text-blue-700 border-stage-outreach-border',
    icon: FileText,
  },
  accepted: {
    label: 'Accepted',
    color: 'bg-stage-approved text-stage-approved-fg border-green-200',
    icon: CheckCircle2,
  },
  rejected: {
    label: 'Rejected',
    color: 'bg-red-100 text-red-700 border-red-200',
    icon: AlertCircle,
  },
};

// Deal.dealValue is typed as `number` but the live API can and does return
// null (deal not yet priced) — mirrors the guard the pipeline card's
// formatINR uses for the same field (see brand-pipeline.tsx).
const formatBudget = (amount: number | null | undefined): string => {
  if (amount == null || Number.isNaN(amount)) return 'No budget set';
  if (amount >= 100000) return `₹${(amount / 100000).toFixed(1)}L`;
  if (amount >= 1000) return `₹${(amount / 1000).toFixed(0)}K`;
  return `₹${amount}`;
};

export function DealRoomDashboard() {
  const navigate = useNavigate();
  // Deep-link support: /brand/deals/:id selects that deal in-page; the legacy
  // ?deal= query (previously emitted by the DealRedirect shim) is still honored
  // as a fallback so old chat links keep resolving.
  const { id: routeDealId } = useParams<{ id?: string }>();
  const [searchParams] = useSearchParams();
  const deepLinkDealId = routeDealId ?? searchParams.get('deal') ?? undefined;
  const [searchTerm, setSearchTerm] = React.useState('');
  const [filterStatus, setFilterStatus] = React.useState<string>('all');
  const [selectedDeal, setSelectedDeal] = React.useState<DealRoom | null>(null);
  const [activeTab, setActiveTab] = React.useState('overview');
  const [messageInput, setMessageInput] = React.useState('');
  const [showProposalDialog, setShowProposalDialog] = React.useState(false);
  const [showCounterDialog, setShowCounterDialog] = React.useState(false);
  const [showContractGeneratingDialog, setShowContractGeneratingDialog] = React.useState(false);
  const [contractGenerationStep, setContractGenerationStep] = React.useState(0);
  const [counterAmount, setCounterAmount] = React.useState('');
  const [counterMessage, setCounterMessage] = React.useState('');
  const [showRejectDialog, setShowRejectDialog] = React.useState(false);
  const [rejectReason, setRejectReason] = React.useState('');

  // Live mode (isApiLive()) state — mock rendering above stays untouched.
  const [liveDeals, setLiveDeals] = React.useState<DealRoom[]>([]);
  const [dealsLoading, setDealsLoading] = React.useState(false);
  const [dealsError, setDealsError] = React.useState<string | null>(null);
  const [actionLoading, setActionLoading] = React.useState(false);
  const [actionError, setActionError] = React.useState<string | null>(null);
  const [liveMessages, setLiveMessages] = React.useState<DealMessage[]>([]);
  const [messagesLoading, setMessagesLoading] = React.useState(false);
  const [messagesError, setMessagesError] = React.useState<string | null>(null);
  const [sendingMessage, setSendingMessage] = React.useState(false);

  const dealRooms = isApiLive() ? liveDeals : mockDealRooms;

  const loadDeals = React.useCallback(async () => {
    setDealsLoading(true);
    setDealsError(null);
    try {
      const remote = await dealsApi.list('brand');
      setLiveDeals(Array.isArray(remote) ? remote.map(mapDealToRoom) : []);
    } catch {
      setLiveDeals([]);
      setDealsError('Could not load deal rooms. Check your connection and retry.');
    } finally {
      setDealsLoading(false);
    }
  }, []);

  React.useEffect(() => {
    if (isApiLive()) void loadDeals();
  }, [loadDeals]);

  // Resolve a deep-linked deal (/brand/deals/:id or ?deal=) once the list is
  // available. Works in both mock and live mode because `dealRooms` switches
  // sources; in live mode this re-runs when `liveDeals` populates after fetch.
  React.useEffect(() => {
    if (!deepLinkDealId) return;
    if (selectedDeal?.id === deepLinkDealId) return;
    const match = dealRooms.find((deal) => deal.id === deepLinkDealId);
    if (match) setSelectedDeal(match);
  }, [deepLinkDealId, dealRooms, selectedDeal]);

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

  React.useEffect(() => {
    if (isApiLive() && selectedDeal) {
      void loadMessages(selectedDeal.id);
    }
  }, [selectedDeal, loadMessages]);

  const runContractAnimation = React.useCallback(() => {
    setShowContractGeneratingDialog(true);
    setContractGenerationStep(0);

    // Simulate contract generation steps
    const steps = [
      { delay: 800, step: 1 },   // Locking escrow
      { delay: 1200, step: 2 },  // Generating contract
      { delay: 800, step: 3 },   // Setting up signatures
      { delay: 600, step: 4 },   // Ready
    ];

    let currentDelay = 0;
    steps.forEach(({ delay, step }) => {
      currentDelay += delay;
      setTimeout(() => setContractGenerationStep(step), currentDelay);
    });

    // Navigate to contracts after all steps complete
    setTimeout(() => {
      setShowContractGeneratingDialog(false);
      navigate('/brand/contracts');
    }, currentDelay + 1000);
  }, [navigate]);

  const filteredDeals = dealRooms.filter((deal) => {
    const matchesSearch =
      deal.campaignName.toLowerCase().includes(searchTerm.toLowerCase()) ||
      deal.creatorName.toLowerCase().includes(searchTerm.toLowerCase());
    const matchesStatus = filterStatus === 'all' || deal.status === filterStatus;
    return matchesSearch && matchesStatus;
  });

  const handleSendMessage = async () => {
    if (!messageInput.trim() || !selectedDeal) return;
    const content = messageInput.trim();

    if (isApiLive()) {
      setMessageInput('');
      setMessagesError(null);
      setSendingMessage(true);
      try {
        const sent = await messagesApi.send('brand', selectedDeal.id, content);
        setLiveMessages((prev) => [...prev, sent]);
      } catch {
        setMessageInput(content); // restore so the user can retry — no silent loss
        setMessagesError('Could not send message. Try again.');
      } finally {
        setSendingMessage(false);
      }
      return;
    }

    setMessageInput('');
  };

  const handleAcceptProposal = async () => {
    if (!selectedDeal) return;
    setShowProposalDialog(false);

    if (isApiLive()) {
      setActionLoading(true);
      setActionError(null);
      try {
        await dealsApi.accept(selectedDeal.id, 'brand');
        await loadDeals();
        runContractAnimation();
      } catch {
        setActionError('Could not accept the proposal. Try again.');
      } finally {
        setActionLoading(false);
      }
      return;
    }

    runContractAnimation();
  };

  const handleSendCounter = async () => {
    if (!selectedDeal) return;

    if (isApiLive()) {
      setActionLoading(true);
      setActionError(null);
      try {
        await dealsApi.counter(
          selectedDeal.id,
          { amount: Number(counterAmount) || 0, message: counterMessage || undefined },
          'brand',
        );
        await loadDeals();
        setShowCounterDialog(false);
        setCounterAmount('');
        setCounterMessage('');
      } catch {
        setActionError('Could not send the counter offer. Try again.');
      } finally {
        setActionLoading(false);
      }
      return;
    }

    setShowCounterDialog(false);
    setCounterAmount('');
    setCounterMessage('');
  };

  const handleRejectProposal = async () => {
    if (!selectedDeal) return;

    if (isApiLive()) {
      setActionLoading(true);
      setActionError(null);
      try {
        await dealsApi.reject(selectedDeal.id, rejectReason || undefined, 'brand');
        await loadDeals();
        setShowRejectDialog(false);
        setRejectReason('');
      } catch {
        setActionError('Could not reject the proposal. Try again.');
      } finally {
        setActionLoading(false);
      }
      return;
    }

    setShowRejectDialog(false);
    setRejectReason('');
  };

  return (
    <div className="flex-1 overflow-hidden flex flex-col">
      {/* Header */}
      <div className="border-b bg-background px-6 py-4">
        <div className="flex items-center justify-between">
          <div>
            <div className="flex items-center gap-2">
              <h1 className="text-2xl font-semibold tracking-tight">Deal Rooms</h1>
              <Badge variant="secondary" className="text-xs font-normal text-muted-foreground">
                All campaigns · All offers
              </Badge>
            </div>
            <p className="text-sm text-muted-foreground mt-1">
              Every creator offer, across all campaigns — accept, counter, or reject in one place.
            </p>
          </div>
          <Button size="sm" onClick={() => navigate('/brand/discover')}>
            <Plus className="h-4 w-4 mr-2" />
            Start New Deal
          </Button>
        </div>

        {/* Search and Filter */}
        <div className="mt-4 flex gap-3">
          <div className="relative flex-1 max-w-sm">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
            <Input
              placeholder="Search deals..."
              className="pl-10"
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
            />
          </div>
          <Select value={filterStatus} onValueChange={setFilterStatus}>
            <SelectTrigger className="w-40">
              <SelectValue placeholder="Status" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">All Status</SelectItem>
              <SelectItem value="negotiating">Negotiating</SelectItem>
              <SelectItem value="proposed">Proposed</SelectItem>
              <SelectItem value="accepted">Accepted</SelectItem>
              <SelectItem value="rejected">Rejected</SelectItem>
            </SelectContent>
          </Select>
        </div>
      </div>

      {/* Main Content */}
      <div className="flex-1 flex overflow-hidden">
        {/* Deal List */}
        <div className="w-80 border-r bg-muted/30 flex-shrink-0">
          <ScrollArea className="h-full">
            <div className="p-3 space-y-2">
              {isApiLive() && dealsLoading ? (
                <div className="flex items-center justify-center py-12">
                  <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
                </div>
              ) : isApiLive() && dealsError ? (
                <Card className="p-6 text-center">
                  <AlertCircle className="h-10 w-10 text-destructive-foreground mx-auto mb-2" />
                  <p className="text-sm text-destructive-foreground">{dealsError}</p>
                </Card>
              ) : filteredDeals.length === 0 ? (
                <Card className="p-6 text-center">
                  <MessageCircle className="h-10 w-10 text-muted-foreground mx-auto mb-2" />
                  <p className="text-sm text-muted-foreground">No deals found</p>
                </Card>
              ) : (
                filteredDeals.map((deal) => {
                  const StatusIcon = statusConfig[deal.status].icon;
                  const isSelected = selectedDeal?.id === deal.id;
                  return (
                    <button
                      key={deal.id}
                      onClick={() => {
                        setSelectedDeal(deal);
                        // Keep the URL shareable/bookmarkable without adding a
                        // history entry per click.
                        navigate(`/brand/deals/${deal.id}`, { replace: true });
                      }}
                      className={`w-full text-left p-3 rounded-lg border transition-all ${
                        isSelected
                          ? 'bg-background border-primary shadow-sm'
                          : 'bg-background/50 border-transparent hover:bg-background hover:border-border'
                      }`}
                    >
                      <div className="flex items-start gap-3">
                        <Avatar className="h-10 w-10">
                          <AvatarImage src={deal.creatorAvatar} />
                          <AvatarFallback>{deal.creatorName.charAt(0)}</AvatarFallback>
                        </Avatar>
                        <div className="flex-1 min-w-0">
                          <div className="flex items-center justify-between gap-2">
                            <p className="font-medium text-sm truncate">{deal.creatorName}</p>
                            <Badge variant="outline" className={`text-xs ${statusConfig[deal.status].color}`}>
                              {statusConfig[deal.status].label}
                            </Badge>
                          </div>
                          <p className="text-xs text-muted-foreground truncate mt-0.5">
                            {deal.campaignName}
                          </p>
                          <p className="text-xs text-muted-foreground truncate mt-1">
                            {deal.lastMessage}
                          </p>
                          <div className="flex items-center justify-between mt-2">
                            <span className="text-xs text-muted-foreground">{deal.lastMessageTime}</span>
                            {deal.messages > 0 && (
                              <Badge variant="secondary" className="text-xs">
                                {deal.messages}
                              </Badge>
                            )}
                          </div>
                        </div>
                      </div>
                    </button>
                  );
                })
              )}
            </div>
          </ScrollArea>
        </div>

        {/* Deal Details */}
        <div className="flex-1 flex flex-col overflow-hidden">
          {selectedDeal ? (
            <>
              {/* Deal Header */}
              <div className="p-4 border-b flex items-center justify-between">
                <div className="flex items-center gap-4">
                  <Avatar className="h-12 w-12">
                    <AvatarImage src={selectedDeal.creatorAvatar} />
                    <AvatarFallback>{selectedDeal.creatorName.charAt(0)}</AvatarFallback>
                  </Avatar>
                  <div>
                    <div className="flex items-center gap-2">
                      <h2 className="font-semibold">{selectedDeal.creatorName}</h2>
                      <Badge variant="outline" className={statusConfig[selectedDeal.status].color}>
                        {statusConfig[selectedDeal.status].label}
                      </Badge>
                    </div>
                    <p className="text-sm text-muted-foreground">{selectedDeal.campaignName}</p>
                  </div>
                </div>
                <div className="flex items-center gap-2">
                  <Button variant="outline" size="sm" onClick={() => navigate(`/brand/creators/creator-1`)}>
                    View Profile
                  </Button>
                  <Button variant="outline" size="sm" onClick={() => setShowProposalDialog(true)}>
                    <Eye className="h-4 w-4 mr-2" />
                    View Proposal
                  </Button>
                </div>
              </div>

              {/* Tabs */}
              <Tabs value={activeTab} onValueChange={setActiveTab} className="flex-1 flex flex-col overflow-hidden">
                <TabsList className="w-full justify-start rounded-none border-b bg-transparent px-4">
                  <TabsTrigger value="overview" className="rounded-none data-[state=active]:border-b-2 data-[state=active]:border-primary">
                    Overview
                  </TabsTrigger>
                  <TabsTrigger value="messages" className="rounded-none data-[state=active]:border-b-2 data-[state=active]:border-primary">
                    Messages
                  </TabsTrigger>
                  <TabsTrigger value="history" className="rounded-none data-[state=active]:border-b-2 data-[state=active]:border-primary">
                    History
                  </TabsTrigger>
                </TabsList>

                <div className="flex-1 overflow-auto">
                  {/* Overview Tab */}
                  <TabsContent value="overview" className="m-0 p-6">
                    <div className="grid grid-cols-3 gap-4 mb-6">
                      <Card className="p-4">
                        <p className="text-sm text-muted-foreground mb-1">Budget</p>
                        <p className="text-2xl font-semibold flex items-center">
                          {formatBudget(selectedDeal.budget)}
                        </p>
                      </Card>
                      <Card className="p-4">
                        <p className="text-sm text-muted-foreground mb-1">Deliverables</p>
                        <p className="text-2xl font-semibold">{selectedDeal.deliverables}</p>
                      </Card>
                      <Card className="p-4">
                        <p className="text-sm text-muted-foreground mb-1">Timeline</p>
                        <p className="text-2xl font-semibold">{selectedDeal.timeline}</p>
                      </Card>
                    </div>

                    <Card className="p-4 mb-6">
                      <h3 className="font-medium mb-3">
                        Current Proposal{selectedDeal.proposalVersion ? ` (v${selectedDeal.proposalVersion})` : ''}
                      </h3>
                      {isApiLive() ? (
                        <p className="text-sm text-muted-foreground">
                          {selectedDeal.deliverables} deliverable{selectedDeal.deliverables === 1 ? '' : 's'} agreed
                          {' · '}Budget {formatBudget(selectedDeal.budget)}
                          {' · '}{selectedDeal.timeline}
                          {' — itemized breakdown not available yet.'}
                        </p>
                      ) : (
                        <div className="space-y-3 text-sm">
                          <div className="flex justify-between py-2 border-b">
                            <span className="text-muted-foreground">Platform Coverage</span>
                            <span>Instagram, YouTube</span>
                          </div>
                          <div className="flex justify-between py-2 border-b">
                            <span className="text-muted-foreground">Content Types</span>
                            <span>3 Reels, 5 Stories, 2 Posts</span>
                          </div>
                          <div className="flex justify-between py-2 border-b">
                            <span className="text-muted-foreground">Usage Rights</span>
                            <span>3 months</span>
                          </div>
                          <div className="flex justify-between py-2">
                            <span className="text-muted-foreground">Exclusivity</span>
                            <span>30 days</span>
                          </div>
                        </div>
                      )}
                    </Card>

                    {isApiLive() && actionError && (
                      <p className="text-sm text-destructive-foreground mb-3">{actionError}</p>
                    )}

                    <div className="flex gap-3">
                      {selectedDeal.status === 'proposed' && (
                        <>
                          <Button className="flex-1" onClick={handleAcceptProposal} disabled={actionLoading}>
                            {actionLoading ? (
                              <Loader2 className="h-4 w-4 mr-2 animate-spin" />
                            ) : (
                              <CheckCircle2 className="h-4 w-4 mr-2" />
                            )}
                            Accept Proposal
                          </Button>
                          <Button variant="outline" className="flex-1" onClick={() => setShowCounterDialog(true)} disabled={actionLoading}>
                            <ArrowRight className="h-4 w-4 mr-2" />
                            Counter Offer
                          </Button>
                          <Button
                            variant="outline"
                            className="flex-1 text-destructive-foreground hover:text-destructive-foreground"
                            onClick={() => setShowRejectDialog(true)}
                            disabled={actionLoading}
                          >
                            <XCircle className="h-4 w-4 mr-2" />
                            Reject
                          </Button>
                        </>
                      )}
                      {selectedDeal.status === 'negotiating' && (
                        <>
                          <Button className="flex-1" onClick={() => setShowProposalDialog(true)}>
                            <FileText className="h-4 w-4 mr-2" />
                            Send Proposal
                          </Button>
                          <Button variant="outline" className="flex-1">
                            <MessageCircle className="h-4 w-4 mr-2" />
                            Continue Chat
                          </Button>
                        </>
                      )}
                      {selectedDeal.status === 'accepted' && (
                        <Button className="flex-1" onClick={() => navigate('/brand/contracts')}>
                          <FileText className="h-4 w-4 mr-2" />
                          View Contract
                        </Button>
                      )}
                    </div>
                  </TabsContent>

                  {/* Messages Tab */}
                  <TabsContent value="messages" className="m-0 flex flex-col h-full">
                    <ScrollArea className="flex-1 p-4">
                      <div className="space-y-4">
                        {isApiLive() ? (
                          <>
                            {messagesLoading && (
                              <div className="flex items-center justify-center py-8 text-sm text-muted-foreground">
                                <Loader2 className="h-4 w-4 mr-2 animate-spin" /> Loading messages…
                              </div>
                            )}
                            {!messagesLoading && messagesError && (
                              <p className="text-sm text-destructive-foreground text-center py-8">{messagesError}</p>
                            )}
                            {!messagesLoading && !messagesError && liveMessages.length === 0 && (
                              <p className="text-sm text-muted-foreground text-center py-8">No messages yet.</p>
                            )}
                            {!messagesLoading && !messagesError && liveMessages.map((m) => {
                              const isFromMe = m.senderType === 'brand';
                              return (
                                <div key={m.id} className={`flex gap-3 ${isFromMe ? 'justify-end' : ''}`}>
                                  {!isFromMe && (
                                    <Avatar className="h-8 w-8">
                                      <AvatarImage src={selectedDeal.creatorAvatar} />
                                      <AvatarFallback>{selectedDeal.creatorName.charAt(0)}</AvatarFallback>
                                    </Avatar>
                                  )}
                                  <div className={`rounded-lg p-3 max-w-md ${isFromMe ? 'bg-primary/10' : 'bg-muted'}`}>
                                    <p className="text-sm">{m.content}</p>
                                    <p className="text-xs text-muted-foreground mt-1">
                                      {formatRelativeTime(new Date(m.createdAt))}
                                    </p>
                                  </div>
                                </div>
                              );
                            })}
                          </>
                        ) : (
                          <>
                            <div className="flex gap-3">
                              <Avatar className="h-8 w-8">
                                <AvatarImage src={selectedDeal.creatorAvatar} />
                                <AvatarFallback>{selectedDeal.creatorName.charAt(0)}</AvatarFallback>
                              </Avatar>
                              <div className="bg-muted rounded-lg p-3 max-w-md">
                                <p className="text-sm">Hi! I am interested in this campaign. Can we discuss the deliverables?</p>
                                <p className="text-xs text-muted-foreground mt-1">2 days ago</p>
                              </div>
                            </div>
                            <div className="flex gap-3 justify-end">
                              <div className="bg-primary/10 rounded-lg p-3 max-w-md">
                                <p className="text-sm">Sure! We are looking for 3 Instagram Reels and 5 Stories. Would that work for you?</p>
                                <p className="text-xs text-muted-foreground mt-1">2 days ago</p>
                              </div>
                            </div>
                            <div className="flex gap-3">
                              <Avatar className="h-8 w-8">
                                <AvatarImage src={selectedDeal.creatorAvatar} />
                                <AvatarFallback>{selectedDeal.creatorName.charAt(0)}</AvatarFallback>
                              </Avatar>
                              <div className="bg-muted rounded-lg p-3 max-w-md">
                                <p className="text-sm">{selectedDeal.lastMessage}</p>
                                <p className="text-xs text-muted-foreground mt-1">{selectedDeal.lastMessageTime}</p>
                              </div>
                            </div>
                          </>
                        )}
                      </div>
                    </ScrollArea>
                    <div className="p-4 border-t">
                      {isApiLive() && messagesError && (
                        <p className="text-xs text-destructive-foreground mb-2">{messagesError}</p>
                      )}
                      <div className="flex gap-2">
                        <Button variant="outline" size="icon">
                          <Paperclip className="h-4 w-4" />
                        </Button>
                        <Input
                          placeholder="Type your message..."
                          value={messageInput}
                          onChange={(e) => setMessageInput(e.target.value)}
                          onKeyDown={(e) => e.key === 'Enter' && handleSendMessage()}
                          className="flex-1"
                          disabled={sendingMessage}
                        />
                        <Button onClick={handleSendMessage} disabled={sendingMessage}>
                          {sendingMessage ? (
                            <Loader2 className="h-4 w-4 animate-spin" />
                          ) : (
                            <Send className="h-4 w-4" />
                          )}
                        </Button>
                      </div>
                    </div>
                  </TabsContent>

                  {/* History Tab */}
                  <TabsContent value="history" className="m-0 p-6">
                    {isApiLive() ? (
                      <div className="flex flex-col items-center justify-center py-16 text-center">
                        <History className="h-10 w-10 text-muted-foreground mb-3" />
                        <p className="text-sm text-muted-foreground">
                          Proposal version history isn&apos;t available yet for live deals.
                        </p>
                      </div>
                    ) : (
                      <div className="space-y-4">
                        {[
                          { version: 3, action: 'Counter proposal sent', date: '2 hours ago', amount: 55000 },
                          { version: 2, action: 'Proposal revised by creator', date: '1 day ago', amount: 60000 },
                          { version: 1, action: 'Initial proposal sent', date: '3 days ago', amount: 50000 },
                        ].map((item, i) => (
                          <div key={i} className="flex gap-4 items-start">
                            <div className="flex h-8 w-8 items-center justify-center rounded-full bg-primary/10">
                              <History className="h-4 w-4 text-primary" />
                            </div>
                            <div className="flex-1">
                              <div className="flex items-center justify-between">
                                <p className="font-medium text-sm">v{item.version} - {item.action}</p>
                                <span className="text-sm font-medium">{formatBudget(item.amount)}</span>
                              </div>
                              <p className="text-xs text-muted-foreground">{item.date}</p>
                            </div>
                          </div>
                        ))}
                      </div>
                    )}
                  </TabsContent>
                </div>
              </Tabs>
            </>
          ) : (
            <div className="flex-1 flex items-center justify-center">
              <div className="text-center">
                <MessageCircle className="h-12 w-12 text-muted-foreground mx-auto mb-3" />
                <p className="text-muted-foreground">Select a deal room to view details</p>
              </div>
            </div>
          )}
        </div>
      </div>

      {/* View Proposal Dialog */}
      <Dialog open={showProposalDialog} onOpenChange={setShowProposalDialog}>
        <DialogContent className="max-w-2xl">
          <DialogHeader>
            <DialogTitle>
              Proposal Details{selectedDeal?.proposalVersion ? ` (v${selectedDeal.proposalVersion})` : ''}
            </DialogTitle>
            <DialogDescription>
              Review the current proposal from {selectedDeal?.creatorName}
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-4 py-4">
            <div className="grid grid-cols-2 gap-4">
              <Card className="p-4">
                <p className="text-sm text-muted-foreground">Total Budget</p>
                <p className="text-xl font-semibold">{formatBudget(selectedDeal?.budget || 0)}</p>
              </Card>
              <Card className="p-4">
                <p className="text-sm text-muted-foreground">Timeline</p>
                <p className="text-xl font-semibold">{selectedDeal?.timeline}</p>
              </Card>
            </div>
            <Separator />
            {isApiLive() ? (
              <div>
                <h4 className="font-medium mb-3">Deliverables</h4>
                <p className="text-sm text-muted-foreground">
                  {selectedDeal?.deliverables ?? 0} deliverable{selectedDeal?.deliverables === 1 ? '' : 's'} total.
                  Itemized breakdown and terms aren&apos;t returned by the deals API yet.
                </p>
              </div>
            ) : (
              <>
                <div>
                  <h4 className="font-medium mb-3">Deliverables Breakdown</h4>
                  <div className="space-y-2">
                    <div className="flex justify-between py-2 border-b text-sm">
                      <span>Instagram Reels (3x)</span>
                      <span className="font-medium">₹25,000</span>
                    </div>
                    <div className="flex justify-between py-2 border-b text-sm">
                      <span>Instagram Stories (5x)</span>
                      <span className="font-medium">₹15,000</span>
                    </div>
                    <div className="flex justify-between py-2 text-sm">
                      <span>Feed Posts (2x)</span>
                      <span className="font-medium">₹10,000</span>
                    </div>
                  </div>
                </div>
                <Separator />
                <div>
                  <h4 className="font-medium mb-3">Terms</h4>
                  <ul className="text-sm space-y-2 text-muted-foreground">
                    <li>Usage Rights: 3 months from delivery</li>
                    <li>Exclusivity: 30 days (no competing brands)</li>
                    <li>Revisions: Up to 2 rounds per deliverable</li>
                    <li>Payment: 50% upfront, 50% on completion</li>
                  </ul>
                </div>
              </>
            )}
            {isApiLive() && actionError && (
              <p className="text-sm text-destructive-foreground">{actionError}</p>
            )}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setShowProposalDialog(false)} disabled={actionLoading}>
              Close
            </Button>
            {selectedDeal?.status === 'proposed' && (
              <>
                <Button
                  variant="ghost"
                  className="text-destructive-foreground hover:text-destructive-foreground"
                  onClick={() => { setShowProposalDialog(false); setShowRejectDialog(true); }}
                  disabled={actionLoading}
                >
                  Reject
                </Button>
                <Button
                  variant="outline"
                  onClick={() => { setShowProposalDialog(false); setShowCounterDialog(true); }}
                  disabled={actionLoading}
                >
                  Counter
                </Button>
                <Button onClick={handleAcceptProposal} disabled={actionLoading}>
                  {actionLoading && <Loader2 className="h-4 w-4 mr-2 animate-spin" />}
                  Accept & Create Contract
                </Button>
              </>
            )}
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Counter Offer Dialog */}
      <Dialog open={showCounterDialog} onOpenChange={setShowCounterDialog}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Send Counter Offer</DialogTitle>
            <DialogDescription>
              Propose different terms to {selectedDeal?.creatorName}
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-4 py-4">
            <div>
              <label className="text-sm font-medium">Your Offer Amount</label>
              <div className="relative mt-1">
                <IndianRupee className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
                <Input
                  placeholder="45000"
                  className="pl-10"
                  value={counterAmount}
                  onChange={(e) => setCounterAmount(e.target.value)}
                />
              </div>
              <p className="text-xs text-muted-foreground mt-1">
                Current ask: {formatBudget(selectedDeal?.budget || 0)}
              </p>
            </div>
            <div>
              <label className="text-sm font-medium">Message (Optional)</label>
              <Textarea
                placeholder="Explain your counter offer..."
                className="mt-1"
                value={counterMessage}
                onChange={(e) => setCounterMessage(e.target.value)}
                rows={3}
              />
            </div>
            {isApiLive() && actionError && (
              <p className="text-sm text-destructive-foreground">{actionError}</p>
            )}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setShowCounterDialog(false)} disabled={actionLoading}>
              Cancel
            </Button>
            <Button onClick={handleSendCounter} disabled={actionLoading}>
              {actionLoading && <Loader2 className="h-4 w-4 mr-2 animate-spin" />}
              Send Counter Offer
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Reject Proposal Dialog */}
      <Dialog open={showRejectDialog} onOpenChange={setShowRejectDialog}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <XCircle className="h-5 w-5 text-destructive-foreground" />
              Reject Proposal
            </DialogTitle>
            <DialogDescription>
              Reject {selectedDeal?.creatorName}&apos;s proposal. This action cannot be undone.
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-4 py-4">
            <div>
              <label className="text-sm font-medium">Reason (Optional)</label>
              <Textarea
                placeholder="Let them know why..."
                className="mt-1"
                value={rejectReason}
                onChange={(e) => setRejectReason(e.target.value)}
                rows={3}
              />
            </div>
            {isApiLive() && actionError && (
              <p className="text-sm text-destructive-foreground">{actionError}</p>
            )}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setShowRejectDialog(false)} disabled={actionLoading}>
              Cancel
            </Button>
            <Button variant="destructive" onClick={handleRejectProposal} disabled={actionLoading}>
              {actionLoading && <Loader2 className="h-4 w-4 mr-2 animate-spin" />}
              Reject Proposal
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Contract Generation Dialog */}
      <Dialog open={showContractGeneratingDialog} onOpenChange={() => {}}>
        <DialogContent className="sm:max-w-md" onInteractOutside={(e) => e.preventDefault()}>
          <DialogHeader>
            <DialogTitle className="text-center">Generating Contract</DialogTitle>
            <DialogDescription className="text-center">
              Creating your contract with {selectedDeal?.creatorName}
            </DialogDescription>
          </DialogHeader>
          <div className="py-8 space-y-6">
            {[
              { step: 1, label: 'Locking escrow funds', icon: Lock },
              { step: 2, label: 'Generating contract from proposal', icon: FileText },
              { step: 3, label: 'Setting up e-signature', icon: PenTool },
              { step: 4, label: 'Contract ready', icon: CheckCircle2 },
            ].map(({ step, label, icon: Icon }) => (
              <div key={step} className="flex items-center gap-4">
                <div className={`h-10 w-10 rounded-full flex items-center justify-center transition-all duration-300 ${
                  contractGenerationStep >= step 
                    ? 'bg-stage-approved text-stage-approved-fg' 
                    : contractGenerationStep === step - 1 
                      ? 'bg-primary/10 text-primary animate-pulse' 
                      : 'bg-muted text-muted-foreground'
                }`}>
                  {contractGenerationStep >= step ? (
                    <CheckCircle2 className="h-5 w-5" />
                  ) : (
                    <Icon className="h-5 w-5" />
                  )}
                </div>
                <span className={`text-sm ${
                  contractGenerationStep >= step 
                    ? 'text-foreground font-medium' 
                    : 'text-muted-foreground'
                }`}>
                  {label}
                </span>
              </div>
            ))}
          </div>
          {contractGenerationStep >= 4 && (
            <div className="text-center text-sm text-muted-foreground">
              Redirecting to contracts...
            </div>
          )}
        </DialogContent>
      </Dialog>
    </div>
  );
}
