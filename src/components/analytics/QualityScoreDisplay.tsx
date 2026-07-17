import { useReducedMotion, motion } from 'framer-motion';
import { Star, TrendingUp, MessageCircle, Users } from 'lucide-react';

import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Progress } from '@/components/ui/progress';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/utils';

interface QualityScoreDisplayProps {
  /** 0-100. Null = not yet computed. */
  overallScore: number | null;
  engagementConsistency?: number | null;
  postingFrequency?: number | null;
  audienceMatchScore?: number | null;
  loading?: boolean;
  className?: string;
}

function getScoreColor(score: number) {
  if (score >= 80) return 'text-success-foreground';
  if (score >= 60) return 'text-primary';
  if (score >= 40) return 'text-amber-600';
  return 'text-destructive-foreground';
}

/**
 * Adapted from ANANYA_FRONTEND_IMPLEMENTATION_SPEC.md section 2.1. The spec's
 * generic `metrics` prop assumed a "Content Quality" sub-metric that has no
 * backend field — CreatorScoresResponse only has qualityScore (the overall
 * score), engagementConsistency, postingFrequency, and audienceMatchScore, so
 * those three are the only sub-metric rows rendered. Solid *-foreground/
 * amber-600 tokens used for contrast per established brand-CTA-contrast
 * feedback.
 */
export function QualityScoreDisplay({
  overallScore,
  engagementConsistency,
  postingFrequency,
  audienceMatchScore,
  loading = false,
  className,
}: QualityScoreDisplayProps) {
  const shouldReduceMotion = useReducedMotion();

  if (loading) {
    return (
      <Card className={className}>
        <CardHeader className="pb-2">
          <Skeleton className="h-4 w-28" />
        </CardHeader>
        <CardContent>
          <div className="mb-6 flex items-center gap-4">
            <Skeleton className="h-20 w-20 rounded-full" />
            <Skeleton className="h-6 w-24" />
          </div>
        </CardContent>
      </Card>
    );
  }

  if (overallScore === null) {
    return (
      <Card className={className}>
        <CardHeader className="pb-2">
          <CardTitle className="text-sm font-medium text-muted-foreground">Quality Score</CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-sm text-muted-foreground">
            Not yet available — this creator hasn't been scored yet.
          </p>
        </CardContent>
      </Card>
    );
  }

  const metrics = [
    { key: 'engagement', label: 'Engagement Consistency', score: engagementConsistency, icon: MessageCircle },
    { key: 'consistency', label: 'Posting Frequency', score: postingFrequency, icon: TrendingUp },
    { key: 'audience', label: 'Audience Match', score: audienceMatchScore, icon: Users },
  ].filter((m) => m.score !== undefined && m.score !== null) as {
    key: string;
    label: string;
    score: number;
    icon: React.ComponentType<{ className?: string }>;
  }[];

  return (
    <Card className={className}>
      <CardHeader className="pb-2">
        <CardTitle className="text-sm font-medium text-muted-foreground">Quality Score</CardTitle>
      </CardHeader>
      <CardContent>
        <div
          className="mb-6 flex items-center gap-4"
          role="img"
          aria-label={`Overall quality score: ${overallScore} out of 100`}
        >
          <div className="relative">
            <svg viewBox="0 0 100 100" className="h-20 w-20">
              <circle cx="50" cy="50" r="40" fill="none" stroke="currentColor" strokeWidth="8" className="text-muted" />
              <motion.circle
                cx="50"
                cy="50"
                r="40"
                fill="none"
                stroke="currentColor"
                strokeWidth="8"
                strokeLinecap="round"
                className={getScoreColor(overallScore)}
                strokeDasharray={`${2 * Math.PI * 40}`}
                strokeDashoffset={`${2 * Math.PI * 40 * (1 - overallScore / 100)}`}
                transform="rotate(-90 50 50)"
                initial={{ strokeDashoffset: 2 * Math.PI * 40 }}
                animate={{ strokeDashoffset: 2 * Math.PI * 40 * (1 - overallScore / 100) }}
                transition={{ duration: shouldReduceMotion ? 0 : 1, ease: 'easeOut' }}
              />
            </svg>
            <div className="absolute inset-0 flex items-center justify-center">
              <span className={cn('text-xl font-bold', getScoreColor(overallScore))}>{overallScore}</span>
            </div>
          </div>
          <div>
            <div className="flex items-center gap-1">
              {[1, 2, 3, 4, 5].map((star) => (
                <Star
                  key={star}
                  className={cn(
                    'h-4 w-4',
                    star <= Math.round(overallScore / 20) ? 'fill-amber-500 text-amber-500' : 'text-muted',
                  )}
                  aria-hidden="true"
                />
              ))}
            </div>
            <p className="mt-1 text-sm text-muted-foreground">
              {overallScore >= 80
                ? 'Excellent'
                : overallScore >= 60
                  ? 'Good'
                  : overallScore >= 40
                    ? 'Average'
                    : 'Needs Improvement'}
            </p>
          </div>
        </div>

        {metrics.length > 0 && (
          <div className="space-y-3" role="list" aria-label="Quality metrics breakdown">
            {metrics.map((metric) => (
              <div key={metric.key} role="listitem">
                <div className="mb-1 flex items-center justify-between text-sm">
                  <span className="flex items-center gap-2">
                    <metric.icon className="h-4 w-4 text-muted-foreground" aria-hidden="true" />
                    {metric.label}
                  </span>
                  <span className={getScoreColor(metric.score)}>{metric.score}</span>
                </div>
                <Progress value={metric.score} className="h-1.5" aria-label={`${metric.label}: ${metric.score}%`} />
              </div>
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  );
}

export default QualityScoreDisplay;
