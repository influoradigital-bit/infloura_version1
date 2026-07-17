# Influora — How Priya Gets All Motion & UI Skills (React + Vite)

**For:** Priya Ingle · **Project:** Influora (Vite 6 + React 19 + TypeScript)  
**Source:** `FrontendDesign/GENERIC-SKILLS-REFERENCE.md` (32-skill catalog)  
**No code in this doc** — only *what* the skills are, *where* they live, and *how* to install them.

---

## 1. What “skills” means (three layers)

The FrontendDesign kit uses **“skills”** in three different ways. Priya needs all three:

| Layer | What it is | Where it lives | Example |
|-------|------------|----------------|---------|
| **A — Cursor Agent Skills** | Markdown instruction files the AI reads before coding | `~/.cursor/skills/` or project `.cursor/skills/` | `emil-design-eng`, `impeccable` |
| **B — npm packages** | Libraries installed in the project | `package.json` | `framer-motion`, `three`, `gsap` |
| **C — Project docs + components** | Rules and reusable React pieces in *your* repo | `docs/react/`, `src/components/motion/` | `INFLUORA-MASTER-PROMPT.md`, `FadeUp.tsx` |

```
┌─────────────────────────────────────────────────────────────┐
│  LAYER A — Cursor reads SKILL.md → behaves like an expert   │
├─────────────────────────────────────────────────────────────┤
│  LAYER B — npm gives the engine (Framer, Three, GSAP)       │
├─────────────────────────────────────────────────────────────┤
│  LAYER C — Influora docs + motion/ folder = your playbook  │
└─────────────────────────────────────────────────────────────┘
```

**Important:** Skills are **not** something React downloads at runtime. They teach **you and Cursor** how to write good React code.

---

## 2. Full 32-skill catalog (from GENERIC-SKILLS-REFERENCE)

The pointer file lists skills grouped below. **Skill #22 (`next-best-practices`) does not apply** — Influora uses **Vite**, not Next.js. Everything else maps to React + Vite.

### Group 1 — UI craft & motion philosophy (Skills 1–4)

| # | Skill name | Purpose | Influora equivalent |
|---|------------|---------|---------------------|
| 1 | `impeccable` | Shape → craft → animate → audit → polish workflow | Create Cursor skill OR use `INFLUORA-MASTER-PROMPT.md` + self-check |
| 2 | `emil-design-eng` | Button press, easing, no `transition-all` | Already in `INFLUORA-MASTER-PROMPT.md` § Skill 1 |
| 3 | `design-motion-principles` | AUDIT page before animating; anti-AI-slop | `INFLUORA-3D-MOTION-BLUEPRINT.md` + `docs/frontend/` audits |
| 4 | `animate-skill` | Lightbox, card hover, wizard step patterns | Build via `prompts/INFLUORA-MOTION-COMPONENT-PROMPTS.md` MC-01–06 |

### Group 2 — Framer Motion (Skills 5–9)

| # | Skill name | Purpose | npm / status |
|---|------------|---------|--------------|
| 5 | `framer-motion-basics` | `motion`, `AnimatePresence`, variants | ✅ `framer-motion` installed |
| 6 | `framer-motion-scroll` | `whileInView`, viewport once | Use in `FadeUp`, onboarding |
| 7 | `framer-motion-layout` | `layoutId` shared transitions | Portfolio gallery lightbox |
| 8 | `framer-motion-gestures` | drag, hover spring | `MagneticButton`, `TiltCard` |
| 9 | `framer-motion-orchestration` | stagger, delayChildren | `StaggerContainer` |

### Group 3 — Scroll & video (Skills 10–11)

| # | Skill name | Purpose | npm / status |
|---|------------|---------|--------------|
| 10 | `gsap-framer-scroll-animation` | Pinned sections, scroll-scrub video | ⬜ Not installed — Phase 7 optional |
| 11 | `lenis` | Smooth scroll synced with GSAP | ⬜ Not installed — marketing landing only |

**Influora note:** SaaS dashboards rarely need Lenis. Skip until a public marketing landing page exists.

### Group 4 — Three.js / R3F (Skills 12–21)

| # | Skill name | Purpose | npm / status |
|---|------------|---------|--------------|
| 12 | `threejs-fundamentals` | Scene, camera, lights | ✅ `three` installed |
| 13 | `react-three-fiber` | `<Canvas>`, hooks | ✅ `@react-three/fiber` installed |
| 14 | `drei-helpers` | Float, Html, OrbitControls | ✅ `@react-three/drei` installed |
| 15 | `threejs-materials` | MeshDistortMaterial, PBR | ✅ Used in `login-scene-3d.tsx` |
| 16 | `threejs-performance` | dpr, PerformanceMonitor | Documented in blueprint — polish needed |
| 17 | `threejs-particles` | Points, instancing | DiscoverCanvas (planned) |
| 18 | `threejs-loaders` | GLTF (if needed) | Optional — Influora uses procedural geometry |
| 19 | `threejs-interaction` | Raycaster, pointer parallax | DiscoverCanvas mouse parallax |
| 20 | `threejs-postprocessing` | Bloom (optional) | Skip for SaaS — keep lightweight |
| 21 | `threejs-fallbacks` | reduced-motion, lazy load | `CanvasFallback` (planned) |

### Group 5 — Stack & UI (Skills 22–24)

| # | Skill name | Next.js kit | **Influora Vite replacement** |
|---|------------|-------------|-------------------------------|
| 22 | `next-best-practices` | App Router, `next/image` | **`vite-react-best-practices`** — see §4 below |
| 23 | `typescript` | Strict types | ✅ Already in project (`tsconfig.json` strict) |
| 24 | `shadcn` | Forms, dialogs, components | ✅ 57 components in `src/components/ui/` |

### Group 6 — Assets & reuse (Skills 25–32)

| # | Skill name | Purpose | Influora doc |
|---|------------|---------|--------------|
| 25–30 | Gemini pipeline | Generate images/videos offline | `prompts/INFLUORA-IMAGE-PROMPT-TEMPLATE.md` |
| 31 | Gemini scripts | `generate-assets.mjs` | Optional — copy template from FrontendDesign |
| 32 | Project motion components | Reuse before raw Framer | `src/components/motion/` (to build) |

---

## 3. What Priya already has (Influora today)

| Skill area | Already in project? | Evidence |
|------------|---------------------|----------|
| Framer Motion (5–9) | **Partial** | `framer-motion` in package.json; used on auth + onboarding only |
| Three.js / R3F (12–16) | **Partial** | `login-scene-3d.tsx`, `@react-three/*` installed |
| Emil rules (2) | **In docs** | `INFLUORA-MASTER-PROMPT.md` |
| shadcn (24) | **Yes** | Full `src/components/ui/` |
| TypeScript (23) | **Yes** | Strict TS, `types.ts` |
| Motion components (32) | **No** | `components/motion/` not created yet |
| GSAP + Lenis (10–11) | **No** | Not in package.json |
| Cursor skills (1–4) | **No** | Not in `~/.cursor/skills/` yet |
| Gemini pipeline (25–31) | **Docs only** | Prompt templates in `docs/react/prompts/` |

---

## 4. Next.js skill → Vite skill (Skill #22 swap)

The Hind Exports kit assumes **Next.js**. Influora uses **Vite + React Router**. Same React patterns, different imports:

| Next.js (kit) | Influora Vite |
|---------------|---------------|
| `next/dynamic(..., { ssr: false })` | `React.lazy()` + `<Suspense>` |
| `next/image` | `<img>` or lazy loading + `public/` |
| `app/layout.tsx` global canvas | **Do not** — use page-level 3D only |
| `usePathname()` | `useLocation()` from react-router-dom |
| `app/page.tsx` | `src/pages/*.tsx` + `App.tsx` routes |
| Metadata API | `document.title` + meta tags (see portfolio public) |
| `public/` folder | Same — `public/images/` |

**Priya’s Vite skill doc:** `docs/react/INFLUORA-FLOW-MASTER.md` § “Vite vs Next.js notes”

---

## 5. How Priya installs everything — step by step

### Step 1 — Project docs (Layer C) — **DONE**

You already have the Influora fork of the FrontendDesign kit:

```
docs/react/
├── INFLUORA-FLOW-MASTER.md
├── INFLUORA-MASTER-PROMPT.md      ← Emil + Framer + R3F rules
├── INFLUORA-3D-MOTION-BLUEPRINT.md
├── INFLUORA-PLAN-OF-ACTION.md
├── INFLUORA-PROJECT-CONFIG.md
└── prompts/                        ← Cursor copy-paste prompts

docs/frontend/                      ← All pages, colors, UI audit
```

**Action:** Before every session, open `INFLUORA-FLOW-MASTER.md` → pick next task.

---

### Step 2 — npm packages (Layer B)

| Package | Command (when ready) | Phase |
|---------|----------------------|-------|
| Already have | `framer-motion`, `three`, `@react-three/fiber`, `@react-three/drei` | — |
| GSAP (optional) | `npm install gsap @gsap/react` | Phase 7 |
| Lenis (optional) | `npm install lenis` | Later |

**Action:** Do **not** install GSAP/Lenis until Phase 1–5 motion components work with Framer alone.

---

### Step 3 — Build motion components (Skill #32)

This is how Priya **internalizes** Framer skills 5–9 + animate-skill 4:

| Order | Prompt file | Builds |
|-------|-------------|--------|
| 1 | `prompts/INFLUORA-MOTION-COMPONENT-PROMPTS.md` MC-00 | `motion-config.ts` |
| 2 | MC-01 → MC-06 | FadeUp, Stagger, WordReveal, TiltCard, CountUp, MagneticButton |
| 3 | MC-07 → MC-08 | CanvasFallback + polish LoginScene3D |
| 4 | MC-09 → MC-10 | DiscoverCanvas, PortfolioCanvas |
| 5 | `prompts/INFLUORA-PAGE-SESSION-PROMPTS.md` PS-01+ | Wire into real pages |

**Action:** In Cursor Composer (`Ctrl+I`), paste MC-00, attach `@docs/react/INFLUORA-MASTER-PROMPT.md`.

---

### Step 4 — Cursor Agent Skills (Layer A)

The Hind Exports repo has full `SKILL.md` files (e.g. `emil-design-eng`). Priya’s Downloads folder only has the **pointer** — not the full 32 files.

**Option A — Create Influora project skills (recommended)**

Create folder in the repo:

```
New Influora/.cursor/skills/
├── influora-emil-motion/SKILL.md       ← copy rules from INFLUORA-MASTER-PROMPT
├── influora-r3f/SKILL.md               ← copy R3F section from blueprint
├── influora-motion-audit/SKILL.md      ← self-check protocol
└── influora-vite-react/SKILL.md        ← Vite replacements for Next skill
```

Cursor auto-discovers skills in `.cursor/skills/` for this project.

**Option B — User-level skills**

Copy skill folders to:

```
C:\Users\Priya Ingle\.cursor\skills\
```

These apply to **all** Cursor projects.

**Option C — Use Cursor Rules instead**

If skills feel heavy, create `.cursor/rules/motion.mdc` with the Emil + R3F rules from `INFLUORA-MASTER-PROMPT.md`. Rules load automatically in every chat.

**How to create a skill:** Use Cursor’s “Create Skill” flow or ask the agent: *“Create a Cursor skill from INFLUORA-MASTER-PROMPT.md § Skill 1–3”*

---

### Step 5 — Gemini asset pipeline (Skills 25–31)

| Step | Action |
|------|--------|
| 1 | Read `prompts/INFLUORA-BRAND-STYLE-RULES.md` |
| 2 | Fill image prompts from `INFLUORA-IMAGE-PROMPT-TEMPLATE.md` |
| 3 | Add `GEMINI_API_KEY` to `.env.local` |
| 4 | Copy `generate-assets.mjs.template` from FrontendDesign → adapt paths |
| 5 | Run script → files land in `public/images/` |
| 6 | Reference in `CanvasFallback` and empty states |

**Split:** Gemini makes **pixels**. Cursor makes **React**. Never mix.

---

## 6. Priya’s weekly learning path (skills → practice)

| Week | Focus | Skills covered | Influora pages |
|------|-------|----------------|------------------|
| **1** | Docs + Emil audit | 2, 3, 24 | Run PS-11 button audit on `button.tsx` |
| **2** | Motion primitives | 4, 5–9, 32 | MC-00 → MC-06; test on dashboard |
| **3** | Page integration L1 | 32 | PS-02 campaigns, PS-04 dashboard, PS-08 creator deals |
| **4** | 3D polish | 12–16, 21 | MC-08 auth; MC-09 discover canvas |
| **5** | Onboarding + forms | 4, 6 | PS-06 onboarding AnimatePresence |
| **6** | Portfolio + public | 7, 17–19 | MC-10 + PS-10 public portfolio |
| **7+** | Optional GSAP | 10–11 | Dashboard pipeline pin only if needed |

---

## 7. Session checklist for Priya (every time you open Cursor)

```
□ 1. Open docs/react/INFLUORA-FLOW-MASTER.md — confirm phase
□ 2. Open INFLUORA-PLAN-OF-ACTION.md — pick one unchecked page
□ 3. Pick skills for today:
      - Buttons/hover     → emil-design-eng (Skill 2)
      - New page motion   → design-motion-principles AUDIT first (Skill 3)
      - Framer components → framer-motion-* (Skills 5–9) OR MC prompts
      - 3D canvas         → threejs-* (Skills 12–21) OR MC-08–10
      - Forms/dialogs     → shadcn (Skill 24)
□ 4. Attach files with @ in Composer
□ 5. Paste prompt from prompts/INFLUORA-*-PROMPTS.md
□ 6. Run self-check from INFLUORA-MASTER-PROMPT.md
□ 7. Update PLAN-OF-ACTION checkbox + PROJECT-CONFIG build status
```

---

## 8. Skill → Influora file map (quick lookup)

| When you work on… | Read this skill/doc |
|-------------------|---------------------|
| Any button or hover | `INFLUORA-MASTER-PROMPT.md` § Emil |
| Before touching a page | `INFLUORA-3D-MOTION-BLUEPRINT.md` motion level |
| Building FadeUp / TiltCard | `INFLUORA-MOTION-COMPONENT-PROMPTS.md` |
| Wiring campaigns page | `INFLUORA-PAGE-SESSION-PROMPTS.md` PS-02 |
| Colors on a page | `docs/frontend/COLOR-SYSTEM.md` |
| What UI exists on a page | `docs/frontend/BRAND-PAGES-AUDIT.md` or CREATOR |
| 3D canvas rules | Blueprint § DiscoverCanvas / LoginScene3D |
| Vite lazy load (not Next) | `INFLUORA-FLOW-MASTER.md` § Vite vs Next |
| Generate hero images | `prompts/INFLUORA-IMAGE-PROMPT-TEMPLATE.md` |

---

## 9. What Priya does NOT need (for Influora SaaS)

| Skill / pattern | Why skip |
|-----------------|----------|
| `next-best-practices` | Vite project |
| `lenis` + long scroll pin | No marketing brochure pages yet |
| OceanCanvas in layout | Conflicts with dashboard UX |
| ExportGlobe, ProductShapeViewer | B2B SaaS — use creator cards instead |
| WhatsApp pulse buttons | Influora is email/in-app, not WhatsApp CRO |
| `@splinetool/react-spline` | R3F already used — pick one 3D approach |
| Heavy GSAP on chat | Deal room must stay fast (Level 0–1) |

---

## 10. Getting the missing full SKILLS-REFERENCE.md

Your `GENERIC-SKILLS-REFERENCE.md` points to:

```
../SKILLS-REFERENCE.md  (32 skills, Hind Exports full catalog)
```

That file is **not** in `Downloads/FrontendDesign/`. To get it:

1. Copy from the Hind Exports reference repo if you have it, **or**
2. Use Influora docs as replacement — they already contain the rules, **or**
3. Ask Cursor: *“Create `.cursor/skills/` from INFLUORA-MASTER-PROMPT.md for this project”*

For Influora, **you do not need the Hind Exports file** — `docs/react/` is the filled-in version.

---

## 11. Summary — three actions for Priya today

| Priority | Action | Gets you skills |
|----------|--------|-----------------|
| **1** | Read `docs/react/INFLUORA-FLOW-MASTER.md` | Full pipeline |
| **2** | Run Cursor prompt **MC-00** then **MC-01** | Framer 5–9 + Skill 32 |
| **3** | Create `.cursor/rules/motion.mdc` OR one project skill from MASTER-PROMPT | Cursor Layer A (Emil + R3F) |

After Week 2, Priya will have **practical** motion skills in React + Vite — not just documentation — because the skills live in **reusable components** (`src/components/motion/`) that every page imports.

---

## Related files

| File | Role |
|------|------|
| `Downloads/FrontendDesign/GENERIC-SKILLS-REFERENCE.md` | Original 32-skill pointer |
| `docs/react/INFLUORA-MASTER-PROMPT.md` | Emil + Framer + R3F rules (Skills 1–4, 12–16) |
| `docs/react/prompts/INFLUORA-MOTION-COMPONENT-PROMPTS.md` | Build Skill 32 components |
| `docs/frontend/UI-ELEMENTS-AND-MODELS.md` | shadcn Skill 24 inventory |

---

*Guide v1.0 — Influora React + Vite skills path for Priya*
