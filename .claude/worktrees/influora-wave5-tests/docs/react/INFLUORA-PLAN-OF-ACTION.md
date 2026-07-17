# Influora — Plan of Action (React Motion)
### Every page. Every element. Check boxes as you ship.

> Reference: `INFLUORA-3D-MOTION-BLUEPRINT.md` · Prompts: `prompts/INFLUORA-PAGE-SESSION-PROMPTS.md`

---

## Build order (default)

| # | Task | Area | Priority |
|---|------|------|----------|
| 1 | Create `motion-config.ts` + motion folder | Foundation | 🔴 NOW |
| 2 | FadeUp, Stagger, TiltCard, WordReveal, CountUp | Primitives | 🔴 NOW |
| 3 | `scale(0.97)` on all primary buttons | Global | 🔴 NOW |
| 4 | Polish LoginScene3D (dpr, reduced motion) | Auth | 🔴 NOW |
| 5 | Campaign list — Stagger + TiltCard | Brand | 🟠 NEXT |
| 6 | Discover grid — TiltCard | Brand | 🟠 NEXT |
| 7 | Dashboard — CountUp + FadeUp | Brand | 🟠 NEXT |
| 8 | Onboarding — AnimatePresence polish | Brand | 🟠 NEXT |
| 9 | Wallet — CountUp balances | Brand + Creator | 🟡 SOON |
| 10 | DiscoverCanvas Level 3 | Brand | 🟡 SOON |
| 11 | Creator deals — Stagger cards | Creator | 🟡 SOON |
| 12 | PortfolioCanvas + public page | Creator | 🟢 LATER |
| 13 | Optional GSAP pipeline pin | Dashboard | 🟢 LATER |

---

## Phase 0 — Foundation

**Status:** ✅

- [x] `src/lib/motion-config.ts` — durations, easings, stagger delays
- [x] `src/hooks/useInViewOnce.ts`
- [x] `src/components/motion/index.ts` barrel
- [ ] Update `INFLUORA-PROJECT-CONFIG.md` build status

---

## Phase 1 — Motion primitives

**Status:** ✅

Use prompts `MC-01` through `MC-07` in `prompts/INFLUORA-MOTION-COMPONENT-PROMPTS.md`

- [x] FadeUp
- [x] StaggerContainer + StaggerItem
- [x] WordReveal
- [x] TiltCard
- [x] CountUp
- [x] MagneticButton
- [x] CanvasFallback (in `3d/`)

---

## Page 1 — Brand auth (`/brand/login`, `/brand/register`, `/brand/forgot-password`)

**Level:** 3 · **Status:** ✅ (login complete · register/forgot pending)

### Tasks
- [x] LoginScene3D: dpr `[1, 1.5]`, antialias false
- [x] LoginScene3D: useReducedMotion → CanvasFallback
- [x] LoginScene3D: PerformanceMonitor
- [x] AuthLoginShell: WordReveal hero + motion-config entry
- [x] Brand login: MagneticButton on primary CTA
- [x] Form fields: Rule Set D focus rings
- [x] Button active scale(0.97) on submit
- [x] Error state: AnimatePresence + shake

### 3D
- Keep existing blob scene — no layout canvas

---

## Page 2 — Creator auth (`/creator/login`, `/creator/register`)

**Level:** 3 · **Status:** 🔄

- [ ] Reuse AuthLoginShell
- [ ] Optional: shift blob colors toward `#7ec8e8` (sky accent)
- [ ] Same reduced-motion + performance rules as brand auth

---

## Page 3 — Brand onboarding (`/brand/onboarding`)

**Level:** 2 · **Status:** 🔄

- [ ] Step content: AnimatePresence `mode="wait"` (extend existing layout)
- [ ] Sidebar step indicators: spring on active step
- [ ] Form stagger 60ms per field group
- [ ] Success on step 3: checkmark spring
- [ ] NO 3D canvas

---

## Page 4 — Brand dashboard (`/brand/dashboard`)

**Level:** 1–2 · **Status:** ⬜

**Component:** `src/components/brand/dashboard/dashboard-page.tsx`

- [ ] Page title: WordReveal
- [ ] Stat cards: CountUp on inView
- [ ] Pipeline section: FadeUp stagger
- [ ] Quick links: TiltCard or hover lift
- [ ] SLA alerts: FadeUp — no pulse unless critical
- [ ] NO 3D canvas in dashboard

---

## Page 5 — Brand campaigns list (`/brand/campaigns`)

**Level:** 1 · **Status:** ⬜

**Component:** `src/components/brand/campaigns/campaigns-list.tsx`

- [ ] Heading: WordReveal
- [ ] Filter bar: FadeUp
- [ ] Campaign cards: StaggerContainer + TiltCard
- [ ] Empty state: FadeUp + subtle icon spring
- [ ] NO 3D

---

## Page 6 — Campaign form / detail (`/brand/campaigns/new`, `/:id`, `/:id/edit`)

**Level:** 1–2 · **Status:** ⬜

- [ ] Campaign title on detail: WordReveal
- [ ] Tabs content: FadeUp on switch (not AnimatePresence spam)
- [ ] Form steps: stagger fields
- [ ] Save success toast — use existing Sonner
- [ ] NO 3D

---

## Page 7 — Brand discover (`/brand/discover`)

**Level:** 3 · **Status:** ⬜

**Component:** `src/components/brand/discover/creator-discovery.tsx`

- [ ] Hero band (desktop lg+): DiscoverCanvas lazy + Suspense
- [ ] Mobile: static gradient hero
- [ ] Creator cards: StaggerContainer + TiltCard
- [ ] Filters: FadeUp
- [ ] Reduced motion: no canvas

---

## Page 8 — Brand creator profile (`/brand/creators/:id`)

**Level:** 2 · **Status:** ⬜

- [ ] Header image/stats: subtle parallax (Framer useScroll, not GSAP yet)
- [ ] Sections: FadeUp
- [ ] CTA buttons: primary press feedback
- [ ] NO 3D

---

## Page 9 — Brand chat / Deal Room (`/brand/chat`)

**Level:** 0–1 · **Status:** ⬜

**Component:** `src/pages/brand-chat.tsx`

- [ ] New messages: opacity fade 150ms only
- [ ] Deal list mobile sheet: slide 200ms
- [ ] **FORBIDDEN:** stagger full history, 3D, MagneticButton on send
- [ ] Typing indicator: optional subtle spring

---

## Page 10 — Brand wallet (`/brand/wallet`)

**Level:** 1 · **Status:** ⬜

- [ ] Balance hero: CountUp
- [ ] Transaction rows: FadeUp once on mount — not on poll refresh
- [ ] Escrow card: TiltCard optional
- [ ] NO 3D

---

## Page 11 — Brand settings (`/brand/settings`)

**Level:** 1 · **Status:** ⬜

- [ ] Section FadeUp
- [ ] Form Rule Set D
- [ ] NO 3D

---

## Page 12 — Creator deals (`/creator/deals`)

**Level:** 1 · **Status:** ⬜

- [ ] Deal/opportunity cards: Stagger + TiltCard
- [ ] Tab switch (new / in progress): instant or 150ms fade
- [ ] NO 3D

---

## Page 13 — Creator chat (`/creator/chat`)

**Level:** 0–1 · **Status:** ⬜

- Same rules as brand chat — minimal motion

---

## Page 14 — Creator wallet (`/creator/wallet`)

**Level:** 1 · **Status:** ⬜

- [ ] Earnings CountUp
- [ ] Transaction FadeUp on mount
- [ ] NO 3D

---

## Page 15 — Creator portfolio editor (`/creator/portfolio`)

**Level:** 1–2 · **Status:** ⬜

- [ ] Editor sections: FadeUp stagger
- [ ] Live preview panel: smooth layout transition
- [ ] See `docs/CREATOR-PORTFOLIO-PAGE.md`

---

## Page 16 — Public portfolio (`/:handle`)

**Level:** 3 · **Status:** ⬜

**Component:** `src/pages/creator-portfolio-public.tsx`

- [ ] PortfolioCanvas in hero (desktop)
- [ ] Mobile: static hero image from `public/images/portfolio/`
- [ ] Gallery: layoutId lightbox (Framer)
- [ ] Reduced motion: static hero PNG

---

## Element audit template

### Buttons
- [ ] scale(0.97) active on all primary/secondary touched
- [ ] No transition-all
- [ ] MagneticButton auth only

### Backgrounds
- [ ] Lilac Mist — no pure black pages
- [ ] Card white on `#f0ebfa` background

### 3D / Canvas
- [ ] useReducedMotion on every canvas
- [ ] Max 1 WebGL per viewport
- [ ] No canvas in layout or chat

### Chat
- [ ] No full-history stagger
- [ ] Send remains instant

### Forms
- [ ] Focus ring primary/30
- [ ] Error shake on validate
- [ ] Success state on onboarding complete

---

## Status legend

| Symbol | Meaning |
|--------|---------|
| ⬜ | Not started |
| 🔄 | In progress |
| ✅ | Done |

Update `INFLUORA-PROJECT-CONFIG.md` build status when a phase completes.

---

*Plan v1.0 — update checkboxes each session*
