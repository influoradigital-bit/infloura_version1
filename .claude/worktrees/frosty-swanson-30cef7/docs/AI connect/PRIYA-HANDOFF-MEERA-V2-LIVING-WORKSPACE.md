# 🏗️ PRIYA → ANANYA — MEERA v2: THE LIVING WORKSPACE (Beat Alippo)

> **From:** Priya (CTO) · **To:** Ananya (Frontend) · **Date:** 2026-07-05 · **Milestone:** M2.5+
> **Context:** Swapnil reviewed Alippo's AI-cofounder reel. Their split-screen "AI talks left / store builds right" is the same pattern you already shipped (`ANANYA-BUILD-NOTES.md`). This handoff is the **v2 layer that makes ours clearly better** — not a rebuild. Build ON the existing slice; touch nothing that works.
> **Reference:** `FRONTEND-BUILD-SPEC-MEERA.md` (§5A voice, §3 trust patterns) + the shipped components in `src/components/feature/meera/`.

---

## 1. WHAT ALIPPO DOES (observed, frame-by-frame)

From the screen recording — their AI Cofounder UI:
1. **Left:** a circular gradient logo with animated **equalizer/sound-wave bars**, label "AI Cofounder is speaking." Below it, chat replies rendered as **compact task cards** — "Monsoon makeover · Draft", "Rain-care note" with mini sub-action chips ("Wipe dry", "Airtight pouch"), a counter badge ("22").
2. **Right:** the actual store, **morphing live** — homepage → hero swaps to "Rain, reimagined" → collection page renders (products + prices) → drills into a single product detail page (₹2,399, Add to Cart). A "Published"-style badge signals it's the real live site.

It's good. But it has clear weaknesses we exploit below.

---

## 2. WHERE WE BEAT THEM (the thesis)

| Alippo | Meera v2 (ours) |
|---|---|
| Right panel = a shaky phone-recording of a real store, whole-page jumps | Native, crisp canvas with **granular in-place morphs** (count-ups, slider→number live), no jarring reloads |
| Presence orb is generic "AI is speaking" | **Brand-themed** orb + **real thinking steps** ("scanning 300 → filtering Mumbai → ranking"), not a vanity label |
| Chat cards are passive drafts | **Interactive artifact cards** — expand, edit, approve inline, and **bidirectionally linked** to the canvas (hover card → canvas element highlights) |
| Builds a store | Builds a campaign **+ the escrow-lock trust moment** — a money-guarantee hook they simply don't have |
| Text only in the reel | **Voice-first, Hinglish** (Sarvam), with text safety net (§5A) |
| No verified-supply proof | **Verified-creator badges** + transparent fee breakdown |

**One line for the team:** Alippo shows AI *doing work*. We show AI *doing work, proving trust, and speaking your language* — with smoother motion and a two-way live link between what Meera says and what the canvas shows.

---

## 3. WHAT TO BUILD (v2 additions — all layer on the shipped slice)

### 3.1 `ChatArtifactCard` — artifacts live inside the chat, not only the canvas
**New:** `src/components/feature/meera/ChatArtifactCard.tsx`. A compact card variant rendered inside `MeeraChatPanel` when Meera proposes something (campaign plan, creator shortlist, fee summary). Structure: title + status pill (reuse `EscrowPill`/status chip) + up to 3 mini sub-action chips (reuse `QuickReplyChip` styling) + a right-aligned count badge.
- Example in the flow: the instant Meera says "I'd run a Hype Campaign," a `Vitamin C Hype · Draft` card appears **in the conversation**, mirroring what `StageRecommend` shows on the canvas.
- Card variants driven by `meera-mock.ts` artifact type: `campaign` | `creators` | `fee` | `note`.
- Alippo does this passively. **Ours is interactive** — see 3.3.

### 3.2 `MeeraPresence` — the brand-themed talking orb (beat their generic one)
**New:** `src/components/feature/meera/MeeraPresence.tsx` + `src/components/motion/VoiceWaveform.tsx` (from spec §5A). Corner-docked in the chat header.
- States: `idle` (gentle breathing pulse) → `thinking` (the T3 step log runs) → `speaking` (equalizer waveform animates).
- **Themed via `useBrandTheme`** — the orb + waveform adopt `--meera-accent` (brand color), so every brand's Meera looks like theirs. Alippo's is one fixed purple.
- The "speaking" waveform is the same component voice output (§5A) will drive later — build it now, wire audio later. No TTS dependency to ship the visual.
- **Reduced-motion:** static avatar + online dot only.

### 3.3 Chat ↔ Canvas live linking (the thing Alippo can't do)
**New shared highlight state** — a lightweight context (`MeeraWorkspaceContext`) holding `focusedArtifactId`.
- Hover/focus a `ChatArtifactCard` → the corresponding canvas element (campaign card, a creator tile, the fee row) gets a `--meera-accent` ring highlight. And vice-versa.
- This makes the two panels feel like **one connected surface**, not two videos side by side. It's the single biggest "how is this better" moment in a demo.
- Keep it cheap: shared id + a `data-artifact-id` on canvas elements + a CSS ring class. No heavy state library.

### 3.4 Granular in-canvas live edits (smoother than their page-jumps)
Within a stage, let values update **in place** instead of only swapping whole stages.
- `StageRecommend`: a budget slider → the pool `StatPair` and est-reach **count-up to the new value in place** (reuse `CountUp`); the `FeeBreakdown` recomputes live. No `StageMorph` swap for a value change — `StageMorph` is only for stage-to-stage.
- `StageMatching`: changing a filter chip re-runs the staggered creator entry via `AnimatePresence` layout (exit fade+scale, enter stagger) — you already have the pattern; make sure it triggers on filter change, not just first mount.
- **New tiny hook:** `useCanvasField` — binds a chat/slider input to a canvas value with an animated transition. Keeps the "calculated live for you" feeling Alippo's hard cuts lack.

### 3.5 Live status chip ("Published" done better)
Alippo shows a static "Published" badge. Ours: on `StageLive`, combine the existing `hype-live-indicator.tsx` (pulsing LIVE dot) with the `EscrowPill` in secured/releasing state — so our "it's live" moment **also proves the money is secured**. Trust + liveness in one glance.

---

## 4. COMPONENT MAP (new vs reuse)

**New (v2):**
- `feature/meera/ChatArtifactCard.tsx`
- `feature/meera/MeeraPresence.tsx`
- `motion/VoiceWaveform.tsx`
- `feature/meera/MeeraWorkspaceContext.tsx` (shared focus/highlight state)
- `hooks/useCanvasField.ts`

**Reuse / extend (already shipped — do not rebuild):**
- `MeeraChatPanel` → render `ChatArtifactCard` inline; mount `MeeraPresence` in header
- `LivingCanvas` + stages → add `data-artifact-id` hooks + in-place field updates
- `CountUp`, `StatPair`, `FeeBreakdown`, `QuickReplyChip`, `EscrowPill`, `hype-live-indicator`, `useBrandTheme`, `StageMorph`, `PulseAura` — all as-is
- `meera-mock.ts` → add artifact-card data + slider-driven field examples so the demo shows live edits

---

## 5. HARD RULES (CTO — non-negotiable)

1. **Additive only.** Do not modify shipped, passing components except to mount the new ones. The vertical slice compiles clean — keep it that way.
2. **Tokens only.** Everything themes through `--meera-*` / `useBrandTheme`. Zero raw Tailwind color classes. Escrow-green stays sacred (trust colors never adopt the brand color).
3. **One WebGL context per page.** The presence orb and waveform are **SVG/Canvas-2D + Framer Motion, NOT R3F.** Do not add a second `<Canvas>`. (The R3F lock is already deferred — keep it deferred.)
4. **`prefers-reduced-motion` bypass on every new animation** — orb → static, waveform → static bars, count-ups → snap, live edits → instant.
5. **Mock-first.** No backend dependency. `VoiceWaveform` ships as a visual; real audio (Sarvam) wires in with the Python AI service per `BACKEND-ARCHITECTURE-DECISION.md`.
6. **Perf budget holds:** Lighthouse ≥85 mobile, CLS ≈ 0 (we already measure `/brand/meera` via the approved `lighthouse` + `puppeteer-core` dev-deps). No layout shift from the new cards/orb — reserve dimensions.

---

## 6. DEFINITION OF DONE

- [ ] `ChatArtifactCard` renders inline for campaign/creators/fee/note; matches canvas content
- [ ] `MeeraPresence` orb: idle/thinking/speaking states, brand-themed, reduced-motion static fallback
- [ ] Hover a chat artifact card → corresponding canvas element highlights (and reverse)
- [ ] `StageRecommend` budget change → pool + reach count-up **in place**, fee recomputes live (no stage swap)
- [ ] `StageMatching` filter change re-runs staggered creator entry
- [ ] `StageLive` shows LIVE indicator + secured escrow pill together
- [ ] Zero raw color classes; escrow-green untouched; all new motion honors reduced-motion
- [ ] `npm run build` clean; `tsc --noEmit` no new errors; Lighthouse ≥85 mobile, CLS ≈ 0
- [ ] Demo script in `meera-mock.ts` shows: artifact card appears → user tweaks budget → canvas updates live → hover-link highlight → escrow-lock → LIVE

**Pipeline:** build → Kavya (QA) → Meera/DevOps (build + Lighthouse) → Kabir (security, if any input surface added) → Priya (sign-off) → Swapnil.

---

## 7. ONE-LINE FOR ANANYA

> Layer four things on your shipped slice: artifact cards **inside** the chat, a **brand-themed talking orb**, a **hover-link** that ties chat to canvas, and **in-place live value edits** — so Meera doesn't just match Alippo's split-screen, she out-motions it, out-trusts it (escrow-lock + verified), and out-personalizes it (brand-themed, Hinglish-voice-ready). Additive, tokens-only, mock-first, reduced-motion safe.
