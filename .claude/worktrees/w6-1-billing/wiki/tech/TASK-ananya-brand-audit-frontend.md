# TASK — Ananya (Frontend) — Brand UX Audit Follow-ups

> **From:** Priya (CTO)
> **Date:** 2026-07-06
> **Source:** Tejas CMO first-time brand UX audit (17 questions)
> **Priority:** Read the CTO ruling first — it right-sizes the "P0 launch blockers."

---

## ⚠️ CTO RULING — the two "P0 blockers" are smaller than the audit says

### Blocker 1 — "Registration form broken" → FALSE POSITIVE
I verified the register/login forms work for real users (the auditor hit a test-automation artifact, not a bug). **Do not touch `brand-register.tsx` / `brand-login.tsx` form logic.** Details in `TASK-vikram-brand-audit-backend.md`.

### Blocker 2 — "Message/Deal Room/Contract confusion" → ALREADY 80% SOLVED
The nav is **already unified**. See `src/components/brand/brand-layout.tsx` lines 65-74 & 117:
- Single **"Deals"** nav item → `/brand/chat`, and the code comment literally says *"Deals nav covers chat, contracts and messages — they share the same surface"* and *"Contracts live inside Deal Room (P2.1)."*
- There is **no** standalone "Messages" nav item. The structural fix Tejas asked for mostly exists.

**So this is NOT a restructure. The remaining gap is EXPLANATION + cleanup, which is your real work below.**

---

## YOUR ACTUAL WORK (priority order)

### P0.1 — Deal Room explainer (the real remaining part of "terminology confusion")
A first-time brand still doesn't know what a "Deal Room" is or that chat/contract/deliverables live together.
- Add a **first-visit explainer** on the Deals surface (`/brand/chat`): a dismissible popover or empty-state card:
  *"This is your Deal Room. Everything for a collaboration lives here — chat, contract, and deliverables. Each accepted campaign or direct invite gets its own room."*
- Inside a deal room, make sure the **Chat / Contract / Deliverables** structure is visually obvious (tabs or clearly labeled sections). If tabs already exist, ensure labels are explicit. Don't rebuild — audit and clarify.

### P0.2 — Reconcile the orphaned routes (cleanup)
`/brand/messages` (`brand-messages.tsx`) and `/brand/contracts` (`brand-contracts.tsx`) are **routed but not in the main nav** (`App.tsx` lines 180-192). Decide with intent:
- If they're legacy/superseded by the unified `/brand/chat` surface → remove the routes + delete the dead pages (check for any live links/imports first — do NOT delete blind; we got burned by a scaffold deletion before).
- If they're intentional deep-link sub-surfaces → leave them, but confirm nothing tells a user they're primary destinations.
- Report which you chose and why.

### P1.1 — Trust section on registration (Q7)
Brands with no fintech background don't know why escrow matters.
- Add a "How Influora Protects Your Budget" block to the brand registration page (3 points: escrow-held funds, verified creators, release-on-approval). Copy comes from Tejas + Nisha — coordinate; don't write final marketing copy yourself.
- Use existing brand tokens (`--meera-escrow` for the escrow/trust visual — it's the load-bearing trust green, don't repurpose it decoratively).

### P1.2 — Make Meera visible during campaign creation (Q8, Q16)
Meera exists in nav but is invisible when the brand actually needs help.
- Add a **"✨ Let Meera draft this"** affordance on the campaign brief form (`components/brand/campaigns/campaign-form.tsx`).
- If Meera can act on the brand's behalf (e.g. message a creator), that action must be **explicit and approved by the brand** — never auto-send. Confirm the interaction model with me before wiring any send-on-behalf behavior.

### P2 — Jargon tooltips + empty states (Q14, Q15, Q17)
- First-use tooltips for **"Deliverable"**, **"Bid/Counter"**, **"Escrow"** (one-line plain-language definitions).
- Empty-state copy for 0 campaigns / 0 deals / 0 creators (helpful next-step prompts, not blank screens).

### P2 — Mobile responsiveness pass (Q12) — coordinate with Kavya
Desktop is good post the color-token sweep. Mobile is unverified. Check forms, deal-room layout, wallet cards, campaign grids at `<768px`. Kavya runs the QA; you fix what breaks.

---

## RULES
- **Do not rewrite working auth forms.** (False positive — see ruling.)
- **Do not blind-delete** the orphaned routes — grep for live imports/links first (prior scaffold-deletion regression).
- Stay inside the brand token system — no raw Tailwind colors (we just finished that sweep). `--meera-escrow` stays trust-only.
- Additive UX changes; preserve existing analytics event names / IDs.
- Everything through Kavya (QA) → Meera (build verify).

## REPORT BACK TO PRIYA
- Deal Room explainer: where you placed it + screenshot.
- Orphaned routes: keep or delete + reasoning.
- Trust section + Meera affordance: status (blocked on copy? shipped?).
- Confirm no auth-form logic was touched.
