# ANANYA: Brand Analytics UI - Frontend Implementation Spec

**Author:** Priya (CTO)  
**Date:** 2026-07-06  
**Sprint Duration:** 6 weeks  
**Target:** Production-ready Brand Analytics Dashboard

---

## Executive Summary

This spec covers the complete frontend implementation for the Brand Analytics feature set. You will build analytics dashboards, creator metrics visualization, campaign tracking (UTM/coupon), and creator comparison views. All work MUST follow TECH-STACK.md rules and DESIGN_SYSTEM.md tokens.

**Critical Requirements (Non-Negotiable):**
- TypeScript strict mode, NO `any` types
- WCAG AA accessibility (4.5:1 contrast, keyboard nav, aria-labels)
- `useReducedMotion()` bypass for ALL animations
- Mobile-first responsive design
- Existing shadcn/ui components from `components/ui/`

---

## 1. Analytics Dashboard Pages

### 1.1 Route Structure

Create these routes under `src/app/dashboard/` (Next.js App Router):

```
src/app/dashboard/
├── analytics/
│   ├── page.tsx                    # Main analytics overview
│   ├── loading.tsx                 # Skeleton loading state
│   ├── error.tsx                   # Error boundary
│   └── [creatorId]/
│       ├── page.tsx                # Individual creator analytics
│       └── loading.tsx
├── analytics/compare/
│   ├── page.tsx                    # Side-by-side comparison
│   └── loading.tsx
└── campaigns/[id]/tracking/
    ├── page.tsx                    # UTM/conversion tracking view
    └── loading.tsx
```

### 1.2 Main Analytics Overview (`/dashboard/analytics`)

**Layout:**
```
┌─────────────────────────────────────────────────────────────────┐
│ Analytics Overview                           [Date Range Picker]│
├─────────────────────────────────────────────────────────────────┤
│ ┌───────────┐ ┌───────────┐ ┌───────────┐ ┌───────────────────┐ │
│ │Total Reach│ │Engagement │ │Conversions│ │   Avg. ROI       │ │
│ │ 2.4M ↑12% │ │ 4.8% ↑0.3 │ │ 1,247 ↑8% │ │   3.2x ↑0.4x     │ │
│ └───────────┘ └───────────┘ └───────────┘ └───────────────────┘ │
├─────────────────────────────────────────────────────────────────┤
│ [Reach Over Time - Line Chart]              [Engagement by      │
│                                              Platform - Bar]    │
├─────────────────────────────────────────────────────────────────┤
│ Top Performing Creators          │ Campaign Performance         │
│ ┌────────────────────────────────┤ ┌────────────────────────────│
│ │ 1. @sarahcreates  4.8% eng    │ │ Summer Launch     65% ▓▓▓░░│
│ │ 2. @mikephoto     4.2% eng    │ │ Holiday Guide     40% ▓▓░░░│
│ │ 3. @lifestylejane 6.1% eng    │ │ Ambassador        0%  ░░░░░│
│ └────────────────────────────────┘ └────────────────────────────┘
└─────────────────────────────────────────────────────────────────┘
```

**File:** `src/app/dashboard/analytics/page.tsx`

```typescript
// Required structure - implement with proper types
'use client';

import { useState } from 'react';
import { useCreatorMetrics } from '@/hooks/analytics/useCreatorMetrics';
import { AnalyticsSummaryCards } from '@/components/analytics/AnalyticsSummaryCards';
import { MetricsTrendChart } from '@/components/analytics/MetricsTrendChart';
import { TopCreatorsTable } from '@/components/analytics/TopCreatorsTable';
import { CampaignPerformanceList } from '@/components/analytics/CampaignPerformanceList';
import type { DateRange } from '@/lib/types';

export default function AnalyticsOverviewPage() {
  const [dateRange, setDateRange] = useState<DateRange>({ /* ... */ });
  // Implementation follows...
}
```

### 1.3 Individual Creator Analytics (`/dashboard/analytics/[creatorId]`)

**Layout:**
```
┌─────────────────────────────────────────────────────────────────┐
│ ← Back │ @sarahcreates Analytics     [Export] [Compare]         │
├─────────────────────────────────────────────────────────────────┤
│ ┌─────────────────────────────────────────────────────────────┐ │
│ │ CREATOR HEADER                                              │ │
│ │ [Avatar] Sarah Creates        ✓ Verified                    │ │
│ │          Los Angeles, CA      Quality: 92 │ Safety: A+      │ │
│ └─────────────────────────────────────────────────────────────┘ │
├─────────────────────────────────────────────────────────────────┤
│ ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────────┐ │
│ │ Followers  │ │ Engagement │ │ Avg. Views │ │ Fake Follower  │ │
│ │ 950K       │ │ 4.8%       │ │ 125K       │ │ Score: 97%     │ │
│ └────────────┘ └────────────┘ └────────────┘ └────────────────┘ │
├─────────────────────────────────────────────────────────────────┤
│ Follower Growth (30d)          │ Audience Demographics         │
│ [Line Chart - growth trend]    │ [Age/Gender/Location panels]  │
├─────────────────────────────────────────────────────────────────┤
│ Platform Breakdown             │ Content Performance           │
│ [Instagram, TikTok, YouTube]   │ [Top posts by engagement]     │
└─────────────────────────────────────────────────────────────────┘
```

### 1.4 Creator Comparison (`/dashboard/analytics/compare`)

**Features:**
- Multi-select up to 4 creators
- Side-by-side metric cards
- Overlaid trend charts
- Radar chart for category comparison

### 1.5 Campaign Tracking (`/dashboard/campaigns/[id]/tracking`)

**Layout:**
```
┌─────────────────────────────────────────────────────────────────┐
│ Summer Collection Launch - Tracking                             │
├─────────────────────────────────────────────────────────────────┤
│ [UTM Link Generator]  │  [Coupon Generator]  │  [Export Data]   │
├─────────────────────────────────────────────────────────────────┤
│ CONVERSION FUNNEL                                               │
│ Impressions → Clicks → Add to Cart → Purchase                   │
│ 1.2M         48K        12K           3.2K                      │
│ [Visual funnel with animated transitions]                       │
├─────────────────────────────────────────────────────────────────┤
│ Creator Attribution                │ Coupon Redemptions         │
│ ┌──────────────────────────────────┤ ┌──────────────────────────│
│ │ Creator      │ Clicks │ Conv. │$ │ │ Code        │ Uses │ Rev │
│ │ @sarahcreates│ 12,450 │  245  │8K│ │ SARAH15     │  89  │ $4K │
│ │ @mikephoto   │  8,200 │  178  │6K│ │ MIKE10      │  56  │ $2K │
│ └──────────────────────────────────┘ └──────────────────────────┘
└─────────────────────────────────────────────────────────────────┘
```

---

## 2. Components to Build

### 2.1 Creator Analytics Components

All components go in `src/components/analytics/`:

#### CreatorMetricsCard.tsx

```typescript
// src/components/analytics/CreatorMetricsCard.tsx
'use client';

import { useReducedMotion } from 'framer-motion';
import { TrendingUp, TrendingDown, Minus } from 'lucide-react';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { CountUp } from '@/components/motion/CountUp';
import { cn } from '@/lib/utils';

interface CreatorMetricsCardProps {
  title: string;
  value: number;
  previousValue?: number;
  format?: 'number' | 'percentage' | 'currency' | 'compact';
  currency?: string;
  icon?: React.ComponentType<{ className?: string }>;
  loading?: boolean;
  className?: string;
}

export function CreatorMetricsCard({
  title,
  value,
  previousValue,
  format = 'number',
  currency = 'USD',
  icon: Icon,
  loading = false,
  className,
}: CreatorMetricsCardProps) {
  const shouldReduceMotion = useReducedMotion();
  
  // Calculate trend
  const trend = previousValue 
    ? ((value - previousValue) / previousValue) * 100 
    : undefined;
  
  const formatValue = (val: number): string => {
    switch (format) {
      case 'percentage':
        return `${val.toFixed(1)}%`;
      case 'currency':
        return new Intl.NumberFormat('en-US', {
          style: 'currency',
          currency,
          notation: val >= 10000 ? 'compact' : 'standard',
        }).format(val);
      case 'compact':
        return new Intl.NumberFormat('en-US', { notation: 'compact' }).format(val);
      default:
        return new Intl.NumberFormat('en-US').format(val);
    }
  };

  if (loading) {
    return (
      <Card className={className}>
        <CardHeader className="flex flex-row items-center justify-between pb-2">
          <Skeleton className="h-4 w-24" />
          <Skeleton className="h-4 w-4" />
        </CardHeader>
        <CardContent>
          <Skeleton className="h-8 w-20 mb-2" />
          <Skeleton className="h-3 w-16" />
        </CardContent>
      </Card>
    );
  }

  return (
    <Card 
      className={cn('relative overflow-hidden', className)}
      role="region"
      aria-label={`${title}: ${formatValue(value)}`}
    >
      <CardHeader className="flex flex-row items-center justify-between pb-2">
        <CardTitle className="text-sm font-medium text-muted-foreground">
          {title}
        </CardTitle>
        {Icon && <Icon className="h-4 w-4 text-muted-foreground" aria-hidden="true" />}
      </CardHeader>
      <CardContent>
        <div className="text-2xl font-bold">
          {shouldReduceMotion ? (
            formatValue(value)
          ) : (
            <CountUp end={value} format={format} currency={currency} />
          )}
        </div>
        {trend !== undefined && (
          <div 
            className={cn(
              'flex items-center gap-1 text-xs mt-1',
              trend > 0 && 'text-chart-2',
              trend < 0 && 'text-destructive',
              trend === 0 && 'text-muted-foreground'
            )}
            aria-label={`${trend > 0 ? 'Increased' : trend < 0 ? 'Decreased' : 'No change'} by ${Math.abs(trend).toFixed(1)}%`}
          >
            {trend > 0 && <TrendingUp className="h-3 w-3" aria-hidden="true" />}
            {trend < 0 && <TrendingDown className="h-3 w-3" aria-hidden="true" />}
            {trend === 0 && <Minus className="h-3 w-3" aria-hidden="true" />}
            <span>{trend > 0 ? '+' : ''}{trend.toFixed(1)}%</span>
            <span className="text-muted-foreground">vs previous</span>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
```

#### MetricsTrendChart.tsx

```typescript
// src/components/analytics/MetricsTrendChart.tsx
'use client';

import { useMemo } from 'react';
import { useReducedMotion } from 'framer-motion';
import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  Legend,
} from 'recharts';
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from '@/components/ui/card';
import { ChartContainer, ChartTooltip, ChartTooltipContent, type ChartConfig } from '@/components/ui/chart';
import { Skeleton } from '@/components/ui/skeleton';
import type { MetricDataPoint } from '@/lib/types';

interface MetricsTrendChartProps {
  title: string;
  description?: string;
  data: MetricDataPoint[];
  metrics: {
    key: string;
    label: string;
    color: string;
  }[];
  loading?: boolean;
  height?: number;
  className?: string;
}

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

  const chartConfig: ChartConfig = useMemo(() => {
    return metrics.reduce((acc, metric) => ({
      ...acc,
      [metric.key]: {
        label: metric.label,
        color: metric.color,
      },
    }), {} as ChartConfig);
  }, [metrics]);

  if (loading) {
    return (
      <Card className={className}>
        <CardHeader>
          <Skeleton className="h-5 w-32" />
          <Skeleton className="h-4 w-48 mt-1" />
        </CardHeader>
        <CardContent>
          <Skeleton className="w-full" style={{ height }} />
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
        <ChartContainer config={chartConfig} className="w-full" style={{ height }}>
          <LineChart
            data={data}
            margin={{ top: 5, right: 10, left: 10, bottom: 5 }}
            aria-label={`${title} chart showing ${metrics.map(m => m.label).join(', ')}`}
          >
            <CartesianGrid strokeDasharray="3 3" className="stroke-border/50" />
            <XAxis 
              dataKey="date" 
              tick={{ fontSize: 12 }}
              tickLine={false}
              axisLine={false}
              aria-label="Date"
            />
            <YAxis 
              tick={{ fontSize: 12 }}
              tickLine={false}
              axisLine={false}
              tickFormatter={(value) => 
                value >= 1000 ? `${(value / 1000).toFixed(0)}K` : value
              }
              aria-label="Value"
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
                animationDuration={shouldReduceMotion ? 0 : 750}
              />
            ))}
          </LineChart>
        </ChartContainer>
      </CardContent>
    </Card>
  );
}
```

#### AudienceDemographicsPanel.tsx

```typescript
// src/components/analytics/AudienceDemographicsPanel.tsx
'use client';

import { useReducedMotion } from 'framer-motion';
import {
  PieChart,
  Pie,
  Cell,
  BarChart,
  Bar,
  XAxis,
  YAxis,
  ResponsiveContainer,
} from 'recharts';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { ChartContainer, ChartConfig } from '@/components/ui/chart';
import { Skeleton } from '@/components/ui/skeleton';
import { Progress } from '@/components/ui/progress';
import type { AudienceDemographics } from '@/lib/types';

interface AudienceDemographicsPanelProps {
  data: AudienceDemographics | null;
  loading?: boolean;
  className?: string;
}

const GENDER_COLORS = {
  male: '#3b82f6',     // blue-500
  female: '#ec4899',   // pink-500
  other: '#8b5cf6',    // violet-500
};

const AGE_COLORS = ['#06b6d4', '#3b82f6', '#8b5cf6', '#ec4899', '#f59e0b'];

export function AudienceDemographicsPanel({
  data,
  loading = false,
  className,
}: AudienceDemographicsPanelProps) {
  const shouldReduceMotion = useReducedMotion();

  if (loading) {
    return (
      <Card className={className}>
        <CardHeader>
          <Skeleton className="h-5 w-40" />
        </CardHeader>
        <CardContent>
          <Skeleton className="h-64 w-full" />
        </CardContent>
      </Card>
    );
  }

  if (!data) {
    return (
      <Card className={className}>
        <CardHeader>
          <CardTitle>Audience Demographics</CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-sm text-muted-foreground">
            No demographic data available for this creator.
          </p>
        </CardContent>
      </Card>
    );
  }

  const genderData = [
    { name: 'Male', value: data.genderSplit.male, color: GENDER_COLORS.male },
    { name: 'Female', value: data.genderSplit.female, color: GENDER_COLORS.female },
    { name: 'Other', value: data.genderSplit.other, color: GENDER_COLORS.other },
  ].filter(g => g.value > 0);

  const ageData = data.ageBrackets.map((bracket, i) => ({
    ...bracket,
    fill: AGE_COLORS[i % AGE_COLORS.length],
  }));

  const chartConfig: ChartConfig = {
    male: { label: 'Male', color: GENDER_COLORS.male },
    female: { label: 'Female', color: GENDER_COLORS.female },
    other: { label: 'Other', color: GENDER_COLORS.other },
  };

  return (
    <Card className={className}>
      <CardHeader>
        <CardTitle>Audience Demographics</CardTitle>
      </CardHeader>
      <CardContent>
        <Tabs defaultValue="gender" className="w-full">
          <TabsList className="grid w-full grid-cols-3">
            <TabsTrigger value="gender">Gender</TabsTrigger>
            <TabsTrigger value="age">Age</TabsTrigger>
            <TabsTrigger value="location">Location</TabsTrigger>
          </TabsList>
          
          <TabsContent value="gender" className="mt-4">
            <div 
              className="h-48"
              role="img"
              aria-label={`Gender distribution: ${genderData.map(g => `${g.name} ${g.value}%`).join(', ')}`}
            >
              <ChartContainer config={chartConfig} className="h-full w-full">
                <PieChart>
                  <Pie
                    data={genderData}
                    cx="50%"
                    cy="50%"
                    innerRadius={40}
                    outerRadius={70}
                    paddingAngle={2}
                    dataKey="value"
                    animationDuration={shouldReduceMotion ? 0 : 500}
                  >
                    {genderData.map((entry, index) => (
                      <Cell key={`cell-${index}`} fill={entry.color} />
                    ))}
                  </Pie>
                </PieChart>
              </ChartContainer>
            </div>
            <div className="flex justify-center gap-4 mt-4">
              {genderData.map((g) => (
                <div key={g.name} className="flex items-center gap-2">
                  <div 
                    className="w-3 h-3 rounded-full" 
                    style={{ backgroundColor: g.color }}
                    aria-hidden="true"
                  />
                  <span className="text-sm">{g.name}: {g.value}%</span>
                </div>
              ))}
            </div>
          </TabsContent>
          
          <TabsContent value="age" className="mt-4">
            <div 
              className="h-48"
              role="img"
              aria-label={`Age distribution: ${ageData.map(a => `${a.range} ${a.percentage}%`).join(', ')}`}
            >
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={ageData} layout="vertical">
                  <XAxis type="number" domain={[0, 100]} tickFormatter={(v) => `${v}%`} />
                  <YAxis type="category" dataKey="range" width={60} />
                  <Bar 
                    dataKey="percentage" 
                    radius={[0, 4, 4, 0]}
                    animationDuration={shouldReduceMotion ? 0 : 500}
                  >
                    {ageData.map((entry, index) => (
                      <Cell key={`cell-${index}`} fill={entry.fill} />
                    ))}
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
            </div>
          </TabsContent>
          
          <TabsContent value="location" className="mt-4">
            <div className="space-y-3" role="list" aria-label="Top locations by audience">
              {data.topLocations.map((loc, i) => (
                <div key={loc.country} className="space-y-1" role="listitem">
                  <div className="flex justify-between text-sm">
                    <span>{loc.country}</span>
                    <span className="text-muted-foreground">{loc.percentage}%</span>
                  </div>
                  <Progress 
                    value={loc.percentage} 
                    className="h-2"
                    aria-label={`${loc.country}: ${loc.percentage}%`}
                  />
                </div>
              ))}
            </div>
          </TabsContent>
        </Tabs>
      </CardContent>
    </Card>
  );
}
```

#### EngagementRateGauge.tsx

```typescript
// src/components/analytics/EngagementRateGauge.tsx
'use client';

import { useReducedMotion, motion } from 'framer-motion';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { cn } from '@/lib/utils';

interface EngagementRateGaugeProps {
  rate: number;           // 0-100 percentage
  benchmark?: number;     // Industry benchmark
  className?: string;
}

export function EngagementRateGauge({
  rate,
  benchmark = 3.5,
  className,
}: EngagementRateGaugeProps) {
  const shouldReduceMotion = useReducedMotion();
  
  // Normalize rate for gauge (max 15% for visualization)
  const normalizedRate = Math.min(rate / 15, 1) * 100;
  const normalizedBenchmark = Math.min(benchmark / 15, 1) * 100;
  
  const getQuality = (r: number): { label: string; color: string } => {
    if (r >= 6) return { label: 'Excellent', color: 'text-chart-2' };
    if (r >= 3) return { label: 'Good', color: 'text-primary' };
    if (r >= 1) return { label: 'Average', color: 'text-chart-3' };
    return { label: 'Below Average', color: 'text-destructive' };
  };
  
  const quality = getQuality(rate);

  return (
    <Card className={className}>
      <CardHeader className="pb-2">
        <CardTitle className="text-sm font-medium text-muted-foreground">
          Engagement Rate
        </CardTitle>
      </CardHeader>
      <CardContent>
        <div 
          className="relative h-32 flex items-center justify-center"
          role="img"
          aria-label={`Engagement rate: ${rate.toFixed(1)}%, ${quality.label}`}
        >
          {/* SVG Gauge */}
          <svg viewBox="0 0 100 60" className="w-full h-full">
            {/* Background arc */}
            <path
              d="M 10 50 A 40 40 0 0 1 90 50"
              fill="none"
              stroke="currentColor"
              strokeWidth="8"
              className="text-muted"
            />
            {/* Value arc */}
            <motion.path
              d="M 10 50 A 40 40 0 0 1 90 50"
              fill="none"
              stroke="currentColor"
              strokeWidth="8"
              strokeLinecap="round"
              className={quality.color}
              initial={{ pathLength: 0 }}
              animate={{ pathLength: normalizedRate / 100 }}
              transition={{ 
                duration: shouldReduceMotion ? 0 : 1,
                ease: 'easeOut'
              }}
            />
            {/* Benchmark marker */}
            <circle
              cx={10 + (80 * normalizedBenchmark / 100)}
              cy={50 - Math.sin(Math.PI * normalizedBenchmark / 100) * 40}
              r="3"
              className="fill-muted-foreground"
            />
          </svg>
          {/* Center value */}
          <div className="absolute inset-0 flex flex-col items-center justify-center pt-4">
            <span className={cn('text-3xl font-bold', quality.color)}>
              {rate.toFixed(1)}%
            </span>
            <span className="text-xs text-muted-foreground">{quality.label}</span>
          </div>
        </div>
        <div className="flex justify-between text-xs text-muted-foreground mt-2">
          <span>0%</span>
          <span className="flex items-center gap-1">
            <span className="w-2 h-2 rounded-full bg-muted-foreground" aria-hidden="true" />
            Benchmark: {benchmark}%
          </span>
          <span>15%+</span>
        </div>
      </CardContent>
    </Card>
  );
}
```

#### FakeFollowerIndicator.tsx

```typescript
// src/components/analytics/FakeFollowerIndicator.tsx
'use client';

import { useReducedMotion, motion } from 'framer-motion';
import { ShieldCheck, ShieldAlert, ShieldQuestion, Info } from 'lucide-react';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Progress } from '@/components/ui/progress';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip';
import { cn } from '@/lib/utils';

interface FakeFollowerIndicatorProps {
  authenticityScore: number;  // 0-100 (100 = all real followers)
  suspiciousAccounts?: number;
  massFollowers?: number;
  inactiveAccounts?: number;
  loading?: boolean;
  className?: string;
}

export function FakeFollowerIndicator({
  authenticityScore,
  suspiciousAccounts,
  massFollowers,
  inactiveAccounts,
  loading = false,
  className,
}: FakeFollowerIndicatorProps) {
  const shouldReduceMotion = useReducedMotion();
  
  const getTrustLevel = (score: number) => {
    if (score >= 90) return { 
      label: 'Highly Authentic', 
      color: 'text-chart-2',
      bgColor: 'bg-chart-2/10',
      Icon: ShieldCheck 
    };
    if (score >= 70) return { 
      label: 'Mostly Authentic', 
      color: 'text-primary',
      bgColor: 'bg-primary/10',
      Icon: ShieldCheck 
    };
    if (score >= 50) return { 
      label: 'Some Concerns', 
      color: 'text-chart-3',
      bgColor: 'bg-chart-3/10',
      Icon: ShieldQuestion 
    };
    return { 
      label: 'High Risk', 
      color: 'text-destructive',
      bgColor: 'bg-destructive/10',
      Icon: ShieldAlert 
    };
  };
  
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
                  Authenticity score indicates the percentage of real, 
                  engaged followers. Calculated by analyzing account 
                  activity patterns, engagement ratios, and follower quality.
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
          <div className={cn(
            'flex h-12 w-12 items-center justify-center rounded-full',
            trustLevel.bgColor
          )}>
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
        
        {(suspiciousAccounts !== undefined || massFollowers !== undefined || inactiveAccounts !== undefined) && (
          <div className="mt-4 space-y-2 pt-4 border-t border-border">
            <p className="text-xs font-medium text-muted-foreground mb-2">Breakdown</p>
            {suspiciousAccounts !== undefined && (
              <div className="flex justify-between text-xs">
                <span>Suspicious accounts</span>
                <span className="text-muted-foreground">{suspiciousAccounts}%</span>
              </div>
            )}
            {massFollowers !== undefined && (
              <div className="flex justify-between text-xs">
                <span>Mass followers</span>
                <span className="text-muted-foreground">{massFollowers}%</span>
              </div>
            )}
            {inactiveAccounts !== undefined && (
              <div className="flex justify-between text-xs">
                <span>Inactive accounts</span>
                <span className="text-muted-foreground">{inactiveAccounts}%</span>
              </div>
            )}
          </div>
        )}
      </CardContent>
    </Card>
  );
}
```

#### BrandSafetyBadge.tsx

```typescript
// src/components/analytics/BrandSafetyBadge.tsx
'use client';

import { Shield, AlertTriangle, CheckCircle, XCircle, Info } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip';
import { cn } from '@/lib/utils';

// GARM (Global Alliance for Responsible Media) safety categories
type GARMCategory = 
  | 'adult_content'
  | 'arms_ammunition'
  | 'crime_harmful_acts'
  | 'death_injury'
  | 'online_piracy'
  | 'hate_speech'
  | 'obscenity_profanity'
  | 'drugs_tobacco_alcohol'
  | 'spam_malware'
  | 'terrorism'
  | 'debated_social_issues';

type SafetyRating = 'A+' | 'A' | 'B' | 'C' | 'D' | 'F';

interface BrandSafetyBadgeProps {
  overallRating: SafetyRating;
  categoryFlags?: Partial<Record<GARMCategory, boolean>>;
  lastAuditDate?: Date;
  compact?: boolean;
  className?: string;
}

const RATING_CONFIG: Record<SafetyRating, { 
  color: string; 
  bgColor: string; 
  label: string; 
  Icon: React.ComponentType<{ className?: string }>;
}> = {
  'A+': { color: 'text-chart-2', bgColor: 'bg-chart-2/10', label: 'Excellent', Icon: CheckCircle },
  'A': { color: 'text-chart-2', bgColor: 'bg-chart-2/10', label: 'Very Good', Icon: CheckCircle },
  'B': { color: 'text-primary', bgColor: 'bg-primary/10', label: 'Good', Icon: Shield },
  'C': { color: 'text-chart-3', bgColor: 'bg-chart-3/10', label: 'Moderate Risk', Icon: AlertTriangle },
  'D': { color: 'text-destructive', bgColor: 'bg-destructive/10', label: 'High Risk', Icon: AlertTriangle },
  'F': { color: 'text-destructive', bgColor: 'bg-destructive/10', label: 'Unsafe', Icon: XCircle },
};

const CATEGORY_LABELS: Record<GARMCategory, string> = {
  adult_content: 'Adult Content',
  arms_ammunition: 'Arms & Ammunition',
  crime_harmful_acts: 'Crime & Harmful Acts',
  death_injury: 'Death & Injury',
  online_piracy: 'Online Piracy',
  hate_speech: 'Hate Speech',
  obscenity_profanity: 'Obscenity & Profanity',
  drugs_tobacco_alcohol: 'Drugs/Tobacco/Alcohol',
  spam_malware: 'Spam & Malware',
  terrorism: 'Terrorism',
  debated_social_issues: 'Debated Social Issues',
};

export function BrandSafetyBadge({
  overallRating,
  categoryFlags,
  lastAuditDate,
  compact = false,
  className,
}: BrandSafetyBadgeProps) {
  const config = RATING_CONFIG[overallRating];
  const flaggedCategories = categoryFlags 
    ? Object.entries(categoryFlags).filter(([_, flagged]) => flagged)
    : [];

  if (compact) {
    return (
      <TooltipProvider>
        <Tooltip>
          <TooltipTrigger asChild>
            <Badge 
              variant="outline" 
              className={cn('gap-1', config.bgColor, config.color, className)}
              aria-label={`Brand safety rating: ${overallRating}, ${config.label}`}
            >
              <config.Icon className="h-3 w-3" aria-hidden="true" />
              {overallRating}
            </Badge>
          </TooltipTrigger>
          <TooltipContent>
            <p>Brand Safety: {config.label}</p>
            {flaggedCategories.length > 0 && (
              <p className="text-xs text-muted-foreground mt-1">
                {flaggedCategories.length} category flag(s)
              </p>
            )}
          </TooltipContent>
        </Tooltip>
      </TooltipProvider>
    );
  }

  return (
    <Card className={className}>
      <CardHeader className="pb-2">
        <div className="flex items-center justify-between">
          <CardTitle className="text-sm font-medium text-muted-foreground">
            Brand Safety Score
          </CardTitle>
          <TooltipProvider>
            <Tooltip>
              <TooltipTrigger asChild>
                <button 
                  className="text-muted-foreground hover:text-foreground"
                  aria-label="Learn about GARM brand safety standards"
                >
                  <Info className="h-4 w-4" />
                </button>
              </TooltipTrigger>
              <TooltipContent className="max-w-xs">
                <p>
                  Based on GARM (Global Alliance for Responsible Media) 
                  standards. Content is analyzed for brand safety across 
                  11 risk categories.
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
          aria-label={`Brand safety rating: ${overallRating}, ${config.label}`}
        >
          <div className={cn(
            'flex h-14 w-14 items-center justify-center rounded-xl text-2xl font-bold',
            config.bgColor,
            config.color
          )}>
            {overallRating}
          </div>
          <div>
            <p className={cn('font-semibold', config.color)}>{config.label}</p>
            {lastAuditDate && (
              <p className="text-xs text-muted-foreground">
                Last audit: {new Date(lastAuditDate).toLocaleDateString()}
              </p>
            )}
          </div>
        </div>
        
        {flaggedCategories.length > 0 && (
          <div className="mt-4 pt-4 border-t border-border">
            <p className="text-xs font-medium text-muted-foreground mb-2">
              Flagged Categories
            </p>
            <div className="flex flex-wrap gap-1">
              {flaggedCategories.map(([category]) => (
                <Badge 
                  key={category} 
                  variant="secondary" 
                  className="text-xs"
                >
                  {CATEGORY_LABELS[category as GARMCategory]}
                </Badge>
              ))}
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
```

#### QualityScoreDisplay.tsx

```typescript
// src/components/analytics/QualityScoreDisplay.tsx
'use client';

import { useReducedMotion, motion } from 'framer-motion';
import { Star, TrendingUp, Camera, MessageCircle, Users } from 'lucide-react';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Progress } from '@/components/ui/progress';
import { cn } from '@/lib/utils';

interface QualityMetric {
  key: string;
  label: string;
  score: number;  // 0-100
  icon: React.ComponentType<{ className?: string }>;
}

interface QualityScoreDisplayProps {
  overallScore: number;  // 0-100
  metrics?: QualityMetric[];
  className?: string;
}

const DEFAULT_METRICS: Omit<QualityMetric, 'score'>[] = [
  { key: 'content', label: 'Content Quality', icon: Camera },
  { key: 'engagement', label: 'Engagement Quality', icon: MessageCircle },
  { key: 'consistency', label: 'Posting Consistency', icon: TrendingUp },
  { key: 'audience', label: 'Audience Quality', icon: Users },
];

export function QualityScoreDisplay({
  overallScore,
  metrics,
  className,
}: QualityScoreDisplayProps) {
  const shouldReduceMotion = useReducedMotion();
  
  const getScoreColor = (score: number) => {
    if (score >= 80) return 'text-chart-2';
    if (score >= 60) return 'text-primary';
    if (score >= 40) return 'text-chart-3';
    return 'text-destructive';
  };

  return (
    <Card className={className}>
      <CardHeader className="pb-2">
        <CardTitle className="text-sm font-medium text-muted-foreground">
          Quality Score
        </CardTitle>
      </CardHeader>
      <CardContent>
        <div 
          className="flex items-center gap-4 mb-6"
          role="img"
          aria-label={`Overall quality score: ${overallScore} out of 100`}
        >
          <div className="relative">
            <svg viewBox="0 0 100 100" className="w-20 h-20">
              {/* Background circle */}
              <circle
                cx="50"
                cy="50"
                r="40"
                fill="none"
                stroke="currentColor"
                strokeWidth="8"
                className="text-muted"
              />
              {/* Score arc */}
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
              <span className={cn('text-xl font-bold', getScoreColor(overallScore))}>
                {overallScore}
              </span>
            </div>
          </div>
          <div>
            <div className="flex items-center gap-1">
              {[1, 2, 3, 4, 5].map((star) => (
                <Star
                  key={star}
                  className={cn(
                    'h-4 w-4',
                    star <= Math.round(overallScore / 20)
                      ? 'fill-chart-3 text-chart-3'
                      : 'text-muted'
                  )}
                  aria-hidden="true"
                />
              ))}
            </div>
            <p className="text-sm text-muted-foreground mt-1">
              {overallScore >= 80 ? 'Excellent' : 
               overallScore >= 60 ? 'Good' :
               overallScore >= 40 ? 'Average' : 'Needs Improvement'}
            </p>
          </div>
        </div>
        
        {metrics && metrics.length > 0 && (
          <div className="space-y-3" role="list" aria-label="Quality metrics breakdown">
            {metrics.map((metric) => (
              <div key={metric.key} role="listitem">
                <div className="flex items-center justify-between text-sm mb-1">
                  <span className="flex items-center gap-2">
                    <metric.icon className="h-4 w-4 text-muted-foreground" aria-hidden="true" />
                    {metric.label}
                  </span>
                  <span className={getScoreColor(metric.score)}>{metric.score}</span>
                </div>
                <Progress 
                  value={metric.score} 
                  className="h-1.5"
                  aria-label={`${metric.label}: ${metric.score}%`}
                />
              </div>
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  );
}
```

### 2.2 Campaign Tracking Components

All components go in `src/components/campaigns/tracking/`:

#### UTMGeneratorForm.tsx

```typescript
// src/components/campaigns/tracking/UTMGeneratorForm.tsx
'use client';

import { useState, useCallback } from 'react';
import { Copy, Check, Link2, RefreshCw } from 'lucide-react';
import { Card, CardHeader, CardTitle, CardDescription, CardContent, CardFooter } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Button } from '@/components/ui/button';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { useToast } from '@/hooks/use-toast';
import { useGenerateUTMLink } from '@/hooks/analytics/useGenerateUTMLink';
import type { Campaign, CreatorProfile } from '@/lib/types';

interface UTMGeneratorFormProps {
  campaign: Campaign;
  creators: CreatorProfile[];
  className?: string;
}

interface UTMParams {
  source: string;
  medium: string;
  campaign: string;
  content: string;
  term: string;
}

export function UTMGeneratorForm({
  campaign,
  creators,
  className,
}: UTMGeneratorFormProps) {
  const { toast } = useToast();
  const { mutate: generateLink, isPending } = useGenerateUTMLink();
  
  const [selectedCreatorId, setSelectedCreatorId] = useState<string>('');
  const [destinationUrl, setDestinationUrl] = useState(campaign.landingPageUrl || '');
  const [generatedUrl, setGeneratedUrl] = useState<string>('');
  const [copied, setCopied] = useState(false);

  const selectedCreator = creators.find(c => c.id === selectedCreatorId);

  const handleGenerate = useCallback(() => {
    if (!selectedCreatorId || !destinationUrl) {
      toast({
        title: 'Missing information',
        description: 'Please select a creator and enter a destination URL.',
        variant: 'destructive',
      });
      return;
    }

    generateLink(
      {
        campaignId: campaign.id,
        creatorId: selectedCreatorId,
        destinationUrl,
      },
      {
        onSuccess: (data) => {
          setGeneratedUrl(data.trackingUrl);
        },
        onError: () => {
          toast({
            title: 'Generation failed',
            description: 'Could not generate tracking link. Please try again.',
            variant: 'destructive',
          });
        },
      }
    );
  }, [selectedCreatorId, destinationUrl, campaign.id, generateLink, toast]);

  const handleCopy = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(generatedUrl);
      setCopied(true);
      toast({ title: 'Copied to clipboard' });
      setTimeout(() => setCopied(false), 2000);
    } catch {
      toast({
        title: 'Copy failed',
        description: 'Please copy the link manually.',
        variant: 'destructive',
      });
    }
  }, [generatedUrl, toast]);

  return (
    <Card className={className}>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <Link2 className="h-5 w-5" aria-hidden="true" />
          UTM Link Generator
        </CardTitle>
        <CardDescription>
          Create unique tracking links for each creator to measure campaign performance.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="space-y-2">
          <Label htmlFor="creator-select">Creator</Label>
          <Select value={selectedCreatorId} onValueChange={setSelectedCreatorId}>
            <SelectTrigger id="creator-select">
              <SelectValue placeholder="Select a creator" />
            </SelectTrigger>
            <SelectContent>
              {creators.map((creator) => (
                <SelectItem key={creator.id} value={creator.id}>
                  {creator.displayName}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        <div className="space-y-2">
          <Label htmlFor="destination-url">Destination URL</Label>
          <Input
            id="destination-url"
            type="url"
            placeholder="https://yoursite.com/landing-page"
            value={destinationUrl}
            onChange={(e) => setDestinationUrl(e.target.value)}
          />
        </div>

        {selectedCreator && (
          <div className="rounded-lg border border-border p-3 bg-muted/50">
            <p className="text-xs text-muted-foreground mb-2">UTM Parameters (auto-generated)</p>
            <div className="grid grid-cols-2 gap-2 text-xs">
              <div><span className="text-muted-foreground">source:</span> influora</div>
              <div><span className="text-muted-foreground">medium:</span> influencer</div>
              <div><span className="text-muted-foreground">campaign:</span> {campaign.slug || campaign.id}</div>
              <div><span className="text-muted-foreground">content:</span> {selectedCreator.displayName.toLowerCase().replace(/\s+/g, '-')}</div>
            </div>
          </div>
        )}

        {generatedUrl && (
          <div className="space-y-2">
            <Label>Generated Tracking Link</Label>
            <div className="flex gap-2">
              <Input
                value={generatedUrl}
                readOnly
                className="font-mono text-xs"
                aria-label="Generated tracking URL"
              />
              <Button
                size="icon"
                variant="outline"
                onClick={handleCopy}
                aria-label={copied ? 'Copied' : 'Copy to clipboard'}
              >
                {copied ? <Check className="h-4 w-4" /> : <Copy className="h-4 w-4" />}
              </Button>
            </div>
          </div>
        )}
      </CardContent>
      <CardFooter>
        <Button 
          onClick={handleGenerate} 
          disabled={isPending || !selectedCreatorId || !destinationUrl}
          className="w-full"
        >
          {isPending ? (
            <>
              <RefreshCw className="h-4 w-4 mr-2 animate-spin" aria-hidden="true" />
              Generating...
            </>
          ) : (
            <>
              <Link2 className="h-4 w-4 mr-2" aria-hidden="true" />
              Generate Tracking Link
            </>
          )}
        </Button>
      </CardFooter>
    </Card>
  );
}
```

#### CouponCodeGenerator.tsx

```typescript
// src/components/campaigns/tracking/CouponCodeGenerator.tsx
'use client';

import { useState, useCallback } from 'react';
import { Copy, Check, Sparkles, Tag, RefreshCw } from 'lucide-react';
import { Card, CardHeader, CardTitle, CardDescription, CardContent, CardFooter } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Button } from '@/components/ui/button';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Badge } from '@/components/ui/badge';
import { useToast } from '@/hooks/use-toast';
import { useGenerateCoupon } from '@/hooks/analytics/useGenerateCoupon';
import type { Campaign, CreatorProfile } from '@/lib/types';

interface CouponCodeGeneratorProps {
  campaign: Campaign;
  creators: CreatorProfile[];
  className?: string;
}

type DiscountType = 'percentage' | 'fixed';

export function CouponCodeGenerator({
  campaign,
  creators,
  className,
}: CouponCodeGeneratorProps) {
  const { toast } = useToast();
  const { mutate: generateCoupon, isPending } = useGenerateCoupon();
  
  const [selectedCreatorId, setSelectedCreatorId] = useState<string>('');
  const [discountType, setDiscountType] = useState<DiscountType>('percentage');
  const [discountValue, setDiscountValue] = useState<string>('15');
  const [generatedCode, setGeneratedCode] = useState<string>('');
  const [suggestions, setSuggestions] = useState<string[]>([]);
  const [copied, setCopied] = useState(false);

  const selectedCreator = creators.find(c => c.id === selectedCreatorId);

  const handleGenerate = useCallback(() => {
    if (!selectedCreatorId) {
      toast({
        title: 'Missing information',
        description: 'Please select a creator.',
        variant: 'destructive',
      });
      return;
    }

    generateCoupon(
      {
        campaignId: campaign.id,
        creatorId: selectedCreatorId,
        discountType,
        discountValue: parseFloat(discountValue),
      },
      {
        onSuccess: (data) => {
          setGeneratedCode(data.code);
          setSuggestions(data.alternatives || []);
        },
        onError: () => {
          toast({
            title: 'Generation failed',
            description: 'Could not generate coupon code. Please try again.',
            variant: 'destructive',
          });
        },
      }
    );
  }, [selectedCreatorId, discountType, discountValue, campaign.id, generateCoupon, toast]);

  const handleCopy = useCallback(async (code: string) => {
    try {
      await navigator.clipboard.writeText(code);
      setCopied(true);
      toast({ title: 'Copied to clipboard' });
      setTimeout(() => setCopied(false), 2000);
    } catch {
      toast({
        title: 'Copy failed',
        description: 'Please copy the code manually.',
        variant: 'destructive',
      });
    }
  }, [toast]);

  const selectSuggestion = useCallback((code: string) => {
    setGeneratedCode(code);
  }, []);

  return (
    <Card className={className}>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <Tag className="h-5 w-5" aria-hidden="true" />
          Coupon Code Generator
        </CardTitle>
        <CardDescription>
          AI-powered coupon codes personalized for each creator.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="space-y-2">
          <Label htmlFor="coupon-creator-select">Creator</Label>
          <Select value={selectedCreatorId} onValueChange={setSelectedCreatorId}>
            <SelectTrigger id="coupon-creator-select">
              <SelectValue placeholder="Select a creator" />
            </SelectTrigger>
            <SelectContent>
              {creators.map((creator) => (
                <SelectItem key={creator.id} value={creator.id}>
                  {creator.displayName}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        <div className="grid grid-cols-2 gap-4">
          <div className="space-y-2">
            <Label htmlFor="discount-type">Discount Type</Label>
            <Select 
              value={discountType} 
              onValueChange={(v) => setDiscountType(v as DiscountType)}
            >
              <SelectTrigger id="discount-type">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="percentage">Percentage (%)</SelectItem>
                <SelectItem value="fixed">Fixed Amount ({campaign.currency || 'USD'})</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <div className="space-y-2">
            <Label htmlFor="discount-value">
              {discountType === 'percentage' ? 'Percentage' : 'Amount'}
            </Label>
            <Input
              id="discount-value"
              type="number"
              min="1"
              max={discountType === 'percentage' ? '100' : undefined}
              value={discountValue}
              onChange={(e) => setDiscountValue(e.target.value)}
            />
          </div>
        </div>

        {generatedCode && (
          <div className="space-y-4">
            <div className="space-y-2">
              <Label>Generated Code</Label>
              <div className="flex gap-2">
                <div className="flex-1 flex items-center justify-center p-4 rounded-lg bg-primary/10 border border-primary/20">
                  <span className="text-2xl font-bold font-mono tracking-wider">
                    {generatedCode}
                  </span>
                </div>
                <Button
                  size="icon"
                  variant="outline"
                  onClick={() => handleCopy(generatedCode)}
                  aria-label={copied ? 'Copied' : 'Copy to clipboard'}
                >
                  {copied ? <Check className="h-4 w-4" /> : <Copy className="h-4 w-4" />}
                </Button>
              </div>
            </div>

            {suggestions.length > 0 && (
              <div className="space-y-2">
                <Label className="text-muted-foreground">Alternative Suggestions</Label>
                <div className="flex flex-wrap gap-2">
                  {suggestions.map((code) => (
                    <Badge
                      key={code}
                      variant="outline"
                      className="cursor-pointer hover:bg-primary/10 transition-colors py-1 px-3"
                      onClick={() => selectSuggestion(code)}
                      role="button"
                      tabIndex={0}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter' || e.key === ' ') {
                          selectSuggestion(code);
                        }
                      }}
                    >
                      {code}
                    </Badge>
                  ))}
                </div>
              </div>
            )}
          </div>
        )}
      </CardContent>
      <CardFooter>
        <Button 
          onClick={handleGenerate} 
          disabled={isPending || !selectedCreatorId}
          className="w-full"
        >
          {isPending ? (
            <>
              <RefreshCw className="h-4 w-4 mr-2 animate-spin" aria-hidden="true" />
              Generating...
            </>
          ) : (
            <>
              <Sparkles className="h-4 w-4 mr-2" aria-hidden="true" />
              Generate AI Coupon Code
            </>
          )}
        </Button>
      </CardFooter>
    </Card>
  );
}
```

#### ConversionFunnel.tsx

```typescript
// src/components/campaigns/tracking/ConversionFunnel.tsx
'use client';

import { useMemo } from 'react';
import { useReducedMotion, motion } from 'framer-motion';
import { Eye, MousePointer, ShoppingCart, CreditCard, TrendingDown } from 'lucide-react';
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/utils';

interface FunnelStage {
  id: string;
  label: string;
  value: number;
  icon: React.ComponentType<{ className?: string }>;
}

interface ConversionFunnelProps {
  impressions: number;
  clicks: number;
  addToCarts: number;
  purchases: number;
  loading?: boolean;
  className?: string;
}

export function ConversionFunnel({
  impressions,
  clicks,
  addToCarts,
  purchases,
  loading = false,
  className,
}: ConversionFunnelProps) {
  const shouldReduceMotion = useReducedMotion();

  const stages: FunnelStage[] = useMemo(() => [
    { id: 'impressions', label: 'Impressions', value: impressions, icon: Eye },
    { id: 'clicks', label: 'Clicks', value: clicks, icon: MousePointer },
    { id: 'addToCarts', label: 'Add to Cart', value: addToCarts, icon: ShoppingCart },
    { id: 'purchases', label: 'Purchases', value: purchases, icon: CreditCard },
  ], [impressions, clicks, addToCarts, purchases]);

  const maxValue = stages[0]?.value || 1;

  const formatNumber = (num: number): string => {
    if (num >= 1000000) return `${(num / 1000000).toFixed(1)}M`;
    if (num >= 1000) return `${(num / 1000).toFixed(1)}K`;
    return num.toString();
  };

  const getConversionRate = (current: number, previous: number): number => {
    if (previous === 0) return 0;
    return (current / previous) * 100;
  };

  if (loading) {
    return (
      <Card className={className}>
        <CardHeader>
          <Skeleton className="h-5 w-40" />
          <Skeleton className="h-4 w-64 mt-1" />
        </CardHeader>
        <CardContent>
          <div className="space-y-4">
            {[1, 2, 3, 4].map((i) => (
              <Skeleton key={i} className="h-16 w-full" />
            ))}
          </div>
        </CardContent>
      </Card>
    );
  }

  return (
    <Card className={className}>
      <CardHeader>
        <CardTitle>Conversion Funnel</CardTitle>
        <CardDescription>
          Track your campaign performance from impressions to purchases
        </CardDescription>
      </CardHeader>
      <CardContent>
        <div 
          className="space-y-3"
          role="img"
          aria-label={`Conversion funnel: ${stages.map(s => `${s.label}: ${formatNumber(s.value)}`).join(', ')}`}
        >
          {stages.map((stage, index) => {
            const width = (stage.value / maxValue) * 100;
            const prevStage = stages[index - 1];
            const conversionRate = prevStage 
              ? getConversionRate(stage.value, prevStage.value) 
              : 100;
            
            return (
              <div key={stage.id} className="space-y-1">
                <div className="flex items-center justify-between text-sm">
                  <span className="flex items-center gap-2">
                    <stage.icon className="h-4 w-4 text-muted-foreground" aria-hidden="true" />
                    {stage.label}
                  </span>
                  <div className="flex items-center gap-3">
                    <span className="font-semibold">{formatNumber(stage.value)}</span>
                    {index > 0 && (
                      <span className={cn(
                        'text-xs flex items-center gap-1',
                        conversionRate >= 50 ? 'text-chart-2' : 
                        conversionRate >= 25 ? 'text-chart-3' : 'text-destructive'
                      )}>
                        <TrendingDown className="h-3 w-3" aria-hidden="true" />
                        {conversionRate.toFixed(1)}%
                      </span>
                    )}
                  </div>
                </div>
                <div className="relative h-10 bg-muted rounded-lg overflow-hidden">
                  <motion.div
                    className={cn(
                      'absolute inset-y-0 left-0 rounded-lg',
                      index === 0 ? 'bg-primary' :
                      index === 1 ? 'bg-primary/80' :
                      index === 2 ? 'bg-primary/60' :
                      'bg-chart-2'
                    )}
                    initial={{ width: 0 }}
                    animate={{ width: `${width}%` }}
                    transition={{ 
                      duration: shouldReduceMotion ? 0 : 0.8,
                      delay: shouldReduceMotion ? 0 : index * 0.1,
                      ease: 'easeOut'
                    }}
                  />
                </div>
              </div>
            );
          })}
        </div>

        {/* Summary stats */}
        <div className="mt-6 pt-4 border-t border-border">
          <div className="grid grid-cols-2 gap-4 text-center">
            <div>
              <p className="text-2xl font-bold text-primary">
                {((clicks / impressions) * 100).toFixed(2)}%
              </p>
              <p className="text-xs text-muted-foreground">Click-through Rate</p>
            </div>
            <div>
              <p className="text-2xl font-bold text-chart-2">
                {((purchases / clicks) * 100).toFixed(2)}%
              </p>
              <p className="text-xs text-muted-foreground">Conversion Rate</p>
            </div>
          </div>
        </div>
      </CardContent>
    </Card>
  );
}
```

#### RedemptionTable.tsx

```typescript
// src/components/campaigns/tracking/RedemptionTable.tsx
'use client';

import { useState } from 'react';
import { ArrowUpDown, Download, Search } from 'lucide-react';
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { Skeleton } from '@/components/ui/skeleton';
import type { CouponRedemption } from '@/lib/types';

interface RedemptionTableProps {
  redemptions: CouponRedemption[];
  loading?: boolean;
  onExport?: () => void;
  className?: string;
}

type SortField = 'code' | 'creator' | 'uses' | 'revenue' | 'lastUsed';
type SortOrder = 'asc' | 'desc';

export function RedemptionTable({
  redemptions,
  loading = false,
  onExport,
  className,
}: RedemptionTableProps) {
  const [searchQuery, setSearchQuery] = useState('');
  const [sortField, setSortField] = useState<SortField>('revenue');
  const [sortOrder, setSortOrder] = useState<SortOrder>('desc');

  const handleSort = (field: SortField) => {
    if (sortField === field) {
      setSortOrder(sortOrder === 'asc' ? 'desc' : 'asc');
    } else {
      setSortField(field);
      setSortOrder('desc');
    }
  };

  const filteredAndSorted = redemptions
    .filter((r) => 
      r.code.toLowerCase().includes(searchQuery.toLowerCase()) ||
      r.creatorName.toLowerCase().includes(searchQuery.toLowerCase())
    )
    .sort((a, b) => {
      const multiplier = sortOrder === 'asc' ? 1 : -1;
      switch (sortField) {
        case 'code':
          return a.code.localeCompare(b.code) * multiplier;
        case 'creator':
          return a.creatorName.localeCompare(b.creatorName) * multiplier;
        case 'uses':
          return (a.totalUses - b.totalUses) * multiplier;
        case 'revenue':
          return (a.totalRevenue - b.totalRevenue) * multiplier;
        case 'lastUsed':
          return (new Date(a.lastUsedAt).getTime() - new Date(b.lastUsedAt).getTime()) * multiplier;
        default:
          return 0;
      }
    });

  const formatCurrency = (amount: number): string => {
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
    }).format(amount);
  };

  if (loading) {
    return (
      <Card className={className}>
        <CardHeader>
          <Skeleton className="h-5 w-40" />
        </CardHeader>
        <CardContent>
          <div className="space-y-2">
            {[1, 2, 3, 4, 5].map((i) => (
              <Skeleton key={i} className="h-12 w-full" />
            ))}
          </div>
        </CardContent>
      </Card>
    );
  }

  return (
    <Card className={className}>
      <CardHeader>
        <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
          <div>
            <CardTitle>Coupon Redemptions</CardTitle>
            <CardDescription>
              Track coupon usage and revenue by creator
            </CardDescription>
          </div>
          <div className="flex items-center gap-2">
            <div className="relative">
              <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                placeholder="Search codes or creators..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                className="pl-9 w-64"
                aria-label="Search redemptions"
              />
            </div>
            {onExport && (
              <Button variant="outline" size="sm" onClick={onExport}>
                <Download className="h-4 w-4 mr-2" aria-hidden="true" />
                Export
              </Button>
            )}
          </div>
        </div>
      </CardHeader>
      <CardContent>
        <div className="rounded-md border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>
                  <Button 
                    variant="ghost" 
                    size="sm" 
                    onClick={() => handleSort('code')}
                    className="h-8 font-medium"
                  >
                    Code
                    <ArrowUpDown className="ml-2 h-3 w-3" aria-hidden="true" />
                  </Button>
                </TableHead>
                <TableHead>
                  <Button 
                    variant="ghost" 
                    size="sm" 
                    onClick={() => handleSort('creator')}
                    className="h-8 font-medium"
                  >
                    Creator
                    <ArrowUpDown className="ml-2 h-3 w-3" aria-hidden="true" />
                  </Button>
                </TableHead>
                <TableHead className="text-right">
                  <Button 
                    variant="ghost" 
                    size="sm" 
                    onClick={() => handleSort('uses')}
                    className="h-8 font-medium"
                  >
                    Uses
                    <ArrowUpDown className="ml-2 h-3 w-3" aria-hidden="true" />
                  </Button>
                </TableHead>
                <TableHead className="text-right">
                  <Button 
                    variant="ghost" 
                    size="sm" 
                    onClick={() => handleSort('revenue')}
                    className="h-8 font-medium"
                  >
                    Revenue
                    <ArrowUpDown className="ml-2 h-3 w-3" aria-hidden="true" />
                  </Button>
                </TableHead>
                <TableHead>Status</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {filteredAndSorted.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={5} className="text-center py-8 text-muted-foreground">
                    No redemptions found
                  </TableCell>
                </TableRow>
              ) : (
                filteredAndSorted.map((redemption) => (
                  <TableRow key={redemption.id}>
                    <TableCell className="font-mono font-medium">
                      {redemption.code}
                    </TableCell>
                    <TableCell>{redemption.creatorName}</TableCell>
                    <TableCell className="text-right">{redemption.totalUses}</TableCell>
                    <TableCell className="text-right font-medium">
                      {formatCurrency(redemption.totalRevenue)}
                    </TableCell>
                    <TableCell>
                      <Badge 
                        variant={redemption.isActive ? 'default' : 'secondary'}
                      >
                        {redemption.isActive ? 'Active' : 'Expired'}
                      </Badge>
                    </TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
        </div>
      </CardContent>
    </Card>
  );
}
```

#### CampaignROICard.tsx

```typescript
// src/components/campaigns/tracking/CampaignROICard.tsx
'use client';

import { useReducedMotion, motion } from 'framer-motion';
import { TrendingUp, TrendingDown, DollarSign, Target, BarChart3 } from 'lucide-react';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { Progress } from '@/components/ui/progress';
import { cn } from '@/lib/utils';

interface CampaignROICardProps {
  spent: number;
  revenue: number;
  targetROI?: number;
  currency?: string;
  loading?: boolean;
  className?: string;
}

export function CampaignROICard({
  spent,
  revenue,
  targetROI = 2,
  currency = 'USD',
  loading = false,
  className,
}: CampaignROICardProps) {
  const shouldReduceMotion = useReducedMotion();
  
  const roi = spent > 0 ? revenue / spent : 0;
  const roiPercentage = (roi * 100).toFixed(0);
  const targetProgress = Math.min((roi / targetROI) * 100, 100);
  const isPositive = roi >= 1;
  const meetsTarget = roi >= targetROI;

  const formatCurrency = (amount: number): string => {
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency,
      notation: amount >= 10000 ? 'compact' : 'standard',
    }).format(amount);
  };

  if (loading) {
    return (
      <Card className={className}>
        <CardHeader>
          <Skeleton className="h-5 w-32" />
        </CardHeader>
        <CardContent>
          <Skeleton className="h-20 w-full" />
        </CardContent>
      </Card>
    );
  }

  return (
    <Card className={className}>
      <CardHeader className="pb-2">
        <CardTitle className="text-sm font-medium text-muted-foreground flex items-center gap-2">
          <BarChart3 className="h-4 w-4" aria-hidden="true" />
          Campaign ROI
        </CardTitle>
      </CardHeader>
      <CardContent>
        <div 
          className="space-y-4"
          role="img"
          aria-label={`Campaign ROI: ${roi.toFixed(2)}x return on investment`}
        >
          {/* Main ROI Display */}
          <div className="flex items-center gap-4">
            <motion.div 
              className={cn(
                'text-4xl font-bold',
                meetsTarget ? 'text-chart-2' : 
                isPositive ? 'text-primary' : 'text-destructive'
              )}
              initial={{ scale: 0.5, opacity: 0 }}
              animate={{ scale: 1, opacity: 1 }}
              transition={{ duration: shouldReduceMotion ? 0 : 0.3 }}
            >
              {roi.toFixed(2)}x
            </motion.div>
            <div className="flex-1">
              <div className={cn(
                'flex items-center gap-1 text-sm',
                isPositive ? 'text-chart-2' : 'text-destructive'
              )}>
                {isPositive ? (
                  <TrendingUp className="h-4 w-4" aria-hidden="true" />
                ) : (
                  <TrendingDown className="h-4 w-4" aria-hidden="true" />
                )}
                {roiPercentage}% return
              </div>
              <p className="text-xs text-muted-foreground">
                {meetsTarget ? 'Exceeds target' : `Target: ${targetROI}x`}
              </p>
            </div>
          </div>

          {/* Target Progress */}
          <div className="space-y-1">
            <div className="flex justify-between text-xs">
              <span className="text-muted-foreground flex items-center gap-1">
                <Target className="h-3 w-3" aria-hidden="true" />
                Progress to {targetROI}x target
              </span>
              <span className={meetsTarget ? 'text-chart-2' : ''}>
                {targetProgress.toFixed(0)}%
              </span>
            </div>
            <Progress 
              value={targetProgress} 
              className="h-2"
              aria-label={`${targetProgress.toFixed(0)}% progress to target ROI`}
            />
          </div>

          {/* Spend vs Revenue */}
          <div className="grid grid-cols-2 gap-4 pt-2 border-t border-border">
            <div>
              <p className="text-xs text-muted-foreground flex items-center gap-1">
                <DollarSign className="h-3 w-3" aria-hidden="true" />
                Total Spent
              </p>
              <p className="text-lg font-semibold">{formatCurrency(spent)}</p>
            </div>
            <div>
              <p className="text-xs text-muted-foreground flex items-center gap-1">
                <TrendingUp className="h-3 w-3" aria-hidden="true" />
                Revenue Generated
              </p>
              <p className={cn(
                'text-lg font-semibold',
                isPositive ? 'text-chart-2' : 'text-destructive'
              )}>
                {formatCurrency(revenue)}
              </p>
            </div>
          </div>
        </div>
      </CardContent>
    </Card>
  );
}
```

### 2.3 Creator Discovery Components

All components go in `src/components/discovery/`:

#### CreatorSearchFilters.tsx

See existing `src/components/brand/discover/creator-discovery.tsx` for the filter pattern. Extract and enhance with:
- Safety score filter slider
- Quality score range
- Brand compatibility toggle

#### CreatorCompareSelector.tsx

```typescript
// src/components/discovery/CreatorCompareSelector.tsx
'use client';

import { useState, useCallback } from 'react';
import { Check, X, Scale, Plus, ArrowRight } from 'lucide-react';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { cn } from '@/lib/utils';
import type { CreatorProfile } from '@/lib/types';

interface CreatorCompareSelectorProps {
  creators: CreatorProfile[];
  selectedIds: string[];
  maxSelections?: number;
  onSelectionChange: (ids: string[]) => void;
  onCompare: () => void;
  className?: string;
}

export function CreatorCompareSelector({
  creators,
  selectedIds,
  maxSelections = 4,
  onSelectionChange,
  onCompare,
  className,
}: CreatorCompareSelectorProps) {
  const selectedCreators = creators.filter(c => selectedIds.includes(c.id));
  const canAddMore = selectedIds.length < maxSelections;

  const handleToggle = useCallback((creatorId: string) => {
    if (selectedIds.includes(creatorId)) {
      onSelectionChange(selectedIds.filter(id => id !== creatorId));
    } else if (canAddMore) {
      onSelectionChange([...selectedIds, creatorId]);
    }
  }, [selectedIds, canAddMore, onSelectionChange]);

  const handleRemove = useCallback((creatorId: string) => {
    onSelectionChange(selectedIds.filter(id => id !== creatorId));
  }, [selectedIds, onSelectionChange]);

  const handleClearAll = useCallback(() => {
    onSelectionChange([]);
  }, [onSelectionChange]);

  if (selectedIds.length === 0) {
    return null;
  }

  return (
    <div 
      className={cn(
        'fixed bottom-4 left-1/2 -translate-x-1/2 z-50',
        'bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/80',
        'border border-border rounded-lg shadow-lg p-4',
        'w-[calc(100%-2rem)] max-w-2xl',
        className
      )}
      role="region"
      aria-label="Creator comparison selection"
    >
      <div className="flex items-center justify-between gap-4">
        <div className="flex items-center gap-2">
          <Scale className="h-5 w-5 text-primary" aria-hidden="true" />
          <span className="text-sm font-medium">
            Compare ({selectedIds.length}/{maxSelections})
          </span>
        </div>
        
        <div className="flex items-center gap-2">
          <div className="flex -space-x-2" role="list" aria-label="Selected creators">
            {selectedCreators.map((creator) => (
              <div 
                key={creator.id} 
                className="relative group"
                role="listitem"
              >
                <Avatar className="h-10 w-10 border-2 border-background">
                  <AvatarImage src={creator.avatarUrl || undefined} />
                  <AvatarFallback className="bg-primary/10 text-primary">
                    {creator.displayName.charAt(0)}
                  </AvatarFallback>
                </Avatar>
                <button
                  className="absolute -top-1 -right-1 h-5 w-5 rounded-full bg-destructive text-destructive-foreground opacity-0 group-hover:opacity-100 transition-opacity flex items-center justify-center"
                  onClick={() => handleRemove(creator.id)}
                  aria-label={`Remove ${creator.displayName} from comparison`}
                >
                  <X className="h-3 w-3" />
                </button>
              </div>
            ))}
            {canAddMore && (
              <div className="h-10 w-10 rounded-full border-2 border-dashed border-border flex items-center justify-center">
                <Plus className="h-4 w-4 text-muted-foreground" />
              </div>
            )}
          </div>
        </div>

        <div className="flex items-center gap-2">
          <Button variant="ghost" size="sm" onClick={handleClearAll}>
            Clear
          </Button>
          <Button 
            size="sm" 
            onClick={onCompare}
            disabled={selectedIds.length < 2}
          >
            Compare
            <ArrowRight className="h-4 w-4 ml-2" aria-hidden="true" />
          </Button>
        </div>
      </div>
    </div>
  );
}
```

#### PublicCreatorCard.tsx

For creators discovered via Content Library API (not on platform):

```typescript
// src/components/discovery/PublicCreatorCard.tsx
'use client';

import { ExternalLink, AlertCircle, UserPlus } from 'lucide-react';
import { Card, CardContent, CardFooter } from '@/components/ui/card';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip';
import type { PublicCreatorData } from '@/lib/types';

interface PublicCreatorCardProps {
  creator: PublicCreatorData;
  onInvite?: (creator: PublicCreatorData) => void;
  className?: string;
}

export function PublicCreatorCard({
  creator,
  onInvite,
  className,
}: PublicCreatorCardProps) {
  const formatFollowers = (count: number): string => {
    if (count >= 1000000) return `${(count / 1000000).toFixed(1)}M`;
    if (count >= 1000) return `${(count / 1000).toFixed(0)}K`;
    return count.toString();
  };

  return (
    <Card className={className}>
      <CardContent className="p-4">
        <div className="flex items-start justify-between gap-3">
          <div className="flex items-center gap-3">
            <Avatar className="h-14 w-14">
              <AvatarImage src={creator.profilePictureUrl || undefined} />
              <AvatarFallback className="bg-muted text-muted-foreground text-lg">
                {creator.username?.charAt(0).toUpperCase() || '?'}
              </AvatarFallback>
            </Avatar>
            <div>
              <div className="flex items-center gap-2">
                <h3 className="font-semibold">{creator.username}</h3>
                <TooltipProvider>
                  <Tooltip>
                    <TooltipTrigger>
                      <Badge variant="secondary" className="text-xs gap-1">
                        <AlertCircle className="h-3 w-3" />
                        Public
                      </Badge>
                    </TooltipTrigger>
                    <TooltipContent>
                      <p>This creator is not on Influora yet.</p>
                      <p className="text-xs text-muted-foreground">Limited data available.</p>
                    </TooltipContent>
                  </Tooltip>
                </TooltipProvider>
              </div>
              <p className="text-sm text-muted-foreground">
                {creator.platform}
              </p>
            </div>
          </div>
        </div>

        {creator.bio && (
          <p className="mt-3 line-clamp-2 text-sm text-muted-foreground">
            {creator.bio}
          </p>
        )}

        <div className="mt-4 grid grid-cols-2 gap-2 rounded-lg border border-border p-3">
          <div className="text-center">
            <p className="text-lg font-bold">
              {formatFollowers(creator.followerCount)}
            </p>
            <p className="text-xs text-muted-foreground">Followers</p>
          </div>
          <div className="text-center">
            <p className="text-lg font-bold text-muted-foreground">
              --
            </p>
            <p className="text-xs text-muted-foreground">Engagement</p>
          </div>
        </div>

        <p className="mt-3 text-xs text-muted-foreground text-center">
          Invite this creator to Influora for full analytics
        </p>
      </CardContent>
      <CardFooter className="gap-2 border-t border-border p-3">
        <Button 
          variant="outline" 
          size="sm" 
          className="flex-1"
          asChild
        >
          <a 
            href={creator.profileUrl} 
            target="_blank" 
            rel="noopener noreferrer"
          >
            <ExternalLink className="h-3.5 w-3.5 mr-1.5" />
            View Profile
          </a>
        </Button>
        {onInvite && (
          <Button 
            size="sm" 
            className="flex-1 gap-1.5"
            onClick={() => onInvite(creator)}
          >
            <UserPlus className="h-3.5 w-3.5" />
            Invite
          </Button>
        )}
      </CardFooter>
    </Card>
  );
}
```

---

## 3. State Management

### 3.1 TanStack Query Setup

Create `src/lib/query-client.ts`:

```typescript
import { QueryClient } from '@tanstack/react-query';

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 5 * 60 * 1000,      // 5 minutes
      gcTime: 30 * 60 * 1000,         // 30 minutes (formerly cacheTime)
      retry: 3,
      retryDelay: (attemptIndex) => Math.min(1000 * 2 ** attemptIndex, 30000),
      refetchOnWindowFocus: false,
    },
    mutations: {
      retry: 1,
    },
  },
});
```

### 3.2 Query Keys

Create `src/lib/query-keys.ts`:

```typescript
export const queryKeys = {
  // Analytics
  creatorMetrics: (creatorId: string, dateRange?: DateRange) => 
    ['creator-metrics', creatorId, dateRange] as const,
  
  audienceDemographics: (creatorId: string) => 
    ['audience-demographics', creatorId] as const,
  
  creatorScores: (creatorId: string) => 
    ['creator-scores', creatorId] as const,
  
  // Campaign tracking
  campaignTracking: (campaignId: string) => 
    ['campaign-tracking', campaignId] as const,
  
  conversionData: (campaignId: string, dateRange?: DateRange) => 
    ['conversion-data', campaignId, dateRange] as const,
  
  couponRedemptions: (campaignId: string) => 
    ['coupon-redemptions', campaignId] as const,
  
  // Discovery
  creatorSearch: (filters: CreatorSearchFilters) => 
    ['creator-search', filters] as const,
} as const;
```

---

## 4. API Integration Hooks

Create all hooks in `src/hooks/analytics/`:

### 4.1 useCreatorMetrics.ts

```typescript
// src/hooks/analytics/useCreatorMetrics.ts
import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { queryKeys } from '@/lib/query-keys';
import type { DateRange, CreatorMetrics } from '@/lib/types';

interface UseCreatorMetricsOptions {
  enabled?: boolean;
}

export function useCreatorMetrics(
  creatorId: string,
  dateRange?: DateRange,
  options?: UseCreatorMetricsOptions
) {
  return useQuery({
    queryKey: queryKeys.creatorMetrics(creatorId, dateRange),
    queryFn: async (): Promise<CreatorMetrics> => {
      const params = new URLSearchParams();
      if (dateRange?.start) params.append('startDate', dateRange.start.toISOString());
      if (dateRange?.end) params.append('endDate', dateRange.end.toISOString());
      
      const response = await api.get(`/analytics/creators/${creatorId}/metrics?${params}`);
      return response.data;
    },
    enabled: options?.enabled !== false && !!creatorId,
  });
}
```

### 4.2 useAudienceDemographics.ts

```typescript
// src/hooks/analytics/useAudienceDemographics.ts
import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { queryKeys } from '@/lib/query-keys';
import type { AudienceDemographics } from '@/lib/types';

export function useAudienceDemographics(creatorId: string) {
  return useQuery({
    queryKey: queryKeys.audienceDemographics(creatorId),
    queryFn: async (): Promise<AudienceDemographics> => {
      const response = await api.get(`/analytics/creators/${creatorId}/demographics`);
      return response.data;
    },
    enabled: !!creatorId,
  });
}
```

### 4.3 useCreatorScores.ts

```typescript
// src/hooks/analytics/useCreatorScores.ts
import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { queryKeys } from '@/lib/query-keys';

interface CreatorScores {
  authenticityScore: number;     // 0-100, fake follower detection
  qualityScore: number;          // 0-100, content quality
  brandSafetyRating: 'A+' | 'A' | 'B' | 'C' | 'D' | 'F';
  brandSafetyFlags?: string[];
  lastAuditDate: Date;
}

export function useCreatorScores(creatorId: string) {
  return useQuery({
    queryKey: queryKeys.creatorScores(creatorId),
    queryFn: async (): Promise<CreatorScores> => {
      const response = await api.get(`/analytics/creators/${creatorId}/scores`);
      return response.data;
    },
    enabled: !!creatorId,
    staleTime: 24 * 60 * 60 * 1000, // 24 hours - scores don't change frequently
  });
}
```

### 4.4 useGenerateUTMLink.ts

```typescript
// src/hooks/analytics/useGenerateUTMLink.ts
import { useMutation } from '@tanstack/react-query';
import { api } from '@/lib/api';

interface GenerateUTMLinkInput {
  campaignId: string;
  creatorId: string;
  destinationUrl: string;
}

interface GenerateUTMLinkResponse {
  trackingUrl: string;
  shortUrl?: string;
  utmParams: {
    source: string;
    medium: string;
    campaign: string;
    content: string;
    term?: string;
  };
}

export function useGenerateUTMLink() {
  return useMutation({
    mutationFn: async (input: GenerateUTMLinkInput): Promise<GenerateUTMLinkResponse> => {
      const response = await api.post('/tracking/utm/generate', input);
      return response.data;
    },
  });
}
```

### 4.5 useGenerateCoupon.ts

```typescript
// src/hooks/analytics/useGenerateCoupon.ts
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { queryKeys } from '@/lib/query-keys';

interface GenerateCouponInput {
  campaignId: string;
  creatorId: string;
  discountType: 'percentage' | 'fixed';
  discountValue: number;
}

interface GenerateCouponResponse {
  code: string;
  alternatives: string[];
  expiresAt?: Date;
}

export function useGenerateCoupon() {
  const queryClient = useQueryClient();
  
  return useMutation({
    mutationFn: async (input: GenerateCouponInput): Promise<GenerateCouponResponse> => {
      const response = await api.post('/tracking/coupons/generate', input);
      return response.data;
    },
    onSuccess: (_, variables) => {
      // Invalidate coupon redemptions to refresh the list
      queryClient.invalidateQueries({
        queryKey: queryKeys.couponRedemptions(variables.campaignId),
      });
    },
  });
}
```

### 4.6 useConversionData.ts

```typescript
// src/hooks/analytics/useConversionData.ts
import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { queryKeys } from '@/lib/query-keys';
import type { DateRange } from '@/lib/types';

interface ConversionData {
  impressions: number;
  clicks: number;
  addToCarts: number;
  purchases: number;
  revenue: number;
  creatorAttribution: {
    creatorId: string;
    creatorName: string;
    clicks: number;
    conversions: number;
    revenue: number;
  }[];
  dailyData: {
    date: string;
    impressions: number;
    clicks: number;
    conversions: number;
  }[];
}

export function useConversionData(campaignId: string, dateRange?: DateRange) {
  return useQuery({
    queryKey: queryKeys.conversionData(campaignId, dateRange),
    queryFn: async (): Promise<ConversionData> => {
      const params = new URLSearchParams();
      if (dateRange?.start) params.append('startDate', dateRange.start.toISOString());
      if (dateRange?.end) params.append('endDate', dateRange.end.toISOString());
      
      const response = await api.get(`/tracking/campaigns/${campaignId}/conversions?${params}`);
      return response.data;
    },
    enabled: !!campaignId,
    refetchInterval: 5 * 60 * 1000, // Refresh every 5 minutes
  });
}
```

---

## 5. Data Visualization Guidelines

### 5.1 Approved Libraries

Per TECH-STACK.md:
- **Recharts** - Line, bar, area, pie charts (already in `components/ui/chart.tsx`)
- **GSAP** - Number count-up animations (see `src/components/motion/CountUp.tsx`)
- **Framer Motion** - Card transitions, gauge animations

### 5.2 Animation Requirements

Every animated component MUST:

```typescript
import { useReducedMotion } from 'framer-motion';

function MyChart() {
  const shouldReduceMotion = useReducedMotion();
  
  return (
    <LineChart>
      <Line 
        animationDuration={shouldReduceMotion ? 0 : 750}
        // ...
      />
    </LineChart>
  );
}
```

### 5.3 Chart Accessibility

All charts require:

```tsx
<div
  role="img"
  aria-label="Detailed description of chart data"
>
  <ChartContainer config={config}>
    {/* Chart content */}
  </ChartContainer>
</div>
```

---

## 6. Responsive Design Breakpoints

From DESIGN_SYSTEM.md:

| Breakpoint | Size | Usage |
|------------|------|-------|
| Base | 0-640px | Mobile phones |
| sm | 640px+ | Large phones, small tablets |
| md | 768px+ | Tablets (portrait) |
| lg | 1024px+ | Tablets (landscape), small laptops |
| xl | 1280px+ | Desktops |
| 2xl | 1536px+ | Large desktops |

### 6.1 Analytics Dashboard Grid

```tsx
// Mobile: 1 column
// Tablet: 2 columns
// Desktop: 3-4 columns
<div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
  <CreatorMetricsCard />
  <CreatorMetricsCard />
  <CreatorMetricsCard />
  <CreatorMetricsCard />
</div>
```

### 6.2 Charts Responsiveness

```tsx
<Card className="lg:col-span-2">
  <ChartContainer 
    className="h-[200px] sm:h-[250px] md:h-[300px]"
    config={config}
  >
    {/* Responsive chart */}
  </ChartContainer>
</Card>
```

---

## 7. Accessibility Checklist (WCAG AA)

### 7.1 Color Contrast

Use DESIGN_SYSTEM.md colors:
- Text on background: 4.5:1 minimum
- UI components: 3:1 minimum
- Verify with Chrome DevTools or axe

### 7.2 Required ARIA Attributes

```tsx
// Charts
<div role="img" aria-label="Description of data visualization">

// Interactive elements
<button aria-label="Copy to clipboard">
<input aria-describedby="helper-text-id">

// Data updates (use live regions)
<div aria-live="polite" aria-atomic="true">
  Value updated to {newValue}
</div>

// Decorative icons
<Icon aria-hidden="true" />
```

### 7.3 Keyboard Navigation

- All interactive elements focusable with Tab
- Focus visible with `ring-2 ring-blue-500`
- Escape closes modals/dropdowns
- Enter/Space activates buttons

---

## 8. Empty States & Loading

### 8.1 Use Existing Components

- `Skeleton` from `components/ui/skeleton.tsx`
- `Empty`, `EmptyHeader`, `EmptyTitle`, `EmptyDescription` from `components/ui/empty.tsx`

### 8.2 Required Empty States

| Context | Title | Description | Action |
|---------|-------|-------------|--------|
| No analytics | "No data yet" | "Analytics will appear once this creator connects their accounts" | Connect Accounts |
| No campaigns | "No campaigns" | "Create your first campaign to start tracking performance" | Create Campaign |
| No conversions | "No conversions recorded" | "Conversions will appear once customers use your tracking links" | Generate Links |
| Search no results | "No creators found" | "Try adjusting your filters or search terms" | Clear Filters |

### 8.3 Loading Pattern

```tsx
function AnalyticsPage() {
  const { data, isLoading, error } = useCreatorMetrics(creatorId);
  
  if (error) {
    return <ErrorBoundary error={error} />;
  }
  
  return (
    <div>
      <CreatorMetricsCard 
        loading={isLoading}
        // ... props
      />
    </div>
  );
}
```

---

## 9. Error Handling

### 9.1 Error Boundary Component

Create `src/components/error-boundary.tsx`:

```tsx
'use client';

import { useEffect } from 'react';
import { AlertTriangle, RefreshCw, Wifi, WifiOff } from 'lucide-react';
import { Card, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';

interface ErrorBoundaryProps {
  error: Error;
  reset?: () => void;
}

export function ErrorBoundary({ error, reset }: ErrorBoundaryProps) {
  const isNetworkError = error.message.includes('network') || 
                         error.message.includes('fetch');
  
  useEffect(() => {
    console.error('Analytics Error:', error);
  }, [error]);

  return (
    <Card className="border-destructive/50">
      <CardContent className="flex flex-col items-center justify-center py-12 text-center">
        <div className="flex h-12 w-12 items-center justify-center rounded-full bg-destructive/10">
          {isNetworkError ? (
            <WifiOff className="h-6 w-6 text-destructive" />
          ) : (
            <AlertTriangle className="h-6 w-6 text-destructive" />
          )}
        </div>
        <h3 className="mt-4 text-lg font-semibold">
          {isNetworkError ? 'Connection Error' : 'Something went wrong'}
        </h3>
        <p className="mt-1 text-sm text-muted-foreground max-w-md">
          {isNetworkError 
            ? 'Please check your internet connection and try again.'
            : 'We encountered an error loading this data. Please try again.'}
        </p>
        {reset && (
          <Button 
            variant="outline" 
            className="mt-4"
            onClick={reset}
          >
            <RefreshCw className="h-4 w-4 mr-2" />
            Try Again
          </Button>
        )}
      </CardContent>
    </Card>
  );
}
```

### 9.2 Offline Indicator

```tsx
// src/components/offline-indicator.tsx
'use client';

import { useEffect, useState } from 'react';
import { WifiOff } from 'lucide-react';
import { cn } from '@/lib/utils';

export function OfflineIndicator() {
  const [isOffline, setIsOffline] = useState(false);

  useEffect(() => {
    const handleOnline = () => setIsOffline(false);
    const handleOffline = () => setIsOffline(true);
    
    window.addEventListener('online', handleOnline);
    window.addEventListener('offline', handleOffline);
    
    // Check initial state
    setIsOffline(!navigator.onLine);
    
    return () => {
      window.removeEventListener('online', handleOnline);
      window.removeEventListener('offline', handleOffline);
    };
  }, []);

  if (!isOffline) return null;

  return (
    <div 
      className={cn(
        'fixed bottom-4 right-4 z-50',
        'flex items-center gap-2 px-4 py-2',
        'bg-destructive text-destructive-foreground',
        'rounded-lg shadow-lg'
      )}
      role="alert"
      aria-live="polite"
    >
      <WifiOff className="h-4 w-4" />
      <span className="text-sm font-medium">You're offline</span>
    </div>
  );
}
```

---

## 10. Sprint Schedule

### Week 1-2: Foundation + Metrics Cards

**Deliverables:**
- [ ] Route structure setup (`/dashboard/analytics/*`)
- [ ] `CreatorMetricsCard` component
- [ ] `EngagementRateGauge` component
- [ ] `FakeFollowerIndicator` component
- [ ] `BrandSafetyBadge` component
- [ ] `QualityScoreDisplay` component
- [ ] API hooks: `useCreatorMetrics`, `useCreatorScores`
- [ ] Loading skeletons for all cards
- [ ] Main analytics overview page layout

### Week 3-4: Data Visualization + Charts

**Deliverables:**
- [ ] `MetricsTrendChart` component
- [ ] `AudienceDemographicsPanel` component
- [ ] Individual creator analytics page (`/analytics/[creatorId]`)
- [ ] `useAudienceDemographics` hook
- [ ] Creator comparison page (`/analytics/compare`)
- [ ] `CreatorCompareSelector` component
- [ ] Responsive chart layouts
- [ ] All animations with `useReducedMotion` bypass

### Week 5: Campaign Tracking

**Deliverables:**
- [ ] Campaign tracking page (`/campaigns/[id]/tracking`)
- [ ] `UTMGeneratorForm` component
- [ ] `CouponCodeGenerator` component
- [ ] `ConversionFunnel` component
- [ ] `RedemptionTable` component
- [ ] `CampaignROICard` component
- [ ] API hooks: `useGenerateUTMLink`, `useGenerateCoupon`, `useConversionData`

### Week 6: Creator Discovery + Polish

**Deliverables:**
- [ ] Enhanced `CreatorSearchFilters` with safety/quality scores
- [ ] `PublicCreatorCard` for Content Library API results
- [ ] `CreatorListPaginated` with infinite scroll
- [ ] Error boundaries for all pages
- [ ] Empty states for all views
- [ ] Offline indicator
- [ ] Full accessibility audit (axe, keyboard testing)
- [ ] Mobile testing on real devices
- [ ] Performance optimization (React.memo, useMemo)

---

## 11. Design System Integration

### 11.1 Color Tokens (from DESIGN_SYSTEM.md)

```css
/* Use these semantic colors */
--primary: #3b82f6;        /* Blue - CTAs, links */
--chart-2: #10b981;        /* Green - positive trends */
--chart-3: #f59e0b;        /* Amber - warnings */
--destructive: #ef4444;    /* Red - errors, negative */
--muted-foreground: #64748b; /* Secondary text */
```

### 11.2 Component Imports

Always import from existing UI components:

```typescript
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { Empty, EmptyHeader, EmptyTitle, EmptyDescription } from '@/components/ui/empty';
import { ChartContainer, ChartConfig } from '@/components/ui/chart';
import { Progress } from '@/components/ui/progress';
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs';
```

### 11.3 Motion Components (Already Exist)

```typescript
import { CountUp } from '@/components/motion/CountUp';
import { FadeUp } from '@/components/motion/FadeUp';
import { StaggerContainer } from '@/components/motion/StaggerContainer';
```

---

## 12. Types to Add

Add to `src/lib/types.ts`:

```typescript
// Analytics Types
export interface DateRange {
  start: Date;
  end: Date;
}

export interface MetricDataPoint {
  date: string;
  [key: string]: string | number;
}

export interface CreatorMetrics {
  totalReach: number;
  totalImpressions: number;
  totalEngagements: number;
  engagementRate: number;
  followerGrowth: number;
  avgViewsPerPost: number;
  trendData: MetricDataPoint[];
}

export interface AudienceDemographics {
  genderSplit: {
    male: number;
    female: number;
    other: number;
  };
  ageBrackets: {
    range: string;
    percentage: number;
  }[];
  topLocations: {
    country: string;
    percentage: number;
  }[];
}

export interface CouponRedemption {
  id: string;
  code: string;
  creatorId: string;
  creatorName: string;
  totalUses: number;
  totalRevenue: number;
  isActive: boolean;
  lastUsedAt: Date;
  createdAt: Date;
}

export interface PublicCreatorData {
  id: string;
  platform: Platform;
  username: string;
  profileUrl: string;
  profilePictureUrl?: string;
  bio?: string;
  followerCount: number;
  isOnPlatform: false;
}

export interface CreatorSearchFilters {
  query?: string;
  platforms?: Platform[];
  categories?: string[];
  followerRange?: [number, number];
  engagementRange?: [number, number];
  safetyScoreMin?: number;
  qualityScoreMin?: number;
  verifiedOnly?: boolean;
}
```

---

## Final Notes

1. **Test on real devices** - iPads are common for brand users
2. **Performance** - Use React.memo for metric cards, virtualize long lists
3. **Caching** - Analytics data is expensive; leverage TanStack Query caching
4. **Feature flags** - Consider hiding incomplete features during development
5. **Dark mode** - Everything uses dark theme tokens from DESIGN_SYSTEM.md

**Questions?** Reach out to Priya (CTO) or check existing implementations in:
- `components/brand/dashboard/dashboard-page.tsx`
- `components/brand/discover/creator-discovery.tsx`
- `components/ui/chart.tsx`

---

*Document Version: 1.0*  
*Last Updated: 2026-07-06*  
*Author: Priya (CTO)*

---

# ADDENDUM: New Requirements (2026-07-06 Update)

> Added per Swapnil's review. These components extend the original spec.

---

## 15. Creator Coupon Dashboard

Creators need a simple way to see and copy their unique coupon codes.

### CreatorCampaignCard.tsx

```tsx
interface CreatorCampaignCardProps {
  campaign: {
    id: string;
    name: string;
    brandName: string;
    discountType: 'percentage' | 'fixed';
    discountValue: number;
    couponCode: string;
    trackingLink: string;
    stats: {
      clicks: number;
      sales: number;
      revenue: number;
      commission?: number;  // For affiliate campaigns
    };
    paymentModel: 'flat_fee' | 'gifted' | 'affiliate' | 'hybrid';
    commissionPercent?: number;
  };
}

export function CreatorCampaignCard({ campaign }: CreatorCampaignCardProps) {
  const [copiedCoupon, setCopiedCoupon] = useState(false);
  const [copiedLink, setCopiedLink] = useState(false);

  const copyToClipboard = async (text: string, type: 'coupon' | 'link') => {
    await navigator.clipboard.writeText(text);
    if (type === 'coupon') {
      setCopiedCoupon(true);
      setTimeout(() => setCopiedCoupon(false), 2000);
    } else {
      setCopiedLink(true);
      setTimeout(() => setCopiedLink(false), 2000);
    }
  };

  return (
    <Card className="p-6">
      <div className="flex justify-between items-start mb-4">
        <div>
          <h3 className="font-semibold text-lg">{campaign.name}</h3>
          <p className="text-sm text-muted-foreground">{campaign.brandName}</p>
        </div>
        {campaign.paymentModel === 'affiliate' && (
          <Badge variant="secondary">
            {campaign.commissionPercent}% Commission
          </Badge>
        )}
      </div>

      {/* Coupon Code */}
      <div className="mb-4">
        <Label className="text-xs text-muted-foreground">YOUR COUPON CODE</Label>
        <div className="flex items-center gap-2 mt-1">
          <code className="flex-1 bg-muted px-4 py-2 rounded-md font-mono text-lg">
            {campaign.couponCode}
          </code>
          <Button
            variant="outline"
            size="icon"
            onClick={() => copyToClipboard(campaign.couponCode, 'coupon')}
          >
            {copiedCoupon ? <Check className="h-4 w-4" /> : <Copy className="h-4 w-4" />}
          </Button>
        </div>
      </div>

      {/* Tracking Link */}
      <div className="mb-4">
        <Label className="text-xs text-muted-foreground">YOUR TRACKING LINK</Label>
        <div className="flex items-center gap-2 mt-1">
          <Input
            readOnly
            value={campaign.trackingLink}
            className="font-mono text-sm"
          />
          <Button
            variant="outline"
            size="icon"
            onClick={() => copyToClipboard(campaign.trackingLink, 'link')}
          >
            {copiedLink ? <Check className="h-4 w-4" /> : <Copy className="h-4 w-4" />}
          </Button>
        </div>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-3 gap-4 pt-4 border-t">
        <div className="text-center">
          <p className="text-2xl font-bold">{campaign.stats.clicks}</p>
          <p className="text-xs text-muted-foreground">Clicks</p>
        </div>
        <div className="text-center">
          <p className="text-2xl font-bold">{campaign.stats.sales}</p>
          <p className="text-xs text-muted-foreground">Sales</p>
        </div>
        <div className="text-center">
          <p className="text-2xl font-bold">
            ₹{campaign.paymentModel === 'affiliate' 
              ? campaign.stats.commission?.toLocaleString() 
              : campaign.stats.revenue.toLocaleString()}
          </p>
          <p className="text-xs text-muted-foreground">
            {campaign.paymentModel === 'affiliate' ? 'Earned' : 'Revenue'}
          </p>
        </div>
      </div>

      {/* Share buttons */}
      <div className="flex gap-2 mt-4">
        <Button variant="outline" className="flex-1">
          <Instagram className="h-4 w-4 mr-2" />
          Share
        </Button>
        <Button variant="outline" className="flex-1">
          <Download className="h-4 w-4 mr-2" />
          Media Kit
        </Button>
      </div>
    </Card>
  );
}
```

---

## 16. Store Integration Setup UI

### StoreIntegrationSetup.tsx

```tsx
type Platform = 'shopify' | 'woocommerce' | 'custom';

export function StoreIntegrationSetup() {
  const [platform, setPlatform] = useState<Platform | null>(null);
  const [shopDomain, setShopDomain] = useState('');
  const [webhookSecret, setWebhookSecret] = useState('');
  const { data: status } = useIntegrationStatus();

  const startShopifyOAuth = () => {
    window.location.href = `/connect/shopify/install?shop=${shopDomain}`;
  };

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-xl font-semibold">Connect Your Store</h2>
        <p className="text-muted-foreground">
          Track coupon redemptions and conversions automatically
        </p>
      </div>

      {/* Connection Status */}
      {status && (
        <Alert variant={status.connected ? 'default' : 'warning'}>
          <div className="flex items-center gap-2">
            {status.connected ? (
              <CheckCircle className="h-4 w-4 text-green-600" />
            ) : (
              <AlertCircle className="h-4 w-4" />
            )}
            <span>
              {status.connected 
                ? `Connected • Last activity: ${formatDate(status.lastActivity)}`
                : 'Not connected • Required for sale campaigns'}
            </span>
          </div>
        </Alert>
      )}

      {/* Platform Selection */}
      <div className="grid grid-cols-3 gap-4">
        <Card 
          className={cn(
            "p-4 cursor-pointer hover:border-primary transition-colors",
            platform === 'shopify' && "border-primary bg-primary/5"
          )}
          onClick={() => setPlatform('shopify')}
        >
          <div className="flex flex-col items-center gap-2">
            <ShopifyIcon className="h-8 w-8" />
            <span className="font-medium">Shopify</span>
          </div>
        </Card>
        <Card 
          className={cn(
            "p-4 cursor-pointer hover:border-primary transition-colors",
            platform === 'woocommerce' && "border-primary bg-primary/5"
          )}
          onClick={() => setPlatform('woocommerce')}
        >
          <div className="flex flex-col items-center gap-2">
            <WooCommerceIcon className="h-8 w-8" />
            <span className="font-medium">WooCommerce</span>
          </div>
        </Card>
        <Card 
          className={cn(
            "p-4 cursor-pointer hover:border-primary transition-colors",
            platform === 'custom' && "border-primary bg-primary/5"
          )}
          onClick={() => setPlatform('custom')}
        >
          <div className="flex flex-col items-center gap-2">
            <Code className="h-8 w-8" />
            <span className="font-medium">Custom / Other</span>
          </div>
        </Card>
      </div>

      {/* Shopify Setup */}
      {platform === 'shopify' && (
        <Card className="p-6">
          <h3 className="font-semibold mb-4">Connect Shopify</h3>
          
          <Tabs defaultValue="automatic">
            <TabsList className="mb-4">
              <TabsTrigger value="automatic">Automatic (Recommended)</TabsTrigger>
              <TabsTrigger value="manual">Manual Webhook</TabsTrigger>
            </TabsList>
            
            <TabsContent value="automatic">
              <div className="space-y-4">
                <div>
                  <Label>Your Shopify Store URL</Label>
                  <div className="flex gap-2 mt-1">
                    <Input
                      placeholder="your-store"
                      value={shopDomain}
                      onChange={(e) => setShopDomain(e.target.value)}
                    />
                    <span className="flex items-center text-muted-foreground">
                      .myshopify.com
                    </span>
                  </div>
                </div>
                <Button onClick={startShopifyOAuth}>
                  Connect Shopify
                </Button>
              </div>
            </TabsContent>
            
            <TabsContent value="manual">
              <div className="space-y-4">
                <ol className="list-decimal list-inside space-y-2 text-sm">
                  <li>Go to Shopify Admin → Settings → Notifications → Webhooks</li>
                  <li>Click "Create webhook"</li>
                  <li>
                    Event: <code>Order payment</code>
                  </li>
                  <li>
                    URL: 
                    <code className="ml-2 bg-muted px-2 py-1 rounded">
                      https://api.influora.com/webhooks/shopify/redemption
                    </code>
                    <Button variant="ghost" size="sm" className="ml-2">
                      <Copy className="h-3 w-3" />
                    </Button>
                  </li>
                  <li>Format: JSON</li>
                  <li>Copy the webhook signing secret and paste below:</li>
                </ol>
                
                <div>
                  <Label>Webhook Signing Secret</Label>
                  <Input
                    type="password"
                    placeholder="shpss_..."
                    value={webhookSecret}
                    onChange={(e) => setWebhookSecret(e.target.value)}
                    className="mt-1"
                  />
                </div>
                
                <Button>Verify & Save</Button>
              </div>
            </TabsContent>
          </Tabs>
        </Card>
      )}

      {/* Custom Setup */}
      {platform === 'custom' && (
        <Card className="p-6">
          <h3 className="font-semibold mb-4">Custom Integration</h3>
          <p className="text-sm text-muted-foreground mb-4">
            Integrate using our REST API or JavaScript pixel.
          </p>
          
          <div className="space-y-4">
            <Button variant="outline" asChild>
              <a href="/docs/api" target="_blank">
                <FileText className="h-4 w-4 mr-2" />
                View API Documentation
              </a>
            </Button>
            
            <Button variant="outline" onClick={() => openMeeraChat()}>
              <MessageSquare className="h-4 w-4 mr-2" />
              Ask Meera AI for Help
            </Button>
          </div>
        </Card>
      )}
    </div>
  );
}
```

---

## 17. Affiliate Earnings Dashboard (Creator View)

### AffiliateEarningsView.tsx

```tsx
export function AffiliateEarningsView({ campaignId }: { campaignId: string }) {
  const { data: earnings } = useAffiliateEarnings(campaignId);
  const { data: summary } = useAffiliateSummary(campaignId);

  return (
    <div className="space-y-6">
      {/* Summary Cards */}
      <div className="grid grid-cols-4 gap-4">
        <Card className="p-4">
          <p className="text-sm text-muted-foreground">This Month</p>
          <p className="text-2xl font-bold">{summary?.thisMonthSales || 0}</p>
          <p className="text-xs text-muted-foreground">Sales</p>
        </Card>
        <Card className="p-4">
          <p className="text-sm text-muted-foreground">Revenue Generated</p>
          <p className="text-2xl font-bold">₹{summary?.thisMonthRevenue?.toLocaleString() || 0}</p>
        </Card>
        <Card className="p-4">
          <p className="text-sm text-muted-foreground">Your Earnings</p>
          <p className="text-2xl font-bold text-green-600">
            ₹{summary?.thisMonthCommission?.toLocaleString() || 0}
          </p>
        </Card>
        <Card className="p-4">
          <p className="text-sm text-muted-foreground">Next Payout</p>
          <p className="text-lg font-semibold">1st {format(addMonths(new Date(), 1), 'MMM')}</p>
          <p className="text-xs text-muted-foreground">
            ₹{summary?.pendingPayout?.toLocaleString() || 0} pending
          </p>
        </Card>
      </div>

      {/* Recent Sales */}
      <Card>
        <CardHeader>
          <CardTitle>Recent Sales</CardTitle>
        </CardHeader>
        <CardContent>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Date</TableHead>
                <TableHead>Order</TableHead>
                <TableHead className="text-right">Sale Value</TableHead>
                <TableHead className="text-right">Your Commission</TableHead>
                <TableHead>Status</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {earnings?.map((earning) => (
                <TableRow key={earning.id}>
                  <TableCell>{format(earning.createdAt, 'dd MMM, HH:mm')}</TableCell>
                  <TableCell className="font-mono text-sm">#{earning.orderId}</TableCell>
                  <TableCell className="text-right">₹{earning.orderTotal.toLocaleString()}</TableCell>
                  <TableCell className="text-right text-green-600 font-medium">
                    ₹{earning.commissionAmount.toLocaleString()}
                  </TableCell>
                  <TableCell>
                    <Badge variant={earning.status === 'paid' ? 'default' : 'secondary'}>
                      {earning.status}
                    </Badge>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
    </div>
  );
}
```

---

## 18. Campaign Type Selector (Brand View)

### CampaignTypeSelector.tsx

```tsx
type PaymentModel = 'flat_fee' | 'gifted' | 'affiliate' | 'hybrid';

export function CampaignTypeSelector({ 
  value, 
  onChange 
}: { 
  value: PaymentModel; 
  onChange: (v: PaymentModel) => void;
}) {
  return (
    <div className="space-y-4">
      <Label>Campaign Payment Model</Label>
      
      <div className="grid grid-cols-2 gap-4">
        <Card 
          className={cn(
            "p-4 cursor-pointer transition-colors",
            value === 'flat_fee' && "border-primary bg-primary/5"
          )}
          onClick={() => onChange('flat_fee')}
        >
          <div className="flex items-start gap-3">
            <Banknote className="h-5 w-5 mt-0.5 text-primary" />
            <div>
              <p className="font-medium">Flat Fee</p>
              <p className="text-sm text-muted-foreground">
                Pay creator fixed amount upfront
              </p>
            </div>
          </div>
        </Card>

        <Card 
          className={cn(
            "p-4 cursor-pointer transition-colors",
            value === 'gifted' && "border-primary bg-primary/5"
          )}
          onClick={() => onChange('gifted')}
        >
          <div className="flex items-start gap-3">
            <Gift className="h-5 w-5 mt-0.5 text-primary" />
            <div>
              <p className="font-medium">Gifted</p>
              <p className="text-sm text-muted-foreground">
                Send free product, no cash payment
              </p>
            </div>
          </div>
        </Card>

        <Card 
          className={cn(
            "p-4 cursor-pointer transition-colors",
            value === 'affiliate' && "border-primary bg-primary/5"
          )}
          onClick={() => onChange('affiliate')}
        >
          <div className="flex items-start gap-3">
            <TrendingUp className="h-5 w-5 mt-0.5 text-primary" />
            <div>
              <p className="font-medium">Affiliate / Revenue Share</p>
              <p className="text-sm text-muted-foreground">
                Creator earns % of each sale they generate
              </p>
            </div>
          </div>
        </Card>

        <Card 
          className={cn(
            "p-4 cursor-pointer transition-colors",
            value === 'hybrid' && "border-primary bg-primary/5"
          )}
          onClick={() => onChange('hybrid')}
        >
          <div className="flex items-start gap-3">
            <Layers className="h-5 w-5 mt-0.5 text-primary" />
            <div>
              <p className="font-medium">Hybrid</p>
              <p className="text-sm text-muted-foreground">
                Base fee + commission on sales
              </p>
            </div>
          </div>
        </Card>
      </div>

      {(value === 'affiliate' || value === 'hybrid') && (
        <div className="pt-4 space-y-4">
          <div>
            <Label>Commission Percentage</Label>
            <div className="flex items-center gap-2 mt-1">
              <Input type="number" min="1" max="50" placeholder="15" className="w-24" />
              <span className="text-muted-foreground">% per sale</span>
            </div>
          </div>
          <div>
            <Label>Commission Cap (Optional)</Label>
            <div className="flex items-center gap-2 mt-1">
              <span className="text-muted-foreground">₹</span>
              <Input type="number" placeholder="5000" className="w-32" />
              <span className="text-muted-foreground">max per creator</span>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
```

---

## 19. New API Hooks

```tsx
// hooks/useIntegration.ts
export function useIntegrationStatus() {
  return useQuery({
    queryKey: ['integration-status'],
    queryFn: () => api.get('/integrations/status'),
  });
}

export function useAffiliateEarnings(campaignId: string) {
  return useQuery({
    queryKey: ['affiliate-earnings', campaignId],
    queryFn: () => api.get(`/campaigns/${campaignId}/affiliate-earnings`),
  });
}

export function useAffiliateSummary(campaignId: string) {
  return useQuery({
    queryKey: ['affiliate-summary', campaignId],
    queryFn: () => api.get(`/campaigns/${campaignId}/affiliate-summary`),
  });
}

export function useCreatorCampaigns() {
  return useQuery({
    queryKey: ['creator-campaigns'],
    queryFn: () => api.get('/creator/campaigns'),  // Returns campaigns with unique coupons
  });
}
```

---

## Updated Sprint Schedule (Week 7-8)

| Task | Owner | Deliverable |
|------|-------|-------------|
| CreatorCampaignCard (coupon display) | Ananya | Component + tests |
| StoreIntegrationSetup (Shopify/WooCommerce) | Ananya | Component + OAuth flow |
| AffiliateEarningsView | Ananya | Creator earnings dashboard |
| CampaignTypeSelector | Ananya | Payment model selector |
| Integration status hooks | Ananya | API hooks |
| Affiliate earnings hooks | Ananya | API hooks |

---

**End of Addendum**
