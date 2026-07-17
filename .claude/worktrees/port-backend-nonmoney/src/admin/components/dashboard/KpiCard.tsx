/**
 * INFLUORA ADMIN PANEL — KPI Stat Card
 * Owner: Ananya (Frontend)
 * Reference: src/admin/TASK_ASSIGNMENTS.md (P0)
 *
 * Reuses the `KpiCard` data shape already defined by Priya in
 * src/admin/types/admin.types.ts (title, value, change, changeType, icon)
 * — this file only adds the render-time icon lookup and props needed to
 * decouple presentation from that plain-data type.
 *
 * Delta contrast: solid, WCAG-AA-legible pill (not a washed-out pastel
 * badge) per prior brand-CTA-contrast feedback — trend up/down state must
 * stay readable at a glance on both light and dark surfaces.
 */

import { useReducedMotion, motion } from 'framer-motion';
import {
  TrendingUp,
  TrendingDown,
  Minus,
  DollarSign,
  Users,
  Megaphone,
  Wallet,
  LifeBuoy,
  ShieldAlert,
  Activity,
  type LucideIcon,
} from 'lucide-react';
import { Card } from '@/components/ui/card';
import { cn } from '@/lib/utils';
import type { KpiCard as KpiCardData } from '@/admin/types/admin.types';

// ============================================
// ICON RESOLUTION
// ============================================

/** Maps the `icon` string on KpiCardData to an actual lucide component.
 *  Add entries here as new KPIs are introduced — falls back to Activity. */
const ICON_MAP: Record<string, LucideIcon> = {
  revenue: DollarSign,
  gmv: DollarSign,
  dollar: DollarSign,
  users: Users,
  brands: Users,
  creators: Users,
  campaigns: Megaphone,
  marketing: Megaphone,
  finance: Wallet,
  escrow: Wallet,
  support: LifeBuoy,
  moderation: ShieldAlert,
  risk: ShieldAlert,
};

function resolveIcon(icon?: string): LucideIcon {
  if (!icon) return Activity;
  return ICON_MAP[icon.toLowerCase()] ?? Activity;
}

// ============================================
// PROPS
// ============================================

export interface KpiCardProps extends KpiCardData {
  /** Optional loading skeleton state (e.g. while the dashboard query resolves). */
  isLoading?: boolean;
  className?: string;
}

// ============================================
// COMPONENT
// ============================================

export default function KpiCard({
  title,
  value,
  change,
  changeType = 'neutral',
  icon,
  isLoading = false,
  className,
}: KpiCardProps) {
  const shouldReduceMotion = useReducedMotion();
  const Icon = resolveIcon(icon);

  const TrendIcon = changeType === 'positive' ? TrendingUp : changeType === 'negative' ? TrendingDown : Minus;

  // Solid, AA-legible pills — the "-foreground" tokens are the dark/saturated
  // half of each semantic pair, so used as a solid fill with white text they
  // stay readable instead of washing out like a pale badge would.
  const deltaClasses = cn(
    'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-semibold text-white',
    changeType === 'positive' && 'bg-success-foreground',
    changeType === 'negative' && 'bg-destructive-foreground',
    changeType === 'neutral' && 'bg-foreground',
  );

  if (isLoading) {
    return (
      <Card className={cn('gap-3 p-5', className)}>
        <div className="h-4 w-24 animate-pulse rounded bg-muted" />
        <div className="h-8 w-32 animate-pulse rounded bg-muted" />
        <div className="h-4 w-16 animate-pulse rounded bg-muted" />
      </Card>
    );
  }

  return (
    <motion.div
      initial={shouldReduceMotion ? {} : { opacity: 0, y: 8 }}
      animate={shouldReduceMotion ? {} : { opacity: 1, y: 0 }}
      transition={{ duration: 0.25, ease: 'easeOut' }}
    >
      <Card className={cn('gap-3 p-5', className)}>
        <div className="flex items-start justify-between">
          <span className="text-sm font-medium text-muted-foreground">{title}</span>
          <span className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-accent text-accent-foreground">
            <Icon className="size-4.5" aria-hidden="true" />
          </span>
        </div>

        <span className="text-2xl font-semibold tracking-tight text-foreground sm:text-3xl">
          {value}
        </span>

        {change !== undefined && (
          <span className={deltaClasses}>
            <TrendIcon className="size-3.5" aria-hidden="true" />
            {change > 0 ? '+' : ''}
            {change}%
          </span>
        )}
      </Card>
    </motion.div>
  );
}
