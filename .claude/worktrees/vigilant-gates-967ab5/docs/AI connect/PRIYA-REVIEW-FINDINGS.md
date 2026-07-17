# 🏗️ PRIYA — SIGN-OFF REVIEW FINDINGS: MEERA AI COFOUNDER (Frontend)

> **From:** Priya (CTO) · **Milestone:** M2.5 · **Date:** 2026-07-04
> **Builder:** Ananya (Frontend) · **Pipeline step:** Priya sign-off gate (post-Tara audit)
> **Method:** Full-tree read (1M context) against `FRONTEND-BUILD-SPEC-MEERA.md`, `PRIYA-ARCH-HANDOFF-MEERA.md`, `TARA-BUILD-REPORT-MEERA.md`. `npx tsc --noEmit` re-run by me.
> **Verdict:** ⛔ **CHANGES REQUESTED.** The token/architecture layer is clean and the handoff was followed faithfully — but there is a **Blocker that kills the signature escrow-lock moment** (the entire point of this screen) plus two trust-color leaks Tara's grep could not catch because they live in *reused* primitives. Fix the Blocker + two Majors and this passes.

---

## SEVERITY COUNT

| Severity | Count |
|---|---|
| 🔴 Blocker | 1 |
| 🟠 Major | 4 |
| 🟡 Minor | 4 |
| ⚪ Nit | 3 |

---

## 🔴 BLOCKERS

### B1 — Paying skips the escrow-lock hero (T2). The "Guaranteed" moment never renders.
**Files:** `src/components/feature/meera/MeeraWorkspace.tsx:26-30`, `src/hooks/useMeeraStage.ts:33-37`, `src/components/feature/meera/LivingCanvas.tsx:22-26,55`

**What's wrong:** The whole screen exists to make the escrow-lock moment felt (spec §0, §4 Stage 4, T2). But the pay handler advances the stage *off* the funding stage the instant payment resolves, so the hero unmounts before it can play:

```ts
// MeeraWorkspace.tsx
const handlePay = async () => {
  await new Promise((r) => setTimeout(r, 900))
  markPaid()               // isPaid = true
  advance('confirm_launch')// ← CALL_TO_STAGE['confirm_launch'] = 'live'  → stage jumps to 'live'
}
```

`LivingCanvas` only renders `<StageFunding paid={isPaid}>` when `stage === 'funding'` (L55), and `StageFunding` only mounts `<EscrowLockSequence>` when `paid === true` (`StageFunding.tsx:24`). But by the time `isPaid` flips true, `stage` is already `'live'`, so `StageFunding` (and the lock hero) is unmounted in the same commit. The user pays and is teleported to the Live dashboard — the fill→lock→pulse→caption timeline shows for ~0 frames.

**Same root cause, second symptom:** the `EscrowPill` "secured" state is unreachable. `escrowStateForStage` returns `'secured'` only when `stage === 'funding' && isPaid` (`LivingCanvas.tsx:23`). That combination never exists, so the pill goes straight `securing → releasing` and the "🔒 Secured ₹17,250" trust state (T1, spec §3 table) is dead code.

**Concrete fix:** Payment success must land on (and hold) the funding stage with the lock playing, and only *then* advance to live on an explicit `confirm_launch` (the "Approve & release" / launch action). Two-step it:
1. In `useMeeraStage`, set `isPaid` on the **payment** signal, not on `confirm_launch`. Add a `markPaid()`-driven path (already exists) and stop tying `isPaid` to `confirm_launch` (`useMeeraStage.ts:36`).
2. In `handlePay`, call `markPaid()` **only** (stay on `funding`, `isPaid=true` → lock hero plays, pill shows `secured`). Move `advance('confirm_launch')` to a separate "go live" affordance fired after the hero (e.g. a CTA in `StageFunding` post-lock, or a timeout matching `MEERA_LOCK_TIMELINE.totalMs`).
3. Verify: after Pay, `StageFunding` stays mounted, `EscrowLockSequence` runs, pill reads `Secured`, then transition to `live`.

This is the difference between shipping the product's thesis and shipping a screen that pays and skips the payoff.

---

## 🟠 MAJOR

### M1 — Verified-creator tick renders BLUE, not escrow-green (T4 + trust-color violation).
**File:** `src/components/ui/verified-badge.tsx:18` (consumed by `creator-card.tsx:47` in Stages 3 & 5)

Spec T4 (§3) is explicit: "Instagram-OAuth tick in **escrow-green**." The reused `VerifiedBadge` renders `<BadgeCheck className="... text-info-foreground" />` — the Lilac-Mist **blue** info token. Inside the Meera workspace every verified creator shows a blue tick, breaking the learned green = "secured / verified" trust cue the entire color system is built around (spec §2 "green is load-bearing").

Tara marked T4 ✅ because the badge is *consumed* — but nobody checked its color. It's a shared component, so don't hard-recolor it globally (it may be intentional blue elsewhere). **Fix:** add a variant/prop (e.g. `tone?: 'info' | 'escrow'`) or wrap it Meera-side so the tick uses `text-meera-escrow` within the workspace. Confirm the tooltip copy ("Instagram-verified stats", spec T4) too — current default label is fine.

### M2 — Slot tracker renders in CYAN "Hype" colors inside the Meera surface (color-system violation).
**File:** `src/components/ui/slot-progress-bar.tsx:17,25,31` (consumed by `StageLive.tsx:24`)

`SlotProgressBar` is hardcoded to `bg-hype`, `bg-hype-solid`, `text-hype-foreground`, `text-muted-foreground` — the cyan Hype-campaign palette and generic app tokens, none of them Meera tokens. Dropped into Stage 5, the slot bar is a cyan stripe in an indigo/green money UI. This violates DoD #1 ("all colors reference tokens") for the Meera surface. Tara's grep only scanned `feature/meera` + `escrow-pill`, so reused `ui/` primitives pulled into stages slipped the net — this is exactly the cross-file leak the 1M read is for.

**Fix:** parametrize `SlotProgressBar` with a tone/variant (accept `trackClass`/`fillClass` or a `tone` prop) and pass Meera tokens (`bg-meera-surface-2` track, `bg-meera-accent` or `bg-meera-escrow` fill, `text-meera-text-muted` caption) from `StageLive`. Do not recolor the shared component globally — Hype pages still need cyan.

### M3 — PayButton hardcodes English UI strings; DoD #5 ("no content hardcoded") violated.
**File:** `src/components/ui/pay-button.tsx:42`

`{status === 'idle' ? label : status === 'loading' ? 'Securing…' : 'Secured'}` inlines `'Securing…'` and `'Secured'` literally. Everything else in this build correctly pulls from `src/data/meera-copy.ts` (which even has `MEERA_ESCROW_PILL_LABEL.securing = 'Securing…'`). **Fix:** source both labels from `meera-copy.ts`. Also add `aria-live="polite"` to the button (or an inner status span) so screen-reader users hear the loading→secured transition — right now the state change is silent.

### M4 — Meera trust-caption depends on `FadeUp`, which fails `tsc --noEmit`.
**Files:** `src/components/motion/EscrowLockSequence.tsx:6,69` → `src/components/motion/FadeUp.tsx:32`

`tsc --noEmit` (re-run by me) reports 2 errors, both in `FadeUp.tsx` / `WordReveal.tsx` (framer-motion generic `children: never` strictness in the reduced-motion branch). Tara is right that these are **pre-existing** and Ananya didn't author them. But Ananya *chose* to render the T2 lock caption through `FadeUp` (`EscrowLockSequence.tsx:69`), so the signature moment's render path now rides a component that doesn't typecheck. If CI gates on `tsc` (it should), the Meera slice is red by proxy — "clean for Meera" is slightly generous.

**Fix (either):** (a) fix `FadeUp`/`WordReveal` generic typing so the tree is green (preferred — small, unblocks everyone), or (b) swap the caption to a plain `motion.p` with the `MEERA_EASE_ENTRY` token so Meera doesn't inherit a red dependency. Do not ship with a red `tsc`.

---

## 🟡 MINOR

### m1 — Reduced-motion aura never actually fades in.
**File:** `src/components/motion/PulseAura.tsx:16-24`

Spec §5 reduced-motion: the lock should show "a single 150ms aura fade." The reduced branch renders the aura at `opacity-0` with a `transition: opacity 150ms` but nothing ever raises the opacity, so under `prefers-reduced-motion` there is *no* aura at all — just a static locked SVG. **Fix:** mount at `opacity-0` then set to a visible opacity on next frame (or render a brief keyframed fade) so the 150ms cue is honored. Legibility is fine either way; this is spec fidelity, not a break.

### m2 — `MeeraChatPanel` nested `setTimeout` has no cleanup (state-after-unmount risk).
**File:** `src/components/feature/meera/MeeraChatPanel.tsx:48-51`

The outer play timer is cleaned up (L54), but the inner `window.setTimeout(() => setThinkingKey(null), 1600)` inside the `showThinking` branch is never cleared. Unmount the workspace mid-script (navigate away during a thinking step) and it calls `setState` on an unmounted component. **Fix:** track the inner timer id and clear it in the effect cleanup, or lift the thinking-clear into the main scripted timeline.

### m3 — Count-up formatters show misleading intermediate frames.
**Files:** `src/components/feature/meera/StageRecommend.tsx:28`, `StageMatching.tsx:31-32`, `StageLive.tsx:20-21`

Reach uses `formatFn={(n) => \`${(Math.round(n)/1000).toFixed(0)}k\`}`. During the ~900ms tween this reads `0k` for most of the animation (values <500 round to `0k`) then jumps to `420k` near the end — the "calculated live" effect (T6) reads as a stall-then-snap. Counts (`38`, `15`) are fine. **Fix:** for the reach tile, either count in thousands (`value={420}` + `k` suffix) so the tween is smooth, or use a formatter that interpolates the `k` value continuously. Cosmetic, but it undercuts the T6 "live math" trust signal.

### m4 — `EscrowLockSequence` double-drives the meter width (state + framer transition).
**File:** `src/components/motion/EscrowLockSequence.tsx:53-58`

The meter animates via BOTH a `meterPct` state flip (0→100 on rAF) AND a framer `animate={{ width }}` with its own duration. It happens to work because both target 100%/700ms, but it's two sources of truth for one animation. **Fix:** pick one — drive width purely with framer `initial/animate` (drop the `meterPct` state), or drive it purely with state + CSS transition. Not a bug today; a maintenance trap.

---

## ⚪ NITS

### n1 — Dead node in `EscrowPill`.
`src/components/ui/escrow-pill.tsx:49` renders `<ShieldCheck className="hidden h-0 w-0" />` — an always-hidden zero-size icon that does nothing. Remove it (and the now-unused `ShieldCheck` import).

### n2 — `BrandAvatar` doc/impl mismatch; `useBrandTheme` gradient outputs are dead.
`src/components/ui/brand-avatar.tsx:30` documents a `--brand → +12% L` 2-stop gradient (spec §2, handoff §3b) but actually uses `--meera-accent → --meera-accent-hover`. Meanwhile `useBrandTheme.ts:126,132-133` computes `gradientStart`/`gradientEnd` and returns them — but nothing consumes them. Either wire the avatar to the intended `--brand` gradient (spec-correct) or drop the unused gradient fields from `BrandThemeResult`. Cosmetically fine today because accent ≈ brand; diverges the moment a brand color is clamped.

### n3 — `nextStage` / `STAGE_ORDER` helpers unused.
`src/data/stage-config.ts:62-68` exports `STAGE_ORDER` + `nextStage()` that no consumer imports (stage flow is driven by `CALL_TO_STAGE`). Fine to keep as intended seam for the real dispatcher, but flag as currently-dead so it's not mistaken for the live path. (If B1 is fixed via an explicit next-stage step, `nextStage` may become the right tool — consider using it there.)

---

## ✅ WHAT PASSED (verified clean in my read)

- **Token namespacing (handoff §3, the #1 risk):** all 17 `--meera-*` tokens present in **all three** blocks — `:root` (`globals.css:91-113`), `.dark` (`158-171`), `@theme inline` (`259-275`). No `@theme inline` typos → every `bg-meera-*`/`text-meera-*`/`border-meera-*` utility resolves. No generic Lilac-Mist token (`--accent`/`--success`/`--border`/etc.) redefined → existing app not restyled. Dark block correctly omits only the accent/hover/press/glow/brand vars that intentionally inherit `:root`.
- **Escrow-green sacredness (load-bearing):** `useBrandTheme.ts:151-157` sets only `--brand` + `--meera-accent*`; `--meera-escrow`/`-danger`/`-warning` are never written. Contrast guardrail is real (WCAG luminance + ratio + iterative darken + near-white/grey fallback to `#6D5AE6`); the RGB-multiplier clamp (vs literal OKLCH) is the deviation I already permitted (handoff §3b/§10) — behavior contract met.
- **Reduced-motion coverage:** every animated component gates on `useReducedMotion` — `MessageBubble`, `ThinkingState`, `StageMatching`, `StageMorph`, `EscrowLockSequence`, `PulseAura`, `LockFallback`, `QuickReplyChip`, reused `CountUp`/`StaggerContainer`. Count-ups seed `display` to final value (`CountUp.tsx:30`); lock renders final pose immediately (`LockFallback.tsx:18`). Only gap is m1 (reduced aura fade).
- **No raw color classes in Meera-authored files:** `feature/meera/*` and new `ui/` primitives use tokens only. (The two color leaks M1/M2 are inside *reused* shared primitives, not the Meera files.)
- **Motion constants imported, not inlined:** `StageMorph`, `EscrowLockSequence`, `MessageBubble`, `StageMatching` all consume `src/data/motion-tokens.ts`. (Minor inline durations remain in `ThinkingState.tsx:60` `delay: 0.15 + i*0.1` and `PulseAura.tsx:33` — acceptable, but could pull from tokens for full consistency.)
- **One WebGL context:** no `<Canvas>` mounted anywhere in the slice (SVG `LockFallback` is the default per spec §5). Rule trivially satisfied; if R3F `EscrowLockScene` is greenlit later, DoD #4's PerformanceMonitor/DPR gate becomes live and must be re-reviewed.
- **Wiring / no regressions:** route `App.tsx:7,166-173` inside the protected group, ahead of the `/:handle` + `*` catch-alls. Nav item `brand-layout.tsx:19,70` (Sparkles, flagship position 2). Icon-theme `icon-theme.ts:6` maps `/brand/meera → 'primary'`. `isActive` default handles it. No existing route touched.
- **No content hardcoded** except M3 (`PayButton`). All copy from `meera-copy.ts`, all data from `meera-mock.ts`.
- **`tsc --noEmit`:** 2 errors, both pre-existing in `FadeUp.tsx`/`WordReveal.tsx` (see M4). Zero errors originate in a Meera file.
- **5 stages + StageMorph:** all five present, wired in `LivingCanvas.tsx:52-56`, header stays mounted, body morphs via `AnimatePresence mode="wait"` — spec §4 "continuity, not page swaps" met.

---

## SIGN-OFF DECISION

**Not signed.** Fix **B1** (mandatory — it defeats the product thesis), **M1** and **M2** (mandatory — trust-color integrity is the whole spec), **M3** and **M4** (mandatory before merge — DoD + green build). Minors/Nits are follow-ups, not blockers. Re-submit B1+M1+M2+M3+M4 and I'll sign.

Still owed to the pipeline (route to Meera/DevOps, not Ananya's defect): **DoD #7 Lighthouse ≥85 mobile** — no artifact exists anywhere; unverified.

— Priya (CTO)
