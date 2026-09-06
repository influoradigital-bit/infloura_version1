import type { CSSProperties, ReactNode } from 'react';
import { Easing, interpolate, useCurrentFrame } from 'remotion';

import { fontFamily, PHONE, SCREEN, theme } from '../theme';

interface PhoneFrameProps {
  chapter: string;
  header: string;
  headerSub: string;
  surface: 'chat' | 'wa';
  children: ReactNode;
}

/**
 * The phone mockup that hosts every scene: chapter chip above, bezel, status
 * bar, chat header, then the children as the screen body.
 */
export function PhoneFrame({ chapter, header, headerSub, surface, children }: PhoneFrameProps) {
  const frame = useCurrentFrame();
  const isWa = surface === 'wa';

  const chipStyle: CSSProperties = {
    position: 'absolute',
    top: 118,
    left: 0,
    right: 0,
    display: 'flex',
    justifyContent: 'center',
    opacity: interpolate(frame, [0, 18], [0, 1], {
      extrapolateLeft: 'clamp',
      extrapolateRight: 'clamp',
      easing: Easing.bezier(0.16, 1, 0.3, 1),
    }),
    translate: interpolate(frame, [0, 18], ['0px 16px', '0px 0px'], {
      extrapolateLeft: 'clamp',
      extrapolateRight: 'clamp',
      easing: Easing.bezier(0.16, 1, 0.3, 1),
    }),
  };

  return (
    <div
      style={{
        position: 'absolute',
        inset: 0,
        backgroundColor: theme.background,
        fontFamily,
        color: theme.foreground,
      }}
    >
      <div style={chipStyle}>
        <div
          style={{
            backgroundColor: theme.accent,
            color: theme.accentForeground,
            fontSize: 38,
            fontWeight: 600,
            padding: '16px 34px',
            borderRadius: 999,
            letterSpacing: -0.5,
          }}
        >
          {chapter}
        </div>
      </div>

      <div
        style={{
          position: 'absolute',
          top: PHONE.top,
          left: (1080 - PHONE.width) / 2,
          width: PHONE.width,
          height: PHONE.height,
          borderRadius: PHONE.radius,
          backgroundColor: theme.phoneBezel,
          boxShadow: '0 40px 90px rgba(34, 30, 53, 0.28)',
          padding: PHONE.bezel,
          boxSizing: 'border-box',
        }}
      >
        <div
          style={{
            width: SCREEN.width,
            height: SCREEN.height,
            borderRadius: PHONE.radius - PHONE.bezel,
            backgroundColor: isWa ? '#ece5dd' : theme.card,
            overflow: 'hidden',
            position: 'relative',
          }}
        >
          <div
            style={{
              height: PHONE.header,
              backgroundColor: isWa ? '#075e54' : theme.primary,
              color: '#ffffff',
              display: 'flex',
              alignItems: 'center',
              gap: 20,
              padding: '0 32px',
              boxSizing: 'border-box',
            }}
          >
            <div
              style={{
                width: 72,
                height: 72,
                borderRadius: 999,
                backgroundColor: '#ffffff',
                color: isWa ? '#075e54' : theme.primary,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                fontSize: 38,
                fontWeight: 800,
              }}
            >
              M
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', lineHeight: 1.15 }}>
              <div style={{ fontSize: 36, fontWeight: 700 }}>{header}</div>
              <div style={{ fontSize: 26, opacity: 0.85 }}>{headerSub}</div>
            </div>
            <div style={{ marginLeft: 'auto', fontSize: 24, opacity: 0.85 }}>
              {isWa ? 'WhatsApp' : 'Influora'}
            </div>
          </div>
          {children}
        </div>
      </div>
    </div>
  );
}
