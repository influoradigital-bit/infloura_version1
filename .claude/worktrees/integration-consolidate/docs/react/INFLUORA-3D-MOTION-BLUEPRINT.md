# Influora — 3D & Motion Blueprint
### Motion levels, canvas inventory, component library, performance rules

> SaaS-adapted from FrontendDesign generic blueprint. Assign motion level per route before coding.

---

## Philosophy

> Influora is a **productivity tool**, not a marketing brochure. Motion builds trust and guides attention — it never blocks deals, chat, or forms.

Every page has a **motion budget**:

| Level | Name | When |
|-------|------|------|
| **3 — Full 3D** | WebGL R3F canvas | Auth + max 2 hero pages (Discover, Portfolio) |
| **2 — Motion Rich** | Step transitions, parallax, WordReveal | Onboarding, detail pages |
| **1 — Motion Subtle** | Stagger, FadeUp, TiltCard | Grids, lists, dashboard |
| **0 — Minimal** | Instant | Chat, contracts, signatures |

**Rule:** If motion does not help the user complete a campaign, deal, or payment — remove it.

---

## Technology stack

| Tool | Purpose | Status |
|------|---------|--------|
| `framer-motion` | FadeUp, WordReveal, TiltCard, layout, AnimatePresence | ✅ Installed |
| `@react-three/fiber` | WebGL canvases | ✅ Installed |
| `three` | Geometry, materials | ✅ Installed |
| `@react-three/drei` | Float, MeshDistortMaterial, Html, PerformanceMonitor | ✅ Installed |
| `gsap` + `ScrollTrigger` | Pinned pipeline timeline (optional) | ⬜ Phase 7 |
| `lenis` | Smooth scroll (marketing landing only) | ⬜ Optional |

---

## Page motion map (Influora routes)

| Page | Route | Level | R3F Canvas | Key motion |
|------|-------|-------|------------|------------|
| Brand login | `/brand/login` | 3 | LoginScene3D | Aurora + blob parallax, MagneticButton |
| Brand register | `/brand/register` | 3 | LoginScene3D | Same shell |
| Creator login | `/creator/login` | 3 | LoginScene3D | Optional sky-tinted blobs |
| Brand onboarding | `/brand/onboarding` | 2 | — | Step AnimatePresence, progress bar |
| Brand dashboard | `/brand/dashboard` | 1–2 | — | CountUp stats, FadeUp sections, WordReveal |
| Brand campaigns | `/brand/campaigns` | 1 | — | StaggerContainer + TiltCard |
| Campaign detail | `/brand/campaigns/:id` | 1–2 | — | WordReveal title, tab FadeUp |
| Brand discover | `/brand/discover` | **3** | DiscoverCanvas | Network/orbit hero + TiltCard grid |
| Creator profile (brand view) | `/brand/creators/:id` | 2 | — | Parallax header, FadeUp stats |
| Brand chat (Deal Room) | `/brand/chat` | **0–1** | — | New message fade only — **NO 3D** |
| Brand wallet | `/brand/wallet` | 1 | — | CountUp balance, FadeUp ledger |
| Brand settings | `/brand/settings` | 1 | — | Form focus, success spring |
| Creator deals | `/creator/deals` | 1 | — | Stagger deal cards |
| Creator chat | `/creator/chat` | **0–1** | — | Minimal — **NO 3D** |
| Creator wallet | `/creator/wallet` | 1 | — | CountUp earnings |
| Creator portfolio edit | `/creator/portfolio` | 1–2 | — | Section stagger, preview FadeUp |
| Public portfolio | `/:handle` | **3** | PortfolioCanvas | Orbit stats, gallery lightbox |
| Static | `/terms`, `/privacy` | 0 | — | FadeUp only |

**Cap:** Maximum **3** full R3F hero canvases sitewide (see inventory below).

---

## 3D canvas inventory

| Canvas | File | Page | Status |
|--------|------|------|--------|
| Auth blobs | `src/components/shared/login-scene-3d.tsx` | brand/creator auth | ✅ Exists |
| Discover network | `src/components/3d/DiscoverCanvas.tsx` | `/brand/discover` | ⬜ |
| Portfolio orbit | `src/components/3d/PortfolioCanvas.tsx` | `/:handle` | ⬜ |
| Canvas fallback | `src/components/3d/CanvasFallback.tsx` | shared | ⬜ |

**Rejected for SaaS:** ExportGlobe, OceanCanvas in layout, ProductShapeViewer (use 2D creator cards instead).

---

## Motion component library

| Component | Path | Status | Used on |
|-----------|------|--------|---------|
| FadeUp | `src/components/motion/FadeUp.tsx` | ⬜ | All sections below fold |
| StaggerContainer | `src/components/motion/StaggerContainer.tsx` | ⬜ | Card grids |
| StaggerItem | `src/components/motion/StaggerContainer.tsx` | ⬜ | Grid children |
| WordReveal | `src/components/motion/WordReveal.tsx` | ⬜ | Page titles, hero copy |
| TiltCard | `src/components/motion/TiltCard.tsx` | ⬜ | Campaign, creator, deal cards |
| MagneticButton | `src/components/motion/MagneticButton.tsx` | ⬜ | Auth CTAs only (1–2/page) |
| CountUp | `src/components/motion/CountUp.tsx` | ⬜ | Wallet, dashboard metrics |
| MotionProvider | `src/components/motion/MotionProvider.tsx` | ⬜ | Optional shared context |
| CanvasFallback | `src/components/3d/CanvasFallback.tsx` | ⬜ | All R3F reduced-motion |
| AuroraBackground | `src/components/shared/aurora-background.tsx` | ✅ | Auth pages |
| LoginScene3D | `src/components/shared/login-scene-3d.tsx` | ✅ | Auth pages |

---

## DiscoverCanvas — spec

```
Purpose:     Visualize creator ecosystem — platforms orbit a central brand hub
Page:        /brand/discover (hero band, desktop lg+ only)
Height:      280–360px in hero strip above search/filters
Background:  transparent — page bg #f0ebfa shows through

Scene:
  - Central icosahedron or soft sphere — PRIMARY_COLOR #9b8cf2
  - 4–6 smaller spheres labeled INSTAGRAM, YOUTUBE, etc. (colors from icon-theme)
  - Orbit via useFrame — slow Y rotation ~0.08 rad/s
  - Mouse parallax: camera offset ±0.3 on pointer move (subtle)
  - Float from drei on satellite spheres
  - Particle count ≤ 80 (not 1500 — this is SaaS, not export globe)

Mobile:      Hide canvas — show static SVG or gradient mesh only
Reduced:     CanvasFallback with lilac gradient blobs (match aurora)
Lazy:        React.lazy in creator-discovery.tsx
dpr:         [1, 1.5]
gl:          { antialias: false, alpha: true }
PerformanceMonitor: onDecline → hide particles, keep central shape only
```

---

## PortfolioCanvas — spec

```
Purpose:     Public creator portfolio — stats orbit avatar
Page:        /:handle (creator-portfolio-public.tsx)
Height:      40vh max in hero, min 240px

Scene:
  - Center: flat circle placeholder for avatar (texture from creator.avatarUrl if loaded)
  - 3 orbiting rings: followers, engagement, collabs — Html labels from drei
  - Colors: #7ec8e8 + #9b8cf2
  - Auto-rotate very slow

Mobile:      Static hero image from public/images/portfolio/ fallback
Reduced:     Same static image
See also:    docs/CREATOR-PORTFOLIO-PAGE.md
```

---

## LoginScene3D — polish checklist (existing)

Current gaps vs blueprint:

| Rule | Current | Target |
|------|---------|--------|
| dpr | `[1, 1.75]` | `[1, 1.5]` |
| antialias | `true` | `false` |
| useReducedMotion | missing | CanvasFallback |
| PerformanceMonitor | missing | add onDecline |
| lazy load | ✅ via AuthLoginShell | keep |

---

## Performance rules (non-negotiable)

1. `dpr={[1, 1.5]}` on every Canvas
2. `gl={{ antialias: false, alpha: true }}`
3. `React.lazy()` + `Suspense` for all canvas imports (Vite)
4. Max ~200 particles per hero; Discover uses ≤ 80
5. Framer scroll animations: `once: true` via `useInViewOnce`
6. `useReducedMotion()` → static gradient or PNG fallback
7. Max **1** active WebGL context per viewport
8. Unmount canvas on route leave
9. No R3F inside chat, settings forms, or data tables

---

## Chat & deal room rules

```
ALLOWED:
  - opacity fade-in for NEW messages only (150ms ease-out)
  - typing indicator scale spring
  - panel slide for mobile deal list (200ms)

FORBIDDEN:
  - Stagger entire message history on load
  - 3D canvas in chat layout
  - GSAP scroll pin in chat column
  - MagneticButton on send button
  - animate-pulse on message bubbles
```

---

## Definition of done (per page)

- [ ] Motion level assigned in PLAN-OF-ACTION
- [ ] Hero/title: WordReveal OR FadeUp — not bare static h1 on marketing-style bands
- [ ] Card grids: StaggerContainer + StaggerItem
- [ ] Interactive cards: TiltCard where hover makes sense
- [ ] Auth CTAs: MagneticButton (max 2 per page)
- [ ] Below-fold: FadeUp with once
- [ ] useReducedMotion fallback on all canvases
- [ ] No TypeScript errors; Vite HMR works
- [ ] Chat pages: verified no heavy motion

---

*Blueprint v1.0 — Influora SaaS*
