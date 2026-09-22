import * as React from 'react';

import { api, type CreatorCreditBalance } from '@/lib/api';

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md §9.3, F5-F9) — the one place a creator-side component reads
 * `GET /creator/credits`. Every credits UI piece (`CreditBalancePill`, `BuyCreditsCard`,
 * `ZeroCreditsBanner`, `CreditCostHint`, `WelcomeCreditsModal`, `MeeraCopilotChat`'s own refusal
 * handling) is driven off the SAME fetched `CreatorCreditBalance`, so the pill, the banner and the
 * chat can never show a stale/mismatched number relative to each other within one mounted tree.
 *
 * Deliberately NOT a `Context` provider: nothing in this task list wires a page-level provider
 * around both `MeeraCopilotChat` and `PasteBriefCard`, and each already fetches its own data
 * independently (see `MeeraCopilotChat`'s own `meeraApi.startSession`/`getHistory` calls). Two
 * independent `GET /creator/credits` calls when both are mounted is a deliberate, small trade for
 * keeping each component self-contained and independently testable — the same shape this codebase
 * already uses elsewhere (e.g. `useEscrowFund` vs `brand-wallet.tsx` both calling `api.wallet.get`).
 *
 * With the server flag off, `GET /creator/credits` itself always 200s `{enabled:false}` (never a
 * 404) — see `CreatorCreditDtos.BalanceResponse.disabled()` — so `enabled` here is reliably false
 * rather than the hook having to infer "off" from a fetch failure. A real fetch failure (network,
 * 401 mid-session, etc.) is treated the same way: nothing new renders, and the rest of the page
 * behaves exactly as it does today. This hook never throws.
 */
export interface UseCreatorCreditsResult {
  /** True until the first `GET /creator/credits` settles (success or failure). */
  loading: boolean;
  /** Mirrors `balance?.enabled ?? false` — the single flag every credits component gates on. */
  enabled: boolean;
  balance: CreatorCreditBalance | null;
  /** Re-fetches the balance. Callers use this after a turn/brief/purchase that may have changed it. */
  refresh: () => Promise<void>;
  /**
   * SPEC.md §9.3 — "pill refresh from `creditsRemaining`": an OPTIMISTIC, synchronous patch of
   * just `total` from the integer `MeeraTurnResponse.creditsRemaining`/turn result already carries,
   * so the header pill updates the instant a turn completes instead of waiting on a second
   * `GET /creator/credits` round trip. A no-op when there is no balance yet or credits are
   * disabled — `CreditBalancePill` renders nothing in that state regardless. Callers should still
   * call {@link refresh} afterward (fire-and-forget is fine) to reconcile `dailyUsed`/`pending`/etc,
   * which this patch does not touch.
   */
  applyCreditsRemaining: (total: number) => void;
}

export function useCreatorCredits(): UseCreatorCreditsResult {
  const [balance, setBalance] = React.useState<CreatorCreditBalance | null>(null);
  const [loading, setLoading] = React.useState(true);
  const mountedRef = React.useRef(true);

  React.useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const refresh = React.useCallback(async () => {
    try {
      const res = await api.creatorCredits.get();
      if (mountedRef.current) setBalance(res);
    } catch {
      // A fetch failure must never break the surrounding chat/brief flow — fall back to "nothing
      // new renders" rather than surfacing an error nobody asked to see here.
      if (mountedRef.current) setBalance((prev) => prev ?? { enabled: false });
    } finally {
      if (mountedRef.current) setLoading(false);
    }
  }, []);

  React.useEffect(() => {
    refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const applyCreditsRemaining = React.useCallback((total: number) => {
    setBalance((prev) => (prev && prev.enabled ? { ...prev, total } : prev));
  }, []);

  return { loading, enabled: balance?.enabled ?? false, balance, refresh, applyCreditsRemaining };
}

export default useCreatorCredits;
