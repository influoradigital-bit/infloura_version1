import { useCallback, useEffect, useRef, useState } from 'react'

import { cleanTranscript } from '@/lib/clean-transcript'
import { logVoiceUsage } from '@/lib/voice-usage'

/**
 * STT hook for the composer's mic button (spec §5A.C / Priya's voice
 * handoff §0, §2, §3).
 *
 * Contract (non-negotiable): this hook only ever WRITES into the composer's
 * existing value — edit-first. It NEVER calls onSend itself. Every failure
 * path (unsupported browser, permission denied, onerror, no-speech) routes
 * to the same graceful text fallback; there is no dead end.
 *
 * Flow: idle -> listening (mic active) -> transcribing (cleanup running)
 * -> the caller receives the cleaned text via `onResult` and is expected to
 * drop it into the composer's input, editable, never auto-sent.
 */
export type VoiceInputPhase = 'idle' | 'listening' | 'transcribing' | 'error'

/**
 * Hard cap on raw STT transcript length (Kabir A4). Not an exploitable
 * vector — the transcript is always rendered via React text interpolation,
 * never HTML — but a pathological multi-minute dictation could otherwise
 * dump an unbounded string into component state / the composer textarea
 * (self-DoS at worst). Belt-and-suspenders: truncate before `cleanTranscript`
 * ever sees it. ~4000 chars is generous for any realistic voice message.
 */
export const MAX_TRANSCRIPT_LENGTH = 4000

export interface UseVoiceInputOptions {
  /** Called with the cleaned (but still editable) transcript. Never auto-sends. */
  onResult: (cleanedText: string) => void
  /** Called on any failure path with the fallback copy to show the user. */
  onError?: (message: string) => void
  lang?: string
}

export interface UseVoiceInputResult {
  supported: boolean
  phase: VoiceInputPhase
  isListening: boolean
  start: () => void
  stop: () => void
}

function detectSupport(): boolean {
  if (typeof window === 'undefined') return false
  return Boolean(window.SpeechRecognition || window.webkitSpeechRecognition)
}

export function useVoiceInput({ onResult, onError, lang = 'en-IN' }: UseVoiceInputOptions): UseVoiceInputResult {
  const supportedRef = useRef(detectSupport())
  const supported = supportedRef.current

  const [phase, setPhase] = useState<VoiceInputPhase>('idle')
  const recognitionRef = useRef<SpeechRecognition | null>(null)

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

  const start = useCallback(() => {
    if (!supported || phase === 'listening' || phase === 'transcribing') return

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

      // Single swappable async seam — cleanTranscript is a labelled mock
      // today; a real backend LLM cleanup call replaces the body only,
      // no change needed here or in the composer.
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
  }, [supported, phase, lang, onResult, fail])

  const stop = useCallback(() => {
    recognitionRef.current?.stop()
  }, [])

  // Clean up any in-flight recognition session on unmount.
  useEffect(() => {
    return () => {
      recognitionRef.current?.abort()
    }
  }, [])

  return { supported, phase, isListening: phase === 'listening', start, stop }
}
