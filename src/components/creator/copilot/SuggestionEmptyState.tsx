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
  reason: 'pending_tagging' | 'no_suggestion_today' | 'verifying_connection';
  className?: string;
}

const COPY: Record<SuggestionEmptyStateProps['reason'], string> = {
  // T-IGTRUST-0907 — was "Usually ready within a day.", which says nothing about what is
  // happening and reads as a vague apology at the single highest-stakes moment in the creator's
  // first session: they have just granted Instagram access and this is the promised payoff.
  // Naming the work ("reading your recent posts") and giving a real horizon is what makes the
  // wait tolerable. The horizon is honest either way — the connect now kicks a caption sync for
  // that creator immediately (CreatorMetaConnectedEvent), and CreatorThemeTaggingJob's 03:00 UTC
  // run is the backstop, so "your first idea lands by tomorrow morning" is the outer bound
  // rather than a hope.
  pending_tagging: 'Reading your recent posts — your first idea lands by tomorrow morning.',
  no_suggestion_today: 'No new idea today — check back tomorrow.',
  // F-0480 — shown only while the backend connection check is in flight on a fresh mount; the
  // "within a day" copy above would be wrong for a ~200ms status round-trip.
  verifying_connection: 'Checking your Instagram connection…',
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
