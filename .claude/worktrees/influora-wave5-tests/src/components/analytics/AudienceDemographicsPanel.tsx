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
}

const AGE_GENDER_LABELS: Record<string, string> = {};

/** "18-24_female" -> "18-24 · Female" (falls back to the raw key if it doesn't match Meta's shape). */
function formatAgeGenderBucket(key: string): string {
  if (AGE_GENDER_LABELS[key]) return AGE_GENDER_LABELS[key];
  const match = key.match(/^([\d.+-]+)_(male|female|unknown)$/i);
  if (!match) return key;
  const [, age, gender] = match;
  return `${age} · ${gender.charAt(0).toUpperCase()}${gender.slice(1)}`;
}

function topEntries(breakdown: Record<string, number> | null, limit: number): [string, number][] {
  if (!breakdown) return [];
  return Object.entries(breakdown)
    .sort((a, b) => b[1] - a[1])
    .slice(0, limit);
}

function BreakdownList({
  title,
  icon: Icon,
  entries,
  formatLabel,
}: {
  title: string;
  icon: React.ComponentType<{ className?: string }>;
  entries: [string, number][];
  formatLabel?: (key: string) => string;
}) {
  const total = entries.reduce((sum, [, count]) => sum + count, 0) || 1;
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
 * Audience demographics panel — Wave B task B5. Wired to
 * GET /analytics/creators/{creatorId}/demographics (B4). Replaces the
 * previous "coming soon" static placeholder in brand-creator-analytics.tsx.
 *
 * `data.hasData === false` is a graceful, expected steady state (the weekly
 * AudienceDemographicsJob hasn't produced a snapshot for this creator yet) —
 * rendered as an explicit informational empty state, never as an error and
 * never with fabricated zero-filled bars. Uses Progress bars (the same
 * primitive QualityScoreDisplay already uses) rather than introducing a new
 * chart type — no recharts BarChart exists anywhere in this codebase yet.
 */
export function AudienceDemographicsPanel({
  data,
  loading = false,
  error,
  className,
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

  if (!data || !data.hasData) {
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
                Demographics will appear after the first weekly audience sync.
              </EmptyDescription>
            </EmptyHeader>
          </Empty>
        </CardContent>
      </Card>
    );
  }

  const ageGender = topEntries(data.ageGenderBreakdown, 8);
  const countries = topEntries(data.countryBreakdown, 6);
  const cities = topEntries(data.cityBreakdown, 6);
  const locales = topEntries(data.localeBreakdown, 6);

  return (
    <Card className={className}>
      <CardHeader className="pb-2">
        <CardTitle className="text-sm font-medium text-muted-foreground">
          Audience Demographics
        </CardTitle>
        {data.fetchedAt && (
          <p className="text-xs text-muted-foreground">
            Last synced {new Date(data.fetchedAt).toLocaleDateString()}
          </p>
        )}
      </CardHeader>
      <CardContent className="grid gap-6 sm:grid-cols-2">
        <BreakdownList
          title="Age & Gender"
          icon={Users}
          entries={ageGender}
          formatLabel={formatAgeGenderBucket}
        />
        <BreakdownList title="Top Countries" icon={Globe2} entries={countries} />
        <BreakdownList title="Top Cities" icon={MapPin} entries={cities} />
        <BreakdownList title="Locales" icon={Languages} entries={locales} />
      </CardContent>
    </Card>
  );
}

export default AudienceDemographicsPanel;
