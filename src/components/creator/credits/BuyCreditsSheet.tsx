import * as React from 'react';
import { useReducedMotion } from 'framer-motion';
import { CheckCircle2, Loader2, ShieldCheck } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription, SheetFooter } from '@/components/ui/sheet';
import { api, ApiError, type CreatorCreditBalance } from '@/lib/api';
import { openRazorpayCheckout, type RazorpayCheckoutResponse } from '@/lib/razorpay';
import { creditsCopy } from '@/lib/copy/creator-credits';
import { spendableCredits, spendableFreeCredits } from '@/lib/creator-credits-balance';

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md §9.3, F6/A48) — the top-up flow: `createOrder` → Checkout (opened
 * ONLY from the creator's own tap on the in-sheet Buy button, never automatically) → the gateway's
 * `onSuccess` callback → server-side `verify` → poll on `PENDING`.
 *
 * ## The one rule this component exists to enforce (K-11 / R7)
 * Razorpay Checkout's `handler` callback (`onSuccess` below) is UNTRUSTED — it only means "the
 * browser saw a success screen", not "money moved". `onSuccess` alone NEVER flips this component
 * to the success state; it only triggers a call to `POST /creator/credits/orders/{id}/verify`,
 * which independently re-checks the payment with Razorpay server-side before the backend credits
 * anything. Only a `CREDITED` response from `verify` (or from a later poll of `GET
 * /creator/credits` showing the balance actually grew) is ever shown as success.
 */

type PurchaseStage =
  | 'idle'
  | 'creating_order'
  | 'awaiting_payment'
  | 'verifying'
  | 'polling'
  | 'success'
  | 'pending'
  | 'failed';

const POLL_INTERVAL_MS = 3000;
const POLL_TIMEOUT_MS = 30000;

export interface BuyCreditsSheetProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** BCP-47-ish `creator_language`. */
  language?: string;
  balance: CreatorCreditBalance | null;
  /** Fires once `verify`/the poll loop confirms `CREDITED`, with the new total, so the caller can
   *  refresh its own pill/balance state instead of this sheet owning that. */
  onCredited?: (total: number) => void;
}

function safeRandomKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  return `ccr-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
}

function wait(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

export function BuyCreditsSheet({ open, onOpenChange, language, balance, onCredited }: BuyCreditsSheetProps) {
  const reduceMotion = useReducedMotion();
  const [stage, setStage] = React.useState<PurchaseStage>('idle');
  const [errorText, setErrorText] = React.useState<string | null>(null);
  const [finalBalance, setFinalBalance] = React.useState<number | null>(null);
  const pollAbortRef = React.useRef(false);

  // Reset to a fresh attempt every time the sheet is (re)opened, so a prior success/failure
  // doesn't linger the next time the creator opens it.
  React.useEffect(() => {
    if (open) {
      setStage('idle');
      setErrorText(null);
      setFinalBalance(null);
      pollAbortRef.current = false;
    } else {
      pollAbortRef.current = true;
    }
  }, [open]);

  React.useEffect(() => () => {
    pollAbortRef.current = true;
  }, []);

  const pack = balance?.pack ?? { code: 'PACK_60', credits: 60, pricePaise: 24900, gstInclusive: true };

  const pollForCredit = React.useCallback(
    async (initialTotal: number, targetCredits: number) => {
      setStage('polling');
      const deadline = Date.now() + POLL_TIMEOUT_MS;
      while (!pollAbortRef.current && Date.now() < deadline) {
        await wait(POLL_INTERVAL_MS);
        if (pollAbortRef.current) return;
        try {
          const fresh = await api.creatorCredits.get();
          const total = fresh.total ?? initialTotal;
          if (fresh.enabled && total >= initialTotal + targetCredits) {
            if (!pollAbortRef.current) {
              setFinalBalance(total);
              setStage('success');
              onCredited?.(total);
            }
            return;
          }
        } catch {
          // A single failed poll tick is not fatal — keep trying until the deadline.
        }
      }
      if (!pollAbortRef.current) setStage('pending');
    },
    [onCredited],
  );

  const handleBuyClick = async () => {
    setErrorText(null);
    setStage('creating_order');
    try {
      const idempotencyKey = safeRandomKey();
      const order = await api.creatorCredits.createOrder(pack.code, idempotencyKey);
      const initialTotal = balance?.total ?? 0;

      setStage('awaiting_payment');
      await openRazorpayCheckout({
        role: 'creator',
        orderId: order.razorpayOrderId,
        amount: order.amountPaise,
        currency: order.currency,
        name: 'Influora',
        description: creditsCopy('sheet.pack', language),
        onSuccess: (response: RazorpayCheckoutResponse) => {
          void (async () => {
            setStage('verifying');
            try {
              const result = await api.creatorCredits.verify(
                order.orderId,
                response.razorpay_payment_id,
                response.razorpay_signature ?? '',
              );
              if (result.status === 'CREDITED') {
                setFinalBalance(result.balance);
                setStage('success');
                onCredited?.(result.balance);
              } else {
                await pollForCredit(initialTotal, order.credits);
              }
            } catch (err) {
              setErrorText(err instanceof ApiError ? err.message : null);
              setStage('failed');
            }
          })();
        },
        onDismiss: (reason?: string) => {
          if (reason) {
            setErrorText(reason);
            setStage('failed');
          } else {
            // The creator closed Checkout without paying — no money moved, no error to show.
            setStage('idle');
          }
        },
      });
    } catch (err) {
      setErrorText(err instanceof ApiError ? err.message : null);
      setStage('failed');
    }
  };

  const busy = stage === 'creating_order' || stage === 'awaiting_payment' || stage === 'verifying' || stage === 'polling';

  return (
    <Sheet open={open} onOpenChange={(next) => !busy && onOpenChange(next)}>
      <SheetContent
        side="bottom"
        data-testid="buy-credits-sheet"
        className={
          'max-h-[85vh] overflow-y-auto rounded-t-2xl sm:inset-x-auto sm:inset-y-auto sm:left-1/2 sm:top-1/2 sm:bottom-auto sm:w-full sm:max-w-md sm:-translate-x-1/2 sm:-translate-y-1/2 sm:rounded-2xl sm:border' +
          (reduceMotion ? ' !duration-0' : '')
        }
      >
        <SheetHeader>
          <SheetTitle>{creditsCopy('sheet.title', language)}</SheetTitle>
          <SheetDescription>{creditsCopy('sheet.pack', language)}</SheetDescription>
        </SheetHeader>

        <div className="space-y-3 px-4 text-sm text-foreground">
          <p className="text-muted-foreground">{creditsCopy('sheet.bullets', language)}</p>
          {balance?.enabled ? (
            <p data-testid="buy-sheet-balance" className="font-medium">
              {creditsCopy('sheet.balance', language, {
                total: spendableCredits(balance),
                free: spendableFreeCredits(balance),
                paid: balance.paid ?? 0,
              })}
            </p>
          ) : null}
          <p className="text-xs text-muted-foreground">{creditsCopy('sheet.freeFirst', language)}</p>
          <p className="flex items-center gap-1.5 text-xs text-muted-foreground">
            <ShieldCheck className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
            {creditsCopy('sheet.provider', language)}
          </p>

          {stage === 'success' && (
            <p
              role="status"
              data-testid="buy-sheet-success"
              className="flex items-start gap-2 rounded-lg bg-[var(--success)] px-3 py-2 text-sm text-[var(--success-foreground)]"
            >
              <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
              {creditsCopy('pay.success', language, { total: finalBalance ?? 0 })}
            </p>
          )}
          {stage === 'pending' && (
            <p role="status" data-testid="buy-sheet-pending" className="rounded-lg bg-[var(--info)] px-3 py-2 text-sm text-[var(--info-foreground)]">
              {creditsCopy('pay.pending', language)}
            </p>
          )}
          {stage === 'failed' && (
            <p role="alert" data-testid="buy-sheet-failed" className="rounded-lg bg-destructive px-3 py-2 text-sm text-destructive-foreground">
              {errorText || creditsCopy('pay.failed', language)}
            </p>
          )}
        </div>

        <SheetFooter>
          {stage === 'success' ? (
            <Button type="button" onClick={() => onOpenChange(false)}>
              Done
            </Button>
          ) : (
            <Button
              type="button"
              onClick={handleBuyClick}
              disabled={busy}
              data-testid="buy-sheet-confirm"
              className="h-11 min-h-11"
            >
              {busy ? (
                <>
                  <Loader2 className="mr-2 h-4 w-4 animate-spin" aria-hidden="true" />
                  {stage === 'polling' ? creditsCopy('pay.pending', language) : creditsCopy('sheet.title', language)}
                </>
              ) : (
                creditsCopy('buy.cta', language)
              )}
            </Button>
          )}
        </SheetFooter>
      </SheetContent>
    </Sheet>
  );
}

export default BuyCreditsSheet;
