import type { ReactElement } from 'react';

import { FadeUp } from '@/components/motion';
import { BRAND_TRUST_ITEMS, type TrustBarItem } from '@/components/site/trust-items';

export { BRAND_TRUST_ITEMS, CREATOR_TRUST_ITEMS } from '@/components/site/trust-items';
export type { TrustBarItem } from '@/components/site/trust-items';

/**
 * Trust strip rendered immediately under the hero on the main funnel pages.
 *
 * WHY IT SITS THERE: the hero makes a promise and the trust strip is the first
 * thing that has to survive scrutiny of it. Placing risk-reducers above the fold
 * boundary (rather than in a "Why us" section two screens down) is the highest-
 * leverage placement available, because a visitor who bounces at the hero never
 * reaches the section that would have convinced them.
 *
 * WHY THESE CLAIMS AND NOT LOGOS/TESTIMONIALS: every item below is a *mechanism*
 * the product actually implements and that a reader can verify by using it — a
 * licensed gateway holds the money, contracts are e-signed, TDS invoices are
 * generated, payouts clear in ~24h. Customer logos and quotes convert harder,
 * but we do not have signed permission to display any, and an invented one is
 * both a legal exposure and, once noticed, a worse trust signal than nothing.
 * Swap these for real logos the moment permission exists.
 *
 * The defaults are deliberately audience-neutral; `items` overrides them on
 * pages selling to one side (see /how-it-works/creators).
 */
export interface TrustBarProps {
  items?: TrustBarItem[];
  className?: string;
}

export function TrustBar({
  items = BRAND_TRUST_ITEMS,
  className = 'border-y border-border/60 bg-card/50 py-6',
}: TrustBarProps): ReactElement {
  return (
    <section className={className} aria-label="Why Influora is safe to use">
      <FadeUp>
        <ul className="mx-auto flex max-w-6xl flex-wrap items-center justify-center gap-x-8 gap-y-3 px-6">
          {items.map((item) => {
            const Icon = item.icon;
            return (
              <li
                key={item.label}
                className="flex items-center gap-2 text-sm text-muted-foreground"
              >
                <Icon className="h-4 w-4 shrink-0 text-primary" aria-hidden="true" />
                {item.label}
              </li>
            );
          })}
        </ul>
      </FadeUp>
    </section>
  );
}
