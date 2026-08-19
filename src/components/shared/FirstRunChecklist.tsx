import * as React from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowRight, Check, PlayCircle, X } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader } from '@/components/ui/card';
import { Progress } from '@/components/ui/progress';
import { cn } from '@/lib/utils';
import { walkthroughVideoUrl, type WalkthroughRole } from '@/lib/walkthrough-video';

/**
 * First-run guidance — the ordered "what do I do next" ladder shown to an account that has not
 * started its first collaboration yet.
 *
 * Why this exists (F-0341 / wiki/decisions/first-run-dashboard-guidance-2026-08-19.md): both
 * dashboards answered "what is pending?" and never "what do I do first?". On a brand-new account
 * every region correctly reports nothing, which reads as a broken product rather than a starting
 * point, and the shell offers 11–12 flat destinations with no sequence.
 *
 * Two rules this component exists to enforce:
 *
 *   1. **`done` is derived from real account state, never from a local flag.** A step ticks
 *      because the thing actually happened, so the ladder stays truthful if the user did the work
 *      on another device, or out of order, or before this component existed. The only thing kept
 *      in localStorage is the user's *dismissal*, which is their own input, not a claim about
 *      their account.
 *
 *   2. **`done: null` means "could not be determined", and never renders as done.** A step whose
 *      backing call failed stays actionable and shows no tick — the alternative (defaulting to
 *      `false`) is indistinguishable from a proven "not done", and defaulting to `true` would
 *      congratulate the user for work they may not have done. `null` is excluded from the
 *      "N of M" denominator for the same reason: a count that silently absorbs unknowns is a
 *      fabricated progress number.
 */
export interface FirstRunStep {
  id: string;
  title: string;
  subtitle: string;
  /** Where the primary CTA goes. */
  href: string;
  /** Label for the primary CTA when this is the active step. */
  cta: string;
  /** `true` proved done · `false` proved not done · `null` undeterminable (see above). */
  done: boolean | null;
}

interface FirstRunChecklistProps {
  /** Heading, e.g. "Get your first campaign live". */
  title: string;
  subtitle: string;
  steps: FirstRunStep[];
  /** localStorage key holding the user's dismissal. Distinct per role. */
  storageKey: string;
  /** "See the full flow" destination. Omit to hide the link. */
  flowHref?: string;
  /**
   * Role whose walkthrough video to advertise. When one is configured, the footer link changes
   * to offer the video — `flowHref` is expected to be the page the video is embedded on, so the
   * user stays inside the product instead of being sent to an external player. With no video
   * configured the link reads as text-only, because promising a video that does not play is
   * worse than not mentioning one.
   */
  walkthroughRole?: WalkthroughRole;
  className?: string;
}

/** Read once, defensively — a corrupt or unavailable localStorage must not blank the dashboard. */
function readDismissed(key: string): boolean {
  try {
    return localStorage.getItem(key) === 'true';
  } catch {
    return false;
  }
}

export function FirstRunChecklist({
  title,
  subtitle,
  steps,
  storageKey,
  flowHref,
  walkthroughRole,
  className,
}: FirstRunChecklistProps) {
  const navigate = useNavigate();
  const [dismissed, setDismissed] = React.useState(() => readDismissed(storageKey));

  const dismiss = React.useCallback(() => {
    setDismissed(true);
    try {
      localStorage.setItem(storageKey, 'true');
    } catch {
      // A browser that refuses the write still gets the dismissal for this session; the
      // checklist reappearing on the next load is the correct failure direction — losing the
      // user's guidance is worse than re-showing it.
    }
  }, [storageKey]);

  // Only *proved* outcomes count. An undeterminable step (`null`) is neither done nor pending
  // for the purpose of the number, so it can never inflate or deflate the reported progress.
  const determinable = steps.filter((s) => s.done !== null);
  const doneCount = determinable.filter((s) => s.done === true).length;
  const undeterminable = steps.length - determinable.length;

  // The first step not proved done is the one to act on. A `null` step is included here — it is
  // not proved done, so it stays actionable.
  const activeIndex = steps.findIndex((s) => s.done !== true);

  // Every step proved done and nothing unknown → the ladder is finished, retire it for good.
  const allDone = activeIndex === -1 && undeterminable === 0;

  // Only offer the video if one is actually configured for this role.
  const hasWalkthrough = walkthroughRole ? walkthroughVideoUrl(walkthroughRole) !== null : false;

  if (dismissed || allDone || steps.length === 0) return null;

  return (
    <Card className={cn('border-primary/30 bg-primary/[0.03]', className)}>
      <CardHeader className="pb-3">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <h2 className="text-base font-semibold">{title}</h2>
            <p className="mt-0.5 text-sm text-muted-foreground">{subtitle}</p>
          </div>
          <Button
            variant="ghost"
            size="sm"
            onClick={dismiss}
            className="-mr-2 -mt-1 h-8 shrink-0 gap-1 text-muted-foreground"
          >
            <X className="h-3.5 w-3.5" aria-hidden />
            <span className="hidden sm:inline">Dismiss</span>
            <span className="sr-only sm:hidden">Dismiss the getting-started checklist</span>
          </Button>
        </div>

        <div className="mt-3 flex items-center gap-3">
          <Progress
            value={determinable.length ? (doneCount / determinable.length) * 100 : 0}
            className="h-1.5 flex-1"
          />
          <span className="shrink-0 text-xs font-medium tabular-nums text-muted-foreground">
            {doneCount} of {determinable.length}
            {/* Named, not hidden — the denominator shrank because a step could not be checked. */}
            {undeterminable > 0 && ` · ${undeterminable} couldn’t be checked`}
          </span>
        </div>
      </CardHeader>

      <CardContent className="space-y-1.5">
        <ol className="space-y-1.5">
          {steps.map((step, i) => {
            const isDone = step.done === true;
            const isActive = i === activeIndex;
            return (
              <li key={step.id}>
                <button
                  type="button"
                  onClick={() => navigate(step.href)}
                  className={cn(
                    'flex w-full items-center gap-3 rounded-lg border p-3 text-left transition-colors',
                    'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2',
                    isActive
                      ? 'border-primary/40 bg-card shadow-sm'
                      : 'border-transparent hover:bg-muted/50',
                  )}
                >
                  <span
                    className={cn(
                      'flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-xs font-medium',
                      isDone
                        ? 'bg-success/15 text-success'
                        : isActive
                          ? 'bg-primary text-primary-foreground'
                          : 'bg-muted text-muted-foreground',
                    )}
                    aria-hidden
                  >
                    {isDone ? <Check className="h-3.5 w-3.5" /> : i + 1}
                  </span>

                  <span className="min-w-0 flex-1">
                    <span
                      className={cn(
                        'block truncate text-sm',
                        isDone
                          ? 'text-muted-foreground line-through'
                          : isActive
                            ? 'font-medium text-foreground'
                            : 'text-foreground',
                      )}
                    >
                      {step.title}
                    </span>
                    {/* Only the step being acted on carries its explanation — showing all five at
                        once is the wall of text the checklist replaces. */}
                    {isActive && (
                      <span className="mt-0.5 block text-xs text-muted-foreground">
                        {step.subtitle}
                      </span>
                    )}
                  </span>

                  <span className="sr-only">
                    {isDone ? 'Done. ' : step.done === null ? 'Status unknown. ' : ''}
                  </span>

                  {isActive ? (
                    <span className="inline-flex shrink-0 items-center gap-1 rounded-md bg-primary px-2.5 py-1.5 text-xs font-medium text-primary-foreground">
                      {step.cta}
                      <ArrowRight className="h-3 w-3" aria-hidden />
                    </span>
                  ) : (
                    !isDone && (
                      <ArrowRight
                        className="h-3.5 w-3.5 shrink-0 text-muted-foreground"
                        aria-hidden
                      />
                    )
                  )}
                </button>
              </li>
            );
          })}
        </ol>

        {flowHref && (
          <div className="pt-1">
            <Button
              variant="link"
              size="sm"
              className="h-auto p-0 text-xs"
              onClick={() => navigate(flowHref)}
            >
              {hasWalkthrough ? (
                <>
                  <PlayCircle className="mr-1 h-3.5 w-3.5" aria-hidden />
                  Watch the walkthrough, or read the steps
                </>
              ) : (
                'See how the whole flow works'
              )}
              <ArrowRight className="ml-1 h-3 w-3" aria-hidden />
            </Button>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

export default FirstRunChecklist;
