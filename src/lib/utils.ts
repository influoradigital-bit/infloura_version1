import { clsx, type ClassValue } from 'clsx'
import { twMerge } from 'tailwind-merge'

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

/**
 * Format a number as Indian Rupees (INR)
 * Uses the Indian numbering system (lakhs, crores)
 *
 * Accepts null/undefined because some amounts are legitimately absent (e.g.
 * budget-less Meera drafts, or an unpopulated wallet/payout field) — without
 * this guard, Intl.NumberFormat renders "₹NaN". Returns a NEUTRAL "—" so this
 * shared formatter is safe in every money context (wallet, escrow, payouts,
 * budgets). Budget-specific copy like "No budget set" belongs at the call site
 * (see formatBudget in campaigns-list.tsx), not in this generic helper.
 */
export function formatINR(amount?: number | null): string {
  if (amount == null || Number.isNaN(amount)) return '—'
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    maximumFractionDigits: 0,
  }).format(amount)
}

/**
 * Build a creator's public profile URL from the *real* deployed origin
 * instead of a hardcoded domain. The app is reachable at different hosts
 * (influora.in, a raw IP during deploy, localhost in dev) and the previous
 * hardcoded "influora.com" label was always wrong/dead — the actual link
 * (`/@username`) was relative and worked fine, only the displayed/copied
 * text was broken. SSR-safe: falls back to the production domain when
 * `window` isn't available.
 */
export function publicProfileUrl(username: string): string {
  const origin = typeof window !== 'undefined' ? window.location.origin : 'https://influora.in'
  return `${origin}/@${username}`
}

/**
 * Same as publicProfileUrl but without the protocol/origin scheme prefix,
 * for compact display labels (e.g. "influora.in/@handle" instead of the
 * full "https://influora.in/@handle").
 */
export function publicProfileLabel(username: string): string {
  return publicProfileUrl(username).replace(/^https?:\/\//, '')
}

/**
 * Defense-in-depth guard for URLs sourced from brand-supplied data (e.g. a
 * coupon's raw `trackingUrl`) before they're bound to an `<a href>` or the
 * clipboard. React does not sanitize hrefs, so a `javascript:...` or other
 * dangerous-scheme string would otherwise reach the DOM as-is. Only `http:`
 * and `https:` are considered safe.
 */
export function isSafeHttpUrl(url?: string | null): boolean {
  if (!url) return false
  try {
    const parsed = new URL(url)
    return parsed.protocol === 'http:' || parsed.protocol === 'https:'
  } catch {
    return false
  }
}
