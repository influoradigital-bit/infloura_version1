import { Easing, Interactive, interpolate, useCurrentFrame } from 'remotion';

import { fontFamily } from '../../theme';

/** Opening card. One promise, one screen. */
export function HookScene() {
  const frame = useCurrentFrame();
  return (
    <Interactive.Div
      name="Hook background"
      style={{
        position: 'absolute',
        inset: 0,
        background: 'linear-gradient(160deg, #6d5ae6 0%, #4c3bc2 100%)',
        color: '#ffffff',
        fontFamily,
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        textAlign: 'center',
      }}
    >
      <Interactive.Div
        name="Hook eyebrow"
        style={{
          fontSize: 30,
          fontWeight: 800,
          letterSpacing: 3,
          textTransform: 'uppercase',
          backgroundColor: 'rgba(255,255,255,0.18)',
          padding: '14px 34px',
          borderRadius: 999,
          opacity: interpolate(frame, [4, 22], [0, 1], {
            extrapolateLeft: 'clamp',
            extrapolateRight: 'clamp',
            easing: Easing.bezier(0.16, 1, 0.3, 1),
          }),
          translate: interpolate(frame, [4, 26], ['0px 24px', '0px 0px'], {
            extrapolateLeft: 'clamp',
            extrapolateRight: 'clamp',
            easing: Easing.bezier(0.16, 1, 0.3, 1),
          }),
        }}
      >
        Influora for brands
      </Interactive.Div>
      <Interactive.Div
        name="Hook title"
        style={{
          marginTop: 46,
          fontSize: 132,
          fontWeight: 900,
          letterSpacing: -4,
          lineHeight: 1.05,
          opacity: interpolate(frame, [14, 34], [0, 1], {
            extrapolateLeft: 'clamp',
            extrapolateRight: 'clamp',
            easing: Easing.bezier(0.16, 1, 0.3, 1),
          }),
          translate: interpolate(frame, [14, 38], ['0px 30px', '0px 0px'], {
            extrapolateLeft: 'clamp',
            extrapolateRight: 'clamp',
            easing: Easing.bezier(0.16, 1, 0.3, 1),
          }),
        }}
      >
        How to create a campaign
      </Interactive.Div>
      <Interactive.Div
        name="Hook subtitle"
        style={{
          marginTop: 28,
          fontSize: 56,
          fontWeight: 600,
          opacity: interpolate(frame, [30, 50], [0, 0.92], {
            extrapolateLeft: 'clamp',
            extrapolateRight: 'clamp',
            easing: Easing.bezier(0.16, 1, 0.3, 1),
          }),
        }}
      >
        Five steps, start to finish
      </Interactive.Div>
    </Interactive.Div>
  );
}

/** Closing card: the Secure Payments promise, then the call to action. */
export function OutroScene() {
  const frame = useCurrentFrame();
  return (
    <Interactive.Div
      name="Outro background"
      style={{
        position: 'absolute',
        inset: 0,
        background: 'linear-gradient(160deg, #6d5ae6 0%, #4c3bc2 100%)',
        color: '#ffffff',
        fontFamily,
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        textAlign: 'center',
      }}
    >
      <Interactive.Div
        name="Outro promise"
        style={{
          fontSize: 66,
          fontWeight: 800,
          letterSpacing: -1.5,
          maxWidth: 1400,
          lineHeight: 1.25,
          opacity: interpolate(frame, [4, 24], [0, 1], {
            extrapolateLeft: 'clamp',
            extrapolateRight: 'clamp',
            easing: Easing.bezier(0.16, 1, 0.3, 1),
          }),
          translate: interpolate(frame, [4, 28], ['0px 26px', '0px 0px'], {
            extrapolateLeft: 'clamp',
            extrapolateRight: 'clamp',
            easing: Easing.bezier(0.16, 1, 0.3, 1),
          }),
        }}
      >
        Secure Payments holds the funds until the work is delivered
      </Interactive.Div>
      <Interactive.Div
        name="Outro CTA"
        style={{
          marginTop: 56,
          backgroundColor: '#ffffff',
          color: '#4c3bc2',
          fontSize: 44,
          fontWeight: 800,
          padding: '26px 60px',
          borderRadius: 999,
          boxShadow: '0 24px 60px rgba(0,0,0,0.28)',
          opacity: interpolate(frame, [34, 52], [0, 1], {
            extrapolateLeft: 'clamp',
            extrapolateRight: 'clamp',
            easing: Easing.bezier(0.16, 1, 0.3, 1),
          }),
          scale: interpolate(frame, [34, 56], [0.88, 1], {
            extrapolateLeft: 'clamp',
            extrapolateRight: 'clamp',
            easing: Easing.bezier(0.16, 1, 0.3, 1),
            output: 'perceptual-scale',
          }),
        }}
      >
        Create your campaign on Influora
      </Interactive.Div>
    </Interactive.Div>
  );
}
