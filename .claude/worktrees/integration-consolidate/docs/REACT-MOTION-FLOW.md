# Influora — React Motion & 3D Implementation Flow

**Purpose:** Step-by-step flow for how React code should be built, wired, and rolled out.  
**No code in this doc** — only architecture, file order, decision rules, and page-by-page plan.  
**Sources:** `FrontendDesign/` kit + current Influora codebase (`src/`)

> **Full documentation pack:** [`docs/react/README.md`](./react/README.md) — config, blueprint, master prompt, plan of action, and Cursor/Gemini prompt files.

---

## Table of Contents

1. [Big Picture Flow](#1-big-picture-flow)
2. [React Folder Flow (What to Create First)](#2-react-folder-flow-what-to-create-first)
3. [Motion Level Decision Tree](#3-motion-level-decision-tree)
4. [Component Build Order](#4-component-build-order)
5. [Page Integration Flow](#5-page-integration-flow)
6. [Data & State Flow (No Backend Change)](#6-data--state-flow-no-backend-change)
7. [Auth vs App Shell vs Page Flow](#7-auth-vs-app-shell-vs-page-flow)
8. [3D Canvas Lifecycle Flow](#8-3d-canvas-lifecycle-flow)
9. [Performance & Accessibility Gate](#9-performance--accessibility-gate)
10. [Session Workflow (How Devs Work)](#10-session-workflow-how-devs-work)
11. [What NOT to Animate](#11-what-not-to-animate)
12. [Definition of Done Checklist](#12-definition-of-done-checklist)

---

## 1. Big Picture Flow

Influora is a **Vite + React SPA**, not Next.js. The FrontendDesign kit assumes Next.js — the same motion libraries work; only **import/lazy pattern** differs (Vite `lazy()` instead of `next/dynamic`).

```
┌─────────────────────────────────────────────────────────────────────────┐
│  PHASE 0 — FOUNDATION                                                   │
│  Install missing deps (GSAP optional) · Create motion/ folder           │
│  Define motion tokens in globals.css · Document level per route         │
└───────────────────────────────┬─────────────────────────────────────────┘
                                ▼
┌─────────────────────────────────────────────────────────────────────────┐
│  PHASE 1 — MOTION PRIMITIVES (Framer only, no new 3D)                   │
│  FadeUp · Stagger · WordReveal · TiltCard · MagneticButton              │
│  Button press rules · useReducedMotion hook wrapper                     │
└───────────────────────────────┬─────────────────────────────────────────┘
                                ▼
┌─────────────────────────────────────────────────────────────────────────┐
│  PHASE 2 — POLISH EXISTING 3D (Auth)                                    │
│  LoginScene3D → kit performance rules · reduced-motion fallback         │
│  AuthLoginShell already lazy-loads canvas — keep that pattern          │
└───────────────────────────────┬─────────────────────────────────────────┘
                                ▼
┌─────────────────────────────────────────────────────────────────────────┐
│  PHASE 3 — LEVEL 1 ON ALL DASHBOARD PAGES                               │
│  Wrap card grids · stats · lists · empty states                         │
│  Brand: dashboard, campaigns, discover, wallet, settings                  │
│  Creator: deals, wallet, profile, portfolio editor                        │
└───────────────────────────────┬─────────────────────────────────────────┘
                                ▼
┌─────────────────────────────────────────────────────────────────────────┐
│  PHASE 4 — LEVEL 2 ON KEY FLOWS                                         │
│  Onboarding step transitions · form success/error · campaign detail hero  │
│  Optional: GSAP pinned pipeline on dashboard (later)                    │
└───────────────────────────────┬─────────────────────────────────────────┘
                                ▼
┌─────────────────────────────────────────────────────────────────────────┐
│  PHASE 5 — LEVEL 3 CANVASES (max 2–3 for SaaS)                          │
│  Discover hero canvas · Public portfolio canvas                         │
│  Optional: marketing landing page canvas (future)                       │
└───────────────────────────────┬─────────────────────────────────────────┘
                                ▼
┌─────────────────────────────────────────────────────────────────────────┐
│  PHASE 6 — QA + SHIP                                                    │
│  Reduced motion test · mobile perf · no motion in chat input focus        │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 2. React Folder Flow (What to Create First)

Current structure stays. **Add** these paths in this order:

```
src/
├── components/
│   ├── motion/                    ← CREATE FIRST (Phase 1)
│   │   ├── FadeUp.tsx
│   │   ├── StaggerContainer.tsx   # + StaggerItem export
│   │   ├── WordReveal.tsx
│   │   ├── TiltCard.tsx
│   │   ├── MagneticButton.tsx
│   │   ├── CountUp.tsx            # wallet / dashboard stats
│   │   ├── MotionProvider.tsx     # optional: shared reduced-motion context
│   │   └── index.ts               # barrel export
│   │
│   ├── 3d/                        ← CREATE LATER (Phase 5)
│   │   ├── DiscoverCanvas.tsx     # /brand/discover hero
│   │   ├── PortfolioCanvas.tsx    # /@username public page
│   │   ├── CanvasFallback.tsx     # shared static gradient fallback
│   │   └── index.ts
│   │
│   ├── shared/                    ← ALREADY EXISTS — polish in Phase 2
│   │   ├── login-scene-3d.tsx
│   │   ├── auth-login-shell.tsx
│   │   └── aurora-background.tsx
│   │
│   ├── brand/                     ← WRAP existing components in Phase 3
│   └── creator/                   ← WRAP existing components in Phase 3
│
├── hooks/
│   ├── useReducedMotion.ts        ← CREATE in Phase 1 (or re-export framer)
│   └── useInViewOnce.ts           ← scroll trigger helper for FadeUp/CountUp
│
└── lib/
    └── motion-config.ts           ← durations, easings, stagger delays (constants)
```

**Rule:** Never import `@react-three/fiber` inside `components/ui/` or layout files.  
3D lives only in `components/3d/` and `components/shared/login-scene-3d.tsx`.

---

## 3. Motion Level Decision Tree

Use this **before touching any page**:

```
START: Which page am I editing?
│
├─ Is it Deal Room / Chat (/brand/chat, /creator/chat)?
│     └─ YES → LEVEL 1 ONLY (message stagger max). NO 3D. NO GSAP pin.
│
├─ Is it a form-heavy page (settings, campaign form, contract sign)?
│     └─ YES → LEVEL 1 (focus rings, error shake, success spring). NO 3D.
│
├─ Is it a card grid (campaigns, discover, deals list)?
│     └─ YES → LEVEL 1 (StaggerContainer + TiltCard)
│
├─ Is it onboarding or multi-step wizard?
│     └─ YES → LEVEL 2 (AnimatePresence step swap, sidebar progress motion)
│
├─ Is it dashboard / detail page with hero area?
│     └─ YES → LEVEL 1–2 (WordReveal title, FadeUp sections, CountUp stats)
│
├─ Is it Discover or Public Portfolio with dedicated hero band?
│     └─ YES → LEVEL 3 allowed (one R3F canvas per page, lazy loaded)
│
└─ Is it auth login/register?
      └─ YES → LEVEL 3 (already exists — polish only)
```

**Cap:** Maximum **3** full R3F canvases in entire Influora app:

| # | Canvas | Route | Status |
|---|--------|-------|--------|
| 1 | `LoginScene3D` | `/brand/login`, `/creator/login`, register | ✅ Exists |
| 2 | `DiscoverCanvas` | `/brand/discover` | ⬜ Planned |
| 3 | `PortfolioCanvas` | `/@username` | ⬜ Planned |

---

## 4. Component Build Order

Build and test **one component at a time** in this sequence. Each must work in isolation before page integration.

| Step | Component | Depends on | Test on |
|------|-----------|------------|---------|
| 1 | `motion-config.ts` | — | — |
| 2 | `useReducedMotion` / `useInViewOnce` | — | any page |
| 3 | `FadeUp` | hooks | dashboard section |
| 4 | `StaggerContainer` + `StaggerItem` | FadeUp | campaign list |
| 5 | `TiltCard` | — | creator card in discover |
| 6 | `WordReveal` | — | dashboard heading |
| 7 | `CountUp` | useInViewOnce | wallet balance |
| 8 | `MagneticButton` | — | auth CTA only (1–2 per page max) |
| 9 | Polish `LoginScene3D` | reduced motion | brand login |
| 10 | `CanvasFallback` | — | shared by all 3D |
| 11 | `DiscoverCanvas` | CanvasFallback | discover page |
| 12 | `PortfolioCanvas` | CanvasFallback | public portfolio |

**Do not start step 11 until steps 1–8 pass reduced-motion test.**

---

## 5. Page Integration Flow

For **each page**, follow this 6-step loop:

```
┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐
│ 1. AUDIT │ →  │ 2. LEVEL │ →  │ 3. MAP   │ →  │ 4. WRAP  │ →  │ 5. TEST  │ →  │ 6. MARK  │
│  page    │    │  assign  │    │  elements│    │  motion  │    │  a11y    │    │  done    │
└──────────┘    └──────────┘    └──────────┘    └──────────┘    └──────────┘    └──────────┘
```

### Step 1 — Audit (list DOM sections top to bottom)

Example: `/brand/campaigns`

| Section | Current component | Motion target |
|---------|-------------------|---------------|
| Page header | `campaigns-list.tsx` h1 | WordReveal |
| Filter bar | tabs + search | FadeUp delay 0 |
| Campaign grid | card map | StaggerContainer + TiltCard |
| Empty state | static text | FadeUp + subtle icon spring |

### Step 2 — Assign level → **Level 1** for campaigns

### Step 3 — Map wrappers (which JSX blocks get which component)

### Step 4 — Wrap (outermost → innermost: page → section → grid → card)

### Step 5 — Test: mobile, reduced motion, slow 3G, keyboard nav

### Step 6 — Update status table at bottom of this doc

---

## 6. Data & State Flow (No Backend Change)

Motion is **purely presentational**. It must not change API calls or Zustand logic.

```
┌─────────────────────────────────────────────────────────────┐
│  Page (e.g. brand-discover.tsx)                             │
│    └── Feature component (creator-discovery.tsx)              │
│          ├── useQuery / api.creators.search()  ← UNCHANGED  │
│          ├── useAuthStore / filters            ← UNCHANGED  │
│          └── RENDER:                                          │
│                StaggerContainer                               │
│                  └── TiltCard × N                             │
│                        └── existing CreatorCard content       │
└─────────────────────────────────────────────────────────────┘
```

**Rules:**

- Motion components receive **already-fetched data** as children — no fetching inside `FadeUp`.
- Loading skeletons: use existing `Skeleton` from `ui/` — animate opacity only, not layout shift.
- Error states: no shake unless user submitted invalid form (Rule Set D from kit).
- Chat messages: animate **new** messages only; do not re-animate full list on poll.

---

## 7. Auth vs App Shell vs Page Flow

Influora has **three UI zones**. Motion rules differ per zone:

```
                    ┌─────────────────────────────────────┐
                    │         ZONE A — AUTH               │
                    │  /brand/login, /creator/register    │
                    │  Full bleed · Aurora + 3D canvas    │
                    │  Level 3 · MagneticButton on CTA    │
                    │  Component: AuthLoginShell          │
                    └─────────────────────────────────────┘
                                      │
                              user logs in
                                      ▼
                    ┌─────────────────────────────────────┐
                    │      ZONE B — ONBOARDING            │
                    │  /brand/onboarding (no sidebar)     │
                    │  Level 2 · step AnimatePresence     │
                    │  Component: OnboardingLayout        │
                    │  (already uses framer-motion)       │
                    └─────────────────────────────────────┘
                                      │
                           onboarding complete
                                      ▼
                    ┌─────────────────────────────────────┐
                    │      ZONE C — APP SHELL             │
                    │  BrandLayout / CreatorLayout        │
                    │  Sidebar + header — NO 3D canvas    │
                    │  Level 0–1 on nav only (hover)      │
                    │  Page content inside {children}     │
                    └─────────────────────────────────────┘
                                      │
                                      ▼
                    ┌─────────────────────────────────────┐
                    │      ZONE D — PAGE CONTENT          │
                    │  dashboard, campaigns, discover…    │
                    │  Level 1–3 per page map below       │
                    └─────────────────────────────────────┘
```

**Critical:** Do **not** put a global 3D canvas in `BrandLayout` or `CreatorLayout`.  
The FrontendDesign "OceanCanvas in layout.tsx" pattern **does not apply** to Influora SaaS shell.

---

## 8. 3D Canvas Lifecycle Flow

Every R3F canvas must follow this lifecycle (Vite/React):

```
User navigates to page with 3D
        │
        ▼
┌───────────────────┐
│ useReducedMotion? │
└─────────┬─────────┘
          │
    YES ──┴── NO
     │         │
     ▼         ▼
 CanvasFallback   React.lazy(() => import('…Canvas'))
 (static gradient)        │
                           ▼
                    Suspense boundary
                           │
                           ▼
                    <Canvas dpr={[1,1.5]} …>
                           │
                           ▼
                    PerformanceMonitor
                    onDecline → simplify scene
                           │
                           ▼
User leaves page → Canvas unmounts → WebGL context released
```

**Lazy import pattern (conceptual — already used in AuthLoginShell):**

- Page or shell holds `Suspense` + fallback
- 3D module loaded only when route mounts
- Desktop-only for heavy canvases (Discover hero) — mobile gets static hero image

---

## 9. Performance & Accessibility Gate

**Every PR that adds motion must pass this gate before merge:**

| Check | Pass criteria |
|-------|---------------|
| Reduced motion | OS setting ON → no canvas, no infinite loops, static UI |
| Mobile | No Level 3 canvas on chat/settings; Discover canvas optional hide < lg |
| DPR | All Canvas use `dpr={[1, 1.5]}` max |
| WebGL count | Max 1 active Canvas per viewport |
| Button feedback | All primary buttons have active scale 0.97 |
| No transition-all | Grep codebase — zero new `transition-all` on animated elements |
| Chat | Typing and sending messages remain instant (< 100ms perceived) |
| Focus | Keyboard tab order unchanged; no animation on `:focus-visible` |
| Lighthouse | LCP not regressed > 10% on pages touched |

---

## 10. Session Workflow (How Devs Work)

Each dev session should follow this order (from FrontendDesign `GENERIC-FLOW-MASTER.md`, adapted for React SPA):

```
1. Read this file — confirm current phase + next page
2. Read INFLUORA motion level for target route (Section 13 below)
3. If building primitive → build + Storybook-style test on one page
4. If integrating page → run 6-step Page Integration Flow (Section 5)
5. Run Performance Gate (Section 9)
6. Update page status table in Section 13
7. Do NOT start next 3D canvas until previous Level 1 pages are done
```

### Suggested session types

| Session | Goal | Output |
|---------|------|--------|
| **A** | Motion primitives | `components/motion/*` complete |
| **B** | Auth 3D polish | LoginScene3D meets kit rules |
| **C** | Brand list pages | campaigns + discover Level 1 |
| **D** | Dashboard + wallet | CountUp + FadeUp sections |
| **E** | Onboarding Level 2 | step transitions polished |
| **F** | Discover Level 3 | DiscoverCanvas integrated |
| **G** | Portfolio Level 3 | PortfolioCanvas on `/:handle` |

---

## 11. What NOT to Animate

These areas must stay **static or minimal** for usability:

| Area | Reason |
|------|--------|
| Deal Room message list (full re-stagger) | Disorienting during negotiation |
| Contract legal text | Users must read carefully |
| Wallet transaction table rows on every poll | Data feels unstable |
| Sidebar navigation | Wayfinding must be instant |
| Modal / dialog open on critical actions | Use fast 200ms fade only |
| Command bar (`command-bar.tsx`) | Power users need snappy search |
| Signature canvas (`contracts-and-deliverables`) | Drawing must not lag |
| Form inputs while typing | Only validate on blur/submit |

---

## 12. Definition of Done Checklist

Per **page**, all must be true:

- [ ] Motion level assigned and documented in Section 13
- [ ] Hero or page title uses WordReveal OR FadeUp (never bare static h1 alone on marketing-style sections)
- [ ] Card grids use StaggerContainer + StaggerItem
- [ ] Interactive cards use TiltCard where hover is appropriate
- [ ] Primary hero CTA uses MagneticButton (max 1–2 per page)
- [ ] Below-fold sections use FadeUp with `once: true`
- [ ] `useReducedMotion` fallback verified
- [ ] No new TypeScript errors; Vite HMR works
- [ ] No motion added to chat input or real-time feeds

---

## 13. Page Status Map (Influora Routes)

Update this table as work completes.

### Brand portal

| Route | Zone | Level | Key motion components | Status |
|-------|------|-------|----------------------|--------|
| `/brand/login` | Auth | 3 | LoginScene3D, Aurora, MagneticButton | 🔄 Polish |
| `/brand/register` | Auth | 3 | Same shell | 🔄 Polish |
| `/brand/forgot-password` | Auth | 2 | Aurora, FadeUp form | ⬜ |
| `/brand/onboarding` | Onboarding | 2 | AnimatePresence steps (partial ✅) | 🔄 |
| `/brand/dashboard` | App | 1–2 | FadeUp, CountUp, WordReveal | ⬜ |
| `/brand/campaigns` | App | 1 | Stagger + TiltCard | ⬜ |
| `/brand/campaigns/new` | App | 1 | Form stagger, step progress | ⬜ |
| `/brand/campaigns/:id` | App | 1–2 | WordReveal, FadeUp tabs | ⬜ |
| `/brand/discover` | App | **3** | DiscoverCanvas + TiltCard grid | ⬜ |
| `/brand/creators/:id` | App | 2 | Parallax header, FadeUp sections | ⬜ |
| `/brand/chat` | App | 1 | New message fade only | ⬜ |
| `/brand/wallet` | App | 1 | CountUp balances, FadeUp rows | ⬜ |
| `/brand/settings` | App | 1 | Form focus/success | ⬜ |
| `/brand/contracts` | App | 1 | FadeUp list | ⬜ |

### Creator portal

| Route | Zone | Level | Key motion components | Status |
|-------|------|-------|----------------------|--------|
| `/creator/login` | Auth | 3 | AuthLoginShell | 🔄 Polish |
| `/creator/register` | Auth | 3 | AuthLoginShell | 🔄 Polish |
| `/creator/onboarding` | Onboarding | 2 | Step transitions | ⬜ |
| `/creator/deals` | App | 1 | Stagger deal cards | ⬜ |
| `/creator/chat` | App | 1 | Message fade only | ⬜ |
| `/creator/wallet` | App | 1 | CountUp | ⬜ |
| `/creator/profile` | App | 1 | FadeUp sections | ⬜ |
| `/creator/portfolio` | App | 1–2 | Editor stagger | ⬜ |
| `/@username` | Public | **3** | PortfolioCanvas + lightbox | ⬜ |

**Legend:** ✅ Done · 🔄 In progress · ⬜ Not started

---

## 14. Dependency Flow (npm packages)

What to add and when:

| Package | Phase | Used for |
|---------|-------|----------|
| `framer-motion` | ✅ Already installed | All Level 1–2 |
| `@react-three/fiber` | ✅ Already installed | Level 3 |
| `@react-three/drei` | ✅ Already installed | Level 3 |
| `three` | ✅ Already installed | Level 3 |
| `gsap` + `@gsap/react` | Phase 4 (optional) | Pinned timeline, scroll-scrub video |
| `lenis` | Phase 4 (optional) | Smooth scroll — only if marketing landing page |
| `react-parallax-tilt` | Skip | TiltCard custom with Framer is enough |

**Do not install GSAP until Phase 4** — Framer covers 90% of SaaS needs.

---

## 15. Visual — End-to-End User Journey with Motion Zones

```
  [Visitor]
      │
      ▼
 /brand/login ────────────── LEVEL 3 (3D blobs + aurora)
      │
      ▼
 /brand/register ─────────── LEVEL 3
      │
      ▼
 /brand/onboarding ───────── LEVEL 2 (step sidebar animates)
      │
      ▼
 /brand/dashboard ────────── LEVEL 1–2 (stats count up)
      │
      ├──► /brand/campaigns ── LEVEL 1 (tilt cards)
      │
      ├──► /brand/discover ─── LEVEL 3 hero + LEVEL 1 grid
      │         │
      │         └──► /brand/creators/:id ── LEVEL 2
      │
      ├──► /brand/chat ─────── LEVEL 1 minimal (NO 3D)
      │
      └──► /brand/wallet ───── LEVEL 1 (count up)
```

---

## Related docs

| Doc | Role |
|-----|------|
| `docs/PROJECT-OVERVIEW.md` | Full project structure |
| `FrontendDesign/GENERIC-3D-MOTION-BLUEPRINT.md` | Original motion level spec |
| `FrontendDesign/GENERIC-MASTER-PROMPT.md` | Emil animation rules |
| `FrontendDesign/GENERIC-FLOW-MASTER.md` | Asset + build pipeline (marketing site) |

---

*This flow is React/Vite-specific. Start Phase 1 before any new 3D work.*
