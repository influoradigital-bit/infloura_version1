import * as React from 'react';
import type { LucideIcon } from 'lucide-react';
import { cn } from '@/lib/utils';

export type IconBadgeVariant =
  | 'primary'
  | 'muted'
  | 'success'
  | 'warning'
  | 'destructive'
  | 'info'
  | 'draft'
  | 'outreach'
  | 'negotiating'
  | 'contracted'
  | 'progress'
  | 'review'
  | 'approved'
  | 'disputed';

export type IconBadgeSize = 'xs' | 'sm' | 'md' | 'lg' | 'xl';

const variantStyles: Record<IconBadgeVariant, { box: string; icon: string }> = {
  primary: {
    box: 'bg-primary/12 ring-primary/25 shadow-[0_2px_8px_-2px] shadow-primary/20',
    icon: 'text-primary',
  },
  muted: {
    box: 'bg-muted ring-border/80',
    icon: 'text-muted-foreground',
  },
  success: {
    box: 'bg-success ring-success-foreground/15 shadow-[0_2px_8px_-2px] shadow-success-foreground/15',
    icon: 'text-success-foreground',
  },
  warning: {
    box: 'bg-warning ring-warning-foreground/15',
    icon: 'text-warning-foreground',
  },
  destructive: {
    box: 'bg-destructive ring-stage-disputed-border',
    icon: 'text-destructive-foreground',
  },
  info: {
    box: 'bg-info ring-stage-outreach-border',
    icon: 'text-info-foreground',
  },
  draft: {
    box: 'bg-stage-draft ring-stage-draft-border',
    icon: 'text-stage-draft-fg',
  },
  outreach: {
    box: 'bg-stage-outreach ring-stage-outreach-border shadow-[0_2px_8px_-2px] shadow-stage-outreach-fg/15',
    icon: 'text-stage-outreach-fg',
  },
  negotiating: {
    box: 'bg-stage-negotiating ring-stage-negotiating-border',
    icon: 'text-stage-negotiating-fg',
  },
  contracted: {
    box: 'bg-stage-contracted ring-stage-contracted-border shadow-[0_2px_8px_-2px] shadow-stage-contracted-fg/15',
    icon: 'text-stage-contracted-fg',
  },
  progress: {
    box: 'bg-stage-progress ring-stage-progress-border',
    icon: 'text-stage-progress-fg',
  },
  review: {
    box: 'bg-stage-review ring-stage-review-border',
    icon: 'text-stage-review-fg',
  },
  approved: {
    box: 'bg-stage-approved ring-stage-approved-border shadow-[0_2px_8px_-2px] shadow-stage-approved-fg/15',
    icon: 'text-stage-approved-fg',
  },
  disputed: {
    box: 'bg-stage-disputed ring-stage-disputed-border',
    icon: 'text-stage-disputed-fg',
  },
};

const sizeStyles: Record<
  IconBadgeSize,
  { box: string; icon: string; stroke: number }
> = {
  xs: { box: 'h-6 w-6', icon: 'h-3 w-3', stroke: 2.5 },
  sm: { box: 'h-8 w-8', icon: 'h-4 w-4', stroke: 2.35 },
  md: { box: 'h-10 w-10', icon: 'h-5 w-5', stroke: 2.25 },
  lg: { box: 'h-12 w-12', icon: 'h-6 w-6', stroke: 2.15 },
  xl: { box: 'h-14 w-14', icon: 'h-7 w-7', stroke: 2.1 },
};

export interface IconBadgeProps {
  icon: LucideIcon;
  variant?: IconBadgeVariant;
  size?: IconBadgeSize;
  active?: boolean;
  rounded?: 'md' | 'lg' | 'xl' | 'full';
  className?: string;
  iconClassName?: string;
}

export function IconBadge({
  icon: Icon,
  variant = 'primary',
  size = 'md',
  active = false,
  rounded = 'lg',
  className,
  iconClassName,
}: IconBadgeProps) {
  const styles = variantStyles[variant];
  const dimensions = sizeStyles[size];

  return (
    <div
      className={cn(
        'flex items-center justify-center shrink-0 ring-1 ring-inset transition-all duration-200',
        rounded === 'full' && 'rounded-full',
        rounded === 'lg' && 'rounded-lg',
        rounded === 'xl' && 'rounded-xl',
        rounded === 'md' && 'rounded-md',
        styles.box,
        dimensions.box,
        active && 'ring-2 ring-primary/35 shadow-md scale-[1.04]',
        className,
      )}
      aria-hidden
    >
      <Icon
        className={cn(styles.icon, dimensions.icon, iconClassName)}
        strokeWidth={dimensions.stroke}
      />
    </div>
  );
}
