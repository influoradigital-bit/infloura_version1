import { useEffect, useMemo, useRef, useState } from 'react';
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion';
import { Loader2, Mic, Sparkles, Volume2 } from 'lucide-react';
import { cn } from '@/lib/utils';

/**
 * Full-screen voice view (listening / thinking / speaking). Adapted from a community "Siri-style"
 * voice chat component.
 *
 * Changes from the original, all deliberate:
 * - CONTROLLED: `status` comes from the app (real mic / request / TTS state). The original faked a
 *   waveform, a volume meter and a looping demo from Math.random(); here the bars are a decorative
 *   animation only, no number or meter claims to measure anything, and the looping demo exists only
 *   when `demoMode` is set (the dev preview page).
 * - The timer counts real seconds while `status === 'listening'`.
 * - Ambient particles are placed once and animated by framer-motion, not re-rendered 60x a second.
 * - Text colours meet contrast on the light theme; respects prefers-reduced-motion.
 */
export type VoiceChatStatus = 'idle' | 'listening' | 'thinking' | 'speaking';

export interface VoiceChatProps {
  status?: VoiceChatStatus;
  /** Called when the big button is pressed; the parent starts or stops listening. */
  onToggle?: () => void;
  /** Label under the button, e.g. the assistant's name. */
  label?: string;
  className?: string;
  /** Cycle idle -> listening -> thinking -> speaking on its own. Preview pages only. */
  demoMode?: boolean;
}

const STATUS_TEXT: Record<VoiceChatStatus, string> = {
  idle: 'Tap to speak',
  listening: 'Listening…',
  thinking: 'Thinking…',
  speaking: 'Speaking…',
};

// 60-30-10: one accent (--primary) for every active state; states differ by intensity, not hue.
const STATUS_COLOR: Record<VoiceChatStatus, string> = {
  idle: 'text-muted-foreground',
  listening: 'text-primary',
  thinking: 'text-foreground',
  speaking: 'text-primary',
};

const RING: Record<VoiceChatStatus, string> = {
  idle: 'border-border hover:border-primary/50',
  listening: 'border-primary shadow-lg shadow-primary/30',
  thinking: 'border-primary/40 shadow-lg shadow-primary/10',
  speaking: 'border-primary shadow-lg shadow-primary/30',
};

const BAR: Record<VoiceChatStatus, string> = {
  idle: 'bg-muted',
  listening: 'bg-primary',
  thinking: 'bg-primary/40',
  speaking: 'bg-primary',
};

const DEMO_SEQUENCE: Array<[VoiceChatStatus, number]> = [
  ['listening', 3000],
  ['thinking', 2000],
  ['speaking', 4000],
  ['idle', 2000],
];

function formatTime(seconds: number) {
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
}

export function VoiceChat({ status: statusProp = 'idle', onToggle, label = 'Meera', className, demoMode = false }: VoiceChatProps) {
  const reduced = useReducedMotion();
  const [demoStatus, setDemoStatus] = useState<VoiceChatStatus>('idle');
  const status = demoMode ? demoStatus : statusProp;
  const [seconds, setSeconds] = useState(0);
  const timerRef = useRef<ReturnType<typeof setInterval> | undefined>(undefined);

  // Preview-only cycle.
  useEffect(() => {
    if (!demoMode) return;
    let i = 0;
    let t: ReturnType<typeof setTimeout>;
    const step = () => {
      const [next, ms] = DEMO_SEQUENCE[i % DEMO_SEQUENCE.length];
      setDemoStatus(next);
      i += 1;
      t = setTimeout(step, ms);
    };
    t = setTimeout(step, 800);
    return () => clearTimeout(t);
  }, [demoMode]);

  // Real listening time.
  useEffect(() => {
    if (status === 'listening') {
      setSeconds(0);
      timerRef.current = setInterval(() => setSeconds((s) => s + 1), 1000);
    }
    return () => {
      if (timerRef.current) clearInterval(timerRef.current);
    };
  }, [status]);

  const particles = useMemo(
    () =>
      Array.from({ length: 20 }, (_, id) => ({
        id,
        left: `${(id * 37) % 100}%`,
        top: `${(id * 53) % 100}%`,
        delay: (id % 7) * 0.4,
      })),
    [],
  );

  const active = status !== 'idle';
  const animated = active && !reduced;

  return (
    <div className={cn('relative flex flex-col items-center justify-center overflow-hidden bg-background', className)}>
      {/* Ambient particles */}
      <div className="pointer-events-none absolute inset-0" aria-hidden="true">
        {particles.map((p) => (
          <motion.span
            key={p.id}
            className="absolute h-1 w-1 rounded-full bg-primary/25"
            style={{ left: p.left, top: p.top }}
            animate={reduced ? undefined : { y: [0, -12, 0], opacity: [0.15, 0.45, 0.15] }}
            transition={{ duration: 4, repeat: Infinity, ease: 'easeInOut', delay: p.delay }}
          />
        ))}
      </div>

      {/* Glow */}
      <div className="pointer-events-none absolute inset-0 flex items-center justify-center" aria-hidden="true">
        <motion.div
          className="h-80 w-80 rounded-full bg-gradient-to-r from-primary/15 via-violet-300/15 to-primary/15 blur-3xl"
          animate={reduced ? undefined : { scale: active ? [1, 1.2, 1] : [1, 1.08, 1], opacity: active ? [0.35, 0.65, 0.35] : [0.15, 0.25, 0.15] }}
          transition={{ duration: 2, repeat: Infinity, ease: 'easeInOut' }}
        />
      </div>

      <div className="relative z-10 flex flex-col items-center gap-8">
        <div className="relative">
          <motion.button
            type="button"
            onClick={onToggle}
            disabled={!onToggle || status === 'thinking'}
            aria-label={status === 'listening' ? 'Stop listening' : 'Start speaking'}
            whileHover={onToggle ? { scale: 1.05 } : undefined}
            whileTap={onToggle ? { scale: 0.95 } : undefined}
            className={cn(
              'relative flex h-32 w-32 items-center justify-center rounded-full border-2 bg-gradient-to-br from-primary/20 to-primary/10 transition-colors duration-300',
              'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-default',
              RING[status],
            )}
          >
            <AnimatePresence mode="wait">
              <motion.span
                key={status}
                initial={{ opacity: 0, scale: 0.8 }}
                animate={{ opacity: 1, scale: 1 }}
                exit={{ opacity: 0, scale: 0.8 }}
              >
                {status === 'thinking' ? (
                  <Loader2 className="h-12 w-12 animate-spin text-primary/70" />
                ) : status === 'speaking' ? (
                  <Volume2 className="h-12 w-12 text-primary" />
                ) : (
                  <Mic className={cn('h-12 w-12', status === 'listening' ? 'text-primary' : 'text-muted-foreground')} />
                )}
              </motion.span>
            </AnimatePresence>
          </motion.button>

          <AnimatePresence>
            {status === 'listening' && !reduced && (
              <>
                <motion.span
                  aria-hidden="true"
                  className="absolute inset-0 rounded-full border-2 border-primary/30"
                  initial={{ scale: 1, opacity: 0.6 }}
                  animate={{ scale: 1.5, opacity: 0 }}
                  exit={{ opacity: 0 }}
                  transition={{ duration: 1.5, repeat: Infinity, ease: 'easeOut' }}
                />
                <motion.span
                  aria-hidden="true"
                  className="absolute inset-0 rounded-full border-2 border-primary/20"
                  initial={{ scale: 1, opacity: 0.4 }}
                  animate={{ scale: 2, opacity: 0 }}
                  exit={{ opacity: 0 }}
                  transition={{ duration: 1.5, repeat: Infinity, ease: 'easeOut', delay: 0.5 }}
                />
              </>
            )}
          </AnimatePresence>
        </div>

        {/* Decorative bars: an animation, not a measurement. */}
        <div className="flex h-16 items-center justify-center gap-1" aria-hidden="true">
          {Array.from({ length: 32 }, (_, i) => (
            <motion.span
              key={i}
              className={cn('w-1 rounded-full transition-colors duration-300', BAR[status])}
              animate={
                animated && status !== 'thinking'
                  ? { height: [6, 14 + ((i * 7) % 34), 6], opacity: 1 }
                  : { height: 4, opacity: 0.35 }
              }
              transition={animated ? { duration: 0.9 + (i % 5) * 0.12, repeat: Infinity, ease: 'easeInOut' } : { duration: 0.2 }}
            />
          ))}
        </div>

        <div className="space-y-1 text-center">
          <p className={cn('text-lg font-medium', STATUS_COLOR[status])} role="status" aria-live="polite">
            {STATUS_TEXT[status]}
          </p>
          {status === 'listening' && <p className="font-mono text-sm text-muted-foreground">{formatTime(seconds)}</p>}
        </div>

        <div className="flex items-center gap-2 text-sm text-muted-foreground">
          <Sparkles className="h-4 w-4" aria-hidden="true" />
          <span>{label}</span>
        </div>
      </div>
    </div>
  );
}

export default VoiceChat;
