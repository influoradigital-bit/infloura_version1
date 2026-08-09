import * as React from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Plus,
  MessageSquare,
  Clock,
  CheckCircle2,
  AlertCircle,
  AlertTriangle,
  Send,
  FileCheck,
  Banknote,
  LayoutGrid,
  List,
  Calendar,
  Search,
  Instagram,
  Youtube,
  Loader2,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Badge } from '@/components/ui/badge';
import { Input } from '@/components/ui/input';
import { ScrollArea, ScrollBar } from '@/components/ui/scroll-area';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Progress } from '@/components/ui/progress';
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from '@/components/ui/tooltip';
import { cn } from '@/lib/utils';
import { deals as dealsApi, isApiLive, type Deal } from '@/lib/api';
// CR-30 — this board derives its columns from the one shared switch over CollaborationStatus
// instead of keeping a fourth private copy. See lib/brand-pipeline-stage.ts.
import {
  mapCollaborationStatusToPipelineStage,
  type BrandPipelineStage,
} from '@/lib/brand-pipeline-stage';

interface Collaboration {
  id: string;
  creatorId: string;
  creatorName: string;
  creatorAvatar: string;
  /** No DTO source on GET /deals (`DealResponse` — see DealDtos.java) — deals API carries no follower count. Left '' in live mode; PL-4. */
  creatorFollowers: string;
  campaignTitle: string;
  budget: number;
  /** No DTO source on GET /deals (`DealResponse` — see DealDtos.java) — deals API carries no platform list. Left [] in live mode; PL-4. */
  platforms: string[];
  /** CR-30 — the board's own column vocabulary. See lib/brand-pipeline-stage.ts. */
  status: BrandPipelineStage;
  slaHoursRemaining?: number; // SLA time remaining
  daysRemaining?: number;
  /** No DTO source on GET /deals — deals API carries no engagement metric. Only ever set in mock data; never rendered today. */
  engagementRate?: number;
  /** No DTO source on GET /deals — deals API carries no match-score concept. Left undefined in live mode; "Match" stat hidden rather than fabricated. */
  matchScore?: number;
  lastActivity: string;
  deliverableProgress?: number; // 0-100
}

// ---------------------------------------------------------------------------
// Live-mode mapping — Deal (src/lib/api.ts) -> Collaboration (this file's
// board/list/timeline shape). Fields with no DTO source are left undefined
// rather than fabricated (see Collaboration comments above).
// ---------------------------------------------------------------------------

/**
 * CR-30 — the board's column mapping now lives in `lib/brand-pipeline-stage.ts`, derived from
 * the shared `DealStage` instead of a fourth private switch over `CollaborationStatus`.
 *
 * ⚠️ **User-visible consequence, and it IS the fix: a `TERMS_AGREED` deal moves from the
 * Contracted column to Negotiating.** This file held the last surviving copy of
 * `TERMS_AGREED -> CONTRACTED` — the mapping CR-05 deleted from `creator-chat.tsx` and CR-24
 * deleted from `brand-chat.tsx` — so the same deal read "Negotiating" in the brand deal room
 * while sitting under **Contracted** here. The board was claiming a contract that did not
 * exist; `doAccept` produces `TERMS_AGREED` and no contract row exists at that point.
 *
 * The `OUTREACH` column is kept (Wave 6 ruling, §10.3) as the one documented delta. See that
 * module for both deltas and why the vocabulary stays while the derivation is shared.
 */
const mapDealStatusToStage = mapCollaborationStatusToPipelineStage;

function relativeLastActivity(iso?: string): string {
  if (!iso) return '—';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '—';
  const minutes = Math.floor((Date.now() - date.getTime()) / 60000);
  if (minutes < 1) return 'just now';
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} hour${hours === 1 ? '' : 's'} ago`;
  const days = Math.floor(hours / 24);
  return `${days} day${days === 1 ? '' : 's'} ago`;
}

function daysUntil(iso?: string): number | undefined {
  if (!iso) return undefined;
  const deadline = new Date(iso);
  if (Number.isNaN(deadline.getTime())) return undefined;
  return Math.ceil((deadline.getTime() - Date.now()) / (1000 * 60 * 60 * 24));
}

// PL-1 (BrandF.md §69): `slaHoursRemaining` used to be set only in mock data — the live
// mapper below never populated it at all, so `isAtRisk` (and the "N at risk" filter button
// gated on it) was structurally always false/0 in live mode, no matter how overdue a deal
// actually was. `deal.nextDeadline` now carries a real earliest-incomplete-deliverable
// deadline (DealService#toDealResponse, PL-1 backend fix), so this can be a genuine hours
// count instead of `undefined`.
function hoursUntil(iso?: string): number | undefined {
  if (!iso) return undefined;
  const deadline = new Date(iso);
  if (Number.isNaN(deadline.getTime())) return undefined;
  return Math.round((deadline.getTime() - Date.now()) / (1000 * 60 * 60));
}

function mapDealToCollaboration(deal: Deal): Collaboration | null {
  const status = mapDealStatusToStage(deal.status);
  if (!status) return null;
  return {
    id: deal.id,
    creatorId: deal.counterpartyId,
    creatorName: deal.counterpartyName,
    creatorAvatar: deal.counterpartyAvatar || '',
    // PL-4: no DTO source on GET /deals — see the field comment on Collaboration above.
    creatorFollowers: '',
    campaignTitle: deal.campaignName,
    budget: deal.dealValue,
    // PL-4: no DTO source on GET /deals — see the field comment on Collaboration above.
    platforms: [],
    status,
    slaHoursRemaining: hoursUntil(deal.nextDeadline),
    daysRemaining: daysUntil(deal.nextDeadline),
    lastActivity: relativeLastActivity(deal.lastMessageAt),
    deliverableProgress:
      deal.deliverablesTotal > 0
        ? Math.round((deal.deliverablesDone / deal.deliverablesTotal) * 100)
        : undefined,
  };
}

const mockCollaborations: Collaboration[] = [
  {
    id: 'col-1',
    creatorId: 'creator-1',
    creatorName: 'Priya Sharma',
    creatorAvatar: 'https://api.dicebear.com/7.x/avataaars/svg?seed=Priya',
    creatorFollowers: '2.3L',
    campaignTitle: 'Summer Collection Launch',
    budget: 50000,
    platforms: ['Instagram', 'YouTube'],
    status: 'NEGOTIATING',
    slaHoursRemaining: 4, // At risk!
    daysRemaining: 5,
    engagementRate: 8.5,
    matchScore: 92,
    lastActivity: '2 hours ago',
  },
  {
    id: 'col-2',
    creatorId: 'creator-2',
    creatorName: 'Arjun Patel',
    creatorAvatar: 'https://api.dicebear.com/7.x/avataaars/svg?seed=Arjun',
    creatorFollowers: '5.1L',
    campaignTitle: 'Summer Collection Launch',
    budget: 75000,
    platforms: ['Instagram'],
    status: 'CONTRACTED',
    slaHoursRemaining: 48,
    daysRemaining: 3,
    engagementRate: 6.2,
    matchScore: 85,
    lastActivity: '1 day ago',
  },
  {
    id: 'col-3',
    creatorId: 'creator-3',
    creatorName: 'Maya Gupta',
    creatorAvatar: 'https://api.dicebear.com/7.x/avataaars/svg?seed=Maya',
    creatorFollowers: '1.8L',
    campaignTitle: 'Holiday Season Promo',
    budget: 45000,
    platforms: ['YouTube', 'Instagram'],
    status: 'IN_PROGRESS',
    slaHoursRemaining: 120,
    daysRemaining: 12,
    engagementRate: 9.1,
    matchScore: 88,
    lastActivity: '3 hours ago',
    deliverableProgress: 65,
  },
  {
    id: 'col-4',
    creatorId: 'creator-4',
    creatorName: 'Rohit Singh',
    creatorAvatar: 'https://api.dicebear.com/7.x/avataaars/svg?seed=Rohit',
    creatorFollowers: '800K',
    campaignTitle: 'New Product Reveal',
    budget: 60000,
    platforms: ['Instagram', 'YouTube'],
    status: 'REVIEW',
    slaHoursRemaining: 6, // At risk!
    daysRemaining: 2,
    engagementRate: 7.8,
    matchScore: 86,
    lastActivity: '5 hours ago',
  },
  {
    id: 'col-5',
    creatorId: 'creator-5',
    creatorName: 'Ananya Verma',
    creatorAvatar: 'https://api.dicebear.com/7.x/avataaars/svg?seed=Ananya',
    creatorFollowers: '3.2L',
    campaignTitle: 'Summer Collection Launch',
    budget: 55000,
    platforms: ['Instagram'],
    status: 'SETTLED',
    engagementRate: 8.3,
    matchScore: 90,
    lastActivity: '2 days ago',
  },
  {
    id: 'col-6',
    creatorId: 'creator-6',
    creatorName: 'Vikram Malhotra',
    creatorAvatar: 'https://api.dicebear.com/7.x/avataaars/svg?seed=Vikram',
    creatorFollowers: '420K',
    campaignTitle: 'Brand Awareness Drive',
    budget: 40000,
    platforms: ['YouTube'],
    status: 'OUTREACH',
    slaHoursRemaining: 72,
    daysRemaining: 7,
    engagementRate: 6.9,
    matchScore: 78,
    lastActivity: '1 hour ago',
  },
  {
    id: 'col-7',
    creatorId: 'creator-7',
    creatorName: 'Neha Kapoor',
    creatorAvatar: 'https://api.dicebear.com/7.x/avataaars/svg?seed=Neha',
    creatorFollowers: '1.5L',
    campaignTitle: 'Summer Collection Launch',
    budget: 35000,
    platforms: ['Instagram'],
    status: 'OUTREACH',
    slaHoursRemaining: 96,
    daysRemaining: 10,
    engagementRate: 7.2,
    matchScore: 82,
    lastActivity: '4 hours ago',
  },
  {
    id: 'col-8',
    creatorId: 'creator-8',
    creatorName: 'Karan Mehta',
    creatorAvatar: 'https://api.dicebear.com/7.x/avataaars/svg?seed=Karan',
    creatorFollowers: '670K',
    campaignTitle: 'Tech Product Launch',
    budget: 65000,
    platforms: ['YouTube'],
    status: 'IN_PROGRESS',
    slaHoursRemaining: 36,
    daysRemaining: 8,
    engagementRate: 7.9,
    matchScore: 84,
    lastActivity: '6 hours ago',
    deliverableProgress: 40,
  },
];

const stages = [
  { id: 'OUTREACH', label: 'Outreach', color: 'border-l-slate-400', icon: Send },
  { id: 'NEGOTIATING', label: 'Negotiating', color: 'border-l-amber-400', icon: MessageSquare },
  { id: 'CONTRACTED', label: 'Contracted', color: 'border-l-blue-400', icon: FileCheck },
  { id: 'IN_PROGRESS', label: 'In Progress', color: 'border-l-purple-400', icon: Clock },
  { id: 'REVIEW', label: 'Review', color: 'border-l-orange-400', icon: CheckCircle2 },
  { id: 'SETTLED', label: 'Settled', color: 'border-l-green-400', icon: Banknote },
];

const formatINR = (amount?: number | null) => {
  // Meera-created drafts legitimately have no deal value yet — render a
  // placeholder instead of "₹null"/"₹undefined".
  if (amount == null || Number.isNaN(amount)) return 'No budget set';
  if (amount >= 100000) return `₹${(amount / 100000).toFixed(1)}L`;
  if (amount >= 1000) return `₹${(amount / 1000).toFixed(0)}K`;
  return `₹${amount}`;
};

const PlatformIcon = ({ platform }: { platform: string }) => {
  if (platform === 'Instagram') return <Instagram className="h-3 w-3 text-pink-500" />;
  if (platform === 'YouTube') return <Youtube className="h-3 w-3 text-red-500" />;
  return null;
};

// Check if collaboration is at SLA risk (< 12 hours)
// PL-5: `&&` short-circuited on a falsy-but-valid `0` (SLA exactly at breach — the most urgent
// case) and misclassified it as not at risk. Explicit null/undefined check instead of truthiness.
const isAtRisk = (collab: Collaboration) =>
  collab.slaHoursRemaining != null && collab.slaHoursRemaining < 12;

// Module-scope so it keeps a stable component identity and never remounts on a
// BrandPipelinePage re-render (F-0050). Reads module-level stages/isAtRisk/
// formatINR/PlatformIcon; takes the row data and an onOpen handler as props.
function CollaborationCard({ collab, onOpen }: { collab: Collaboration; onOpen: () => void }) {
  const stage = stages.find((s) => s.id === collab.status);
  const atRiskStatus = isAtRisk(collab);

  return (
    <Card
      className={cn(
        'p-3 cursor-pointer hover:shadow-md transition-all border-l-4',
        stage?.color || 'border-l-muted',
        atRiskStatus && 'ring-2 ring-red-200 bg-red-50/50'
      )}
      role="button"
      tabIndex={0}
      aria-label={`Open deal with ${collab.creatorName}`}
      onClick={onOpen}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          onOpen();
        }
      }}
    >
      {/* SLA Warning */}
      {atRiskStatus && (
        <div className="flex items-center gap-1 text-stage-disputed-fg text-xs font-medium mb-2 bg-red-100 rounded px-2 py-1">
          <AlertTriangle className="h-3 w-3" />
          <span>SLA at risk: {collab.slaHoursRemaining}h remaining</span>
        </div>
      )}

      <div className="flex items-start gap-3">
        <Avatar className="h-10 w-10 border-2 border-background">
          <AvatarImage src={collab.creatorAvatar} />
          <AvatarFallback>{collab.creatorName.charAt(0)}</AvatarFallback>
        </Avatar>
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <p className="font-medium text-sm truncate">{collab.creatorName}</p>
            <span className="text-xs text-muted-foreground">{collab.creatorFollowers}</span>
          </div>
          <p className="text-xs text-muted-foreground truncate">{collab.campaignTitle}</p>
        </div>
      </div>

      <div className="mt-3 grid grid-cols-2 gap-2 text-xs">
        <div>
          <span className="text-muted-foreground">Budget</span>
          <p className="font-semibold">{formatINR(collab.budget)}</p>
        </div>
        {collab.matchScore !== undefined && (
          <div>
            <span className="text-muted-foreground">Match</span>
            <p className={cn(
              'font-semibold',
              collab.matchScore >= 90 ? 'text-stage-approved-fg' :
              collab.matchScore >= 80 ? 'text-stage-negotiating-fg' : 'text-muted-foreground'
            )}>
              {collab.matchScore}%
            </p>
          </div>
        )}
      </div>

      {/* Deliverable Progress (for IN_PROGRESS) */}
      {collab.status === 'IN_PROGRESS' && collab.deliverableProgress !== undefined && (
        <div className="mt-3">
          <div className="flex items-center justify-between text-xs mb-1">
            <span className="text-muted-foreground">Progress</span>
            <span className="font-medium">{collab.deliverableProgress}%</span>
          </div>
          <Progress value={collab.deliverableProgress} className="h-1.5" />
        </div>
      )}

      <div className="mt-3 flex items-center justify-between">
        <div className="flex items-center gap-1">
          {collab.platforms.map((p) => (
            <PlatformIcon key={p} platform={p} />
          ))}
        </div>
        <span className="text-xs text-muted-foreground">{collab.lastActivity}</span>
      </div>
    </Card>
  );
}

export default function BrandPipelinePage() {
  const navigate = useNavigate();
  const [viewMode, setViewMode] = React.useState<'board' | 'list' | 'timeline'>('board');
  const [searchQuery, setSearchQuery] = React.useState('');
  const [showAtRiskOnly, setShowAtRiskOnly] = React.useState(false);

  // Live mode (isApiLive()) state — mock rendering above stays untouched.
  const [liveCollaborations, setLiveCollaborations] = React.useState<Collaboration[]>([]);
  const [collabsLoading, setCollabsLoading] = React.useState(false);
  const [collabsError, setCollabsError] = React.useState<string | null>(null);

  const collaborations = isApiLive() ? liveCollaborations : mockCollaborations;

  const loadCollaborations = React.useCallback(async () => {
    setCollabsLoading(true);
    setCollabsError(null);
    try {
      const remote = await dealsApi.list('brand');
      setLiveCollaborations(
        Array.isArray(remote)
          ? remote.map(mapDealToCollaboration).filter((c): c is Collaboration => c !== null)
          : [],
      );
    } catch {
      setLiveCollaborations([]);
      setCollabsError('Could not load the pipeline. Check your connection and retry.');
    } finally {
      setCollabsLoading(false);
    }
  }, []);

  React.useEffect(() => {
    if (isApiLive()) void loadCollaborations();
  }, [loadCollaborations]);

  const filteredCollabs = collaborations.filter((c) => {
    if (showAtRiskOnly && !isAtRisk(c)) return false;
    if (searchQuery) {
      const query = searchQuery.toLowerCase();
      return c.creatorName.toLowerCase().includes(query) ||
             c.campaignTitle.toLowerCase().includes(query);
    }
    return true;
  });

  const atRiskCount = collaborations.filter(isAtRisk).length;

  const getCollaborationsByStage = (stageId: string) => 
    filteredCollabs.filter((c) => c.status === stageId);

  const openDeal = (id: string) => navigate(`/brand/deals/${id}`);

  // Board View — a render helper (called, not mounted as <BoardView/>), so it
  // produces inlined elements with no in-render component identity to remount.
  const boardView = () => (
    <ScrollArea className="w-full">
      <div className="flex gap-4 pb-4 min-w-max">
        {stages.map((stage) => {
          const stageCollabs = getCollaborationsByStage(stage.id);
          const StageIcon = stage.icon;
          const stageAtRisk = stageCollabs.filter(isAtRisk).length;
          
          return (
            <div key={stage.id} className="w-80 flex-shrink-0">
              <div className="flex items-center justify-between mb-3 px-1">
                <div className="flex items-center gap-2">
                  <StageIcon className="h-4 w-4 text-muted-foreground" />
                  <span className="font-medium text-sm">{stage.label}</span>
                  <Badge variant="secondary" className="h-5 px-1.5 text-xs">
                    {stageCollabs.length}
                  </Badge>
                </div>
                {stageAtRisk > 0 && (
                  <Badge variant="destructive" className="h-5 px-1.5 text-xs">
                    {stageAtRisk} at risk
                  </Badge>
                )}
              </div>
              <div className="space-y-3">
                {stageCollabs.map((collab) => (
                  <CollaborationCard key={collab.id} collab={collab} onOpen={() => openDeal(collab.id)} />
                ))}
                {stageCollabs.length === 0 && (
                  <div className="rounded-lg border-2 border-dashed p-6 text-center">
                    <p className="text-sm text-muted-foreground">No collaborations</p>
                  </div>
                )}
              </div>
            </div>
          );
        })}
      </div>
      <ScrollBar orientation="horizontal" />
    </ScrollArea>
  );

  // List View — render helper (see boardView note).
  const listView = () => (
    <div className="rounded-lg border overflow-hidden">
      <table className="w-full">
        <thead className="bg-muted/50">
          <tr className="text-left text-sm text-muted-foreground">
            <th className="p-3 font-medium">Creator</th>
            <th className="p-3 font-medium">Campaign</th>
            <th className="p-3 font-medium">Stage</th>
            <th className="p-3 font-medium">Budget</th>
            <th className="p-3 font-medium">Match</th>
            <th className="p-3 font-medium">SLA</th>
            <th className="p-3 font-medium">Last Activity</th>
          </tr>
        </thead>
        <tbody className="divide-y">
          {filteredCollabs.map((collab) => {
            const stage = stages.find((s) => s.id === collab.status);
            const atRiskStatus = isAtRisk(collab);
            const openDeal = () => navigate(`/brand/deals/${collab.id}`);

            return (
              <tr
                key={collab.id}
                className={cn(
                  'hover:bg-muted/30 cursor-pointer transition-colors',
                  atRiskStatus && 'bg-red-50'
                )}
                role="button"
                tabIndex={0}
                aria-label={`Open deal with ${collab.creatorName}`}
                onClick={openDeal}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault();
                    openDeal();
                  }
                }}
              >
                <td className="p-3">
                  <div className="flex items-center gap-3">
                    <Avatar className="h-8 w-8">
                      <AvatarImage src={collab.creatorAvatar} />
                      <AvatarFallback>{collab.creatorName.charAt(0)}</AvatarFallback>
                    </Avatar>
                    <div>
                      <p className="font-medium text-sm">{collab.creatorName}</p>
                      <p className="text-xs text-muted-foreground">{collab.creatorFollowers}</p>
                    </div>
                  </div>
                </td>
                <td className="p-3 text-sm">{collab.campaignTitle}</td>
                <td className="p-3">
                  <Badge variant="outline" className={cn('text-xs', stage?.color.replace('border-l', 'border'))}>
                    {stage?.label}
                  </Badge>
                </td>
                <td className="p-3 text-sm font-medium">{formatINR(collab.budget)}</td>
                <td className="p-3">
                  {collab.matchScore !== undefined ? (
                    <span className={cn(
                      'text-sm font-medium',
                      collab.matchScore >= 90 ? 'text-stage-approved-fg' : 'text-muted-foreground'
                    )}>
                      {collab.matchScore}%
                    </span>
                  ) : (
                    <span className="text-sm text-muted-foreground">—</span>
                  )}
                </td>
                <td className="p-3">
                  {collab.slaHoursRemaining !== undefined && (
                    <span className={cn(
                      'text-sm font-medium flex items-center gap-1',
                      atRiskStatus ? 'text-stage-disputed-fg' : 'text-muted-foreground'
                    )}>
                      {atRiskStatus && <AlertTriangle className="h-3 w-3" />}
                      {collab.slaHoursRemaining}h
                    </span>
                  )}
                </td>
                <td className="p-3 text-sm text-muted-foreground">{collab.lastActivity}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );

  // Timeline View (simplified Gantt-like) — render helper (see boardView note).
  const timelineView = () => (
    <div className="space-y-4">
      {filteredCollabs.map((collab) => {
        const stage = stages.find((s) => s.id === collab.status);
        const stageIndex = stages.findIndex((s) => s.id === collab.status);
        const progress = ((stageIndex + 1) / stages.length) * 100;
        const atRiskStatus = isAtRisk(collab);
        const openDeal = () => navigate(`/brand/deals/${collab.id}`);

        return (
          <div
            key={collab.id}
            className={cn(
              'rounded-lg border p-4 cursor-pointer hover:shadow-md transition-all',
              atRiskStatus && 'ring-2 ring-red-200 bg-red-50/50'
            )}
            role="button"
            tabIndex={0}
            aria-label={`Open deal with ${collab.creatorName}`}
            onClick={openDeal}
            onKeyDown={(e) => {
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                openDeal();
              }
            }}
          >
            <div className="flex items-center justify-between mb-3">
              <div className="flex items-center gap-3">
                <Avatar className="h-8 w-8">
                  <AvatarImage src={collab.creatorAvatar} />
                  <AvatarFallback>{collab.creatorName.charAt(0)}</AvatarFallback>
                </Avatar>
                <div>
                  <p className="font-medium text-sm">{collab.creatorName}</p>
                  <p className="text-xs text-muted-foreground">{collab.campaignTitle}</p>
                </div>
              </div>
              <div className="flex items-center gap-3">
                {atRiskStatus && (
                  <Badge variant="destructive" className="text-xs">
                    <AlertTriangle className="h-3 w-3 mr-1" />
                    {collab.slaHoursRemaining}h left
                  </Badge>
                )}
                <Badge variant="outline">{stage?.label}</Badge>
                <span className="font-semibold text-sm">{formatINR(collab.budget)}</span>
              </div>
            </div>
            
            {/* Timeline Progress Bar */}
            <div className="relative">
              <div className="h-2 bg-muted rounded-full overflow-hidden">
                <div 
                  className={cn(
                    'h-full rounded-full transition-all',
                    atRiskStatus ? 'bg-red-500' : 'bg-primary'
                  )}
                  style={{ width: `${progress}%` }}
                />
              </div>
              {/* Stage markers */}
              <div className="absolute top-0 left-0 right-0 flex justify-between -mt-1">
                {stages.map((s, idx) => (
                  <Tooltip key={s.id}>
                    <TooltipTrigger asChild>
                      <div 
                        className={cn(
                          'h-4 w-4 rounded-full border-2 border-background',
                          idx <= stageIndex ? 'bg-primary' : 'bg-muted'
                        )}
                      />
                    </TooltipTrigger>
                    <TooltipContent>{s.label}</TooltipContent>
                  </Tooltip>
                ))}
              </div>
            </div>
          </div>
        );
      })}
    </div>
  );

  return (
    <TooltipProvider>
      <div className="p-6 space-y-6">
        {/* Header */}
        <div className="flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
          <div>
            <h1 className="text-2xl font-bold">Pipeline</h1>
            <p className="text-muted-foreground">
              Track all collaborations across stages
            </p>
          </div>
          <div className="flex items-center gap-2">
            <Button variant="outline" onClick={() => navigate('/brand/discover')}>
              <Plus className="h-4 w-4 mr-2" />
              New Collaboration
            </Button>
          </div>
        </div>

        {/* Toolbar */}
        <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
          <div className="flex items-center gap-3">
            {/* View Toggle */}
            <Tabs value={viewMode} onValueChange={(v) => setViewMode(v as typeof viewMode)}>
              <TabsList>
                <TabsTrigger value="board" className="gap-1.5">
                  <LayoutGrid className="h-4 w-4" />
                  <span className="hidden sm:inline">Board</span>
                </TabsTrigger>
                <TabsTrigger value="list" className="gap-1.5">
                  <List className="h-4 w-4" />
                  <span className="hidden sm:inline">List</span>
                </TabsTrigger>
                <TabsTrigger value="timeline" className="gap-1.5">
                  <Calendar className="h-4 w-4" />
                  <span className="hidden sm:inline">Timeline</span>
                </TabsTrigger>
              </TabsList>
            </Tabs>

            {/* At Risk Filter */}
            {atRiskCount > 0 && (
              <Button 
                variant={showAtRiskOnly ? 'destructive' : 'outline'} 
                size="sm"
                onClick={() => setShowAtRiskOnly(!showAtRiskOnly)}
                className="gap-1.5"
              >
                <AlertTriangle className="h-4 w-4" />
                {atRiskCount} at risk
              </Button>
            )}
          </div>

          {/* Search */}
          <div className="relative w-full sm:w-64">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
            <Input
              placeholder="Search collaborations..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="pl-9"
            />
          </div>
        </div>

        {/* Content */}
        {isApiLive() && collabsLoading ? (
          <div className="flex items-center justify-center py-16">
            <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
          </div>
        ) : isApiLive() && collabsError ? (
          <Card className="p-6 text-center">
            <AlertCircle className="h-10 w-10 text-destructive-foreground mx-auto mb-2" />
            <p className="text-sm text-destructive-foreground">{collabsError}</p>
          </Card>
        ) : filteredCollabs.length === 0 ? (
          <Card className="p-6 text-center">
            <MessageSquare className="h-10 w-10 text-muted-foreground mx-auto mb-2" />
            <p className="text-sm text-muted-foreground">No collaborations found</p>
          </Card>
        ) : (
          <>
            {viewMode === 'board' && boardView()}
            {viewMode === 'list' && listView()}
            {viewMode === 'timeline' && timelineView()}
          </>
        )}
      </div>
    </TooltipProvider>
  );
}
