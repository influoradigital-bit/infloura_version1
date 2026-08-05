/**
 * CreditMeter - Visual AI credit indicator
 * ----------------------------------------------------------------------------
 * P13: Shows remaining credits and state in the Meera workspace header.
 *
 * States:
 *   - unlimited: Subtle "unlimited while live" badge, no meter pressure
 *   - metered: Progress bar with remaining/allotment, decrement feedback
 *   - low: Warning treatment (--warning color)
 *   - exhausted: Swap to paywall CTA
 *
 * Voice cost awareness (04 section 5):
 *   - Text: 1 credit
 *   - Voice in: 3 credits
 *   - Voice reply: 4 credits
 */

import { Sparkles, Infinity as InfinityIcon, AlertTriangle } from 'lucide-react';
import { motion, useReducedMotion } from 'framer-motion';

import type { CreditState } from '@/hooks/useMeeraCredits';
import { cn } from '@/lib/utils';

interface CreditMeterProps {
  state: CreditState;
  creditsRemaining: number;
  monthlyAllotment: number;
  /** Show voice cost indicator */
  showVoiceCost?: boolean;
  /** Custom class name */
  className?: string;
}

export function CreditMeter({
  state,
  creditsRemaining,
  monthlyAllotment,
  showVoiceCost = false,
  className,
}: CreditMeterProps) {
  const reduceMotion = useReducedMotion();

  // Calculate progress percentage
  const progress = monthlyAllotment > 0 ? (creditsRemaining / monthlyAllotment) * 100 : 0;

  // Loading state
  if (state === 'loading') {
    return (
      <div className={cn('flex items-center gap-2 text-xs text-meera-text-muted', className)}>
        <div className="h-3 w-16 animate-pulse rounded-full bg-meera-border" />
      </div>
    );
  }

  // Error state
  if (state === 'error') {
    return (
      <div className={cn('flex items-center gap-1.5 text-xs text-meera-text-muted', className)}>
        <AlertTriangle className="h-3 w-3" />
        <span>Credits unavailable</span>
      </div>
    );
  }

  // Unlimited state - minimal, no pressure
  if (state === 'unlimited') {
    return (
      <div className={cn('flex items-center gap-1.5 text-xs', className)}>
        <InfinityIcon className="h-3.5 w-3.5 text-meera-escrow" />
        <span className="text-meera-text-muted">Unlimited while live</span>
      </div>
    );
  }

  // Exhausted state - CTA to fund
  if (state === 'exhausted') {
    return (
      <div className={cn('flex items-center gap-2', className)}>
        <div className="flex items-center gap-1.5 rounded-full bg-meera-danger/10 px-2 py-0.5">
          <Sparkles className="h-3 w-3 text-meera-danger" />
          <span className="text-xs font-medium text-meera-danger">No credits</span>
        </div>
        <span className="text-xs text-meera-text-muted">Fund to unlock</span>
      </div>
    );
  }

  // Low credits warning
  const isLow = state === 'low';

  return (
    <div className={cn('flex items-center gap-2', className)}>
      {/* Icon */}
      <Sparkles
        className={cn(
          'h-3.5 w-3.5',
          isLow ? 'text-meera-warning' : 'text-meera-accent'
        )}
      />

      {/* Progress bar */}
      <div className="flex items-center gap-2">
        <div className="relative h-1.5 w-16 overflow-hidden rounded-full bg-meera-border">
          {reduceMotion ? (
            <div
              className={cn(
                'absolute inset-y-0 left-0 rounded-full',
                isLow ? 'bg-meera-warning' : 'bg-meera-accent'
              )}
              style={{ width: `${Math.max(progress, 2)}%` }}
            />
          ) : (
            <motion.div
              className={cn(
                'absolute inset-y-0 left-0 rounded-full',
                isLow ? 'bg-meera-warning' : 'bg-meera-accent'
              )}
              initial={{ width: 0 }}
              animate={{ width: `${Math.max(progress, 2)}%` }}
              transition={{ duration: 0.4, ease: 'easeOut' }}
            />
          )}
        </div>

        {/* Count */}
        <span
          className={cn(
            'text-xs tabular-nums',
            isLow ? 'font-medium text-meera-warning' : 'text-meera-text-muted'
          )}
        >
          {creditsRemaining}/{monthlyAllotment}
        </span>
      </div>

      {/* Voice cost indicator (subtle, near mic toggle) */}
      {showVoiceCost && (
        <span className="text-[10px] text-meera-text-muted opacity-70">
          Voice: 3-4x
        </span>
      )}
    </div>
  );
}

export default CreditMeter;
