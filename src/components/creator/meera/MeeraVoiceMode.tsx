import { Loader2, Mic, MicOff, RotateCcw, Send, X } from 'lucide-react';
import { Dialog, DialogContent, DialogDescription, DialogTitle, DialogClose } from '@/components/ui/dialog';
import { VoicePoweredOrb } from '@/components/ui/voice-powered-orb';
import { cn } from '@/lib/utils';

/**
 * Meera voice mode (creator). A full-screen stage on phones, a centred panel from `sm` up.
 *
 * Colour, 60-30-10: the stage is the 30% (--meera-stage, the brand's deep ink), the orb, the mic
 * and Send are the 10% (--primary), and text is white at two strengths. The 60% light base stays
 * on the page behind it.
 *
 * Everything shown is REAL state passed in by MeeraCopilotChat: `status` from useVoiceInput /
 * the pending turn / useVoiceOutput, `transcript` is exactly what the mic wrote into the composer,
 * and `lastReply` is Meera's last message. Nothing is sent without the creator pressing Send, the
 * same rule as the typed composer. Built on the Radix dialog: focus trap, Esc closes, aria-modal.
 */
export type MeeraVoiceStatus = 'idle' | 'listening' | 'thinking' | 'speaking';

export interface MeeraVoiceModeProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  status: MeeraVoiceStatus;
  /** What the creator said (the composer text the mic filled). */
  transcript: string;
  /** Meera's most recent reply, shown while she speaks or after. */
  lastReply?: string;
  micSupported: boolean;
  onMicToggle: () => void;
  onSend: () => void;
  onSpeakAgain: () => void;
  sendDisabled?: boolean;
  language?: string;
}

const COPY = {
  en: {
    title: 'Talk to Meera',
    description: 'Voice mode. Tap the microphone, speak, then send.',
    idle: 'Tap the mic and speak',
    listening: 'Listening…',
    thinking: 'Thinking…',
    speaking: 'Meera is speaking…',
    youSaid: 'You said',
    meera: 'Meera',
    send: 'Send',
    again: 'Speak again',
    start: 'Start speaking',
    stop: 'Stop listening',
    close: 'Close voice mode',
    noMic: 'Voice input is not available in this browser. You can still type in the chat.',
  },
  hi: {
    title: 'Meera से बात करें',
    description: 'वॉइस मोड। माइक दबाएँ, बोलें, फिर भेजें।',
    idle: 'माइक दबाएँ और बोलें',
    listening: 'सुन रही हूँ…',
    thinking: 'सोच रही हूँ…',
    speaking: 'Meera बोल रही है…',
    youSaid: 'आपने कहा',
    meera: 'Meera',
    send: 'भेजें',
    again: 'फिर से बोलें',
    start: 'बोलना शुरू करें',
    stop: 'सुनना बंद करें',
    close: 'वॉइस मोड बंद करें',
    noMic: 'इस ब्राउज़र में वॉइस इनपुट उपलब्ध नहीं है। आप चैट में टाइप कर सकते हैं।',
  },
} as const;

const ACTIVITY: Record<MeeraVoiceStatus, number> = { idle: 0.1, listening: 0.5, thinking: 0.35, speaking: 0.75 };

export function MeeraVoiceMode({
  open,
  onOpenChange,
  status,
  transcript,
  lastReply,
  micSupported,
  onMicToggle,
  onSend,
  onSpeakAgain,
  sendDisabled = false,
  language = 'en-IN',
}: MeeraVoiceModeProps) {
  const t = language.toLowerCase().startsWith('hi') ? COPY.hi : COPY.en;
  const listening = status === 'listening';
  const thinking = status === 'thinking';
  const hasTranscript = transcript.trim().length > 0 && !listening;
  const showReply = !hasTranscript && !listening && !!lastReply && (status === 'speaking' || status === 'idle');

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        showCloseButton={false}
        className={cn(
          // Phone: full screen. sm+: centred panel.
          // The app reserves a scrollbar gutter (html { scrollbar-gutter: stable }), which a fixed inset-0
          // panel cannot cover; right: 100% - 100vw extends into it (0 on phones, where scrollbars overlay).
          'inset-0 right-[calc(100%-100vw)] flex h-[100dvh] w-auto max-w-none translate-x-0 translate-y-0 flex-col gap-0 rounded-none border-0 p-0 text-white',
          'sm:top-1/2 sm:right-auto sm:bottom-auto sm:left-1/2 sm:h-[min(42rem,90dvh)] sm:w-full sm:max-w-md sm:-translate-x-1/2 sm:-translate-y-1/2 sm:rounded-2xl',
          'bg-[radial-gradient(120%_80%_at_50%_0%,var(--meera-stage-2)_0%,var(--meera-stage)_60%)]',
        )}
      >
        <DialogTitle className="sr-only">{t.title}</DialogTitle>
        <DialogDescription className="sr-only">{t.description}</DialogDescription>

        {/* Top bar */}
        <div className="flex items-center justify-between px-4 pt-[max(1rem,env(safe-area-inset-top))]">
          <p className="text-sm font-semibold text-white/90">{t.meera}</p>
          <DialogClose
            aria-label={t.close}
            className="grid h-11 w-11 place-items-center rounded-full text-white/80 transition-colors hover:bg-white/10 hover:text-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-violet-300"
          >
            <X className="h-5 w-5" aria-hidden />
          </DialogClose>
        </div>

        {/* Stage */}
        <div className="flex min-h-0 flex-1 flex-col items-center justify-center gap-5 px-6">
          <div className="aspect-square w-[min(68vw,17rem)] shrink-0" aria-hidden>
            <VoicePoweredOrb enableVoiceControl={listening} activity={ACTIVITY[status]} />
          </div>

          <p className="text-center text-lg font-medium text-white" role="status" aria-live="polite">
            {thinking ? (
              <span className="inline-flex items-center gap-2">
                <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
                {t.thinking}
              </span>
            ) : (
              t[status]
            )}
          </p>

          {hasTranscript && (
            <div className="w-full max-w-sm rounded-2xl bg-white/[0.07] p-4 ring-1 ring-white/10">
              <p className="text-xs font-medium uppercase tracking-wide text-violet-200">{t.youSaid}</p>
              <p className="mt-1 line-clamp-4 text-base text-white">{transcript}</p>
            </div>
          )}

          {showReply && (
            <div className="w-full max-w-sm rounded-2xl bg-white/[0.07] p-4 ring-1 ring-white/10">
              <p className="text-xs font-medium uppercase tracking-wide text-violet-200">{t.meera}</p>
              <p className="mt-1 line-clamp-5 text-base text-white/90">{lastReply}</p>
            </div>
          )}

          {!micSupported && <p className="max-w-sm text-center text-sm text-white/75">{t.noMic}</p>}
        </div>

        {/* Controls */}
        <div className="flex items-center justify-center gap-4 px-6 pt-4 pb-[max(1.5rem,env(safe-area-inset-bottom))]">
          {hasTranscript ? (
            <>
              <button
                type="button"
                onClick={onSpeakAgain}
                className="inline-flex h-12 items-center gap-2 rounded-full px-5 text-sm font-medium text-white ring-1 ring-white/25 transition-colors hover:bg-white/10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-violet-300"
              >
                <RotateCcw className="h-4 w-4" aria-hidden />
                {t.again}
              </button>
              <button
                type="button"
                onClick={onSend}
                disabled={sendDisabled}
                className="inline-flex h-12 items-center gap-2 rounded-full bg-primary px-6 text-sm font-semibold text-primary-foreground shadow-lg shadow-primary/30 transition-colors hover:bg-primary/90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-violet-200 disabled:opacity-60"
              >
                <Send className="h-4 w-4" aria-hidden />
                {t.send}
              </button>
            </>
          ) : (
            micSupported && (
              <button
                type="button"
                onClick={onMicToggle}
                disabled={thinking}
                aria-label={listening ? t.stop : t.start}
                aria-pressed={listening}
                className={cn(
                  'grid h-[4.5rem] w-[4.5rem] place-items-center rounded-full transition-all focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-violet-200 disabled:opacity-50',
                  listening
                    ? 'bg-white text-primary shadow-[0_0_0_10px_rgba(124,106,240,0.35)]'
                    : 'bg-primary text-primary-foreground shadow-lg shadow-primary/40 hover:bg-primary/90',
                )}
              >
                {listening ? <MicOff className="h-7 w-7" aria-hidden /> : <Mic className="h-7 w-7" aria-hidden />}
              </button>
            )
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
}
