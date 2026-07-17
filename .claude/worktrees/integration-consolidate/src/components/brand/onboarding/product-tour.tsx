import * as React from 'react';
import { motion, AnimatePresence, useReducedMotion } from 'framer-motion';
import { X, Sparkles } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { useUIStore } from '@/lib/store';

const TOUR_SEEN_KEY = 'brand_tour_seen';

interface TourStep {
  /** Matches a `data-tour="..."` attribute in brand-layout.tsx. */
  selector: string;
  title: string;
  body: string;
}

const STEPS: TourStep[] = [
  { selector: '[data-tour="nav-dashboard"]', title: 'Home', body: 'Your at-a-glance overview — active campaigns, pending actions, and recent activity.' },
  { selector: '[data-tour="nav-meera"]', title: 'Meera', body: 'Your AI cofounder. Ask her anything about running campaigns, budgets, or how Influora works.' },
  { selector: '[data-tour="nav-campaigns"]', title: 'Campaigns', body: 'Create and manage the campaigns you\'re running to find creators.' },
  { selector: '[data-tour="nav-discover"]', title: 'Creators', body: 'Search and discover creators to invite to your campaigns.' },
  { selector: '[data-tour="nav-chat"]', title: 'Deals', body: 'Every collaboration gets a Deal Room here — chat, contract, and deliverables together.' },
  { selector: '[data-tour="nav-wallet"]', title: 'Wallet', body: 'Fund escrow, track balances, and see every payment to your creators.' },
  { selector: '[data-tour="new-campaign"]', title: 'New Campaign', body: 'The fastest way to start — launch a new campaign brief in a few steps.' },
];

interface Rect {
  top: number;
  left: number;
  width: number;
  height: number;
}

function measure(selector: string): Rect | null {
  const el = document.querySelector(selector);
  if (!el) return null;
  const r = el.getBoundingClientRect();
  return { top: r.top, left: r.left, width: r.width, height: r.height };
}

/**
 * One-time, dismissible spotlight walkthrough over the 5 nav areas + New
 * Campaign. Hand-rolled with framer-motion (no driver.js / react-joyride —
 * CTO ruling in TASK-ananya-saas-help-layer.md). Honors useReducedMotion by
 * swapping the moving spotlight for a static quick-start checklist card.
 * Gated on localStorage `brand_tour_seen`; re-launchable via useUIStore's
 * tourOpen (wired to the Help menu).
 */
interface ProductTourProps {
  /** Only auto-check-and-fire the "unseen" tour when true (dashboard route).
   * The component still renders when re-launched via useUIStore.openTour()
   * from any brand page (e.g. the Help menu). */
  autoFireOnMount?: boolean;
}

export function ProductTour({ autoFireOnMount = false }: ProductTourProps) {
  const shouldReduceMotion = useReducedMotion();
  const { tourOpen, openTour, closeTour } = useUIStore();
  const [stepIndex, setStepIndex] = React.useState(0);
  const [rect, setRect] = React.useState<Rect | null>(null);

  // Fire once on first dashboard visit, unless already seen.
  React.useEffect(() => {
    if (!autoFireOnMount) return;
    const seen = localStorage.getItem(TOUR_SEEN_KEY) === 'true';
    if (!seen) {
      openTour();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [autoFireOnMount]);

  const finish = React.useCallback(() => {
    localStorage.setItem(TOUR_SEEN_KEY, 'true');
    closeTour();
    setStepIndex(0);
  }, [closeTour]);

  // Re-measure the target element whenever the step or viewport changes.
  React.useLayoutEffect(() => {
    if (!tourOpen || shouldReduceMotion) return;

    const update = () => setRect(measure(STEPS[stepIndex].selector));
    update();

    window.addEventListener('resize', update);
    return () => window.removeEventListener('resize', update);
  }, [tourOpen, stepIndex, shouldReduceMotion]);

  if (!tourOpen) return null;

  // Reduced-motion fallback: a single static quick-start card, no spotlight
  // movement, no per-step animation.
  if (shouldReduceMotion) {
    return (
      <div
        role="dialog"
        aria-label="Quick start guide"
        className="fixed inset-0 z-[100] flex items-center justify-center bg-foreground/40 p-4"
      >
        <div className="w-full max-w-sm rounded-xl border border-border bg-card p-5 shadow-xl">
          <div className="flex items-center gap-2">
            <Sparkles className="h-5 w-5 text-primary" />
            <h2 className="text-base font-semibold">Quick start</h2>
          </div>
          <ul className="mt-3 space-y-2.5">
            {STEPS.map((step) => (
              <li key={step.selector} className="text-sm">
                <span className="font-medium text-foreground">{step.title}</span>
                <span className="text-muted-foreground"> — {step.body}</span>
              </li>
            ))}
          </ul>
          <Button onClick={finish} className="mt-4 w-full" size="sm">
            Got it
          </Button>
        </div>
      </div>
    );
  }

  const step = STEPS[stepIndex];
  const isLast = stepIndex === STEPS.length - 1;

  // If the target isn't mounted (e.g. mobile sidebar collapsed), skip
  // straight to the quick-start card rather than spotlighting nothing.
  if (!rect) {
    return (
      <div
        role="dialog"
        aria-label="Quick start guide"
        className="fixed inset-0 z-[100] flex items-center justify-center bg-foreground/40 p-4"
      >
        <div className="w-full max-w-sm rounded-xl border border-border bg-card p-5 shadow-xl">
          <div className="flex items-center gap-2">
            <Sparkles className="h-5 w-5 text-primary" />
            <h2 className="text-base font-semibold">Quick start</h2>
          </div>
          <ul className="mt-3 space-y-2.5">
            {STEPS.map((s) => (
              <li key={s.selector} className="text-sm">
                <span className="font-medium text-foreground">{s.title}</span>
                <span className="text-muted-foreground"> — {s.body}</span>
              </li>
            ))}
          </ul>
          <Button onClick={finish} className="mt-4 w-full" size="sm">
            Got it
          </Button>
        </div>
      </div>
    );
  }

  const padding = 6;
  const spotlight = {
    top: rect.top - padding,
    left: rect.left - padding,
    width: rect.width + padding * 2,
    height: rect.height + padding * 2,
  };

  // Position the callout card to the right of the sidebar item, clamped to viewport.
  const cardTop = Math.min(
    Math.max(spotlight.top, 16),
    typeof window !== 'undefined' ? window.innerHeight - 220 : spotlight.top,
  );
  const cardLeft = spotlight.left + spotlight.width + 16;

  return (
    <AnimatePresence>
      <motion.div
        key="tour-overlay"
        className="fixed inset-0 z-[100]"
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        exit={{ opacity: 0 }}
        transition={{ duration: 0.15 }}
        role="dialog"
        aria-label={`Product tour — step ${stepIndex + 1} of ${STEPS.length}`}
      >
        {/* Dimmed backdrop with a cut-out spotlight, via box-shadow trick */}
        <motion.div
          className="absolute rounded-lg"
          animate={{
            top: spotlight.top,
            left: spotlight.left,
            width: spotlight.width,
            height: spotlight.height,
          }}
          transition={{ type: 'spring', stiffness: 300, damping: 30 }}
          style={{
            boxShadow: '0 0 0 9999px rgba(20, 16, 32, 0.55)',
          }}
        />

        {/* Callout card */}
        <motion.div
          key={stepIndex}
          className="absolute w-72 rounded-xl border border-border bg-card p-4 shadow-xl"
          initial={{ opacity: 0, x: -8 }}
          animate={{ opacity: 1, x: 0, top: cardTop, left: cardLeft }}
          exit={{ opacity: 0, x: -8 }}
          transition={{ duration: 0.2, ease: 'easeOut' }}
        >
          <button
            type="button"
            onClick={finish}
            aria-label="Skip tour"
            className="absolute right-2.5 top-2.5 rounded-md p-1 text-muted-foreground hover:bg-muted hover:text-foreground transition-colors"
          >
            <X className="h-4 w-4" />
          </button>
          <p className="pr-6 text-sm font-semibold text-foreground">{step.title}</p>
          <p className="mt-1 text-sm text-muted-foreground">{step.body}</p>

          <div className="mt-4 flex items-center justify-between">
            <span className="text-xs text-muted-foreground">
              {stepIndex + 1} / {STEPS.length}
            </span>
            <div className="flex items-center gap-2">
              <Button variant="ghost" size="sm" onClick={finish} className="h-7 px-2 text-xs">
                Skip
              </Button>
              <Button
                size="sm"
                className="h-7 px-3 text-xs"
                onClick={() => (isLast ? finish() : setStepIndex((i) => i + 1))}
              >
                {isLast ? 'Done' : 'Next'}
              </Button>
            </div>
          </div>
        </motion.div>
      </motion.div>
    </AnimatePresence>
  );
}
