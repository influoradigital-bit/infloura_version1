/**
 * Razorpay Checkout launcher
 * ----------------------------------------------------------------------------
 * The real `window.Razorpay(...).open()` integration — replaces the two
 * simulated-checkout stubs that used to live in `FundEscrowButton.tsx`
 * (`handleOpenRazorpay`) and `brand-wallet.tsx` (`topUpOrder` surfaced the
 * order only, no launcher).
 *
 * Money-safety (Kabir MF-1 / no-secret-in-frontend):
 *   - The `key` handed to Razorpay Checkout is ALWAYS the publishable Key ID
 *     fetched from `GET /config/razorpay` (`PublicConfigController`). This
 *     module never hardcodes a key and never expects/accepts a secret.
 *   - This is a UI convenience layer only. It never decides whether money
 *     actually moved — Checkout's own `handler` callback is untrusted for
 *     that; callers MUST verify funding via a server-confirmed status
 *     (escrow: poll `GET /wallet/escrow/{id}` for FUNDED; top-up: re-fetch
 *     `GET /wallet` — see `useEscrowFund.ts` / `brand-wallet.tsx`).
 */

import { api } from './api';

// ---------------------------------------------------------------------------
// window.Razorpay typings (no `any` — the SDK ships no first-party types)
// ---------------------------------------------------------------------------

export interface RazorpayCheckoutResponse {
  razorpay_payment_id: string;
  razorpay_order_id: string;
  razorpay_signature?: string;
}

export interface RazorpayCheckoutOptions {
  key: string;
  amount?: number;
  currency?: string;
  name: string;
  description?: string;
  order_id: string;
  prefill?: { name?: string; email?: string; contact?: string };
  theme?: { color?: string };
  handler: (response: RazorpayCheckoutResponse) => void;
  modal?: {
    ondismiss?: () => void;
  };
}

interface RazorpayInstance {
  open: () => void;
  on: (event: 'payment.failed', handler: (response: { error: { description?: string } }) => void) => void;
  close: () => void;
}

declare global {
  interface Window {
    Razorpay?: new (options: RazorpayCheckoutOptions) => RazorpayInstance;
  }
}

const RAZORPAY_SCRIPT_SRC = 'https://checkout.razorpay.com/v1/checkout.js';
const SCRIPT_ID = 'razorpay-checkout-sdk';

let scriptLoadPromise: Promise<void> | null = null;
let cachedKeyId: string | null = null;
let keyIdFetchPromise: Promise<string> | null = null;

/**
 * Injects the Razorpay Checkout script exactly once, idempotently. Safe to
 * call from multiple components/renders — concurrent callers share the same
 * in-flight load, and a script tag left over from a prior mount (e.g. HMR)
 * is reused instead of duplicated.
 */
export function loadRazorpayScript(): Promise<void> {
  if (typeof window === 'undefined') {
    return Promise.reject(new Error('Razorpay Checkout can only load in a browser context'));
  }
  if (window.Razorpay) {
    return Promise.resolve();
  }
  if (scriptLoadPromise) {
    return scriptLoadPromise;
  }

  scriptLoadPromise = new Promise<void>((resolve, reject) => {
    const existing = document.getElementById(SCRIPT_ID) as HTMLScriptElement | null;
    if (existing) {
      existing.addEventListener('load', () => resolve(), { once: true });
      existing.addEventListener('error', () => reject(new Error('Failed to load Razorpay Checkout script')), {
        once: true,
      });
      // Script tag already finished loading before this call attached listeners.
      if (window.Razorpay) resolve();
      return;
    }

    const script = document.createElement('script');
    script.id = SCRIPT_ID;
    script.src = RAZORPAY_SCRIPT_SRC;
    script.async = true;
    script.onload = () => resolve();
    script.onerror = () => {
      // Allow a future call to retry instead of caching a permanent failure.
      scriptLoadPromise = null;
      reject(new Error('Failed to load Razorpay Checkout script'));
    };
    document.head.appendChild(script);
  });

  return scriptLoadPromise;
}

/**
 * Fetches the publishable Razorpay Key ID from `GET /config/razorpay` and
 * caches it for the session (it is a static, environment-scoped value, not
 * per-request). NEVER a secret — see `PublicConfigController` on the backend.
 */
export async function getRazorpayKeyId(): Promise<string> {
  if (cachedKeyId) return cachedKeyId;
  if (keyIdFetchPromise) return keyIdFetchPromise;

  keyIdFetchPromise = api.config
    .razorpay()
    .then((res) => {
      cachedKeyId = res.keyId;
      return res.keyId;
    })
    .finally(() => {
      keyIdFetchPromise = null;
    });

  return keyIdFetchPromise;
}

export interface OpenCheckoutParams {
  orderId: string;
  amount?: number;
  currency?: string;
  name: string;
  description?: string;
  prefill?: RazorpayCheckoutOptions['prefill'];
  /** Called once Checkout reports success. NEVER treat this as proof money moved — verify server-side. */
  onSuccess: (response: RazorpayCheckoutResponse) => void;
  /** Called when the user closes/cancels Checkout without paying, or the SDK fails to load/open. No money moved. */
  onDismiss: (reason?: string) => void;
}

/**
 * Loads the SDK (if needed), fetches the publishable key (if needed), and
 * opens Razorpay Checkout for the given order. Human interaction with the
 * Checkout modal is required to submit payment — this function only opens
 * the modal, it never auto-submits.
 */
export async function openRazorpayCheckout(params: OpenCheckoutParams): Promise<void> {
  const { orderId, amount, currency, name, description, prefill, onSuccess, onDismiss } = params;

  let keyId: string;
  try {
    await loadRazorpayScript();
    keyId = await getRazorpayKeyId();
  } catch (err) {
    onDismiss(err instanceof Error ? err.message : 'Could not load payment SDK');
    return;
  }

  if (!window.Razorpay) {
    onDismiss('Razorpay Checkout unavailable');
    return;
  }

  const checkout = new window.Razorpay({
    key: keyId,
    amount,
    currency: currency ?? 'INR',
    name,
    description,
    order_id: orderId,
    prefill,
    theme: { color: '#6D28D9' },
    handler: (response) => onSuccess(response),
    modal: {
      ondismiss: () => onDismiss(),
    },
  });

  checkout.on('payment.failed', (response) => {
    onDismiss(response.error?.description || 'Payment failed');
  });

  checkout.open();
}
