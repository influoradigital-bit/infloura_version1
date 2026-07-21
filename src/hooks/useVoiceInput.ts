import { useCallback, useEffect, useRef, useState } from 'react'

import { cleanTranscript } from '@/lib/clean-transcript'
import { logVoiceUsage } from '@/lib/voice-usage'
import { meeraApi } from '@/lib/meera-api'

/**
 * STT hook for the composer's mic button (spec §5A.C / Priya's voice
 * handoff §0, §2, §3).
 *
 * Contract (non-negotiable): this hook only ever WRITES into the composer's
 * existing value — edit-first. It NEVER calls onSend itself. Every failure
 * path (unsupported browser, permission denied, onerror, no-speech) routes
 * to the same graceful text fallback; there is no dead end.
 *
 * Voice source (Sarvam-first, silent fallback — mirrors `useVoiceOutput`'s
 * server-first/browser-fallback discipline for TTS, in reverse):
 * - Primary: `start()` opens the mic via `MediaRecorder`/`getUserMedia` and
 *   records while the user holds/toggles the mic button. `stop()` ends the
 *   clip and sends it to `meeraApi.transcribe()` (Sarvam STT via the brand
 *   API). A successful transcript is delivered via `onResult` AS-IS — the
 *   backend already cleaned it, so `cleanTranscript` never runs on this
 *   path.
 * - Fallback to browser `webkitSpeechRecognition` (the previous
 *   implementation, unchanged) when: recording/transcribing isn't even
 *   attempted because `MediaRecorder`/`getUserMedia` is unavailable or mic
 *   permission is denied at `start()` time; OR the Sarvam path was
 *   attempted but `meeraApi.transcribe` resolved to `null` (mock mode, the
 *   backend's own soft-fail, a non-2xx response, or a network error) —
 *   `meeraApi.transcribe` contractually never throws, so `null` is the only
 *   signal to watch for. `cleanTranscript` still runs on this path's raw
 *   transcript, same as before.
 *
 * Flow: idle -> listening (mic active, either recording or browser
 * recognition) -> transcribing (Sarvam upload, or the browser-fallback
 * cleanup running) -> the caller receives the text via `onResult` and is
 * expected to drop it into the composer's input, editable, never auto-sent.
 */
export type VoiceInputPhase = 'idle' | 'listening' | 'transcribing' | 'error'

/**
 * Hard cap on transcript length (Kabir A4), applied to both the Sarvam and
 * browser-fallback paths. Not an exploitable vector — the transcript is
 * always rendered via React text interpolation, never HTML — but a
 * pathological multi-minute dictation could otherwise dump an unbounded
 * string into component state / the composer textarea (self-DoS at worst).
 * ~4000 chars is generous for any realistic voice message.
 */
export const MAX_TRANSCRIPT_LENGTH = 4000

/**
 * Hard backstop (ms) for a single hands-free utterance. The VAD normally ends an
 * utterance on trailing silence; this only fires if the VAD never does (e.g. Web
 * Audio unavailable), so the mic can never hang on "Listening…" indefinitely.
 */
const MAX_UTTERANCE_MS = 20000

export interface UseVoiceInputOptions {
  /**
   * Called with the cleaned (but still editable) transcript. Never auto-sends.
   * `lang` (W3) is the Sarvam-detected language code (`lang_detected` from
   * `/meera/voice/transcribe`, e.g. `hi-IN`) for the utterance just
   * transcribed — passed through so a caller can thread it into the spoken
   * reply (`useVoiceOutput.speak`'s `lang` param). Undefined on the browser
   * `webkitSpeechRecognition` fallback path, which never detects a language.
   */
  onResult: (cleanedText: string, lang?: string) => void
  /** Called on any failure path with the fallback copy to show the user. */
  onError?: (message: string) => void
  lang?: string
  /**
   * Hands-free mode: when set (>0), the Sarvam recording path auto-stops after
   * this many ms of trailing silence (once speech has actually been detected),
   * instead of waiting for a manual `stop()`. Opt-in — the default composer mic
   * passes nothing here, so its manual tap-to-stop behavior is completely
   * unchanged. Only the {@code VoiceMode} conversation loop sets this.
   */
  autoStopSilenceMs?: number
}

export interface UseVoiceInputResult {
  supported: boolean
  phase: VoiceInputPhase
  isListening: boolean
  start: () => void
  stop: () => void
}

/** MIME types tried in preference order for the Sarvam recording path. */
const PREFERRED_AUDIO_MIME_TYPES = ['audio/webm;codecs=opus', 'audio/webm', 'audio/ogg;codecs=opus'] as const

function pickRecorderMimeType(): string | undefined {
  if (typeof MediaRecorder === 'undefined' || typeof MediaRecorder.isTypeSupported !== 'function') return undefined
  return PREFERRED_AUDIO_MIME_TYPES.find((type) => MediaRecorder.isTypeSupported(type))
}

function detectRecorderSupport(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof MediaRecorder !== 'undefined' &&
    Boolean(navigator.mediaDevices?.getUserMedia)
  )
}

function detectBrowserSttSupport(): boolean {
  if (typeof window === 'undefined') return false
  return Boolean(window.SpeechRecognition || window.webkitSpeechRecognition)
}

export function useVoiceInput({
  onResult,
  onError,
  lang = 'en-IN',
  autoStopSilenceMs,
}: UseVoiceInputOptions): UseVoiceInputResult {
  // Detected once — capability doesn't change over the hook's lifetime.
  const recorderSupportedRef = useRef(detectRecorderSupport())
  const browserSttSupportedRef = useRef(detectBrowserSttSupport())
  const supported = recorderSupportedRef.current || browserSttSupportedRef.current

  const [phase, setPhase] = useState<VoiceInputPhase>('idle')
  const recognitionRef = useRef<SpeechRecognition | null>(null)
  const mediaRecorderRef = useRef<MediaRecorder | null>(null)
  const mediaStreamRef = useRef<MediaStream | null>(null)
  const chunksRef = useRef<Blob[]>([])

  // Hands-free silence detection (only used when autoStopSilenceMs is set).
  const audioContextRef = useRef<AudioContext | null>(null)
  const vadRafRef = useRef<number | null>(null)
  const vadSilenceStartRef = useRef<number | null>(null)
  const vadSpeechDetectedRef = useRef(false)
  // Hands-free hard backstop timer (fires only if the VAD never stops us).
  const maxDurationTimerRef = useRef<number | null>(null)

  const fail = useCallback(
    (message: string) => {
      setPhase('error')
      onError?.(message)
      // Return to idle so the mic button is usable again immediately —
      // "error" is a transient flash state, not a stuck state.
      setPhase('idle')
    },
    [onError],
  )

  // Tear down the Web Audio silence-detection graph. Safe to call when it was
  // never started (autoStopSilenceMs unset) — every ref is null in that case.
  const stopSilenceDetection = useCallback(() => {
    if (vadRafRef.current !== null) {
      cancelAnimationFrame(vadRafRef.current)
      vadRafRef.current = null
    }
    const ctx = audioContextRef.current
    audioContextRef.current = null
    vadSilenceStartRef.current = null
    vadSpeechDetectedRef.current = false
    if (ctx && ctx.state !== 'closed') {
      // close() returns a promise; we don't await it — the graph is detached
      // from the (already-stopped) stream by the time this runs.
      void ctx.close().catch(() => {})
    }
  }, [])

  const releaseRecordingResources = useCallback(() => {
    stopSilenceDetection()
    if (maxDurationTimerRef.current !== null) {
      clearTimeout(maxDurationTimerRef.current)
      maxDurationTimerRef.current = null
    }
    mediaStreamRef.current?.getTracks().forEach((track) => track.stop())
    mediaStreamRef.current = null
    mediaRecorderRef.current = null
    chunksRef.current = []
  }, [stopSilenceDetection])

  /**
   * Hands-free auto-stop: watches the live mic level and calls {@code onSilence}
   * once the user has spoken and then gone quiet for {@code autoStopSilenceMs}.
   * Uses a time-domain RMS from an AnalyserNode — no external VAD model, works
   * offline. Threshold/min-speech are conservative so background hiss doesn't
   * trip it and a half-second pause mid-sentence doesn't cut the user off.
   */
  const startSilenceDetection = useCallback(
    (stream: MediaStream, silenceMs: number, onSilence: () => void) => {
      const AudioCtx = window.AudioContext || (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext
      if (!AudioCtx) return // no Web Audio — hands-free just relies on manual/other stop

      let ctx: AudioContext
      try {
        ctx = new AudioCtx()
      } catch {
        return
      }
      audioContextRef.current = ctx
      // Chrome starts an AudioContext created after an await (past the click
      // gesture) in "suspended" state — a suspended context feeds the analyser
      // flat silence, so speech is NEVER detected and the mic never auto-stops
      // (the "stuck on Listening" bug). Resume it so the VAD sees real levels.
      if (ctx.state === 'suspended') void ctx.resume().catch(() => {})
      const source = ctx.createMediaStreamSource(stream)
      const analyser = ctx.createAnalyser()
      analyser.fftSize = 2048
      source.connect(analyser)

      const buffer = new Uint8Array(analyser.fftSize)
      // RMS threshold on the 0..1-centered-at-0.5 time-domain signal. ~0.015 is
      // a quiet room's noise floor; speech sits well above it.
      const SPEECH_RMS = 0.02
      const MIN_SPEECH_MS = 300 // must hear this much speech before arming auto-stop
      let speechStartAt = 0

      const tick = () => {
        const now = performance.now()
        analyser.getByteTimeDomainData(buffer)
        let sumSquares = 0
        for (let i = 0; i < buffer.length; i++) {
          const centered = (buffer[i] - 128) / 128
          sumSquares += centered * centered
        }
        const rms = Math.sqrt(sumSquares / buffer.length)

        if (rms >= SPEECH_RMS) {
          // Speaking. Mark speech detected once it's sustained past MIN_SPEECH_MS.
          if (speechStartAt === 0) speechStartAt = now
          if (!vadSpeechDetectedRef.current && now - speechStartAt >= MIN_SPEECH_MS) {
            vadSpeechDetectedRef.current = true
          }
          vadSilenceStartRef.current = null
        } else {
          // Silence. Only counts once real speech has been heard.
          speechStartAt = 0
          if (vadSpeechDetectedRef.current) {
            if (vadSilenceStartRef.current === null) vadSilenceStartRef.current = now
            else if (now - vadSilenceStartRef.current >= silenceMs) {
              onSilence()
              return // stop the loop; onSilence triggers recorder.stop() -> teardown
            }
          }
        }

        vadRafRef.current = requestAnimationFrame(tick)
      }

      vadRafRef.current = requestAnimationFrame(tick)
    },
    [],
  )

  // ---- Browser SpeechRecognition path -----------------------------------
  // Used both as the sole path when Sarvam recording isn't available at
  // all, and as the fallback re-listen when a Sarvam transcribe attempt
  // comes back null. Behavior is unchanged from the pre-Sarvam hook.
  const startBrowserRecognition = useCallback(() => {
    if (!browserSttSupportedRef.current) {
      fail('Voice input is not supported in this browser — type it instead.')
      return
    }

    const RecognitionCtor = window.SpeechRecognition || window.webkitSpeechRecognition
    if (!RecognitionCtor) {
      fail('Voice input is not supported in this browser — type it instead.')
      return
    }

    const recognition = new RecognitionCtor()
    recognition.lang = lang
    recognition.continuous = false
    recognition.interimResults = false
    recognition.maxAlternatives = 1
    recognitionRef.current = recognition

    recognition.onstart = () => setPhase('listening')

    recognition.onresult = (event: SpeechRecognitionEvent) => {
      const rawTranscript = event.results?.[0]?.[0]?.transcript ?? ''
      if (!rawTranscript.trim()) {
        fail('Didn’t catch that — type it instead?')
        return
      }
      // Cap before any further processing (Kabir A4) — belt-and-suspenders
      // against an unbounded dictation bloating state/textarea.
      const transcript = rawTranscript.slice(0, MAX_TRANSCRIPT_LENGTH)

      setPhase('transcribing')
      logVoiceUsage('stt')

      // Browser-fallback path only — the Sarvam path's server-cleaned
      // result never runs through this.
      cleanTranscript(transcript)
        .then((cleaned) => {
          onResult(cleaned)
          setPhase('idle')
        })
        .catch(() => {
          // Even the mock cleanup failing must not lose the user's words —
          // fall back to the raw transcript rather than a dead end.
          onResult(transcript)
          setPhase('idle')
        })
    }

    recognition.onerror = () => {
      fail('Didn’t catch that — type it instead?')
    }

    recognition.onnomatch = () => {
      fail('Didn’t catch that — type it instead?')
    }

    recognition.onend = () => {
      // If we never reached onresult (e.g. user stopped early), settle back
      // to idle rather than leaving the button stuck on "listening".
      setPhase((current) => (current === 'listening' ? 'idle' : current))
    }

    try {
      recognition.start()
    } catch {
      fail('Didn’t catch that — type it instead?')
    }
  }, [lang, onResult, fail])

  // ---- Sarvam recording path (primary) -----------------------------------
  const handleRecordingStopped = useCallback(
    (recorder: MediaRecorder) => {
      const blob = new Blob(chunksRef.current, { type: recorder.mimeType || 'audio/webm' })
      releaseRecordingResources()

      if (blob.size === 0) {
        // Nothing captured (e.g. stopped instantly) — same graceful
        // fallback as an empty browser transcript.
        startBrowserRecognition()
        return
      }

      setPhase('transcribing')
      logVoiceUsage('stt')

      meeraApi
        .transcribe(blob)
        .then((result) => {
          if (!result) {
            // Mock mode, backend soft-fail, non-2xx, or network error —
            // meeraApi.transcribe contractually never throws, so `null` is
            // the one signal to act on. Re-listen via the browser fallback
            // rather than losing the user's turn.
            startBrowserRecognition()
            return
          }

          const text = (result.cleanedText || result.rawTranscript).slice(0, MAX_TRANSCRIPT_LENGTH)
          if (!text.trim()) {
            startBrowserRecognition()
            return
          }

          // Server already cleaned this — no client-side cleanTranscript pass.
          onResult(text, result.langDetected)
          setPhase('idle')
        })
        .catch(() => {
          // Belt-and-suspenders: meeraApi.transcribe shouldn't throw, but
          // guard the same way useVoiceOutput guards meeraApi.speak().
          startBrowserRecognition()
        })
    },
    [releaseRecordingResources, startBrowserRecognition, onResult],
  )

  const startSarvamRecording = useCallback(async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
      mediaStreamRef.current = stream

      const mimeType = pickRecorderMimeType()
      const recorder = mimeType ? new MediaRecorder(stream, { mimeType }) : new MediaRecorder(stream)
      mediaRecorderRef.current = recorder
      chunksRef.current = []

      recorder.ondataavailable = (event: BlobEvent) => {
        if (event.data.size > 0) chunksRef.current.push(event.data)
      }
      recorder.onstop = () => handleRecordingStopped(recorder)

      recorder.start()
      setPhase('listening')

      // Hands-free: auto-stop after trailing silence. onSilence stops the
      // recorder, which fires onstop -> handleRecordingStopped -> transcribe.
      if (autoStopSilenceMs && autoStopSilenceMs > 0) {
        const stopRecorder = () => {
          const rec = mediaRecorderRef.current
          if (rec && rec.state !== 'inactive') rec.stop()
        }
        startSilenceDetection(stream, autoStopSilenceMs, stopRecorder)
        // Hard backstop so a VAD that never fires can't hang on "Listening…".
        maxDurationTimerRef.current = window.setTimeout(stopRecorder, MAX_UTTERANCE_MS)
      }
    } catch {
      // getUserMedia permission denied/unavailable at call time — fall back
      // to browser recognition rather than a dead end.
      releaseRecordingResources()
      startBrowserRecognition()
    }
  }, [
    handleRecordingStopped,
    releaseRecordingResources,
    startBrowserRecognition,
    autoStopSilenceMs,
    startSilenceDetection,
  ])

  const start = useCallback(() => {
    if (!supported || phase === 'listening' || phase === 'transcribing') return

    if (recorderSupportedRef.current) {
      void startSarvamRecording()
    } else {
      // MediaRecorder/getUserMedia unavailable — browser path is the only
      // supported one.
      startBrowserRecognition()
    }
  }, [supported, phase, startSarvamRecording, startBrowserRecognition])

  const stop = useCallback(() => {
    const recorder = mediaRecorderRef.current
    if (recorder && recorder.state !== 'inactive') {
      recorder.stop() // -> onstop -> handleRecordingStopped -> transcribe
      return
    }
    recognitionRef.current?.stop()
  }, [])

  // Clean up any in-flight recording/recognition session on unmount.
  useEffect(() => {
    return () => {
      recognitionRef.current?.abort()
      const recorder = mediaRecorderRef.current
      if (recorder && recorder.state !== 'inactive') recorder.stop()
      mediaStreamRef.current?.getTracks().forEach((track) => track.stop())
      if (maxDurationTimerRef.current !== null) clearTimeout(maxDurationTimerRef.current)
      if (vadRafRef.current !== null) cancelAnimationFrame(vadRafRef.current)
      const ctx = audioContextRef.current
      if (ctx && ctx.state !== 'closed') void ctx.close().catch(() => {})
    }
  }, [])

  return { supported, phase, isListening: phase === 'listening', start, stop }
}
