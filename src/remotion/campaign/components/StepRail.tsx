import { Easing, interpolate, useCurrentFrame } from 'remotion';

import { fontFamily, theme } from '../../theme';
import { RAIL_WIDTH, TYPE } from '../theme';

/**
 * The five-step rail. Labels and descriptions are the literal strings from the
 * real form's `steps` array — `src/components/brand/campaigns/campaign-form.tsx:228`.
 * Nothing here is invented copy.
 */
const STEPS = [
  { label: 'Basics', description: 'Campaign details' },
  { label: 'Content', description: 'Platforms & formats' },
  { label: 'Budget', description: 'Timeline & budget' },
  { label: 'Requirements', description: 'Creator criteria' },
  { label: 'Review', description: 'Final review' },
];

export function StepRail({ active }: { active: number }) {
  const frame = useCurrentFrame();
  return (
    <div
      style={{
        width: RAIL_WIDTH,
        flexShrink: 0,
        height: '100%',
        backgroundColor: theme.card,
        borderRight: `1px solid ${theme.border}`,
        padding: '40px 34px',
        fontFamily,
        display: 'flex',
        flexDirection: 'column',
        gap: 10,
      }}
    >
      <div
        style={{
          fontSize: 22,
          fontWeight: 700,
          letterSpacing: 1.6,
          textTransform: 'uppercase',
          color: theme.mutedForeground,
          marginBottom: 22,
        }}
      >
        New campaign
      </div>
      {STEPS.map((step, index) => (
        <div
          key={step.label}
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 18,
            padding: '15px 16px',
            borderRadius: 14,
            backgroundColor: index === active ? theme.accent : 'transparent',
            opacity: index > active ? 0.45 : 1,
          }}
        >
          <div
            style={{
              width: 44,
              height: 44,
              flexShrink: 0,
              borderRadius: 999,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              fontSize: 22,
              fontWeight: 800,
              backgroundColor: index <= active ? theme.primary : theme.muted,
              color: index <= active ? theme.primaryForeground : theme.mutedForeground,
              scale: interpolate(frame, [0, 14], [index === active ? 0.7 : 1, 1], {
                extrapolateLeft: 'clamp',
                extrapolateRight: 'clamp',
                easing: Easing.bezier(0.16, 1, 0.3, 1),
                output: 'perceptual-scale',
              }),
            }}
          >
            {index < active ? '✓' : index + 1}
          </div>
          <div>
            <div
              style={{
                fontSize: TYPE.label,
                fontWeight: 700,
                color: index === active ? theme.accentForeground : theme.foreground,
              }}
            >
              {step.label}
            </div>
            <div style={{ fontSize: TYPE.helper, color: theme.mutedForeground, marginTop: 2 }}>
              {step.description}
            </div>
          </div>
        </div>
      ))}
    </div>
  );
}
