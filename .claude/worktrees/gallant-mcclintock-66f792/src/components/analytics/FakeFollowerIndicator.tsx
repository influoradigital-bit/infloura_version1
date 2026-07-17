import { useReducedMotion, motion } from 'framer-motion';
import { ShieldCheck, ShieldAlert, ShieldQuestion, Info } from 'lucide-react';

import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/utils';

interface FakeFollowerIndicatorProps {
  /** 0-100 (100 = all real followers). Null = not yet computed. */
  authenticityScore: number | null;
  /** Free-text reasons the score isn't 100 — from CreatorScoresResponse.fakeFollowerReasons. */
  reasons?: string[] | null;
  loading?: boolean;
  className?: string;
}

function getTrustLevel(score: number) {
  if (score >= 90)
    return {
      label: 'Highly Authentic',
      color: 'text-success-foreground',
      bgColor: 'bg-success',
      Icon: ShieldCheck,
    };
  if (score >= 70)
    return {
      label: 'Mostly Authentic',
      color: 'text-primary',
      bgColor: 'bg-primary/10',
      Icon: ShieldCheck,
    };
  if (score >= 50)
    return {
      label: 'Some Concerns',
      color: 'text-amber-600',
      bgColor: 'bg-amber-500/10',
      Icon: ShieldQuestion,
    };
  return {
    label: 'High Risk',
    color: 'text-destructive-foreground',
    bgColor: 'bg-destructive',
    Icon: ShieldAlert,
  };
}

/**
 * Adapted from ANANYA_FRONTEND_IMPLEMENTATION_SPEC.md section 2.1. The spec's
 * props (suspiciousAccounts/massFollowers/inactiveAccounts as numeric
 * breakdown percentages) don't exist on the real CreatorScoresResponse —
 * only authenticityScore and a free-text fakeFollowerReasons list are
 * returned, so the breakdown here lists reasons instead of fabricating
 * numeric categories. Solid *-foreground/amber-600 tokens used for contrast
 * per established brand-CTA-contrast feedback.
 */
export function FakeFollowerIndicator({
  authenticityScore,
  reasons,
  loading = false,
  className,
}: FakeFollowerIndicatorProps) {
  const shouldReduceMotion = useReducedMotion();

  if (loading) {
    return (
      <Card className={className}>
        <CardHeader className="pb-2">
          <Skeleton className="h-4 w-40" />
        </CardHeader>
        <CardContent>
          <div className="flex items-center gap-4">
            <Skeleton className="h-12 w-12 rounded-full" />
            <Skeleton className="h-6 w-24" />
          </div>
        </CardContent>
      </Card>
    );
  }

  if (authenticityScore === null) {
    return (
      <Card className={className}>
        <CardHeader className="pb-2">
          <CardTitle className="text-sm font-medium text-muted-foreground">
            Audience Authenticity
          </CardTitle>
        </CardHeader>
        <CardContent>
          <div className="flex items-center gap-4">
            <div className="flex h-12 w-12 items-center justify-center rounded-full bg-muted">
              <ShieldQuestion className="h-6 w-6 text-muted-foreground" aria-hidden="true" />
            </div>
            <div>
              <p className="font-medium text-muted-foreground">Not yet available</p>
              <p className="text-sm text-muted-foreground">Score has not been computed yet.</p>
            </div>
          </div>
        </CardContent>
      </Card>
    );
  }

  const trustLevel = getTrustLevel(authenticityScore);

  return (
    <Card className={className}>
      <CardHeader className="pb-2">
        <div className="flex items-center justify-between">
          <CardTitle className="text-sm font-medium text-muted-foreground">
            Audience Authenticity
          </CardTitle>
          <TooltipProvider>
            <Tooltip>
              <TooltipTrigger asChild>
                <button
                  className="text-muted-foreground hover:text-foreground"
                  aria-label="Learn more about authenticity scoring"
                >
                  <Info className="h-4 w-4" />
                </button>
              </TooltipTrigger>
              <TooltipContent className="max-w-xs">
                <p>
                  Authenticity score indicates the percentage of real, engaged followers.
                  Calculated by analyzing account activity patterns, engagement ratios, and
                  follower quality.
                </p>
              </TooltipContent>
            </Tooltip>
          </TooltipProvider>
        </div>
      </CardHeader>
      <CardContent>
        <div
          className="flex items-center gap-4"
          role="img"
          aria-label={`Authenticity score: ${authenticityScore}%, ${trustLevel.label}`}
        >
          <div className={cn('flex h-12 w-12 items-center justify-center rounded-full', trustLevel.bgColor)}>
            <trustLevel.Icon className={cn('h-6 w-6', trustLevel.color)} aria-hidden="true" />
          </div>
          <div className="flex-1">
            <div className="flex items-baseline gap-2">
              <motion.span
                className={cn('text-2xl font-bold', trustLevel.color)}
                initial={{ opacity: 0 }}
                animate={{ opacity: 1 }}
                transition={{ duration: shouldReduceMotion ? 0 : 0.3 }}
              >
                {authenticityScore}%
              </motion.span>
              <span className="text-sm text-muted-foreground">Real Followers</span>
            </div>
            <p className={cn('text-sm', trustLevel.color)}>{trustLevel.label}</p>
          </div>
        </div>

        {reasons && reasons.length > 0 && (
          <div className="mt-4 space-y-1.5 border-t border-border pt-4">
            <p className="mb-2 text-xs font-medium text-muted-foreground">Flagged reasons</p>
            <ul className="space-y-1 text-xs text-muted-foreground">
              {reasons.map((reason) => (
                <li key={reason}>{reason}</li>
              ))}
            </ul>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

export default FakeFollowerIndicator;
