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

/**
 * Every mounted `useCreatorCredits` hears every other one's new balance (2026-09-24). The Co-pilot
 * page mounts several at once (the chat or the hero chip, plus `PasteBriefCard`); each used to
 * update only itself, so a chat message left the brief card's count stale, and credits bought from
 * one place still read "0" in another. Each hook still owns its own state and still fetches on
 * mount; this only forwards changes, so nothing leaks between unrelated trees or tests.
 */
type BalanceUpdate = (update: (prev: CreatorCreditBalance | null) => CreatorCreditBalance | null) => void;
const listeners = new Set<BalanceUpdate>();

function broadcast(from: BalanceUpdate, update: (prev: CreatorCreditBalance | null) => CreatorCreditBalance | null) {
  listeners.forEach((listener) => {
    if (listener !== from) listener(update);
  });
}

/**
 * A charge adds any pending welcome/monthly grant before it debits (`CreatorCreditService.charge`),
 * so the `creditsRemaining` it returns already includes them: clear `pending`, or the pill would
 * count those credits twice until the follow-up refresh lands.
 */
function withTotalAfterCharge(prev: CreatorCreditBalance | null, total: number): CreatorCreditBalance | null {
  if (!prev || !prev.enabled) return prev;
  return { ...prev, total, pending: prev.pending ? { welcome: 0, monthly: 0 } : prev.pending };
}

export function useCreatorCredits(): UseCreatorCreditsResult {
  const [balance, setBalance] = React.useState<CreatorCreditBalance | null>(null);
  const [loading, setLoading] = React.useState(true);
  const mountedRef = React.useRef(true);

  // Stable per hook instance: the function other instances call to push a change into this one.
  const receive = React.useCallback<BalanceUpdate>((update) => {
    if (!mountedRef.current) return;
    setBalance(update);
    setLoading(false);
  }, []);

  React.useEffect(() => {
    mountedRef.current = true;
    listeners.add(receive);
    return () => {
      mountedRef.current = false;
      listeners.delete(receive);
    };
  }, [receive]);

  const refresh = React.useCallback(async () => {
    try {
      const res = await api.creatorCredits.get();
      if (mountedRef.current) setBalance(res);
      broadcast(receive, () => res);
    } catch {
      // A fetch failure must never break the surrounding chat/brief flow — fall back to "nothing
      // new renders" rather than surfacing an error nobody asked to see here.
      if (mountedRef.current) setBalance((prev) => prev ?? { enabled: false });
    } finally {
      if (mountedRef.current) setLoading(false);
    }
  }, [receive]);

  React.useEffect(() => {
    refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const applyCreditsRemaining = React.useCallback(
    (total: number) => {
      setBalance((prev) => withTotalAfterCharge(prev, total));
      broadcast(receive, (prev) => withTotalAfterCharge(prev, total));
    },
    [receive],
  );

  return { loading, enabled: balance?.enabled ?? false, balance, refresh, applyCreditsRemaining };
}

export default useCreatorCredits;
