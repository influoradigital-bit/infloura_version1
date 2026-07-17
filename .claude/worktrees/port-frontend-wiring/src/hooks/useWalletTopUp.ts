/**
 * useWalletTopUp - human-confirm gate for brand wallet top-up (B0)
 * ----------------------------------------------------------------------------
 * Mirrors `useEscrowFund`'s shape and flow (src/hooks/useEscrowFund.ts) — same
 * "human commits money, never automated" discipline:
 *   - No amount re-derivation trick here (top-up amount IS the brand's own
 *     chosen deposit, unlike escrow's server-derived fund amount) but the
 *     mutation is still only ever triggered by an explicit human click.
 *   - Idempotency-Key generated client-side, required by the server.
 *   - Browser calls the PUBLIC `/wallet/topup` endpoint on the brand JWT.
 *
 * Flow:
 *   1. POST /wallet/topup { amount, pan?, gstin? } + Idempotency-Key header
 *   2. Server returns razorpayOrderId + PENDING status
 *   3. Caller opens Razorpay Checkout (human confirms payment)
 *   4. Unlike escrow funding, there is no GET /wallet/topup/{id} status-polling
 *      endpoint — the ledger credit is applied asynchronously off the Razorpay
 *      webhook (WalletTopUpService#confirmCredited). So `onPaymentComplete`
 *      only marks the local flow 'submitted'; the caller is responsible for
 *      re-fetching the wallet balance (api.wallet.get / getBalance) rather
 *      than trusting this hook to confirm the credit landed.
 */

import { useCallback, useState } from 'react';
import { api, ApiError, type WalletTopUpResponse } from '@/lib/api';

export type WalletTopUpStatus =
  | 'idle'
  | 'initiating' // POST /wallet/topup in flight
  | 'awaiting_payment' // Razorpay checkout open
  | 'submitted' // Razorpay success reported; webhook will credit the ledger
  | 'error';

export interface UseWalletTopUpResult {
  status: WalletTopUpStatus;
  error: string | null;
  /** Server response from the initiate call (order id, server-confirmed amount). */
  response: WalletTopUpResponse | null;
  /**
   * Initiate the top-up flow. Human must click this - never auto-called.
   * @param amount - the amount the brand chose to deposit
   * @param pan - optional PAN for TDS reconciliation
   * @param gstin - optional GSTIN for TDS reconciliation
   */
  initiateTopUp: (amount: number, pan?: string, gstin?: string) => Promise<void>;
  /**
   * Call after the Razorpay checkout success callback fires. There is no
   * server-side status to poll for top-ups (see file header), so this simply
   * marks the flow 'submitted' — callers should refresh the wallet balance
   * display themselves.
   */
  onPaymentComplete: () => void;
  /** Reset to idle state (e.g., on cancel, dialog close, or retry). */
  reset: () => void;
}

/** Generate a client idempotency key, mirroring useEscrowFund's helper. */
function generateIdempotencyKey(): string {
  return `topup-${Date.now()}-${Math.random().toString(36).substring(2, 15)}`;
}

export function useWalletTopUp(): UseWalletTopUpResult {
  const [status, setStatus] = useState<WalletTopUpStatus>('idle');
  const [error, setError] = useState<string | null>(null);
  const [response, setResponse] = useState<WalletTopUpResponse | null>(null);

  const reset = useCallback(() => {
    setStatus('idle');
    setError(null);
    setResponse(null);
  }, []);

  const initiateTopUp = useCallback(
    async (amount: number, pan?: string, gstin?: string) => {
      if (status !== 'idle' && status !== 'error') return;

      setStatus('initiating');
      setError(null);

      try {
        const result = await api.wallet.topUp(
          { amount, pan, gstin },
          generateIdempotencyKey(),
        );
        setResponse(result);
        setStatus('awaiting_payment');
        // Note: caller is responsible for opening Razorpay Checkout with
        // result.razorpayOrderId (same contract as useEscrowFund).
      } catch (err) {
        setStatus('error');
        setError(err instanceof ApiError ? err.message : 'Failed to start wallet top-up');
      }
    },
    [status],
  );

  const onPaymentComplete = useCallback(() => {
    setStatus('submitted');
  }, []);

  return {
    status,
    error,
    response,
    initiateTopUp,
    onPaymentComplete,
    reset,
  };
}

export default useWalletTopUp;
