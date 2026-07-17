/**
 * useMeeraCredits - AI credit meter state management
 * ----------------------------------------------------------------------------
 * P13: Credit meter bound to GET /meera/credits + creditsRemaining from turns.
 *
 * Credit states (02 section 1.3, 04 section 5):
 *   - unlimited: Funded/live workspace - no meter pressure
 *   - metered: FREE tier - show remaining/allotment, decrement per turn
 *   - paused/exhausted: 402 CREDITS_EXHAUSTED - show paywall
 *
 * Voice weighting (04 section 5):
 *   - text input: 1 credit
 *   - voice input: 3 credits
 *   - voice reply: 4 credits
 *   - website analysis: 10 credits
 */

import { useCallback, useEffect, useState } from 'react';
import { meeraApi, type MeeraCreditStatus } from '@/lib/meera-api';

export type CreditState = 'loading' | 'unlimited' | 'metered' | 'low' | 'exhausted' | 'error';

/** Threshold for "low credits" warning */
const LOW_CREDITS_THRESHOLD = 10;

export interface UseMeeraCreditsResult {
  state: CreditState;
  creditsRemaining: number;
  monthlyAllotment: number;
  unlimited: boolean;
  unlimitedUntil: string | null;
  cycleStart: string | null;
  /** Fetch fresh credit status from server */
  refresh: () => Promise<void>;
  /** Update credits locally (e.g., from turn response) */
  updateRemaining: (remaining: number) => void;
  /** Set exhausted state (e.g., from 402 response) */
  setExhausted: () => void;
}

export function useMeeraCredits(): UseMeeraCreditsResult {
  const [state, setState] = useState<CreditState>('loading');
  const [creditsRemaining, setCreditsRemaining] = useState(0);
  const [monthlyAllotment, setMonthlyAllotment] = useState(100);
  const [unlimited, setUnlimited] = useState(false);
  const [unlimitedUntil, setUnlimitedUntil] = useState<string | null>(null);
  const [cycleStart, setCycleStart] = useState<string | null>(null);

  /**
   * Derive credit state from current values
   */
  const deriveState = useCallback((remaining: number, isUnlimited: boolean): CreditState => {
    if (isUnlimited) return 'unlimited';
    if (remaining <= 0) return 'exhausted';
    if (remaining <= LOW_CREDITS_THRESHOLD) return 'low';
    return 'metered';
  }, []);

  /**
   * Fetch fresh credit status from server
   */
  const refresh = useCallback(async () => {
    try {
      const status: MeeraCreditStatus = await meeraApi.getCredits();

      setCreditsRemaining(status.creditsRemaining);
      setMonthlyAllotment(status.monthlyAllotment);
      setUnlimited(status.unlimited);
      setUnlimitedUntil(status.unlimitedUntil);
      setCycleStart(status.cycleStart);
      setState(deriveState(status.creditsRemaining, status.unlimited));
    } catch (err) {
      console.error('[useMeeraCredits] Failed to fetch credits:', err);
      setState('error');
    }
  }, [deriveState]);

  /**
   * Update credits locally (e.g., from turn response creditsRemaining field)
   */
  const updateRemaining = useCallback(
    (remaining: number) => {
      setCreditsRemaining(remaining);
      setState(deriveState(remaining, unlimited));
    },
    [deriveState, unlimited]
  );

  /**
   * Set exhausted state (e.g., from 402 CREDITS_EXHAUSTED response)
   */
  const setExhausted = useCallback(() => {
    setCreditsRemaining(0);
    setState('exhausted');
  }, []);

  // Fetch credits on mount
  useEffect(() => {
    refresh();
  }, [refresh]);

  return {
    state,
    creditsRemaining,
    monthlyAllotment,
    unlimited,
    unlimitedUntil,
    cycleStart,
    refresh,
    updateRemaining,
    setExhausted,
  };
}

export default useMeeraCredits;
