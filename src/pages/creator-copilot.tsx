import * as React from 'react';

import { CreatorLayout } from '@/components/creator/creator-layout';
import { CopilotPreviewCard } from '@/components/creator/copilot/CopilotPreviewCard';
import { DailySuggestionSection } from '@/components/creator/copilot/DailySuggestionSection';
import { useDailySuggestion } from '@/hooks/useDailySuggestion';

/**
 * Creator AI Co-pilot — route: /creator/copilot (Ananya A2).
 *
 * Previously the daily-idea experience only existed as a card embedded in
 * creator-deals.tsx with no route or nav link of its own (unlike brand's
 * full /brand/meera page). This gives it a proper home, still built on the
 * same `DailySuggestionSection` + `useDailySuggestion` data layer — no
 * forked logic, no second copy of the card.
 *
 * `status === 'idle'` covers both "never connected" and "connected but
 * wrong account type" (see useDailySuggestion.ts's `requiresBusinessAccount`
 * doc comment) — in both cases there's no real suggestion yet, so this page
 * additionally renders a non-blocking `CopilotPreviewCard` above the section
 * so the value is visible before a creator connects Instagram. The real
 * Connect Instagram CTA (`IGConnectPrompt`) still renders via
 * `DailySuggestionSection` itself — this only adds an illustrative example,
 * it doesn't replace or duplicate the connect flow.
 */
export default function CreatorCopilotPage() {
  // Separate hook instance from the one inside DailySuggestionSection — same
  // react-query key, so it's deduped, not a second network call. Reading
  // `status` here is the only way to know pre-connect vs. post-connect from
  // outside the section without forking its internals.
  const { status } = useDailySuggestion();
  const showPreview = status === 'idle';

  return (
    <CreatorLayout>
      <div className="container mx-auto px-4 py-6 max-w-3xl">
        <div className="mb-6">
          <h1 className="text-2xl font-bold">Co-pilot</h1>
          <p className="text-muted-foreground">Your AI content partner</p>
        </div>

        {showPreview && <CopilotPreviewCard className="mb-3" />}

        <DailySuggestionSection />
      </div>
    </CreatorLayout>
  );
}
