import * as React from 'react';
import { motion, useReducedMotion } from 'framer-motion';
import { Loader2, Sparkles } from 'lucide-react';

import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { DURATION_NORMAL, EASE_OUT } from '@/lib/motion-config';
import type { DailySuggestion } from '@/lib/api';
import type { SuggestionStatus } from '@/hooks/useDailySuggestion';

interface DailySuggestionCardProps {
  suggestion: DailySuggestion | null;
  status: SuggestionStatus;
  onDismiss: (id: string) => void | Promise<void>;
  onMarkActed: (id: string) => void | Promise<void>;
  onRetry?: () => void;
  className?: string;
}

type ButtonSubState = 'idle' | 'submitting';

/**
 * Renders the `ready` and `dismissed` bodies of the Creator Co-pilot daily
 * suggestion card (fe-components-plan.md §1.1). `idle` / `loading` / `error`
 * are owned by sibling components (IGConnectPrompt / BusinessAccountRequired /
 * SuggestionEmptyState / the section's own inline retry) — this component
 * intentionally renders nothing for those statuses so
 * DailySuggestionSection stays the single switch(status) site (§3).
 */
export function DailySuggestionCard({
  suggestion,
  status,
  onDismiss,
  onMarkActed,
  className,
}: DailySuggestionCardProps) {
  const shouldReduceMotion = useReducedMotion();
  const [dismissState, setDismissState] = React.useState<ButtonSubState>('idle');
  const [actedState, setActedState] = React.useState<ButtonSubState>('idle');

  if ((status !== 'ready' && status !== 'dismissed') || !suggestion) return null;

  const busy = dismissState === 'submitting' || actedState === 'submitting';

  const handleDismiss = async () => {
    setDismissState('submitting');
    try {
      await onDismiss(suggestion.id);
    } catch {
      // Optimistic-disable, revert on throw — same pattern as
      // HypeInboxCard.handleAccept. The hook's own `error` field drives the
      // toast; this local state only drives the button spinner.
      setDismissState('idle');
    }
  };

  const handleMarkActed = async () => {
    setActedState('submitting');
    try {
      await onMarkActed(suggestion.id);
    } catch {
      setActedState('idle');
    }
  };

  return (
    <motion.div
      initial={shouldReduceMotion ? {} : { opacity: 0, y: 12 }}
      animate={shouldReduceMotion ? {} : { opacity: 1, y: 0 }}
      transition={{ duration: DURATION_NORMAL, ease: EASE_OUT }}
      className={className}
    >
      <Card>
        {status === 'dismissed' ? (
          <CardContent className="flex items-center py-4">
            <p className="text-sm text-muted-foreground">Next one tomorrow.</p>
          </CardContent>
        ) : (
          <CardContent className="space-y-3">
            <div className="flex items-center justify-between gap-2">
              <span className="inline-flex items-center gap-1.5 rounded-md bg-primary/10 px-2 py-1 text-xs font-medium text-primary">
                <Sparkles className="h-3.5 w-3.5" aria-hidden="true" />
                Today&rsquo;s idea
              </span>
              <Badge variant="secondary" className="text-[10px] font-normal">
                {suggestion.theme}
              </Badge>
            </div>

            <div>
              <p className="font-medium">{suggestion.headline}</p>
              <p className="mt-1 text-sm text-muted-foreground">{suggestion.contentIdea}</p>
            </div>

            <div className="flex items-center justify-end gap-2 pt-1">
              <Button size="sm" variant="outline" onClick={handleDismiss} disabled={busy}>
                {dismissState === 'submitting' ? (
                  <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden="true" />
                ) : (
                  'Dismiss'
                )}
              </Button>
              <Button size="sm" onClick={handleMarkActed} disabled={busy}>
                {actedState === 'submitting' ? (
                  <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden="true" />
                ) : (
                  'Mark done'
                )}
              </Button>
            </div>
          </CardContent>
        )}
      </Card>
    </motion.div>
  );
}
