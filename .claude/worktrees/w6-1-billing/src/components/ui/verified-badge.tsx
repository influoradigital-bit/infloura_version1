import { BadgeCheck } from 'lucide-react';

import { cn } from '@/lib/utils';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';

interface VerifiedBadgeProps {
  /** What was verified — shown in the tooltip */
  label?: string;
  /**
   * Color tone. Default `info` (Lilac-Mist blue) is the shared-app default —
   * do NOT change it globally. Meera surfaces pass `tone="escrow"` so the
   * verified tick uses escrow-green, per spec T4 (Priya M1 / Swapnil #1).
   */
  tone?: 'info' | 'escrow';
  className?: string;
}

/** Instagram-OAuth-verified checkmark with explanatory tooltip. */
export function VerifiedBadge({
  label = 'Verified via Instagram OAuth',
  tone = 'info',
  className,
}: VerifiedBadgeProps) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <span className={cn('inline-flex shrink-0', className)} aria-label={label}>
          <BadgeCheck className={cn('h-4 w-4', tone === 'escrow' ? 'text-meera-escrow' : 'text-info-foreground')} />
        </span>
      </TooltipTrigger>
      <TooltipContent>
        <p>{label}</p>
      </TooltipContent>
    </Tooltip>
  );
}
