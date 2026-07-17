import { useReducedMotion, motion } from 'framer-motion';

import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { cn } from '@/lib/utils';

interface EngagementRateGaugeProps {
  /** 0-100 percentage. Null means the rate hasn't been computed yet. */
  rate: number | null;
  /** Industry benchmark, percentage. */
  benchmark?: number;
  className?: string;
}

function getQuality(r: number): { label: string; color: string } {
  if (r >= 6) return { label: 'Excellent', color: 'text-success-foreground' };
  if (r >= 3) return { label: 'Good', color: 'text-primary' };
  if (r >= 1) return { label: 'Average', color: 'text-amber-600' };
  return { label: 'Below Average', color: 'text-destructive-foreground' };
}

/**
 * Adapted from ANANYA_FRONTEND_IMPLEMENTATION_SPEC.md section 2.1. Uses
 * solid *-foreground/amber-600 tokens instead of the spec's pastel
 * chart-2/chart-3/destructive tokens, which fail WCAG AA as text color in
 * this repo's palette (established brand-CTA-contrast feedback). Handles
 * `rate === null` (engagementRate can be null in CreatorMetricsResponse)
 * with an explicit "not yet available" state rather than rendering 0%.
 */
export function EngagementRateGauge({ rate, benchmark = 3.5, className }: EngagementRateGaugeProps) {
  const shouldReduceMotion = useReducedMotion();

  if (rate === null) {
    return (
      <Card className={className}>
        <CardHeader className="pb-2">
          <CardTitle className="text-sm font-medium text-muted-foreground">Engagement Rate</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="flex h-32 flex-col items-center justify-center text-center">
            <span className="text-lg font-semibold text-muted-foreground">Not yet available</span>
            <p className="mt-1 text-xs text-muted-foreground">
              This creator has no engagement data yet.
            </p>
          </div>
        </CardContent>
      </Card>
    );
  }

  const normalizedRate = Math.min(rate / 15, 1) * 100;
  const normalizedBenchmark = Math.min(benchmark / 15, 1) * 100;
  const quality = getQuality(rate);

  return (
    <Card className={className}>
      <CardHeader className="pb-2">
        <CardTitle className="text-sm font-medium text-muted-foreground">Engagement Rate</CardTitle>
      </CardHeader>
      <CardContent>
        <div
          className="relative flex h-32 items-center justify-center"
          role="img"
          aria-label={`Engagement rate: ${rate.toFixed(1)}%, ${quality.label}`}
        >
          <svg viewBox="0 0 100 60" className="h-full w-full">
            <path
              d="M 10 50 A 40 40 0 0 1 90 50"
              fill="none"
              stroke="currentColor"
              strokeWidth="8"
              className="text-muted"
            />
            <motion.path
              d="M 10 50 A 40 40 0 0 1 90 50"
              fill="none"
              stroke="currentColor"
              strokeWidth="8"
              strokeLinecap="round"
              className={quality.color}
              initial={{ pathLength: 0 }}
              animate={{ pathLength: normalizedRate / 100 }}
              transition={{ duration: shouldReduceMotion ? 0 : 1, ease: 'easeOut' }}
            />
            <circle
              cx={10 + (80 * normalizedBenchmark) / 100}
              cy={50 - Math.sin((Math.PI * normalizedBenchmark) / 100) * 40}
              r="3"
              className="fill-muted-foreground"
            />
          </svg>
          <div className="absolute inset-0 flex flex-col items-center justify-center pt-4">
            <span className={cn('text-3xl font-bold', quality.color)}>{rate.toFixed(1)}%</span>
            <span className="text-xs text-muted-foreground">{quality.label}</span>
          </div>
        </div>
        <div className="mt-2 flex justify-between text-xs text-muted-foreground">
          <span>0%</span>
          <span className="flex items-center gap-1">
            <span className="h-2 w-2 rounded-full bg-muted-foreground" aria-hidden="true" />
            Benchmark: {benchmark}%
          </span>
          <span>15%+</span>
        </div>
      </CardContent>
    </Card>
  );
}

export default EngagementRateGauge;
