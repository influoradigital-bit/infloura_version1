import { Easing, interpolate, useCurrentFrame } from 'remotion';

import { fontFamily, theme } from '../../theme';
import { TYPE } from '../theme';

/**
 * The step kicker + caption that sits above the browser window. Kept to one
 * short line so the viewer reads it in a glance and returns to the form.
 */
export function Caption({ kicker, text }: { kicker: string; text: string }) {
  const frame = useCurrentFrame();
  return (
    <div
      style={{
        position: 'absolute',
        left: 180,
        top: 46,
        width: 1560,
        fontFamily,
        opacity: interpolate(frame, [2, 18], [0, 1], {
          extrapolateLeft: 'clamp',
          extrapolateRight: 'clamp',
          easing: Easing.bezier(0.16, 1, 0.3, 1),
        }),
        translate: interpolate(frame, [2, 22], ['0px 18px', '0px 0px'], {
          extrapolateLeft: 'clamp',
          extrapolateRight: 'clamp',
          easing: Easing.bezier(0.16, 1, 0.3, 1),
        }),
      }}
    >
      <div
        style={{
          display: 'inline-block',
          fontSize: TYPE.captionKicker,
          fontWeight: 800,
          letterSpacing: 2.4,
          textTransform: 'uppercase',
          color: theme.accentForeground,
          backgroundColor: theme.accent,
          padding: '8px 20px',
          borderRadius: 999,
        }}
      >
        {kicker}
      </div>
      <div
        style={{
          marginTop: 14,
          fontSize: TYPE.caption,
          fontWeight: 800,
          letterSpacing: -1,
          color: theme.foreground,
        }}
      >
        {text}
      </div>
    </div>
  );
}
