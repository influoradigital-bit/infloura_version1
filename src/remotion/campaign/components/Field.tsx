import { Easing, interpolate, useCurrentFrame } from 'remotion';

import { theme } from '../../theme';
import { TYPE } from '../theme';

/** Characters revealed per frame when a field "types" itself. */
const TYPE_RATE = 1.9;

function typedText(text: string, frame: number, from: number): string {
  if (frame < from) return '';
  return text.slice(0, Math.floor((frame - from) * TYPE_RATE));
}

/** Frames a string of this length needs to finish typing. */
function typingFrames(text: string): number {
  return Math.ceil(text.length / TYPE_RATE);
}

/**
 * One labelled input. `from` is the frame the value starts typing on; before
 * that the field shows its real placeholder, exactly as an empty form would.
 */
export function Field({
  label,
  value,
  placeholder,
  from,
  multiline,
  height,
}: {
  label: string;
  value: string;
  placeholder: string;
  from: number;
  multiline?: boolean;
  height?: number;
}) {
  const frame = useCurrentFrame();
  const shown = typedText(value, frame, from);
  const focused = frame >= from && frame < from + typingFrames(value) + 20;
  return (
    <div style={{ marginBottom: 26 }}>
      <div style={{ fontSize: TYPE.label, fontWeight: 600, color: theme.foreground, marginBottom: 10 }}>
        {label}
      </div>
      <div
        style={{
          minHeight: height ?? 64,
          borderRadius: 12,
          border: `2px solid ${focused ? theme.ring : theme.border}`,
          backgroundColor: theme.card,
          padding: multiline ? '16px 20px' : '0 20px',
          display: 'flex',
          alignItems: multiline ? 'flex-start' : 'center',
          fontSize: TYPE.input,
          lineHeight: 1.45,
          color: shown ? theme.foreground : theme.mutedForeground,
          boxShadow: focused ? `0 0 0 6px rgba(109,90,230,0.14)` : 'none',
        }}
      >
        <span>
          {shown || placeholder}
          {focused && shown.length < value.length ? (
            <span style={{ opacity: interpolate(frame % 16, [0, 8, 15], [1, 0, 1]) }}>|</span>
          ) : null}
        </span>
      </div>
    </div>
  );
}

/** A row of selectable chips that pop in one after another. */
export function ChipRow({
  label,
  options,
  selected,
  from,
  stagger,
}: {
  label: string;
  options: string[];
  selected: string[];
  from: number;
  stagger: number;
}) {
  const frame = useCurrentFrame();
  return (
    <div style={{ marginBottom: 26 }}>
      <div style={{ fontSize: TYPE.label, fontWeight: 600, color: theme.foreground, marginBottom: 12 }}>
        {label}
      </div>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 12 }}>
        {options.map((option) => {
          const order = selected.indexOf(option);
          const at = order === -1 ? Number.MAX_SAFE_INTEGER : from + order * stagger;
          const on = frame >= at;
          return (
            <div
              key={option}
              style={{
                padding: '13px 22px',
                borderRadius: 999,
                fontSize: TYPE.chip,
                fontWeight: 600,
                border: `2px solid ${on ? theme.primary : theme.border}`,
                backgroundColor: on ? theme.primary : theme.card,
                color: on ? theme.primaryForeground : theme.mutedForeground,
                scale: interpolate(frame, [at, at + 6, at + 16], [1, 1.09, 1], {
                  extrapolateLeft: 'clamp',
                  extrapolateRight: 'clamp',
                  easing: Easing.bezier(0.16, 1, 0.3, 1),
                  output: 'perceptual-scale',
                }),
              }}
            >
              {option}
            </div>
          );
        })}
      </div>
    </div>
  );
}
