import * as React from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Building2,
  CheckCircle2,
  Clock,
  IndianRupee,
  MessageCircle,
  Search,
  Shield,
  Sparkles,
  AlertCircle,
  ChevronRight,
  Loader2,
} from 'lucide-react';

import { CreatorLayout } from '@/components/creator/creator-layout';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { cn, formatINR } from '@/lib/utils';
import { api, type DealStatusFilter } from '@/lib/api';
import { demoHypeInvite, type HypeInvite } from '@/lib/demo-data';
import { HypeInboxCard } from '@/components/creator/hype-inbox-card';

/**
 * Unified Creator Deals page.
 *
 * Replaces three legacy pages (Inbox, Active, Deal Room list) with a single
 * surface filtered by status chips. The underlying object is the same
 * `Deal` returned by `GET /deals?role=creator&status=...`.
 *
 * Backend endpoints used:
 *   - GET    /deals?status=...            api.deals.list
 *   - POST   /deals/:id/accept            api.deals.accept
 *   - POST   /deals/:id/reject            api.deals.reject
 *   - POST   /deals/:id/counter           api.deals.counter
 *   - POST   /deals/:id/messages/read     api.messages.markRead
 */

type StatusChip = {
  id: DealStatusFilter;
  label: string;
  match: (d: DealRoom) => boolean;
};

interface DealRoom {
  id: string;
  brandId: string;
  brandName: string;
  brandLogo?: string;
  brandVerified: boolean;
  brandRating?: number;
  brandPaymentSpeed?: string;
  campaignTitle: string;
  status:
    | 'new'
    | 'negotiating'
    | 'contracted'
    | 'in_progress'
    | 'review'
    | 'completed';
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

const STATUS_CHIPS: StatusChip[] = [
  { id: 'all',         label: 'All',           match: () => true },
  { id: 'new',         label: 'New',           match: (d) => d.status === 'new' },
  { id: 'negotiating', label: 'Negotiating',   match: (d) => d.status === 'negotiating' },
  { id: 'in_progress', label: 'Active',        match: (d) => d.status === 'contracted' || d.status === 'in_progress' || d.status === 'review' },
  { id: 'completed',   label: 'Completed',     match: (d) => d.status === 'completed' },
];

// ---------------------------------------------------------------------------
// Mock data — replaced by api.deals.list() in live mode.
// Aggregates what used to be Inbox proposals + Active collaborations + Deal
// Room conversations into one shape.
// ---------------------------------------------------------------------------
export const mockDeals: DealRoom[] = [
  {
    id: 'deal-new-1',
    brandId: 'b-1',
    brandName: 'Nykaa Fashion',
    brandVerified: true,
    brandRating: 4.8,
    brandPaymentSpeed: '24h avg',
    campaignTitle: 'Summer Collection Launch',
    status: 'new',
    budget: 45000,
    deliverables: [{ type: 'REEL', count: 2 }, { type: 'STORY', count: 3 }],
    deadline: '2026-06-15',
    lastMessage: 'Hi Priya! We loved your recent fashion content…',
    receivedAt: new Date(Date.now() - 1000 * 60 * 25),
    expiresAt: new Date(Date.now() + 1000 * 60 * 60 * 24 * 7),
    unreadCount: 1,
    deliverablesDone: 0,
    deliverablesTotal: 5,
    escrowFunded: true,
  },
  {
    id: 'deal-new-2',
    brandId: 'b-2',
    brandName: 'BoAt Lifestyle',
    brandVerified: true,
    brandRating: 4.5,
    brandPaymentSpeed: '48h avg',
    campaignTitle: 'New Earbuds Launch',
    status: 'new',
    budget: 75000,
    deliverables: [{ type: 'VIDEO', count: 1 }, { type: 'POST', count: 2 }],
    deadline: '2026-06-20',
    lastMessage: 'We are launching our new premium earbuds…',
    receivedAt: new Date(Date.now() - 1000 * 60 * 60 * 2),
    unreadCount: 1,
    deliverablesDone: 0,
    deliverablesTotal: 3,
    escrowFunded: true,
  },
  {
    id: 'deal-neg-1',
    brandId: 'b-3',
    brandName: 'Sugar Cosmetics',
    brandVerified: true,
    brandRating: 4.6,
    campaignTitle: 'Festival Makeup',
    status: 'negotiating',
    budget: 40000,
    deliverables: [{ type: 'REEL', count: 2 }],
    deadline: '2026-06-25',
    lastMessage: 'How about 45k including stories?',
    lastMessageAt: new Date(Date.now() - 1000 * 60 * 45),
    unreadCount: 0,
    deliverablesDone: 0,
    deliverablesTotal: 2,
    escrowFunded: false,
  },
  {
    id: 'deal-active-1',
    brandId: 'b-4',
    brandName: 'Mamaearth',
    brandVerified: true,
    campaignTitle: 'Skincare Routine Series',
    status: 'in_progress',
    budget: 50000,
    deliverables: [{ type: 'REEL', count: 3 }],
    deadline: '2026-06-10',
    lastMessage: 'Looking forward to the first reel!',
    lastMessageAt: new Date(Date.now() - 1000 * 60 * 60 * 6),
    unreadCount: 0,
    deliverablesDone: 1,
    deliverablesTotal: 3,
    escrowFunded: true,
  },
  {
    id: 'deal-active-2',
    brandId: 'b-5',
    brandName: 'Lakme',
    brandVerified: true,
    campaignTitle: 'Lipstick Try-On',
    status: 'review',
    budget: 30000,
    deliverables: [{ type: 'REEL', count: 1 }],
    deadline: '2026-05-30',
    lastMessage: 'Submitted for review',
    lastMessageAt: new Date(Date.now() - 1000 * 60 * 60 * 4),
    unreadCount: 0,
    deliverablesDone: 1,
    deliverablesTotal: 1,
    escrowFunded: true,
  },
  {
    id: 'deal-done-1',
    brandId: 'b-6',
    brandName: 'Nykaa Fashion',
    brandVerified: true,
    campaignTitle: 'Winter Collection',
    status: 'completed',
    budget: 50000,
    deliverables: [{ type: 'REEL', count: 2 }],
    deadline: '2026-04-15',
    lastMessage: 'Payment released',
    unreadCount: 0,
    deliverablesDone: 2,
    deliverablesTotal: 2,
    escrowFunded: true,
  },
];

export default function CreatorDealsPage() {
  const navigate = useNavigate();
  const [deals, setDeals] = React.useState<DealRoom[]>(mockDeals);
  const [activeFilter, setActiveFilter] = React.useState<DealStatusFilter>('all');
  const [search, setSearch] = React.useState('');
  const [loading, setLoading] = React.useState(false);
  const [actionLoading, setActionLoading] = React.useState<string | null>(null);
  // Demo Hype invite — replaced by api-driven invites in live mode.
  const hypeInvites = React.useMemo(() => [demoHypeInvite], []);

  // Load deals from API when filter changes. Live mode swaps mock for backend.
  React.useEffect(() => {
    let cancelled = false;
    (async () => {
      setLoading(true);
      try {
        const remote = await api.deals.list('creator', activeFilter);
        // Array.isArray alone, deliberately no `.length > 0` -- a creator with genuinely zero
        // deals gets back a real empty array, which EmptyState already renders correctly.
        // Requiring non-empty treated that correct empty response the same as "API call didn't
        // return usable data," so it silently kept the mock deals forever instead (same bug
        // fixed in dashboard-page.tsx's action items/pipeline).
        if (!cancelled && Array.isArray(remote)) {
          setDeals(remote as unknown as DealRoom[]);
        }
      } catch (err) {
        console.error('Failed to load deals', err);
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [activeFilter]);

  const counts = React.useMemo(() => {
    const map = new Map<DealStatusFilter, number>();
    STATUS_CHIPS.forEach((chip) => {
      map.set(chip.id, deals.filter(chip.match).length);
    });
    return map;
  }, [deals]);

  const filtered = React.useMemo(() => {
    const chip = STATUS_CHIPS.find((c) => c.id === activeFilter) || STATUS_CHIPS[0];
    const q = search.trim().toLowerCase();
    return deals
      .filter(chip.match)
      .filter(
        (d) =>
          !q ||
          d.brandName.toLowerCase().includes(q) ||
          d.campaignTitle.toLowerCase().includes(q),
      )
      .sort((a, b) => {
        // unread + new first; then most recent activity
        const aScore = (a.unreadCount > 0 ? 1000 : 0) + (a.status === 'new' ? 500 : 0);
        const bScore = (b.unreadCount > 0 ? 1000 : 0) + (b.status === 'new' ? 500 : 0);
        if (aScore !== bScore) return bScore - aScore;
        const aTime = (a.lastMessageAt || a.receivedAt || new Date(0)).getTime();
        const bTime = (b.lastMessageAt || b.receivedAt || new Date(0)).getTime();
        return bTime - aTime;
      });
  }, [deals, activeFilter, search]);

  const newCount = deals.filter((d) => d.status === 'new').length;
  const activeCount = deals.filter(
    (d) => d.status === 'contracted' || d.status === 'in_progress' || d.status === 'review',
  ).length;
  const pendingPayout = deals
    .filter((d) => d.status === 'review' || d.status === 'in_progress')
    .reduce((sum, d) => sum + d.budget, 0);

  const openDeal = (dealId: string) => {
    navigate(`/creator/chat?deal=${dealId}`);
  };

  const handleHypeAccept = async (invite: HypeInvite) => {
    // Hype invites are flat-rate, first-come: accepting locks a slot, no negotiation.
    await api.deals.accept(invite.id);
  };

  const handleAccept = async (deal: DealRoom, e: React.MouseEvent) => {
    e.stopPropagation();
    setActionLoading(deal.id);
    try {
      await api.deals.accept(deal.id);
      setDeals((prev) =>
        prev.map((d) => (d.id === deal.id ? { ...d, status: 'negotiating' } : d)),
      );
      openDeal(deal.id);
    } finally {
      setActionLoading(null);
    }
  };

  const handleCounter = (deal: DealRoom, e: React.MouseEvent) => {
    e.stopPropagation();
    // Open deal room with counter flow — full counter UI lives there
    navigate(`/creator/chat?deal=${deal.id}&action=counter`);
  };

  const handleReject = async (deal: DealRoom, e: React.MouseEvent) => {
    e.stopPropagation();
    setActionLoading(deal.id);
    try {
      await api.deals.reject(deal.id);
      setDeals((prev) => prev.filter((d) => d.id !== deal.id));
    } finally {
      setActionLoading(null);
    }
  };

  return (
    <CreatorLayout>
      <div className="container mx-auto px-4 py-6 max-w-3xl">
        {/* Header */}
        <div className="mb-6">
          <h1 className="text-2xl font-bold">Deals</h1>
          <p className="text-muted-foreground">
            {newCount > 0 && (
              <>
                <span className="font-medium text-foreground">{newCount} new</span>
                {' · '}
              </>
            )}
            {activeCount} active
            {pendingPayout > 0 && (
              <>
                {' · '}
                <span className="font-medium text-foreground">{formatINR(pendingPayout)}</span> pending payout
              </>
            )}
          </p>
        </div>

        {/* Search + filter chips */}
        <div className="mb-5 space-y-3">
          <div className="relative">
            <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              placeholder="Search brand or campaign…"
              className="pl-9"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
            />
          </div>

          <div className="flex gap-2 overflow-x-auto pb-1">
            {STATUS_CHIPS.map((chip) => {
              const count = counts.get(chip.id) ?? 0;
              const active = activeFilter === chip.id;
              return (
                <button
                  key={chip.id}
                  type="button"
                  onClick={() => setActiveFilter(chip.id)}
                  className={cn(
                    'flex items-center gap-1.5 whitespace-nowrap rounded-full border px-3 py-1.5 text-xs font-medium transition-colors',
                    active
                      ? 'border-primary bg-primary text-primary-foreground'
                      : 'border-border bg-background text-muted-foreground hover:bg-muted',
                  )}
                >
                  {chip.label}
                  <Badge
                    variant="secondary"
                    className={cn(
                      'h-4 min-w-4 rounded-full px-1.5 text-[10px]',
                      active && 'bg-primary-foreground/20 text-primary-foreground',
                    )}
                  >
                    {count}
                  </Badge>
                </button>
              );
            })}
          </div>
        </div>

        {/* Hype invites — one-tap accept, shown alongside new proposals */}
        {(activeFilter === 'all' || activeFilter === 'new') &&
          hypeInvites
            .filter(
              (inv) =>
                !search.trim() ||
                inv.brandName.toLowerCase().includes(search.trim().toLowerCase()) ||
                inv.campaignTitle.toLowerCase().includes(search.trim().toLowerCase()),
            )
            .map((invite) => (
              <HypeInboxCard
                key={invite.id}
                invite={invite}
                onAccept={handleHypeAccept}
                className="mb-3"
              />
            ))}

        {/* List */}
        {loading ? (
          <div className="flex items-center justify-center py-12">
            <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
          </div>
        ) : filtered.length === 0 ? (
          <EmptyState filter={activeFilter} />
        ) : (
          <div className="space-y-3">
            {filtered.map((deal) => (
              <DealRow
                key={deal.id}
                deal={deal}
                actionLoading={actionLoading === deal.id}
                onOpen={() => openDeal(deal.id)}
                onAccept={(e) => handleAccept(deal, e)}
                onCounter={(e) => handleCounter(deal, e)}
                onReject={(e) => handleReject(deal, e)}
              />
            ))}
          </div>
        )}
      </div>
    </CreatorLayout>
  );
}

// ---------------------------------------------------------------------------
// Deal row — collapses Inbox card / Active card / Deal-Room list item into
// one row that adapts its CTAs to the deal status.
// ---------------------------------------------------------------------------

interface DealRowProps {
  deal: DealRoom;
  actionLoading: boolean;
  onOpen: () => void;
  onAccept: (e: React.MouseEvent) => void;
  onCounter: (e: React.MouseEvent) => void;
  onReject: (e: React.MouseEvent) => void;
}

function DealRow({ deal, actionLoading, onOpen, onAccept, onCounter, onReject }: DealRowProps) {
  const isNew = deal.status === 'new';
  const isFresh = deal.receivedAt && Date.now() - deal.receivedAt.getTime() < 1000 * 60 * 30;
  const progress = deal.deliverablesTotal
    ? Math.round((deal.deliverablesDone / deal.deliverablesTotal) * 100)
    : 0;

  return (
    <Card
      onClick={onOpen}
      className={cn(
        'group cursor-pointer transition-all hover:shadow-sm',
        deal.unreadCount > 0 && 'border-primary/30 bg-primary/[0.02]',
      )}
    >
      <CardContent className="p-4">
        <div className="flex items-start gap-3">
          <Avatar className="h-10 w-10 shrink-0">
            <AvatarImage src={deal.brandLogo} alt={deal.brandName} />
            <AvatarFallback className="bg-primary/10 text-primary text-xs font-semibold">
              {deal.brandName.split(' ').map((w) => w[0]).join('').slice(0, 2)}
            </AvatarFallback>
          </Avatar>

          <div className="flex-1 min-w-0">
            <div className="flex items-start justify-between gap-2">
              <div className="min-w-0">
                <div className="flex items-center gap-1.5">
                  <p className="font-medium truncate">{deal.brandName}</p>
                  {deal.brandVerified && (
                    <CheckCircle2 className="h-3.5 w-3.5 text-blue-500 shrink-0" />
                  )}
                </div>
                <p className="text-sm text-muted-foreground truncate">
                  {deal.campaignTitle}
                </p>
              </div>
              <div className="text-right shrink-0">
                <p className="text-sm font-semibold">{formatINR(deal.budget)}</p>
                <StatusPill status={deal.status} />
              </div>
            </div>

            {/* Trust signals (only for new proposals) */}
            {isNew && (
              <div className="mt-2 flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                {deal.escrowFunded && (
                  <span className="inline-flex items-center gap-1 text-green-700">
                    <Shield className="h-3 w-3" /> Escrow funded
                  </span>
                )}
                {deal.brandPaymentSpeed && (
                  <span className="inline-flex items-center gap-1">
                    <Clock className="h-3 w-3" /> {deal.brandPaymentSpeed}
                  </span>
                )}
                {typeof deal.brandRating === 'number' && (
                  <span>★ {deal.brandRating}</span>
                )}
                {isFresh && (
                  <span className="inline-flex items-center gap-1 font-medium text-amber-700">
                    <Sparkles className="h-3 w-3" /> Fresh
                  </span>
                )}
              </div>
            )}

            {/* Progress for active deals */}
            {(deal.status === 'in_progress' || deal.status === 'review') && (
              <div className="mt-2 flex items-center gap-2 text-xs text-muted-foreground">
                <div className="flex-1 h-1.5 rounded-full bg-muted overflow-hidden">
                  <div className="h-full bg-primary" style={{ width: `${progress}%` }} />
                </div>
                <span>{deal.deliverablesDone}/{deal.deliverablesTotal} done</span>
              </div>
            )}

            {/* Last message preview */}
            {deal.lastMessage && (
              <p className="mt-2 text-xs text-muted-foreground line-clamp-1">
                {deal.lastMessage}
              </p>
            )}

            {/* Status-specific CTAs */}
            <div className="mt-3 flex items-center gap-2">
              {isNew ? (
                <>
                  <Button
                    size="sm"
                    onClick={onAccept}
                    disabled={actionLoading}
                    className="h-8 text-xs"
                  >
                    {actionLoading ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : 'Accept'}
                  </Button>
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={onCounter}
                    disabled={actionLoading}
                    className="h-8 text-xs"
                  >
                    Counter
                  </Button>
                  <Button
                    size="sm"
                    variant="ghost"
                    onClick={onReject}
                    disabled={actionLoading}
                    className="h-8 text-xs text-muted-foreground"
                  >
                    Decline
                  </Button>
                </>
              ) : (
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={onOpen}
                  className="h-8 text-xs gap-1"
                >
                  <MessageCircle className="h-3.5 w-3.5" />
                  Open chat
                  {deal.unreadCount > 0 && (
                    <Badge className="ml-1 h-4 min-w-4 rounded-full bg-primary px-1.5 text-[10px] text-primary-foreground">
                      {deal.unreadCount}
                    </Badge>
                  )}
                  <ChevronRight className="h-3.5 w-3.5 ml-0.5" />
                </Button>
              )}
            </div>
          </div>
        </div>
      </CardContent>
    </Card>
  );
}

// ---------------------------------------------------------------------------
// Status pill — colour-codes the deal stage.
// ---------------------------------------------------------------------------
function StatusPill({ status }: { status: DealRoom['status'] }) {
  const config: Record<DealRoom['status'], { label: string; cls: string }> = {
    new:         { label: 'New',         cls: 'bg-blue-100 text-blue-700 border-blue-200' },
    negotiating: { label: 'Negotiating', cls: 'bg-amber-100 text-amber-700 border-amber-200' },
    contracted:  { label: 'Contracted',  cls: 'bg-violet-100 text-violet-700 border-violet-200' },
    in_progress: { label: 'Active',      cls: 'bg-emerald-100 text-emerald-700 border-emerald-200' },
    review:      { label: 'In review',   cls: 'bg-orange-100 text-orange-700 border-orange-200' },
    completed:   { label: 'Done',        cls: 'bg-gray-100 text-gray-600 border-gray-200' },
  };
  const c = config[status];
  return (
    <span className={cn('mt-1 inline-block rounded-full border px-2 py-0.5 text-[10px] font-medium', c.cls)}>
      {c.label}
    </span>
  );
}

// ---------------------------------------------------------------------------
// Empty state — tailored copy per filter.
// ---------------------------------------------------------------------------
function EmptyState({ filter }: { filter: DealStatusFilter }) {
  const copy: Record<DealStatusFilter, { icon: React.ComponentType<{ className?: string }>; title: string; body: string }> = {
    all:         { icon: Building2,  title: 'No deals yet',           body: 'Brands will reach out as they discover your profile. Make sure your profile is complete.' },
    new:         { icon: Sparkles,    title: 'No new proposals',       body: 'You\'ll see fresh proposals here as brands send them.' },
    negotiating: { icon: MessageCircle, title: 'Nothing in flight',    body: 'Deals you\'re negotiating will appear here.' },
    contracted:  { icon: AlertCircle, title: 'No contracts yet',       body: 'Signed contracts will show up here.' },
    in_progress: { icon: AlertCircle, title: 'Nothing active',         body: 'Deals you\'re working on will show here.' },
    review:      { icon: AlertCircle, title: 'Nothing in review',      body: 'Submitted work awaiting brand approval will appear here.' },
    completed:   { icon: CheckCircle2, title: 'No completed deals yet', body: 'Past deals and earnings will show up here.' },
  };
  const c = copy[filter];
  const Icon = c.icon;
  return (
    <div className="flex flex-col items-center justify-center py-16 text-center">
      <div className="flex h-14 w-14 items-center justify-center rounded-full bg-muted mb-3">
        <Icon className="h-7 w-7 text-muted-foreground" />
      </div>
      <p className="font-medium">{c.title}</p>
      <p className="mt-1 text-sm text-muted-foreground max-w-xs">{c.body}</p>
    </div>
  );
}
