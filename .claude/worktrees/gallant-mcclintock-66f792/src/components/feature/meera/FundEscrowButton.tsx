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
 */

import { useState, useEffect } from 'react';
import { Loader2, Lock, Check, AlertCircle } from 'lucide-react';

import { useEscrowFund, type EscrowFundStatus } from '@/hooks/useEscrowFund';
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

export function FundEscrowButton({
  campaignId,
  milestoneId,
  displayAmount,
  onFunded,
  className,
  disabled = false,
}: FundEscrowButtonProps) {
  const {
    status,
    error,
    serverAmount,
    razorpayOrderId,
    initiateFund,
    onPaymentComplete,
    reset,
  } = useEscrowFund();

  const [razorpayOpen, setRazorpayOpen] = useState(false);

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
      await initiateFund(campaignId, milestoneId);
    }
  };

  /**
   * Open Razorpay Checkout when awaiting_payment.
   * In a real implementation, this would use the Razorpay SDK.
   * For now, we simulate success after a delay.
   */
  const handleOpenRazorpay = async () => {
    if (status !== 'awaiting_payment' || !razorpayOrderId) return;

    setRazorpayOpen(true);

    // In production: window.Razorpay({ order_id: razorpayOrderId, ... }).open()
    // For mock mode, simulate payment completion after 1.5s
    if (!(import.meta as any).env?.VITE_API_MODE || (import.meta as any).env?.VITE_API_MODE !== 'live') {
      await new Promise((r) => setTimeout(r, 1500));
      setRazorpayOpen(false);
      await onPaymentComplete();
      onFunded?.();
    }
  };

  // Auto-trigger Razorpay when status changes to awaiting_payment
  // Wrapped in useEffect to avoid side effects during render (React StrictMode safe)
  useEffect(() => {
    if (status === 'awaiting_payment' && !razorpayOpen) {
      handleOpenRazorpay();
    }
  }, [status, razorpayOpen]);

  // Notify parent when funded
  if (status === 'funded') {
    // Call onFunded once, not repeatedly
  }

  const isLoading = status === 'initiating' || status === 'awaiting_payment' || status === 'verifying';
  const isSuccess = status === 'funded';
  const isError = status === 'error';

  // Use server amount if available, otherwise display amount hint
  const amount = serverAmount ?? displayAmount;

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

      {/* Trust copy - money moves only when you approve */}
      {status === 'idle' && (
        <p className="text-center text-xs text-meera-text-muted">
          Money moves only when you approve.
        </p>
      )}

      {/* Server-confirmed amount display */}
      {serverAmount && status !== 'idle' && (
        <p className="text-center text-xs text-meera-text-muted">
          {formatINR(serverAmount)} secured. Released only on your approval.
        </p>
      )}
    </div>
  );
}

export default FundEscrowButton;
