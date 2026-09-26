import * as React from 'react';
import { Zap } from 'lucide-react';

import { Button } from '@/components/ui/button';
import type { CreatorCreditBalance } from '@/lib/api';
import { creditsCopy } from '@/lib/copy/creator-credits';
import { BuyCreditsSheet } from './BuyCreditsSheet';

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md §9.3, F6/F7) — the inline card rendered under a refusal bubble
 * (`MeeraCopilotChat`'s 402/429 handling) or under `PasteBriefCard`'s own refusal (§9.3 "the same
 * bubble and card"). Self-contained: it owns its own `BuyCreditsSheet` rather than making every
 * caller wire one up, so dropping `<BuyCreditsCard balance={...} onCredited={...} />` anywhere is
 * the whole integration.
 *
 * The refusal SENTENCE itself (the server's `message`, already keyed en/hi by
 * `creator_language`) is rendered by the caller as the chat/error bubble — SPEC.md R6 requires
 * the chat to show that server-templated message verbatim, which only the call site (with the
 * `ApiError` in hand) can do. This card is only ever the CTA half.
 */
export interface BuyCreditsCardProps {
  language?: string;
  balance: CreatorCreditBalance | null;
  onCredited?: (total: number) => void;
  className?: string;
}

export function BuyCreditsCard({ language, balance, onCredited, className }: BuyCreditsCardProps) {
  const [sheetOpen, setSheetOpen] = React.useState(false);

  return (
    <div
      data-testid="buy-credits-card"
      className={`flex items-center justify-between gap-3 rounded-xl border border-border bg-[var(--meera-accent-soft)] px-3 py-2.5 ${className ?? ''}`}
    >
      <p className="flex items-center gap-2 text-sm text-[var(--meera-text)]">
        <Zap className="h-4 w-4 shrink-0 text-primary" aria-hidden="true" />
        {creditsCopy('sheet.pack', language)}
      </p>
      <Button
        type="button"
        size="sm"
        data-testid="buy-credits-card-cta"
        className="h-11 min-h-11 shrink-0 bg-primary text-primary-foreground hover:bg-primary/90"
        onClick={() => setSheetOpen(true)}
      >
        {creditsCopy('buy.cta', language)}
      </Button>

      <BuyCreditsSheet open={sheetOpen} onOpenChange={setSheetOpen} language={language} balance={balance} onCredited={onCredited} />
    </div>
  );
}

export default BuyCreditsCard;
