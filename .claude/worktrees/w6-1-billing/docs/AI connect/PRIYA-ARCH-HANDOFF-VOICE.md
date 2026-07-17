# 🏗️ PRIYA — ARCH HANDOFF ADDENDUM: Voice & Living Presence (Spec §5A)

> **From:** Priya (CTO) · **For:** Ananya · **Milestone:** M2.5
> **Reads with:** `PRIYA-ARCH-HANDOFF-MEERA.md` (base handoff still governs) + spec `§5A`.
> **Status:** APPROVED TO BUILD with the constraints below. These are LOCKED — no deviation without my sign-off.

The base handoff (tokens namespaced `--meera-*`, tokens-only, reduced-motion mandatory, `@/` alias, kebab in `ui/` / PascalCase in `motion|3d|feature`, motion constants in `data/motion-tokens.ts`) still applies in full. This addendum only covers the six §5A components.

---

## 0. THE ONE RULE THAT OVERRIDES EVERYTHING
**Voice is additive. Text is the contract.** If every voice line you write were deleted, the chat must still work perfectly. Concretely:
- `useVoiceInput` only ever writes into the **composer's existing value** (edit-first). It NEVER calls `onSend` itself.
- `useVoiceOutput` only ever **reads a reply that has already rendered**. A reply must never wait on audio.
- Any voice failure path ends in the text UI, never a dead end (spec §5A acceptance #1).

If you're unsure whether something belongs in the voice path or the text path — put it in text and layer voice on top.

---

## 1. NO NEW NPM DEPENDENCIES (my hard rule)
Do **not** `npm install` anything. Everything here ships on built-in browser APIs:
- **STT (voice input):** `window.SpeechRecognition || window.webkitSpeechRecognition`.
- **TTS (voice output):** `window.speechSynthesis` + `SpeechSynthesisUtterance`.
- **Grammar cleanup:** a local mock function — **NOT** an LLM SDK. See §3.

If you think you need a package, stop and escalate to me first. You won't need one for this.

---

## 2. BROWSER SUPPORT IS UNEVEN — FEATURE-DETECT, DON'T ASSUME
- `SpeechRecognition` (STT) is effectively **Chromium-only** (`webkitSpeechRecognition`). Safari/Firefox: treat as unsupported.
- `speechSynthesis` (TTS) is broadly supported, but **Indian-English / Hindi voices may not exist** on the device, and browsers **block autoplay audio** until a user gesture.
- **Both hooks MUST return a `supported: boolean`.** When `false`: hide the mic button / voice toggle entirely (don't render a broken control) and the text path stands alone. This is acceptance #1 and #4.
- Permission-denied, `onerror`, no-speech, and no-voice-available all route to the same graceful fallback. MicButton STT failure → the spec's `"Didn't catch that — type it instead?"` copy (put it in `meera-copy.ts`).

---

## 3. GRAMMAR CLEANUP IS A MOCK SEAM — AND IT'S A MONEY-SAFETY BOUNDARY
There is **no backend AI endpoint** (`lib/api.ts` has none). Real Hinglish→clean-English rewriting needs an LLM and lands **post-M2**. For M2.5:

- Build `cleanTranscript(raw: string): string` as a clearly-labelled **mock** in `src/lib/` (or `src/data/`). Light deterministic tidy only: trim, collapse whitespace, capitalize first letter, sentence-case. Mark it `// MOCK — replace with backend LLM cleanup post-M2 (see spec §5A.C)`.
- **MEANING-PRESERVATION IS NON-NEGOTIABLE (spec §5A.C + acceptance #2).** The mock must **never alter numbers, ₹ amounts, `@handles`, or city/brand names.** In a money UI, "15" becoming "50" or "Mumbai" becoming something else is a trust breach. When in doubt, the mock passes text through unchanged.
- **The real guardrail is the edit-first UI, not the cleanup function.** The cleaned text lands in the composer **editable**; the user reads it and sends. Never auto-send (spec §5A.C). That's what makes imperfect STT safe to ship.
- Structure `useVoiceInput` so the cleanup call is a single swappable async seam — backend swap later is a one-function replace, no UI change.

---

## 4. `useVoiceOutput` — TTS, and the truth about "synced"
- You **cannot** run an FFT on `speechSynthesis` output — there's no audio stream to analyze. So "waveform synced to voice" is **time-based, not audio-reactive**: `VoiceWaveform` animates while `isSpeaking === true` and stops on `utterance.onend`. Don't over-promise real audio bars; a looping waveform during speech reads exactly as intended and costs nothing.
- Default the voice-output toggle **OFF**. Do not autoplay audio on load (browsers block it; it's also rude). Speaking only ever starts from a user having turned it on.
- Persist the toggle in `localStorage` (key e.g. `meera:voice-output`). Vite is CSR so there's no SSR hydration trap, but still read it in an effect, not during render.
- `speechSynthesis` is a documented footgun: cancel any in-flight utterance before starting a new one (`speechSynthesis.cancel()`), and cancel on unmount, or it leaks and overlaps across turns. Treat this like the `EscrowLockSequence` cleanup discipline.

---

## 5. `MeeraPresence` — placement, state, and color discipline
- **State machine derives from what already exists.** Don't invent a new global. `MeeraChatPanel` already owns a `phase` (`revealing → awaiting-input → thinking`). Presence state = `talking` (TTS `isSpeaking` OR reply actively streaming) → `thinking` (`phase === 'thinking'`) → else `idle`. Thread it via props from `MeeraChatPanel`/`MeeraWorkspace`; a tiny context is acceptable only if prop-drilling gets ugly — your call, document it.
- **Corner-dock inside the canvas/panel container, `position: absolute` — NOT `position: fixed` to the viewport.** Fixed will collide with `BrandLayout` chrome and the mobile safe-area bottom bar. It must never overlap the Pay CTA, the `EscrowPill`, or the composer send button. Reserve its box so there's no layout shift.
  - Desktop: bottom corner of the `LivingCanvas` (right panel).
  - Mobile (full-screen chat / 90vh sheet): keep it clear of the composer and the safe-area Pay bar — top of the chat area is safer than the bottom.
- **Reuse `BrandAvatar`** as the base — presence is a state layer on top, not a second avatar. Reduced-motion → the existing static `BrandAvatar` + online dot, nothing animated (acceptance #4).
- **COLOR: presence ring + `VoiceWaveform` use `--meera-accent` (brand-themed) ONLY.** Escrow-green stays sacred — the waveform must **never** use `--meera-escrow`/`--meera-success`. This is the same rule that caught the blue-tick and cyan-slot-bar leaks; don't reintroduce one.
- `VoiceWaveform` is shared by presence-talking and mic-listening — build it once, drive it with an `active` prop, honor `useReducedMotion`.

---

## 6. TYPES — one allowed shim, no `any`
`SpeechRecognition` isn't in the default TS DOM lib. Add a minimal ambient declaration in `src/types/speech.d.ts` (or `vite-env.d.ts`) for the bits you use — do **not** scatter `as any` casts. Strict mode stays strict (base handoff / TECH-STACK). This typed shim is the only exception I'm granting.

---

## 7. CREDITS METERING (for Rohan) — stub only
No backend meter exists. Add a fire-and-forget seam `logVoiceUsage(kind: 'stt' | 'tts')` in `src/lib/` that's a no-op today (or console in dev). Rohan hooks the real cost model later. **Do not build billing.** Text-only path emits nothing → baseline unchanged (acceptance #5).

---

## 8. ACCESSIBILITY (non-negotiable, WCAG AA)
- `MicButton`: `aria-label`, `aria-pressed` while listening; visible focus ring (`--meera-accent-glow`).
- Transcription result region: `aria-live="polite"` so the cleaned text is announced.
- `VoiceToggle`: `aria-pressed`, labelled state.
- All waveform/presence motion bypassed under `prefers-reduced-motion`.

---

## 9. BUILD ORDER (lowest-risk first)
1. `VoiceWaveform` + `MeeraPresence` — pure frontend, **no permissions**, no API. Wire state from the existing `phase`. Ship the "alive" feel first; it can't fail.
2. `VoiceToggle` + `useVoiceOutput` (TTS) — additive, text already renders. localStorage persistence, cancel-on-unmount.
3. `MicButton` + `useVoiceInput` (STT + `cleanTranscript` mock seam) — edit-first into composer, full fallback chain.
4. Fold the five §5A acceptance checkboxes into your DoD pass; verify each in the browser.

## 10. SIGN-OFF GATES (I will check these)
- [ ] Zero new npm deps. Web Speech API only. Types via a `.d.ts` shim, no `any`.
- [ ] Text chat fully works with voice OFF, unsupported, or failed — no dead ends.
- [ ] `useVoiceInput` never auto-sends; cleaned text is editable first; numbers/handles/cities never altered by the mock.
- [ ] `cleanTranscript` is a labelled mock seam, swappable for backend LLM with no UI change.
- [ ] Presence + waveform use `--meera-accent` only; escrow-green untouched.
- [ ] `prefers-reduced-motion` → static presence (online dot only); no waveform motion.
- [ ] `speechSynthesis.cancel()` on new utterance + on unmount (no audio leak/overlap).
- [ ] Presence is `absolute` within the panel, never overlaps Pay CTA / EscrowPill / composer; no layout shift.
- [ ] `npm run build` + `tsc --noEmit` clean (only the 2 pre-existing FadeUp/WordReveal errors allowed).

---

**One line for Ananya:** Make Meera feel alive and let brands talk to her — but every voice path is a bonus layered on a text chat that already works without it, the cleanup never changes what a brand actually said, and escrow-green stays sacred. Mock the STT-cleanup seam, ship presence first, no new deps.
