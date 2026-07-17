# 🏗️ PRIYA — ARCHITECTURE HANDOFF: MEERA AI COFOUNDER

> **From:** Priya (CTO) · **To:** Ananya (Frontend) · **Date:** 2026-07-04 · **Milestone:** M2.5
> **Companion to:** `docs/AI connect/FRONTEND-BUILD-SPEC-MEERA.md` (the build sheet) and `docs/PRD-MEERA-AI-COFOUNDER.md`
> **Status:** APPROVED — this doc is the *how to wire it into THIS repo*. The spec says what to build; this says where every wire goes. **All paths verified against the repo on 2026-07-04.**

This handoff resolves the one thing the spec leaves ambiguous per-repo: **exact insertion points and existing patterns**. Do not invent new patterns. Match what is already here.

---

## 0. TL;DR — the 8 things you must not get wrong

1. This is **Vite + react-router-dom v7**, NOT Next. Route wiring is manual `<Route>` elements in `src/App.tsx`. There is no file-system routing.
2. Pages are **thin wrappers** in `src/pages/*.tsx`. The heavy component tree lives in `src/components/...`. Make a `src/pages/brand-meera.tsx` that renders `<MeeraWorkspace />`.
3. Tokens live in **`src/app/globals.css`** in a **two-block pattern**: raw hex in `:root` / `.dark`, then *mapped* to `--color-*` inside `@theme inline`. You must edit **both** blocks for every new token, or Tailwind won't emit the class.
4. **No `tailwind.config` file exists.** Tailwind v4 is CSS-first. You cannot add colors in JS. Tokens-only; **zero raw color classes** (no `bg-indigo-500`, no `text-[#6D5AE6]`).
5. `--radius` base is **`0.625rem`** and the repo already exposes `--radius-sm/md/lg/xl` via `@theme inline`. Align the spec's radius scale to these — don't fork a second radius system.
6. Import alias is **`@/`** → `src/`. Every import uses it (`@/components/...`, `@/lib/...`, `@/hooks/...`). Never use relative `../../`.
7. Reuse the **`HeroGlobe` + `HeroGlobeGate`** pattern for any `<Canvas>`. The reduced-motion / low-power gate is a **separate exported component**, not an inline check.
8. `src/data/` **does not exist yet** — you create it. `src/hooks/` exists (add Meera hooks there OR co-locate in the feature folder — see §6).

---

## 1. ROUTING — add `/brand/meera`

**File:** `src/App.tsx` (verified). Routing is 100% manual. The repo pattern for a protected, chrome-wrapped brand page is:

```tsx
<Route
  path="/brand/xxx"
  element={
    <BrandLayoutWrapper>
      <BrandXxxPage />
    </BrandLayoutWrapper>
  }
/>
```

`BrandLayoutWrapper` (defined at `src/App.tsx:49`) already wraps children in `<ProtectedRoute>` + `<BrandLayout>`. **Use it — do not roll your own guard.** Demo/dev mode is already allowed by `ProtectedRoute` (`src/App.tsx:41-46`), so `/brand/meera?demo=true` and dev builds work without a token.

### Steps
1. Create the thin page wrapper **`src/pages/brand-meera.tsx`**:
   ```tsx
   import { MeeraWorkspace } from '@/components/feature/meera/MeeraWorkspace';
   export default function BrandMeeraPage() {
     return <MeeraWorkspace />;
   }
   ```
2. In `src/App.tsx`, add the import next to the other brand-page imports (near lines 3-23):
   ```tsx
   import BrandMeeraPage from '@/pages/brand-meera';
   ```
3. Add the route **inside the "Protected Routes with Layout" group** (after the `/brand/chat` route block, ~line 164):
   ```tsx
   <Route
     path="/brand/meera"
     element={
       <BrandLayoutWrapper>
         <BrandMeeraPage />
       </BrandLayoutWrapper>
     }
   />
   ```
   Placement matters only relative to the **catch-all `/:handle`** (`src/App.tsx:311`) and `*` (`:312`) — both are last, so any `/brand/*` route added in the protected group is safe.

> ⚠️ **Full-viewport layout note.** `<BrandLayout>` renders your page inside `<main className="flex-1">` beside a `w-60` sidebar and under a `h-14` sticky header. The 50/50 workspace must size itself to the *remaining* viewport, not `100vh`. Follow the existing convention from `brand-chat.tsx:547`: the page root uses **`h-[calc(100vh-3.5rem)]`** (3.5rem = the `h-14` header). Use that same height so the seam and canvas fill correctly and don't double-scroll.

---

## 2. NAV ITEM — add "Meera" to the sidebar

**File:** `src/components/brand/brand-layout.tsx` (verified). The nav is a single array consumed by both the desktop sidebar and the mobile sheet.

### 2a. Add to `navItems` (array at `brand-layout.tsx:67-73`)
Pick a lucide icon that reads as "AI cofounder" — `Sparkles` or `Bot` (both already available from `lucide-react`; add to the import block at lines 3-19). Insert Meera high in the list (it's the flagship surface):

```tsx
const navItems = [
  { label: 'Home', href: '/brand/dashboard', icon: Home },
  { label: 'Meera', href: '/brand/meera', icon: Sparkles }, // ← NEW, flagship position
  { label: 'Campaigns', href: '/brand/campaigns', icon: Megaphone },
  { label: 'Creators', href: '/brand/discover', icon: Users2 },
  { label: 'Deals', href: '/brand/chat', icon: MessageCircle },
  { label: 'Wallet', href: '/brand/wallet', icon: Wallet },
];
```

### 2b. Active-state highlight (`isActive()` at `brand-layout.tsx:109-124`)
The default `return pathname.startsWith(href)` (line 123) already lights up `/brand/meera` correctly. **No change needed** unless Meera gains sub-routes. Do NOT touch the `/brand/chat` special case — that's the Deals cluster.

### 2c. Icon variant
Each nav item's icon color comes from `getBrandNavIconVariant(item.href)` (`brand-layout.tsx:196, 422`, imported from `@/lib/icon-theme`). Open **`src/lib/icon-theme.ts`** and add a case for `/brand/meera` so the icon gets a defined variant (fall back to the existing default if you want it neutral until active). This is a 1-line map entry — don't skip it or the icon renders with the fallback variant only.

That's it — the array drives **both** the desktop sidebar (`:179`) and the mobile sheet (`:406`). One edit, both surfaces.

---

## 3. TOKENS — extend `src/app/globals.css` without breaking anything

**This is the highest-risk step. Read it twice.**

### The repo's token mechanism (verified, `globals.css`)
Tailwind v4, CSS-first. There are **three blocks** and a new token must appear in the right ones:

- **`:root { … }`** (lines 7-88) — raw **light-mode** hex values.
- **`.dark { … }`** (lines 91-130) — raw **dark-mode** hex overrides for the same variable names.
- **`@theme inline { … }`** (lines 132-214) — maps each raw var to a `--color-*` (or `--radius-*`, `--font-*`) entry. **This is what makes `bg-foo`, `text-foo`, `border-foo` classes exist.** If a token is only in `:root` and not mapped here, there is NO utility class for it.

**Rule:** every Meera color token = **3 edits** (`:root` value, `.dark` value, `@theme inline` map). Miss the `@theme inline` map and the class silently doesn't exist. Miss `.dark` and dark mode falls back to the light value.

### 3a. Add the §2 palette — do NOT overwrite existing tokens
⚠️ **Collision warning:** the spec's §2 uses generic names (`--accent`, `--success`, `--warning`, `--info`, `--border`) that **already exist** in this repo with *different* Lilac-Mist values (e.g. existing `--accent: #ede9fe` is a pale lilac surface; existing `--success: #ddf5e8`). If you redefine those names, you will silently restyle the **entire existing app** (dashboard, deal room, campaigns). **Do not.**

**Namespace every Meera token under `--meera-*`.** Add a dedicated block; leave the existing Lilac-Mist tokens untouched.

In `:root` (append a clearly-commented block near the end of `:root`, before line 88's closing context):
```css
  /* ── Meera AI Cofounder — money-serious palette (docs/AI connect/FRONTEND-BUILD-SPEC-MEERA.md §2) */
  /* 60% surfaces */
  --meera-bg: #F7F8FB;
  --meera-bg-subtle: #EEF1F6;
  --meera-surface: #FFFFFF;
  --meera-surface-2: #F2F4F9;
  /* 30% structure */
  --meera-border: #DDE3EC;
  --meera-border-strong: #C3CCDC;
  --meera-text: #0E1626;
  --meera-text-muted: #5B667C;
  /* 10% accent (default — overridden at runtime by useBrandTheme) */
  --meera-accent: #6D5AE6;
  --meera-accent-hover: #5B48D6;
  --meera-accent-press: #4C3BC2;
  --meera-accent-soft: #EDEAFB;
  --meera-accent-glow: rgba(109, 90, 230, 0.35);
  /* Trust — LOAD-BEARING. Never themed per-brand. */
  --meera-escrow: #12A150;
  --meera-escrow-soft: #E4F7EC;
  --meera-warning: #E8A317;
  --meera-danger: #E0344B;
  --meera-info: #2C7BE5;
  /* Brand theming input — default falls back to accent */
  --brand: #6D5AE6;
```

In `.dark` (append the dark overrides — same names, dark values from spec §2):
```css
  --meera-bg: #0B0F1A;
  --meera-bg-subtle: #111726;
  --meera-surface: #151C2C;
  --meera-surface-2: #1B2436;
  --meera-border: #25304A;
  --meera-border-strong: #33415F;
  --meera-text: #EAEEF6;
  --meera-text-muted: #93A0B8;
  --meera-accent-soft: #221E3F;
  --meera-escrow: #2BD576;
  --meera-escrow-soft: #123024;
  --meera-warning: #F4B740;
  --meera-danger: #FF5C72;
  --meera-info: #4C9BFF;
  /* accent/hover/press/glow/brand inherit :root unless a dark-specific value is needed */
```

In `@theme inline` (map every one so the utilities exist — follow the exact `--color-<name>: var(--<name>)` shape already used at lines 135-213):
```css
  --color-meera-bg: var(--meera-bg);
  --color-meera-bg-subtle: var(--meera-bg-subtle);
  --color-meera-surface: var(--meera-surface);
  --color-meera-surface-2: var(--meera-surface-2);
  --color-meera-border: var(--meera-border);
  --color-meera-border-strong: var(--meera-border-strong);
  --color-meera-text: var(--meera-text);
  --color-meera-text-muted: var(--meera-text-muted);
  --color-meera-accent: var(--meera-accent);
  --color-meera-accent-hover: var(--meera-accent-hover);
  --color-meera-accent-press: var(--meera-accent-press);
  --color-meera-accent-soft: var(--meera-accent-soft);
  --color-meera-escrow: var(--meera-escrow);
  --color-meera-escrow-soft: var(--meera-escrow-soft);
  --color-meera-warning: var(--meera-warning);
  --color-meera-danger: var(--meera-danger);
  --color-meera-info: var(--meera-info);
```
Now `bg-meera-surface`, `text-meera-text-muted`, `border-meera-border-strong`, `bg-meera-accent`, `text-meera-escrow`, etc. all exist as real utilities.

> `--meera-accent-glow`, `--brand`, and the raw `--meera-accent` are consumed as **CSS vars** (in `box-shadow`, gradients, and the runtime override) — they don't need a `--color-*` map unless you want a `bg-`/`text-` utility for them. Map `--meera-accent` (done above) because CTAs use `bg-meera-accent`; keep `--meera-accent-glow` var-only for `box-shadow: 0 0 0 4px var(--meera-accent-glow)`.

### 3b. `useBrandTheme` — runtime accent derivation (spec §2 overlay + §6)
The mechanism: inject the brand's scraped hex as **`--brand`** on the workspace root element, then set `--meera-accent` (+ hover/press/soft/glow) from it at runtime. **Only the accent layer** is themed. `--meera-escrow`, `--meera-danger`, `--meera-warning` are **never** touched.

- Set the vars via `element.style.setProperty('--meera-accent', derived)` on a ref'd wrapper (scope = workspace root only, so the rest of the app is unaffected).
- **Contrast guardrail (mandatory):** WCAG check the brand hex against `--meera-surface` (`#FFFFFF` light). If it fails ≥4.5:1 (text) / ≥3:1 (large UI), clamp lightness in OKLCH until it passes; if it's near-white/near-grey, fall back to the default `#6D5AE6`. The avatar uses a 2-stop gradient (`--brand` → +12% L).
- Put this in **`src/hooks/useBrandTheme.ts`** (repo hooks convention, matching `useInViewOnce.ts`) OR co-locate as `src/components/feature/meera/useBrandTheme.ts` if you prefer feature-scoping. Either is acceptable; pick one and be consistent with `useMeeraStage`.
- No new dependency needed for OKLCH — use a small inline conversion or CSS `color-mix()` where supported. **If you want a color lib, it must be approved by me first** (log in `wiki/tech/approved-deps.md`). Prefer zero-dep.

---

## 4. REUSE MAP — verified import paths

Every path below is confirmed present. Use these exact specifiers.

### Motion primitives — `src/components/motion/`
| Need | Import |
|---|---|
| Count-up | `import { CountUp } from '@/components/motion/CountUp'` (or barrel `@/components/motion`) |
| Stagger | `import { StaggerContainer, StaggerItem } from '@/components/motion/StaggerContainer'` |
| Entry fade | `import { FadeUp } from '@/components/motion/FadeUp'` · `import { WordReveal } from '@/components/motion/WordReveal'` |
| Escrow base | `import { EscrowFlowAnimation } from '@/components/motion/EscrowFlowAnimation'` — **extend into `EscrowLockSequence`; do not import for the hero directly.** It's a *scroll-pinned* explainer (uses `useScroll` over a 300vh track). The Meera hero is an *event-triggered* timeline (fill→lock→pulse→caption). Reuse its structure/reduced-motion pattern, build a new component. |
| Barrel | `import { FadeUp, StaggerContainer, StaggerItem, CountUp } from '@/components/motion'` (see `motion/index.ts`) |

Motion constants: `import { EASE_OUT, SPRING_MAGNETIC, STAGGER_DEFAULT, DURATION_NORMAL } from '@/lib/motion-config'`.

> ⚠️ **CountUp reconciliation.** The spec §5 asks for `useMotionValue(0)` + `useTransform` → `Math.round`. The existing `CountUp` (`motion/CountUp.tsx`) instead uses `requestAnimationFrame` + `useInViewOnce` + `useReducedMotion` and already: (a) snaps to final value under reduced motion, (b) runs once on in-view, (c) formats INR via `formatINR`. **This satisfies every acceptance criterion in the spec** (snap-on-reduced-motion, run-once, tabular money). **Reuse it as-is.** Do NOT rebuild it with `useMotionValue` just to match the spec's wording — the behavior contract is what matters, and it's already met. If a stage needs a non-INR format, pass `formatFn`/`prefix`.

### UI primitives — `src/components/ui/` and `src/components/shared/`
| Need | Import (verified location) |
|---|---|
| Verified badge (T4) | `import { VerifiedBadge } from '@/components/ui/verified-badge'` — note: **`ui/`, not `shared/`** (spec said shared) |
| Slot progress (Stage 5) | `import { SlotProgressBar } from '@/components/ui/slot-progress-bar'` (confirm export name when you open it) |
| Live indicator | `@/components/ui/hype-live-indicator` |
| Escrow status bar | `import { EscrowStatusBar } from '@/components/shared/escrow-status-bar'` — **`shared/`** |
| Stage colors | `import { statusToStage, stageBadgeClass } from '@/lib/stage-colors'` |
| Full shadcn set (~70) | `@/components/ui/*` — Button, Card, Badge, Avatar, ScrollArea, Sheet, Tooltip, Input, Textarea, Progress, etc. |
| Class merge | `import { cn } from '@/lib/utils'` · money format `import { formatINR } from '@/lib/utils'` |

> The **T1 Escrow Pill is NEW** (`ui/EscrowPill`). `escrow-status-bar.tsx` is a related-but-different component — read it for the money-state vocabulary, but build the pill fresh per spec §6.

### 3D pattern — `src/components/3d/`
Copy the **`HeroGlobe` + `HeroGlobeGate`** shape exactly (`3d/HeroGlobe.tsx`, verified):
- `<Canvas dpr={[1, 1.5]} gl={{ alpha, antialias:false, powerPreference:'high-performance' }}>`
- `<PerformanceMonitor onDecline={…} onIncline={…} />` from `@react-three/drei` drives a `degraded` state.
- Fallback is a **separate exported gate component** (`HeroGlobeGate`) that checks `useReducedMotion()` and renders `CanvasFallback` instead of the Canvas. Mirror this: `EscrowLockScene` (the Canvas) + `LockFallback` (SVG), and a gate that picks between them.
- Import the existing fallback pattern from `@/components/3d/CanvasFallback` for reference. Barrel: `@/components/3d` (`3d/index.ts`).
- **SVG lock is the default** per spec §5 — R3F is optional. Ship the SVG `LockFallback` first; the `<Canvas>` version is a stretch. **One WebGL context per page**; unmount it after Stage 4.

### Chat structure reference — `src/pages/brand-chat.tsx`
Read it for message-list + composer + ScrollArea + Sheet structure (the mobile canvas sheet mirrors its `<Sheet>` usage at `:1101`). **Meera's chat is AI-styled (chips, thinking states, brand avatar) — a distinct component tree**, not a fork of `brand-chat.tsx`. Reuse the *shape*, not the file.

---

## 5. FILE / FOLDER STRUCTURE TO CREATE

Create exactly this. `feature/` and `data/` are new top-level folders under `src/components/` and `src/` respectively (neither exists today — verified).

```
src/
├─ pages/
│  └─ brand-meera.tsx                    # NEW thin wrapper → <MeeraWorkspace/>
├─ data/                                 # NEW folder (no hardcoded content in components)
│  ├─ meera-copy.ts                      # all strings + mobile labels (Tejas voice, §3)
│  ├─ stage-config.ts                    # stage → {title, canvas} map (§4 table)
│  ├─ motion-tokens.ts                   # spring/ease/stagger constants for Meera (§5)
│  └─ meera-mock.ts                      # mock creators/brand/fees for mock-first build
├─ hooks/                                # EXISTS — add here (or co-locate, see §6)
│  ├─ useBrandTheme.ts                   # NEW — inject --brand, derive accent, contrast guard
│  └─ useMeeraStage.ts                   # NEW — function-call result → active stage
└─ components/
   ├─ feature/                           # NEW top-level bucket
   │  └─ meera/                          # ALL new Meera-specific components
   │     ├─ MeeraWorkspace.tsx           # 50/50 shell + seam + responsive controller
   │     ├─ MeeraChatPanel.tsx           # header + message list + composer + paywall state
   │     ├─ MessageBubble.tsx            # role-styled, entry motion
   │     ├─ ThinkingState.tsx            # T3 streaming step log
   │     ├─ Composer.tsx                 # input + chips + send; disabled/paused
   │     ├─ LivingCanvas.tsx             # right panel: mounted header + StageMorph body
   │     ├─ StageSnapshot.tsx            # Stage 1
   │     ├─ StageRecommend.tsx           # Stage 2
   │     ├─ StageMatching.tsx            # Stage 3
   │     ├─ StageFunding.tsx             # Stage 4 (hosts EscrowLockSequence)
   │     ├─ StageLive.tsx                # Stage 5
   │     ├─ PayoutLedger.tsx             # T9 approve→release rows
   │     └─ CreditPaywall.tsx            # soft empty-state wall (PRD §7)
   ├─ motion/                            # EXTEND existing
   │  ├─ EscrowLockSequence.tsx          # NEW — extends EscrowFlowAnimation pattern
   │  ├─ StageMorph.tsx                  # NEW — AnimatePresence mode="wait" wrapper
   │  └─ PulseAura.tsx                   # NEW — one-shot escrow-green aura
   ├─ 3d/                                # EXTEND existing (optional hero)
   │  ├─ EscrowLockScene.tsx             # NEW — single-<Canvas> low-poly lock (optional)
   │  └─ LockFallback.tsx                # NEW — SVG lock (DEFAULT, ship first)
   └─ ui/                                # ADD new primitives here (repo convention)
      ├─ EscrowPill.tsx                  # T1 money-state chip
      ├─ FeeBreakdown.tsx                # T5 line-item pool+fee+total
      ├─ QuickReplyChip.tsx
      ├─ BrandAvatar.tsx                 # brand-gradient ring + online dot
      ├─ StatPair.tsx                    # label + CountUp tile
      ├─ CreatorCard.tsx                 # verified creator tile
      └─ PayButton.tsx                   # accent CTA, scale(0.97) press
```

**Placement rationale:** generic, reusable primitives → `ui/` (matches existing `verified-badge`, `slot-progress-bar`). Meera-orchestration components → `feature/meera/`. Cross-cutting motion → `motion/`. This keeps `feature/meera/` a clean, deletable unit and lets any future surface reuse the `ui/` pieces.

---

## 6. REPO CONVENTIONS — match these exactly

| Convention | Rule (verified in repo) |
|---|---|
| **Import alias** | `@/` → `src/`. Always. No relative deep imports. |
| **Page wrappers** | `src/pages/*.tsx`, `export default function XxxPage()`, thin — just render the feature component. |
| **Component export** | Feature/ui components use **named exports** (`export function CountUp(...)`), pages use **default**. Match this: `export function MeeraWorkspace()`. |
| **File naming** | Two live conventions: `ui/` + shared use **kebab-case** (`verified-badge.tsx`, `escrow-status-bar.tsx`); `motion/` + `3d/` use **PascalCase** (`CountUp.tsx`, `HeroGlobe.tsx`). **Follow the folder's local convention:** new `ui/` files kebab-case (`escrow-pill.tsx`), new `motion/`/`3d/`/`feature/meera/` files PascalCase (`MeeraWorkspace.tsx`). *(This resolves the spec's ambiguity — the spec lists PascalCase names for ui/ items; override to kebab-case there to match neighbors.)* |
| **Typing** | TypeScript strict, **no `any`**. Props typed inline (`{ value }: { value: number }`) or via a named `type XxxProps = {…}`. Prefer explicit prop types on every exported component. Discriminated unions for stage state (`useMeeraStage`). |
| **Reduced motion** | `useReducedMotion()` from `framer-motion` in EVERY animated component; render the static branch first (see `StaggerContainer.tsx:41`, `HeroGlobe.tsx:145` for the exact early-return pattern). |
| **Colors** | Tokens only. Utilities like `bg-meera-surface`, `text-meera-escrow`. **Zero raw color** (`bg-indigo-500`, `#hex`, `text-[...]`). The existing `escrow-status-bar`/`stage-colors` already model this. |
| **Money** | `formatINR` from `@/lib/utils` for all ₹ amounts; `tabular-nums` on money/count elements (spec §7). |
| **`cn`** | `import { cn } from '@/lib/utils'` for conditional classes. |

---

## 7. RECOMMENDED BUILD ORDER

Matches spec §9, sequenced to de-risk (tokens and the theming hook first, because everything downstream consumes them):

1. **Tokens** — edit `globals.css` (all 3 blocks, `--meera-*` namespace) → §3a. Verify `bg-meera-surface` compiles in a throwaway div before moving on.
2. **`useBrandTheme`** + contrast guardrail (§3b). Prove the accent overrides at the workspace root and that green/danger stay fixed. Build the default `#6D5AE6` fallback path first.
3. **Shell** — `MeeraWorkspace` (50/50 + seam + `h-[calc(100vh-3.5rem)]`) → route in `App.tsx` (§1) → nav item + icon variant (§2) → responsive collapse controller.
4. **`src/data/`** — `meera-copy.ts`, `stage-config.ts`, `motion-tokens.ts`, `meera-mock.ts`. Nothing hardcoded downstream.
5. **Left panel** — `MeeraChatPanel`, `MessageBubble`, `ThinkingState` (T3), `Composer`, `QuickReplyChip`, `BrandAvatar` with a mock scripted conversation.
6. **Canvas + stages** — `LivingCanvas` + `StageMorph` (mounted header, morphing body), then Stages 1→5 wiring in `CountUp`, `StaggerContainer`, `CreatorCard`, `FeeBreakdown`, `EscrowPill`, `StatPair`. Cap stagger at 8 (spec §5).
7. **Hero moment** — `EscrowLockSequence` (extend `EscrowFlowAnimation` pattern) + `LockFallback` SVG (default) + `PulseAura`. Reduced-motion shows final locked state + caption immediately.
8. **Paywall + proactive** — `CreditPaywall`, inbound Meera nudge surface.
9. **Glue** — `useMeeraStage` mapping mock function-call results (`show_creators`, `calculate_budget`, `request_payment`, `confirm_launch`) → stage transitions. Mock-first; real APIs land post-M2 (`lib/api.ts` has no AI endpoints today — do not wait on backend).

Hand each slice to Kavya (QA) as it lands — don't batch the whole thing to the end of the pipeline (Build → Kavya → Meera/DevOps → Kabir → **Priya sign-off** → Swapnil).

---

## 8. GOTCHAS (concrete, repo-specific)

1. **No `tailwind.config.js`.** Do not create one, do not add colors in JS. If you catch yourself reaching for a config file, stop — the answer is always `globals.css` `@theme inline`.
2. **A token needs all 3 blocks.** `:root` + `.dark` + `@theme inline`. Skipping `@theme inline` = the utility class silently doesn't exist and your className is a no-op. This is the #1 way this task breaks.
3. **Don't reuse generic token names.** `--accent`/`--success`/`--warning`/`--info`/`--border` already exist app-wide with Lilac-Mist values. Redefining them restyles the whole app. Namespace `--meera-*`. (§3a)
4. **`--radius` is `0.625rem`** and `--radius-sm/md/lg/xl` already exist via `@theme inline` (`globals.css:165-168`). Align the spec's 8/12/16/24 radius intent to these existing vars; don't fork a parallel radius system.
5. **Layout height, not `100vh`.** You're inside `BrandLayout`'s `<main>` under a 14-unit header. Use `h-[calc(100vh-3.5rem)]` like `brand-chat.tsx:547`, or the canvas overflows and you get a double scrollbar.
6. **`EscrowFlowAnimation` is scroll-driven, the Meera hero is event-driven.** Don't drop the existing component into Stage 4 and expect a lock-click moment — it's a 300vh scroll explainer. Extend the *pattern* (reduced-motion handling, staged reveal), build a new timeline component.
7. **CountUp already meets the spec contract** — reuse it, don't rebuild with `useMotionValue` (§4). Rebuilding risks losing the reduced-motion snap that's already correct.
8. **One WebGL context, and it's optional.** SVG `LockFallback` is the default deliverable. The R3F `EscrowLockScene` is a stretch; if you add it, unmount after Stage 4 and never mount a second `<Canvas>` on the page.
9. **`verified-badge`, `slot-progress-bar`, `hype-live-indicator` are in `ui/`**, `escrow-status-bar` is in `shared/` — the spec's reuse table is slightly off on locations. The paths in §4 above are the verified ones.
10. **No new npm dependencies without my sign-off.** Everything needed (framer-motion, R3F, drei, three, shadcn, lucide) is already installed. If `useBrandTheme` tempts you toward a color library, do the OKLCH clamp inline or with `color-mix()` first. Any `npm install` must be logged in `wiki/tech/approved-deps.md` and approved by me.
11. **`src/app/brand/**`, root `app/brand/**`, and root `styles/globals.css` are DEAD** (Next scaffolds / unused shadcn grayscale). Ignore all three. The only theme file is `src/app/globals.css`.
12. **Icon-theme map.** After adding the nav item, add the `/brand/meera` case in `src/lib/icon-theme.ts` (`getBrandNavIconVariant`) or the icon uses only the fallback variant.

---

## 9. SIGN-OFF GATES (what I'll check at Priya review)

- [ ] Zero raw color classes — grep the `feature/meera` + new `ui/` files for `#`, `bg-[`, `text-[`, `-500`/`-600` etc. Must be clean.
- [ ] Every Meera token present in all 3 `globals.css` blocks; existing tokens untouched (diff shows only additions).
- [ ] `--meera-accent` derives from `--brand`; `--meera-escrow`/`--meera-danger`/`--meera-warning` never themed.
- [ ] `useReducedMotion()` in every animated component; count-ups snap; lock renders final state.
- [ ] One `<Canvas>` max; SVG fallback wired; DPR `[1,1.5]`.
- [ ] No content hardcoded in components — all from `src/data/*`.
- [ ] Motion constants imported from `data/motion-tokens.ts`, not inlined.
- [ ] 375px mobile: no overflow, canvas sheet works, Pay CTA reachable.
- [ ] No unapproved `npm install`.

Ship it clean. Ping me in `SHARED_CONTEXT.md` when the shell + tokens land — I'll do an early token-block review before you go deep on stages, so we catch any `@theme inline` mistakes before they propagate.

— Priya (CTO)
