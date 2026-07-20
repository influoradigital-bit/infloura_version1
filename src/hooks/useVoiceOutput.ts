import { useCallback, useEffect, useRef, useState } from 'react'

import { logVoiceUsage } from '@/lib/voice-usage'
import { meeraApi } from '@/lib/meera-api'
import { stripMarkdownForSpeech } from '@/lib/strip-markdown-for-speech'

const STORAGE_KEY = 'meera:voice-output'
const RATE_STORAGE_KEY = 'meera:voice-rate'

/** Clamp to the range SpeechSynthesis reliably honors across engines. */
const MIN_RATE = 0.5
const MAX_RATE = 1.5
const DEFAULT_RATE = 1

/**
 * Meera is a female persona, so prefer a female Indian-English voice when the
 * machine has one instead of the engine default (often a male US voice).
 * Matched at speak() time because getVoices() populates asynchronously and
 * can change (e.g. Edge fetching its Natural voices). Preference order:
 * known female en-IN names → any en-IN → known female en-* names → engine
 * default (null). Name matching is the only API the spec gives us here.
 */
const PREFERRED_VOICE_NAMES = ['neerja', 'heera'] as const
const FEMALE_FALLBACK_NAMES = ['zira', 'samantha', 'female'] as const

function pickMeeraVoice(): SpeechSynthesisVoice | null {
  const voices = window.speechSynthesis.getVoices()
  if (voices.length === 0) return null

  const byName = (names: readonly string[], requireLang?: string) =>
    voices.find(
      (v) =>
        (!requireLang || v.lang.toLowerCase().startsWith(requireLang)) &&
        names.some((n) => v.name.toLowerCase().includes(n)),
    ) ?? null

  return (
    byName(PREFERRED_VOICE_NAMES) ??
    voices.find((v) => v.lang.toLowerCase().startsWith('en-in')) ??
    byName(FEMALE_FALLBACK_NAMES, 'en') ??
    null
  )
}

/**
 * TTS hook for Meera's replies (spec §5A.B / Priya's voice handoff §4).
 *
 * Contract (non-negotiable): a reply must NEVER wait on audio. This hook
 * only ever *reads* a reply that has already rendered as text — callers
 * pass the text to `speak()` after it's on screen, never before. `speak()`
 * itself returns instantly; the server-TTS attempt and any fallback happen
 * asynchronously behind it, so there is nothing on the critical path.
 *
 * Voice source (server-first, silent fallback):
 * - Every call first tries `meeraApi.speak(text)` (Sarvam TTS via the brand
 *   API). In mock mode this resolves to `null` immediately, so local dev
 *   always uses the browser voice below.
 * - If that resolves to a playable WAV blob, it's played through a single
 *   reusable `<audio>` element.
 * - If it resolves to `null`, or the audio fails to *start* playing, this
 *   falls back to the existing `SpeechSynthesisUtterance` path unchanged.
 * - A failure that happens *after* audio has already started (e.g. a
 *   mid-stream decode error) just stops silently — text is already fully
 *   rendered, so there's no dead end to recover into.
 *
 * Race safety: only one "voice attempt" is ever live. Each `speak()` call
 * mints a token; `stop()`, `setEnabled(false)`, and every subsequent
 * `speak()` bump it. A Sarvam fetch/playback that resolves after its token
 * has been superseded is discarded instead of played — the same
 * "cancel-before-you-start" rule the SpeechSynthesis path already followed.
 *
 * - Default OFF. No autoplay — browsers block it, and it's rude besides.
 *   Speaking only ever starts because the user turned the toggle on.
 * - Persisted in localStorage (read in an effect, not during render — no
 *   SSR here since this is a Vite CSR app, but same discipline).
 * - `isSpeaking` is time-based truth (audio/utterance start/end events),
 *   which is exactly what MeeraPresence's "talking" state and VoiceWaveform
 *   consume.
 */
export interface UseVoiceOutputResult {
  supported: boolean
  enabled: boolean
  setEnabled: (value: boolean) => void
  isSpeaking: boolean
  speak: (text: string) => void
  /**
   * Speak an ordered list of sentences back-to-back, synthesizing the NEXT
   * while the current one plays, and calling `onSentenceStart(i)` at the moment
   * sentence i's audio actually begins — so the caller can reveal that
   * sentence's TEXT in lockstep with the voice (text + audio together).
   * `onAllDone` fires when the whole sequence finishes OR immediately when voice
   * is unsupported/disabled (the caller then reveals text on its own).
   */
  speakSequence: (
    sentences: string[],
    opts?: { onSentenceStart?: (index: number) => void; onAllDone?: () => void },
  ) => void
  stop: () => void
  /** Speaking rate, clamped to [0.5, 1.5]. Persisted like the on/off toggle. */
  rate: number
  setRate: (value: number) => void
}

function detectSupport(): boolean {
  return typeof window !== 'undefined' && 'speechSynthesis' in window && typeof SpeechSynthesisUtterance !== 'undefined'
}

export function useVoiceOutput(): UseVoiceOutputResult {
  const supportedRef = useRef(detectSupport())
  const supported = supportedRef.current

  const [enabled, setEnabledState] = useState(false)
  const [isSpeaking, setIsSpeaking] = useState(false)
  const [rate, setRateState] = useState(DEFAULT_RATE)
  // Read at speak() time via a ref so `speak` stays referentially stable when
  // the slider moves — consumers capture `speak` in effects, and a rate-dep
  // rebuild would leave them holding a stale closure with the old rate.
  const rateRef = useRef(DEFAULT_RATE)
  const utteranceRef = useRef<SpeechSynthesisUtterance | null>(null)
  const audioRef = useRef<HTMLAudioElement | null>(null)
  // Bumped on every speak()/stop()/disable so a late-arriving Sarvam blob
  // (or a play() promise that resolves after the fact) can recognize it's
  // stale and discard itself instead of playing over whatever's current.
  const speakTokenRef = useRef(0)

  // Read the persisted choices in an effect, not during render.
  useEffect(() => {
    if (!supported) return
    try {
      const stored = window.localStorage.getItem(STORAGE_KEY)
      if (stored === 'on') setEnabledState(true)
      const storedRate = Number(window.localStorage.getItem(RATE_STORAGE_KEY))
      if (Number.isFinite(storedRate) && storedRate >= MIN_RATE && storedRate <= MAX_RATE) {
        setRateState(storedRate)
        rateRef.current = storedRate
      }
    } catch {
      // localStorage unavailable (private mode, etc.) — stay defaults.
    }
  }, [supported])

  const setRate = useCallback((value: number) => {
    const clamped = Math.min(MAX_RATE, Math.max(MIN_RATE, value))
    setRateState(clamped)
    rateRef.current = clamped
    try {
      window.localStorage.setItem(RATE_STORAGE_KEY, String(clamped))
    } catch {
      // Non-fatal — the rate still applies for this session.
    }
  }, [])

  // Pause + fully release the shared <audio> element (revokes its object
  // URL). Safe to call when nothing is playing.
  const releaseAudio = useCallback(() => {
    const audio = audioRef.current
    if (!audio) return
    audio.onended = null
    audio.onerror = null
    audio.pause()
    if (audio.src) URL.revokeObjectURL(audio.src)
    audioRef.current = null
  }, [])

  const setEnabled = useCallback(
    (value: boolean) => {
      setEnabledState(value)
      try {
        window.localStorage.setItem(STORAGE_KEY, value ? 'on' : 'off')
      } catch {
        // Non-fatal — the toggle still works for this session.
      }
      if (!value) {
        speakTokenRef.current += 1
        releaseAudio()
        window.speechSynthesis?.cancel()
        setIsSpeaking(false)
      }
    },
    [releaseAudio],
  )

  const stop = useCallback(() => {
    speakTokenRef.current += 1
    releaseAudio()
    if (!supported) return
    window.speechSynthesis.cancel()
    setIsSpeaking(false)
  }, [supported, releaseAudio])

  const speak = useCallback(
    (rawText: string) => {
      // Text has already rendered by the time this is ever called — speaking
      // is purely additive from here. If unsupported or disabled, no-op.
      if (!supported || !enabled || !rawText) return

      // Strip Markdown so TTS speaks the words, not "asterisk asterisk"/"dash
      // dash dash" — the chat bubble still renders the full Markdown visually,
      // this only affects what's spoken. Single chokepoint: covers the Sarvam
      // path AND the browser-voice fallback below. If stripping leaves nothing
      // (e.g. a reply that was only a divider), there's nothing to speak.
      const text = stripMarkdownForSpeech(rawText)
      if (!text) return

      // New utterance supersedes anything in flight — same "cancel before
      // you start" rule as before, now covering the Sarvam attempt too.
      const token = (speakTokenRef.current += 1)
      window.speechSynthesis.cancel()
      releaseAudio()

      logVoiceUsage('tts')

      const speakWithBrowser = () => {
        if (speakTokenRef.current !== token) return // superseded — discard

        const utterance = new SpeechSynthesisUtterance(text)
        const voice = pickMeeraVoice()
        if (voice) utterance.voice = voice
        utterance.rate = rateRef.current
        utteranceRef.current = utterance

        utterance.onstart = () => setIsSpeaking(true)
        utterance.onend = () => setIsSpeaking(false)
        utterance.onerror = () => {
          // TTS failure is silent from the user's perspective — text is
          // already fully rendered, so there is no dead end to fall back to.
          setIsSpeaking(false)
        }

        window.speechSynthesis.speak(utterance)
      }

      // Kick off the server-TTS attempt but never await it before
      // returning — speak() itself must stay synchronous/instant.
      meeraApi
        .speak(text)
        .then((blob) => {
          if (speakTokenRef.current !== token) return // superseded — discard
          if (!blob) {
            speakWithBrowser()
            return
          }

          const url = URL.createObjectURL(blob)
          const audio = new Audio(url)
          audio.playbackRate = rateRef.current
          audioRef.current = audio

          audio.onended = () => {
            if (speakTokenRef.current === token) setIsSpeaking(false)
            releaseAudio()
          }
          audio.onerror = () => {
            // Failure after playback had already started: stay silent, same
            // contract as the SpeechSynthesis path — no fallback mid-stream.
            if (speakTokenRef.current === token) setIsSpeaking(false)
            releaseAudio()
          }

          audio.play().then(
            () => {
              if (speakTokenRef.current !== token) {
                // A newer speak()/stop() arrived while play() was pending.
                releaseAudio()
                return
              }
              setIsSpeaking(true)
            },
            () => {
              // Playback failed to start — fall back to the browser voice.
              releaseAudio()
              speakWithBrowser()
            },
          )
        })
        .catch(() => {
          // meeraApi.speak() contractually never throws, but guard anyway.
          if (speakTokenRef.current !== token) return
          speakWithBrowser()
        })
    },
    [supported, enabled, releaseAudio],
  )

  const speakSequence = useCallback(
    (
      sentences: string[],
      opts?: { onSentenceStart?: (index: number) => void; onAllDone?: () => void },
    ) => {
      // Supersede anything in flight — same "cancel before you start" rule as speak().
      const token = (speakTokenRef.current += 1)
      window.speechSynthesis.cancel()
      releaseAudio()

      // onAllDone must fire at most once — guard against any double-advance
      // (e.g. a browser firing both onended and onerror) reaching the end twice.
      let allDoneFired = false
      const fireAllDone = () => {
        if (allDoneFired) return
        allDoneFired = true
        opts?.onAllDone?.()
      }

      if (!supported || !enabled || sentences.length === 0) {
        // Voice off/unsupported: nothing to play. The caller still needs to
        // finish, so resolve immediately and let it reveal text on its own.
        fireAllDone()
        return
      }

      logVoiceUsage('tts')

      // Speakable text per sentence (a divider-only line strips to '' — it still
      // reveals as text, it just isn't voiced).
      const spoken = sentences.map((s) => stripMarkdownForSpeech(s))

      // Prefetch buffer: sentence index -> in-flight Sarvam blob (or null).
      const fetches = new Map<number, Promise<Blob | null>>()
      const prefetch = (i: number) => {
        if (i >= spoken.length || fetches.has(i)) return
        const text = spoken[i]
        fetches.set(i, text ? meeraApi.speak(text).catch(() => null) : Promise.resolve(null))
      }

      // Fall back to the browser voice for ONE sentence, keeping the sequence going.
      const speakSentenceWithBrowser = (i: number, text: string, advance: () => void) => {
        // Browser voice doesn't use the shared <audio> element — clear it so a
        // later releaseAudio() doesn't touch a stale clip from a prior sentence.
        audioRef.current = null
        const utterance = new SpeechSynthesisUtterance(text)
        const voice = pickMeeraVoice()
        if (voice) utterance.voice = voice
        utterance.rate = rateRef.current
        utterance.onstart = () => {
          if (speakTokenRef.current !== token) return
          setIsSpeaking(true)
          opts?.onSentenceStart?.(i)
        }
        utterance.onend = () => {
          if (speakTokenRef.current === token) advance()
        }
        utterance.onerror = () => {
          if (speakTokenRef.current === token) advance()
        }
        window.speechSynthesis.speak(utterance)
      }

      const playIndex = (i: number) => {
        if (speakTokenRef.current !== token) return // superseded
        if (i >= sentences.length) {
          setIsSpeaking(false)
          fireAllDone()
          return
        }

        prefetch(i)
        prefetch(i + 1) // look one ahead so the next clip is ready when this ends

        const advance = () => playIndex(i + 1)
        const text = spoken[i]
        if (!text) {
          // Nothing to voice for this line — reveal its text and move on.
          opts?.onSentenceStart?.(i)
          advance()
          return
        }

        fetches.get(i)!.then((blob) => {
          if (speakTokenRef.current !== token) return
          if (!blob) {
            speakSentenceWithBrowser(i, text, advance)
            return
          }

          const url = URL.createObjectURL(blob)
          const audio = new Audio(url)
          audio.playbackRate = rateRef.current
          audioRef.current = audio
          audio.onended = () => {
            URL.revokeObjectURL(url)
            if (speakTokenRef.current === token) advance()
          }
          audio.onerror = () => {
            URL.revokeObjectURL(url)
            if (speakTokenRef.current === token) advance()
          }
          audio.play().then(
            () => {
              if (speakTokenRef.current !== token) {
                URL.revokeObjectURL(url)
                return
              }
              setIsSpeaking(true)
              opts?.onSentenceStart?.(i)
            },
            () => {
              // Couldn't start this clip — browser-voice this one sentence.
              URL.revokeObjectURL(url)
              if (speakTokenRef.current === token) speakSentenceWithBrowser(i, text, advance)
            },
          )
        })
      }

      playIndex(0)
    },
    [supported, enabled, releaseAudio],
  )

  // Cancel on unmount so nothing keeps talking after the panel is gone.
  useEffect(() => {
    return () => {
      speakTokenRef.current += 1
      releaseAudio()
      if (supported) window.speechSynthesis.cancel()
    }
  }, [supported, releaseAudio])

  return { supported, enabled, setEnabled, isSpeaking, speak, speakSequence, stop, rate, setRate }
}
