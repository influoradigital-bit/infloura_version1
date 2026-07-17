import { useCallback, useEffect, useRef, useState } from 'react'

import { logVoiceUsage } from '@/lib/voice-usage'

const STORAGE_KEY = 'meera:voice-output'

/**
 * TTS hook for Meera's replies (spec §5A.B / Priya's voice handoff §4).
 *
 * Contract (non-negotiable): a reply must NEVER wait on audio. This hook
 * only ever *reads* a reply that has already rendered as text — callers
 * pass the text to `speak()` after it's on screen, never before. If speech
 * synthesis is unsupported or errors, the text stays exactly as it was;
 * there is nothing for this hook to "fall back" to because it was never on
 * the critical path in the first place.
 *
 * - Default OFF. No autoplay — browsers block it, and it's rude besides.
 *   Speaking only ever starts because the user turned the toggle on.
 * - Persisted in localStorage (read in an effect, not during render — no
 *   SSR here since this is a Vite CSR app, but same discipline).
 * - `speechSynthesis.cancel()` before every new utterance AND on unmount,
 *   so nothing leaks or overlaps across turns.
 * - `isSpeaking` is time-based truth (utterance start/end events), which is
 *   exactly what MeeraPresence's "talking" state and VoiceWaveform consume
 *   — there is no audio stream to FFT-analyze from speechSynthesis.
 */
export interface UseVoiceOutputResult {
  supported: boolean
  enabled: boolean
  setEnabled: (value: boolean) => void
  isSpeaking: boolean
  speak: (text: string) => void
  stop: () => void
}

function detectSupport(): boolean {
  return typeof window !== 'undefined' && 'speechSynthesis' in window && typeof SpeechSynthesisUtterance !== 'undefined'
}

export function useVoiceOutput(): UseVoiceOutputResult {
  const supportedRef = useRef(detectSupport())
  const supported = supportedRef.current

  const [enabled, setEnabledState] = useState(false)
  const [isSpeaking, setIsSpeaking] = useState(false)
  const utteranceRef = useRef<SpeechSynthesisUtterance | null>(null)

  // Read the persisted choice in an effect, not during render.
  useEffect(() => {
    if (!supported) return
    try {
      const stored = window.localStorage.getItem(STORAGE_KEY)
      if (stored === 'on') setEnabledState(true)
    } catch {
      // localStorage unavailable (private mode, etc.) — stay default OFF.
    }
  }, [supported])

  const setEnabled = useCallback(
    (value: boolean) => {
      setEnabledState(value)
      try {
        window.localStorage.setItem(STORAGE_KEY, value ? 'on' : 'off')
      } catch {
        // Non-fatal — the toggle still works for this session.
      }
      if (!value) {
        window.speechSynthesis?.cancel()
        setIsSpeaking(false)
      }
    },
    [],
  )

  const stop = useCallback(() => {
    if (!supported) return
    window.speechSynthesis.cancel()
    setIsSpeaking(false)
  }, [supported])

  const speak = useCallback(
    (text: string) => {
      // Text has already rendered by the time this is ever called — speaking
      // is purely additive from here. If unsupported or disabled, no-op.
      if (!supported || !enabled || !text) return

      // Documented footgun: cancel any in-flight utterance before starting
      // a new one, or utterances leak/overlap across turns.
      window.speechSynthesis.cancel()

      const utterance = new SpeechSynthesisUtterance(text)
      utteranceRef.current = utterance

      utterance.onstart = () => setIsSpeaking(true)
      utterance.onend = () => setIsSpeaking(false)
      utterance.onerror = () => {
        // TTS failure is silent from the user's perspective — text is
        // already fully rendered, so there is no dead end to fall back to.
        setIsSpeaking(false)
      }

      logVoiceUsage('tts')
      window.speechSynthesis.speak(utterance)
    },
    [supported, enabled],
  )

  // Cancel on unmount so nothing keeps talking after the panel is gone.
  useEffect(() => {
    return () => {
      if (supported) window.speechSynthesis.cancel()
    }
  }, [supported])

  return { supported, enabled, setEnabled, isSpeaking, speak, stop }
}
