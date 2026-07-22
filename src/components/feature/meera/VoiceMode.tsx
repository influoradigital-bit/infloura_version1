import { useCallback, useEffect, useRef, useState } from 'react'
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion'
import { Mic, X } from 'lucide-react'

import { MeeraOrb, type MeeraOrbState } from '@/components/feature/meera/MeeraOrb'
import { useVoiceInput } from '@/hooks/useVoiceInput'
import type { Phase } from '@/components/feature/meera/MeeraChatPanel'
import { cn } from '@/lib/utils'

/**
 * Hands-free voice conversation mode — the "Claude mobile voice" experience for
 * Meera. A full-screen overlay that runs a continuous loop:
 *
 *   listen → (you stop talking) → auto-send → Meera thinks → Meera speaks →
 *   listen again
 *
 * with no taps in between. It owns none of the send/stream/TTS machinery — that
 * all stays in {@code MeeraChatPanel}. This component only:
 *   - captures speech ({@code useVoiceInput} with silence auto-stop),
 *   - hands each finished utterance to {@code onSend},
 *   - watches the parent's turn {@code phase} + {@code isSpeaking} to know when
 *     the reply cycle has fully finished, then re-opens the mic.
 *
 * Voice OUTPUT is force-enabled while this overlay is open (a silent voice
 * "conversation" makes no sense) and restored to the user's prior preference on
 * exit.
 */

interface VoiceModeProps {
  open: boolean
  onExit: () => void
  /**
   * Send a finished utterance through the normal turn pipeline (handleSend).
   * `lang` (W3) is the Sarvam-detected language for the utterance
   * (`lang_detected` from `/meera/voice/transcribe`), so the reply can be
   * spoken back in the same language. Undefined when no detection is
   * available.
   */
  onSend: (text: string, lang?: string) => void
  /** Parent turn-engine phase — 'thinking' while Meera composes a reply. */
  phase: Phase
  /** Parent TTS speaking flag — true while Meera's reply audio is playing. */
  isSpeaking: boolean
  /** Whether voice OUTPUT (TTS) is currently on, and the setter to force it. */
  voiceOutputEnabled: boolean
  setVoiceOutputEnabled: (value: boolean) => void
  /** Cut off any in-flight Meera speech (used on exit). */
  stopSpeaking: () => void
}

type LoopState = 'listening' | 'processing' | 'speaking' | 'idle'

/** Consecutive STT failures before we stop auto-retrying and wait for a tap. */
const MAX_CONSECUTIVE_FAILURES = 3
/** Trailing silence (ms) that ends an utterance and auto-sends it. */
const SILENCE_AUTO_STOP_MS = 1500

export function VoiceMode({
  open,
  onExit,
  onSend,
  phase,
  isSpeaking,
  voiceOutputEnabled,
  setVoiceOutputEnabled,
  stopSpeaking,
}: VoiceModeProps) {
  const reduceMotion = useReducedMotion()

  // True from the moment we send an utterance until the reply cycle (think +
  // speak) has fully finished. Guards the auto-restart so we never re-open the
  // mic over Meera's own turn.
  const awaitingReplyRef = useRef(false)
  const failureCountRef = useRef(0)
  const [status, setStatus] = useState<string>('Listening…')
  const [lastHeard, setLastHeard] = useState<string>('')
  // Set true once STT keeps failing — surfaces a manual "tap to talk" instead
  // of a tight retry loop (e.g. mic permission denied).
  const [needsTap, setNeedsTap] = useState(false)

  const {
    supported,
    phase: inputPhase,
    start,
    stop,
  } = useVoiceInput({
    autoStopSilenceMs: SILENCE_AUTO_STOP_MS,
    onResult: (text, lang) => {
      failureCountRef.current = 0
      const trimmed = text.trim()
      if (!trimmed) {
        // Empty transcript — treat like a soft failure and re-listen.
        awaitingReplyRef.current = false
        return
      }
      setLastHeard(trimmed)
      awaitingReplyRef.current = true
      onSend(trimmed, lang)
    },
    onError: () => {
      failureCountRef.current += 1
      awaitingReplyRef.current = false
      if (failureCountRef.current >= MAX_CONSECUTIVE_FAILURES) {
        setNeedsTap(true)
      }
    },
  })

  // Force voice output ON while the overlay is open; restore on close.
  useEffect(() => {
    if (!open) return
    const prev = voiceOutputEnabled
    if (!prev) setVoiceOutputEnabled(true)
    return () => {
      if (!prev) setVoiceOutputEnabled(false)
    }
    // Intentionally only on open/close — we don't want a mid-session manual
    // toggle to re-trigger this restore dance.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  // Derive the human-facing loop state from the input phase + parent signals.
  const loopState: LoopState = isSpeaking
    ? 'speaking'
    : phase === 'thinking' || inputPhase === 'transcribing' || awaitingReplyRef.current
      ? 'processing'
      : inputPhase === 'listening'
        ? 'listening'
        : 'idle'

  // Once the reply cycle is fully done (parent back to awaiting-input AND not
  // speaking), clear the guard so the restart effect can re-open the mic.
  useEffect(() => {
    if (awaitingReplyRef.current && phase === 'awaiting-input' && !isSpeaking) {
      awaitingReplyRef.current = false
    }
  }, [phase, isSpeaking])

  // The loop's engine: whenever we're open, idle (not listening, not awaiting a
  // reply, not speaking), and STT hasn't been disabled, (re)open the mic.
  useEffect(() => {
    if (!open || !supported || needsTap) return
    if (awaitingReplyRef.current || isSpeaking || phase === 'thinking') return
    if (inputPhase === 'listening' || inputPhase === 'transcribing') return
    start()
  }, [open, supported, needsTap, isSpeaking, phase, inputPhase, start])

  // Status copy per loop state.
  useEffect(() => {
    // When voice input can't run at all (insecure http origin — no getUserMedia /
    // Web Speech), don't sit forever on "Getting ready…": say why. Real fix is HTTPS.
    if (!supported) {
      setStatus('Voice needs a secure (HTTPS) connection')
      return
    }
    switch (loopState) {
      case 'listening':
        setStatus('Listening…')
        break
      case 'processing':
        setStatus('Thinking…')
        break
      case 'speaking':
        setStatus('Speaking…')
        break
      default:
        setStatus(needsTap ? 'Tap the mic to talk' : 'Getting ready…')
    }
  }, [loopState, needsTap, supported])

  const handleExit = useCallback(() => {
    stop()
    stopSpeaking()
    awaitingReplyRef.current = false
    onExit()
  }, [stop, stopSpeaking, onExit])

  const handleManualTap = useCallback(() => {
    failureCountRef.current = 0
    setNeedsTap(false)
    awaitingReplyRef.current = false
    start()
  }, [start])

  const orbState: MeeraOrbState =
    loopState === 'speaking' ? 'talking' : loopState === 'processing' ? 'thinking' : 'idle'

  // A live "you're listening" ring pulse on the orb container while listening —
  // small affordance so it's obvious the mic is hot without a full-screen UI.
  const listening = loopState === 'listening'

  return (
    <AnimatePresence>
      {open && (
        <motion.div
          className="flex items-center gap-3 rounded-2xl border border-meera-border bg-[#0B0F1A] px-3 py-2.5 text-white"
          initial={reduceMotion ? false : { opacity: 0, y: 8 }}
          animate={{ opacity: 1, y: 0 }}
          exit={reduceMotion ? undefined : { opacity: 0, y: 8 }}
          transition={{ duration: 0.2 }}
          role="region"
          aria-label="Meera voice conversation"
        >
          {/* Compact living orb — small enough to live inside the composer slot,
              so the chat transcript above stays fully visible. */}
          <div className={cn('relative shrink-0', listening && 'ring-2 ring-[#6D5AE6]/50 rounded-full')}>
            <MeeraOrb state={orbState} size={44} />
          </div>

          {/* Status + last-heard preview. */}
          <div className="min-w-0 flex-1">
            <motion.p
              key={status}
              initial={reduceMotion ? false : { opacity: 0, y: 4 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.18 }}
              className="text-sm font-medium text-white/90"
            >
              {status}
            </motion.p>
            <p className="truncate text-xs text-white/45">
              {lastHeard && loopState !== 'listening'
                ? `“${lastHeard}”`
                : supported
                  ? 'Just talk — Meera listens when you pause.'
                  : 'Open Influora over HTTPS to use voice — your mic is blocked on an insecure connection.'}
            </p>
          </div>

          {/* Manual mic — only when the auto-loop has paused (repeated STT
              failure / permission issue). */}
          {needsTap && (
            <button
              type="button"
              onClick={handleManualTap}
              aria-label="Start listening"
              className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-[#6D5AE6] text-white transition-transform hover:scale-105 active:scale-95"
            >
              <Mic className="h-4 w-4" />
            </button>
          )}

          {/* Exit voice mode -> back to the text composer. */}
          <button
            type="button"
            onClick={handleExit}
            aria-label="Exit voice mode"
            className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-white/10 text-white/80 transition-colors hover:bg-white/20"
          >
            <X className="h-4 w-4" />
          </button>
        </motion.div>
      )}
    </AnimatePresence>
  )
}

export default VoiceMode
