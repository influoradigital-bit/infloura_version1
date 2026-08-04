'use client';

/**
 * MetricSourceBadge — the honesty signal shared by creator + brand analytics
 * (verified-analytics-0804). Renders the provenance of a metric row so a self-report is never
 * shown as verified (DPF-6). Driven by the real `DeliverableMetric.source`, not hard-coded copy.
 */

import * as React from 'react';
import { BadgeCheck, Clock, UserPen } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { cn } from '@/lib/utils';

export interface MetricSourceBadgeProps {
  /** `PLATFORM_VERIFIED` | `CREATOR_REPORTED` | null/undefined (not yet verified). */
  source: string | null | undefined;
  className?: string;
}

export function MetricSourceBadge({ source, className }: MetricSourceBadgeProps) {
  if (source === 'PLATFORM_VERIFIED') {
    return (
      <Badge
        className={cn('gap-1 bg-stage-approved text-stage-approved-fg', className)}
        aria-label="Verified by Instagram"
      >
        <BadgeCheck className="h-3.5 w-3.5" aria-hidden="true" />
        Verified by Instagram
      </Badge>
    );
  }
  if (source === 'CREATOR_REPORTED') {
    return (
      <Badge
        variant="outline"
        className={cn('gap-1 border-amber-300 text-amber-700', className)}
        aria-label="Self-reported by the creator"
      >
        <UserPen className="h-3.5 w-3.5" aria-hidden="true" />
        Self-reported
      </Badge>
    );
  }
  return (
    <Badge
      variant="outline"
      className={cn('gap-1 text-muted-foreground', className)}
      aria-label="Pending verification"
    >
      <Clock className="h-3.5 w-3.5" aria-hidden="true" />
      Pending verification
    </Badge>
  );
}

export default MetricSourceBadge;
