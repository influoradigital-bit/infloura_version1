import { useEffect, useRef, useState } from 'react'

import { BrandAvatar } from '@/components/ui/brand-avatar'
import { VoiceToggle } from '@/components/ui/voice-toggle'
import { MessageBubble } from '@/components/feature/meera/MessageBubble'
import { ThinkingState } from '@/components/feature/meera/ThinkingState'
import { Composer } from '@/components/feature/meera/Composer'
import { CreditPaywall } from '@/components/feature/meera/CreditPaywall'
import { useVoiceOutput } from '@/hooks/useVoiceOutput'
import { MEERA_IDENTITY, MEERA_THINKING_STEPS } from '@/data/meera-copy'
import { MEERA_CONVERSATION_SCRIPT } from '@/data/meera-mock'
import type { MeeraFunctionCall } from '@/data/stage-config'
import { cn } from '@/lib/utils'

interface MeeraChatPanelProps {
  onFunctionCall: (call: MeeraFunctionCall) => void
  paused?: boolean
  /**
   * Reports the turn-engine phase up to the caller (e.g. MeeraWorkspace) so
   * MeeraPresence can derive its state without a new global — phase stays
   * owned here, this is a one-level prop bubble, not a context (Priya's
   * voice handoff §5).
   */
  onPhaseChange?: (phase: Phase) => void
  /** Reports TTS isSpeaking up so MeeraPresence can show the "talking" state. */
  onSpeakingChange?: (isSpeaking: boolean) => void
  className?: string
}

interface RenderedMessage {
  id: string
  role: 'meera' | 'brand'
  text: string
}

const STAGE_TO_CALL: Record<string, MeeraFunctionCall> = {
  snapshot: 'analyze_site',
  recommend: 'calculate_budget',
  matching: 'show_creators',
  funding: 'request_payment',
  live: 'confirm_launch',
}

/**
 * Turn engine phase. The cursor only moves on a real user send — nothing
 * plays on a bare mount timer except the reveal of the CURRENT turn's lines
 * and the thinking beat that follows a send. See `data/meera-mock.ts` for
 * the `MeeraTurn` script this drives.
 */
export type Phase = 'revealing' | 'awaiting-input' | 'thinking'

/** Loose "looks like a website" check for the turn-0 URL gate. Deterministic, not NLP. */
function looksLikeSite(text: string) {
  return /\.[a-z]{2,}/i.test(text.trim()) || /^https?:\/\//i.test(text.trim())
}

/** Left panel: sticky header + turn-driven mock conversation + composer. */
export function MeeraChatPanel({
  onFunctionCall,
  paused = false,
  onPhaseChange,
  onSpeakingChange,
  className,
}: MeeraChatPanelProps) {
  const [messages, setMessages] = useState<RenderedMessage[]>([])
  const [turnIndex, setTurnIndex] = useState(0)
  const [revealCount, setRevealCount] = useState(0)
  const [phase, setPhase] = useState<Phase>('revealing')
  const [thinkingKey, setThinkingKey] = useState<keyof typeof MEERA_THINKING_STEPS | null>(null)
  const scrollRef = useRef<HTMLDivElement>(null)
  const timerRef = useRef<number | null>(null)

  // Voice output (spec §5A.B) — additive only. Text renders unconditionally;
  // speak() is called AFTER a Meera line is already in `messages`, so audio
  // never gates the reply. Default OFF, persisted, cancel-on-unmount handled
  // inside the hook.
  const { supported: voiceOutputSupported, enabled: voiceEnabled, setEnabled: setVoiceEnabled, isSpeaking, speak } =
    useVoiceOutput()

  const turn = MEERA_CONVERSATION_SCRIPT[turnIndex]
  const conversationDone = turnIndex >= MEERA_CONVERSATION_SCRIPT.length

  // Report phase + speaking state up to the caller so MeeraPresence (hosted
  // in LivingCanvas) can derive idle/thinking/talking without a new global.
  useEffect(() => {
    onPhaseChange?.(phase)
  }, [phase, onPhaseChange])

  useEffect(() => {
    onSpeakingChange?.(isSpeaking)
  }, [isSpeaking, onSpeakingChange])

  // Reveal the active turn's Meera lines one at a time, then open the composer for input.
  useEffect(() => {
    if (conversationDone || phase !== 'revealing') return

    if (revealCount >= turn.meeraResponses.length) {
      setPhase('awaiting-input')
      return
    }

    const delay = turnIndex === 0 && revealCount === 0 ? 400 : 900
    timerRef.current = window.setTimeout(() => {
      const line = turn.meeraResponses[revealCount]
      setMessages((prev) => [...prev, { id: `${turn.id}-meera-${revealCount}`, role: 'meera', text: line }])
      setRevealCount((c) => c + 1)
      // Speak AFTER the line is already queued to render as text — voice is
      // strictly additive, never a gate on the reply appearing.
      speak(line)
    }, delay)

    return () => {
      if (timerRef.current !== null) window.clearTimeout(timerRef.current)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [phase, revealCount, turnIndex, conversationDone])

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' })
  }, [messages, thinkingKey])

  useEffect(() => {
    return () => {
      if (timerRef.current !== null) window.clearTimeout(timerRef.current)
    }
  }, [])

  /**
   * User sends (typed or a quick-reply chip). This is the ONLY thing that
   * advances the turn cursor — the engine never auto-plays past an
   * awaiting-input turn on its own.
   */
  const handleSend = (text: string) => {
    if (phase !== 'awaiting-input' || conversationDone) return

    // Turn 0 is gated on something that looks like a site — nudge otherwise,
    // without consuming the turn or appending a stray Meera line out of script.
    const nudge = turn.nudge
    if (turnIndex === 0 && !looksLikeSite(text) && nudge) {
      setMessages((prev) => [
        ...prev,
        { id: `${turn.id}-brand-nudge-${prev.length}`, role: 'brand', text },
        { id: `${turn.id}-meera-nudge-${prev.length}`, role: 'meera', text: nudge },
      ])
      return
    }

    setMessages((prev) => [...prev, { id: `${turn.id}-brand-${prev.length}`, role: 'brand', text }])
    setPhase('thinking')

    const resolveTurn = () => {
      if (turn.triggersStage) {
        onFunctionCall(STAGE_TO_CALL[turn.triggersStage])
      }
      setThinkingKey(null)
      setTurnIndex((i) => i + 1)
      setRevealCount(0)
      setPhase('revealing')
    }

    if (turn.showThinking) {
      setThinkingKey(turn.showThinking)
      timerRef.current = window.setTimeout(resolveTurn, 1400)
    } else {
      timerRef.current = window.setTimeout(resolveTurn, 700)
    }
  }

  return (
    <div className={cn('flex h-full flex-col bg-meera-bg-subtle', className)}>
      {/* Sticky header */}
      <div className="flex shrink-0 items-center gap-3 border-b border-meera-border bg-meera-surface px-4 py-3">
        <BrandAvatar initials="M" size="md" online />
        <div className="min-w-0 flex-1">
          <p className="text-sm font-semibold text-meera-text">{MEERA_IDENTITY.name}</p>
          <p className="text-xs text-meera-text-muted">{MEERA_IDENTITY.subtitle}</p>
          {/* Quiet confidence signal (Tejas §3 / Swapnil #3) — once, subtle, not a banner. */}
          <p className="truncate text-xs text-meera-text-muted opacity-70">
            {MEERA_IDENTITY.firstInIndiaBadge}
          </p>
        </div>
        {/* Voice output toggle — only rendered when TTS is actually supported
            (Priya's voice handoff §2): an unsupported browser never sees a
            control that does nothing. */}
        {voiceOutputSupported && <VoiceToggle enabled={voiceEnabled} onToggle={setVoiceEnabled} />}
      </div>

      {/* Message list */}
      <div ref={scrollRef} className="flex-1 space-y-3 overflow-y-auto px-4 py-4 scrollbar-thin">
        {messages.map((message) => (
          <MessageBubble key={message.id} role={message.role} text={message.text} />
        ))}
        {thinkingKey && <ThinkingState steps={MEERA_THINKING_STEPS[thinkingKey]} />}
      </div>

      {/* Composer / paywall */}
      <div className="shrink-0 border-t border-meera-border bg-meera-surface p-4">
        {paused ? (
          <CreditPaywall onFund={() => onFunctionCall('request_payment')} />
        ) : (
          <Composer
            onSend={handleSend}
            suggestedReplies={!conversationDone && phase === 'awaiting-input' ? turn.suggestedReplies : []}
            sendLocked={conversationDone || phase !== 'awaiting-input'}
          />
        )}
      </div>
    </div>
  )
}
