import type { CreatorCreditBalance } from '@/lib/api';

/**
 * The credits a creator can spend on their NEXT message — the only number any credits UI shows.
 *
 * `GET /creator/credits` reports `total` from grants that already exist, and lists separately, in
 * `pending`, the welcome grant (40, before the first charge) and this month's free grant (15, at a
 * new month) that have not been written yet. Both land inside the very next charge, before the
 * balance is checked (`CreatorCreditService.charge`: `materializeMonthly` then `grantWelcome`), and
 * `pending.welcome` uses the same eligibility test `grantWelcome` does. So they are spendable now.
 *
 * Reading `total` alone showed a brand-new creator "0 credits", an out-of-credits banner and
 * disabled quick actions while 40 free credits were waiting (2026-09-24).
 */
export function spendableCredits(balance: CreatorCreditBalance | null | undefined): number {
  if (!balance) return 0;
  return (balance.total ?? 0) + pendingCredits(balance);
}

/** Free credits, including the pending welcome/monthly grants (they are free buckets). */
export function spendableFreeCredits(balance: CreatorCreditBalance | null | undefined): number {
  if (!balance) return 0;
  return (balance.free ?? 0) + pendingCredits(balance);
}

function pendingCredits(balance: CreatorCreditBalance): number {
  return Math.max(0, balance.pending?.welcome ?? 0) + Math.max(0, balance.pending?.monthly ?? 0);
}
