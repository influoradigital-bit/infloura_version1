import { useEffect, useState, type ReactElement } from 'react';
import { Link } from 'react-router-dom';
import { ArrowRight } from 'lucide-react';

import { Button } from '@/components/ui/button';

/**
 * Mobile-only sticky conversion bar.
 *
 * WHY MOBILE ONLY: on desktop the header CTA stays visible the whole way down
 * the page, so a second persistent bar would be redundant chrome. On mobile the
 * header CTA is collapsed behind the hamburger — meaning that from the moment a
 * visitor scrolls past the hero, there is NO visible way to convert until they
 * reach the footer. On an India-first, mobile-majority site that is the single
 * largest structural leak in the funnel. This restores a persistent, one-tap
 * path to the primary action.
 *
 * WHY IT WAITS FOR SCROLL: showing it immediately would cover the hero's own CTA
 * with a duplicate of itself and eat vertical space on the most important
 * screen. It appears only once the hero CTA has plausibly scrolled away
 * (`showAfter` px, default 600) — i.e. exactly when the page would otherwise
 * have no call to action on screen.
 *
 * ACCESSIBILITY / LAYOUT NOTES:
 *   - `pb-[env(safe-area-inset-bottom)]` keeps the button clear of the iOS home
 *     indicator, which otherwise overlaps the tap target.
 *   - Pages that render this must add matching bottom padding to their last
 *     section, or the bar covers the footer's final row. `<StickyCtaSpacer />`
 *     is exported for that.
 *   - The scroll listener is `passive` so it cannot contribute to scroll jank
 *     (INP is a Core Web Vital and a ranking input).
 */
export interface StickyCtaProps {
  label: string;
  to: string;
  /** Scroll depth in px before the bar appears. */
  showAfter?: number;
  /** Short line shown beside the button on wider phones. */
  note?: string;
}

export function StickyCta({ label, to, showAfter = 600, note }: StickyCtaProps): ReactElement | null {
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    const onScroll = () => setVisible(window.scrollY > showAfter);
    onScroll();
    window.addEventListener('scroll', onScroll, { passive: true });
    return () => window.removeEventListener('scroll', onScroll);
  }, [showAfter]);

  if (!visible) {
    return null;
  }

  return (
    <div
      className="fixed inset-x-0 bottom-0 z-40 border-t border-border/60 bg-background/95 px-4 pb-[max(0.75rem,env(safe-area-inset-bottom))] pt-3 backdrop-blur lg:hidden"
      // Not a landmark — it duplicates an action already present in the page,
      // so it is announced as a plain region rather than competing navigation.
      role="region"
      aria-label="Get started"
    >
      <div className="flex items-center gap-3">
        {note && (
          <p className="hidden flex-1 text-xs text-muted-foreground sm:block">{note}</p>
        )}
        <Button size="lg" className="flex-1 sm:flex-none" asChild>
          <Link to={to}>
            {label}
            <ArrowRight className="ml-1.5 h-4 w-4" aria-hidden="true" />
          </Link>
        </Button>
      </div>
    </div>
  );
}

/**
 * Bottom spacer so the sticky bar never covers the last row of the footer.
 * Render once, immediately before `</div>` on any page using `<StickyCta />`.
 */
export function StickyCtaSpacer(): ReactElement {
  return <div className="h-20 lg:hidden" aria-hidden="true" />;
}
