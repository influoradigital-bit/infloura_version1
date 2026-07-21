/**
 * useEscrowFund - Human-confirm gate for escrow funding
 * ----------------------------------------------------------------------------
 * P11: Commit-tier confirm control for fund_escrow action.
 *
 * Security rules (06-MEERA-PERMISSIONS-MATRIX.md):
 *   - Human commits money, never Meera. Chat "yes" is not consent.
 *   - No amount in request body - server re-derives from campaignId
 *   - Idempotency-Key header required (client UUID, retry-safe)
 *   - Funding success confirmed by SERVER status = FUNDED (webhook-verified)
 *   - Browser calls PUBLIC endpoint on user JWT (not /internal/meera/*)
 *
 * Flow:
 *   1. POST /wallet/escrow/fund { campaignId } + Idempotency-Key header
 *   2. Server returns razorpayOrderId + PENDING status
 *   3. Open Razorpay Checkout (human confirms payment)
 *   4. Poll GET /wallet/escrow/{id} until status === FUNDED
 *   5. Only then mark isPaid in UI
 *
 * Option 1 addition (inline insufficient-funds -> top-up -> fund, one button,
 * SHARED_CONTEXT.md "Vikram - Option 1 Backend DONE"):
 *   - `EscrowService.initiateFund` throws `INSUFFICIENT_FUNDS` (HTTP 402) when
 *     the wallet balance is short. Rather than surfacing that as a dead-end
 *     error, this hook transitions to `insufficient_funds` and exposes
 *     `beginTopUp` so the caller can top up the shortfall inline, then
 *     automatically retries the SAME fund attempt (same idempotency key -
 *     no double charge risk, see EscrowService.initiateFund's idempotency
 *     replay check).
 *   - [SEC: MF-1 follow-up, 2026-07-21] The top-up charge is sized from the
 *     SERVER-computed `shortfallAmount` on the 402 body (`ApiError.details`,
 *     see `InsufficientFundsException`/`ApiErrorBody`), NOT from a client
 *     estimate. `requiredAmountHint` (e.g. FundEscrowButton's `displayAmount`
 *     prop) is display-only now - it may label the button before the first
 *     attempt, but it is never sent to `/wallet/topup`. If a 402 somehow
 *     arrives without the shortfall fields (older/edge server), this hook
 *     does NOT fall back to re-estimating - it surfaces an error asking the
 *     human to top up from the wallet page instead.
 *   - `initiateFund`'s retry re-derives and re-validates the fund amount
 *     server-side exactly like the first attempt regardless of what was
 *     charged on the top-up leg.
 *   - `POST /wallet/topup` has no status-poll endpoint - the ledger credit
 *     lands asynchronously off the Razorpay webhook. After the top-up
 *     Checkout succeeds, this hook re-fetches the wallet balance a bounded
 *     number of times (`confirming_topup`) before retrying the fund call, to
 *     absorb webhook lag without hard-failing.
 */

import { useCallback, useRef, useState } from 'react';
import { api, ApiError, type InsufficientFundsDetails } from '@/lib/api';
import { meeraApi, type MeeraEscrowStatus } from '@/lib/meera-api';

export type EscrowFundStatus =
  | 'idle'
  | 'initiating'          // POST /wallet/escrow/fund in flight
  | 'insufficient_funds'  // 402 INSUFFICIENT_FUNDS - about to top up the shortfall
  | 'topping_up'          // POST /wallet/topup in flight
  | 'awaiting_topup_payment' // Razorpay checkout open for the top-up shortfall
  | 'confirming_topup'    // top-up Checkout succeeded; re-fetching balance (webhook lag)
  | 'awaiting_payment'    // Razorpay checkout open for the escrow fund itself
  | 'verifying'           // Polling for FUNDED status
  | 'funded'              // Server confirmed FUNDED
  | 'error';

/** Server-derived top-up order to hand to Razorpay Checkout. */
export interface EscrowTopUpOrder {
  topUpId: string;
  amount: number;
  currency: string;
  razorpayOrderId: string;
}

export interface UseEscrowFundResult {
  status: EscrowFundStatus;
  error: string | null;
  /** Server-confirmed amount (only valid after initiation) */
  serverAmount: number | null;
  /** Razorpay order ID for the escrow-fund checkout */
  razorpayOrderId: string | null;
  /** Escrow hold ID for status polling */
  escrowHoldId: string | null;
  /** Display-only shortfall hint shown while `status === 'insufficient_funds'` (never sized against). */
  requiredAmountHint: number | null;
  /**
   * [SEC: MF-1 follow-up] The AUTHORITATIVE server-computed shortfall from the
   * `INSUFFICIENT_FUNDS` 402 body, present whenever `status === 'insufficient_funds'`.
   * This - not `requiredAmountHint` - is what sizes the inline top-up charge.
   */
  serverShortfall: InsufficientFundsDetails | null;
  /** Razorpay order for the inline top-up, once `beginTopUp` succeeds. */
  topUpOrder: EscrowTopUpOrder | null;
  /**
   * Initiate the escrow funding flow. Human must click this - never auto-called.
   * @param campaignId - The campaign to fund (server re-derives amount)
   * @param milestoneId - Optional milestone for partial funding
   * @param requiredAmountHint - Optional display-amount hint, used only to size an inline top-up if the wallet is short
   */
  initiateFund: (campaignId: string, milestoneId?: string, requiredAmountHint?: number) => Promise<void>;
  /**
   * Start an inline top-up for the shortfall, from `insufficient_funds`.
   * @param amount - amount to add to the wallet. Callers should pass `serverShortfall.shortfallAmount`
   *   (the authoritative server-computed figure) - this is not re-validated against the hint, and the
   *   eventual escrow fund itself is always re-derived server-side regardless of what's topped up here.
   */
  beginTopUp: (amount: number) => Promise<void>;
  /**
   * Call after the TOP-UP Razorpay checkout completes (success callback).
   * Re-fetches the wallet balance (bounded attempts, absorbing webhook lag)
   * then automatically retries the original fund attempt.
   */
  onTopUpPaymentComplete: () => Promise<void>;
  /**
   * Call after the ESCROW-FUND Razorpay checkout completes (success callback).
   * Starts polling for server-confirmed FUNDED status.
   */
  onPaymentComplete: () => Promise<void>;
  /**
   * Reset to idle state (e.g., on cancel or retry)
   */
  reset: () => void;
}

/** Generate a client UUID for idempotency */
function generateIdempotencyKey(prefix: string): string {
  return `${prefix}-${Date.now()}-${Math.random().toString(36).substring(2, 15)}`;
}

/** Poll interval for escrow status */
const POLL_INTERVAL_MS = 2000;
const POLL_MAX_ATTEMPTS = 30; // 60 seconds max

/** Bounded balance-recheck window after a top-up Checkout success, to absorb webhook lag. */
const BALANCE_RECHECK_INTERVAL_MS = 2000;
const BALANCE_RECHECK_MAX_ATTEMPTS = 5; // ~10 seconds, then retry the fund anyway

/** How many inline top-up rounds we'll attempt before giving up and surfacing an error. */
const MAX_TOPUP_ROUNDS = 2;

export function useEscrowFund(): UseEscrowFundResult {
  const [status, setStatus] = useState<EscrowFundStatus>('idle');
  const [error, setError] = useState<string | null>(null);
  const [serverAmount, setServerAmount] = useState<number | null>(null);
  const [razorpayOrderId, setRazorpayOrderId] = useState<string | null>(null);
  const [escrowHoldId, setEscrowHoldId] = useState<string | null>(null);
  const [requiredAmountHint, setRequiredAmountHint] = useState<number | null>(null);
  const [serverShortfall, setServerShortfall] = useState<InsufficientFundsDetails | null>(null);
  const [topUpOrder, setTopUpOrder] = useState<EscrowTopUpOrder | null>(null);

  const idempotencyKeyRef = useRef<string | null>(null);
  const topUpIdempotencyKeyRef = useRef<string | null>(null);
  const pollTimerRef = useRef<number | null>(null);
  const balanceRecheckTimerRef = useRef<number | null>(null);
  // Preserved across the insufficient_funds -> top-up -> retry loop so the retry can
  // call initiateFund again with the SAME campaign/milestone (and same idempotency key).
  const pendingFundRef = useRef<{ campaignId: string; milestoneId?: string } | null>(null);
  const topUpRoundsRef = useRef(0);

  const clearTimers = () => {
    if (pollTimerRef.current !== null) {
      window.clearTimeout(pollTimerRef.current);
      pollTimerRef.current = null;
    }
    if (balanceRecheckTimerRef.current !== null) {
      window.clearTimeout(balanceRecheckTimerRef.current);
      balanceRecheckTimerRef.current = null;
    }
  };

  const reset = useCallback(() => {
    clearTimers();
    setStatus('idle');
    setError(null);
    setServerAmount(null);
    setRazorpayOrderId(null);
    setEscrowHoldId(null);
    setRequiredAmountHint(null);
    setServerShortfall(null);
    setTopUpOrder(null);
    idempotencyKeyRef.current = null;
    topUpIdempotencyKeyRef.current = null;
    pendingFundRef.current = null;
    topUpRoundsRef.current = 0;
  }, []);

  /**
   * Initiate (or retry) the escrow funding flow. This is the ONLY entry point
   * for a fresh attempt - must be triggered by human click. Also used
   * internally to retry after a successful inline top-up (same idempotency key).
   */
  const initiateFund = useCallback(
    async (campaignId: string, milestoneId?: string, requiredAmountHintArg?: number) => {
      // Allow re-entry from 'confirming_topup' (the internal retry path); otherwise only
      // a fresh human click from 'idle' may start a new attempt.
      if (status !== 'idle' && status !== 'confirming_topup') return;

      pendingFundRef.current = { campaignId, milestoneId };
      if (requiredAmountHintArg !== undefined) setRequiredAmountHint(requiredAmountHintArg);

      setStatus('initiating');
      setError(null);

      // Generate a new idempotency key only for a genuinely new submission (idle -> initiating).
      // A retry from confirming_topup reuses the key minted on the original attempt.
      if (!idempotencyKeyRef.current) {
        idempotencyKeyRef.current = generateIdempotencyKey('escrow');
      }

      try {
        const response = await meeraApi.fundEscrow(campaignId, idempotencyKeyRef.current, milestoneId);

        // Store server-confirmed values
        setServerAmount(response.amount);
        setRazorpayOrderId(response.razorpayOrderId);
        setEscrowHoldId(response.escrowHoldId);
        setStatus('awaiting_payment');

        // Note: Caller is responsible for opening Razorpay Checkout
        // with the returned razorpayOrderId
      } catch (err) {
        if (err instanceof ApiError && err.code === 'INSUFFICIENT_FUNDS') {
          if (topUpRoundsRef.current >= MAX_TOPUP_ROUNDS) {
            setStatus('error');
            setError('Wallet is still short after topping up. Please add funds from the wallet page and try again.');
            return;
          }
          // [SEC: MF-1 follow-up] The server-computed shortfall (details) is the ONLY value
          // that may size the inline top-up charge. An older/edge server that 402s without it
          // is a fallback case, not silently re-estimated client-side - surface an error and
          // send the human to the wallet page instead.
          if (!err.details) {
            setStatus('error');
            setError('Could not determine the exact top-up amount. Please add funds from the wallet page and try again.');
            return;
          }
          setServerShortfall(err.details);
          setStatus('insufficient_funds');
          return;
        }
        setStatus('error');
        setError(err instanceof Error ? err.message : 'Failed to initiate funding');
      }
    },
    [status],
  );

  // `recheckBalanceThenRetry` below has an intentionally empty dep array (it's a
  // self-rescheduling timer loop) but must always call the LATEST `initiateFund`
  // (whose identity changes with `status`), not the one captured at loop-start -
  // otherwise it would retry against a stale `status` check. Ref indirection avoids
  // the stale closure without re-creating the timer loop on every status change.
  const initiateFundRef = useRef(initiateFund);
  initiateFundRef.current = initiateFund;

  /**
   * Start an inline top-up for the shortfall. Human-triggered via the same
   * "Fund & go live" button flow (FundEscrowButton auto-advances from
   * insufficient_funds), never auto-submitted to Razorpay itself - the
   * Checkout modal still requires a human tap to pay.
   * [SEC: MF-1 follow-up] `amount` should be `serverShortfall.shortfallAmount` -
   * this function does not itself enforce that (it just forwards whatever it's
   * given to `/wallet/topup`), so the caller is responsible for never passing a
   * client-estimated figure.
   */
  const beginTopUp = useCallback(async (amount: number) => {
    if (status !== 'insufficient_funds') return;
    if (!amount || amount <= 0) {
      setStatus('error');
      setError('Could not determine a top-up amount. Please add funds from the wallet page.');
      return;
    }

    setStatus('topping_up');
    setError(null);
    topUpIdempotencyKeyRef.current = generateIdempotencyKey('topup');

    try {
      const order = await api.wallet.topUp({ amount }, topUpIdempotencyKeyRef.current);
      setTopUpOrder({
        topUpId: order.topUpId,
        amount: order.amount,
        currency: order.currency,
        razorpayOrderId: order.razorpayOrderId,
      });
      setStatus('awaiting_topup_payment');
    } catch (err) {
      setStatus('error');
      setError(err instanceof Error ? err.message : 'Failed to start top-up');
    }
  }, [status]);

  /**
   * Bounded balance re-check after top-up Checkout success, then retries the
   * original fund attempt regardless of whether the balance visibly moved yet
   * (initiateFund's retry re-validates against the authoritative ledger under
   * lock - if the webhook genuinely hasn't landed, it will correctly 402
   * again and, within MAX_TOPUP_ROUNDS, offer another top-up round).
   */
  const recheckBalanceThenRetry = useCallback(
    async (attempt = 0) => {
      if (attempt >= BALANCE_RECHECK_MAX_ATTEMPTS) {
        const pending = pendingFundRef.current;
        if (pending) await initiateFundRef.current(pending.campaignId, pending.milestoneId);
        return;
      }
      try {
        // Best-effort refresh; failures here don't block the retry - initiateFund's own
        // server-side check is the authoritative one.
        await api.wallet.get('brand');
      } catch {
        // ignore - fall through to retry anyway
      }
      balanceRecheckTimerRef.current = window.setTimeout(() => {
        recheckBalanceThenRetry(attempt + 1);
      }, BALANCE_RECHECK_INTERVAL_MS);
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  );

  /**
   * Called after the TOP-UP Razorpay checkout success callback fires.
   * SECURITY: the client Razorpay callback is never trusted as proof of a
   * credited wallet - only used to decide when to start re-checking/retrying.
   */
  const onTopUpPaymentComplete = useCallback(async () => {
    topUpRoundsRef.current += 1;
    setTopUpOrder(null);
    setStatus('confirming_topup');
    const pending = pendingFundRef.current;
    if (!pending) {
      setStatus('error');
      setError('Lost track of the campaign to fund. Please try again.');
      return;
    }
    await recheckBalanceThenRetry(0);
  }, [recheckBalanceThenRetry]);

  /**
   * Poll for FUNDED status after Razorpay callback.
   * SECURITY: Never trust client Razorpay callback alone - verify server-side.
   */
  const pollForFunded = useCallback(async (holdId: string, attempt = 0): Promise<void> => {
    if (attempt >= POLL_MAX_ATTEMPTS) {
      setStatus('error');
      setError('Payment verification timed out. Please contact support.');
      return;
    }

    try {
      const escrowStatus: MeeraEscrowStatus = await meeraApi.getEscrowStatus(holdId);

      if (escrowStatus.status === 'FUNDED') {
        setStatus('funded');
        return;
      }

      if (escrowStatus.status === 'CANCELLED') {
        setStatus('error');
        setError('Payment was cancelled.');
        return;
      }

      // Still pending - poll again
      pollTimerRef.current = window.setTimeout(() => {
        pollForFunded(holdId, attempt + 1);
      }, POLL_INTERVAL_MS);
    } catch (err) {
      // Network error during polling - retry
      pollTimerRef.current = window.setTimeout(() => {
        pollForFunded(holdId, attempt + 1);
      }, POLL_INTERVAL_MS);
    }
  }, []);

  /**
   * Called after the ESCROW-FUND Razorpay checkout success callback.
   * Starts server-side verification polling.
   */
  const onPaymentComplete = useCallback(async () => {
    if (!escrowHoldId) {
      setStatus('error');
      setError('No escrow hold ID available');
      return;
    }

    setStatus('verifying');
    await pollForFunded(escrowHoldId);
  }, [escrowHoldId, pollForFunded]);

  return {
    status,
    error,
    serverAmount,
    razorpayOrderId,
    escrowHoldId,
    requiredAmountHint,
    serverShortfall,
    topUpOrder,
    initiateFund,
    beginTopUp,
    onTopUpPaymentComplete,
    onPaymentComplete,
    reset,
  };
}

export default useEscrowFund;
