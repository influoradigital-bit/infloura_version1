import { useState } from 'react';
import { Check } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { COMPANY } from '@/lib/company';

/**
 * Three-option pricing poll for Meera for Creators.
 *
 * Asks creators which monthly plan they would pick. There is no backend for
 * anonymous votes yet (the contact page is a mailto link, and every POST route
 * is authenticated), so the choice is kept in the browser and the creator is
 * offered a one-tap email that carries their pick. Central collection needs a
 * small public endpoint; see the recap in the session notes.
 *
 * Credit limits are a proposal for Swapnil to adjust: cost per credit is about
 * ₹0.56 on Rohan's sheet (finance/meera-credit-sheet.md), so every option here
 * clears a 75% margin even at full use.
 */

export interface PricingOption {
  id: '899' | '999' | '1499';
  price: string;
  credits: number;
  headline: string;
  fits: string;
}

const PRICING_OPTIONS: PricingOption[] = [
  { id: '899', price: '₹899', credits: 300, headline: '300 credits a month', fits: 'About 60 brief reads or 300 chats' },
  { id: '999', price: '₹999', credits: 400, headline: '400 credits a month', fits: 'Room for 2 brand searches on top' },
  { id: '1499', price: '₹1,499', credits: 750, headline: '750 credits a month', fits: 'Heavy use, 5 brand searches included' },
];

const STORAGE_KEY = 'meera-pricing-vote';

function readVote(): PricingOption['id'] | null {
  try {
    const v = window.localStorage.getItem(STORAGE_KEY);
    return v === '899' || v === '999' || v === '1499' ? v : null;
  } catch {
    return null;
  }
}

function writeVote(id: PricingOption['id']) {
  try {
    window.localStorage.setItem(STORAGE_KEY, id);
  } catch {
    // Private mode or blocked storage: the on-screen state still updates.
  }
}

export function MeeraPricingPoll() {
  // Lazy initialiser: the stored vote is read once on first render. The page is
  // prerendered as static HTML without a window, so guard for that.
  const [chosen, setChosen] = useState<PricingOption['id'] | null>(() =>
    typeof window === 'undefined' ? null : readVote(),
  );

  const choose = (id: PricingOption['id']) => {
    writeVote(id);
    setChosen(id);
  };

  const picked = PRICING_OPTIONS.find((o) => o.id === chosen);
  const mailHref = picked
    ? `mailto:${COMPANY.email}?subject=${encodeURIComponent(`Meera pricing vote: ${picked.price}`)}&body=${encodeURIComponent(
        `I would pick ${picked.price} for ${picked.credits} credits a month.\n\nWhy: `,
      )}`
    : undefined;

  return (
    <div>
      <div className="grid gap-4 sm:grid-cols-3">
        {PRICING_OPTIONS.map((o) => {
          const active = chosen === o.id;
          return (
            <div
              key={o.id}
              className={`flex h-full flex-col rounded-2xl border p-6 transition-colors ${
                active ? 'border-accent-foreground bg-accent/40' : 'border-border/60 bg-card/50'
              }`}
            >
              <div className="flex items-baseline gap-1">
                <span className="text-3xl font-bold tracking-tight">{o.price}</span>
                <span className="text-sm text-muted-foreground">/ month</span>
              </div>
              <p className="mt-2 font-semibold">{o.headline}</p>
              <p className="mt-1 text-sm text-muted-foreground">{o.fits}</p>
              <div className="mt-auto pt-5">
                <Button
                  type="button"
                  className={
                    active
                      ? 'w-full bg-accent-foreground text-white hover:bg-accent-foreground/90'
                      : 'w-full'
                  }
                  variant={active ? 'default' : 'outline'}
                  aria-pressed={active}
                  onClick={() => choose(o.id)}
                >
                  {active ? (
                    <>
                      <Check className="mr-1.5 h-4 w-4" aria-hidden="true" /> Your pick
                    </>
                  ) : (
                    'Choose this'
                  )}
                </Button>
              </div>
            </div>
          );
        })}
      </div>
      <p className="mt-4 text-xs text-muted-foreground">
        1 credit = 1 chat message. A brief read is 3, a voice message is 2, a brand search is 40.
        Money tracking, delivery proof, health flags and the Monday note stay free on every option.
      </p>
      <div aria-live="polite" className="mt-5">
        {picked ? (
          <div className="flex flex-wrap items-center gap-3 rounded-xl border border-border/60 bg-background p-4 text-sm">
            <Badge variant="outline">Thanks</Badge>
            <span>
              You picked <span className="font-semibold">{picked.price}</span>. Want to tell us why?
            </span>
            <a href={mailHref} className="font-medium text-accent-foreground hover:underline">
              Send a one-line note
            </a>
          </div>
        ) : null}
      </div>
    </div>
  );
}
