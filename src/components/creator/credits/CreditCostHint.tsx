import * as React from 'react';
import { Coins } from 'lucide-react';

import { cn } from '@/lib/utils';
import { creditsCopy } from '@/lib/copy/creator-credits';

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md §9.1/§9.3, F9) — a small, static cost label. NOT AI-intent cost
 * detection (SPEC.md C16/Kabir K-17/K-28): chat content is never classified to guess a price, so
 * this only ever renders one of the two fixed labels the spec allows —
 * `cost.voice` while the creator's voice-reply toggle is on, `cost.brief` on `PasteBriefCard`.
 */
export interface CreditCostHintProps {
  variant: 'voice' | 'brief';
  language?: string;
  className?: string;
}

export function CreditCostHint({ variant, language, className }: CreditCostHintProps) {
  return (
    <p
      data-testid={`credit-cost-hint-${variant}`}
      className={cn('flex items-center gap-1.5 text-xs text-muted-foreground', className)}
    >
      <Coins className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
      {creditsCopy(variant === 'voice' ? 'cost.voice' : 'cost.brief', language)}
    </p>
  );
}

export default CreditCostHint;
