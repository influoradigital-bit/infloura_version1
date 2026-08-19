import * as React from 'react';

import { Card, CardContent } from '@/components/ui/card';
import type { HowItWorksStep } from '@/content/how-it-works-steps';

/**
 * The six-step flow, rendered for a signed-in user.
 *
 * Presentation only — the copy comes from `@/content/how-it-works-steps`, the same arrays the
 * public marketing pages render and the same objects their `HowTo` JSON-LD is built from. This
 * component deliberately does NOT restate the steps: a second copy of this text is how the
 * in-app explanation and the public one start describing different products.
 *
 * The layout differs from the marketing page on purpose. That page is selling; this one is
 * answering "where am I in this, and what happens next?" for someone who is already inside the
 * product, so it is denser, carries no CTAs, and marks the reader's current position when the
 * caller can work out what that is.
 */
interface HowItWorksFlowProps {
  steps: readonly HowItWorksStep[];
  /**
   * 1-based index of the step the user is on now, if known. Renders a "You're here" marker.
   * Omit when it cannot be derived — an unmarked list is honest, a wrongly-marked one is not.
   */
  currentStep?: number;
}

export function HowItWorksFlow({ steps, currentStep }: HowItWorksFlowProps) {
  return (
    <ol className="space-y-3">
      {steps.map((step, i) => {
        const Icon = step.icon;
        const isCurrent = currentStep === i + 1;
        const isPast = currentStep !== undefined && i + 1 < currentStep;
        return (
          <li key={step.step}>
            <Card className={isCurrent ? 'border-primary/40 bg-primary/[0.03]' : undefined}>
              <CardContent className="flex gap-4 p-4">
                <div className="flex flex-col items-center gap-2">
                  <div
                    className={
                      isCurrent
                        ? 'flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-primary text-primary-foreground'
                        : isPast
                          ? 'flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-success/15 text-success'
                          : 'flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-muted text-muted-foreground'
                    }
                  >
                    <Icon className="h-5 w-5" aria-hidden />
                  </div>
                  {/* Connector — the thing that makes this read as a sequence rather than a
                      list of features. Hidden from AT: it carries no information the ordered
                      list does not already convey. */}
                  {i < steps.length - 1 && (
                    <span className="w-px flex-1 bg-border" aria-hidden />
                  )}
                </div>
                <div className="min-w-0 flex-1 pb-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-xs font-medium tabular-nums text-muted-foreground">
                      {step.step}
                    </span>
                    <h2 className="text-sm font-semibold">{step.title}</h2>
                    {isCurrent && (
                      <span className="rounded-full bg-primary/15 px-2 py-0.5 text-[10px] font-medium text-primary">
                        You&apos;re here
                      </span>
                    )}
                  </div>
                  <p className="mt-1.5 text-sm leading-relaxed text-muted-foreground">{step.body}</p>
                </div>
              </CardContent>
            </Card>
          </li>
        );
      })}
    </ol>
  );
}

export default HowItWorksFlow;
