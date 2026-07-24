# AI Review: Meera Voice ("Talk to Meera")

**Reviewer:** Ash · **Date:** 2026-07-23 · **Trigger:** user reports (1) "Talk to Meera" stuck on *"Getting ready…"*, (2) "the voice is like robot"
**Env:** live test box `http://200.141.1.6/` (dev profile, HTTP, no TLS)

## How It Works (traced flow)

**Voice OUTPUT (TTS):** Meera text reply → `useVoiceOutput.speak()` → `meeraApi.speak()` → Spring `POST /api/v1/meera/voice/speak` → `MeeraVoiceAiClient` → influora-ai `POST /voice/speak` → `SarvamProvider.speak()`. On a WAV blob → played via `<audio>` (natural voice). On `null`/non-audio → **browser `SpeechSynthesisUtterance` fallback = the robotic voice.**
Files: `src/hooks/useVoiceOutput.ts`, `src/lib/meera-api.ts:651`, `influora-api/.../web/MeeraController.java:237`, `.../integration/ai/MeeraVoiceAiClient.java`, `influora-ai/app/routes/voice.py:292`.

**Voice INPUT (STT):** overlay open → `useVoiceInput.start()` → `getUserMedia`+`MediaRecorder` (Sarvam path) or `webkitSpeechRecognition` (fallback) → `POST /meera/voice/transcribe`. `VoiceMode` drives a listen→send→think→speak loop.
Files: `src/components/feature/meera/VoiceMode.tsx`, `src/hooks/useVoiceInput.ts`.

## Findings

### P0 — Robotic voice: server TTS never reached (config) — **FIXED**
**Where:** `MeeraVoiceAiClient.java:96` default `http://localhost:8000`; only `application-prod.yml` set `influora.voice-ai.base-url`.
**Issue:** the box runs the **dev profile**, which never set the voice base URL, so every `/voice/speak` call hit `localhost:8000` *inside the api container* → `HttpHostConnectException` (confirmed in live logs: `MeeraVoiceAiClient: transport failure calling /voice/speak … Connect to http://l…`) → `SpeakResult.fallback()` → Spring returns bare `{"fallback":true}` → browser `SpeechSynthesis` (robot). Live curl reproduced: `POST /meera/voice/speak` → `200 {"fallback":true}` (17 bytes, not audio). Not a Sarvam/key problem — Spring never reached Python. The code comment at `MeeraVoiceAiClient.java:61-63` literally predicted this.
**Fix:** `application.yml` (default profile) `influora.voice-ai.base-url: ${VOICE_AI_BASE_URL:${MEERA_CHAT_AI_BASE_URL:http://localhost:8000}}` — reuses the AI-container URL already set on every containerized deploy; prod's no-default override + local `localhost` both preserved.
**Gain:** natural Sarvam TTS instead of the OS robot voice. Also fixes server-side STT (same client/base-url) for when input is unblocked.

### P0 — Voice input hangs on "Getting ready…" (insecure origin) — **partial: UX fixed; real fix = HTTPS**
**Where:** `useVoiceInput.ts` support detection; `http://200.141.1.6` is not a secure context.
**Issue:** browsers gate `getUserMedia`/Web-Speech to **secure contexts (HTTPS or localhost)**. Verified live: `isSecureContext:false`, `navigator.mediaDevices:undefined`, but `webkitSpeechRecognition` constructor still exists → old `supported` was a **false positive** → the loop kept "starting" but never reached `listening`, so status sat on *"Getting ready…"* forever with no explanation.
**Fix (code):** gate `supported` on `window.isSecureContext`; `VoiceMode` now shows *"Voice needs a secure (HTTPS) connection"* instead of an endless spinner. **Real fix (deploy):** serve over HTTPS (domain + TLS — already the planned prod step; the prod Caddy compose does TLS). Voice input cannot work on the raw-IP HTTP box regardless of code.
**Gain:** honest UX now; full voice input returns the moment the app is on HTTPS.

### P2 — TTS/STT provider health is invisible to the client
Both `speak`/`transcribe` collapse every failure (transport, spend-gate, provider-miss) into the same silent `{"fallback":true}`. Good for UX resilience, but it masked this transport misconfig for however long voice has been "robotic." Consider a lightweight server metric/log-count on `voice_speak` fallbacks so a 100%-fallback rate is alertable.

## Data & Training Roadmap
- **Now:** log `voice_speak`/`voice_transcribe` fallback-reason counts (transport vs spend vs provider) — would have surfaced this instantly.
- **Next:** capture `lang_detected` distribution to tune the Sarvam voice/lang defaults.
- **Later:** no fine-tuning relevant (Sarvam is a hosted provider).

## Verdict: **SHIP** (P0 output fix deployed & verified; input P0 needs the HTTPS deploy — tracked, code UX hardened)
