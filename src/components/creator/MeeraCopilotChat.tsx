import * as React from 'react';
import { useReducedMotion } from 'framer-motion';
import { Send, Mic, MicOff, Volume2, VolumeX, X, Loader2 } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { cn } from '@/lib/utils';
import { ApiError, isApiLive } from '@/lib/api';
import { meeraApi } from '@/lib/meera-api';
import { uniqueId } from '@/lib/unique-id';
import { useMeeraStream } from '@/hooks/useMeeraStream';
import { useVoiceOutput } from '@/hooks/useVoiceOutput';
import { useVoiceInput } from '@/hooks/useVoiceInput';

/**
 * T-MEERA-CREATOR-PHASE-A (A4/A5/A10, SPEC.md §4.7) — the CREATOR-side Meera chat.
 *
 * Deliberately NOT a reuse of `MeeraChatPanel.tsx`: that component is wired end-to-end to the
 * brand Living Canvas (stage advancement, tool_result cards, credits paywall, present_options)
 * per its own file-level docs, none of which exist for a creator turn in Phase A ("conversational
 * + profile/deals summary only" — SPEC.md §7 explicitly excludes creator tools). Forking a
 * matching-but-different panel avoids either (a) threading an `audience` prop through brand-only
 * logic that would need to no-op for every branch, or (b) risking a brand regression editing a
 * component with existing brand test coverage. Both panels talk to the SAME `meeraApi` — this one
 * just passes `role: 'creator'` everywhere (see meera-api.ts's MeeraRole threading).
 */

interface ChatMessage {
  id: string;
  role: 'meera' | 'creator';
  text: string;
}

const CONSENT_ERROR_CODE = 'CONSENT_REQUIRED';
/** influora-ai spend_tracker.py (A8, TASKS.md dev notes 2026-09-03) — 429 over the per-creator
 *  monthly cap, with a friendly message the caller should show verbatim rather than the generic
 *  "something went wrong" fallback. */
const CAP_REACHED_ERROR_CODE = 'CREATOR_MONTHLY_CAP_REACHED';

/**
 * Matched on `code` only (SPEC.md §3.4's `HTTPException(403, {"code": "CONSENT_REQUIRED", ...})`),
 * deliberately NOT on a bare 403 — a 403 can also mean "wrong role"/"session expired", and
 * mis-routing either of those into the consent screen would hide the real problem.
 */
export function isConsentRequiredError(err: unknown): boolean {
  return err instanceof ApiError && err.code === CONSENT_ERROR_CODE;
}

/**
 * Fix round 1, Q4 item (3): useMeeraStream's `fail()` sets `event.message` to transport-internal
 * strings like "Stream connection failed" / "Stream ended before completion" — accurate for a
 * console.error, not something to hand a creator verbatim. Map the known transport codes to an
 * honest, human sentence; anything else (including CREATOR_MONTHLY_CAP_REACHED, which already
 * carries its own friendly copy from spend_tracker.py) falls through to `event.message` as-is.
 */
function friendlyStreamErrorText(code: string, message: string | undefined): string {
  switch (code) {
    case 'CONNECTION_ERROR':
      return "Lost the connection to Meera — try again?";
    case 'STREAM_INCOMPLETE':
      return "Meera's reply got cut off — try again?";
    default:
      return message ?? "Didn't catch that — try again?";
  }
}

/** Client-side-only greeting shown while a brand-new conversation has no history yet. Never
 *  sent to the model as history — purely a "someone's home" placeholder while the backend's
 *  own day-one onboarding turn (SPEC.md §4.7) lands on the wire. */
function onboardingGreeting(firstName: string, language: string): string {
  return language.startsWith('hi')
    ? `नमस्ते ${firstName}! मैं Meera हूं, Influora पर आपकी मैनेजर। मैं आपकी डील्स, कमाई और मेट्रिक्स समझने में मदद कर सकती हूं। आप क्या जानना चाहेंगे?`
    : `Hi ${firstName}! I'm Meera, your manager here on Influora. I can help you track your deals, understand your earnings, and answer questions about the platform. What would you like to know?`;
}

export interface MeeraCopilotChatProps {
  firstName: string;
  /** BCP-47 — from CreatorAgentPreferences.creator_language (A5). */
  language: string;
  onClose: () => void;
  /** Bubbles a CONSENT_REQUIRED failure up so the caller can re-show the consent screen
   *  (e.g. consent was revoked/expired mid-session). */
  onConsentRequired: () => void;
}

export function MeeraCopilotChat({ firstName, language, onClose, onConsentRequired }: MeeraCopilotChatProps) {
  const [live] = React.useState(() => isApiLive());
  const [messages, setMessages] = React.useState<ChatMessage[]>([]);
  const [conversationId, setConversationId] = React.useState<string | null>(null);
  const [connecting, setConnecting] = React.useState(true);
  const [connectError, setConnectError] = React.useState<string | null>(null);
  const [sending, setSending] = React.useState(false);
  const [draft, setDraft] = React.useState('');
  const scrollRef = React.useRef<HTMLDivElement>(null);
  const reduceMotion = useReducedMotion();
  const stream = useMeeraStream();

  const {
    supported: voiceOutputSupported,
    enabled: voiceEnabled,
    setEnabled: setVoiceEnabled,
    speak,
    stop: stopSpeaking,
  } = useVoiceOutput('creator');

  const handleVoiceResult = React.useCallback((text: string) => {
    setDraft((prev) => (prev ? `${prev} ${text}` : text));
  }, []);

  const { supported: voiceInputSupported, isListening, start: startListening, stop: stopListening } =
    useVoiceInput({ onResult: handleVoiceResult, lang: language, role: 'creator' });

  /**
   * Fix round 1, Q4 item (2): pulled out of the mount effect so the `connectError` retry button
   * can re-run the exact same connect sequence instead of forcing a full page reload — a dead
   * Spring at panel-open time previously had no recovery path but a reload.
   */
  const connectToMeera = React.useCallback(
    (onCancelledRef: { current: boolean }) => {
      setConnecting(true);
      setConnectError(null);
      meeraApi
        .startSession('creator')
        .then((session) => {
          if (onCancelledRef.current) return;
          setConversationId(session.conversationId);
          return meeraApi
            .getHistory(session.conversationId, 'creator')
            .then((history) => {
              if (onCancelledRef.current) return;
              if (history.length > 0) {
                setMessages(
                  history.map((m) => ({ id: m.id, role: m.role === 'ASSISTANT' ? 'meera' : 'creator', text: m.content })),
                );
              } else {
                // Backend day-one onboarding hasn't sent a first turn yet (or this build predates
                // it) — show a local, non-persisted greeting so the panel never opens blank.
                setMessages([{ id: uniqueId('meera-greet'), role: 'meera', text: onboardingGreeting(firstName, language) }]);
              }
            })
            .catch(() => {
              setMessages([{ id: uniqueId('meera-greet'), role: 'meera', text: onboardingGreeting(firstName, language) }]);
            });
        })
        .catch((err: unknown) => {
          if (onCancelledRef.current) return;
          if (isConsentRequiredError(err)) {
            onConsentRequired();
            return;
          }
          setConnectError("Couldn't reach Meera.");
        })
        .finally(() => {
          if (!onCancelledRef.current) setConnecting(false);
        });
    },
    [firstName, language, onConsentRequired],
  );

  React.useEffect(() => {
    if (!live) {
      setConnecting(false);
      setMessages([{ id: uniqueId('meera-mock'), role: 'meera', text: onboardingGreeting(firstName, language) }]);
      return;
    }
    const cancelledRef = { current: false };
    connectToMeera(cancelledRef);
    return () => {
      cancelledRef.current = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [live]);

  const handleRetryConnect = React.useCallback(() => {
    connectToMeera({ current: false });
  }, [connectToMeera]);

  React.useEffect(() => {
    const el = scrollRef.current;
    // Feature-detect rather than assume `scrollTo` exists — jsdom (this repo's test DOM) has no
    // implementation, and an unguarded call throws inside the effect, uncaught, on every render
    // that touches this panel (this is how F-0324-shaped bugs get missed: it only ever throws in
    // an environment nobody currently asserts against).
    if (el && typeof el.scrollTo === 'function') {
      el.scrollTo({ top: el.scrollHeight, behavior: reduceMotion ? 'auto' : 'smooth' });
    }
  }, [messages, sending, reduceMotion]);

  React.useEffect(() => {
    return () => stopSpeaking();
  }, [stopSpeaking]);

  const handleSend = () => {
    const text = draft.trim();
    if (!text || sending) return;
    setDraft('');
    setMessages((prev) => [...prev, { id: uniqueId('creator'), role: 'creator', text }]);

    if (!live) {
      // Mock mode — no backend to talk to. Echo a short, honest placeholder rather than a
      // scripted brand-shaped conversation this panel has no script for.
      window.setTimeout(() => {
        setMessages((prev) => [
          ...prev,
          { id: uniqueId('meera-mock'), role: 'meera', text: 'This is mock mode — connect a live backend to chat with Meera.' },
        ]);
      }, 500);
      return;
    }

    if (!conversationId) return;
    setSending(true);
    const assistantId = uniqueId('meera');
    let assistantText = '';
    let bubbleAdded = false;

    meeraApi
      .sendTurn(conversationId, text, 'creator')
      .then((turnRes) => {
        if (turnRes.reply != null) {
          const replyText = turnRes.reply.trim() || "Sorry, I lost my train of thought there. Say that again?";
          setMessages((prev) => [...prev, { id: assistantId, role: 'meera', text: replyText }]);
          speak(replyText, language);
          setSending(false);
          return;
        }

        stream.open(
          turnRes.streamUrl,
          turnRes.streamToken,
          {
            onToken: (event) => {
              assistantText += event.text;
              const rendered = assistantText;
              setMessages((prev) => {
                if (!bubbleAdded) {
                  bubbleAdded = true;
                  return [...prev, { id: assistantId, role: 'meera', text: rendered }];
                }
                return prev.map((m) => (m.id === assistantId ? { ...m, text: rendered } : m));
              });
            },
            onDone: () => {
              setSending(false);
              if (assistantText.trim() === '') {
                assistantText = "Sorry, I lost my train of thought there. Say that again?";
                setMessages((prev) =>
                  bubbleAdded
                    ? prev.map((m) => (m.id === assistantId ? { ...m, text: assistantText } : m))
                    : [...prev, { id: assistantId, role: 'meera', text: assistantText }],
                );
              }
              speak(assistantText, language);
            },
            onError: (event) => {
              setSending(false);
              if (event.code === CONSENT_ERROR_CODE) {
                onConsentRequired();
                return;
              }
              // Cap-reached carries its own friendly copy from spend_tracker.py — show it
              // verbatim instead of a generic failure message. Everything else (including the
              // transport-level CONNECTION_ERROR / STREAM_INCOMPLETE codes from useMeeraStream's
              // `fail()`) goes through friendlyStreamErrorText rather than the raw event.message.
              const fallbackText =
                event.code === CAP_REACHED_ERROR_CODE
                  ? event.message ?? "You've reached your monthly Meera usage limit."
                  : friendlyStreamErrorText(event.code, event.message);
              setMessages((prev) =>
                prev.some((m) => m.id === assistantId)
                  ? prev.map((m) => (m.id === assistantId ? { ...m, text: fallbackText } : m))
                  : [...prev, { id: assistantId, role: 'meera', text: fallbackText }],
              );
            },
            onHeartbeatTimeout: () => {
              // Fix round 1, Q4 item (1): a hung (not dead) Python process — SIGSTOP, GC stall,
              // wedged provider socket — never fires onError/onDone, so without this the panel
              // sat in `sending=true` forever with no message and no way out but reload.
              stream.close();
              setSending(false);
              const timeoutText = 'Meera stopped responding — try again?';
              setMessages((prev) =>
                prev.some((m) => m.id === assistantId)
                  ? prev.map((m) => (m.id === assistantId ? { ...m, text: timeoutText } : m))
                  : [...prev, { id: assistantId, role: 'meera', text: timeoutText }],
              );
            },
          },
          {
            workspace_id: turnRes.workspaceId ?? '',
            conversation_id: conversationId,
            turn_id: turnRes.messageId,
            onbehalf_jwt: turnRes.onBehalfToken ?? '',
            conversation: [
              ...messages.map((m) => ({ role: m.role === 'creator' ? 'user' : 'assistant', content: m.text })),
              { role: 'user', content: text },
            ],
          },
        );
      })
      .catch((err: unknown) => {
        setSending(false);
        if (isConsentRequiredError(err)) {
          onConsentRequired();
          return;
        }
        // Cap-reached (spend_tracker.py) carries its own friendly copy — show it verbatim.
        const text =
          err instanceof ApiError && err.code === CAP_REACHED_ERROR_CODE
            ? err.message
            : 'Something went wrong sending that — try again?';
        setMessages((prev) => [...prev, { id: uniqueId('meera-error'), role: 'meera', text }]);
      });
  };

  return (
    <div className="flex h-[32rem] max-h-[75vh] flex-col rounded-xl border border-border bg-card shadow-sm">
      <div className="flex shrink-0 items-center justify-between border-b border-border px-4 py-3">
        <div>
          <p className="text-sm font-semibold">Meera</p>
          <p className="text-xs text-muted-foreground">Your AI manager</p>
        </div>
        <div className="flex items-center gap-1">
          {voiceOutputSupported && (
            <Button
              type="button"
              variant="ghost"
              size="icon"
              className="h-8 w-8"
              title={voiceEnabled ? 'Voice replies on' : 'Voice replies off'}
              onClick={() => setVoiceEnabled(!voiceEnabled)}
            >
              {voiceEnabled ? <Volume2 className="h-4 w-4" /> : <VolumeX className="h-4 w-4" />}
            </Button>
          )}
          <Button type="button" variant="ghost" size="icon" className="h-8 w-8" onClick={onClose} aria-label="Close">
            <X className="h-4 w-4" />
          </Button>
        </div>
      </div>

      <div ref={scrollRef} className="flex-1 space-y-3 overflow-y-auto px-4 py-4">
        {connecting && (
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <Loader2 className="h-4 w-4 animate-spin" />
            Connecting to Meera…
          </div>
        )}
        {connectError && (
          <div className="flex flex-col items-start gap-2">
            <p className="text-sm text-destructive-foreground">{connectError}</p>
            <Button type="button" variant="outline" size="sm" onClick={handleRetryConnect}>
              Try again
            </Button>
          </div>
        )}
        {messages.map((m) => (
          <div key={m.id} className={cn('flex', m.role === 'creator' ? 'justify-end' : 'justify-start')}>
            <div
              className={cn(
                'max-w-[85%] whitespace-pre-wrap rounded-2xl px-3 py-2 text-sm',
                m.role === 'creator' ? 'bg-primary text-primary-foreground' : 'bg-muted text-foreground',
              )}
            >
              {m.text}
            </div>
          </div>
        ))}
        {sending && (
          <div className="flex items-center gap-2 text-xs text-muted-foreground">
            <Loader2 className="h-3 w-3 animate-spin" />
            Thinking…
          </div>
        )}
      </div>

      <div className="shrink-0 border-t border-border p-3">
        <div className="flex items-end gap-2">
          <Textarea
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                handleSend();
              }
            }}
            placeholder="Ask Meera about your deals, earnings, or metrics…"
            rows={1}
            className="min-h-9 resize-none text-sm"
            disabled={connecting}
          />
          {voiceInputSupported && (
            <Button
              type="button"
              variant="outline"
              size="icon"
              className="h-9 w-9 shrink-0"
              title={isListening ? 'Stop recording' : 'Speak your question'}
              onClick={isListening ? stopListening : startListening}
              disabled={connecting}
            >
              {isListening ? <MicOff className="h-4 w-4" /> : <Mic className="h-4 w-4" />}
            </Button>
          )}
          <Button
            type="button"
            size="icon"
            className="h-9 w-9 shrink-0"
            onClick={handleSend}
            disabled={connecting || sending || !draft.trim()}
          >
            <Send className="h-4 w-4" />
          </Button>
        </div>
      </div>
    </div>
  );
}

export default MeeraCopilotChat;
