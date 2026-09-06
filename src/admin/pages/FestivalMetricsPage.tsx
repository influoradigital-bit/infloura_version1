/**
 * INFLUORA ADMIN PANEL — Festival Box coupon-copy metrics
 * Owner: Ananya (Frontend)
 * Reference: T-FESTIVALBOX-0905 phase 10, screen 3
 *
 * Per-sponsor coupon-COPY totals + daily series for one Festival Box edition. Backed by
 * `festivalMetricsApi.getCopyMetrics` (src/admin/services/api-contracts.ts) against the real
 * `AdminFestivalMetricsController` / `AdminFestivalMetricsService`.
 *
 * LOAD-BEARING FRAMING (do not soften this on a future edit): a "copy" is a visitor tapping
 * "copy code" on the public /festival-box page — it is an INTENT signal, not a sale, and this
 * number runs several times higher than actual redemptions (see the backend's own
 * `CouponCopyMetricsResponse` javadoc, which keeps this data out of any DTO that also carries
 * revenue). If this screen ever reads like a revenue dashboard, someone will quote a copy count
 * as a sales number. For marketplace/Amazon sponsors specifically, this is the ONLY signal
 * available at all — on-platform redemption tracking is structurally impossible off-Influora's
 * own checkout, so a low/zero count for one of those sponsors means "no visibility", not
 * necessarily "no interest".
 *
 * Same manual-URLSearchParams-via-the-api-client, loading/empty-state, orDash discipline as
 * FestivalEnquiriesPage.tsx (read first, matched here). No chart library is introduced — plain
 * tables, consistent with the rest of the admin console(no other admin screen renders a chart
 * today; recharts is a project dependency but has zero admin usages to match).
 */

import { type FormEvent, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { AlertTriangle, LineChart, Megaphone } from 'lucide-react';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { festivalMetricsApi } from '../services/api-contracts';
import type { FestivalDailyCopyPoint } from '../services/api-contracts';

function orDash(value: string | null | undefined): string {
  return value && value.trim().length > 0 ? value : '—';
}

function formatDay(iso: string): string {
  // Backend sends a bare LocalDate ("2026-09-05") — append a time so `Date` parses it as local,
  // not UTC-midnight (which can roll the displayed day back by one in a negative-UTC-offset
  // browser).
  return new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium' }).format(new Date(`${iso}T00:00:00`));
}

/** Groups the flat daily series by sponsor for the per-sponsor mini-table, preserving the
 *  backend's day-ascending order within each sponsor. */
function groupBySponsor(points: FestivalDailyCopyPoint[]): Map<string, FestivalDailyCopyPoint[]> {
  const bySponsor = new Map<string, FestivalDailyCopyPoint[]>();
  for (const point of points) {
    const existing = bySponsor.get(point.sponsorSlug);
    if (existing) {
      existing.push(point);
    } else {
      bySponsor.set(point.sponsorSlug, [point]);
    }
  }
  return bySponsor;
}

export default function FestivalMetricsPage() {
  const [editionDraft, setEditionDraft] = useState('');
  const [edition, setEdition] = useState<string | null>(null);

  const metricsQuery = useQuery({
    queryKey: ['admin', 'festival-metrics', 'copies', edition],
    queryFn: () => festivalMetricsApi.getCopyMetrics(edition as string),
    enabled: edition !== null && edition.trim().length > 0,
  });

  function handleSubmit(e: FormEvent) {
    e.preventDefault();
    const trimmed = editionDraft.trim();
    if (trimmed.length === 0) return;
    setEdition(trimmed);
  }

  const hasQueried = edition !== null;
  const sponsorTotals = metricsQuery.data?.success ? metricsQuery.data.data?.sponsorTotals ?? [] : [];
  const dailySeries = metricsQuery.data?.success ? metricsQuery.data.data?.dailySeries ?? [] : [];
  // [Kabir M-1/M-2] The backend sends this with every successful response; absent before a query
  // has run, which is why the render site guards on it rather than assuming a string.
  const measurementCaveat = metricsQuery.data?.success
    ? metricsQuery.data.data?.measurementCaveat
    : null;
  const loadError =
    hasQueried && !metricsQuery.isLoading && metricsQuery.data && !metricsQuery.data.success
      ? metricsQuery.data.error
      : null;
  const dailyBySponsor = groupBySponsor(dailySeries);

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h2 className="flex items-center gap-2 text-2xl font-semibold text-foreground">
          <LineChart className="size-6" aria-hidden="true" />
          Festival Box metrics
        </h2>
        <p className="text-sm text-muted-foreground">
          Coupon-copy counts per sponsor for one Festival Box edition.
        </p>
      </div>

      {/* Load-bearing framing — always visible, never collapsed behind a tooltip. */}
      <div
        role="note"
        className="flex gap-2 rounded-lg border border-warning-foreground/30 bg-card p-3 text-sm"
      >
        <AlertTriangle className="mt-0.5 size-4 shrink-0 text-warning-foreground" aria-hidden="true" />
        <div className="space-y-1">
          <p className="font-medium text-foreground">Coupon copies — a demand signal, not a sale</p>
          <p className="text-muted-foreground">
            Every count on this page is how many times a visitor tapped "copy code" on the public
            Festival Box page. It is not a redemption or a purchase, and it will always run several
            times higher than actual sales — never quote it as revenue. For marketplace/Amazon
            sponsors, this is the <em>only</em> signal available at all: redemption tracking is
            structurally impossible off-platform for them.
          </p>
          {/*
            [Kabir M-1/M-2] Rendered from the server's own `measurementCaveat` rather than
            hardcoded here. The note above already says "not a sale"; this says the separate and
            equally important thing — the number is not VERIFIED. It comes from an unauthenticated
            public page, is not deduplicated per person, and is capped per origin rather than
            authenticated, so it can be moved by anyone willing to use many addresses.

            Sourced from the API so the backend stays the single owner of that sentence: a
            hardcoded copy here would drift the moment the measurement changes, and this page is
            where a number gets read just before it lands in a sponsor deck.
          */}
          {measurementCaveat && (
            <p className="text-muted-foreground">
              <span className="font-medium text-foreground">Not a verified count. </span>
              {measurementCaveat}
            </p>
          )}
        </div>
      </div>

      {/* Edition selector — no "list all editions" endpoint exists server-side, so this is a
          free-text lookup, same convention as FestivalEnquiriesPage's edition filter. */}
      <form onSubmit={handleSubmit} className="flex flex-col gap-2 sm:flex-row sm:items-center">
        <Input
          value={editionDraft}
          onChange={(e) => setEditionDraft(e.target.value)}
          placeholder="Edition (e.g. EDITION_02)"
          className="sm:max-w-64"
          aria-label="Festival edition"
        />
        <Button type="submit" size="sm" disabled={editionDraft.trim().length === 0}>
          Load metrics
        </Button>
      </form>

      {!hasQueried && (
        <div className="flex flex-col items-center gap-2 rounded-lg border border-dashed border-border py-10 text-center text-sm text-muted-foreground">
          <Megaphone className="size-6 text-muted-foreground/60" aria-hidden="true" />
          Enter an edition above to load its coupon-copy metrics.
        </div>
      )}

      {loadError && (
        <div className="rounded-lg border border-destructive-foreground/30 bg-card p-4 text-sm text-destructive-foreground">
          Failed to load Festival Box metrics: {loadError}
        </div>
      )}

      {hasQueried && !loadError && (
        <>
          {/* Sponsor leaderboard */}
          <div className="overflow-hidden rounded-lg border border-border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Sponsor</TableHead>
                  <TableHead className="text-right">Total copies</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {metricsQuery.isLoading ? (
                  Array.from({ length: 3 }).map((_, i) => (
                    <TableRow key={i}>
                      <TableCell>
                        <Skeleton className="h-4 w-32" />
                      </TableCell>
                      <TableCell className="text-right">
                        <Skeleton className="ml-auto h-4 w-12" />
                      </TableCell>
                    </TableRow>
                  ))
                ) : sponsorTotals.length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={2} className="py-10 text-center text-sm text-muted-foreground">
                      No coupon copies recorded for this edition yet.
                    </TableCell>
                  </TableRow>
                ) : (
                  sponsorTotals.map((row) => (
                    <TableRow key={row.sponsorSlug}>
                      <TableCell className="font-medium text-foreground">{orDash(row.sponsorSlug)}</TableCell>
                      <TableCell className="text-right tabular-nums text-foreground">{row.totalCopies}</TableCell>
                    </TableRow>
                  ))
                )}
              </TableBody>
            </Table>
          </div>

          {/* Daily series, grouped by sponsor */}
          {!metricsQuery.isLoading && dailyBySponsor.size > 0 && (
            <div className="flex flex-col gap-4">
              <h3 className="text-sm font-semibold text-foreground">Daily trend</h3>
              {Array.from(dailyBySponsor.entries()).map(([sponsorSlug, points]) => (
                <div key={sponsorSlug} className="overflow-hidden rounded-lg border border-border">
                  <div className="border-b border-border bg-muted px-3 py-2 text-sm font-medium text-foreground">
                    {orDash(sponsorSlug)}
                  </div>
                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead>Day</TableHead>
                        <TableHead className="text-right">Copies</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {points.map((point) => (
                        <TableRow key={`${point.sponsorSlug}-${point.day}`}>
                          <TableCell className="whitespace-nowrap text-muted-foreground">
                            {formatDay(point.day)}
                          </TableCell>
                          <TableCell className="text-right tabular-nums text-foreground">
                            {point.copyCount}
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </div>
              ))}
            </div>
          )}
        </>
      )}
    </div>
  );
}
