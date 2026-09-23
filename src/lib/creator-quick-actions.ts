import type { CreatorCreditBalance } from '@/lib/api';
import { creditsCopy } from '@/lib/copy/creator-credits';
import { spendableCredits } from '@/lib/creator-credits-balance';
import type { CreatorTurnAction } from '@/lib/meera-api';

/**
 * Prices and "can this be sent now?" for the creator chat's quick-action buttons (2026-09-22).
 * Prices come from the server (`balance.costs`); 3 is only the fallback for an older server.
 */

export const FALLBACK_COST = 3;

export function actionCost(action: CreatorTurnAction, balance: CreatorCreditBalance | null): number {
  const costs = balance?.costs;
  return (action === 'SCRIPT' ? costs?.script : costs?.profileReview) ?? FALLBACK_COST;
}

/** Why this action can't be sent right now (plain sentence), or null. Only when credits are on. */
export function actionBlockedReason(
  action: CreatorTurnAction,
  balance: CreatorCreditBalance | null,
  language?: string,
): string | null {
  if (!balance?.enabled) return null;
  const n = actionCost(action, balance);
  const have = spendableCredits(balance);
  if (have < n) return creditsCopy('action.notEnough', language, { n, have });
  const cap = balance.dailyCap;
  const used = balance.dailyUsed;
  if (typeof cap === 'number' && typeof used === 'number' && used + n > cap) {
    return creditsCopy('action.capLeft', language, { n, left: Math.max(0, cap - used) });
  }
  return null;
}
