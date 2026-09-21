import * as React from 'react';
import { Clock } from 'lucide-react';
import { Card, CardContent } from '@/components/ui/card';
import { MoneyFlowSteps, type MoneyFlowStepId } from '@/components/payments/money-flow-steps';
import { cn } from '@/lib/utils';
import type { MoneyOperation } from '@/lib/api';

/**
 * Shown in place of a money action that `isMoneyActionBlocked` refuses — the COLLECTION actions
 * (wallet top-up, funding a deal) when `VITE_PAYMENTS_IN_ENABLED` is off.
 *
 * <p>Deliberately NOT usable for `withdraw`, which is why the prop excludes it. A creator
 * withdrawal is not a feature that is switched off and coming back: Influora pays creators by
 * bank transfer and self-serve withdrawal does not exist (owner's ruling, 2026-09-21). Telling a
 * creator "withdrawals open shortly" promised a control nobody is building, and left them
 * waiting for a button instead of for a transfer. The creator wallet now states who pays them
 * and when, in its own words.
 *
 * <p>Replaces the generic 500 the server would otherwise return. That 500 is truthful but
 * useless: `RazorpayIntegrationException` has no dedicated handler, so it falls through to
 * `GlobalExceptionHandler`'s catch-all and reaches the user as an unexplained server fault —
 * indistinguishable from the product being broken.
 *
 * <p>The copy states what is actually true (the rails are not switched on yet), never apologises
 * for a fault that did not occur, and never implies the user did something wrong. The flow
 * diagram is the substance: it shows the whole lifecycle is built and marks precisely which step
 * is waiting, so the reader can tell "not enabled yet" from "does not exist".
 */

interface PaymentsUnavailableNoticeProps {
  operation: Exclude<MoneyOperation, 'withdraw'>;
  className?: string;
}

const COPY: Record<
  PaymentsUnavailableNoticeProps['operation'],
  { heading: string; body: string; waitingAt: MoneyFlowStepId }
> = {
  'escrow-fund': {
    heading: 'Funding opens shortly',
    body:
      'Your campaign and its terms are saved. Payment collection is being switched on — once it is, you can fund this deal and the creator will see the money held securely before they start work.',
    waitingAt: 'fund',
  },
  topup: {
    heading: 'Wallet top-up opens shortly',
    body:
      'Your workspace and balance are ready. Adding funds is being switched on — everything downstream of it, from securing funds to invoicing, is already in place.',
    waitingAt: 'fund',
  },
};

export function PaymentsUnavailableNotice({ operation, className }: PaymentsUnavailableNoticeProps) {
  const { heading, body, waitingAt } = COPY[operation];

  return (
    <Card className={cn('border-amber-500/30 bg-amber-500/[0.04]', className)}>
      <CardContent className="flex flex-col gap-5 p-5">
        <div className="flex gap-3">
          <span
            aria-hidden="true"
            className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-amber-500/15 text-amber-700 dark:text-amber-300"
          >
            <Clock className="h-4 w-4" />
          </span>
          <div className="flex flex-col gap-1">
            <h3 className="text-sm font-semibold leading-6">{heading}</h3>
            <p className="max-w-prose text-sm leading-6 text-muted-foreground">{body}</p>
          </div>
        </div>

        <div className="rounded-lg border bg-background/60 p-4">
          <p className="mb-3 text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">
            How a deal is paid on Influora
          </p>
          <MoneyFlowSteps waitingAt={waitingAt} />
        </div>
      </CardContent>
    </Card>
  );
}
