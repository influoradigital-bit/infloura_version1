# Influora React — Master Flow & Session Guide
### Read this first. Every motion/3D session.

**Version:** 1.0  
**Stack:** React 19 · Vite 6 · TypeScript · Tailwind 4 · Framer Motion · R3F

---

## Quick start checklist

```
□ 1. Read INFLUORA-PROJECT-CONFIG.md — colors, routes, caps
□ 2. Read INFLUORA-MASTER-PROMPT.md — animation rules
□ 3. Read INFLUORA-PLAN-OF-ACTION.md — pick next unchecked task
□ 4. If 3D → read INFLUORA-3D-MOTION-BLUEPRINT.md
□ 5. If Cursor session → copy prompt from prompts/INFLUORA-*-PROMPTS.md
□ 6. Build → Self-check → Update status tables
```

---

## How all files connect

```
┌─────────────────────────────────────────────────────────────────────┐
│  INFLUORA-PROJECT-CONFIG.md     ← colors, routes, canvas cap        │
└───────────────────────┬─────────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────────────────┐
│  INFLUORA-MASTER-PROMPT.md      ← AI persona + Emil rules + R3F     │
└───────────────────────┬─────────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────────────────┐
│  INFLUORA-PLAN-OF-ACTION.md     ← page tasks + audit checklist      │
└───────────────────────┬─────────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────────────────┐
│  INFLUORA-3D-MOTION-BLUEPRINT.md ← levels, components, performance  │
└───────────────────────┬─────────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────────────────┐
│  REACT-MOTION-FLOW.md           ← folder order, zones, integration  │
└───────────────────────┬─────────────────────────────────────────────┘
                        │
          ┌─────────────┴─────────────┐
          ▼                           ▼
┌──────────────────────┐    ┌──────────────────────────────┐
│  prompts/            │    │  src/                        │
│  Motion + Page       │    │  components/motion/          │
│  Cursor prompts      │    │  components/3d/              │
│  Image/Video Gemini  │    │  pages + brand/creator UI    │
└──────────────────────┘    └──────────────────────────────┘
```

---

## Two pipelines

### Pipeline A — React motion code (Claude / Cursor)

| Step | Action | Doc |
|------|--------|-----|
| 1 | Build motion primitives | `prompts/INFLUORA-MOTION-COMPONENT-PROMPTS.md` |
| 2 | Integrate per page | `prompts/INFLUORA-PAGE-SESSION-PROMPTS.md` |
| 3 | Polish 3D canvases | `INFLUORA-3D-MOTION-BLUEPRINT.md` |
| 4 | Self-check | `INFLUORA-MASTER-PROMPT.md` § Self-Check |

**Rule:** Cursor writes React. Motion docs are the contract.

### Pipeline B — Visual assets (Gemini, optional)

| Step | Action | Doc |
|------|--------|-----|
| 1 | Style rules | `prompts/INFLUORA-BRAND-STYLE-RULES.md` |
| 2 | Image prompts | `prompts/INFLUORA-IMAGE-PROMPT-TEMPLATE.md` |
| 3 | Video prompts | `prompts/INFLUORA-VIDEO-PROMPT-TEMPLATE.md` |
| 4 | Save to `public/images/` | Wire in components as static fallbacks |

**Rule:** Gemini generates pixels. React references paths — no runtime generation.

---

## Motion level map (Influora SaaS)

| Level | Name | Typical routes | Stack |
|-------|------|----------------|-------|
| **3** | Full 3D | Auth, Discover hero, Public portfolio | R3F + lazy Canvas |
| **2** | Motion rich | Onboarding, campaign detail, creator profile | Framer AnimatePresence, parallax |
| **1** | Motion subtle | Campaigns grid, wallet, settings, deals list | FadeUp, Stagger, TiltCard |
| **0** | Minimal | Chat, contract text, signature pad | Instant UI, no stagger on feed |

**Cap:** Max **3** Level-3 canvases (see PROJECT-CONFIG).

---

## Build phases (default order)

```
PHASE 0 — Config + motion-config.ts constants
PHASE 1 — components/motion/* (Framer primitives)
PHASE 2 — Polish login-scene-3d + aurora (reduced motion, dpr)
PHASE 3 — Level 1 on brand: campaigns, discover grid, wallet, dashboard
PHASE 4 — Level 1 on creator: deals, wallet, profile
PHASE 5 — Level 2: onboarding, campaign detail, forms
PHASE 6 — Level 3: DiscoverCanvas, PortfolioCanvas
PHASE 7 — Optional GSAP timeline on dashboard pipeline
PHASE 8 — QA: reduced motion, mobile, Lighthouse
```

Do **not** skip Phase 1 to start Phase 6.

---

## UI zones (where motion applies)

```
ZONE A — Auth (/brand/login, /creator/login)     → Level 3
ZONE B — Onboarding (/brand/onboarding)          → Level 2
ZONE C — App shell (BrandLayout sidebar)         → Level 0–1 hover only, NO 3D
ZONE D — Page content (dashboard, campaigns…)    → Level 1–3 per blueprint
ZONE E — Chat (/brand/chat, /creator/chat)       → Level 1 max, NO 3D
```

**Never** add OceanCanvas-style global 3D to `BrandLayout` or `CreatorLayout`.

---

## Session templates

### Session A — Motion primitives
1. Create `src/lib/motion-config.ts`
2. Run Cursor prompts MC-01 through MC-06
3. Verify on one test section in dashboard

### Session B — Auth 3D polish
1. `@login-scene-3d.tsx` — dpr, PerformanceMonitor, reduced motion
2. `@auth-login-shell.tsx` — verify lazy + fallback
3. Self-check R3F section

### Session C — Brand list pages
1. `@campaigns-list.tsx` — Stagger + TiltCard
2. `@creator-discovery.tsx` — TiltCard on creator cards
3. Update PLAN-OF-ACTION status

### Session D — Discover Level 3
1. Create `DiscoverCanvas.tsx`
2. Integrate in `creator-discovery.tsx` hero band (desktop lg+)
3. Mobile: static gradient fallback

### Session E — Public portfolio
1. `@creator-portfolio-public.tsx`
2. PortfolioCanvas + gallery lightbox motion
3. See `docs/CREATOR-PORTFOLIO-PAGE.md`

---

## Vite vs Next.js notes

| Next.js (generic kit) | Influora (Vite) |
|----------------------|-----------------|
| `next/dynamic(..., { ssr: false })` | `React.lazy()` + `Suspense` |
| `app/layout.tsx` | No layout file — use `BrandLayout` children only |
| `usePathname()` | `useLocation()` from react-router-dom |
| `public/` | Same — `public/images/` |

---

## Related docs

| Doc | Path |
|-----|------|
| React integration flow | `docs/REACT-MOTION-FLOW.md` |
| Project overview | `docs/PROJECT-OVERVIEW.md` |
| UI bug/fix prompts | `docs/CURSOR-AI-PROMPTS.md` |
| Generic source kit | `Downloads/FrontendDesign/` |

---

*"Every session starts cold. This file is the warm-up."*
