import { useReducedMotion } from 'framer-motion';
import { TrendingUp, TrendingDown, Minus } from 'lucide-react';

import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { CountUp } from '@/components/motion/CountUp';
import { cn } from '@/lib/utils';

type MetricFormat = 'number' | 'percentage' | 'currency' | 'compact';

interface CreatorMetricsCardProps {
  title: string;
  value: number;
  previousValue?: number;
  format?: MetricFormat;
  currency?: string;
  icon?: React.ComponentType<{ className?: string }>;
  loading?: boolean;
  className?: string;
}

function formatValue(val: number, format: MetricFormat, currency: string): string {
  switch (format) {
    case 'percentage':
      return `${val.toFixed(1)}%`;
    case 'currency':
      return new Intl.NumberFormat('en-IN', {
        style: 'currency',
        currency,
        notation: val >= 100000 ? 'compact' : 'standard',
        maximumFractionDigits: 0,
      }).format(val);
    case 'compact':
      return new Intl.NumberFormat('en-IN', { notation: 'compact' }).format(val);
    default:
      return new Intl.NumberFormat('en-IN').format(val);
  }
}

/**
 * Single metric tile for the analytics dashboards. Adapted from
 * ANANYA_FRONTEND_IMPLEMENTATION_SPEC.md section 2.1 — swapped the spec's
 * assumed CountUp API (end/format/currency props) for this repo's real
 * CountUp component (src/components/motion/CountUp.tsx), which takes
 * value/formatFn. Trend colors use *-foreground tokens (solid, WCAG-AA),
 * not the pale chart-2/destructive background tokens, per established
 * brand-CTA-contrast feedback in this repo.
 */
export function CreatorMetricsCard({
  title,
  value,
  previousValue,
  format = 'number',
  currency = 'INR',
  icon: Icon,
  loading = false,
  className,
}: CreatorMetricsCardProps) {
  const shouldReduceMotion = useReducedMotion();

  const trend =
    previousValue !== undefined && previousValue !== 0
      ? ((value - previousValue) / previousValue) * 100
      : undefined;

  if (loading) {
    return (
      <Card className={className}>
        <CardHeader className="flex flex-row items-center justify-between pb-2">
          <Skeleton className="h-4 w-24" />
          <Skeleton className="h-4 w-4" />
        </CardHeader>
        <CardContent>
          <Skeleton className="mb-2 h-8 w-20" />
          <Skeleton className="h-3 w-16" />
        </CardContent>
      </Card>
    );
  }

  return (
    <Card
      className={cn('relative overflow-hidden', className)}
      role="region"
      aria-label={`${title}: ${formatValue(value, format, currency)}`}
    >
      <CardHeader className="flex flex-row items-center justify-between pb-2">
        <CardTitle className="text-sm font-medium text-muted-foreground">{title}</CardTitle>
        {Icon && <Icon className="h-4 w-4 text-muted-foreground" aria-hidden="true" />}
      </CardHeader>
      <CardContent>
        <div className="text-2xl font-bold">
          {shouldReduceMotion ? (
            formatValue(value, format, currency)
          ) : (
            <CountUp value={value} formatFn={(n) => formatValue(n, format, currency)} />
          )}
        </div>
        {trend !== undefined && (
          <div
            className={cn(
              'mt-1 flex items-center gap-1 text-xs',
              trend > 0 && 'text-success-foreground',
              trend < 0 && 'text-destructive-foreground',
              trend === 0 && 'text-muted-foreground',
            )}
            aria-label={`${trend > 0 ? 'Increased' : trend < 0 ? 'Decreased' : 'No change'} by ${Math.abs(trend).toFixed(1)}%`}
          >
            {trend > 0 && <TrendingUp className="h-3 w-3" aria-hidden="true" />}
            {trend < 0 && <TrendingDown className="h-3 w-3" aria-hidden="true" />}
            {trend === 0 && <Minus className="h-3 w-3" aria-hidden="true" />}
            <span>
              {trend > 0 ? '+' : ''}
              {trend.toFixed(1)}%
            </span>
            <span className="text-muted-foreground">vs previous</span>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

export default CreatorMetricsCard;
