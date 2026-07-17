/**
 * INFLUORA ADMIN PANEL — Users Page (Brands + Creators list/search)
 * Owner: Ananya (Frontend)
 * Reference: Task #8 (this cycle). Replaces the `UsersPlaceholder` mounted at
 * /admin/users in src/pages/admin-console.tsx — Tasks #2/#3 (Vikram) shipped
 * the backing list endpoints this same cycle:
 *   - GET /admin/brands   -> PaginatedBrandResponse (matches `Brand`, useBrandList.ts)
 *   - GET /admin/creators -> PagedCreatorsDto (matches `CreatorSummary`, useCreatorList.ts)
 *
 * Two tabs (Brands / Creators), each a searchable/filterable, paginated table.
 * Per Swapnil's ruling (SHARED_CONTEXT.md, "KYC verification happens here —
 * this is the launch-blocker use case"), the Brands tab carries the KYC
 * status filter and the suspended filter front-and-center.
 *
 * Row click opens the existing detail views (BrandProfile.tsx /
 * CreatorProfile.tsx, src/admin/components/users/) at a nested route
 * (/admin/users/brands/:id, /admin/users/creators/:id) — this component owns
 * its own <Routes> since AdminConsolePage mounts it at "users/*". Those
 * profile components are untouched: they already do the KYC/application
 * review + suspend/reinstate mutations via useBrandDetail/useCreatorDetail.
 */

import { type ReactNode } from 'react';
import { Routes, Route, Navigate, useNavigate, useParams } from 'react-router-dom';
import {
  ArrowLeft,
  Ban,
  Building2,
  CheckCircle2,
  Instagram,
  ShieldCheck,
  ShieldQuestion,
  ShieldX,
  UserRound,
  Users as UsersIcon,
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
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { cn } from '@/lib/utils';
import { useBrandList } from '../hooks/useBrandList';
import { useCreatorList } from '../hooks/useCreatorList';
import BrandProfile from '../components/users/BrandProfile';
import CreatorProfile from '../components/users/CreatorProfile';
import {
  KycStatus,
  CreatorApplicationStatus,
  type Brand,
  type CreatorSummary,
} from '../types/admin.types';

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

function formatCompactNumber(value: number): string {
  return new Intl.NumberFormat('en-IN', { notation: 'compact', maximumFractionDigits: 1 }).format(value);
}

function formatDate(iso: string | undefined): string {
  if (!iso) return '—';
  return new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium' }).format(new Date(iso));
}

// ============================================
// STATUS PILL — solid, AA-legible fill, same pattern as
// BrandProfile/CreatorProfile/CampaignTable/TicketList per prior
// brand-CTA-contrast feedback.
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

function kycPillTone(status: KycStatus): PillTone {
  switch (status) {
    case KycStatus.APPROVED:
      return 'success';
    case KycStatus.REJECTED:
      return 'destructive';
    case KycStatus.PENDING:
    default:
      return 'warning';
  }
}

function kycPillIcon(status: KycStatus) {
  switch (status) {
    case KycStatus.APPROVED:
      return ShieldCheck;
    case KycStatus.REJECTED:
      return ShieldX;
    case KycStatus.PENDING:
    default:
      return ShieldQuestion;
  }
}

function applicationPillTone(status: CreatorApplicationStatus): PillTone {
  switch (status) {
    case CreatorApplicationStatus.APPROVED:
      return 'success';
    case CreatorApplicationStatus.REJECTED:
      return 'destructive';
    case CreatorApplicationStatus.PENDING:
    default:
      return 'warning';
  }
}

// ============================================
// SUSPENDED FILTER TOGGLE — shared shape between the two tabs
// ============================================

function SuspendedFilterToggle({ active, onToggle }: { active: boolean; onToggle: () => void }) {
  return (
    <button
      type="button"
      onClick={onToggle}
      aria-pressed={active}
      className={cn(
        'inline-flex items-center gap-1.5 rounded-md border px-3 py-1.5 text-sm font-medium transition-colors',
        active
          ? 'border-transparent bg-destructive-foreground text-white'
          : 'border-input text-foreground hover:bg-accent hover:text-accent-foreground',
      )}
    >
      <Ban className="size-3.5" aria-hidden="true" />
      Suspended Only
    </button>
  );
}

function PageControls({
  page,
  totalPages,
  onPageChange,
}: {
  page: number;
  totalPages: number;
  onPageChange: (page: number) => void;
}) {
  if (totalPages <= 1) return null;

  return (
    <div className="flex items-center justify-between border-t border-border pt-3">
      <Button
        type="button"
        variant="outline"
        size="sm"
        disabled={page <= 1}
        onClick={() => onPageChange(page - 1)}
      >
        Previous
      </Button>
      <span className="text-sm text-muted-foreground">
        Page {page} of {totalPages}
      </span>
      <Button
        type="button"
        variant="outline"
        size="sm"
        disabled={page >= totalPages}
        onClick={() => onPageChange(page + 1)}
      >
        Next
      </Button>
    </div>
  );
}

// ============================================
// BRANDS TAB
// ============================================

function BrandsTable() {
  const { brands, totalCount, totalPages, isLoading, error, filters, setFilters, page, setPage } = useBrandList();
  const navigate = useNavigate();

  if (error) {
    return (
      <div className="rounded-lg border border-destructive-foreground/30 bg-card p-4 text-sm text-destructive-foreground">
        Failed to load brands: {error}
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      {/* Filters — search, KYC status, suspended. Per Swapnil's ruling, KYC
          verification happens on this tab (launch-blocker use case), so both
          filters are front-and-center rather than buried in an overflow menu. */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex flex-1 flex-col gap-2 sm:flex-row sm:items-center">
          <Input
            value={filters.search ?? ''}
            onChange={(e) => setFilters({ ...filters, search: e.target.value || undefined })}
            placeholder="Search brand name or email…"
            className="sm:max-w-xs"
            aria-label="Search brands"
          />

          <Select
            value={filters.kycStatus ?? 'ALL'}
            onValueChange={(value) =>
              setFilters({ ...filters, kycStatus: value === 'ALL' ? undefined : (value as KycStatus) })
            }
          >
            <SelectTrigger className="sm:w-44" aria-label="Filter by KYC status">
              <SelectValue placeholder="All KYC statuses" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">All KYC statuses</SelectItem>
              <SelectItem value={KycStatus.PENDING}>KYC Pending</SelectItem>
              <SelectItem value={KycStatus.APPROVED}>KYC Approved</SelectItem>
              <SelectItem value={KycStatus.REJECTED}>KYC Rejected</SelectItem>
            </SelectContent>
          </Select>

          <SuspendedFilterToggle
            active={Boolean(filters.isSuspended)}
            onToggle={() => setFilters({ ...filters, isSuspended: filters.isSuspended ? undefined : true })}
          />
        </div>

        <p className="whitespace-nowrap text-sm text-muted-foreground">
          {isLoading ? 'Loading…' : `${brands.length} of ${totalCount} brands`}
        </p>
      </div>

      {/* Table */}
      <div className="overflow-hidden rounded-lg border border-border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Brand</TableHead>
              <TableHead>Industry</TableHead>
              <TableHead>KYC Status</TableHead>
              <TableHead>Campaigns</TableHead>
              <TableHead>Spend</TableHead>
              <TableHead>Status</TableHead>
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
            ) : brands.length === 0 ? (
              <TableRow>
                <TableCell colSpan={6} className="py-10 text-center text-sm text-muted-foreground">
                  <div className="flex flex-col items-center gap-2">
                    <Building2 className="size-6 text-muted-foreground/60" aria-hidden="true" />
                    No brands match the current filters.
                  </div>
                </TableCell>
              </TableRow>
            ) : (
              brands.map((brand: Brand) => {
                const KycIcon = kycPillIcon(brand.kycStatus);
                return (
                  <TableRow
                    key={brand.id}
                    onClick={() => navigate(`brands/${brand.id}`)}
                    className="cursor-pointer"
                    tabIndex={0}
                    role="button"
                    aria-label={`Open brand: ${brand.name}`}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault();
                        navigate(`brands/${brand.id}`);
                      }
                    }}
                  >
                    <TableCell className="max-w-56 whitespace-normal font-medium text-foreground">
                      {brand.name}
                      <div className="text-xs font-normal text-muted-foreground">{brand.email}</div>
                    </TableCell>
                    <TableCell className="text-muted-foreground">
                      {brand.industry}
                      <Badge variant="outline" className="ml-2 align-middle">
                        {brand.size}
                      </Badge>
                    </TableCell>
                    <TableCell>
                      <StatusPill tone={kycPillTone(brand.kycStatus)}>
                        <KycIcon className="size-3.5" aria-hidden="true" />
                        {brand.kycStatus}
                      </StatusPill>
                    </TableCell>
                    <TableCell className="text-muted-foreground">{brand.campaignCount}</TableCell>
                    <TableCell className="font-medium text-foreground">
                      {formatCompactINR(brand.totalSpend)}
                    </TableCell>
                    <TableCell>
                      {brand.isSuspended ? (
                        <StatusPill tone="destructive">
                          <Ban className="size-3.5" aria-hidden="true" />
                          Suspended
                        </StatusPill>
                      ) : (
                        <StatusPill tone="success">
                          <CheckCircle2 className="size-3.5" aria-hidden="true" />
                          Active
                        </StatusPill>
                      )}
                    </TableCell>
                  </TableRow>
                );
              })
            )}
          </TableBody>
        </Table>
      </div>

      <PageControls page={page} totalPages={totalPages} onPageChange={setPage} />
    </div>
  );
}

// ============================================
// CREATORS TAB
// ============================================

function CreatorsTable() {
  const { creators, totalCount, totalPages, isLoading, error, filters, setFilters, page, setPage } =
    useCreatorList();
  const navigate = useNavigate();

  if (error) {
    return (
      <div className="rounded-lg border border-destructive-foreground/30 bg-card p-4 text-sm text-destructive-foreground">
        Failed to load creators: {error}
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      {/* Filters — search, application status, suspended. */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex flex-1 flex-col gap-2 sm:flex-row sm:items-center">
          <Input
            value={filters.search ?? ''}
            onChange={(e) => setFilters({ ...filters, search: e.target.value || undefined })}
            placeholder="Search creator name or email…"
            className="sm:max-w-xs"
            aria-label="Search creators"
          />

          <Select
            value={filters.applicationStatus ?? 'ALL'}
            onValueChange={(value) =>
              setFilters({
                ...filters,
                applicationStatus: value === 'ALL' ? undefined : (value as CreatorApplicationStatus),
              })
            }
          >
            <SelectTrigger className="sm:w-48" aria-label="Filter by application status">
              <SelectValue placeholder="All application statuses" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">All application statuses</SelectItem>
              <SelectItem value={CreatorApplicationStatus.PENDING}>Application Pending</SelectItem>
              <SelectItem value={CreatorApplicationStatus.APPROVED}>Application Approved</SelectItem>
              <SelectItem value={CreatorApplicationStatus.REJECTED}>Application Rejected</SelectItem>
            </SelectContent>
          </Select>

          <SuspendedFilterToggle
            active={Boolean(filters.isSuspended)}
            onToggle={() => setFilters({ ...filters, isSuspended: filters.isSuspended ? undefined : true })}
          />
        </div>

        <p className="whitespace-nowrap text-sm text-muted-foreground">
          {isLoading ? 'Loading…' : `${creators.length} of ${totalCount} creators`}
        </p>
      </div>

      {/* Table */}
      <div className="overflow-hidden rounded-lg border border-border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Creator</TableHead>
              <TableHead>Instagram</TableHead>
              <TableHead>Followers</TableHead>
              <TableHead>Tier</TableHead>
              <TableHead>Application</TableHead>
              <TableHead>Status</TableHead>
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
            ) : creators.length === 0 ? (
              <TableRow>
                <TableCell colSpan={6} className="py-10 text-center text-sm text-muted-foreground">
                  <div className="flex flex-col items-center gap-2">
                    <UserRound className="size-6 text-muted-foreground/60" aria-hidden="true" />
                    No creators match the current filters.
                  </div>
                </TableCell>
              </TableRow>
            ) : (
              creators.map((creator: CreatorSummary) => (
                <TableRow
                  key={creator.id}
                  onClick={() => navigate(`creators/${creator.id}`)}
                  className="cursor-pointer"
                  tabIndex={0}
                  role="button"
                  aria-label={`Open creator: ${creator.name}`}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault();
                      navigate(`creators/${creator.id}`);
                    }
                  }}
                >
                  <TableCell className="max-w-56 whitespace-normal font-medium text-foreground">
                    {creator.name}
                    <div className="text-xs font-normal text-muted-foreground">{creator.email}</div>
                  </TableCell>
                  <TableCell className="text-muted-foreground">
                    <span className="inline-flex items-center gap-1.5">
                      <Instagram className="size-3.5 shrink-0" aria-hidden="true" />
                      {creator.instagramHandle}
                    </span>
                  </TableCell>
                  <TableCell className="text-muted-foreground">{formatCompactNumber(creator.followers)}</TableCell>
                  <TableCell>
                    <Badge variant="secondary">{creator.tier}</Badge>
                  </TableCell>
                  <TableCell>
                    <StatusPill tone={applicationPillTone(creator.applicationStatus)}>
                      {creator.applicationStatus}
                    </StatusPill>
                  </TableCell>
                  <TableCell>
                    {creator.isSuspended ? (
                      <StatusPill tone="destructive">
                        <Ban className="size-3.5" aria-hidden="true" />
                        Suspended
                      </StatusPill>
                    ) : (
                      <StatusPill tone="success">
                        <CheckCircle2 className="size-3.5" aria-hidden="true" />
                        Active
                      </StatusPill>
                    )}
                  </TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </div>

      <PageControls page={page} totalPages={totalPages} onPageChange={setPage} />
    </div>
  );
}

// ============================================
// LIST VIEW (index route) — tabs
// ============================================

function UsersListView() {
  return (
    <div className="flex flex-col gap-4">
      <div>
        <h2 className="flex items-center gap-2 text-2xl font-semibold text-foreground">
          <UsersIcon className="size-6" aria-hidden="true" />
          User Management
        </h2>
        <p className="text-sm text-muted-foreground">
          Search, filter, and review brand and creator accounts. Select a row to open its full
          profile — including KYC/application review and suspend/reinstate actions.
        </p>
      </div>

      <Tabs defaultValue="brands">
        <TabsList>
          <TabsTrigger value="brands">Brands</TabsTrigger>
          <TabsTrigger value="creators">Creators</TabsTrigger>
        </TabsList>
        <TabsContent value="brands">
          <BrandsTable />
        </TabsContent>
        <TabsContent value="creators">
          <CreatorsTable />
        </TabsContent>
      </Tabs>
    </div>
  );
}

// ============================================
// DETAIL ROUTES — thin wrappers around the existing profile views, with a
// "Back to Users" affordance since these are now reachable via a real route
// instead of only being mountable with a hardcoded id.
// ============================================

function BackToUsersButton() {
  const navigate = useNavigate();
  return (
    <Button
      type="button"
      variant="ghost"
      size="sm"
      className="w-fit"
      onClick={() => navigate('/admin/users')}
    >
      <ArrowLeft aria-hidden="true" />
      Back to Users
    </Button>
  );
}

function BrandDetailRoute() {
  const { brandId } = useParams<{ brandId: string }>();

  if (!brandId) {
    return <Navigate to="/admin/users" replace />;
  }

  return (
    <div className="flex flex-col gap-4">
      <BackToUsersButton />
      <BrandProfile brandId={brandId} />
    </div>
  );
}

function CreatorDetailRoute() {
  const { creatorId } = useParams<{ creatorId: string }>();

  if (!creatorId) {
    return <Navigate to="/admin/users" replace />;
  }

  return (
    <div className="flex flex-col gap-4">
      <BackToUsersButton />
      <CreatorProfile creatorId={creatorId} />
    </div>
  );
}

// ============================================
// PAGE — mounted at "users/*" by AdminConsolePage
// ============================================

export default function UsersPage() {
  return (
    <Routes>
      <Route index element={<UsersListView />} />
      <Route path="brands/:brandId" element={<BrandDetailRoute />} />
      <Route path="creators/:creatorId" element={<CreatorDetailRoute />} />
      <Route path="*" element={<Navigate to="/admin/users" replace />} />
    </Routes>
  );
}
