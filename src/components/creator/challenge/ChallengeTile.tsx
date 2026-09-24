import * as React from 'react';
import { Link } from 'react-router-dom';
import { Flame } from 'lucide-react';

import { Card, CardContent } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { useCreatorChallenge } from '@/hooks/useCreatorChallenge';
import { challengeCopy } from '@/lib/copy/creator-challenge';
import { cn } from '@/lib/utils';

interface ChallengeTileProps {
  className?: string;
}

/**
 * Compact creator-dashboard tile for the 7-day challenge (CHALLENGE-SPEC.md Frontend §7) —
 * a summary that LINKS to the full `ChallengeCard` on `/creator/copilot`; it deliberately has
 * no Start/End button of its own, so there is exactly one place that mutates challenge state.
 *
 * Shares the query key with `useCreatorChallenge` used on the Co-pilot page, so mounting both
 * is one network request, not two (same dedupe as `useDailySuggestion`'s two mount points).
 * Fails soft: a GET error here renders nothing rather than an error card, matching the
 * dashboard's existing "supplementary tile, never blocks the page" convention (see
 * `fetchPortfolioExtras`'s catch-to-null in creator-dashboard.tsx).
 */
export function ChallengeTile({ className }: ChallengeTileProps) {
  const { data, status } = useCreatorChallenge();
  const copy = React.useMemo(() => challengeCopy(), []);

  if (status === 'loading') {
    return (
      <Card className={className}>
        <CardContent className="py-4">
          <Skeleton className="h-4 w-24" />
          <Skeleton className="mt-2 h-3 w-32" />
        </CardContent>
      </Card>
    );
  }

  if (status === 'error' || !data) return null;

  const body = (() => {
    if (!data.instagramConnected) {
      return { title: '7-day challenge', subtitle: 'Connect Instagram to start' };
    }
    if (data.active) {
      return {
        title: copy.dayOfLabel(data.active.dayNumber),
        subtitle: copy.streakLabel(data.active.streak),
      };
    }
    if (data.lastCompleted) {
      return {
        title: copy.completedHeadline(data.lastCompleted.daysDone, data.lastCompleted.daysPlanned),
        subtitle: copy.startNextCta,
      };
    }
    return { title: '7-day challenge', subtitle: copy.startCta };
  })();

  return (
    <Link to="/creator/copilot" className="block">
      <Card className={cn('transition-colors hover:bg-accent/40', className)}>
        <CardContent className="flex items-center justify-between gap-3 py-4">
          <div>
            <p className="text-sm font-medium">{body.title}</p>
            <p className="text-xs text-muted-foreground">{body.subtitle}</p>
          </div>
          <Flame className="h-5 w-5 shrink-0 text-primary" aria-hidden="true" />
        </CardContent>
      </Card>
    </Link>
  );
}

export default ChallengeTile;
