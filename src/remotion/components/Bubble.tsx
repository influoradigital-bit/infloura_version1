import type { CSSProperties } from 'react';
import { Easing, interpolate, useCurrentFrame } from 'remotion';

import { theme } from '../theme';

const BUBBLE_FONT = 34;
const MAX_WIDTH = 580;

function enterStyle(frame: number, start: number, fromX: number): CSSProperties {
  return {
    opacity: interpolate(frame, [start, start + 12], [0, 1], {
      extrapolateLeft: 'clamp',
      extrapolateRight: 'clamp',
      easing: Easing.bezier(0.16, 1, 0.3, 1),
    }),
    translate: interpolate(frame, [start, start + 16], [`${fromX}px 18px`, '0px 0px'], {
      extrapolateLeft: 'clamp',
      extrapolateRight: 'clamp',
      easing: Easing.bezier(0.16, 1, 0.3, 1),
    }),
  };
}

interface BubbleProps {
  start: number;
  text: string;
  side: 'left' | 'right';
  /** Reveal the text character by character (Meera speaks). */
  typewriter?: boolean;
  /** Frames over which the typewriter reveals the whole text. */
  revealFrames?: number;
  /** WhatsApp-style bubble on the WhatsApp surface. */
  wa?: boolean;
}

export function Bubble({ start, text, side, typewriter = false, revealFrames = 60, wa = false }: BubbleProps) {
  const frame = useCurrentFrame();
  const rate = text.length / Math.max(1, revealFrames);
  const shown = typewriter
    ? Math.min(text.length, Math.max(0, Math.floor((frame - start - 6) * rate)))
    : text.length;
  const isRight = side === 'right';

  const bg = isRight ? theme.primary : wa ? '#ffffff' : theme.secondary;
  const fg = isRight ? theme.primaryForeground : theme.foreground;

  return (
    <div
      style={{
        display: 'flex',
        justifyContent: isRight ? 'flex-end' : 'flex-start',
        marginBottom: 22,
        ...enterStyle(frame, start, isRight ? 24 : -24),
      }}
    >
      <div
        style={{
          maxWidth: MAX_WIDTH,
          backgroundColor: bg,
          color: fg,
          fontSize: BUBBLE_FONT,
          lineHeight: 1.35,
          padding: '20px 26px',
          borderRadius: 30,
          borderBottomRightRadius: isRight ? 8 : 30,
          borderBottomLeftRadius: isRight ? 30 : 8,
          boxShadow: wa ? '0 1px 1px rgba(0,0,0,0.08)' : 'none',
          whiteSpace: 'pre-wrap',
          wordBreak: 'break-word',
        }}
      >
        {wa ? (
          <div style={{ fontSize: 22, color: '#075e54', fontWeight: 700, marginBottom: 6 }}>
            Meera · Influora
          </div>
        ) : null}
        <span>{text.slice(0, shown)}</span>
        <span style={{ visibility: 'hidden' }}>{text.slice(shown)}</span>
      </div>
    </div>
  );
}

export function SystemLine({ start, text }: { start: number; text: string }) {
  const frame = useCurrentFrame();
  return (
    <div
      style={{
        display: 'flex',
        justifyContent: 'center',
        marginBottom: 22,
        opacity: interpolate(frame, [start, start + 12], [0, 1], {
          extrapolateLeft: 'clamp',
          extrapolateRight: 'clamp',
        }),
      }}
    >
      <div
        style={{
          fontSize: 24,
          color: theme.mutedForeground,
          backgroundColor: theme.muted,
          padding: '8px 22px',
          borderRadius: 999,
        }}
      >
        {text}
      </div>
    </div>
  );
}

export function TypingDots({ start, hold }: { start: number; hold: number }) {
  const frame = useCurrentFrame();
  if (frame < start || frame >= start + hold) return null;
  const t = frame - start;
  return (
    <div style={{ display: 'flex', justifyContent: 'flex-start', marginBottom: 22, ...enterStyle(frame, start, -24) }}>
      <div
        style={{
          backgroundColor: theme.secondary,
          padding: '22px 28px',
          borderRadius: 30,
          borderBottomLeftRadius: 8,
          display: 'flex',
          gap: 12,
        }}
      >
        {[0, 1, 2].map((i) => (
          <div
            key={i}
            style={{
              width: 16,
              height: 16,
              borderRadius: 999,
              backgroundColor: theme.mutedForeground,
              opacity: interpolate(((t + i * 6) % 18) / 18, [0, 0.5, 1], [0.3, 1, 0.3]),
            }}
          />
        ))}
      </div>
    </div>
  );
}

export function TapButton({ start, label }: { start: number; label: string }) {
  const frame = useCurrentFrame();
  const pressed = frame >= start + 22;
  const done = frame >= start + 32;
  return (
    <div
      style={{
        display: 'flex',
        justifyContent: 'flex-end',
        marginBottom: 22,
        opacity: interpolate(frame, [start, start + 10], [0, 1], {
          extrapolateLeft: 'clamp',
          extrapolateRight: 'clamp',
        }),
      }}
    >
      <div
        style={{
          backgroundColor: done ? theme.success : theme.accentForeground,
          color: done ? theme.successForeground : '#ffffff',
          fontSize: 32,
          fontWeight: 700,
          padding: '22px 44px',
          borderRadius: 999,
          scale: pressed && !done ? '0.94' : '1',
          boxShadow: done ? 'none' : '0 12px 30px rgba(76, 59, 194, 0.35)',
        }}
      >
        {done ? `${label} ✓` : label}
      </div>
    </div>
  );
}
