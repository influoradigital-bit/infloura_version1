export type RunwayTone = 'healthy' | 'warning' | 'critical';

/**
 * Map a wallet's `runwayDays` to a health tone for the runway alarm.
 *
 * F-0099 (false-critical-runway-fallback, money-path): `runwayDays` is `number | null`. `null`
 * means a dormant/funded wallet — no spend in the trailing window, so runway is
 * undefined/effectively-infinite — and MUST read 'healthy', never 'critical'. Only a genuinely
 * computed NUMERIC runway drives 'warning'/'critical'.
 *
 * This is the tripwire for the recurring "false-CRITICAL-wallet-alarm" class: the original bug
 * defaulted/coerced the unknown case to `0` and then compared `runwayDays < 14`. Because JS
 * coerces `null < 14` to `0 < 14` (true), every unknown/pre-load/404'd/dormant wallet flashed a
 * false red CRITICAL. Callers MUST pass the real `number | null` here — never `?? 0`.
 */
export function runwayTone(runwayDays: number | null): RunwayTone {
  if (runwayDays == null) return 'healthy';
  if (runwayDays < 14) return 'critical';
  if (runwayDays < 30) return 'warning';
  return 'healthy';
}
