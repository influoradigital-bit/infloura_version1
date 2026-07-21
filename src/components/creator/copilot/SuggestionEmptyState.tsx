import * as React from 'react';
import { useReducedMotion } from 'framer-motion';
import { Sparkles } from 'lucide-react';

import { Card, CardContent } from '@/components/ui/card';
import { cn } from '@/lib/utils';

interface SuggestionEmptyStateProps {
  /** distinguishes "just linked, first batch hasn't run" vs "batch ran, zero
   *  themes matched" — copy differs; both are silent/non-alarming defaults
   *  pending the Ash/Tejas zero-state copy ruling (spec §6, tracked open in
   *  API-CONTRACT.md §6.1 — not a wire-shape question). */
  reason: 'pending_tagging' | 'no_suggestion_today';
  className?: string;
}

const COPY: Record<SuggestionEmptyStateProps['reason'], string> = {
  pending_tagging: 'Usually ready within a day.',
  no_suggestion_today: 'No new idea today — check back tomorrow.',
};

/**
 * Static "still working on it" / "nothing today" card — inert, no CTA, no
 * dismiss. Simplified sibling of feature/meera/ThinkingState: reuses its
 * shimmer/skeleton visual weight, not its per-step checklist machinery, since
 * there's no batch progress to expose to the creator here (fe-components-plan
 * §1.4).
 */
export function SuggestionEmptyState({ reason, className }: SuggestionEmptyStateProps) {
  const shouldReduceMotion = useReducedMotion();

  return (
    <Card className={className}>
      <CardContent className="flex items-center gap-3 py-4">
        <Sparkles
          className={cn(
            'h-5 w-5 text-muted-foreground',
            !shouldReduceMotion && 'animate-pulse',
          )}
          aria-hidden="true"
        />
        <p className="text-sm text-muted-foreground">{COPY[reason]}</p>
      </CardContent>
    </Card>
  );
}
