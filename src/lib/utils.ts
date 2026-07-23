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
