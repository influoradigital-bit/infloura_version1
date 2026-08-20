import * as React from 'react';
import { Check, Lock } from 'lucide-react';
import { cn } from '@/lib/utils';

/**
 * The escrow lifecycle, rendered as an ordered list.
 *
 * <p>Shown wherever a money action is unavailable, so the answer to "is this thing actually
 * built?" is visible rather than asserted. The numbering is not decoration — these steps are a
 * real sequence and each one gates the next, so the order carries information the reader needs.
 *
 * <p>Deliberately not driven by live data: this renders in exactly the situations where there is
 * no live money state to read. It describes the flow, and marks which step is currently waiting.
 */

export type MoneyFlowStepId = 'fund' | 'hold' | 'deliver' | 'approve' | 'payout';

const STEPS: ReadonlyArray<{ id: MoneyFlowStepId; title: string; detail: string }> = [
  { id: 'fund', title: 'Brand funds the deal', detail: 'Payment is collected up front, before any work starts.' },
  { id: 'hold', title: 'Money is held in escrow', detail: 'Neither side can move it. The creator can see it is there.' },
  { id: 'deliver', title: 'Creator delivers', detail: 'The post goes live and is verified against the brief.' },
  { id: 'approve', title: 'Brand approves', detail: 'Escrow releases into the creator’s Influora balance.' },
  { id: 'payout', title: 'Creator withdraws', detail: 'Balance is transferred to their bank account or UPI.' },
];

interface MoneyFlowStepsProps {
  /** The step that is currently blocked. Earlier steps render as available, later ones as upcoming. */
  waitingAt: MoneyFlowStepId;
  className?: string;
}

export function MoneyFlowSteps({ waitingAt, className }: MoneyFlowStepsProps) {
  const waitingIndex = STEPS.findIndex((s) => s.id === waitingAt);

  return (
    <ol className={cn('flex flex-col gap-0', className)}>
      {STEPS.map((step, index) => {
        const state = index < waitingIndex ? 'ready' : index === waitingIndex ? 'waiting' : 'upcoming';
        const isLast = index === STEPS.length - 1;

        return (
          <li key={step.id} className="flex gap-3">
            {/* Marker column: icon plus the connector to the next step. */}
            <div className="flex flex-col items-center">
              <span
                aria-hidden="true"
                className={cn(
                  'flex h-6 w-6 shrink-0 items-center justify-center rounded-full border text-[11px] font-semibold',
                  state === 'ready' && 'border-primary/40 bg-primary/10 text-primary',
                  state === 'waiting' && 'border-amber-500/50 bg-amber-500/15 text-amber-700 dark:text-amber-300',
                  state === 'upcoming' && 'border-border bg-muted text-muted-foreground',
                )}
              >
                {state === 'ready' ? (
                  <Check className="h-3.5 w-3.5" />
                ) : state === 'waiting' ? (
                  <Lock className="h-3 w-3" />
                ) : (
                  index + 1
                )}
              </span>
              {!isLast && <span aria-hidden="true" className="w-px flex-1 bg-border" />}
            </div>

            <div className={cn('pb-4', isLast && 'pb-0')}>
              <p
                className={cn(
                  'text-sm font-medium leading-6',
                  state === 'upcoming' && 'text-muted-foreground',
                )}
              >
                {step.title}
                {state === 'waiting' && (
                  <span className="ml-2 rounded bg-amber-500/15 px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-amber-700 dark:text-amber-300">
                    Waiting
                  </span>
                )}
              </p>
              <p className="text-xs leading-5 text-muted-foreground">{step.detail}</p>
            </div>
          </li>
        );
      })}
    </ol>
  );
}
