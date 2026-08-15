import { useMemo } from 'react';
import { useReducedMotion } from 'framer-motion';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Legend } from 'recharts';

import { Card, CardHeader, CardTitle, CardDescription, CardContent } from '@/components/ui/card';
import {
  ChartContainer,
  ChartTooltip,
  ChartTooltipContent,
  type ChartConfig,
} from '@/components/ui/chart';
import { Skeleton } from '@/components/ui/skeleton';
import { Empty, EmptyHeader, EmptyTitle, EmptyDescription } from '@/components/ui/empty';
import type { MetricDataPoint } from '@/lib/types';
import { cssVars } from '@/lib/css-vars';

interface TrendMetric {
  key: keyof Pick<MetricDataPoint, 'followers' | 'impressions' | 'reach' | 'engagementRate'>;
  label: string;
  color: string;
}

interface MetricsTrendChartProps {
  title: string;
  description?: string;
  data: MetricDataPoint[];
  metrics: TrendMetric[];
  loading?: boolean;
  height?: number;
  className?: string;
}

/**
 * Trend line chart for a metrics series. Adapted from
 * ANANYA_FRONTEND_IMPLEMENTATION_SPEC.md section 2.1 — the spec assumed a
 * populated trendData array always exists; the real backend
 * (CreatorMetricsResponse.trendData) is only non-empty when both
 * startDate/endDate were passed, so this renders an explicit empty state
 * instead of an empty chart when data is [].
 */
export function MetricsTrendChart({
  title,
  description,
  data,
  metrics,
  loading = false,
  height = 300,
  className,
}: MetricsTrendChartProps) {
  const shouldReduceMotion = useReducedMotion();

  const chartConfig: ChartConfig = useMemo(
    () =>
      metrics.reduce(
        (acc, metric) => ({
          ...acc,
          [metric.key]: { label: metric.label, color: metric.color },
        }),
        {} as ChartConfig,
      ),
    [metrics],
  );

  if (loading) {
    return (
      <Card className={className}>
        <CardHeader>
          <Skeleton className="h-5 w-32" />
          <Skeleton className="mt-1 h-4 w-48" />
        </CardHeader>
        <CardContent>
          <div ref={cssVars({ '--chart-h': `${height}px` })}>
            <Skeleton className="w-full h-[var(--chart-h)]" />
          </div>
        </CardContent>
      </Card>
    );
  }

  return (
    <Card className={className}>
      <CardHeader>
        <CardTitle>{title}</CardTitle>
        {description && <CardDescription>{description}</CardDescription>}
      </CardHeader>
      <CardContent>
        <div ref={cssVars({ '--chart-h': `${height}px` })}>
        {data.length === 0 ? (
          <Empty className="border-0 p-0 min-h-[var(--chart-h)]">
            <EmptyHeader>
              <EmptyTitle>No trend data yet</EmptyTitle>
              <EmptyDescription>
                Select a date range to see how this metric changed over time.
              </EmptyDescription>
            </EmptyHeader>
          </Empty>
        ) : (
          <ChartContainer config={chartConfig} className="w-full h-[var(--chart-h)]">
            <LineChart
              data={data}
              margin={{ top: 5, right: 10, left: 10, bottom: 5 }}
              aria-label={`${title} chart showing ${metrics.map((m) => m.label).join(', ')}`}
            >
              <CartesianGrid strokeDasharray="3 3" className="stroke-border/50" />
              <XAxis
                dataKey="date"
                tick={{ fontSize: 12 }}
                tickLine={false}
                axisLine={false}
              />
              <YAxis
                tick={{ fontSize: 12 }}
                tickLine={false}
                axisLine={false}
                tickFormatter={(value: number) =>
                  value >= 1000 ? `${(value / 1000).toFixed(0)}K` : String(value)
                }
              />
              <ChartTooltip content={<ChartTooltipContent />} />
              <Legend />
              {metrics.map((metric) => (
                <Line
                  key={metric.key}
                  type="monotone"
                  dataKey={metric.key}
                  name={metric.label}
                  stroke={`var(--color-${metric.key})`}
                  strokeWidth={2}
                  dot={false}
                  activeDot={{ r: 4 }}
                  connectNulls
                  animationDuration={shouldReduceMotion ? 0 : 750}
                />
              ))}
            </LineChart>
          </ChartContainer>
        )}
        </div>
      </CardContent>
    </Card>
  );
}

export default MetricsTrendChart;
