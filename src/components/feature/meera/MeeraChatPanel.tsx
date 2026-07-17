import { useEffect, useRef, useState } from 'react'

import { VoiceToggle } from '@/components/ui/voice-toggle'
import { MeeraOrb } from '@/components/feature/meera/MeeraOrb'
import { MessageBubble } from '@/components/feature/meera/MessageBubble'
import { ThinkingState } from '@/components/feature/meera/ThinkingState'
import { Composer } from '@/components/feature/meera/Composer'
import { CreditPaywall } from '@/components/feature/meera/CreditPaywall'
import { useVoiceOutput } from '@/hooks/useVoiceOutput'
import { useMeeraStream } from '@/hooks/useMeeraStream'
import { MEERA_IDENTITY, MEERA_THINKING_STEPS } from '@/data/meera-copy'
import { MEERA_CONVERSATION_SCRIPT } from '@/data/meera-mock'
import type { MeeraFunctionCall } from '@/data/stage-config'
import { ApiError, isApiLive } from '@/lib/api'
import { meeraApi } from '@/lib/meera-api'
import { cn } from '@/lib/utils'

interface MeeraChatPanelProps {
  /**
   * Fired when a stage-driving function call resolves. `data` carries the
   * live tool_result payload (04 §4 `MeeraToolResultEvent.data`) so the
   * Living Canvas can render real numbers instead of the mock script —
   * `undefined` in mock mode, where the mock stage components already own
   * their own data.
   */
  onFunctionCall: (call: MeeraFunctionCall, data?: unknown) => void
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

/** Every tool name the live Python stream can report via `tool_result` (04 §4 / 02 §3). */
const MEERA_FUNCTION_CALLS: readonly MeeraFunctionCall[] = [
  'analyze_site',
  'calculate_budget',
  'show_creators',
  'request_payment',
  'confirm_launch',
]

function isMeeraFunctionCall(name: string): name is MeeraFunctionCall {
  return (MEERA_FUNCTION_CALLS as readonly string[]).includes(name)
}

/**
 * Turn engine phase. In MOCK mode the cursor only moves on a real user send
 * — nothing plays on a bare mount timer except the reveal of the CURRENT
 * turn's lines and the thinking beat that follows a send (see
 * `data/meera-mock.ts` for the `MeeraTurn` script). In LIVE mode this same
 * phase drives the real SSE turn: 'thinking' from send until the stream's
 * `done` event, 'awaiting-input' otherwise.
 */
export type Phase = 'revealing' | 'awaiting-input' | 'thinking'

/** Loose "looks like a website" check for the turn-0 URL gate (mock only). Deterministic, not NLP. */
function looksLikeSite(text: string) {
  return /\.[a-z]{2,}/i.test(text.trim()) || /^https?:\/\//i.test(text.trim())
}

/** Left panel: sticky header + turn-driven conversation (live AI stream, or the scripted mock fallback) + composer. */
export function MeeraChatPanel({
  onFunctionCall,
  paused = false,
  onPhaseChange,
  onSpeakingChange,
  className,
}: MeeraChatPanelProps) {
  // P10: real turns go straight to the Python SSE edge; VITE_API_MODE=mock
  // keeps the old scripted reveal so the workspace still demos with no
  // backend running. Computed once — the env doesn't change at runtime.
  const [live] = useState(() => isApiLive())

  const [messages, setMessages] = useState<RenderedMessage[]>([])
  const [turnIndex, setTurnIndex] = useState(0)
  const [revealCount, setRevealCount] = useState(0)
  const [phase, setPhase] = useState<Phase>(live ? 'thinking' : 'revealing')
  const [thinkingKey, setThinkingKey] = useState<keyof typeof MEERA_THINKING_STEPS | null>(null)
  const scrollRef = useRef<HTMLDivElement>(null)
  const timerRef = useRef<number | null>(null)
  const nextIdRef = useRef(0)

  // Live-mode-only state — session handle, in-flight stream bookkeeping, and
  // the credit-paywall gate driven by a real 402/CREDITS_EXHAUSTED signal
  // instead of a scripted turn.
  const [conversationId, setConversationId] = useState<string | null>(null)
  const [awaitingFirstToken, setAwaitingFirstToken] = useState(live)
  const [liveThinkingSteps, setLiveThinkingSteps] = useState<string[]>(live ? ['Connecting to Meera'] : [])
  const [creditsExhausted, setCreditsExhausted] = useState(false)
  const stream = useMeeraStream()

  // Voice output (spec §5A.B) — additive only. Text renders unconditionally;
  // speak() is called AFTER a Meera line is already in `messages`, so audio
  // never gates the reply. Default OFF, persisted, cancel-on-unmount handled
  // inside the hook.
  const { supported: voiceOutputSupported, enabled: voiceEnabled, setEnabled: setVoiceEnabled, isSpeaking, speak } =
    useVoiceOutput()

  const turn = MEERA_CONVERSATION_SCRIPT[turnIndex]
  const conversationDone = turnIndex >= MEERA_CONVERSATION_SCRIPT.length

  const makeId = (prefix: string) => {
    nextIdRef.current += 1
    return `${prefix}-${nextIdRef.current}`
  }

  // Report phase + speaking state up to the caller so MeeraPresence (hosted
  // in LivingCanvas) can derive idle/thinking/talking without a new global.
  useEffect(() => {
    onPhaseChange?.(phase)
  }, [phase, onPhaseChange])

  useEffect(() => {
    onSpeakingChange?.(isSpeaking)
  }, [isSpeaking, onSpeakingChange])

  // LIVE ONLY — open (or resume) a Meera session on mount so the composer
  // has a conversationId to send turns against.
  useEffect(() => {
    if (!live) return
    let cancelled = false

    meeraApi
      .startSession()
      .then((session) => {
        if (cancelled) return
        setConversationId(session.conversationId)
        setAwaitingFirstToken(false)
        setLiveThinkingSteps([])
        setPhase('awaiting-input')
      })
      .catch(() => {
        if (cancelled) return
        setAwaitingFirstToken(false)
        setLiveThinkingSteps([])
        setPhase('awaiting-input')
        setMessages((prev) => [
          ...prev,
          { id: makeId('meera-session-error'), role: 'meera', text: "Couldn't reach Meera — refresh to try again." },
        ])
      })

    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [live])

  // MOCK ONLY — reveal the active turn's Meera lines one at a time, then open the composer for input.
  useEffect(() => {
    if (live || conversationDone || phase !== 'revealing') return

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
  }, [live, phase, revealCount, turnIndex, conversationDone])

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' })
  }, [messages, thinkingKey, awaitingFirstToken, liveThinkingSteps])

  useEffect(() => {
    return () => {
      if (timerRef.current !== null) window.clearTimeout(timerRef.current)
    }
  }, [])

  /**
   * LIVE send path — drives a real turn through `meeraApi.sendTurn` (Spring)
   * then `useMeeraStream` (Python SSE edge). Tokens stream into the
   * in-progress assistant bubble; `tool_result` events bubble up via
   * `onFunctionCall` so the Living Canvas stage keeps advancing exactly like
   * it did off the mock script.
   */
  const handleLiveSend = (text: string) => {
    if (!conversationId || phase !== 'awaiting-input' || paused || creditsExhausted) return

    setMessages((prev) => [...prev, { id: makeId('brand'), role: 'brand', text }])
    setPhase('thinking')
    setAwaitingFirstToken(true)
    setLiveThinkingSteps([])

    const assistantMessageId = makeId('meera')
    let assistantText = ''

    meeraApi
      .sendTurn(conversationId, text)
      .then((turnRes) => {
        setMessages((prev) => [...prev, { id: assistantMessageId, role: 'meera', text: '' }])

        stream.open(turnRes.streamUrl, turnRes.streamToken, {
          onThinking: (event) => {
            if (event.done) return
            setLiveThinkingSteps((prev) => (prev.includes(event.step) ? prev : [...prev, event.step]))
          },
          onToken: (event) => {
            setAwaitingFirstToken(false)
            assistantText += event.text
            const renderedText = assistantText
            setMessages((prev) => prev.map((m) => (m.id === assistantMessageId ? { ...m, text: renderedText } : m)))
          },
          onToolResult: (event) => {
            if (event.status === 'ok' && isMeeraFunctionCall(event.name)) {
              onFunctionCall(event.name, event.data)
            }
          },
          onDone: () => {
            setAwaitingFirstToken(false)
            setLiveThinkingSteps([])
            setPhase('awaiting-input')
            // Voice is additive only — the bubble above is already fully
            // rendered by the time we speak it (Priya's voice handoff §5A.B).
            speak(assistantText)
          },
          onError: (event) => {
            setAwaitingFirstToken(false)
            setLiveThinkingSteps([])

            if (event.code === 'CREDITS_EXHAUSTED') {
              setCreditsExhausted(true)
              setPhase('awaiting-input')
              return
            }

            // Recovery per useMeeraStream's documented contract (04 §4.5):
            // never re-POST after a stream error — that would double-spend
            // credits. Fetch the finalized turn instead.
            meeraApi
              .getMessagesAfter(conversationId, turnRes.messageId)
              .then((fallbackMessages) => {
                const recovered = fallbackMessages
                  .filter((m) => m.role === 'ASSISTANT')
                  .map((m) => ({ id: m.id, role: 'meera' as const, text: m.content }))
                setMessages((prev) => [...prev.filter((m) => m.id !== assistantMessageId), ...recovered])
              })
              .catch(() => {
                setMessages((prev) =>
                  prev.map((m) =>
                    m.id === assistantMessageId
                      ? { ...m, text: event.message ?? "Didn't catch that — try again?" }
                      : m,
                  ),
                )
              })
              .finally(() => setPhase('awaiting-input'))
          },
        })
      })
      .catch((err: unknown) => {
        setAwaitingFirstToken(false)
        setPhase('awaiting-input')
        if (err instanceof ApiError && err.code === 'CREDITS_EXHAUSTED') {
          setCreditsExhausted(true)
        }
        setMessages((prev) => [
          ...prev,
          { id: makeId('meera-error'), role: 'meera', text: 'Something went wrong sending that — try again?' },
        ])
      })
  }

  /**
   * MOCK send path (typed or a quick-reply chip). This is the ONLY thing
   * that advances the scripted turn cursor — the engine never auto-plays
   * past an awaiting-input turn on its own.
   */
  const handleMockSend = (text: string) => {
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

  const handleSend = live ? handleLiveSend : handleMockSend
  const showPaywall = paused || (live && creditsExhausted)
  const liveThinkingDisplaySteps = liveThinkingSteps.length > 0 ? liveThinkingSteps : ['Thinking…']

  // Living-presence state for the header orb, derived from the existing turn
  // phase + TTS isSpeaking (no new state machine): talking > thinking > idle.
  const presenceState = isSpeaking ? 'talking' : phase === 'thinking' ? 'thinking' : 'idle'
  const headerStatus =
    presenceState === 'talking'
      ? 'Speaking…'
      : presenceState === 'thinking'
        ? 'Thinking…'
        : MEERA_IDENTITY.subtitle

  return (
    <div className={cn('flex h-full flex-col bg-meera-bg-subtle', className)}>
      {/* Sticky header */}
      <div className="flex shrink-0 items-center gap-3 border-b border-meera-border bg-meera-surface px-4 py-3">
        <div className="relative h-10 w-10 shrink-0">
          <MeeraOrb state={presenceState} className="h-full w-full" />
        </div>
        <div className="min-w-0 flex-1">
          <p className="text-sm font-semibold text-meera-text">{MEERA_IDENTITY.name}</p>
          <p className="text-xs text-meera-text-muted">{headerStatus}</p>
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
        {!live && thinkingKey && <ThinkingState steps={MEERA_THINKING_STEPS[thinkingKey]} />}
        {live && awaitingFirstToken && <ThinkingState steps={liveThinkingDisplaySteps} />}
      </div>

      {/* Composer / paywall */}
      <div className="shrink-0 border-t border-meera-border bg-meera-surface p-4">
        {showPaywall ? (
          <CreditPaywall onFund={() => onFunctionCall('request_payment')} />
        ) : (
          <Composer
            onSend={handleSend}
            suggestedReplies={!live && !conversationDone && phase === 'awaiting-input' ? turn.suggestedReplies : []}
            sendLocked={live ? !conversationId || phase !== 'awaiting-input' : conversationDone || phase !== 'awaiting-input'}
          />
        )}
      </div>
    </div>
  )
}
