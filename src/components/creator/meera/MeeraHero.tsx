import { Loader2 } from 'lucide-react';
import { GlowingInput } from '@/components/ui/glowing-input';
import { VoicePoweredOrb } from '@/components/ui/voice-powered-orb';

/**
 * The creator's first view of Meera on the Co-pilot page (closed state of "Talk to Meera").
 *
 * Colour, 60-30-10: this band is the 30% (--meera-stage, the brand's deep ink) inside the page's
 * 60% light base; the orb, the send button and focus rings are the 10% (--primary). Text is white.
 *
 * Asking here never sends: `onAsk` goes through the page's consent check and then PREFILLS the
 * chat composer (ruling R-U1, prefill-only). "Open Meera" keeps the plain entry point.
 */
export interface MeeraHeroProps {
  firstName?: string;
  onAsk: (question: string) => void;
  onOpen: () => void;
  busy?: boolean;
  error?: string | null;
}

export function MeeraHero({ firstName, onAsk, onOpen, busy = false, error }: MeeraHeroProps) {
  return (
    <div className="relative overflow-hidden rounded-2xl bg-[radial-gradient(120%_90%_at_50%_0%,var(--meera-stage-2)_0%,var(--meera-stage)_65%)] px-4 py-8 text-center text-white sm:px-8 sm:py-10">
      <div className="mx-auto h-32 w-32 sm:h-40 sm:w-40" aria-hidden>
        <VoicePoweredOrb activity={busy ? 0.4 : 0.12} />
      </div>

      <h2 className="mt-4 text-xl font-semibold sm:text-2xl">
        {firstName ? `Hi ${firstName}, I'm Meera` : "Hi, I'm Meera"}
      </h2>
      <p className="mx-auto mt-1 max-w-md text-sm text-white/75 sm:text-base">
        Ask about your deals, your earnings, or your next reel.
      </p>

      <div className="mx-auto mt-2 flex justify-center">
        <GlowingInput
          placeholder="Type a question for Meera…"
          onSubmit={onAsk}
          disabled={busy}
          className="max-w-xl"
        />
      </div>

      <button
        type="button"
        onClick={onOpen}
        disabled={busy}
        className="inline-flex h-11 items-center gap-2 rounded-full px-5 text-sm font-medium text-white/90 ring-1 ring-white/25 transition-colors hover:bg-white/10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-violet-300 disabled:opacity-60"
      >
        {busy ? (
          <>
            <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
            Connecting…
          </>
        ) : (
          'Open Meera'
        )}
      </button>

      {error && (
        <p className="mx-auto mt-3 max-w-md text-sm text-white" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}
