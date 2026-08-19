import type { ReactElement } from 'react';
import { Link } from 'react-router-dom';
import { ArrowRight, Check } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { FadeUp } from '@/components/motion';

/**
 * The single conversion block every marketing page ends on.
 *
 * WHY THIS EXISTS (the CRO problem it fixes):
 *
 * Every page used to close with its own ad-hoc pair of equally-weighted buttons
 * — "Create a brand account" beside "Create a creator account", both `size="lg"`,
 * one `default` and one `outline`. Two audiences, two destinations, near-equal
 * visual weight. That is the classic split-intent close: the visitor who has just
 * read a brands page and is ready to sign up is handed a second, unrelated choice
 * at the exact moment of highest intent, and choosing between two options is
 * measurably slower and more abandonable than acting on one.
 *
 * The fix this component enforces structurally:
 *   1. ONE primary action per page, sized and coloured to dominate. `primary` is
 *      required; there is no way to render two co-equal buttons.
 *   2. The secondary action is a text link, not a button — still reachable for
 *      the minority in the wrong audience, but it no longer competes for the
 *      click.
 *   3. `reassurances` sit directly under the button. Friction at the CTA is
 *      almost always an unanswered objection ("will this cost me?", "do I have
 *      to talk to sales?"), and answering it inline is worth more than any
 *      amount of copy further up the page.
 *
 * Pages pick their primary by audience: brand-intent pages point at
 * /brand/register, creator-intent pages at /creator/register. A page that
 * genuinely serves both (the homepage, /pricing) still picks the one it is
 * primarily selling and demotes the other.
 */
export interface FunnelCtaAction {
  label: string;
  /** Site-relative route. */
  to: string;
}

export interface FunnelCtaProps {
  heading: string;
  /** One line. State the outcome, not the feature. */
  sub?: string;
  /** The one action this page is driving toward. */
  primary: FunnelCtaAction;
  /** Demoted to a text link. Use for the other audience or a lower-intent step. */
  secondary?: FunnelCtaAction;
  /**
   * Objection-handling microcopy rendered as a checked list under the button.
   * Keep each to a few words — these are scanned, not read.
   */
  reassurances?: string[];
  /** Use the Hype accent treatment instead of the default primary. */
  tone?: 'default' | 'hype';
  className?: string;
}

export function FunnelCta({
  heading,
  sub,
  primary,
  secondary,
  reassurances = [],
  tone = 'default',
  className = 'border-t border-border/60 py-20',
}: FunnelCtaProps): ReactElement {
  return (
    <section className={className} aria-label="Get started">
      <FadeUp className="mx-auto max-w-2xl px-6 text-center">
        <h2 className="text-3xl font-semibold">{heading}</h2>
        {sub && <p className="mt-3 text-muted-foreground">{sub}</p>}

        <div className="mt-8">
          <Button
            size="lg"
            className={
              tone === 'hype'
                ? 'bg-hype-solid text-white hover:bg-hype-solid/90'
                : undefined
            }
            asChild
          >
            <Link to={primary.to}>
              {primary.label}
              <ArrowRight className="ml-1.5 h-4 w-4" aria-hidden="true" />
            </Link>
          </Button>
        </div>

        {reassurances.length > 0 && (
          <ul className="mt-5 flex flex-wrap items-center justify-center gap-x-5 gap-y-2">
            {reassurances.map((item) => (
              <li
                key={item}
                className="flex items-center gap-1.5 text-sm text-muted-foreground"
              >
                <Check className="h-3.5 w-3.5 text-primary" aria-hidden="true" />
                {item}
              </li>
            ))}
          </ul>
        )}

        {secondary && (
          <p className="mt-6 text-sm text-muted-foreground">
            <Link to={secondary.to} className="font-medium text-primary hover:underline">
              {secondary.label}
            </Link>
          </p>
        )}
      </FadeUp>
    </section>
  );
}
