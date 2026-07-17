# TASK — Ananya (Frontend) — SaaS-style "How it works / How to use" Help Layer

> **From:** Priya (CTO) — approved by Swapnil (CEO), 2026-07-06
> **Scope decision:** Meera-first layered help (NOT a full help-center vendor).
> **Why this shape:** We already have Meera (AI) + signup onboarding. We add a *usage* help layer that leans on Meera instead of a static knowledge base — no vendor, no third-party script on money pages, minimal maintenance.

---

## CONTEXT (what already exists — do NOT rebuild these)
- **Signup onboarding** exists: `src/pages/brand-onboarding.tsx` + `src/components/brand/onboarding/` (6 steps: account/company/verification/team/trust/wallet). This is account SETUP. Leave it. We are adding *how-to-USE* help on top.
- **Meera** is routed at `/brand/meera` (`src/pages/brand-meera.tsx`). This is our knowledge-base engine — wire help INTO it, don't duplicate it.
- **Nav** is the 5-item brand nav in `src/components/brand/brand-layout.tsx`.

---

## BUILD — 4 pieces, priority order

### 1. First-run product tour (P0 of this task)
A one-time, dismissible spotlight walkthrough on first brand dashboard visit. Highlight the 5 nav areas + "New Campaign": Home, Meera, Campaigns, Creators, Deals, Wallet — one sentence each on what it's for.
- **Dependency rule (CTO):** Prefer **hand-rolled** using our existing `framer-motion` (already in stack) + a simple overlay/spotlight — no new dependency. If you strongly need a library, `driver.js` (~5kb, zero-dep) is the only approved pick — and you must log it in `wiki/tech/approved-deps.md` before `npm install`. Do not pull in react-joyride/reactour (heavier).
- Gate on a `localStorage` flag (`brand_tour_seen`) so it fires once. Honor `useReducedMotion()` — no motion tour for reduced-motion users, fall back to a static "quick start" card.
- Must be skippable at every step and re-launchable from the Help menu (see #4).

### 2. Contextual help — tooltips + empty states (P1)
- Jargon "?" tooltips (this overlaps your other audit task — do it once, here): **Escrow**, **Deliverable**, **Bid/Counter**, **Deal Room**. One plain-language line each.
- Empty states for 0 campaigns / 0 deals / 0 creators with a helpful next action, not a blank screen.

### 3. "Ask Meera" as the knowledge base (P1) — the differentiator
- A persistent **Help** affordance (see #4) whose primary action opens `/brand/meera` **pre-seeded** with a starter prompt like "How does Influora work — walk me through campaigns, deal rooms, and payments."
- Confirm with me the mechanism for pre-seeding Meera (query param, route state, or store action) before wiring — I want it consistent with how Meera already receives context. Do NOT invent a second Meera-entry path.

### 4. Help menu + "How it works" page (P1)
- A **Help** entry (avatar menu or a "?" in the top bar — your call, keep nav to 5 items, do NOT add a 6th nav item) that offers: "Take the tour again", "Ask Meera", "How it works".
- One static **/brand/help** (or `/how-it-works`) page: concise, skimmable, sections for Campaigns / Deal Rooms / Contracts / Payments & Escrow / Meera. Copy comes from **Tejas + Nisha** — coordinate; put in sensible placeholder copy and mark it `TODO: final copy from Nisha` so you're not blocked.

---

## RULES
- **No 6th nav item** — Help lives in the avatar menu or a top-bar "?" icon.
- **No third-party help/chat widget** (Intercom/Crisp/Beamer). Explicitly out of scope — money product, no external scripts with PII on wallet pages.
- **No new dependency** unless it's driver.js, logged in `approved-deps.md`, and approved by me first. Default to hand-rolled with framer-motion.
- Brand tokens only (we just finished the color sweep). `--meera-escrow` stays trust-only.
- Everything `MOTION_INTENSITY`-appropriate + `useReducedMotion()` fallback (tech-stack rule).
- All work → Kavya (QA) → Meera (build verify).

## REPORT BACK TO PRIYA
- Tour: hand-rolled or driver.js (if the latter, confirm you logged it)? Screenshot of the spotlight.
- Meera pre-seed mechanism you used (after confirming with me).
- Help menu location + /brand/help page (with placeholder copy flagged for Nisha).
- Confirm: no new nav item, no third-party widget, no un-approved dependency.
