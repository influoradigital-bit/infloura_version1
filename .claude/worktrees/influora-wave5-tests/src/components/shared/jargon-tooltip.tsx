import * as React from 'react';
import { HelpCircle } from 'lucide-react';

import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from '@/components/ui/tooltip';
import { cn } from '@/lib/utils';

/**
 * One plain-language line per jargon term (P2 audit item + SaaS help layer
 * item #2 — shared copy so both tasks are satisfied in one place). Keep
 * these short: they render in a tooltip, not a help page.
 */
export const JARGON_DEFINITIONS = {
  escrow:
    'Your payment is held safely by Influora and only released to the creator once you approve their work.',
  deliverable:
    'A specific piece of content a creator agreed to produce — e.g. one Instagram Reel or a story set.',
  'bid-counter':
    'Back-and-forth on price or scope before a deal is locked in — either side can counter an offer.',
  'deal-room':
    'The shared space for one collaboration — chat, contract, and deliverables all live here together.',
} as const;

export type JargonTerm = keyof typeof JARGON_DEFINITIONS;

interface JargonTooltipProps {
  term: JargonTerm;
  /** Visible label shown before the "?" icon, e.g. "Escrow". Optional — pass none to render just the icon. */
  label?: React.ReactNode;
  className?: string;
  iconClassName?: string;
}

/**
 * Inline "?" affordance with a one-line plain-language definition. Use next
 * to any jargon term (Escrow, Deliverable, Bid/Counter, Deal Room) the first
 * time it appears on a surface.
 */
export function JargonTooltip({ term, label, className, iconClassName }: JargonTooltipProps) {
  const definition = JARGON_DEFINITIONS[term];

  return (
    <span className={cn('inline-flex items-center gap-1', className)}>
      {label}
      <Tooltip>
        <TooltipTrigger asChild>
          <button
            type="button"
            className="inline-flex items-center justify-center rounded-full text-muted-foreground hover:text-foreground transition-colors"
            aria-label={`What does "${label ?? term}" mean?`}
          >
            <HelpCircle className={cn('h-3.5 w-3.5', iconClassName)} />
          </button>
        </TooltipTrigger>
        <TooltipContent className="max-w-64 text-xs">
          {definition}
        </TooltipContent>
      </Tooltip>
    </span>
  );
}
