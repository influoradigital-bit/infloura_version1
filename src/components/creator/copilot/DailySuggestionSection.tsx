import * as React from 'react';

import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { useToast } from '@/hooks/use-toast';
import { useDailySuggestion } from '@/hooks/useDailySuggestion';

import { BusinessAccountRequired } from '@/components/creator/copilot/BusinessAccountRequired';
import { DailySuggestionCard } from '@/components/creator/copilot/DailySuggestionCard';
import { IGConnectPrompt } from '@/components/creator/copilot/IGConnectPrompt';
import { SuggestionEmptyState } from '@/components/creator/copilot/SuggestionEmptyState';

interface DailySuggestionSectionProps {
  className?: string;
}

/**
 * Single switch(status) site for the Creator Co-pilot daily suggestion
 * surface (fe-components-plan.md §3 router table / §1.6). Owns:
 *  - toast-on-error: `useDailySuggestion` only exposes `error: string | null`
 *    and never toasts itself (matches the `useEscrowFund.ts` convention) —
 *    this component fires the toast via a useEffect keyed on `error`.
 *  - the "skip for now" session-local collapse of `BusinessAccountRequired`
 *    so the rest of the dashboard never blocks on it (spec §3.3).
 *
 * Mounted directly above `<HypeInboxCard>` on the creator dashboard
 * (src/pages/creator-deals.tsx) — not in creator-layout.tsx, which only
 * renders `{children}` and has no knowledge of dashboard content.
 */
export function DailySuggestionSection({ className }: DailySuggestionSectionProps) {
  const { toast } = useToast();
  const { suggestion, status, requiresBusinessAccount, error, dismiss, markActed, retry } =
    useDailySuggestion();
  const [businessPromptSkipped, setBusinessPromptSkipped] = React.useState(false);

  React.useEffect(() => {
    if (status === 'error' && error) {
      toast({
        variant: 'destructive',
        title: "Couldn't load today's idea",
        description: error,
      });
    }
  }, [status, error, toast]);

  if (status === 'idle') {
    if (requiresBusinessAccount) {
      if (businessPromptSkipped) return null;
      return (
        <BusinessAccountRequired
          onSkip={() => setBusinessPromptSkipped(true)}
          className={className}
        />
      );
    }
    return <IGConnectPrompt className={className} />;
  }

  if (status === 'loading') {
    return <SuggestionEmptyState reason="pending_tagging" className={className} />;
  }

  if (status === 'error') {
    return (
      <Card className={className}>
        <CardContent className="flex items-center justify-between gap-3 py-4">
          <p className="text-sm text-muted-foreground">Couldn&rsquo;t load today&rsquo;s idea.</p>
          <Button size="sm" variant="ghost" onClick={retry} className="h-8 text-xs">
            Retry
          </Button>
        </CardContent>
      </Card>
    );
  }

  // status is 'ready' | 'dismissed' here — DailySuggestionCard renders both bodies.
  return (
    <DailySuggestionCard
      suggestion={suggestion}
      status={status}
      onDismiss={dismiss}
      onMarkActed={markActed}
      onRetry={retry}
      className={className}
    />
  );
}
