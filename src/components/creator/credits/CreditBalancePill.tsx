import * as React from 'react';

import { cn } from '@/lib/utils';
import type { CreatorCreditBalance } from '@/lib/api';
import { creditsCopy } from '@/lib/copy/creator-credits';
import { spendableCredits } from '@/lib/creator-credits-balance';

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md §9.3, F5) — the chat-header credit pill. Sits between Meera's
 * name and the voice buttons in `MeeraCopilotChat`'s header (`h-8 rounded-full`), and a tap opens
 * `BuyCreditsSheet` (the caller supplies `onClick`).
 *
 * Hidden entirely — renders `null` — whenever there is nothing to show: `balance` is `null`
 * (still loading, or the fetch failed) or `balance.enabled` is `false` (SPEC.md §9.3 "Flag off:
 * no pill…"). This is the ONE gate every other credits component in this directory also uses.
 *
 * ## Accessibility (F10 — WCAG AA on the dark `--meera-stage` header)
 * The pill sits on the dark header, but each state below has ITS OWN solid background — the
 * relevant contrast pair for AA purposes is chip-background vs chip-text, not header vs text, and
 * every pair here was computed by hand against the WCAG 2.1 relative-luminance formula (not just
 * inherited from a systemwide token that might have been tuned for a different context):
 *   - normal `#EDEAFB` / `#4C3BC2` → ≈6.55:1
 *   - low    `#FFF4D6` / `#6B5217` → ≈6.74:1 (darkened past the systemwide `--warning-foreground`,
 *             whose pairing with `--warning` is only ≈4.56:1 — real, but too close to the 4.5:1
 *             floor for a state that must stay legible on top of a dark surface)
 *   - zero   `#FFE5E5` / `#A63A3A` → ≈5.35:1
 *   - cap    `#A63A3A` / `#FFFFFF` → ≈6.38:1 (a plain `--meera-danger` red/white pair measured
 *             ≈4.41:1, just under the 4.5:1 floor for this text size, hence the darker red)
 * All four clear the 4.5:1 floor for this text's size (not "large text" by the WCAG definition)
 * with margin, on top of the chip already reading as a solid, opaque shape against the dark
 * header behind it.
 */

export type CreditBalancePillState = 'normal' | 'low' | 'zero' | 'cap';

const LOW_THRESHOLD = 5;

const STATE_STYLES: Record<CreditBalancePillState, string> = {
  normal: 'bg-[#EDEAFB] text-[#4C3BC2]',
  low: 'bg-[#FFF4D6] text-[#6B5217]',
  zero: 'bg-[#FFE5E5] text-[#A63A3A]',
  cap: 'bg-[#A63A3A] text-white',
};

/** Exported for `BuyCreditsCard`/`ZeroCreditsBanner`, which need the same derivation. */
export function deriveCreditBalancePillState(balance: CreatorCreditBalance): CreditBalancePillState {
  const dailyUsed = balance.dailyUsed ?? 0;
  const dailyCap = balance.dailyCap ?? Infinity;
  if (dailyUsed >= dailyCap) return 'cap';
  const total = spendableCredits(balance);
  if (total <= 0) return 'zero';
  if (total <= LOW_THRESHOLD) return 'low';
  return 'normal';
}

export interface CreditBalancePillProps {
  balance: CreatorCreditBalance | null;
  /** BCP-47-ish `creator_language`, same convention as `MeeraCopilotChat`'s `language` prop. */
  language?: string;
  onClick?: () => void;
  className?: string;
}

export function CreditBalancePill({ balance, language, onClick, className }: CreditBalancePillProps) {
  if (!balance || !balance.enabled) return null;

  const state = deriveCreditBalancePillState(balance);
  const total = spendableCredits(balance);
  const label =
    state === 'cap'
      ? creditsCopy('pill.cap', language)
      : state === 'zero'
        ? creditsCopy('pill.zero', language)
        : state === 'low'
          ? creditsCopy('pill.low', language, { n: total })
          : creditsCopy('pill.normal', language, { n: total });

  const stateSentence =
    state === 'cap'
      ? creditsCopy('cap', language)
      : state === 'zero'
        ? creditsCopy('exhausted', language)
        : undefined;

  return (
    <button
      type="button"
      data-testid="credit-balance-pill"
      data-state={state}
      onClick={onClick}
      // SPEC.md §9.3: "h-8 rounded-full" — matches the header's other controls (the voice-mode
      // and voice-reply-toggle buttons beside it are also `h-8 w-8`), so the pill reads as part of
      // the same control row rather than an oversized outlier. The F10 44px tap-target floor is
      // met by the primary Buy CTA (`buy.cta`, a full button in the sheet/card), not by every small
      // header icon — the same trade-off this header already makes for its existing 32px buttons.
      className={cn(
        'inline-flex h-8 items-center rounded-full px-3 text-xs font-semibold leading-none',
        'transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-white focus-visible:ring-offset-2 focus-visible:ring-offset-[--meera-stage]',
        STATE_STYLES[state],
        className,
      )}
      title={stateSentence ?? label}
      aria-label={stateSentence ?? label}
    >
      <span aria-live="polite">{label}</span>
    </button>
  );
}

export default CreditBalancePill;
