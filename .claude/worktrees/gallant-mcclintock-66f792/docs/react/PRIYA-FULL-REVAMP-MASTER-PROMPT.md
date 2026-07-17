# Priya — Full UI Revamp Master Prompt
### All skills · One session prompt · API calls stay the same

> **Goal:** Revamp every Influora page with Emil motion, Framer, and 3D skills — **without changing backend integration.**  
> **Companion file:** [`PRIYA-ALL-PAGES-AND-ELEMENTS.md`](./PRIYA-ALL-PAGES-AND-ELEMENTS.md) — every page, element, and API to preserve.

---

## How to use this file

1. Open **one page** from the companion file (route + file path).
2. Copy the **Master session prompt** below into Cursor Composer (`Ctrl+I`).
3. Attach files listed for that page with `@filename`.
4. Name the skill(s) from the catalog for that page.
5. Run self-check at the bottom.
6. Tick the page in `INFLUORA-PLAN-OF-ACTION.md`.

**Install first:** [`PRIYA-INSTALL-AND-RUN-SKILLS.md`](./PRIYA-INSTALL-AND-RUN-SKILLS.md)  
**Test page:** http://localhost:3000/dev/motion-skills (dev only)

---

## The golden rule — API & data unchanged

```
✅ ALLOWED during revamp
- JSX structure wrappers (FadeUp, StaggerContainer, TiltCard)
- CSS / Tailwind / className polish
- Framer Motion enter/exit/hover
- Lazy 3D canvas gates (Discover, Portfolio, Auth)
- Button press (Emil scale 0.97)
- Import from @/components/motion and @/components/3d

❌ FORBIDDEN during revamp
- Editing src/lib/api.ts method signatures or endpoints
- Renaming or removing api.* call sites
- Moving fetch logic into motion components
- Changing Zustand store shape (useAuthStore, useCampaignStore, etc.)
- Changing route paths in App.tsx (unless product asks)
- Replacing mock/live branching (isApiLive, VITE_API_MODE)
- New API fields or request payloads
- Refactoring business logic “while you’re here”
```

**If motion needs data:** pass existing props/state into motion wrappers — never fetch inside `FadeUp` or `TiltCard`.

---

## Master session prompt (copy every time)

Paste into Cursor Composer and fill in `[PAGE]` and `[FILES]`:

```
You are the Influora Motion Engineer revamping UI only.

READ FIRST (do not skip):
@docs/react/PRIYA-FULL-REVAMP-MASTER-PROMPT.md
@docs/react/PRIYA-ALL-PAGES-AND-ELEMENTS.md
@docs/react/INFLUORA-3D-MOTION-BLUEPRINT.md
@docs/react/INFLUORA-MASTER-PROMPT.md

TARGET PAGE: [PAGE]
FILES TO EDIT: [FILES]

RULES:
1. UI/UX + motion revamp ONLY — zero API changes
2. Keep every api.* call, useEffect fetch, store usage identical
3. Reuse @/components/motion before writing raw framer-motion
4. Motion level from PRIYA-ALL-PAGES-AND-ELEMENTS.md for this route
5. Emil: active:scale-[0.97), no transition-all, ease-out, reduced motion
6. Chat/deals pages: Level 0-1 — no 3D, no message stagger
7. Max 3 R3F canvases sitewide — only auth, discover hero, public portfolio
8. Minimal diff — wrap existing markup, do not rewrite page architecture

SKILLS TO APPLY: [list from catalog below]

DELIVER:
- List files changed
- Motion level used
- Confirm: no api.ts / store / route changes
- Self-check from master prompt § Self-check

After done: suggest checkbox to tick in INFLUORA-PLAN-OF-ACTION.md
```

---

## All Cursor skills (17 installed)

Skills live in `.cursor/skills/`. Mention by name in Composer.

| # | Skill folder | When to use |
|---|--------------|-------------|
| 1 | `influora-emil-motion` | Buttons, hovers, transitions, form focus |
| 2 | `influora-framer-motion` | General Framer — import from `@/components/motion` |
| 3 | `influora-framer-scroll-reveal` | FadeUp, whileInView, sections below fold |
| 4 | `influora-framer-stagger` | Card grids — campaigns, deals, discover results |
| 5 | `influora-framer-animate-presence` | Onboarding steps, tab swaps, form success |
| 6 | `influora-framer-layout` | Portfolio gallery lightbox layoutId only |
| 7 | `influora-r3f-canvas` | Any Canvas — performance + lazy rules |
| 8 | `influora-threejs-performance` | dpr, PerformanceMonitor, particle caps |
| 9 | `influora-threejs-materials` | Blob colors, Lilac Mist 3D palette |
| 10 | `influora-threejs-scenes` | Login / Discover / Portfolio inventory |
| 11 | `influora-vite-react` | React.lazy, useLocation — not Next.js |
| 12 | `influora-motion-audit` | AUDIT page before animating |
| 13 | `influora-shadcn-ui` | Button, Input, Card, Dialog from `@/components/ui` |
| 14 | `influora-motion-components` | Skill 32 — motion library usage |
| 15 | `influora-motion-test` | Verify after install / component work |
| 16 | `influora-gsap-scroll` | ScrollTrigger pin — dashboard pipeline only |
| 17 | `influora-lenis-scroll` | Smooth scroll — static/marketing pages only |

**Always-on rule:** `.cursor/rules/influora-react-motion.mdc` (loads in `src/` edits)

---

## npm packages (Layer B)

| Package | Skills | Status |
|---------|--------|--------|
| `framer-motion` | 2–6, 14 | ✅ Installed |
| `three`, `@react-three/fiber`, `@react-three/drei` | 7–10 | ✅ Installed |
| `gsap`, `@gsap/react` | 16 | ✅ Installed |
| `lenis` | 17 | ✅ Installed |

---

## Motion component library (import these)

```tsx
import {
  FadeUp,
  StaggerContainer,
  StaggerItem,
  WordReveal,
  TiltCard,
  CountUp,
  MagneticButton,
} from '@/components/motion'

import {
  CanvasFallback,
  DiscoverCanvasGate,
  PortfolioCanvasGate,
} from '@/components/3d'

import { DURATION_NORMAL, VIEWPORT_ONCE } from '@/lib/motion-config'
import { useInViewOnce } from '@/hooks/useInViewOnce'
import { useSmoothScroll } from '@/hooks/useSmoothScroll'      // static pages only
import { useScrollPin } from '@/hooks/useScrollPin'            // GSAP pin only
```

| Component | Use on |
|-----------|--------|
| `FadeUp` | Section blocks, below fold |
| `StaggerContainer` + `StaggerItem` | Card grids |
| `WordReveal` | Page titles, hero headings |
| `TiltCard` | Discover, campaigns, deal cards |
| `CountUp` | Wallet, dashboard numbers |
| `MagneticButton` | **Auth hero CTAs only** |
| `LoginScene3DGate` | Auth (via AuthLoginShell) |
| `DiscoverCanvasGate` | Discover desktop hero |
| `PortfolioCanvasGate` | Public `/@handle` desktop hero |

---

## Motion levels (quick reference)

| Level | Name | Pages |
|-------|------|-------|
| **3** | Full 3D + rich motion | Brand/creator auth, discover, public portfolio |
| **2** | Step transitions, WordReveal | Onboarding, campaign detail, creator profile |
| **1** | Stagger, FadeUp, TiltCard | Campaigns, deals, wallet, dashboard |
| **0–1** | Minimal / instant | Chat, contracts mid-sign, forms sending |

Full map: `INFLUORA-3D-MOTION-BLUEPRINT.md`

---

## Emil rules (Skill 1 — always)

- Button `:active` → scale 0.97, ~160ms ease-out
- Never `transition-all` — list exact properties
- Never `ease-in` on UI
- UI animations ≤ 300ms (ambient aurora/3D excepted)
- Entry ~250ms, exit ~150ms
- Stagger 30–80ms — one layer per grid
- Hover only `@media (hover: hover) and (pointer: fine)`
- `useReducedMotion()` on every animated block
- Forms: focus ring `ring-primary/30`, error shake, success spring

---

## Page prompt IDs (detailed steps)

For copy-paste blocks per page, use:

`docs/react/prompts/INFLUORA-PAGE-SESSION-PROMPTS.md`

| ID | Page area |
|----|-----------|
| PS-01 | Brand auth |
| PS-02 | Campaigns list |
| PS-03 | Discover |
| PS-04 | Dashboard |
| PS-05 | Brand wallet |
| PS-06 | Brand onboarding |
| PS-07 | Brand chat (restraint) |
| PS-08 | Creator deals |
| PS-09 | Creator wallet + profile |
| PS-10 | Public portfolio |
| PS-11 | Global buttons |
| PS-12 | Creator auth |

Component build prompts: `prompts/INFLUORA-MOTION-COMPONENT-PROMPTS.md` (MC-00 → MC-11) — **already done.**

---

## Recommended revamp order (8 weeks)

| Week | Pages | Skills | Prompts |
|------|-------|--------|---------|
| **0** | Foundation | 14, 15 | MC done ✅ |
| **1** | Global buttons + auth | 1, 2, 7, 13 | PS-11, PS-01, PS-12 |
| **2** | Onboarding (brand + creator) | 5, 1, 13 | PS-06 |
| **3** | Dashboard, campaigns, wallet | 3, 4, 14, 1 | PS-04, PS-02, PS-05 |
| **4** | Discover + portfolio 3D | 7–10, 3, 4 | PS-03, PS-10 |
| **5** | Creator deals, wallet, profile | 3, 4, 14 | PS-08, PS-09 |
| **6** | Campaign detail, new/edit, settings | 3, 5, 13 | See companion file |
| **7** | Chat (minimal), contracts, messages | 12 (audit) | PS-07 |
| **8** | Layouts polish, static pages, GSAP optional | 17, 16 | Lenis on /terms |

One page per Composer session. Never revamp chat + discover in the same session.

---

## Self-check (run after every page)

```
API & DATA
□ No changes to src/lib/api.ts
□ All api.* calls preserved (same method, same args)
□ useEffect / React Query / store logic untouched
□ Mock vs live (isApiLive) still works

EMIL
□ scale(0.97) on buttons touched
□ No transition-all added
□ No ease-in on UI
□ MagneticButton only on auth hero
□ prefers-reduced-motion handled

MOTION
□ Reused @/components/motion (not duplicated FadeUp)
□ AnimatePresence only on steps/conditional UI
□ No full-list stagger on chat or wallet refresh
□ viewport once:true on scroll reveals

3D (if page has canvas)
□ dpr [1,1.5], antialias false, lazy+Suspense
□ PerformanceMonitor present
□ CanvasFallback when reduced motion

SAAS
□ Chat/forms still feel instant
□ No Canvas in BrandLayout/CreatorLayout
□ Route still loads in browser without console errors
```

---

## Daily checklist for Priya

```
□ npm run dev → http://localhost:3000
□ Open PRIYA-ALL-PAGES-AND-ELEMENTS.md → pick ONE page
□ Note motion level + API calls for that page
□ Composer: paste Master session prompt + attach @files
□ Name skills from catalog (e.g. influora-framer-stagger)
□ Accept diff → test page in browser (F12 console clean)
□ Test reduced motion (Windows Animation effects OFF)
□ Tick INFLUORA-PLAN-OF-ACTION.md
```

---

## Related docs

| File | Purpose |
|------|---------|
| [`PRIYA-ALL-PAGES-AND-ELEMENTS.md`](./PRIYA-ALL-PAGES-AND-ELEMENTS.md) | **Every page + elements + APIs** |
| [`PRIYA-INSTALL-AND-RUN-SKILLS.md`](./PRIYA-INSTALL-AND-RUN-SKILLS.md) | Install & run |
| [`INFLUORA-SKILLS-GUIDE-FOR-PRIYA.md`](./INFLUORA-SKILLS-GUIDE-FOR-PRIYA.md) | Skills explained |
| [`INFLUORA-PLAN-OF-ACTION.md`](./INFLUORA-PLAN-OF-ACTION.md) | Checkboxes |
| [`../frontend/UI-ELEMENTS-AND-MODELS.md`](../frontend/UI-ELEMENTS-AND-MODELS.md) | Full UI inventory |
| [`../frontend/BRAND-PAGES-AUDIT.md`](../frontend/BRAND-PAGES-AUDIT.md) | Brand detail |
| [`../frontend/CREATOR-PAGES-AUDIT.md`](../frontend/CREATOR-PAGES-AUDIT.md) | Creator detail |

---

*Priya revamp master v1.0 — UI only, APIs frozen*
