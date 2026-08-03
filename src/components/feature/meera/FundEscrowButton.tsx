/**
 * FundEscrowButton - Human-confirm control for escrow funding
 * ----------------------------------------------------------------------------
 * P11: Commit-tier confirm control. This is the ONLY way to fund escrow.
 *
 * Security rules (06-MEERA-PERMISSIONS-MATRIX.md):
 *   - Meera can ONLY surface this button (pre-filled), never auto-submit
 *   - Calls PUBLIC Spring endpoint on USER JWT (not /internal/meera/*)
 *   - No amount in request body - displays server amount, sends campaignId only
 *   - Idempotency-Key header required (retry-safe)
 *   - Success confirmed by SERVER escrow status = FUNDED (not Razorpay callback)
 *
 * This component DOES NOT auto-trigger from tool_result. The tool_result
 * only renders this button visible - human click is required to proceed.
 *
 * Option 1 (inline Razorpay at the fund step, SHARED_CONTEXT.md "Vikram -
 * Option 1 Backend DONE" / "Ananya - Option 1 Frontend"): this button now
 * opens the REAL Razorpay Checkout (src/lib/razorpay.ts) instead of
 * simulating success, and if the wallet is short, tops up the shortfall
 * inline (also via real Checkout) before retrying the fund - all from this
 * one button, so a brand never has to detour to /brand/wallet first. The
 * client Razorpay callback is NEVER trusted as proof money moved - only
 * `useEscrowFund`'s server-side polling (escrow) / balance re-check
 * (top-up) advances the state machine past a Checkout success.
 */

import { useEffect, useRef } from 'react';
import { Loader2, Lock, Check, AlertCircle, Wallet } from 'lucide-react';

import { useEscrowFund, type EscrowFundStatus } from '@/hooks/useEscrowFund';
import { openRazorpayCheckout } from '@/lib/razorpay';
import { formatINR, cn } from '@/lib/utils';

interface FundEscrowButtonProps {
  /** Campaign ID to fund (server re-derives amount) */
  campaignId: string;
  /** Optional milestone for partial funding */
  milestoneId?: string;
  /** Display amount (hint only - server is authoritative) */
  displayAmount?: number;
  /** Called when funding is confirmed by server */
  onFunded?: () => void;
  /** Custom class name */
  className?: string;
  /** Disabled state (e.g., during other operations) */
  disabled?: boolean;
}

/** Map status to button label */
function getButtonLabel(status: EscrowFundStatus, displayAmount?: number): string {
  const amountStr = displayAmount ? formatINR(displayAmount) : '';

  switch (status) {
    case 'idle':
      return amountStr ? `Fund & go live — ${amountStr}` : 'Fund & go live';
    case 'initiating':
      return 'Initiating...';
    case 'insufficient_funds':
    case 'topping_up':
      return 'Adding funds...';
    case 'awaiting_topup_payment':
      return 'Complete payment...';
    case 'confirming_topup':
      return 'Confirming payment...';
    case 'awaiting_payment':
      return 'Complete payment...';
    case 'verifying':
      return 'Verifying...';
    case 'funded':
      return 'Secured';
    case 'error':
      return 'Try again';
    default:
      return 'Fund & go live';
  }
}

/** Map status to icon */
function getButtonIcon(status: EscrowFundStatus) {
  switch (status) {
    case 'initiating':
    case 'insufficient_funds':
    case 'topping_up':
    case 'awaiting_topup_payment':
    case 'confirming_topup':
    case 'awaiting_payment':
    case 'verifying':
      return <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />;
    case 'funded':
      return <Check className="h-4 w-4" aria-hidden="true" />;
    case 'error':
      return <AlertCircle className="h-4 w-4" aria-hidden="true" />;
    default:
      return <Lock className="h-4 w-4" aria-hidden="true" />;
  }
}

/** Status-specific helper copy shown under the button. */
function getStatusHelperText(status: EscrowFundStatus): string | null {
  switch (status) {
    case 'insufficient_funds':
      return 'Your wallet balance is short — adding the difference before funding.';
    case 'topping_up':
    case 'awaiting_topup_payment':
      return 'Add the shortfall to your wallet, then funding continues automatically.';
    case 'confirming_topup':
      return 'Confirming your payment — this can take a few seconds.';
    default:
      return null;
  }
}

export function FundEscrowButton({
  campaignId,
  milestoneId,
  displayAmount,
  // NOTE: `onFunded` is declared on FundEscrowButtonProps but is not currently
  // invoked anywhere in this component — the parent's callback never fires on a
  // successful fund. Left un-destructured (not wired) deliberately: wiring a
  // payment-completion callback is out of scope for a lint pass. Tracked separately.
  className,
  disabled = false,
}: FundEscrowButtonProps) {
  const {
    status,
    error,
    serverAmount,
    razorpayOrderId,
    serverShortfall,
    topUpOrder,
    initiateFund,
    beginTopUp,
    onTopUpPaymentComplete,
    onPaymentComplete,
    reset,
  } = useEscrowFund();

  // Guards against opening the same Razorpay modal twice for one state (React
  // StrictMode double-invoke / re-render safe), separately for each checkout leg.
  const escrowCheckoutOpenRef = useRef(false);
  const topUpCheckoutOpenRef = useRef(false);
  // Guards the insufficient_funds -> beginTopUp auto-advance so it fires once per attempt.
  const topUpStartedRef = useRef(false);

  /**
   * Handle button click - the ONLY way to initiate funding.
   * Human click required - never auto-called.
   */
  const handleClick = async () => {
    if (status === 'funded') return;

    if (status === 'error') {
      // Reset and allow retry
      reset();
      return;
    }

    if (status === 'idle') {
      await initiateFund(campaignId, milestoneId, displayAmount);
    }
  };

  // Open Razorpay Checkout for the ESCROW-FUND order when the server confirms one is
  // ready. Real launcher (src/lib/razorpay.ts) - no more simulated setTimeout.
  useEffect(() => {
    if (status !== 'awaiting_payment' || !razorpayOrderId || escrowCheckoutOpenRef.current) return;
    escrowCheckoutOpenRef.current = true;

    openRazorpayCheckout({
      orderId: razorpayOrderId,
      amount: serverAmount ?? undefined,
      currency: 'INR',
      name: 'Influora',
      description: 'Fund campaign escrow',
      onSuccess: () => {
        escrowCheckoutOpenRef.current = false;
        // NEVER trust this callback alone - onPaymentComplete polls the server for
        // a verified FUNDED status before the UI reports success.
        void onPaymentComplete();
      },
      onDismiss: () => {
        escrowCheckoutOpenRef.current = false;
        // No money moved - return to idle so the human can retry with a fresh click.
        reset();
      },
    });

    return () => {
      escrowCheckoutOpenRef.current = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status, razorpayOrderId]);

  // Auto-advance from insufficient_funds into the inline top-up leg - still part of
  // the single "Fund & go live" click, no separate button/detour to /brand/wallet.
  // [SEC: MF-1 follow-up, 2026-07-21] The top-up amount is the AUTHORITATIVE
  // server-computed `shortfallAmount` from the 402 body (`serverShortfall`) - this
  // component no longer estimates it from a client wallet read. If the hook didn't
  // get a server shortfall (older/edge server), it already transitioned straight to
  // `error` instead of `insufficient_funds` (see useEscrowFund's catch block), so
  // `serverShortfall` is guaranteed non-null whenever this effect fires; the `0`
  // fallback below only guards a defensive/impossible case.
  useEffect(() => {
    if (status !== 'insufficient_funds' || topUpStartedRef.current) return;
    topUpStartedRef.current = true;
    void beginTopUp(serverShortfall?.shortfallAmount ?? 0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status]);

  // Reset the one-shot top-up guard whenever a fresh attempt starts.
  useEffect(() => {
    if (status === 'idle') topUpStartedRef.current = false;
  }, [status]);

  // Open Razorpay Checkout for the TOP-UP shortfall order.
  useEffect(() => {
    if (status !== 'awaiting_topup_payment' || !topUpOrder || topUpCheckoutOpenRef.current) return;
    topUpCheckoutOpenRef.current = true;

    openRazorpayCheckout({
      orderId: topUpOrder.razorpayOrderId,
      amount: topUpOrder.amount,
      currency: topUpOrder.currency,
      name: 'Influora',
      description: 'Add funds to wallet',
      onSuccess: () => {
        topUpCheckoutOpenRef.current = false;
        // The top-up ledger credit is applied asynchronously off the Razorpay
        // webhook - this only starts the bounded balance re-check + fund retry.
        void onTopUpPaymentComplete();
      },
      onDismiss: () => {
        topUpCheckoutOpenRef.current = false;
        // No money moved on this leg - back out entirely rather than leaving the
        // human stranded mid-flow.
        reset();
      },
    });

    return () => {
      topUpCheckoutOpenRef.current = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status, topUpOrder]);

  const isLoading =
    status === 'initiating' ||
    status === 'insufficient_funds' ||
    status === 'topping_up' ||
    status === 'awaiting_topup_payment' ||
    status === 'confirming_topup' ||
    status === 'awaiting_payment' ||
    status === 'verifying';
  const isSuccess = status === 'funded';
  const isError = status === 'error';

  // Use server amount if available, otherwise display amount hint
  const amount = serverAmount ?? displayAmount;
  const helperText = getStatusHelperText(status);

  return (
    <div className={cn('space-y-2', className)}>
      <button
        type="button"
        onClick={handleClick}
        disabled={disabled || isLoading || isSuccess}
        className={cn(
          'inline-flex h-11 w-full items-center justify-center gap-2 rounded-lg px-6 text-sm font-semibold transition-all duration-150 ease-out',
          'focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-[var(--meera-accent-glow)]',
          'active:scale-[0.97]',
          // Default accent state
          !isSuccess && !isError && 'bg-meera-accent text-white hover:bg-meera-accent-hover',
          // Success: escrow green
          isSuccess && 'bg-meera-escrow text-white cursor-default',
          // Error: allow retry
          isError && 'bg-meera-danger text-white hover:bg-meera-danger/90',
          // Disabled
          (disabled || isLoading) && 'cursor-not-allowed opacity-60',
        )}
        aria-live="polite"
      >
        {getButtonIcon(status)}
        <span>{getButtonLabel(status, amount)}</span>
      </button>

      {/* Error message */}
      {error && (
        <p className="text-center text-xs text-meera-danger" role="alert">
          {error}
        </p>
      )}

      {/* Insufficient-funds / top-up helper copy */}
      {!error && helperText && (
        <p className="flex items-center justify-center gap-1.5 text-center text-xs text-meera-text-muted">
          <Wallet className="h-3.5 w-3.5" aria-hidden="true" />
          {helperText}
        </p>
      )}

      {/* Trust copy - money moves only when you approve */}
      {status === 'idle' && (
        <p className="text-center text-xs text-meera-text-muted">
          Money moves only when you approve.
        </p>
      )}

      {/* Server-confirmed amount display */}
      {serverAmount && status !== 'idle' && !helperText && (
        <p className="text-center text-xs text-meera-text-muted">
          {formatINR(serverAmount)} secured. Released only on your approval.
        </p>
      )}
    </div>
  );
}

export default FundEscrowButton;
