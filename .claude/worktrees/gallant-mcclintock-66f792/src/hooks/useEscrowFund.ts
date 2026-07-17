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
 */

import { useCallback, useRef, useState } from 'react';
import { meeraApi, type MeeraEscrowStatus } from '@/lib/meera-api';

export type EscrowFundStatus =
  | 'idle'
  | 'initiating'      // POST /wallet/escrow/fund in flight
  | 'awaiting_payment' // Razorpay checkout open
  | 'verifying'        // Polling for FUNDED status
  | 'funded'           // Server confirmed FUNDED
  | 'error';

export interface UseEscrowFundResult {
  status: EscrowFundStatus;
  error: string | null;
  /** Server-confirmed amount (only valid after initiation) */
  serverAmount: number | null;
  /** Razorpay order ID for checkout */
  razorpayOrderId: string | null;
  /** Escrow hold ID for status polling */
  escrowHoldId: string | null;
  /**
   * Initiate the escrow funding flow. Human must click this - never auto-called.
   * @param campaignId - The campaign to fund (server re-derives amount)
   * @param milestoneId - Optional milestone for partial funding
   */
  initiateFund: (campaignId: string, milestoneId?: string) => Promise<void>;
  /**
   * Call after Razorpay checkout completes (success callback).
   * Starts polling for server-confirmed FUNDED status.
   */
  onPaymentComplete: () => Promise<void>;
  /**
   * Reset to idle state (e.g., on cancel or retry)
   */
  reset: () => void;
}

/** Generate a client UUID for idempotency */
function generateIdempotencyKey(): string {
  return `${Date.now()}-${Math.random().toString(36).substring(2, 15)}`;
}

/** Poll interval for escrow status */
const POLL_INTERVAL_MS = 2000;
const POLL_MAX_ATTEMPTS = 30; // 60 seconds max

export function useEscrowFund(): UseEscrowFundResult {
  const [status, setStatus] = useState<EscrowFundStatus>('idle');
  const [error, setError] = useState<string | null>(null);
  const [serverAmount, setServerAmount] = useState<number | null>(null);
  const [razorpayOrderId, setRazorpayOrderId] = useState<string | null>(null);
  const [escrowHoldId, setEscrowHoldId] = useState<string | null>(null);

  const idempotencyKeyRef = useRef<string | null>(null);
  const pollTimerRef = useRef<number | null>(null);

  const reset = useCallback(() => {
    if (pollTimerRef.current !== null) {
      window.clearTimeout(pollTimerRef.current);
      pollTimerRef.current = null;
    }
    setStatus('idle');
    setError(null);
    setServerAmount(null);
    setRazorpayOrderId(null);
    setEscrowHoldId(null);
    idempotencyKeyRef.current = null;
  }, []);

  /**
   * Initiate the escrow funding flow.
   * This is the ONLY entry point - must be triggered by human click.
   */
  const initiateFund = useCallback(
    async (campaignId: string, milestoneId?: string) => {
      if (status !== 'idle') return;

      setStatus('initiating');
      setError(null);

      // Generate a new idempotency key for this funding attempt
      idempotencyKeyRef.current = generateIdempotencyKey();

      try {
        const response = await meeraApi.fundEscrow(
          campaignId,
          idempotencyKeyRef.current,
          milestoneId
        );

        // Store server-confirmed values
        setServerAmount(response.amount);
        setRazorpayOrderId(response.razorpayOrderId);
        setEscrowHoldId(response.escrowHoldId);
        setStatus('awaiting_payment');

        // Note: Caller is responsible for opening Razorpay Checkout
        // with the returned razorpayOrderId
      } catch (err) {
        setStatus('error');
        setError(err instanceof Error ? err.message : 'Failed to initiate funding');
      }
    },
    [status]
  );

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
   * Called after Razorpay checkout success callback.
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
    initiateFund,
    onPaymentComplete,
    reset,
  };
}

export default useEscrowFund;
