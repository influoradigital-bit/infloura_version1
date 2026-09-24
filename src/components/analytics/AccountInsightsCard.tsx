import { Eye, Heart, Link2, UserCheck, Users } from 'lucide-react';

import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Empty, EmptyDescription, EmptyHeader, EmptyTitle } from '@/components/ui/empty';
import { Skeleton } from '@/components/ui/skeleton';
import type { CreatorAccountInsights } from '@/lib/api';
import { cn } from '@/lib/utils';

const TILES: {
  key: keyof Pick<
    CreatorAccountInsights,
    'reach' | 'views' | 'totalInteractions' | 'accountsEngaged' | 'profileLinksTaps'
  >;
  label: string;
  hint: string;
  icon: React.ComponentType<{ className?: string }>;
}[] = [
  { key: 'reach', label: 'Accounts reached', hint: 'Different accounts that saw your content', icon: Users },
  { key: 'views', label: 'Views', hint: 'Times your content was played or shown', icon: Eye },
  { key: 'totalInteractions', label: 'Interactions', hint: 'Likes, comments, saves, shares and replies', icon: Heart },
  { key: 'accountsEngaged', label: 'Accounts engaged', hint: 'Different accounts that interacted', icon: UserCheck },
  { key: 'profileLinksTaps', label: 'Profile link taps', hint: 'Taps on the links in your bio', icon: Link2 },
];

/** "2026-08-27" -> "27 Aug 2026", read as a calendar date (never shifted by the viewer's zone). */
function formatDay(isoDate: string): string {
  const [y, m, d] = isoDate.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d)).toLocaleDateString('en-IN', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    timeZone: 'UTC',
  });
}

function formatCount(n: number): string {
  return new Intl.NumberFormat('en-IN').format(n);
}

export interface AccountInsightsCardProps {
  data: CreatorAccountInsights | null;
  loading?: boolean;
  error?: string | null;
  className?: string;
}

/**
 * The creator's own Instagram account over the last 28 full days (2026-09-24): accounts reached,
 * views, interactions, accounts engaged and profile-link taps, straight from Meta's account
 * insights. A number Instagram did not report shows "Not reported", never 0; with nothing fetched
 * yet the card says when numbers will arrive.
 */
export function AccountInsightsCard({ data, loading = false, error, className }: AccountInsightsCardProps) {
  const title = (
    <CardTitle className="text-sm font-medium text-muted-foreground">Your account, last 28 days</CardTitle>
  );

  if (loading) {
    return (
      <Card className={className}>
        <CardHeader className="pb-2">
          <Skeleton className="h-4 w-44" />
        </CardHeader>
        <CardContent className="grid grid-cols-2 gap-4 sm:grid-cols-5">
          {TILES.map((t) => (
            <div key={t.key} className="space-y-2">
              <Skeleton className="h-3 w-20" />
              <Skeleton className="h-6 w-16" />
            </div>
          ))}
        </CardContent>
      </Card>
    );
  }

  if (error) {
    return (
      <Card className={className}>
        <CardHeader className="pb-2">{title}</CardHeader>
        <CardContent>
          <p className="text-sm text-destructive-foreground">Couldn't load your account numbers: {error}</p>
        </CardContent>
      </Card>
    );
  }

  if (!data || !data.hasData) {
    return (
      <Card className={className}>
        <CardHeader className="pb-2">{title}</CardHeader>
        <CardContent>
          <Empty className="border-0 p-0">
            <EmptyHeader>
              <Users className="h-8 w-8 text-muted-foreground" aria-hidden="true" />
              <EmptyTitle>No account numbers yet</EmptyTitle>
              <EmptyDescription>
                Your reach, views and interactions arrive a few minutes after Instagram is connected, then
                update every morning.
              </EmptyDescription>
            </EmptyHeader>
          </Empty>
        </CardContent>
      </Card>
    );
  }

  return (
    <Card className={className} data-testid="account-insights-card">
      <CardHeader className="pb-2">
        {title}
        {data.periodStart && data.periodEnd ? (
          <p className="text-xs text-muted-foreground">
            {formatDay(data.periodStart)} to {formatDay(data.periodEnd)}, from Instagram
          </p>
        ) : null}
      </CardHeader>
      <CardContent className="grid grid-cols-2 gap-4 sm:grid-cols-5" role="list" aria-label="Account numbers">
        {TILES.map(({ key, label, hint, icon: Icon }) => {
          const value = data[key];
          return (
            <div key={key} role="listitem" className="min-w-0" title={hint}>
              <p className="flex items-center gap-1.5 text-xs text-muted-foreground">
                <Icon className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
                <span className="truncate">{label}</span>
              </p>
              <p className={cn('mt-1 font-semibold', value === null ? 'text-sm text-muted-foreground' : 'text-xl')}>
                {value === null ? 'Not reported' : formatCount(value)}
              </p>
            </div>
          );
        })}
      </CardContent>
    </Card>
  );
}

export default AccountInsightsCard;
