import { Users, Globe2, MapPin, Languages } from 'lucide-react';

import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Progress } from '@/components/ui/progress';
import { Skeleton } from '@/components/ui/skeleton';
import { Empty, EmptyHeader, EmptyTitle, EmptyDescription } from '@/components/ui/empty';
import type { CreatorDemographics } from '@/lib/types';

interface AudienceDemographicsPanelProps {
  data: CreatorDemographics | null;
  loading?: boolean;
  error?: string | null;
  className?: string;
  /**
   * Show the "Engaged this month" section. Only the creator's OWN analytics page turns this on:
   * the engaged audience is creator-only for now, so a brand page never shows it (2026-09-26).
   */
  showEngaged?: boolean;
}

/** Shown in place of the engaged section when Meta returned nothing for this month (BELOW_THRESHOLD). */
export const ENGAGED_EMPTY_TEXT = 'Shown once your posts get at least 100 engagements in a month';

/**
 * Shown when the engaged audience was never fetched for this snapshot (engagedStatus null or
 * absent: a row written before the engaged fetch existed, until the next weekly job). Same meaning
 * as Meera's CreatorAudienceShares.ENGAGED_NOT_YET; never the under-100 claim, which is unchecked.
 */
export const ENGAGED_NOT_YET_TEXT = 'Not fetched yet. It arrives with the next weekly Instagram update.';

/** Shown instead when the API says the last weekly fetch failed: the creator may well be over 100. */
export const ENGAGED_FETCH_FAILED_TEXT =
  "Instagram didn't return this on the last weekly check. It's tried again every week.";

/** The engaged section's not-available line for a status (AVAILABLE with nothing in it = below 100). */
export function engagedReasonText(status: CreatorDemographics['engagedStatus']): string {
  if (status === 'FETCH_FAILED') return ENGAGED_FETCH_FAILED_TEXT;
  if (status === 'BELOW_THRESHOLD' || status === 'AVAILABLE') return ENGAGED_EMPTY_TEXT;
  return ENGAGED_NOT_YET_TEXT;
}

const AGE_GENDER_LABELS: Record<string, string> = {};

/** "18-24_female" -> "18-24 · Female", the key AudienceDemographicsJob stores (falls back to the raw key). */
function formatAgeGenderBucket(key: string): string {
  if (AGE_GENDER_LABELS[key]) return AGE_GENDER_LABELS[key];
  const match = key.match(/^([\d.+-]+)_(male|female|unknown)$/i);
  if (!match) return key;
  const [, age, gender] = match;
  return `${age} · ${gender.charAt(0).toUpperCase()}${gender.slice(1)}`;
}

type GenderLabel = 'Women' | 'Men' | 'Unknown';
const GENDER_ORDER: GenderLabel[] = ['Women', 'Men', 'Unknown'];

/** The gender part of an age/gender key. Anything that is not female or male counts as Unknown. */
function genderOfBucket(key: string): GenderLabel {
  const gender = key.slice(key.lastIndexOf('_') + 1).toLowerCase();
  if (gender === 'female') return 'Women';
  if (gender === 'male') return 'Men';
  return 'Unknown';
}

/**
 * "Women 64% · Men 33% · Unknown 3%": each gender's share of the WHOLE age/gender breakdown,
 * summed over every age band. Null when the breakdown is empty. Plain text, so it never relies on
 * colour to be read.
 */
export function genderSummary(breakdown: Record<string, number> | null | undefined): string | null {
  if (!breakdown) return null;
  const totals: Record<GenderLabel, number> = { Women: 0, Men: 0, Unknown: 0 };
  let total = 0;
  for (const [key, count] of Object.entries(breakdown)) {
    if (!(count > 0)) continue;
    totals[genderOfBucket(key)] += count;
    total += count;
  }
  if (total === 0) return null;
  return GENDER_ORDER.filter((label) => totals[label] > 0)
    .map((label) => `${label} ${Math.round((totals[label] / total) * 100)}%`)
    .join(' · ');
}

/**
 * The largest `limit` rows, plus the total of the WHOLE breakdown. Percentages are shares of all
 * followers Meta reported, not of the rows on screen: dividing by the visible rows made the top 6
 * cities always add up to 100% (2026-09-24).
 */
function topEntries(
  breakdown: Record<string, number> | null | undefined,
  limit: number,
): { entries: [string, number][]; total: number } {
  if (!breakdown) return { entries: [], total: 0 };
  const all = Object.entries(breakdown).filter(([, count]) => count > 0);
  return {
    entries: [...all].sort((a, b) => b[1] - a[1]).slice(0, limit),
    total: all.reduce((sum, [, count]) => sum + count, 0),
  };
}

/** True when a breakdown has at least one positive count. */
function hasEntries(breakdown: Record<string, number> | null | undefined): boolean {
  return !!breakdown && Object.values(breakdown).some((count) => count > 0);
}

/** "IN" -> "India". Falls back to the code when the browser has no name for it. */
function formatCountry(code: string): string {
  try {
    const names = new Intl.DisplayNames(['en'], { type: 'region' });
    return names.of(code.toUpperCase()) ?? code;
  } catch {
    return code;
  }
}

function BreakdownList({
  title,
  icon: Icon,
  entries,
  total: breakdownTotal,
  formatLabel,
}: {
  title: string;
  icon: React.ComponentType<{ className?: string }>;
  entries: [string, number][];
  /** Sum of the whole breakdown, not just `entries`. */
  total: number;
  formatLabel?: (key: string) => string;
}) {
  const total = breakdownTotal || 1;
  return (
    <div>
      <p className="mb-3 flex items-center gap-2 text-sm font-medium text-muted-foreground">
        <Icon className="h-4 w-4" aria-hidden="true" />
        {title}
      </p>
      {entries.length === 0 ? (
        <p className="text-sm text-muted-foreground">No data in this breakdown.</p>
      ) : (
        <div className="space-y-3" role="list" aria-label={title}>
          {entries.map(([key, count]) => {
            const pct = Math.round((count / total) * 100);
            const label = formatLabel ? formatLabel(key) : key;
            return (
              <div key={key} role="listitem">
                <div className="mb-1 flex items-center justify-between text-sm">
                  <span>{label}</span>
                  <span className="text-muted-foreground">
                    {count.toLocaleString()} ({pct}%)
                  </span>
                </div>
                <Progress value={pct} className="h-1.5" aria-label={`${label}: ${pct}%`} />
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

/**
 * One audience (followers, or the people engaged this month): its heading, sync date, the gender
 * summary line and the breakdown lists. Both sections share this so they read the same way.
 */
function AudienceSection({
  id,
  title,
  subtitle,
  fetchedAt,
  ageGenderBreakdown,
  countryBreakdown,
  cityBreakdown,
  localeBreakdown,
}: {
  id: string;
  title: string;
  subtitle?: string;
  fetchedAt: string | null | undefined;
  ageGenderBreakdown: Record<string, number> | null | undefined;
  countryBreakdown: Record<string, number> | null | undefined;
  cityBreakdown: Record<string, number> | null | undefined;
  localeBreakdown?: Record<string, number> | null | undefined;
}) {
  const headingId = `audience-${id}-heading`;
  const ageGender = topEntries(ageGenderBreakdown, 8);
  const countries = topEntries(countryBreakdown, 6);
  const cities = topEntries(cityBreakdown, 6);
  // Meta no longer reports follower languages; only an older snapshot can carry them.
  const locales = topEntries(localeBreakdown, 6);
  const genders = genderSummary(ageGenderBreakdown);

  return (
    <section aria-labelledby={headingId} className="space-y-4">
      <div className="space-y-1">
        <h3 id={headingId} className="text-sm font-semibold">
          {title}
        </h3>
        {subtitle && <p className="text-xs text-muted-foreground">{subtitle}</p>}
        {fetchedAt && (
          <p className="text-xs text-muted-foreground">
            Last synced {new Date(fetchedAt).toLocaleDateString()}
          </p>
        )}
        {genders && (
          <p className="text-sm" data-testid={`audience-${id}-gender-summary`}>
            {genders}
          </p>
        )}
      </div>
      <div className="grid gap-6 sm:grid-cols-2">
        <BreakdownList
          title="Age & Gender"
          icon={Users}
          entries={ageGender.entries}
          total={ageGender.total}
          formatLabel={formatAgeGenderBucket}
        />
        <BreakdownList
          title="Top Countries"
          icon={Globe2}
          entries={countries.entries}
          total={countries.total}
          formatLabel={formatCountry}
        />
        <BreakdownList title="Top Cities" icon={MapPin} entries={cities.entries} total={cities.total} />
        {locales.entries.length > 0 ? (
          <BreakdownList title="Locales" icon={Languages} entries={locales.entries} total={locales.total} />
        ) : null}
      </div>
    </section>
  );
}

/**
 * Audience demographics panel — Wave B task B5. Wired to
 * GET /analytics/creators/{creatorId}/demographics (B4) for brands and
 * GET /creator/analytics/me/demographics for the creator's own page.
 *
 * `data.hasData === false` is a graceful, expected steady state (the weekly
 * AudienceDemographicsJob hasn't produced a snapshot for this creator yet) —
 * rendered as an explicit informational empty state, never as an error and
 * never with fabricated zero-filled bars. Uses Progress bars (the same
 * primitive QualityScoreDisplay already uses) rather than introducing a new
 * chart type — no recharts BarChart exists anywhere in this codebase yet.
 *
 * With `showEngaged` (creator's own page only), an "Engaged this month" section follows the
 * Followers one: Meta's engaged_audience_demographics, which Meta leaves out when the account had
 * fewer than 100 engagements this month. That absence is shown as its own empty text, not an error.
 */
export function AudienceDemographicsPanel({
  data,
  loading = false,
  error,
  className,
  showEngaged = false,
}: AudienceDemographicsPanelProps) {
  if (loading) {
    return (
      <Card className={className}>
        <CardHeader className="pb-2">
          <Skeleton className="h-4 w-40" />
        </CardHeader>
        <CardContent className="grid gap-6 sm:grid-cols-2">
          {Array.from({ length: 4 }).map((_, i) => (
            <div key={i} className="space-y-2">
              <Skeleton className="h-4 w-32" />
              <Skeleton className="h-3 w-full" />
              <Skeleton className="h-3 w-full" />
              <Skeleton className="h-3 w-3/4" />
            </div>
          ))}
        </CardContent>
      </Card>
    );
  }

  if (error) {
    return (
      <Card className={className}>
        <CardHeader className="pb-2">
          <CardTitle className="text-sm font-medium text-muted-foreground">
            Audience Demographics
          </CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-sm text-destructive-foreground">
            Couldn't load audience demographics: {error}
          </p>
        </CardContent>
      </Card>
    );
  }

  const engagedPresent =
    showEngaged &&
    !!data &&
    (hasEntries(data.engagedAgeGenderBreakdown) ||
      hasEntries(data.engagedCountryBreakdown) ||
      hasEntries(data.engagedCityBreakdown));

  if (!data || (!data.hasData && !engagedPresent)) {
    return (
      <Card className={className}>
        <CardHeader className="pb-2">
          <CardTitle className="text-sm font-medium text-muted-foreground">
            Audience Demographics
          </CardTitle>
        </CardHeader>
        <CardContent>
          <Empty className="border-0 p-0">
            <EmptyHeader>
              <Users className="h-8 w-8 text-muted-foreground" aria-hidden="true" />
              <EmptyTitle>No demographics snapshot yet</EmptyTitle>
              <EmptyDescription>
                Audience details arrive a few minutes after Instagram is connected, then refresh
                every week. Instagram only shares them for accounts with 100 or more followers.
              </EmptyDescription>
            </EmptyHeader>
          </Empty>
        </CardContent>
      </Card>
    );
  }

  return (
    <Card className={className}>
      <CardHeader className="pb-2">
        <CardTitle className="text-sm font-medium text-muted-foreground">
          Audience Demographics
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-8">
        {data.hasData ? (
          <AudienceSection
            id="followers"
            title="Followers"
            fetchedAt={data.fetchedAt}
            ageGenderBreakdown={data.ageGenderBreakdown}
            countryBreakdown={data.countryBreakdown}
            cityBreakdown={data.cityBreakdown}
            localeBreakdown={data.localeBreakdown}
          />
        ) : (
          <section aria-label="Followers" className="space-y-1">
            <h3 className="text-sm font-semibold">Followers</h3>
            <p className="text-sm text-muted-foreground">
              Instagram only shares follower details for accounts with 100 or more followers.
            </p>
          </section>
        )}
        {showEngaged &&
          (engagedPresent ? (
            <AudienceSection
              id="engaged"
              title="Engaged this month"
              subtitle="People who engaged with your posts and Reels this month."
              fetchedAt={data.engagedFetchedAt}
              ageGenderBreakdown={data.engagedAgeGenderBreakdown}
              countryBreakdown={data.engagedCountryBreakdown}
              cityBreakdown={data.engagedCityBreakdown}
            />
          ) : (
            <section aria-labelledby="audience-engaged-heading" className="space-y-1">
              <h3 id="audience-engaged-heading" className="text-sm font-semibold">
                Engaged this month
              </h3>
              <p className="text-sm text-muted-foreground">
                {engagedReasonText(data.engagedStatus)}
              </p>
            </section>
          ))}
      </CardContent>
    </Card>
  );
}

export default AudienceDemographicsPanel;
