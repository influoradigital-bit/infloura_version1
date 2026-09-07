import { Easing, interpolate, useCurrentFrame } from 'remotion';

import { theme } from '../../theme';
import { Caption } from './Caption';
import { FormShell } from './FormShell';

/**
 * Step 5. Headings are the literal strings from the real review step:
 * "Review Campaign" / "Review your campaign details before publishing"
 * (`campaign-form.tsx:1497`), the "Campaign Details" card (L1507), and the
 * "Suggested Creators" card with its `{N}% match` badge (L1628).
 *
 * The suggestions card is informational in the product — the code comment at
 * L1622 notes no selection is wired into submission — so the demo shows it as
 * a hint, never as a hiring step.
 */

function SummaryRow({ label, value, at }: { label: string; value: string; at: number }) {
  const frame = useCurrentFrame();
  return (
    <div
      style={{
        display: 'flex',
        justifyContent: 'space-between',
        padding: '13px 0',
        borderBottom: `1px solid ${theme.border}`,
        fontSize: 26,
        opacity: interpolate(frame, [at, at + 16], [0, 1], {
          extrapolateLeft: 'clamp',
          extrapolateRight: 'clamp',
          easing: Easing.bezier(0.16, 1, 0.3, 1),
        }),
      }}
    >
      <span style={{ color: theme.mutedForeground }}>{label}</span>
      <span style={{ fontWeight: 700, color: theme.foreground }}>{value}</span>
    </div>
  );
}

export function ReviewScene() {
  const frame = useCurrentFrame();
  return (
    <>
      <Caption kicker="Step 5 — Review" text="Review and suggested creators" />
      <FormShell active={4} heading="Review Campaign" sub="Review your campaign details before publishing">
        <div style={{ display: 'flex', gap: 26 }}>
          <div
            style={{
              flex: 1,
              padding: '24px 26px',
              borderRadius: 16,
              backgroundColor: theme.card,
              border: `2px solid ${theme.border}`,
            }}
          >
            <div style={{ fontSize: 28, fontWeight: 800, color: theme.foreground, marginBottom: 8 }}>
              Campaign Details
            </div>
            <SummaryRow label="Title" value="Diwali Glow Kit Launch" at={10} />
            <SummaryRow label="End Brand" value="Bloomveda" at={22} />
            <SummaryRow label="Platforms" value="Instagram, YouTube" at={34} />
            <SummaryRow label="Budget" value="₹15,000 – ₹60,000" at={46} />
            <SummaryRow label="Visibility" value="Public" at={58} />
          </div>

          <div
            style={{
              flex: 1,
              padding: '24px 26px',
              borderRadius: 16,
              backgroundColor: theme.card,
              border: `2px solid ${theme.border}`,
            }}
          >
            <div style={{ fontSize: 28, fontWeight: 800, color: theme.foreground, marginBottom: 16 }}>
              Suggested Creators
            </div>
            {[
              { name: 'Ritika Sharma', reasons: 'Beauty · Instagram', score: 94, initial: 'R' },
              { name: 'Aarav Nair', reasons: 'Skincare · YouTube', score: 89, initial: 'A' },
              { name: 'Meher Kaul', reasons: 'Beauty · Reels', score: 85, initial: 'M' },
            ].map((creator, index) => (
              <div
                key={creator.name}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 16,
                  padding: '14px 16px',
                  marginBottom: 12,
                  borderRadius: 12,
                  border: `1px solid ${theme.border}`,
                  backgroundColor: theme.background,
                  opacity: interpolate(frame, [70 + index * 20, 92 + index * 20], [0, 1], {
                    extrapolateLeft: 'clamp',
                    extrapolateRight: 'clamp',
                    easing: Easing.bezier(0.16, 1, 0.3, 1),
                  }),
                  translate: interpolate(frame, [70 + index * 20, 96 + index * 20], ['0px 18px', '0px 0px'], {
                    extrapolateLeft: 'clamp',
                    extrapolateRight: 'clamp',
                    easing: Easing.bezier(0.16, 1, 0.3, 1),
                  }),
                }}
              >
                <div
                  style={{
                    width: 52,
                    height: 52,
                    borderRadius: 999,
                    flexShrink: 0,
                    backgroundColor: theme.accent,
                    color: theme.accentForeground,
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    fontSize: 26,
                    fontWeight: 800,
                  }}
                >
                  {creator.initial}
                </div>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontSize: 27, fontWeight: 700, color: theme.foreground }}>{creator.name}</div>
                  <div style={{ fontSize: 21, color: theme.mutedForeground, marginTop: 2 }}>{creator.reasons}</div>
                </div>
                <div
                  style={{
                    flexShrink: 0,
                    padding: '7px 15px',
                    borderRadius: 999,
                    border: `2px solid ${theme.primary}`,
                    color: theme.accentForeground,
                    fontSize: 22,
                    fontWeight: 800,
                  }}
                >
                  {creator.score}% match
                </div>
              </div>
            ))}
          </div>
        </div>
      </FormShell>
    </>
  );
}

/** The publish click and the campaign going live. */
export function PublishScene() {
  const frame = useCurrentFrame();
  return (
    <>
      <Caption kicker="Step 5 — Review" text="Publish your campaign" />
      <FormShell active={4} heading="Review Campaign" sub="Review your campaign details before publishing">
        <div
          style={{
            height: 300,
            borderRadius: 16,
            backgroundColor: theme.card,
            border: `2px solid ${theme.border}`,
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            justifyContent: 'center',
            gap: 18,
            opacity: interpolate(frame, [96, 120], [1, 0], {
              extrapolateLeft: 'clamp',
              extrapolateRight: 'clamp',
              easing: Easing.bezier(0.16, 1, 0.3, 1),
            }),
          }}
        >
          <div style={{ fontSize: 30, color: theme.mutedForeground }}>Everything look right?</div>
          <div style={{ display: 'flex', gap: 20, alignItems: 'center' }}>
            <div
              style={{
                padding: '20px 38px',
                borderRadius: 14,
                border: `2px solid ${theme.border}`,
                backgroundColor: theme.card,
                color: theme.mutedForeground,
                fontSize: 30,
                fontWeight: 700,
              }}
            >
              Save Draft
            </div>
            <div
              style={{
                padding: '20px 44px',
                borderRadius: 14,
                backgroundColor: theme.primary,
                color: theme.primaryForeground,
                fontSize: 30,
                fontWeight: 800,
                boxShadow: '0 16px 40px rgba(109,90,230,0.36)',
                scale: interpolate(frame, [62, 74, 88], [1, 0.94, 1], {
                  extrapolateLeft: 'clamp',
                  extrapolateRight: 'clamp',
                  easing: Easing.bezier(0.16, 1, 0.3, 1),
                  output: 'perceptual-scale',
                }),
              }}
            >
              Publish Campaign ✓
            </div>
          </div>
        </div>

        <div
          style={{
            position: 'absolute',
            inset: 0,
            backgroundColor: theme.background,
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            justifyContent: 'center',
            gap: 20,
            opacity: interpolate(frame, [112, 136], [0, 1], {
              extrapolateLeft: 'clamp',
              extrapolateRight: 'clamp',
              easing: Easing.bezier(0.16, 1, 0.3, 1),
            }),
            scale: interpolate(frame, [112, 140], [0.9, 1], {
              extrapolateLeft: 'clamp',
              extrapolateRight: 'clamp',
              easing: Easing.bezier(0.16, 1, 0.3, 1),
              output: 'perceptual-scale',
            }),
          }}
        >
          <div
            style={{
              width: 104,
              height: 104,
              borderRadius: 999,
              backgroundColor: theme.success,
              color: theme.successForeground,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              fontSize: 54,
              fontWeight: 900,
            }}
          >
            ✓
          </div>
          <div style={{ fontSize: 42, fontWeight: 800, color: theme.foreground }}>Campaign is live</div>
          <div style={{ fontSize: 27, color: theme.mutedForeground }}>
            Creators can now see your brief and apply.
          </div>
        </div>
      </FormShell>
    </>
  );
}
