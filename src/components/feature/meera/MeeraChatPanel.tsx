import { useEffect, useRef, useState } from 'react'
import { useReducedMotion } from 'framer-motion'
import { AudioLines } from 'lucide-react'

import { VoiceToggle } from '@/components/ui/voice-toggle'
import { MeeraOrb } from '@/components/feature/meera/MeeraOrb'
import { VoiceMode } from '@/components/feature/meera/VoiceMode'
import { MessageBubble } from '@/components/feature/meera/MessageBubble'
import { ThinkingState } from '@/components/feature/meera/ThinkingState'
import { Composer } from '@/components/feature/meera/Composer'
import { CreditPaywall } from '@/components/feature/meera/CreditPaywall'
import { ToolResultRenderer } from '@/components/feature/meera/ToolResultRenderer'
import { useVoiceOutput } from '@/hooks/useVoiceOutput'
import { useMeeraStream } from '@/hooks/useMeeraStream'
import { MEERA_IDENTITY, MEERA_THINKING_STEPS, MEERA_STARTER_TEMPLATES } from '@/data/meera-copy'
import { MEERA_CONVERSATION_SCRIPT } from '@/data/meera-mock'
import type { MeeraFunctionCall } from '@/data/stage-config'
import { ApiError, isApiLive } from '@/lib/api'
import { meeraApi } from '@/lib/meera-api'
import { uniqueId } from '@/lib/unique-id'
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
  /**
   * One-time starter text for the composer (e.g. the "Ask Meera" help
   * pre-seed threaded from the `?ask=` query param). Pre-fills the input
   * only — never auto-sent.
   */
  initialDraft?: string
  className?: string
}

/** LIVE-only: one `tool_result` event captured against the assistant turn it belongs to, for inline rendering. */
interface LiveToolResult {
  id: string
  name: string
  status: 'ok' | 'error'
  data?: unknown
  /** The real backend reason (e.g. "Internal request rejected: BAD_SERVICE_TOKEN") for an error result. */
  errorMessage?: string
}

/**
 * Pull the human-readable reason out of an error `tool_result` payload — the
 * loop yields `{error: <code>, message: <text>}` on a Spring/mesh failure
 * (influora-ai/app/tools/loop.py). Without this the chat only ever showed a
 * generic "Failed to run <Tool>", masking the actual cause (auth/mesh/scope).
 */
function toolErrorMessage(data: unknown): string | undefined {
  if (data && typeof data === 'object') {
    const d = data as { message?: unknown; error?: unknown }
    if (typeof d.message === 'string' && d.message) return d.message
    if (typeof d.error === 'string' && d.error) return d.error
  }
  return undefined
}

interface RenderedMessage {
  id: string
  role: 'meera' | 'brand'
  text: string
  /** LIVE-only — never set in mock mode. Rendered via ToolResultRenderer after this message's bubble. */
  toolResults?: LiveToolResult[]
}

const STAGE_TO_CALL: Record<string, MeeraFunctionCall> = {
  snapshot: 'analyze_site',
  recommend: 'calculate_budget',
  matching: 'show_creators',
  funding: 'request_payment',
  live: 'confirm_launch',
}

/**
 * Every tool name the LIVE Python stream can actually report via `tool_result`
 * — matches the backend tool contract one-for-one (influora-ai/app/tools/
 * schemas.py: show_creators, calculate_budget, create_campaign,
 * request_payment, confirm_launch, get_campaign_performance). `analyze_site`
 * is NOT a real backend tool — it only exists as a mock-mode stage trigger
 * (STAGE_TO_CALL above) and must never gate the live stream, or a live
 * `create_campaign` tool_result would be silently dropped here while a tool
 * that can never fire stayed allow-listed.
 *
 * `get_campaign_performance` (2.4, phase2-frontend-design.md §5.3): this
 * array IS the actual advancement gate — a name here that doesn't
 * byte-for-byte match `schemas.py`/`MeeraToolName.java`'s wire name makes
 * `onToolResult` below drop the tool_result entirely (see its early-return),
 * not just skip the stage. See stage-config.ts's `MeeraFunctionCall` union
 * for the full lockstep warning.
 */
const MEERA_FUNCTION_CALLS: readonly MeeraFunctionCall[] = [
  'calculate_budget',
  'show_creators',
  'create_campaign',
  'request_payment',
  'confirm_launch',
  'get_campaign_performance',
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

/**
 * Split a reply into sentences (incl. the Hindi danda ।) for sentence-by-sentence
 * voice + text sync (Option A). Whitespace is normalized here for display pacing;
 * the reveal always settles on the exact original text at the end, so nothing is
 * lost.
 */
function splitIntoSentences(text: string): string[] {
  return text
    .split(/(?<=[.!?।])\s+/)
    .map((s) => s.trim())
    .filter((s) => s !== '')
}

// R3b — transcript persistence. The live chat lived only in React state, so a
// tab switch/reload wiped it. We cache it in localStorage keyed by
// conversationId for an instant repaint, and rehydrate the authoritative copy
// from Spring on mount (getHistory).
const TRANSCRIPT_CACHE_PREFIX = 'meera:transcript:'
/** Cap cached turns so a very long chat can't overflow the localStorage quota. */
const TRANSCRIPT_CACHE_MAX = 60

function transcriptCacheKey(conversationId: string) {
  return `${TRANSCRIPT_CACHE_PREFIX}${conversationId}`
}

function readCachedTranscript(conversationId: string): RenderedMessage[] | null {
  try {
    const raw = window.localStorage.getItem(transcriptCacheKey(conversationId))
    if (!raw) return null
    const parsed: unknown = JSON.parse(raw)
    return Array.isArray(parsed) ? (parsed as RenderedMessage[]) : null
  } catch {
    return null
  }
}

function writeCachedTranscript(conversationId: string, messages: RenderedMessage[]) {
  try {
    window.localStorage.setItem(
      transcriptCacheKey(conversationId),
      JSON.stringify(messages.slice(-TRANSCRIPT_CACHE_MAX)),
    )
  } catch {
    // Quota exceeded / private mode — non-fatal; server history is the backstop.
  }
}

/**
 * F5a/F5b (Ash — wiki/ai-review/meera-blank-turn-ai-review.md) — client-side
 * safety net for a turn that streams zero tokens: version skew before
 * Vikram's server-side `empty_response` fallback lands, or a connection drop
 * mid-stream. Same copy as Spring's own empty_response path so both surfaces
 * read identically to the brand. Rendered through the ordinary MessageBubble
 * with no error styling — it's a recoverable, honest message, not a failure,
 * and the composer stays open so the brand can just retype.
 */
const MEERA_EMPTY_TURN_FALLBACK = "Sorry, I lost my train of thought there. Say that again?"

/** Left panel: sticky header + turn-driven conversation (live AI stream, or the scripted mock fallback) + composer. */
export function MeeraChatPanel({
  onFunctionCall,
  paused = false,
  onPhaseChange,
  onSpeakingChange,
  initialDraft,
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
  const revealTimerRef = useRef<number | null>(null)
  const reduceMotion = useReducedMotion()

  // Live-mode-only state — session handle, in-flight stream bookkeeping, and
  // the credit-paywall gate driven by a real 402/CREDITS_EXHAUSTED signal
  // instead of a scripted turn.
  const [conversationId, setConversationId] = useState<string | null>(null)
  const [awaitingFirstToken, setAwaitingFirstToken] = useState(live)
  const [liveThinkingSteps, setLiveThinkingSteps] = useState<string[]>(live ? ['Connecting to Meera'] : [])
  const [creditsExhausted, setCreditsExhausted] = useState(false)
  // Hands-free voice conversation overlay (the "Claude mobile voice" mode).
  // LIVE-only: it drives the real send/stream/speak pipeline, which the mock
  // scripted path doesn't expose in a turn-agnostic way.
  const [voiceModeOpen, setVoiceModeOpen] = useState(false)
  const stream = useMeeraStream()

  // Voice output (spec §5A.B) — additive only. Text renders unconditionally;
  // speak() is called AFTER a Meera line is already in `messages`, so audio
  // never gates the reply. Default OFF, persisted, cancel-on-unmount handled
  // inside the hook.
  const { supported: voiceOutputSupported, enabled: voiceEnabled, setEnabled: setVoiceEnabled, isSpeaking, speak, speakSequence, stop: stopSpeaking } =
    useVoiceOutput()

  const turn = MEERA_CONVERSATION_SCRIPT[turnIndex]
  const conversationDone = turnIndex >= MEERA_CONVERSATION_SCRIPT.length

  // Collision-proof message ids. The previous `${prefix}-${counter}` reset to 0
  // on every remount, so the first sends after a cache/backfill restore collided
  // with restored ids (brand-1, meera-2, tool-7) → React duplicate-key warnings
  // and dropped bubbles. uniqueId() is globally unique regardless of remount.
  const makeId = (prefix: string) => uniqueId(prefix)

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

        // Instant repaint from the local cache so a tab switch / reload doesn't
        // flash an empty chat while the server request is in flight (R3b).
        const cached = readCachedTranscript(session.conversationId)
        if (cached && cached.length) setMessages(cached)

        // Authoritative backfill from Spring. Text-only (server history carries
        // no inline tool cards), so we only replace the cached copy when the
        // server has at least as many turns — otherwise a still-catching-up
        // backend can't wipe a richer local transcript.
        return meeraApi
          .getHistory(session.conversationId)
          .then((history) => {
            if (cancelled || history.length === 0) return
            const mapped: RenderedMessage[] = history.map((m) => ({
              id: m.id,
              role: m.role === 'ASSISTANT' ? 'meera' : 'brand',
              text: m.content,
            }))
            // The `cancelled` check above already covers today's synchronous
            // path (nothing awaits between it and here), but guard the update
            // itself too so this stays correct if an await is ever added.
            setMessages((prev) => (cancelled ? prev : mapped.length >= prev.length ? mapped : prev))
          })
          .catch(() => {
            // History fetch failed — keep whatever the cache painted.
          })
          .finally(() => {
            if (cancelled) return
            setAwaitingFirstToken(false)
            setLiveThinkingSteps([])
            setPhase('awaiting-input')
          })
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
  }, [live])

  // R3b — persist the transcript whenever a turn settles (phase back to
  // awaiting-input), not on every streamed token. Keyed by conversationId so
  // the next mount can repaint it instantly before the server backfill lands.
  useEffect(() => {
    if (!live || !conversationId || phase !== 'awaiting-input' || messages.length === 0) return
    writeCachedTranscript(conversationId, messages)
  }, [live, conversationId, phase, messages])

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
      if (revealTimerRef.current !== null) window.clearInterval(revealTimerRef.current)
    }
  }, [])

  /**
   * Progressive local reveal of an already-complete reply — the A4 sync
   * backend returns the full authoritative text in the sendTurn response, so
   * "streaming" it over the network again is impossible without paying for a
   * second generation. This gives the same token-by-token feel from the text
   * we already hold. Instant under prefers-reduced-motion.
   */
  const revealReply = (assistantMessageId: string, fullText: string, lang?: string) => {
    setAwaitingFirstToken(false)

    const settle = () => {
      setMessages((prev) => prev.map((m) => (m.id === assistantMessageId ? { ...m, text: fullText } : m)))
      setLiveThinkingSteps([])
      setPhase('awaiting-input')
    }

    // Voice ON → text + audio TOGETHER (Option A): each sentence's text is
    // revealed at the moment its audio starts, so Priya's voice and the words
    // land in lockstep instead of "full text, pause, then the whole reply read
    // aloud". speakSequence prefetches the next sentence while the current one
    // plays, so playback is gap-free.
    if (voiceEnabled && voiceOutputSupported) {
      const sentences = splitIntoSentences(fullText)
      if (sentences.length > 0) {
        if (reduceMotion) {
          // Reduced motion: show the whole reply at once, but still stream the
          // audio sentence-by-sentence (audio pacing isn't "motion").
          setMessages((prev) => prev.map((m) => (m.id === assistantMessageId ? { ...m, text: fullText } : m)))
          speakSequence(sentences, { onAllDone: settle, lang })
        } else {
          speakSequence(sentences, {
            onSentenceStart: (i) => {
              const partial = sentences.slice(0, i + 1).join(' ')
              setMessages((prev) => prev.map((m) => (m.id === assistantMessageId ? { ...m, text: partial } : m)))
            },
            onAllDone: settle,
            lang,
          })
        }
        return
      }
    }

    // Voice OFF (default) → the existing local word-by-word reveal, no audio.
    const finish = () => {
      settle()
      // No-op while voice is disabled; kept so enabling voice mid-reveal still
      // gets the reply spoken (as a single clip on this legacy path).
      speak(fullText, lang)
    }

    if (reduceMotion) {
      finish()
      return
    }

    const words = fullText.split(/(\s+)/) // keep whitespace so joins are lossless
    let cursor = 0
    revealTimerRef.current = window.setInterval(() => {
      cursor = Math.min(cursor + 3, words.length)
      const partial = words.slice(0, cursor).join('')
      setMessages((prev) => prev.map((m) => (m.id === assistantMessageId ? { ...m, text: partial } : m)))
      if (cursor >= words.length) {
        if (revealTimerRef.current !== null) {
          window.clearInterval(revealTimerRef.current)
          revealTimerRef.current = null
        }
        finish()
      }
    }, 40)
  }

  /**
   * LIVE send path — drives a real turn through `meeraApi.sendTurn` (Spring).
   *
   * Two backend shapes are supported (2026-07-17 streaming-seam fix):
   *  - Synchronous (current A4 flow): Spring already called Python, persisted
   *    the reply, and returns it as `reply`. We render it directly with a
   *    local progressive reveal. Opening the SSE stream here would trigger a
   *    SECOND paid LLM generation of a turn that is already complete — so we
   *    deliberately never do that.
   *  - Stream-first (future turn split): no `reply` in the response — the
   *    browser owns the one-and-only generation via a POST SSE stream
   *    (useMeeraStream). Tokens stream into the in-progress bubble;
   *    `tool_result` events bubble up via `onFunctionCall` so the Living
   *    Canvas stage keeps advancing.
   */
  const handleLiveSend = (text: string, lang?: string) => {
    if (!conversationId || phase !== 'awaiting-input' || paused || creditsExhausted) return

    // F5b — never replay an empty/whitespace-only bubble back into the model
    // as history. A blank turn (this defect) or any other empty entry would
    // land as `{"role":"assistant","content":""}` mid-history, which is
    // invalid and poisons every subsequent turn on the thread (Ash traced 6
    // of 7 observed failures to exactly this).
    const history = [
      ...messages
        .filter((m) => m.text.trim() !== '')
        .map((m) => ({
          role: m.role === 'brand' ? 'user' : 'assistant',
          content: m.text,
        })),
      { role: 'user', content: text },
    ]

    setMessages((prev) => [...prev, { id: makeId('brand'), role: 'brand', text }])
    setPhase('thinking')
    setAwaitingFirstToken(true)
    setLiveThinkingSteps([])

    const assistantMessageId = makeId('meera')
    let assistantText = ''
    // F5a — the bubble is no longer created unconditionally up front (that's
    // what let a blank turn sit as a dead, permanently-empty bubble forever).
    // It's created lazily, the moment there's real content to show it with —
    // either the full sync reply below, or the first streamed token/tool
    // event further down. `onDone` is the backstop: if nothing ever arrives,
    // it adds the fallback bubble itself.
    let assistantBubbleAdded = false

    meeraApi
      .sendTurn(conversationId, text)
      .then((turnRes) => {
        if (turnRes.reply != null) {
          // Guard the synchronous path too — an empty/whitespace `reply`
          // (the same server-side truncation defect, just on the sync leg)
          // must not animate an empty bubble forever either.
          const replyText = turnRes.reply.trim() === '' ? MEERA_EMPTY_TURN_FALLBACK : turnRes.reply
          assistantBubbleAdded = true
          setMessages((prev) => [...prev, { id: assistantMessageId, role: 'meera', text: '' }])
          revealReply(assistantMessageId, replyText, lang)
          return
        }

        // chat.py contract: stream token authenticates via the Authorization
        // header (set inside useMeeraStream); the body carries workspace/turn
        // identity plus a dedicated per-turn on-behalf credential so
        // server-side tool calls are authorized instead of 401ing into plain
        // text. SECURITY FIX #1 (docs/security/meera-onbehalf-auth-security-
        // design.md §2): this MUST be the scoped, ≤120s on-behalf token
        // Spring minted alongside streamToken (turnRes.onBehalfToken) — never
        // the user's full-lifetime access token. Reading
        // localStorage.getItem('brand_token') here was the vulnerability the
        // design doc's finding #1 flags: it forwarded a durable,
        // full-account-scope credential to a separate service, straight out
        // of XSS-readable storage (the exact regression H-30's
        // in-memory token store, src/lib/token-store.ts, was written to
        // close).
        const streamBody = {
          workspace_id: turnRes.workspaceId ?? '',
          conversation_id: conversationId,
          turn_id: turnRes.messageId,
          onbehalf_jwt: turnRes.onBehalfToken ?? '',
          conversation: history,
        }

        stream.open(turnRes.streamUrl, turnRes.streamToken, {
          onThinking: (event) => {
            if (event.done) return
            setLiveThinkingSteps((prev) => (prev.includes(event.step) ? prev : [...prev, event.step]))
          },
          onToken: (event) => {
            setAwaitingFirstToken(false)
            assistantText += event.text
            const renderedText = assistantText
            // F5a — the bubble is born here, on the first real token, instead
            // of existing (empty) before the stream opened.
            setMessages((prev) => {
              if (!assistantBubbleAdded) {
                assistantBubbleAdded = true
                return [...prev, { id: assistantMessageId, role: 'meera', text: renderedText }]
              }
              return prev.map((m) => (m.id === assistantMessageId ? { ...m, text: renderedText } : m))
            })
          },
          onToolResult: (event) => {
            // Render the 5 stage tools AND the display-only present_options
            // pattern (tappable choice cards); anything else off the wire is
            // ignored. present_options drives NO Living Canvas stage.
            const isStageCall = isMeeraFunctionCall(event.name)
            if (!isStageCall && event.name !== 'present_options') return

            // Attach the result to the assistant turn it belongs to so it
            // renders inline right after that message (create_campaign sits
            // between matching/funding with no Living Canvas stage of its
            // own — advance() below simply no-ops for it via
            // stageForFunctionCall, so this is the only place its result
            // becomes visible). A tool_result can in principle arrive before
            // any token (F5a) — lazily create the bubble here too so the
            // result never gets silently dropped for lack of somewhere to
            // attach to.
            const toolResult = {
              id: makeId('tool'),
              name: event.name,
              status: event.status,
              data: event.data,
              errorMessage: event.status === 'error' ? toolErrorMessage(event.data) : undefined,
            }
            setMessages((prev) => {
              const exists = prev.some((m) => m.id === assistantMessageId)
              const base = exists
                ? prev
                : [...prev, { id: assistantMessageId, role: 'meera' as const, text: assistantText }]
              if (!exists) assistantBubbleAdded = true
              return base.map((m) =>
                m.id === assistantMessageId ? { ...m, toolResults: [...(m.toolResults ?? []), toolResult] } : m,
              )
            })

            // Only the stage tools advance the Living Canvas; present_options is
            // display-only. Re-narrow inline so the type guard applies.
            if (event.status === 'ok' && isMeeraFunctionCall(event.name)) {
              onFunctionCall(event.name, event.data)
            }
          },
          onDone: (event) => {
            setAwaitingFirstToken(false)
            setLiveThinkingSteps([])
            setPhase('awaiting-input')

            // F5a — the backstop. In the normal case (Vikram's server fix)
            // `finish_reason: 'empty_response'` arrives with an honest
            // fallback already streamed as tokens, so `assistantText` is
            // non-empty and this is a no-op. This only fires for the residual
            // case that fix can't cover client-side: version skew against an
            // older AI-service build, or a connection drop that ended the
            // stream with no tokens and no error event. Either way, the turn
            // must not resolve to a dead, permanently-empty bubble — and the
            // composer stays open (sendLocked only gates on phase/paused/
            // credits) so the brand can just retype.
            if (assistantText.trim() === '') {
              assistantText = MEERA_EMPTY_TURN_FALLBACK
              setMessages((prev) =>
                assistantBubbleAdded
                  ? prev.map((m) => (m.id === assistantMessageId ? { ...m, text: assistantText } : m))
                  : [...prev, { id: assistantMessageId, role: 'meera', text: assistantText }],
              )
              assistantBubbleAdded = true
            }
            void event // finish_reason (stop | tool_use | max_tokens | empty_response once Vikram's fix ships) — the emptiness check above is the actual gate, so it stays correct across server versions without depending on this literal.

            // Voice is additive only — the bubble above is already fully
            // rendered by the time we speak it (Priya's voice handoff §5A.B).
            // `lang` (W3): the language detected on the user's own utterance
            // for this turn, so the spoken reply matches (Hinglish in →
            // Hinglish out).
            speak(assistantText, lang)
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
                // F5a — same lazy-bubble concern applies here: if the stream
                // errored before any token/tool_result ever arrived, the
                // bubble doesn't exist yet, so a plain `.map` would silently
                // drop this fallback text instead of showing it.
                const fallbackText = event.message ?? "Didn't catch that — try again?"
                setMessages((prev) =>
                  prev.some((m) => m.id === assistantMessageId)
                    ? prev.map((m) => (m.id === assistantMessageId ? { ...m, text: fallbackText } : m))
                    : [...prev, { id: assistantMessageId, role: 'meera', text: fallbackText }],
                )
              })
              .finally(() => setPhase('awaiting-input'))
          },
        }, streamBody)
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
  // `lang` accepted (unused) only so this matches handleLiveSend's signature —
  // `handleSend` below is assigned one or the other, and both are passed to
  // Composer/VoiceMode's shared `onSend: (text, lang?) => void` prop type.
  // The mock scripted path has no real voice-language detection to honor.
  const handleMockSend = (text: string, _lang?: string) => {
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
        {/* Hands-free voice conversation entry (LIVE only — drives the real
            turn pipeline). Opens the full-screen VoiceMode loop. */}
        {live && (
          <button
            type="button"
            onClick={() => setVoiceModeOpen(true)}
            aria-label="Start voice conversation"
            title="Talk to Meera"
            className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border border-meera-border bg-meera-surface text-meera-text-muted transition-colors hover:text-meera-text"
          >
            <AudioLines className="h-4 w-4" />
          </button>
        )}
        {/* Voice output toggle — only rendered when TTS is actually supported
            (Priya's voice handoff §2): an unsupported browser never sees a
            control that does nothing. */}
        {voiceOutputSupported && <VoiceToggle enabled={voiceEnabled} onToggle={setVoiceEnabled} />}
      </div>

      {/* Message list */}
      <div ref={scrollRef} className="flex-1 space-y-3 overflow-y-auto px-4 py-4 scrollbar-thin">
        {messages.map((message) =>
          live ? (
            <div key={message.id}>
              <MessageBubble role={message.role} text={message.text} />
              {message.toolResults?.map((tr) => (
                <ToolResultRenderer
                  key={tr.id}
                  toolName={tr.name}
                  status={tr.status}
                  data={tr.data}
                  errorMessage={tr.errorMessage}
                  onOptionPick={(opt) => {
                    // ME-1 (BrandF.md §115) — log the tap before sending the
                    // choice as the next turn; conversationId is guaranteed
                    // here since a turn is about to be sent against it.
                    if (conversationId) meeraApi.logOptionTapped(conversationId, tr.name, opt.recommended)
                    handleSend(opt.label)
                  }}
                  className="ml-9 mt-1.5"
                />
              ))}
            </div>
          ) : (
            <MessageBubble key={message.id} role={message.role} text={message.text} />
          ),
        )}
        {!live && thinkingKey && <ThinkingState steps={MEERA_THINKING_STEPS[thinkingKey]} />}
        {live && awaitingFirstToken && <ThinkingState steps={liveThinkingDisplaySteps} />}
      </div>

      {/* Composer / paywall / voice-mode bar. Voice mode replaces the text
          composer in-place (bottom of the LEFT chat column) instead of taking
          over the whole screen — the message list above stays visible, so the
          spoken conversation is recorded and read back as text bubbles in real
          time (the "like Claude" transcript). Only mounted while open so its
          mic loop + Web Audio graph don't run in the background. */}
      <div className="shrink-0 border-t border-meera-border bg-meera-surface p-4">
        {showPaywall ? (
          <CreditPaywall onFund={() => onFunctionCall('request_payment')} />
        ) : live && voiceModeOpen ? (
          <VoiceMode
            open={voiceModeOpen}
            onExit={() => setVoiceModeOpen(false)}
            onSend={handleSend}
            phase={phase}
            isSpeaking={isSpeaking}
            voiceOutputEnabled={voiceEnabled}
            setVoiceOutputEnabled={setVoiceEnabled}
            stopSpeaking={stopSpeaking}
          />
        ) : (
          <Composer
            onSend={handleSend}
            initialDraft={initialDraft}
            suggestedReplies={!live && !conversationDone && phase === 'awaiting-input' ? turn.suggestedReplies : []}
            templates={
              live && phase === 'awaiting-input' && !messages.some((m) => m.role === 'brand')
                ? MEERA_STARTER_TEMPLATES
                : []
            }
            sendLocked={live ? !conversationId || phase !== 'awaiting-input' : conversationDone || phase !== 'awaiting-input'}
          />
        )}
      </div>
    </div>
  )
}
