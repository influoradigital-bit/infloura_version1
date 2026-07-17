/**
 * INFLUORA ADMIN PANEL — Campaign Table
 * Owner: Ananya (Frontend)
 * Reference: src/admin/TASK_ASSIGNMENTS.md (P1)
 *
 * Admin monitoring table for campaigns across all brands: name, brand,
 * status, budget (spend vs. budget), dates, and a performance flag derived
 * from SLA breach rate. Client-side search/status filter + column sort,
 * per TASK_ASSIGNMENTS.md ("client-side against mock data is fine").
 *
 * Data comes from `useCampaignList()` (src/admin/hooks/useCampaignList.ts),
 * live-wired via a `react-query` `useQuery` to `campaignApi.listAll()`
 * (`api-contracts.ts`), backed by the real `AdminCampaignController`
 * (`GET /api/v1/admin/campaigns`). Sort/filter stay client-side against the
 * full returned list — the backend has no pagination/filter/sort params yet.
 */

import { type ReactNode } from 'react';
import {
  ArrowDown,
  ArrowUp,
  ArrowUpDown,
  AlertTriangle,
  CheckCircle2,
  TrendingDown,
  Megaphone,
} from 'lucide-react';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { Input } from '@/components/ui/input';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { Badge } from '@/components/ui/badge';
import { cn } from '@/lib/utils';
import { useCampaignList, type CampaignSortField } from '../../hooks/useCampaignList';
import type { CampaignSummary } from '../../types/admin.types';

// ============================================
// FORMATTING
// ============================================

function formatCompactINR(amount: number): string {
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    notation: 'compact',
    maximumFractionDigits: 1,
  }).format(amount);
}

function formatDate(iso: string | undefined): string {
  if (!iso) return '—';
  return new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium' }).format(new Date(iso));
}

// ============================================
// STATUS / PERFORMANCE PILLS — solid, AA-legible fill (not a washed-out
// pastel badge), same pattern as BrandProfile/CreatorProfile StatusPill
// per prior brand-CTA-contrast feedback.
// ============================================

type PillTone = 'success' | 'warning' | 'destructive' | 'neutral';

const PILL_TONE_CLASSES: Record<PillTone, string> = {
  success: 'bg-success-foreground',
  warning: 'bg-warning-foreground',
  destructive: 'bg-destructive-foreground',
  neutral: 'bg-foreground',
};

function StatusPill({ tone, children }: { tone: PillTone; children: ReactNode }) {
  return (
    <span
      className={cn(
        'inline-flex w-fit items-center gap-1 whitespace-nowrap rounded-full px-2.5 py-1 text-xs font-semibold text-white',
        PILL_TONE_CLASSES[tone],
      )}
    >
      {children}
    </span>
  );
}

function statusPillTone(status: CampaignSummary['status']): PillTone {
  switch (status) {
    case 'ACTIVE':
      return 'success';
    case 'PAUSED':
      return 'warning';
    case 'CANCELLED':
      return 'destructive';
    case 'COMPLETED':
    case 'DRAFT':
    default:
      return 'neutral';
  }
}

/**
 * Performance flag derived client-side from `slaBreachRate`, mirroring the
 * WARNING/CRITICAL severity split used for `RedFlag` on the CEO Pulse
 * dashboard. No `timelineStatus` field exists on `CampaignSummary` (that's
 * `CampaignDetail`-only) — this is a lightweight table-level signal, not a
 * duplicate of that field.
 */
function performanceFlag(campaign: CampaignSummary): { tone: PillTone; icon: typeof AlertTriangle; label: string } {
  if (campaign.status === 'DRAFT' || campaign.status === 'CANCELLED') {
    return { tone: 'neutral', icon: CheckCircle2, label: 'N/A' };
  }
  if (campaign.slaBreachRate >= 15) {
    return { tone: 'destructive', icon: AlertTriangle, label: 'At Risk' };
  }
  if (campaign.slaBreachRate >= 5) {
    return { tone: 'warning', icon: TrendingDown, label: 'Watch' };
  }
  return { tone: 'success', icon: CheckCircle2, label: 'On Track' };
}

// ============================================
// SORTABLE HEADER
// ============================================

interface SortableHeadProps {
  field: CampaignSortField;
  activeField: CampaignSortField;
  direction: 'asc' | 'desc';
  onSort: (field: CampaignSortField) => void;
  children: ReactNode;
  className?: string;
}

function SortableHead({ field, activeField, direction, onSort, children, className }: SortableHeadProps) {
  const isActive = field === activeField;
  const Icon = isActive ? (direction === 'asc' ? ArrowUp : ArrowDown) : ArrowUpDown;

  return (
    <TableHead className={className}>
      <button
        type="button"
        onClick={() => onSort(field)}
        className={cn(
          'inline-flex items-center gap-1 rounded text-left font-medium text-foreground hover:text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50',
          isActive && 'text-primary',
        )}
      >
        {children}
        <Icon className="size-3.5 shrink-0" aria-hidden="true" />
      </button>
    </TableHead>
  );
}

// ============================================
// PROPS
// ============================================

export interface CampaignTableProps {
  className?: string;
}

// ============================================
// COMPONENT
// ============================================

export default function CampaignTable({ className }: CampaignTableProps) {
  const { campaigns, totalCount, isLoading, error, filters, setFilters, sort, setSort } = useCampaignList();

  function handleSort(field: CampaignSortField) {
    setSort({
      field,
      direction: sort.field === field && sort.direction === 'asc' ? 'desc' : 'asc',
    });
  }

  if (error) {
    return (
      <div className="rounded-lg border border-destructive-foreground/30 bg-card p-4 text-sm text-destructive-foreground">
        Failed to load campaigns: {error}
      </div>
    );
  }

  return (
    <div className={cn('flex flex-col gap-4', className)}>
      {/* Filters */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex flex-1 flex-col gap-2 sm:flex-row sm:items-center">
          <Input
            value={filters.search ?? ''}
            onChange={(e) => setFilters({ ...filters, search: e.target.value || undefined })}
            placeholder="Search campaign or brand name…"
            className="sm:max-w-xs"
            aria-label="Search campaigns"
          />

          <Select
            value={filters.status ?? 'ALL'}
            onValueChange={(value) =>
              setFilters({ ...filters, status: value === 'ALL' ? undefined : (value as CampaignSummary['status']) })
            }
          >
            <SelectTrigger className="sm:w-40" aria-label="Filter by status">
              <SelectValue placeholder="All statuses" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">All statuses</SelectItem>
              <SelectItem value="DRAFT">Draft</SelectItem>
              <SelectItem value="ACTIVE">Active</SelectItem>
              <SelectItem value="PAUSED">Paused</SelectItem>
              <SelectItem value="COMPLETED">Completed</SelectItem>
              <SelectItem value="CANCELLED">Cancelled</SelectItem>
            </SelectContent>
          </Select>

          <Select
            value={filters.type ?? 'ALL'}
            onValueChange={(value) =>
              setFilters({ ...filters, type: value === 'ALL' ? undefined : (value as CampaignSummary['type']) })
            }
          >
            <SelectTrigger className="sm:w-32" aria-label="Filter by type">
              <SelectValue placeholder="All types" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">All types</SelectItem>
              <SelectItem value="STANDARD">Standard</SelectItem>
              <SelectItem value="HYPE">Hype</SelectItem>
            </SelectContent>
          </Select>

          <button
            type="button"
            onClick={() => setFilters({ ...filters, atRisk: filters.atRisk ? undefined : true })}
            aria-pressed={Boolean(filters.atRisk)}
            className={cn(
              'inline-flex items-center gap-1.5 rounded-md border px-3 py-1.5 text-sm font-medium transition-colors',
              filters.atRisk
                ? 'border-transparent bg-destructive-foreground text-white'
                : 'border-input text-foreground hover:bg-accent hover:text-accent-foreground',
            )}
          >
            <AlertTriangle className="size-3.5" aria-hidden="true" />
            At Risk Only
          </button>
        </div>

        <p className="whitespace-nowrap text-sm text-muted-foreground">
          {isLoading ? 'Loading…' : `${campaigns.length} of ${totalCount} campaigns`}
        </p>
      </div>

      {/* Table */}
      <div className="overflow-hidden rounded-lg border border-border">
        <Table>
          <TableHeader>
            <TableRow>
              <SortableHead field="name" activeField={sort.field} direction={sort.direction} onSort={handleSort}>
                Campaign
              </SortableHead>
              <SortableHead field="brandName" activeField={sort.field} direction={sort.direction} onSort={handleSort}>
                Brand
              </SortableHead>
              <SortableHead field="status" activeField={sort.field} direction={sort.direction} onSort={handleSort}>
                Status
              </SortableHead>
              <SortableHead field="budget" activeField={sort.field} direction={sort.direction} onSort={handleSort}>
                Budget
              </SortableHead>
              <TableHead>Dates</TableHead>
              <SortableHead
                field="slaBreachRate"
                activeField={sort.field}
                direction={sort.direction}
                onSort={handleSort}
              >
                Performance
              </SortableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {isLoading ? (
              Array.from({ length: 5 }).map((_, i) => (
                <TableRow key={i}>
                  {Array.from({ length: 6 }).map((__, j) => (
                    <TableCell key={j}>
                      <div className="h-4 w-full max-w-24 animate-pulse rounded bg-muted" />
                    </TableCell>
                  ))}
                </TableRow>
              ))
            ) : campaigns.length === 0 ? (
              <TableRow>
                <TableCell colSpan={6} className="py-10 text-center text-sm text-muted-foreground">
                  <div className="flex flex-col items-center gap-2">
                    <Megaphone className="size-6 text-muted-foreground/60" aria-hidden="true" />
                    No campaigns match the current filters.
                  </div>
                </TableCell>
              </TableRow>
            ) : (
              campaigns.map((campaign) => {
                const flag = performanceFlag(campaign);
                const FlagIcon = flag.icon;
                return (
                  <TableRow key={campaign.id}>
                    <TableCell className="max-w-56 whitespace-normal font-medium text-foreground">
                      {campaign.name}
                      <Badge variant="outline" className="ml-2 align-middle">
                        {campaign.type}
                      </Badge>
                    </TableCell>
                    <TableCell className="text-muted-foreground">{campaign.brandName}</TableCell>
                    <TableCell>
                      <StatusPill tone={statusPillTone(campaign.status)}>{campaign.status}</StatusPill>
                    </TableCell>
                    <TableCell>
                      <div className="flex flex-col">
                        <span className="font-medium text-foreground">{formatCompactINR(campaign.budget)}</span>
                        <span className="text-xs text-muted-foreground">
                          {formatCompactINR(campaign.spent)} spent
                        </span>
                      </div>
                    </TableCell>
                    <TableCell className="text-muted-foreground">
                      <div className="flex flex-col text-xs">
                        <span>Started {formatDate(campaign.createdAt)}</span>
                        <span>Ends {formatDate(campaign.endsAt)}</span>
                      </div>
                    </TableCell>
                    <TableCell>
                      <StatusPill tone={flag.tone}>
                        <FlagIcon className="size-3.5" aria-hidden="true" />
                        {flag.label}
                      </StatusPill>
                    </TableCell>
                  </TableRow>
                );
              })
            )}
          </TableBody>
        </Table>
      </div>
    </div>
  );
}
