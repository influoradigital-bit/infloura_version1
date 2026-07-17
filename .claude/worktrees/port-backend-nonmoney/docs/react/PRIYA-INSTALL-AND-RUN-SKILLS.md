# Priya — Install & Run Skills Guide (Influora · React + Vite)

**Project:** `New Influora`  
**Stack:** React 19 · Vite 6 · TypeScript · Tailwind 4 · Framer Motion · R3F  
**Your machine:** Windows

This is the **hands-on install + run** guide. For *what* each skill means, see [INFLUORA-SKILLS-GUIDE-FOR-PRIYA.md](./INFLUORA-SKILLS-GUIDE-FOR-PRIYA.md).

---

## Table of contents

1. [Before you start](#1-before-you-start)
2. [Install npm packages (Layer B)](#2-install-npm-packages-layer-b)
3. [Install Cursor skills (Layer A)](#3-install-cursor-skills-layer-a)
4. [Install project docs (Layer C) — already done](#4-install-project-docs-layer-c--already-done)
5. [Run the app](#5-run-the-app)
6. [Run motion skills (build components)](#6-run-motion-skills-build-components)
7. [Run page integration skills](#7-run-page-integration-skills)
8. [Optional: GSAP + Lenis (Phase 7)](#8-optional-gsap--lenis-phase-7)
9. [Optional: Gemini image assets](#9-optional-gemini-image-assets)
10. [Verify everything works](#10-verify-everything-works)
11. [Daily run checklist](#11-daily-run-checklist)
12. [Troubleshooting](#12-troubleshooting)

---

## 1. Before you start

### Prerequisites

| Tool | Check command | Minimum |
|------|---------------|---------|
| Node.js | `node -v` | 18+ |
| npm | `npm -v` | 9+ |
| Cursor IDE | Open project folder | Latest |
| Git (optional) | `git -v` | Any |

### Open the project folder in Cursor

```
d:\priya workspace\infloura_Production\New Influora
```

### Paths you will use

| What | Windows path |
|------|--------------|
| Project root | `d:\priya workspace\infloura_Production\New Influora` |
| Cursor personal skills | `C:\Users\Priya Ingle\.cursor\skills\` |
| Cursor project skills | `d:\priya workspace\infloura_Production\New Influora\.cursor\skills\` |
| FrontendDesign kit | `C:\Users\Priya Ingle\Downloads\FrontendDesign\` |

---

## 2. Install npm packages (Layer B)

These are the **libraries** behind Framer, 3D, and UI skills.

### Step 2.1 — Open terminal in project root

In Cursor: **Terminal → New Terminal** (or `` Ctrl+` ``)

### Step 2.2 — Install all existing dependencies

```powershell
cd "d:\priya workspace\infloura_Production\New Influora"
npm install
```

**Wait until it finishes** (may take 2–5 minutes first time).

### Step 2.3 — Confirm motion packages are installed

```powershell
npm list framer-motion three @react-three/fiber @react-three/drei --depth=0
```

**Expected:** All four show version numbers (not `empty`).

| Package | Skill group | Status in Influora |
|---------|-------------|-------------------|
| `framer-motion` | Skills 5–9 | Required — already in package.json |
| `three` | Skills 12–21 | Required — already in package.json |
| `@react-three/fiber` | Skill 13 | Required — already in package.json |
| `@react-three/drei` | Skill 14 | Required — already in package.json |

**You do NOT need to run `npm install framer-motion` separately** if Step 2.2 succeeded.

### Step 2.4 — Optional packages (install later only)

**Do not install until Phase 7** unless you need pinned scroll:

```powershell
npm install gsap @gsap/react
```

```powershell
npm install lenis
```

---

## 3. Install Cursor skills (Layer A)

Cursor **Agent Skills** are folders with a `SKILL.md` file. They teach the AI how to code like Emil / Framer / R3F experts.

> **Never** put your skills in `C:\Users\Priya Ingle\.cursor\skills-cursor\` — that folder is for Cursor built-ins only.

### Option A — Project skills (recommended for Influora)

Skills live **inside the repo** so the whole team shares them.

#### Step 3A.1 — Create folder structure

In Cursor terminal:

```powershell
cd "d:\priya workspace\infloura_Production\New Influora"
mkdir .cursor\skills\influora-emil-motion
mkdir .cursor\skills\influora-framer-motion
mkdir .cursor\skills\influora-r3f-canvas
mkdir .cursor\skills\influora-vite-react
```

#### Step 3A.2 — Create each SKILL.md

Ask Cursor Agent (Chat or Composer):

> **Prompt to paste:**
> ```
> Create 4 Cursor project skills in .cursor/skills/ for Influora:
> 1. influora-emil-motion — from docs/react/INFLUORA-MASTER-PROMPT.md Skill 1 (Emil rules)
> 2. influora-framer-motion — from INFLUORA-MASTER-PROMPT.md Skill 2 + motion component reuse rules
> 3. influora-r3f-canvas — from INFLUORA-3D-MOTION-BLUEPRINT.md performance rules
> 4. influora-vite-react — Vite + React Router replacements for Next.js (lazy, useLocation, no layout canvas)
> Each needs YAML frontmatter with name and description. Keep each SKILL.md under 200 lines.
> ```

#### Step 3A.3 — Reload Cursor

After creating skills:
1. **Close and reopen** the project in Cursor, **or**
2. Start a **new Agent chat**

Cursor discovers skills in `.cursor/skills/` automatically.

---

### Option B — Cursor Rules (faster alternative)

If you don’t want full skills yet, use **Rules** (lighter setup):

#### Step 3B.1 — Create rules folder

```powershell
mkdir "d:\priya workspace\infloura_Production\New Influora\.cursor\rules"
```

#### Step 3B.2 — Ask Cursor to create rule file

> **Prompt to paste:**
> ```
> Create .cursor/rules/influora-motion.mdc with alwaysApply: true.
> Content: Emil button rules + Framer once:true + R3F dpr [1,1.5] + no 3D in chat/layout
> from docs/react/INFLUORA-MASTER-PROMPT.md. Max 80 lines.
> ```

Rules load in **every** chat without you naming a skill.

---

### Option C — Personal skills (all projects)

Copy skill folders to:

```
C:\Users\Priya Ingle\.cursor\skills\influora-emil-motion\SKILL.md
```

Same `SKILL.md` content as Option A — but available in **every** Cursor project on your PC.

---

### Skills install map (32 → what Priya installs)

| Skill # | Name | How Priya installs it |
|---------|------|------------------------|
| 1 | impeccable | Cursor skill OR follow PLAN-OF-ACTION checklists |
| 2 | emil-design-eng | `.cursor/skills/influora-emil-motion/` |
| 3 | design-motion-principles | Read blueprint before each page + frontend audits |
| 4 | animate-skill | Build `src/components/motion/` via MC prompts |
| 5–9 | framer-motion-* | `npm install` + motion components |
| 10–11 | gsap, lenis | Optional npm — Phase 7 |
| 12–21 | threejs-* | Already npm installed + `influora-r3f-canvas` skill |
| 22 | next-best-practices | **Skip** — use `influora-vite-react` skill instead |
| 23 | typescript | Already in project |
| 24 | shadcn | Already in `src/components/ui/` |
| 25–31 | Gemini pipeline | Optional — §9 below |
| 32 | motion components | Build + import from `@/components/motion` |

---

## 4. Install project docs (Layer C) — already done

These files **are** your Influora skill library. No install command — just read them.

| File | When to open |
|------|--------------|
| `docs/react/INFLUORA-FLOW-MASTER.md` | Start of every session |
| `docs/react/INFLUORA-PLAN-OF-ACTION.md` | Pick next task |
| `docs/react/INFLUORA-MASTER-PROMPT.md` | Before coding motion |
| `docs/react/prompts/INFLUORA-MOTION-COMPONENT-PROMPTS.md` | Build components |
| `docs/frontend/COLOR-SYSTEM.md` | Colors on any page |

---

## 5. Run the app

### Step 5.1 — Start frontend (Vite)

```powershell
cd "d:\priya workspace\infloura_Production\New Influora"
npm run dev
```

**Open in browser:** http://localhost:3000

(Vite config uses port **3000**, not 5173.)

### Step 5.2 — Start backend (optional — live API)

**Terminal 1 — MySQL:**

```powershell
cd "d:\priya workspace\infloura_Production\New Influora"
docker compose up -d
```

**Terminal 2 — Spring Boot:**

```powershell
cd "d:\priya workspace\infloura_Production\New Influora\influora-api"
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

**Terminal 3 — Frontend with live API:**

Create file `.env.local` in project root:

```env
VITE_API_MODE=live
VITE_API_BASE_URL=http://localhost:8080/api/v1
```

Then:

```powershell
npm run dev
```

### Step 5.3 — Pages to test motion

| URL | What to check |
|-----|---------------|
| http://localhost:3000/brand/login | 3D blobs + aurora (Skill 12–16) |
| http://localhost:3000/creator/login | Same auth shell |
| http://localhost:3000/brand/onboarding | Step transitions (Framer) |
| http://localhost:3000/brand/campaigns | Cards (after PS-02 done) |
| http://localhost:3000/brand/discover | Discover canvas (after MC-09 done) |

### Step 5.4 — Stop the app

In terminal: **Ctrl+C**

---

## 6. Run motion skills (build components)

This turns Framer/animate skills into **real React files** Priya can import.

### Session order (do not skip)

| Session | Prompt ID | File to open |
|---------|-----------|--------------|
| 1 | MC-00 | `docs/react/prompts/INFLUORA-MOTION-COMPONENT-PROMPTS.md` |
| 2 | MC-01 → MC-06 | Same file |
| 3 | MC-07 → MC-08 | Same file |
| 4 | MC-09 → MC-10 | Same file (3D canvases) |

### How to run one prompt in Cursor

1. Open **Composer** — `Ctrl+I`
2. Attach docs:
   - `@docs/react/INFLUORA-MASTER-PROMPT.md`
   - `@docs/react/INFLUORA-3D-MOTION-BLUEPRINT.md`
3. Copy **MC-00** block from `INFLUORA-MOTION-COMPONENT-PROMPTS.md`
4. Paste into Composer → **Enter**
5. Review diff → **Accept**
6. Run app: `npm run dev` → check no errors in browser console

### After MC-00 → MC-06, you should have:

```
src/
├── lib/motion-config.ts
├── hooks/useInViewOnce.ts
└── components/motion/
    ├── FadeUp.tsx
    ├── StaggerContainer.tsx
    ├── WordReveal.tsx
    ├── TiltCard.tsx
    ├── CountUp.tsx
    ├── MagneticButton.tsx
    └── index.ts
```

### Test motion skill on one page

After MC-01, ask Cursor:

> Run PS-04 from docs/react/prompts/INFLUORA-PAGE-SESSION-PROMPTS.md on brand dashboard only.

Refresh http://localhost:3000/brand/dashboard

---

## 7. Run page integration skills

After motion components exist, wire pages one at a time.

| Prompt | Page | URL to verify |
|--------|------|---------------|
| PS-11 | Global buttons | Any page — click buttons |
| PS-01 | Brand auth | `/brand/login` |
| PS-02 | Campaigns list | `/brand/campaigns` |
| PS-03 | Discover | `/brand/discover` |
| PS-04 | Dashboard | `/brand/dashboard` |
| PS-06 | Onboarding | `/brand/onboarding` |
| PS-07 | Brand chat | `/brand/chat` — **minimal motion only** |
| PS-08 | Creator deals | `/creator/deals` |
| PS-10 | Public portfolio | `/@yourhandle` |

**How to run:** Same as §6 — Composer + attach page file + paste PS-XX block.

---

## 8. Optional: GSAP + Lenis (Phase 7)

Only when you need **pinned scroll timeline** on dashboard or a marketing landing page.

### Install

```powershell
cd "d:\priya workspace\infloura_Production\New Influora"
npm install gsap @gsap/react lenis
```

### Run / use

1. Read `INFLUORA-3D-MOTION-BLUEPRINT.md` — no GSAP on chat pages
2. Ask Cursor: *"Add GSAP ScrollTrigger pinned pipeline to dashboard-page.tsx per blueprint, with useReducedMotion static fallback"*
3. `npm run dev` → test scroll on `/brand/dashboard`
4. Test with **Windows Settings → Accessibility → Visual effects → Animation effects OFF**

---

## 9. Optional: Gemini image assets

For static fallbacks when 3D is off (Skills 25–31).

### Step 9.1 — API key

Create `.env.local` (do **not** commit):

```env
GEMINI_API_KEY=your_key_here
```

### Step 9.2 — Copy script template

Copy from:

```
C:\Users\Priya Ingle\Downloads\FrontendDesign\generate-assets.mjs.template
```

Into project (e.g. `scripts/generate-influora-images.mjs`) and adapt paths from:

```
docs/react/prompts/INFLUORA-IMAGE-PROMPT-TEMPLATE.md
```

### Step 9.3 — Run generator

```powershell
cd "d:\priya workspace\infloura_Production\New Influora"
node scripts/generate-influora-images.mjs
```

### Step 9.4 — Verify output

Images should appear in:

```
public/images/auth/hero-static.png
public/images/discover/hero-static.png
```

Refresh app with reduced motion — fallback images should show.

---

## 10. Verify everything works

### Checklist after full install

```
□ npm install completed with no errors
□ npm run dev opens http://localhost:3000
□ Brand login shows 3D blobs (desktop lg+)
□ No red errors in browser DevTools Console (F12)
□ .cursor/skills/ OR .cursor/rules/ exists with motion rules
□ docs/react/INFLUORA-PLAN-OF-ACTION.md — Phase 0 checked
□ (After MC-00) src/lib/motion-config.ts exists
□ (After MC-06) src/components/motion/FadeUp.tsx exists
□ Reduced motion: Windows animation off → auth shows static gradient not broken layout
```

### Quick health commands

```powershell
# TypeScript check (if needed)
npx tsc --noEmit

# Production build test
npm run build
```

Build should finish without errors.

---

## 11. Daily run checklist

Copy this every working day:

```
MORNING SETUP (5 min)
□ Open Cursor → project folder
□ docker compose up -d          (if using live API)
□ npm run dev                   (frontend)
□ Read INFLUORA-PLAN-OF-ACTION — one unchecked task

CODING SESSION
□ Pick prompt: MC-XX or PS-XX
□ Composer Ctrl+I + attach @files
□ Paste prompt → accept changes
□ Browser test the one page you changed
□ Tick checkbox in PLAN-OF-ACTION

END OF DAY
□ Ctrl+C stop dev server
□ Update INFLUORA-PROJECT-CONFIG.md build status table
```

---

## 12. Troubleshooting

| Problem | Fix |
|---------|-----|
| `npm install` fails | Run terminal as Administrator; delete `node_modules` + `package-lock.json`, run `npm install` again |
| Port 3000 in use | Kill other process or change port in `vite.config.ts` |
| 3D canvas blank | Check browser WebGL; try Chrome; open DevTools Console for errors |
| Cursor ignores motion rules | Confirm `.cursor/skills/` or `.cursor/rules/` exists; start **new** chat |
| Skills not found | Never use `skills-cursor` folder — use `.cursor/skills/` in project |
| API calls fail | Check `.env.local` has `VITE_API_MODE=live`; backend on :8080; MySQL docker running |
| Motion too heavy on laptop | Blueprint: max 1 canvas per page; use `useReducedMotion` fallback |
| `mvn` not found | Install Java 21 + Maven; add to PATH |
| Docker not running | Start Docker Desktop first, then `docker compose up -d` |

---

## Quick reference — all commands in order

```powershell
# 1. First-time setup
cd "d:\priya workspace\infloura_Production\New Influora"
npm install

# 2. Create Cursor skills (folders)
mkdir .cursor\skills\influora-emil-motion
mkdir .cursor\skills\influora-framer-motion
mkdir .cursor\skills\influora-r3f-canvas
mkdir .cursor\skills\influora-vite-react
# Then ask Cursor to write SKILL.md files (see §3A.2)

# 3. Run frontend
npm run dev
# → http://localhost:3000

# 4. Run backend (optional)
docker compose up -d
cd influora-api
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 5. Optional Phase 7
npm install gsap @gsap/react lenis

# 6. Verify build
npm run build
```

---

## Related docs

| Doc | Purpose |
|-----|---------|
| [INFLUORA-SKILLS-GUIDE-FOR-PRIYA.md](./INFLUORA-SKILLS-GUIDE-FOR-PRIYA.md) | What each of the 32 skills means |
| [INFLUORA-FLOW-MASTER.md](./INFLUORA-FLOW-MASTER.md) | Full pipeline |
| [INFLUORA-MOTION-COMPONENT-PROMPTS.md](./prompts/INFLUORA-MOTION-COMPONENT-PROMPTS.md) | MC-00 → MC-11 prompts |
| [INFLUORA-PAGE-SESSION-PROMPTS.md](./prompts/INFLUORA-PAGE-SESSION-PROMPTS.md) | PS-01 → PS-12 prompts |
| [../frontend/README.md](../frontend/README.md) | UI & color reference |

---

*Install & run guide v1.0 — for Priya Ingle · Influora React + Vite*
