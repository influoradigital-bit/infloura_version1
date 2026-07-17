# QA Review: brand-billing-settings.tsx (Task 17)
**Date:** 2026-07-14  
**Reviewer:** Kavya (QA Lead)  
**Routed by:** Arjun (Eng Lead)  
**Task:** `SUBSCRIPTION-BILLING-PLAN.md` §1.5 Task 17 — billing settings page UI shell  
**Status:** ✅ **PASS WITH MINOR RECOMMENDATIONS**

---

## FILES REVIEWED
- `src/pages/brand-billing-settings.tsx` (new, 478 lines)
- `src/App.tsx` (route addition, lines 19 + 253-259)

---

## VERIFICATION RESULTS

### ✅ PASS — TypeScript Compliance
- `npx tsc --noEmit` — **0 errors** (independently confirmed)
- All types properly defined (no `any` types)
- Proper TypeScript strict mode compliance
- `PlanCode`, `Plan`, `UsageCounter`, `Invoice` interfaces match spec exactly

### ✅ PASS — TECH-STACK.md Compliance
- **Vite + React 19 + react-router-dom v7** — correct (not Next.js patterns, spec audit-corrected)
- Tailwind v4 utility classes only (no inline styles)
- shadcn-style component reuse (`Button`, `Card`, `Badge`, `Progress`, `Tooltip`, `Separator` from `src/components/ui/*`)
- No fabricated backend calls (mock data clearly marked, no API imports)
- Route properly protected via `BrandLayoutWrapper` → `ProtectedRoute` (line 255-257 in App.tsx)

### ✅ PASS — Route Registration
- Route: `/brand/settings/billing` correctly registered inside `BrandLayoutWrapper`
- Authentication: brand-token-gated via `ProtectedRoute` wrapper (App.tsx:68-76)
- No collision with existing routes (verified against full routing table)
- Follows established pattern (matches `/brand/settings` structure)

### ✅ PASS — Mock Data Honesty (Per Priya's Audit Standards)
- **No silent fabrication** — all mock data clearly declared as constants:
  - Lines 94-109: `mockCurrentPlan`, `mockNextBillingDate`, `mockUsageCounters`, `mockInvoices` — explicit naming
- **Clear UI boundaries** — no `api.ts` import, no fetch calls that would 404 in live mode
- **Honest empty states** — invoices section shows "No invoices yet" (line 402), payment method shows "coming soon" placeholder (line 467)
- **Follows TECH-STACK.md rule #7** — "typed NOT_IMPLEMENTED error in live mode" not needed here since there are zero API calls (shell-only UI, backend entities pending per spec §0.5)

### ✅ PASS — Accessibility (WCAG AA)
- **Progress bars:** Radix UI `<Progress>` primitive used (lines 352-360), inherently accessible (ARIA roles baked into @radix-ui/react-progress)
  - Note: `aria-label` or `aria-labelledby` not explicitly added to `<Progress>` component instances, but each progress bar has visible text label immediately above it (lines 339-350) providing context — meets WCAG 1.3.1 (Info and Relationships)
- **Disabled button accessibility:** 
  - "Upgrade to Pro" button (line 305-308) uses proper `disabled` prop + Radix `<Tooltip>` wrapper (lines 303-312)
  - Tooltip content: "Coming soon — Razorpay checkout integration" (line 311) — clear reason for disabled state
  - Keyboard accessible (Tooltip is keyboard-navigable by default)
- **Color contrast:**
  - Status indicators use semantic color classes with proper contrast (line 428: green-500, amber-500, red-500 for paid/pending/failed)
  - Limit-reached indicators (lines 362-365) use red-500 with AlertCircle icon — WCAG AA compliant contrast against white/dark backgrounds

### ✅ PASS — Security
- No API keys or credentials (none present)
- No NEXT_PUBLIC_ variables (verified via grep — 0 matches)
- No hardcoded secrets
- No direct database calls (component is pure UI)

### ✅ PASS — Architecture
- Component follows PascalCase naming (`BrandBillingSettingsPage`)
- No hooks defined (component is pure render, uses imported shadcn components)
- No inline styles (all Tailwind utility classes)
- No direct backend integration (mock data only, per spec design)

---

## NON-BLOCKING RECOMMENDATIONS

### R1: Progress Bar ARIA Enhancement (Low Priority)
**Current:** Progress bars rely on adjacent text labels for context (WCAG-compliant but implicit).  
**Recommendation:** Add explicit `aria-label` to each `<Progress>` component for screen-reader redundancy:
```tsx
<Progress
  value={percentage}
  aria-label={`${counter.metric}: ${counter.used} of ${counter.limit ?? 'unlimited'}`}
  className={...}
/>
```
**Why defer:** Current implementation is WCAG-compliant (visible labels provide context per 1.3.1). This is defense-in-depth, not a standards violation. Can be added in a polish pass.

### R2: Empty-State Keyboard Navigation (Low Priority)
**Current:** Empty states (invoices line 398-408, payment method 463-472) are visually clear but not programmatically announced.  
**Recommendation:** Consider `role="status"` + `aria-live="polite"` on empty-state containers for dynamic content that changes based on plan state.  
**Why defer:** These are static empty states (not dynamically loading), so they're readable by screen readers as-is. Enhancement would only improve experience for users navigating mid-page-load.

---

## SPEC COMPLIANCE CHECK (SUBSCRIPTION-BILLING-PLAN.md §1.5)

| Requirement | Status | Evidence |
|-------------|--------|----------|
| Plan card (Free vs Pro comparison) | ✅ | Lines 145-255 (current plan card) + 257-318 (upgrade comparison table) |
| Usage meters (tracked creators, analytics, AI, seats) | ✅ | Lines 321-383 (4 counters with progress bars) |
| Invoices section (empty state) | ✅ | Lines 386-448 (empty state for Free tier, line 402-407) |
| Payment method placeholder | ✅ | Lines 451-474 (disabled "Upgrade to Pro" button + "coming soon" placeholder) |
| Vite/React (not Next.js) | ✅ | Confirmed via grep (no `next` imports), uses `react-router-dom` |
| Tailwind v4 utility classes | ✅ | No inline styles, all className-based |
| shadcn component reuse | ✅ | Imports from `@/components/ui/*` (6 components) |
| TypeScript strict (no `any`) | ✅ | Verified via tsc, all types explicit |
| No fabricated backend calls | ✅ | Zero `@/lib/api` imports, mock data clearly marked |

---

## HONEST CAVEAT (Per Arjun's Instructions)

**This page is intentionally UI-shell-only** — per `SUBSCRIPTION-BILLING-PLAN.md` §0.5 audit, backend entities (`Subscription`, `Invoice`, `UsageCounter`) are being built in parallel by Vikram (Task 11-16). The mock usage data (e.g. "3/5 tracked creators") is clearly marked as `mockUsageCounters` and would need backend wiring before going live.

**What's actually solid:**
- Route protection (brand-token-gated, verified)
- TypeScript types match spec exactly (ready for backend integration)
- UI component structure follows TECH-STACK.md standards
- No silent fake-live data (everything is honest mock or empty state)

**What's still gated:**
- Live Razorpay checkout (Task 12 backend dependency)
- Real usage counters (Task 15 backend gating logic)
- Invoice PDF generation (Task 16)
- Plan-gated feature enforcement (Task 14-15)

---

## VERDICT

✅ **PASS** — Route to Meera for local verification (`npm run build` + `npm run dev` smoke test).

**No blocking issues.** The page is well-architected, TECH-STACK.md-compliant, accessibility-sound, and honest about its mock boundaries. The 2 recommendations above are polish items, not gates.

**NEXT:** Meera → build verify → if green, ready for Priya's final review once backend integration is complete.

---

**Kavya Reddy**  
QA Lead, Sage Digital
