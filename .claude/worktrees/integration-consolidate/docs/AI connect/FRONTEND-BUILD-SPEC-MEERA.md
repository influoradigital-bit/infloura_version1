# 🎨 FRONTEND BUILD SPEC — MEERA AI COFOUNDER (Brand-Side)

> **For:** Ananya (Frontend Developer)
> **From:** Swapnil (CEO), with Priya (CTO), Tejas (CMO), and design (`/design`, `/3d-cinematic-web`)
> **Date:** 2026-07-04 · **Milestone:** M2.5 · **Reference:** `docs/PRD-MEERA-AI-COFOUNDER.md`
> **Status:** APPROVED TO BUILD — this is your build sheet. Read top to bottom before writing code.

---

## 0. THE GOAL (Swapnil)

Build the **Meera AI Cofounder workspace** for the brand side. A brand signs up, Meera analyzes their website, and in one conversation takes them from *"I want to promote X"* to a **funded, live campaign** — while they watch the campaign build itself on screen.

The single job of this UI: **make a brand trust putting real money into escrow.** Our tagline — *"Create. Collab. Get Paid. Guaranteed."* — must be *felt*, not just read. The escrow-lock moment is where "Guaranteed" becomes visual.

We are the **first AI-first influencer platform in India.** This screen is the proof.

**What you are NOT doing:** rebuilding the app, touching the backend, or redesigning existing pages. Meera bolts onto the existing `BrandLayout` and reuses our motion/ui/3d libraries.

---

## 1. TECHNICAL GROUND TRUTH (Priya — read first)

Verified state of the repo. Do not deviate.

| Item | Reality |
|---|---|
| Stack | React 18 + **Vite** + TypeScript, **react-router-dom v7**, **Tailwind v4** (CSS-based, NO `tailwind.config`), shadcn/ui, Framer Motion, React Three Fiber |
| Entry | `src/main.tsx` → `src/App.tsx`. Real pages live in `src/pages/*.tsx` (thin wrappers) → components in `src/components/brand|creator/*` |
| Theme source of truth | **`src/app/globals.css`** (Tailwind v4 `@theme inline`, "Lilac Mist"). Design tokens live here. |
| ⚠️ DEAD CODE — DO NOT TOUCH | `src/app/brand/**` and root `app/brand/**` are unused Next.js scaffolds. Root `styles/globals.css` is default shadcn grayscale and is NOT used. Ignore all three. |
| Data | Everything is **mock/demo** today. Build Meera mock-first too — wire to real APIs after M2 backend lands. |

### Reuse map — these already exist, USE them (don't rebuild)
| Need | Already in repo |
|---|---|
| Count-up numbers | `src/components/motion/CountUp` |
| Staggered reveals | `motion/StaggerContainer` + `StaggerItem` |
| Entry fades | `motion/FadeUp`, `motion/WordReveal` |
| Escrow animation base | `motion/EscrowFlowAnimation.tsx` — **upgrade this into the lock-click hero, don't start from scratch** |
| Motion config | `src/lib/motion-config.ts` (springs, reduced-motion already handled) |
| 3D + performance pattern | `src/components/3d/*` — `PerformanceMonitor` + CSS fallback + DPR cap already established. Copy this pattern for any Meera canvas. |
| Chat message structure | `brand-chat.tsx` (Deal Room) — reuse bubble/scroll structure, but Meera is AI-styled (chips, thinking states), not a human DM |
| Escrow status | `shared/escrow-status-bar.tsx`, `lib/stage-colors.ts` |
| Primitives | Full shadcn `ui/` set (~70) + `verified-badge.tsx`, `slot-progress-bar.tsx`, `hype-live-indicator.tsx` |
| Layout shell | `BrandLayout` — add a "Meera" nav item to `navItems` in `brand-layout.tsx` |

---

## 2. COLOR SYSTEM — 60 / 30 / 10 (CEO decision reconciled)

**Decision:** Keep the app's Lilac Mist identity, but for the Meera workspace **deepen the accent** for money moments and **lock escrow-green as the one trust signal**. The Meera indigo (`#6D5AE6`) is a more confident sibling of our existing lilac (`#9b8cf2`) — continuity, but more serious where money is involved.

**The rule of the palette: 60% calm surfaces, 30% quiet structure, 10% accent + trust. Accent is for CTAs and interactive states ONLY — never decorative fills or dividers.**

### 60% — Dominant (surfaces / backgrounds)
Calm, low-chroma cool neutrals. "This product is serious about money."

| Token | Light | Dark | Use |
|---|---|---|---|
| `--bg` | `#F7F8FB` | `#0B0F1A` | App canvas / page base |
| `--bg-subtle` | `#EEF1F6` | `#111726` | Chat rail bg, recessed zones |
| `--surface` | `#FFFFFF` | `#151C2C` | Cards, canvas panels, message bubbles |
| `--surface-2` | `#F2F4F9` | `#1B2436` | Nested cards (fee breakdown, creator tiles) |

### 30% — Secondary (structure / support)
Borders, dividers, muted text, the 50/50 seam. Quiet, orderly = "auditable, engineered."

| Token | Light | Dark | Use |
|---|---|---|---|
| `--border` | `#DDE3EC` | `#25304A` | Hairlines, card borders |
| `--border-strong` | `#C3CCDC` | `#33415F` | The 50/50 divider, base focus ring |
| `--text` | `#0E1626` | `#EAEEF6` | Primary text |
| `--text-muted` | `#5B667C` | `#93A0B8` | Timestamps, thinking-state logs |

### 10% — Accent (Meera identity + CTAs) & Trust (semantic)
| Token | Value | Use |
|---|---|---|
| `--accent` (Meera indigo) | `#6D5AE6` | Primary CTA, Meera avatar ring, active chip, links |
| `--accent-hover` | `#5B48D6` | CTA hover |
| `--accent-press` | `#4C3BC2` | CTA active |
| `--accent-soft` | L `#EDEAFB` / D `#221E3F` | Meera bubble tint, tinted CTA bg |
| `--accent-glow` | `rgba(109,90,230,0.35)` | Focus glow, pre-lock aura |

**Trust semantics — green is load-bearing. `--escrow`/`--success` means "secured / released / verified" and NOTHING else.**

| Token | Light | Dark | Meaning |
|---|---|---|---|
| `--escrow` / `--success` | `#12A150` | `#2BD576` | Escrow secured, funds released, verified creator |
| `--escrow-soft` | `#E4F7EC` | `#123024` | Escrow pill bg, lock aura |
| `--warning` | `#E8A317` | `#F4B740` | Slots filling slow, credits low |
| `--danger` | `#E0344B` | `#FF5C72` | Payment failed, scrape failed |
| `--info` | `#2C7BE5` | `#4C9BFF` | Neutral live updates ("8 accepted") |

Why green: `#12A150` is the saturated "UPI-success / Razorpay-confirmed" green Indian users already read as *a safe, completed transaction*. Reserve it for the money guarantee so it becomes a learned trust cue.

### Brand-color theming overlay (per brand)
Each brand's workspace adopts **their** color, scraped into `brand_profiles.brand_aesthetic`.

- **Brand color drives ONLY the accent layer:** `--accent` (+ hover/press/soft/glow), Meera's avatar ring/gradient, active chips, count-up number color, campaign-card header.
- **Brand color MUST NOT override:** `--escrow`/`--success`, `--danger`, `--warning`, or the 60/30 base. **Trust colors are identical for every brand.**
- **Implementation:** inject brand hex as `--brand` at the workspace root; derive `--accent` at runtime. **Contrast guardrail:** WCAG check against `--surface` — if brand color fails (≥4.5:1 text / ≥3:1 large UI), clamp lightness in OKLCH until it passes; fall back to default `#6D5AE6` for near-white/near-grey brands. Avatar uses a 2-stop gradient (`--brand` → +12% L) so a flat color still reads dimensional. Build this as the `useBrandTheme` hook.

**Token rule:** define all of the above in `src/app/globals.css`, expose via Tailwind v4 `@theme inline`. **No raw Tailwind color classes in components** — tokens only.

---

## 3. TRUST-BUILDING UI PATTERNS (Ananya + Tejas)

Each answers a question the anxious brand is silently asking.

| # | Pattern | What it does | Where |
|---|---|---|---|
| T1 | **Persistent Escrow Pill** | Always-visible money-state chip: `Unfunded → Securing… → 🔒 ₹17,250 Secured → Releasing ₹1,000`. Green when locked. | Canvas header, all stages |
| T2 | **Escrow-Lock Hero** | Signature moment: wallet fills → padlock snaps shut → green pulse → *"₹17,250 secured. Released only on your approval."* This IS "Guaranteed." | Stage 4, on Razorpay success |
| T3 | **"Meera Shows Her Work"** | Never a blank spinner. Streaming log: `Scanning 300 creators → Filtering Mumbai → Ranking → Done (38 found)`, steps check off with a stagger. | Left chat + mirrored in canvas |
| T4 | **Verified-Creator Badge** | Instagram-OAuth tick in escrow-green, tooltip "Instagram-verified stats." | Stage 3 + 5 creator cards |
| T5 | **Transparent Fee Breakdown** | Line-itemed, nothing hidden: `Pool ₹15,000 + Fee (15%) ₹2,250 = ₹17,250`. | Stage 2→4, above Pay CTA |
| T6 | **Count-Up Numbers** | Reach/budget/creators animate 0→target (`Math.round`). Signals "calculated live for you." | Stages 2, 3, 5 |
| T7 | **Release-on-Approval copy** | Recurring micro-copy: *"Money moves only when you approve."* | Lock caption + per-payout |
| T8 | **Live Proof Mirroring** | Right panel updates the instant the brand asks ("make it Mumbai" → creators re-filter visibly). Proof over promise. | Stage 3 |
| T9 | **Payout Ledger Receipt** | Each release: `@creator ₹1,000 released ✓` + timestamp — a running receipt. | Stage 5 |

### Tejas — brand voice & trust copy (microcopy rules)
- **Meera speaks like a sharp, warm marketing partner** — never a corporate bot. Sentence case, contractions, verb-first CTAs. No "!", no "please", no "successfully".
- **Reinforce "Guaranteed" at the money moment**, not everywhere — one confident line at the lock (T7). Overusing it cheapens it.
- **CTAs name the action:** "Fund & go live", "Pay ₹17,250", "Approve & release" — never "Submit" / "OK".
- **Empty/paused state (credit paywall)** is an invitation, not an apology: *"Fund your first campaign to unlock me fully — or I'm back on the 1st."*
- **First-in-India edge** can appear once, subtly, on the workspace intro — a quiet confidence badge, not a banner.

---

## 4. THE 50/50 WORKSPACE LAYOUT

New route **`/brand/meera`**. Fixed split, full viewport height, `1px --border-strong` seam.

```
┌───────────────────────────┬───────────────────────────┐
│  LEFT — Meera (chat)  50%  │  RIGHT — Living Canvas 50% │
│  [avatar · online]         │  ┌ header: title · T1 pill┐│
│  ┌ messages ───────────┐   │  └────────────────────────┘│
│  │ Meera (accent-soft)  │  │                            │
│  │ Brand (surface)      │  │   [ stage-morphing body ]  │
│  │ thinking-state (T3)  │  │                            │
│  └──────────────────────┘  │                            │
│  [ quick-reply chips ]     │                            │
│  ┌ input ──────────── ↑ ┐  │   [ contextual CTA / Pay ] │
└───────────────────────────┴───────────────────────────┘
```

### Left panel — Meera chat
- Sticky header: brand-themed avatar (online dot), "Meera", subtitle "Your AI Cofounder".
- Message list: Meera bubbles `--accent-soft`; brand bubbles `--surface` + `--border`; inline thinking-state (T3).
- Quick-reply chips row above the composer.
- Composer: input + send. Paused variant renders the credit soft-paywall (PRD §7).

### Right panel — Living Canvas: the 5 stages it morphs through
| Stage | Trigger (function-call) | Canvas body |
|---|---|---|
| **1 · Snapshot** | chat opens (post site-analysis) | Brand card: logo, site preview, product tiles w/ prices, detected brand-color swatch. "Analyzing your business…" resolves into this. |
| **2 · Recommending** | `calculate_budget` / recommend | Campaign card assembles piece-by-piece: type badge, creator count, 72-hr window, **count-up** pool + reach (T6), fee breakdown (T5). |
| **3 · Matching** | `show_creators` | Verified creator grid **flies in staggered** (T4); live filter pills ("Mumbai · Skincare"); re-filters in place (T8); count-up "38 found → top 15". |
| **4 · Funding** | `request_payment` → paid | **Escrow-lock hero (T2):** wallet fills, padlock snaps, green pulse, secured caption (T7). Fee breakdown persists. |
| **5 · Live** | `confirm_launch` | Dashboard: invites-sent count-up, slot tracker (`8/15 accepted`), verified accepted list, per-creator approve→release ledger (T9). |

Canvas **header stays mounted** (title + T1 pill) while the body morphs — continuity, not page swaps.

### Responsive / mobile (<768px)
- Collapses to **full-screen chat** + a persistent bottom tab **"View campaign ↗"** with a live-update badge.
- Tap → canvas slides **up as a sheet (~90vh)**, same stages stacked vertically, same motion, full-width.
- Pay CTA pinned to a safe-area-aware bottom bar in the sheet.
- Tablet (768–1024px): keep split but canvas 55% / chat 45% so the creator grid keeps 2 columns.

---

## 5. MOTION SPEC (`/3d-cinematic-web` presets)

Standard spring `{ stiffness: 60, damping: 18 }` · entry ease `[0.23, 1, 0.32, 1]` · stagger `delay: 0.15 + i * 0.10` · entry `{opacity:0, y:12, scale:0.9} → {1,0,1}`. **Constants live once in `src/data/motion-tokens.ts` — never inline them.**

| Element | Motion |
|---|---|
| **Message entry** | Bubble `opacity 0→1, y 8→0`, spring, ~260ms. Meera "typing" = 3-dot loop → T3 log lines stagger in, each with a check-mark swap on completion. |
| **Creator cards (Stage 3)** | `StaggerContainer`/`StaggerItem`, `delay: 0.15 + i*0.10`. **Cap animated batch at 8** (anti-pattern: no stagger >8); rest fade in. Re-filter via `AnimatePresence` layout — exit fade+scale 0.95, enter stagger. |
| **Count-ups (T6)** | `useMotionValue(0)` + `useTransform` → `Math.round`, ~900ms ease. Never `useState`. Runs once on stage-enter. |
| **Escrow-lock (T2)** | Timeline ~1.6s: (a) meter fill 0→100% 700ms ease-out; (b) padlock shackle drops, spring + 4px settle; (c) `--escrow-soft` aura pulse once; (d) caption `FadeUp`. Optional R3F: single low-poly lock in one `<Canvas>` (DPR `[1,1.5]`, PerformanceMonitor + fallback). **SVG lock is the default** — no WebGL required to land the moment. Upgrade from existing `EscrowFlowAnimation.tsx`. |
| **Stage transitions** | Canvas body `AnimatePresence mode="wait"`: out `opacity 1→0, y 0→-8` (~180ms), in `FadeUp`. Header stays mounted. |
| **Pay CTA** | Press `scale(0.97)` ease-out (NOT a spring). Hover: accent→accent-hover, no scale. Focus: `--accent-glow` ring. |
| **Chips** | Hover: border→accent, bg→accent-soft. Select: fill accent-soft, text accent. No scale. |

### Reduced motion (`useReducedMotion()` / `prefers-reduced-motion`) — mandatory
- All animations bypassed. Cards appear instantly (opacity only).
- **Count-ups snap to final value** (no tween).
- **Escrow-lock renders final locked state + caption immediately** (single 150ms aura fade). The trust moment must stay legible without motion.
- Stage transitions become instant swaps.

### Performance (skill quality gate)
One WebGL context per page (the lock is the only optional `<Canvas>`; unmount after Stage 4). DPR `[1,1.5]`; `PerformanceMonitor onDecline` → SVG lock. No layout shift (reserve dimensions). Mobile Lighthouse ≥85. Dispose 3D on unmount; `AnimatePresence` for all conditional mounts.

---

## 5A. VOICE & LIVING PRESENCE (added by Swapnil — build in M2.5)

Two features that make Meera feel like a real, alive cofounder. **Core principle: voice-first with a text safety net — voice is the magic, text is the guarantee it always works.**

### A. Living-presence animation (Meera feels alive)
A small, corner-docked animated Meera avatar — her *presence indicator*. Themed in the brand's accent color.
- **Idle:** gentle breathing/pulse — she's there, waiting.
- **Thinking:** ring shimmer / the T3 "shows her work" dots run.
- **Talking:** soft audio-style waveform (or mouth-pulse) animated in sync with reply streaming / TTS playback.

Rules: **subtle and corner-docked, not a big cartoon face** — a tasteful pulsing presence reads premium; an animated face reads toy/uncanny. SVG + Framer Motion, no extra API cost. Full `prefers-reduced-motion` bypass → static avatar with a simple online dot.

### B. Voice output — combo with graceful fallback
Meera can **speak her replies (TTS)** with the talking waveform synced. But this is a *combo*: **if voice fails at any point — mic/audio unsupported, TTS error, user in a noisy place, permission denied — Meera silently falls back to the text chat with zero dead ends.** Text is always fully functional on its own. Voice is an enhancement layered on top, never a requirement.
- Provide a persistent voice on/off toggle. Remember the choice (localStorage in-app equivalent / user setting).
- Never block a reply on audio — text renders immediately; audio plays alongside if enabled and working.

### C. Voice input — fix grammar, edit-first
Tap-and-talk mic in the composer.
- Flow: **record → speech-to-text (support Hinglish / mixed Hindi-English) → clean to grammatically-correct English → show the polished text in the composer for the user to tweak → they send.**
- **Meaning-preservation is non-negotiable:** cleanup fixes grammar and clarity only, never reinterprets intent. Example: "10 creator Mumbai serum promote" → "Promote the serum with 10 Mumbai creators" (same intent, cleaner).
- Show edit-first (not auto-send) so the brand trusts what Meera heard. Later we can offer an auto-send toggle once trust is established.
- Mic states: idle → listening (waveform) → transcribing → editable result. If STT fails → "Didn't catch that — type it instead?" (fall back to text, no dead end).

### Cost note (for Rohan)
Voice adds a **speech-to-text step + a small grammar-cleanup pass** per voice message, and **TTS** per spoken reply. Small but not free — **voice actions spend credits like any other Meera action**, and Rohan folds STT/TTS into the cost model. Text-only usage stays at the ₹17–31/brand-month baseline.

### Components (add to §6)
- `MeeraPresence` **[NEW]** — corner-docked animated avatar (idle/thinking/talking states), brand-themed, reduced-motion static fallback
- `VoiceWaveform` **[NEW]** — audio-style waveform used by presence-talking + mic-listening
- `MicButton` **[NEW]** — composer mic: idle→listening→transcribing states
- `VoiceToggle` **[NEW]** — persistent speak-replies on/off
- `useVoiceInput` **[NEW]** — STT + Hinglish handling + grammar-cleanup + edit-first result; graceful fallback to text
- `useVoiceOutput` **[NEW]** — TTS playback synced to `MeeraPresence`; auto-disables + falls back on any failure

### Acceptance additions (fold into §9 DoD)
- [ ] Text chat is fully functional with voice OFF or failed — no dead ends anywhere
- [ ] Voice input shows editable cleaned text before sending; meaning never altered
- [ ] Hinglish input transcribes and cleans to correct English
- [ ] `prefers-reduced-motion` → presence avatar is static (online dot only)
- [ ] Voice actions metered against credits; text-only baseline unchanged

---

## 6. COMPONENTS TO BUILD

Legend: **[NEW]** build it · **[REUSE]** already exists · **[EXTEND]** upgrade existing.

### `src/components/motion/`
- `CountUp` **[REUSE]** · `StaggerContainer`/`StaggerItem` **[REUSE]** · `FadeUp` / `WordReveal` **[REUSE]**
- `EscrowLockSequence` **[EXTEND** from `EscrowFlowAnimation.tsx]** — fill→lock→pulse→caption; SVG default, R3F-optional
- `StageMorph` **[NEW]** — `AnimatePresence mode="wait"` wrapper for canvas stage swaps
- `PulseAura` **[NEW]** — one-shot escrow-green aura (lock + release events)

### `src/components/3d/` (optional hero only)
- `EscrowLockScene` **[NEW]** — single-`<Canvas>` low-poly lock, DPR-capped, PerformanceMonitor (copy existing 3d/ pattern)
- `LockFallback` **[NEW]** — SVG/CSS lock for WebGL-decline / reduced-motion

### `src/components/ui/`
- `VerifiedBadge` **[REUSE** `verified-badge.tsx]** · slot progress **[REUSE** `slot-progress-bar.tsx]**
- `EscrowPill` **[NEW]** — money-state chip (T1); variants unfunded/securing/secured/releasing
- `FeeBreakdown` **[NEW]** — line-item pool + fee + total (T5)
- `QuickReplyChip` **[NEW]** · `BrandAvatar` **[NEW]** (brand-gradient ring + online dot)
- `StatPair` **[NEW]** — label + `CountUp` tile · `CreatorCard` **[NEW]** — verified creator tile
- `PayButton` **[NEW]** — accent CTA, `scale(0.97)` press, loading→success

### `src/components/feature/meera/` (all NEW)
- `MeeraWorkspace` — 50/50 shell, seam, responsive collapse controller
- `MeeraChatPanel` — header + message list + composer + credit-paywall state
- `MessageBubble` — role-styled, entry motion · `ThinkingState` — streaming step log (T3)
- `Composer` — input + chips + send; disabled/paused variants
- `LivingCanvas` — right panel: header (title + `EscrowPill`) + `StageMorph` body
- `StageSnapshot` (1) · `StageRecommend` (2) · `StageMatching` (3) · `StageFunding` (4) · `StageLive` (5)
- `PayoutLedger` — per-creator approve→release rows (T9)
- `CreditPaywall` — soft empty-state wall
- `useMeeraStage` — hook mapping function-call results → active stage
- `useBrandTheme` — inject `--brand`, derive accessible accent, contrast guardrail

### `src/data/` (no hardcoded content in components)
- `meera-copy.ts` (all strings, mobile labels) · `stage-config.ts` (stage→title/canvas map) · `motion-tokens.ts` (spring/ease/stagger constants)

---

## 7. TYPOGRAPHY & SPACING

**Fonts:** UI/body **Inter** (already in repo — `--font-sans`). Display/hero numbers: **Space Grotesk** (headlines + big count-ups only). Enable `font-variant-numeric: tabular-nums` on all money/count elements. Pair with **Noto Sans Devanagari** so ₹ + any Hindi register cleanly.

**Type scale** (rem): `display` 40/44 wt600 (lock caption, hero stat) · `h1` 30/36 (stage titles) · `h2` 24/30 (card title) · `h3` 20/26 · `body-lg` 16/26 (Meera messages) · `body` 14/22 · `caption` 12/18 wt500 (timestamps, fee rows, thinking-log) · `mono-num` 14/20 tabular (ledger, fees).

**Radius:** `sm 8px` (chips/badges/inputs) · `md 12px` (bubbles/small cards) · `lg 16px` (canvas/creator cards) · `xl 24px` (canvas panels, lock module) · full (avatar/pill). Note: repo base `--radius` is `0.625rem` — align these to it.

**Spacing:** `xs 4 · sm 8 · md 16 · lg 32 · xl 64 · 2xl 128`. Canvas inner padding `lg` desktop / `md` mobile. Message max-width `~72ch`. Creator grid 2-col desktop (`md` gutter), 1-col mobile.

---

## 8. FEATURES MISSING / GAP LIST (build these — none exist today)

From Priya's audit, the entire Meera surface is greenfield. Concretely:

1. **`/brand/meera` route + page** + "Meera" nav item in `BrandLayout`
2. **Meera AI chat panel** (chips, thinking states, brand-themed avatar) — distinct from the human Deal Room `brand-chat.tsx`
3. **Living Canvas** (5-stage morphing right panel)
4. **Website-analysis onboarding step** — add website-URL required field + "Analyzing your business…" state to the signup/onboarding flow (`brand-onboarding.tsx` has no URL field today)
5. **Brand-accent theming** (`useBrandTheme`) — no dynamic-accent mechanism exists (theme is static)
6. **Escrow-lock hero** — upgrade `EscrowFlowAnimation.tsx`
7. **Credit-meter + soft-paywall UI** (PRD §7) — none exists
8. **Function-call → canvas glue** — frontend handlers for `show_creators`, `calculate_budget`, `create_campaign`, `request_payment`, `confirm_launch` that drive stage transitions (`lib/api.ts` has no AI endpoints — mock these first)
9. **Proactive-message surface** — inbound Meera nudges in the notification/chat surface

---

## 9. BUILD ORDER & ACCEPTANCE

### Suggested order
1. **Tokens first** — add the §2 palette + brand-theming vars to `src/app/globals.css`; expose via `@theme inline`. Build `useBrandTheme` + contrast guardrail.
2. **Shell** — `MeeraWorkspace` 50/50 + route + nav item + responsive collapse.
3. **Left panel** — `MeeraChatPanel`, `MessageBubble`, `ThinkingState`, `Composer`, chips (mock conversation).
4. **Canvas + stages** — `LivingCanvas` + `StageMorph`, then Stages 1→5 reusing motion/ui primitives.
5. **Hero moment** — `EscrowLockSequence` (extend `EscrowFlowAnimation`) + SVG fallback.
6. **Credit paywall** + proactive surface.
7. **Mock function-call glue** (`useMeeraStage`) so stages advance from simulated tool calls.

### Definition of done (per `/3d-cinematic-web` pre-ship checklist)
- [ ] All colors reference tokens — zero raw Tailwind color classes
- [ ] `--accent` derives from `--brand`; green/danger/warning never themed
- [ ] `useReducedMotion()` bypasses every animation; count-ups snap; lock shows final state
- [ ] One WebGL context; `PerformanceMonitor` + SVG fallback wired; DPR `[1,1.5]`
- [ ] No content hardcoded in components — comes from `src/data/*`
- [ ] Mobile tested at 375px — no overflow, canvas sheet works, Pay CTA reachable
- [ ] Lighthouse ≥85 mobile; no layout shift
- [ ] Motion constants imported from `data/motion-tokens.ts` (not inlined)

### Pipeline
Build → **Kavya** (QA) → **Meera/DevOps** (build verify) → **Kabir** (security) → **Priya** (sign-off) → **Swapnil** (final review). Nothing ships to brands without Priya + my sign-off.

---

## 10. ONE-LINE FOR ANANYA

> Build `/brand/meera`: a fixed 50/50 workspace — Meera chats left, the campaign builds itself right through 5 cinematic stages, ending in the escrow-lock that makes "Guaranteed" real. Reuse our motion/ui/3d libraries, theme the accent per brand, keep escrow-green sacred, and never break reduced-motion. Mock-first, tokens-only, ship it clean.
