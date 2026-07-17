# 🎨 ANANYA — BUILD NOTES: MEERA AI COFOUNDER WORKSPACE

> **From:** Ananya (Frontend) · **To:** Kavya (QA), Priya (CTO) · **Date:** 2026-07-04 · **Milestone:** M2.5
> **Status:** Vertical slice COMPLETE and compiling clean. Ready for Kavya QA pass.

Skills invoked before writing code (per task instructions): `anthropic-skills:ananya`, `anthropic-skills:3d-cinematic-web`, `design:design-critique`. Read in full before touching any file.

---

## 1. FILES CREATED

### Tokens
- `src/app/globals.css` — 3-block edit: `--meera-*` block appended to `:root`, `.dark`, and `@theme inline`. Zero existing tokens touched (verified — only additions, no redefinitions of `--accent`/`--success`/`--warning`/`--info`/`--border`).

### Data layer (`src/data/`)
- `meera-copy.ts` — all strings, Tejas-voice CTAs/copy, mobile labels
- `stage-config.ts` — stage → {title, subtitle, trigger} map + `nextStage()` helper
- `motion-tokens.ts` — Meera-specific spring/ease/stagger/timeline constants
- `meera-mock.ts` — mock brand snapshot, campaign plan, creators, payout ledger, scripted conversation

### Hooks (`src/hooks/`)
- `useBrandTheme.ts` — injects `--brand`, derives `--meera-accent` (+hover/press/soft/glow) at runtime; WCAG contrast guardrail with OKLCH-style lightness clamping (zero-dep, no color library); falls back to `#6D5AE6` for near-white/near-grey brands
- `useMeeraStage.ts` — mock function-call → stage mapping (`analyze_site`, `calculate_budget`, `show_creators`, `request_payment`, `confirm_launch`)

### `src/components/ui/` (kebab-case, new primitives)
- `escrow-pill.tsx` (T1) · `fee-breakdown.tsx` (T5) · `quick-reply-chip.tsx` · `brand-avatar.tsx` · `stat-pair.tsx` (T6) · `creator-card.tsx` (T4 consumer) · `pay-button.tsx`

### `src/components/motion/` (PascalCase, extend existing)
- `EscrowLockSequence.tsx` (T2) — new event-triggered timeline (fill→lock→pulse→caption), built alongside `EscrowFlowAnimation.tsx` (untouched), reusing its reduced-motion pattern
- `StageMorph.tsx` — `AnimatePresence mode="wait"` wrapper for canvas stage swaps
- `PulseAura.tsx` — one-shot escrow-green aura

### `src/components/3d/` (PascalCase, extend existing)
- `LockFallback.tsx` — SVG padlock, the DEFAULT lock visual (no WebGL). Added to `3d/index.ts` barrel.
- **`EscrowLockScene.tsx` (R3F Canvas version) — DEFERRED.** Spec marks this optional/stretch; SVG default fully lands the T2 moment. Not building the WebGL version to keep one clean vertical slice and avoid an unnecessary second `<Canvas>` risk.

### `src/components/feature/meera/` (PascalCase, all new)
- `MeeraWorkspace.tsx` — 50/50 shell, seam, `h-[calc(100vh-3.5rem)]`, responsive collapse, mobile bottom-tab + 90vh sheet
- `MeeraChatPanel.tsx` — header + scripted mock message list + composer/paywall switch
- `MessageBubble.tsx` · `ThinkingState.tsx` (T3) · `Composer.tsx`
- `LivingCanvas.tsx` — mounted header (title + `EscrowPill`) + `StageMorph` body
- `StageSnapshot.tsx` (1) · `StageRecommend.tsx` (2) · `StageMatching.tsx` (3) · `StageFunding.tsx` (4) · `StageLive.tsx` (5)
- `PayoutLedger.tsx` (T9) · `CreditPaywall.tsx`

### Page + routing + nav
- `src/pages/brand-meera.tsx` — thin wrapper, default export
- `src/App.tsx` — import + `<Route path="/brand/meera">` added inside the protected group, after `/brand/chat`
- `src/components/brand/brand-layout.tsx` — `Sparkles` icon imported; `Meera` nav item inserted at position 2 (flagship, after Home)
- `src/lib/icon-theme.ts` — `/brand/meera: 'primary'` added to `brandNavIconVariant`

---

## 2. SPEC COMPONENTS — DONE vs DEFERRED

| Component | Status |
|---|---|
| Tokens (§3a, 3-block) | ✅ Done |
| `useBrandTheme` + contrast guardrail | ✅ Done (zero-dep OKLCH-style clamp) |
| `MeeraWorkspace` 50/50 + route + nav + responsive | ✅ Done |
| `MeeraChatPanel`, `MessageBubble`, `ThinkingState`, `Composer`, `QuickReplyChip`, `BrandAvatar` | ✅ Done |
| `LivingCanvas` + `StageMorph` + `EscrowPill` + 5 stages | ✅ Done |
| `EscrowLockSequence` (SVG default) + `LockFallback` + `PulseAura` | ✅ Done |
| `FeeBreakdown`, `StatPair`, `CreatorCard`, `PayButton`, `PayoutLedger`, `CreditPaywall` | ✅ Done |
| `meera-copy.ts`, `stage-config.ts`, `motion-tokens.ts`, `meera-mock.ts` | ✅ Done |
| `useMeeraStage` mock glue | ✅ Done |
| **`EscrowLockScene.tsx` (R3F Canvas lock)** | ⏸️ **Deferred** — optional per spec, SVG default ships the moment |
| Website-analysis onboarding step (`brand-onboarding.tsx` URL field) | ⏸️ **Deferred** — spec §8 item 4, out of scope for this pass (onboarding flow, not the workspace itself); flagging for a follow-up task |
| Proactive/inbound Meera nudge surface outside the workspace (notification bell integration) | ⏸️ **Deferred** — spec §8 item 9; the workspace itself is complete, cross-surface notification wiring not started |
| Real function-call dispatcher (backend) | N/A — mock-first by design, per handoff §9 item 9. `lib/api.ts` has no AI endpoints yet. |

---

## 3. VERIFICATION PERFORMED

- `npx tsc --noEmit` — zero errors introduced by Meera code. Two **pre-existing** errors remain in `FadeUp.tsx`/`WordReveal.tsx` (framer-motion generic-type strictness on a polymorphic `motion.create(Tag)` call) — these files were only read, never edited, and predate this build.
- `npm run build` — **passes clean**, `vite build` succeeded in ~27s, only a pre-existing chunk-size warning (unrelated, pre-existing large vendor bundle).
- Live preview at `/brand/meera?demo=true`:
  - Desktop (1400×900): confirmed 50/50 split, seam, sidebar nav "Meera" item active/clickable, full scripted conversation played correctly through all 5 stage triggers, fee math correct (₹15,000 + ₹2,250 = ₹17,250), escrow pill states correct ("Securing…" → after pay → live "Releasing ₹17,250"), Pay CTA copy correct ("Fund & go live — ₹17,250").
  - Mobile (375×812): zero horizontal overflow confirmed via `scrollWidth === clientWidth`. Bottom "View campaign" tab renders; tapping opens the ~90vh sheet showing the full Stage 5 dashboard (count-up stats, slot progress bar, verified creator list, releasing pill) stacked full-width.
  - Brand-accent theming confirmed live: mock brand hex `#E8927C` correctly derived into the avatar gradient and CTA accent color via `useBrandTheme`, with `--meera-escrow`/`--meera-danger`/`--meera-warning` never touched.
  - No console errors at any point.

---

## 4. KNOWN ISSUES / FOLLOW-UPS FOR KAVYA

1. **R3F lock scene not built** — if Priya wants the WebGL stretch goal, that's a follow-up task; SVG default is production-ready and satisfies T2.
2. **Onboarding URL field + proactive nudge surface** — out of scope for the workspace itself (spec §8 items 4 and 9), flagged as separate follow-up work, not blocking this slice.
3. `Composer.tsx`'s `handleSend` for free-typed text is a no-op stub (mock-first — the scripted conversation auto-plays regardless of user input, matching "mock-first" build order). Real chat input handling arrives with the backend AI endpoint.
4. Escrow-lock timeline in `EscrowLockSequence` runs once per mount; if `StageFunding` unmounts/remounts (e.g. user navigates away mid-animation and back), it will replay — acceptable for this mock-first pass, worth a QA note.

---

## 5. SIGN-OFF GATE SELF-CHECK (handoff §9)

- [x] Zero raw color classes in new files — grepped for `#`, `bg-[`, `text-[`, `-500`/`-600` etc. Clean.
- [x] Every Meera token present in all 3 `globals.css` blocks; existing tokens untouched.
- [x] `--meera-accent` derives from `--brand`; escrow/danger/warning never themed.
- [x] `useReducedMotion()` in every animated component; count-ups snap (reused `CountUp` as-is); lock renders final state immediately under reduced motion.
- [x] One `<Canvas>` max — zero Canvas mounted in this slice (SVG-only path shipped); no WebGL context risk.
- [x] No content hardcoded — all strings from `src/data/meera-copy.ts`, all mock data from `src/data/meera-mock.ts`.
- [x] Motion constants imported from `data/motion-tokens.ts`, not inlined.
- [x] 375px mobile — no overflow, sheet works, Pay CTA reachable (chat-first, sheet houses canvas CTA).
- [x] No unapproved `npm install` — zero new dependencies added.

Ping Priya per her request once this lands in `SHARED_CONTEXT.md`.

---

## 6. REVIEW FIXES (2026-07-04) — Priya + Swapnil sign-off pass

Skills re-invoked before this pass: `anthropic-skills:ananya`, `anthropic-skills:3d-cinematic-web`, `design:design-critique`.

### Priya — Blocker

- **B1 (escrow-lock hero skipped)** — `src/hooks/useMeeraStage.ts:33-37`: `advance()` no longer sets `isPaid` on `confirm_launch`; `isPaid` is set only by `markPaid()`. `src/components/feature/meera/MeeraWorkspace.tsx:26-41`: `handlePay` now calls `markPaid()` only (stays on `funding`); added a separate `handleGoLive()` that calls `advance('confirm_launch')`, wired through `LivingCanvas` (`LivingCanvas.tsx:19-20,31,57`) as a new `onGoLive` prop down to `StageFunding`. `src/components/feature/meera/StageFunding.tsx`: added `onGoLive` prop + `lockComplete` state; renders an explicit "Approve & release" CTA only after `EscrowLockSequence`'s new `onComplete` callback fires (`src/components/motion/EscrowLockSequence.tsx:13-14,32-53` — added `onComplete` prop, fires on `MEERA_LOCK_TIMELINE.totalMs`, or immediately under reduced motion). Verified live: pill now reaches `"₹17,250 Secured"`, the fill→lock→pulse→caption sequence plays in full, and only the explicit CTA advances to `live`.

### Priya — Major

- **M1 (verified tick blue)** — `src/components/ui/verified-badge.tsx`: added `tone?: 'info' | 'escrow'` prop (default `info`, unchanged for other consumers). `src/components/ui/creator-card.tsx:47`: passes `tone="escrow"` and `label="Instagram-verified stats"` (also resolves Swapnil #1/#5). `src/components/creator/hype-inbox-card.tsx` untouched — still blue.
- **M2 (slot tracker cyan)** — `src/components/ui/slot-progress-bar.tsx`: added `tone?: 'hype' | 'meera'` prop with a `TONE_CLASSES` map (default `hype` unchanged). `src/components/feature/meera/StageLive.tsx:24`: passes `tone="meera"` → `bg-meera-surface-2` track / `bg-meera-escrow` fill / `text-meera-text` label. Verified live via `preview_inspect` — fill class confirmed `bg-meera-escrow`, no `bg-hype`.
- **M3 (PayButton hardcoded strings)** — `src/data/meera-copy.ts`: added `MEERA_PAY_BUTTON_LABEL = { loading, success }`. `src/components/ui/pay-button.tsx`: sources both strings from copy file; wrapped the status text in `<span aria-live="polite">` so the loading→secured transition is announced.
- **M4 (FadeUp red-tsc dependency)** — `src/components/motion/EscrowLockSequence.tsx:73-89`: T2 caption no longer imports/renders through `FadeUp`. Replaced with a plain `motion.p` (`initial/animate` + `MEERA_EASE_ENTRY` from `data/motion-tokens.ts`) under motion, and a plain `<p>` under `reduceMotion`. `FadeUp`/`WordReveal` themselves untouched — still the only 2 pre-existing `tsc` errors, and the Meera slice no longer depends on either.

### Priya — Minor

- **m1 (reduced-motion aura never fades)** — `src/components/motion/PulseAura.tsx`: added `reducedVisible` state that flips to `true` on the next animation frame after mount, driving `opacity-0 → opacity-60` over the existing 150ms transition, so the reduced-motion aura cue is now visible instead of a permanent no-op.
- **m2 (MeeraChatPanel timer leak)** — `src/components/feature/meera/MeeraChatPanel.tsx`: added `thinkingClearTimerRef`; the inner `setTimeout` that clears `thinkingKey` is now tracked and cleared in the effect cleanup, preventing a `setState` call after unmount mid-script.
- **m3 (reach count-up stall-then-snap)** — `src/components/feature/meera/StageRecommend.tsx`: `StatPair` for reach now receives `value={reach / 1000}` with a formatter that shows one decimal below 10 and rounds above, so the tween climbs continuously (`0.0k → ... → 420k`) instead of reading `0k` for most of the animation.
- **m4 (EscrowLockSequence double-driven meter)** — `src/components/motion/EscrowLockSequence.tsx`: removed the `meterPct` state; the fill bar is now driven purely by framer's `initial`/`animate` (`width: 0% → 100%`), one source of truth.

### Priya — Nits

- **n1 (dead ShieldCheck)** — `src/components/ui/escrow-pill.tsx`: removed the hidden zero-size `<ShieldCheck>` node and its now-unused import (also Swapnil #4).
- **n2 (BrandAvatar/useBrandTheme gradient mismatch)** — not touched this pass; flagged as pre-existing cosmetic divergence, no functional impact while accent ≈ brand. Left for a follow-up per Priya's "fine to keep" note.
- **n3 (unused `nextStage`/`STAGE_ORDER`)** — left as-is; the new `handleGoLive` uses `advance('confirm_launch')` for consistency with every other stage transition in the file (all driven by named function calls), which reads more consistently than switching just this one transition to `nextStage()`.

### Swapnil — CEO findings

1. **Verified badge blue → green** — same fix as Priya M1 above.
2. **Escrow pill label order** — `src/data/meera-copy.ts`: `secured: (amount) => \`${amount} Secured\`` (was `Secured ${amount}`). Verified live: pill reads `"₹17,250 Secured"`.
3. **"First in India" badge never rendered** — `src/components/feature/meera/MeeraChatPanel.tsx`: added a subtle `text-[10px] opacity-70` line under the Meera subtitle in the chat header, rendering `MEERA_IDENTITY.firstInIndiaBadge` once, quietly (not a banner). Verified live via accessibility snapshot.
4. **Dead ShieldCheck** — same fix as Priya n1 above.
5. **Verified badge tooltip copy** — default label passed as `"Instagram-verified stats"` from `creator-card.tsx` (see M1).
6. **Pay button green success state** — no change; Swapnil explicitly accepted this as-is (monitoring note only).
7. **Awkward em-dash in mock copy** — `src/data/meera-mock.ts:109`: "Here's the plan —" → "Here's the plan:".
8. **Lighthouse not verified** — unchanged; this is a Meera/DevOps pipeline task, not a frontend defect. Still outstanding.

### Verification

- `npx tsc --noEmit` — 2 errors, both pre-existing in `FadeUp.tsx`/`WordReveal.tsx` (untouched). Zero errors introduced by this pass.
- `npm run build` — passes clean (`vite build`, ~26s), same pre-existing chunk-size warning as before, no new warnings.
- Live preview at `/brand/meera?demo=true` (1400×900): drove the scripted conversation to Stage 4, clicked "Fund & go live", confirmed escrow pill reached `role="status"` text `"₹17,250 Secured"`, confirmed the lock hero (meter fill, locked padlock, green pulse, caption) rendered in the DOM, confirmed the new "Approve & release" CTA appeared only after the sequence completed, clicked it, confirmed transition to Stage 5 "Campaign is live" with pill `"Releasing ₹17,250"`. Inspected computed styles: verified badge tick `color: rgb(18, 161, 80)` (escrow-green) via `text-meera-escrow`; slot bar fill class confirmed `bg-meera-escrow` (no `bg-hype`).

— Ananya

---

## 7. INTERACTIVE CONVERSATION (2026-07-04) — Swapnil finding: chat must actually talk with the user

Skills re-invoked before this pass: `anthropic-skills:ananya`, `anthropic-skills:3d-cinematic-web`, `design:design-critique`.

**The gap.** Swapnil reviewed the workspace live and found that `MeeraChatPanel` auto-played `MOCK_CONVERSATION` end to end on mount (a flat array of alternating `meera`/`brand` rows on a timer), and `Composer`'s `handleSend` was a no-op (`void text`) — see item 3 in §4 above. The "brand" rows were pre-written strings, not something the user typed; there was no seam for real input to slot into the script. He wants: user types or taps a chip → Meera responds → the response drives the stage. Still mock-first, no backend.

### Design — turn engine, not a replaying array

**Why turns instead of patching the flat array with a cursor:** a flat `MockScriptedMessage[]` has no natural place to attach "what the user is expected to reply with right now" or "what chips make sense at this exact point." A turn-based model does, so I restructured the data shape rather than bolting a cursor onto the old one.

`src/data/meera-mock.ts` — `MOCK_CONVERSATION: MockScriptedMessage[]` replaced with `MEERA_CONVERSATION_SCRIPT: MeeraTurn[]`. Each `MeeraTurn` is one full round:

```ts
interface MeeraTurn {
  id: string
  meeraResponses: string[]       // Meera's line(s), revealed in order when the turn opens
  suggestedReplies: string[]     // contextual chips offered while awaiting input THIS turn
  showThinking?: 'snapshot' | 'recommend' | 'matching'  // thinking beat on exit
  triggersStage?: MeeraStageId   // function call fired on exit -> advances Living Canvas
  nudge?: string                 // shown if turn 0 gets non-URL free text
}
```

**Trigger timing (the one non-obvious rule, commented in the file):** `showThinking`/`triggersStage` on turn N fire when the USER sends their reply to turn N — i.e. on exit from turn N, immediately before turn N+1's `meeraResponses` are revealed. Turn N's own lines are the "before" state; turn N+1's lines are the payoff that already reflects the result. E.g. turn 0 asks for the site; it's turn 0's *exit* (site sent) that fires `analyze_site` + the snapshot thinking beat; turn 1 then opens already saying "Got it, Kavala Skincare...". I got this backwards on the first pass (attached the trigger to the turn whose lines were the payoff, which fires one full round late) and caught it by literally walking the flow in the browser stage-by-stage — the canvas visibly stalled on Stage 1 while the chat had already moved to Stage 3's chips. Fixed by shifting every `showThinking`/`triggersStage` back one turn.

**Engine state, `MeeraChatPanel.tsx`:**
- `turnIndex` — which `MeeraTurn` is active.
- `messages: RenderedMessage[]` — the actual rendered bubble list, built up as turns resolve. Decoupled from the script on purpose: the script is authoring data, the message list is runtime history.
- `phase: 'revealing' | 'awaiting-input' | 'thinking'` — `revealing` plays the active turn's `meeraResponses` one at a time (a timer per line, same pacing as before); once exhausted, phase flips to `awaiting-input` and STOPS — nothing more happens until `handleSend` is called. `handleSend` is the only thing that can move the cursor: it appends the user's real bubble, flips to `thinking` (showing `ThinkingState` if the turn has `showThinking`), then after a delay fires `triggersStage` (if any) and advances to the next turn's `revealing` phase.
- Turn 0's URL gate: a small `looksLikeSite()` deterministic regex (`/\.[a-z]{2,}/` or `https?://`) — if the first message doesn't look like a domain, Meera's `nudge` line is appended without consuming the turn or firing any stage, so the user can just try again.
- No timers ever fire on their own past an `awaiting-input` turn — verified by loading the page and confirming ONLY Meera's opener renders, with the composer live and the `kavalaskincare.com` chip shown, nothing else auto-playing.

**Quick replies are now contextual, not static.** `MEERA_QUICK_REPLIES` in `meera-copy.ts` is now an empty-array fallback (kept only so the type/export isn't a breaking removal); `Composer` takes a `suggestedReplies` prop and `MeeraChatPanel` passes `turn.suggestedReplies` for the currently-open turn only. Turn 0 suggests the demo URL; turn 1 suggests "What would that cost?" / "Make it Mumbai only"; turn 2 suggests "Show me the creators"; turn 3 suggests "I'm ready to fund it"; turn 4 (funding, terminal) suggests nothing.

**Free text vs. quick reply — same code path.** Both call the identical `onSend(text)` — a quick-reply chip is just a shortcut that pre-fills the exact suggested string and immediately sends it. There is no separate branch for "typed" vs. "tapped." Light keyword acknowledgement is intentionally NOT built beyond the turn-0 URL gate — per the task's own guidance this stays a deterministic next-turn advance, not an NLP layer. Typing "What would that cost, and can we target Mumbai only?" at turn 1 advances exactly the same as tapping the "What would that cost?" chip; Meera's scripted line doesn't change based on the extra "Mumbai" text (documented limitation, see below).

**Composer lock semantics split into two props** (`Composer.tsx`): the old single `disabled` prop conflated two different states — "credit-gated paywall" (shows `disabledHint` copy, paused placeholder) and "mid-turn, can't submit yet" (revealing/thinking). Reusing `disabled` for both would have shown "Fund your first campaign to keep chatting" while the user is mid-flow *before* funding, which is wrong. Split into `disabled` (paywall-style, unchanged meaning, used only when `MeeraChatPanel`'s `paused` prop swaps in `CreditPaywall` instead of `Composer` anyway) and `sendLocked` (turn-engine pacing only, no paywall copy, just disables input/send/chips while Meera is revealing or thinking).

### Files changed

- `src/data/meera-mock.ts` — replaced `MOCK_CONVERSATION`/`MockScriptedMessage` with `MEERA_CONVERSATION_SCRIPT`/`MeeraTurn` (5 turns: opening, snapshot, recommend, matching, funding). Kept `MockScriptedMessage` as a type alias to `MeeraTurn` in case anything else referenced the name (nothing did, grepped to confirm).
- `src/components/feature/meera/MeeraChatPanel.tsx` — full rewrite of the mount effect and `handleSend`. Now owns `turnIndex`/`revealCount`/`phase`/`thinkingKey` state, a single reveal effect (was: one big auto-play effect keyed off `visibleCount` against the flat array), and a real `handleSend` that drives the turn cursor. Timer cleanup on unmount preserved (Priya m2 fix from the original pass carried forward — thinking-clear timer still tracked in a ref and cleared).
- `src/components/feature/meera/Composer.tsx` — added `suggestedReplies` prop (chips sourced from the caller, not a static import) and `sendLocked` prop (see semantics above); `disabled` narrowed to paywall-only meaning.
- `src/data/meera-copy.ts` — `MEERA_QUICK_REPLIES` emptied to `[]` with a comment pointing at the per-turn source of truth; no strings deleted that are still in use (all 5 turns' copy lives in `meera-mock.ts` per the "mock data belongs in meera-mock, UI strings belong in meera-copy" split already established — the turn script itself is conversation *content*, closer to mock data than static UI chrome, so it stays in `meera-mock.ts` alongside the other mock fixtures).
- No changes to `useMeeraStage.ts`, `stage-config.ts`, `MeeraWorkspace.tsx`, `LivingCanvas.tsx`, or any `Stage*.tsx` — the stage-driving contract (`onFunctionCall(call)` → `advance(call)` → stage swap) was already correct and is reused as-is; only what *calls* it changed.

### Verification

- `npx tsc --noEmit` — 2 errors, both pre-existing in `FadeUp.tsx`/`WordReveal.tsx` (untouched, unrelated). Zero errors introduced.
- `npm run build` — clean (`vite build`, ~28s), same pre-existing chunk-size warning, no new warnings.
- Live walkthrough at `/brand/meera?demo=true` (1400×900, fresh dev server to rule out stale HMR):
  1. Mount: only Meera's opener line renders. Composer live, `kavalaskincare.com` chip shown. Nothing else auto-plays — confirmed no further bubbles appear without a send.
  2. Typed "hi there" (not a URL) → Meera's nudge appended ("Drop your website URL and I'll start there — try kavalaskincare.com."), turn 0 stays open, chip still available.
  3. Tapped the `kavalaskincare.com` chip → thinking beat played → single payoff line revealed → canvas advanced to Stage 1 "Your business, at a glance" (`analyze_site` fired).
  4. Typed free text "What would that cost, and can we target Mumbai only?" → thinking → payoff line → canvas advanced to Stage 2 "Campaign, built for you" (`calculate_budget` fired). Confirms free text drives turns exactly like a chip.
  5. Tapped "Show me the creators" chip → canvas advanced to Stage 3 "Matching creators" (`show_creators` fired).
  6. Tapped "I'm ready to fund it" chip → canvas advanced to Stage 4 "Fund the campaign" (`request_payment` fired), Meera's closing line "Locking your funds into escrow now." revealed.
  7. Clicked "Fund & go live" → escrow pill reached `"₹17,250 Secured"` → lock sequence (fill → lock → pulse → caption) played → "Approve & release" CTA appeared only after it completed → clicked it → canvas advanced to Stage 5 "Campaign is live", pill read `"Releasing ₹17,250"`.
  8. Zero console errors on the clean run.
- Caught and fixed one real bug during this verification (not a tooling artifact — reproduced twice on fresh server instances): the initial turn script had `showThinking`/`triggersStage` attached one turn too late, so the Living Canvas visibly lagged the chat by a full round. Fixed per the "Trigger timing" note above; re-verified the full 5-stage walkthrough after the fix.

### Known limitation (be honest about this)

This is a **deterministic scripted mock, not a real LLM**. It advances on ANY non-empty send once a turn is `awaiting-input` — there's no actual language understanding. The only content-sensitive branch is the turn-0 "does this look like a URL" regex gate; every other turn accepts whatever the user types and moves to the next scripted line regardless of what it says (typing "Mumbai" vs. "banana" at turn 1 produces the identical next Meera line). A real integration needs an actual backend/LLM turn to replace `handleSend`'s resolution with a real function-calling response; the turn-based shape here (`MeeraTurn.triggersStage` as an explicit function-call name) is designed so that swap is a matter of replacing the mock resolution with a real API call per turn, not a rewrite of the UI state machine.

— Ananya

---

## 8. §5A VOICE & LIVING PRESENCE (2026-07-05) — M2.5 addendum, per Priya's locked voice handoff

Skills re-invoked before this pass, per the task instructions: `anthropic-skills:ananya`, `anthropic-skills:3d-cinematic-web`, `design:design-critique`.

Read in full before writing code: `docs/AI connect/PRIYA-ARCH-HANDOFF-VOICE.md` (locked constraints, this section's source of truth), `docs/AI connect/FRONTEND-BUILD-SPEC-MEERA.md` §5A, `docs/AI connect/PRIYA-ARCH-HANDOFF-MEERA.md` (base conventions).

### Files created

- `src/types/speech.d.ts` — ambient `SpeechRecognition`/`SpeechRecognitionEvent`/etc. shim. The one allowed typed exception (handoff §6) — no `as any` anywhere in the voice code.
- `src/lib/clean-transcript.ts` — `cleanTranscript(raw): Promise<string>`, the mock grammar-cleanup seam. Trims, collapses whitespace, capitalizes the first character only. Explicitly labelled `// MOCK — replace with backend LLM cleanup post-M2` in the file header.
- `src/lib/voice-usage.ts` — `logVoiceUsage(kind: 'stt' | 'tts')`, a no-op stub (console.debug in dev only) for Rohan's future cost model.
- `src/hooks/useVoiceOutput.ts` — TTS hook (`speechSynthesis` + `SpeechSynthesisUtterance`). Returns `{ supported, enabled, setEnabled, isSpeaking, speak, stop }`.
- `src/hooks/useVoiceInput.ts` — STT hook (`SpeechRecognition`/`webkitSpeechRecognition`). Returns `{ supported, phase, isListening, start, stop }`; `phase` is `idle | listening | transcribing | error`.
- `src/components/ui/voice-waveform.tsx` — `VoiceWaveform`, shared by presence-talking and mic-listening, driven by an `active` prop.
- `src/components/ui/mic-button.tsx` — `MicButton`, composer mic control (idle → listening → transcribing).
- `src/components/ui/voice-toggle.tsx` — `VoiceToggle`, persistent speak-replies on/off control.
- `src/components/feature/meera/MeeraPresence.tsx` — corner-docked living-presence layer, wraps `BrandAvatar`.

### Files changed

- `src/data/meera-copy.ts` — added `MEERA_VOICE_COPY` block (STT fallback copy, mic/toggle labels).
- `src/data/motion-tokens.ts` — added `MEERA_PRESENCE_IDLE_DURATION`, `MEERA_PRESENCE_THINKING_DURATION`, `MEERA_WAVEFORM_BAR_COUNT`, `MEERA_WAVEFORM_LOOP_DURATION`.
- `src/components/feature/meera/Composer.tsx` — added `MicButton` (rendered only when `useVoiceInput().supported`), edit-first wiring (`onResult` appends cleaned text into the existing `value`, never calls `onSend`), STT fallback copy display, and an `aria-live="polite"` transcription-result region that announces either the cleaned text or the fallback copy.
- `src/components/feature/meera/MeeraChatPanel.tsx` — exported `Phase` type; added `onPhaseChange`/`onSpeakingChange` props (report phase + TTS `isSpeaking` up one level, no context); integrated `useVoiceOutput`; calls `speak(line)` immediately *after* each Meera line is pushed into `messages` state (never before — text is always already queued to render); renders `VoiceToggle` in the sticky header, only when `voiceOutputSupported`.
- `src/components/feature/meera/LivingCanvas.tsx` — added required `presenceState: MeeraPresenceState` prop; renders `MeeraPresence` inside a `relative h-8 w-8` reserved slot to the left of the stage title, inside the *mounted* header row (never in the scrolling body, never overlapping the `EscrowPill` on the right or any stage's Pay CTA below).
- `src/components/feature/meera/MeeraWorkspace.tsx` — owns `chatPhase`/`isSpeaking` state, derives `presenceState` via a small `derivePresenceState(phase, isSpeaking)` function (talking > thinking > idle), threads it to both the desktop `LivingCanvas` and the mobile-sheet `LivingCanvas` instance. Passes `onPhaseChange`/`onSpeakingChange` down to `MeeraChatPanel`.

### How each §10 sign-off gate is satisfied

1. **Zero new npm deps; Web Speech API only; typed via `.d.ts`, no `any`.** Confirmed — `useVoiceInput`/`useVoiceOutput` only touch `window.SpeechRecognition`/`webkitSpeechRecognition`/`speechSynthesis`/`SpeechSynthesisUtterance`. `src/types/speech.d.ts` is the only typed exception; grepped the new/changed voice files for `any` — none found.
2. **Text chat fully works with voice OFF/unsupported/failed — no dead ends.** `speak()` no-ops silently if `!supported || !enabled`; the reveal effect that pushes each Meera line into `messages` runs unconditionally, `speak()` is called strictly after. `MicButton` is only rendered when `voiceInputSupported` — an unsupported browser never sees it, and the text `Composer` (textarea + send) is otherwise fully unchanged. Verified live (see below): full 5-turn scripted conversation completes correctly with voice untouched.
3. **`useVoiceInput` never auto-sends; cleaned text editable first; numbers/handles/cities never altered.** `onResult` in `Composer.tsx` only calls `setValue(...)` (appends to existing textarea value) — there is no path from `useVoiceInput` to `onSend`. `cleanTranscript` only trims/collapses whitespace/capitalizes the first character — it cannot alter digits, `₹`, `@handles`, or any word's internal casing (verified by inspection: the only mutation is `.charAt(0).toUpperCase()` on the whole string, a no-op on a leading digit/symbol).
4. **`cleanTranscript` is a labelled mock seam, swappable with no UI change.** File header comment: `// MOCK — replace with backend LLM cleanup post-M2 (see spec §5A.C)`. `useVoiceInput` awaits it as a single `Promise<string>` call — a real backend call is a one-function body swap.
5. **Presence + waveform use `--meera-accent` only; escrow-green untouched.** Grepped `voice-waveform.tsx` and `MeeraPresence.tsx` for `escrow` — only doc-comments stating the rule, zero actual class usage. Both components exclusively reference `bg-meera-accent` / `var(--meera-accent)`. Verified live via `getComputedStyle` that `--meera-accent` (`#6D5AE6`) and `--meera-escrow` (`#12A150`) remain distinct tokens.
6. **`prefers-reduced-motion` → static presence (online dot only); no waveform motion.** Both `MeeraPresence` and `VoiceWaveform` call `useReducedMotion()` and return an early, fully static branch (plain `BrandAvatar` for presence; fixed-height bars with no `motion.span`/`animate` for the waveform) — matches the exact early-return pattern already used in `StaggerContainer.tsx`/`HeroGlobe.tsx`.
7. **`speechSynthesis.cancel()` on new utterance + on unmount.** `useVoiceOutput.speak()` calls `window.speechSynthesis.cancel()` unconditionally before creating each new `SpeechSynthesisUtterance`; a separate unmount effect calls `cancel()` again. `setEnabled(false)` also cancels immediately so turning the toggle off mid-speech doesn't leave audio running.
8. **Presence is `absolute` within the panel, never overlaps Pay CTA / EscrowPill / composer; no layout shift.** `MeeraPresence` renders `position: absolute` (via `absolute` Tailwind class) inside a `relative h-8 w-8` wrapper that `LivingCanvas` reserves in its *mounted* header row, to the left of the stage title and clear of the `EscrowPill` on the right. It never renders inside the scrolling stage body where `StageFunding`'s Pay CTA lives, and it's never `position: fixed`. Verified live: `boundingBox` inspection showed the reserved 28×28px slot with no shift as stages morphed.
9. **`npm run build` + `tsc --noEmit` clean (only the 2 pre-existing FadeUp/WordReveal errors).** Confirmed — see Verification below.

### Verification performed

- `npx tsc --noEmit` — exactly the 2 pre-existing errors in `FadeUp.tsx`/`WordReveal.tsx` (untouched). Zero errors introduced by the voice work.
- `npm run build` — clean, `vite build` succeeded (~20-31s across runs), same pre-existing chunk-size/`baseUrl` warnings, no new warnings.
- Grepped every new/changed voice file for raw color classes (`bg-[`, `text-[`, `#hex`, Tailwind palette classes) — only one pre-existing, unrelated match (`text-[10px]` font-size utility from an earlier pass). Zero raw color violations introduced.
- Live preview at `/brand/meera?demo=true`, Chromium-based preview browser (both `SpeechRecognition` and `speechSynthesis` present):
  - **Desktop (1400×900):** confirmed `MeeraPresence` renders `aria-label="Meera is idle"` on load, docked to the left of the `LivingCanvas` stage title, `EscrowPill` ("Unfunded") undisturbed on the right.
  - **VoiceToggle:** accessibility snapshot confirmed `button: "Voice replies off"` initially; clicking flips `aria-pressed`/label to `"Voice replies on"` and `localStorage.getItem('meera:voice-output')` reads `"on"`; toggling off writes `"off"`. Persists correctly across a full page reload (confirmed via fresh snapshot after reload showing `"Voice replies on"` from a prior session's localStorage).
  - **TTS end-to-end:** instrumented `window.speechSynthesis.speak` to record calls without needing real audio hardware. Advanced the scripted conversation with voice ON — confirmed `speak()` was called with the exact Meera reply text ("Here's the plan: 15 creators, a 72-hour window, and a transparent fee breakdown. Nothing hidden.") **after** the text had already rendered in the message list, and `MeeraPresence`'s `aria-label` flipped to `"Meera is talking"` while the utterance was in flight — confirming text-first, voice-additive, and the presence state wiring all work correctly together.
  - **MicButton:** confirmed present with correct idle `aria-label="Speak to Meera"` / `aria-pressed="false"` when `useVoiceInput().supported` is true. Clicked it to exercise the real permission-denied path (the sandboxed preview browser has microphone permission `denied` via `navigator.permissions.query`) — confirmed the hook's state machine returned cleanly to `idle` rather than getting stuck on `"listening"`, exactly the graceful-fallback behavior the hook is built for. Two notes on this test: (a) the preview's screenshot capture tool hung after this interaction (almost certainly a native OS/Chrome permission-arbitration artifact outside the DOM, not a page-level hang — confirmed the page stayed fully responsive throughout via `eval`/`document.title` and accessibility snapshot), so visual confirmation of the fallback text below the mic used the accessibility tree and computed `localStorage`/permission state rather than a screenshot; (b) I was not able to fully exercise the "recognition returns a low-confidence/no-speech transcript" branch specifically (only the permission-denied branch), since a real transcript requires actual mic hardware.
  - **Mobile (375×812):** accessibility snapshot confirmed full-screen chat layout with `VoiceToggle` and `MicButton` both present and correctly labelled in the composer/header, no overflow. Opened the "View campaign" bottom sheet and confirmed `MeeraPresence` (`"Meera is idle"`) renders correctly in the sheet's `LivingCanvas` header too, alongside the `EscrowPill`, with the same no-overlap layout as desktop.
  - No console errors at any point (`voice-usage` dev-mode `console.debug` lines confirmed the `logVoiceUsage('tts')` stub firing on each spoken reply).

### Honest limitations

- **STT is genuinely Chromium-only.** `useVoiceInput`'s `supported` check is `Boolean(window.SpeechRecognition || window.webkitSpeechRecognition)` — this is `false` in Safari and Firefox today, per Priya's handoff §2, and the mic button correctly does not render there. Not tested in a real Safari/Firefox instance this pass (no such browser available in the sandboxed preview); relying on the well-documented state of Web Speech API browser support plus the code's explicit feature-detection gate.
- **The no-speech/low-confidence STT branch was not exercised with real audio** — only the permission-denied path was verified live (see above), because the sandboxed preview environment has no real microphone. The `onerror`/`onnomatch`/empty-transcript code paths are implemented and route to the same `fail()` helper (same fallback copy, same reset-to-idle behavior verified for permission-denied), but a real end-to-end "mumbled audio" test needs a device with an actual mic.
- **`cleanTranscript` is exactly what it says: a whitespace/capitalization mock, not a Hinglish grammar rewriter.** It does not attempt anything resembling "10 creator Mumbai serum promote" → "Promote the serum with 10 Mumbai creators" (the spec's own example of what real cleanup eventually needs to do) — that requires an actual LLM call, explicitly deferred to post-M2 per the handoff. Today it only trims/collapses whitespace and capitalizes the first character, which is meaning-preserving by construction but is not grammar correction.
- **`VoiceWaveform` is honestly time-based, not audio-reactive.** It loops a fixed animation while `active` is true and stops when it's false — there is no FFT/amplitude analysis of the `speechSynthesis` audio (there is no accessible audio stream to analyze), exactly as Priya's handoff describes. It reads as "she's speaking," not as a real waveform of the actual voice.
- **`useReducedMotion()` reduced-motion branches were verified by code inspection against the repo's established pattern, not by live emulation** — the preview tooling used this session doesn't expose a `prefers-reduced-motion` toggle (unlike its light/dark `colorScheme` support). Both `MeeraPresence` and `VoiceWaveform` follow the identical early-return structure already proven live in `StaggerContainer`/`HeroGlobe` elsewhere in this codebase, so this is a strong but not live-verified guarantee for this specific pass.
- **Presence "thinking" ring-shimmer state was not directly observed live** — the scripted conversation's `thinking` phase in this demo script resolves quickly (700ms–1400ms) and the verification pass happened to catch `idle` and `talking` clearly but not a screenshotted `thinking` frame. The code path (`state === 'thinking'` → faster shimmer ring animation) is implemented and structurally identical to the verified `idle`/`talking` branches, just not separately screenshotted.

---

## QA/SECURITY FIXES (2026-07-05)

Skills invoked before this pass (per task instructions): `anthropic-skills:ananya`, `anthropic-skills:3d-cinematic-web`, `design:design-critique`, `owasp-security`.

Source: `KAVYA-QA-REPORT.md` (1 Minor + 3 Nits) and `KABIR-SECURITY-AUDIT.md` (Part A items routed to Ananya: A2, A3, A4). Backend items (B1–B4) and A1 (JWT/localStorage) are explicitly Vikram's for the live-money cutover and were **not** touched here.

| Finding | File : Line | Fix |
|---|---|---|
| **Kavya Minor #1** — raw `text-[10px]` arbitrary utility on the "first in India" badge | `src/components/feature/meera/MeeraChatPanel.tsx:178` | Replaced `text-[10px]` with the existing `text-xs` (12px) scale token — no bracket arbitrary values, tokens-only gate now clean. No new CSS token needed since Tailwind's own `text-xs` already covers this weight class; verified live at desktop and 375px — badge truncates correctly, no visual regression. |
| **Kabir A2** — `?demo=true` client-side auth bypass, must not survive into prod | `src/App.tsx` — `ProtectedRoute` (was 42–47) and `CreatorProtectedRoute` (was 59–64) | Changed `isDemoMode` from `(?demo=true) OR (MODE === 'development')` to `import.meta.env.DEV && (?demo=true)`. `import.meta.env.DEV` is a Vite compile-time boolean literal (`false` under `vite build`/production mode), so Rollup/esbuild dead-strips the entire bypass expression — the `URLSearchParams(...).get('demo')` read no longer exists in the production bundle at all, not just "returns false". |
| **Kabir A3** — fail-closed mock guard: mock auth must never silently mint a token if a prod build misconfigures `VITE_API_MODE` | `src/lib/api.ts` (new `assertMockAuthAllowed()` + `MockAuthDisabledError`, called at `auth.brandLogin`, `auth.creatorLogin`, `auth.brandRegister` — the three call sites Kavya's report cited at ~230/240/245); `src/pages/creator-register.tsx:47,58` (now guarded, was direct `localStorage.setItem`); `src/pages/creator-login.tsx:33` (now guarded) | Added `assertMockAuthAllowed()`: throws `MockAuthDisabledError` when `import.meta.env.PROD && !isApiLive()`. Called before every hardcoded mock-token mint, including the two page-level direct `localStorage.setItem('creator_token', 'mock_creator_token')` sites that bypass `api.ts` entirely. Default is now SAFE/closed — a misconfigured prod deploy throws and surfaces a config error instead of silently letting anyone "log in". |
| **Kabir A4** — unbounded STT transcript length | `src/hooks/useVoiceInput.ts` (new `MAX_TRANSCRIPT_LENGTH = 4000` constant; capped in `onresult`, was ~line 78); `src/components/feature/meera/Composer.tsx` (`maxLength={MAX_TRANSCRIPT_LENGTH}` added to the textarea) | Named constant `MAX_TRANSCRIPT_LENGTH` (4000 chars) added to `useVoiceInput.ts`. Raw STT result is sliced to this cap *before* it reaches `cleanTranscript` (both the success and catch/fallback paths use the capped value). Composer's free-text textarea also got `maxLength` for the typed/pasted path per Kabir's "ideally the composer free-text" note. Not an XSS fix (Kabir's own A-Info-1 already confirmed the render path is fully escaped) — this is the belt-and-suspenders self-DoS guard he asked for. |
| **Kavya Nit N1** (pre-existing FadeUp/WordReveal tsc errors) | `src/components/motion/FadeUp.tsx:32`, `src/components/motion/WordReveal.tsx:21` | **Skipped, out of scope** — explicitly excluded from this task; confirmed still exactly 2 pre-existing errors, zero new ones. |
| **Kavya Nit N2** (`cleanTranscript` mock label) | `src/lib/clean-transcript.ts` | **Skipped** — Kavya's own report marks this "Not a defect... No fix needed for M2.5 delivery," by design per Priya's handoff. No code change is being requested, just a future backend task for Vikram. |
| **Kavya Nit N3** (mobile 375px not live-verified) | — | **Addressed via live verification, not a code change.** Ran the app at 375×812 in this pass (see Verification below) — badge truncates cleanly, Send button fully within viewport bounds, no horizontal overflow. Closes the nit without needing a code fix. |

### Verification performed (this pass)

- `npx tsc --noEmit` — exactly the 2 pre-existing errors in `FadeUp.tsx`/`WordReveal.tsx`. Zero new errors from any of the four fixes.
- `npm run build` — succeeds cleanly (`vite build`, ~12–15s), same pre-existing `baseUrl` duplicate-key and chunk-size warnings, no new warnings.
- **Prod-bundle confirmation of the A2 fix:** grepped `dist/assets/index-*.js` for `get('demo')` post-build — **0 matches**. The only two remaining occurrences of the substring "demo" in the bundle are unrelated copy ("audience demographics", an admin-dashboard "demo data" label) — the auth-bypass code path is fully absent from the production bundle, not merely gated at runtime.
- **Live re-check** at `/brand/meera?demo=true` in dev (where `import.meta.env.DEV` is `true`, so the bypass correctly still works for local demoing):
  - Badge renders "First AI-first influencer platform in India" at `font-size: 12px` via `text-xs`, `opacity: 0.7`, truncates with ellipsis — no regression from the `text-[10px]` → `text-xs` swap.
  - Clicked the `kavalaskincare.com` quick-reply chip — turn engine advanced correctly through "Opening your website" → "Reading your product pages" → "Pulling your brand colours", confirming `Composer`/chat flow still works after the `MAX_TRANSCRIPT_LENGTH`/`maxLength` change.
  - Resized to 375×812 — body width tracked viewport exactly (no horizontal overflow), badge box confirmed truncating within a 251px-wide container, Send button bounding box (`x:314.4, width:36`) fully inside the 375px viewport.
  - No console errors at any point.

### Notes on the A2 fix vs. Kabir's suggested approach

Kabir's writeup suggested gating behind a new `VITE_ALLOW_DEMO` env flag. Went with `import.meta.env.DEV` instead (also explicitly OK'd in his fix text: "Do not rely on `MODE === 'development'` alone — confirm the prod build sets `MODE=production`") because: (1) it requires zero new env var to configure/forget, (2) Vite already guarantees `import.meta.env.DEV === false` under `vite build` regardless of mode string games, and (3) it's the same pattern already used elsewhere in this file for the `/dev/motion-skills` route (`src/App.tsx` — `{import.meta.env.DEV && (<Route .../>)}`), so it's consistent with an existing, already-trusted convention in this codebase rather than introducing a second mechanism.

— Ananya
