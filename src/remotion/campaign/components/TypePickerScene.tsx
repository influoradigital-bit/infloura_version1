import { Easing, interpolate, useCurrentFrame } from 'remotion';

import { fontFamily, theme } from '../../theme';
import { BrowserFrame } from './BrowserFrame';
import { Caption } from './Caption';

/**
 * The campaign-type picker that precedes the form.
 *
 * All three tiles are shown because all three exist — titles and descriptions
 * are the literal `TYPE_OPTIONS` strings from `src/pages/brand-new-campaign.tsx:56`.
 * The demo selects Open Campaign.
 */
const TYPE_OPTIONS = [
  {
    title: 'Open Campaign',
    description: 'Post a brief publicly — creators apply and you shortlist the best fits.',
    icon: '📣',
  },
  {
    title: 'Direct Deal',
    description: 'Invite specific creators and negotiate terms one-on-one in the Deal Room.',
    icon: '🔎',
  },
  {
    title: 'Hype Campaign',
    description: 'A 72-hour blitz: many creators remix one reel at a flat per-reel rate.',
    icon: '⚡',
  },
];

/** Frame the Open Campaign tile is chosen on. */
const SELECT_AT = 96;

export function TypePickerScene() {
  const frame = useCurrentFrame();
  return (
    <>
      <Caption kicker="Choose a type" text="Open, Direct or Hype" />
      <BrowserFrame url="app.influora.in/brand/campaigns/new">
        <div style={{ flex: 1, padding: '54px 64px', fontFamily }}>
          <div style={{ fontSize: 44, fontWeight: 800, color: theme.foreground, letterSpacing: -1 }}>
            What kind of campaign?
          </div>
          <div style={{ fontSize: 26, color: theme.mutedForeground, marginTop: 10, marginBottom: 44 }}>
            Pick how you want to work with creators.
          </div>
          <div style={{ display: 'flex', gap: 26 }}>
            {TYPE_OPTIONS.map((option, index) => (
              <div
                key={option.title}
                style={{
                  flex: 1,
                  padding: '34px 30px',
                  borderRadius: 20,
                  backgroundColor: theme.card,
                  border: `3px solid ${index === 0 && frame >= SELECT_AT ? theme.primary : theme.border}`,
                  boxShadow:
                    index === 0 && frame >= SELECT_AT
                      ? '0 20px 50px rgba(109,90,230,0.26)'
                      : '0 6px 18px rgba(34,30,53,0.06)',
                  opacity: interpolate(frame, [8 + index * 8, 26 + index * 8], [0, 1], {
                    extrapolateLeft: 'clamp',
                    extrapolateRight: 'clamp',
                    easing: Easing.bezier(0.16, 1, 0.3, 1),
                  }),
                  translate: interpolate(frame, [8 + index * 8, 30 + index * 8], ['0px 26px', '0px 0px'], {
                    extrapolateLeft: 'clamp',
                    extrapolateRight: 'clamp',
                    easing: Easing.bezier(0.16, 1, 0.3, 1),
                  }),
                  scale: interpolate(frame, [SELECT_AT, SELECT_AT + 8, SELECT_AT + 20], index === 0 ? [1, 1.04, 1.02] : [1, 1, 1], {
                    extrapolateLeft: 'clamp',
                    extrapolateRight: 'clamp',
                    easing: Easing.bezier(0.16, 1, 0.3, 1),
                    output: 'perceptual-scale',
                  }),
                }}
              >
                <div style={{ fontSize: 52 }}>{option.icon}</div>
                <div
                  style={{
                    fontSize: 34,
                    fontWeight: 800,
                    color: theme.foreground,
                    marginTop: 18,
                  }}
                >
                  {option.title}
                </div>
                <div
                  style={{
                    fontSize: 24,
                    lineHeight: 1.45,
                    color: theme.mutedForeground,
                    marginTop: 12,
                  }}
                >
                  {option.description}
                </div>
              </div>
            ))}
          </div>
        </div>
      </BrowserFrame>
    </>
  );
}
