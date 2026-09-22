import * as React from 'react';
import { X } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import type { CreatorCreditBalance } from '@/lib/api';
import { creditsCopyWithCta } from '@/lib/copy/creator-credits';
import { BuyCreditsSheet } from './BuyCreditsSheet';

const DISMISS_SESSION_KEY = 'creator-credits:zero-banner-dismissed';

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md §9.3, F9) — a dismissible banner for the "you have 0 credits"
 * state, distinct from the per-turn refusal bubble (`BuyCreditsCard`): this shows proactively
 * whenever the balance is 0, not only after a refused send. "Dismissible for the session" is
 * `sessionStorage` (cleared when the tab/window closes), wrapped in try/catch like every other
 * browser-storage read in this codebase (F8's same discipline) — a storage failure just means the
 * dismiss doesn't persist across a reload, never a thrown error.
 */
export interface ZeroCreditsBannerProps {
  language?: string;
  balance: CreatorCreditBalance | null;
  onCredited?: (total: number) => void;
  className?: string;
}

function readDismissed(): boolean {
  try {
    return window.sessionStorage.getItem(DISMISS_SESSION_KEY) === '1';
  } catch {
    return false;
  }
}

function persistDismissed(): void {
  try {
    window.sessionStorage.setItem(DISMISS_SESSION_KEY, '1');
  } catch {
    // Non-fatal — the banner just reappears if state resets.
  }
}

export function ZeroCreditsBanner({ language, balance, onCredited, className }: ZeroCreditsBannerProps) {
  const [dismissed, setDismissed] = React.useState(readDismissed);
  const [sheetOpen, setSheetOpen] = React.useState(false);

  if (!balance?.enabled || (balance.total ?? 0) > 0 || dismissed) return null;

  const { message, cta } = creditsCopyWithCta('zeroBanner', language);

  return (
    <div
      data-testid="zero-credits-banner"
      role="status"
      className={cn(
        'flex items-center justify-between gap-3 rounded-xl border border-[#f0e0a8] bg-[#FFF4D6] px-3 py-2 text-sm text-[#6B5217]',
        className,
      )}
    >
      <p className="flex-1">{message}</p>
      <div className="flex shrink-0 items-center gap-1">
        <Button
          type="button"
          size="sm"
          data-testid="zero-credits-banner-cta"
          className="h-11 min-h-11 bg-primary text-primary-foreground hover:bg-primary/90"
          onClick={() => setSheetOpen(true)}
        >
          {cta}
        </Button>
        <Button
          type="button"
          variant="ghost"
          size="icon"
          className="h-8 w-8"
          aria-label="Dismiss"
          onClick={() => {
            setDismissed(true);
            persistDismissed();
          }}
        >
          <X className="h-4 w-4" />
        </Button>
      </div>

      <BuyCreditsSheet open={sheetOpen} onOpenChange={setSheetOpen} language={language} balance={balance} onCredited={onCredited} />
    </div>
  );
}

export default ZeroCreditsBanner;
