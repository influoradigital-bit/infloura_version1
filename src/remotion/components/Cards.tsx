import type { CSSProperties, ReactNode } from 'react';
import { Easing, interpolate, useCurrentFrame } from 'remotion';

import type { Card, FlagTone } from '../script';
import { theme } from '../theme';

const TONE: Record<FlagTone, { bg: string; fg: string; dot: string }> = {
  red: { bg: theme.destructive, fg: theme.destructiveForeground, dot: '#d44b4b' },
  amber: { bg: theme.warning, fg: theme.warningForeground, dot: '#d9a02b' },
  yellow: { bg: theme.warning, fg: theme.warningForeground, dot: '#e0c04a' },
  green: { bg: theme.success, fg: theme.successForeground, dot: '#3e9a6a' },
  info: { bg: theme.info, fg: theme.infoForeground, dot: '#4d84c6' },
};

function Shell({ start, children, accent }: { start: number; children: ReactNode; accent?: string }) {
  const frame = useCurrentFrame();
  const style: CSSProperties = {
    backgroundColor: theme.card,
    border: `2px solid ${theme.border}`,
    borderLeft: accent ? `10px solid ${accent}` : `2px solid ${theme.border}`,
    borderRadius: 28,
    padding: '24px 28px',
    marginBottom: 22,
    boxShadow: '0 10px 30px rgba(34, 30, 53, 0.08)',
    fontSize: 30,
    lineHeight: 1.3,
    opacity: interpolate(frame, [start, start + 14], [0, 1], {
      extrapolateLeft: 'clamp',
      extrapolateRight: 'clamp',
      easing: Easing.bezier(0.16, 1, 0.3, 1),
    }),
    scale: interpolate(frame, [start, start + 18], [0.96, 1], {
      extrapolateLeft: 'clamp',
      extrapolateRight: 'clamp',
      easing: Easing.bezier(0.16, 1, 0.3, 1),
    }),
    translate: interpolate(frame, [start, start + 18], ['0px 20px', '0px 0px'], {
      extrapolateLeft: 'clamp',
      extrapolateRight: 'clamp',
      easing: Easing.bezier(0.16, 1, 0.3, 1),
    }),
  };
  return <div style={style}>{children}</div>;
}

function Title({ children, sub }: { children: ReactNode; sub?: string }) {
  return (
    <div style={{ marginBottom: 16 }}>
      <div style={{ fontSize: 30, fontWeight: 800, letterSpacing: -0.3 }}>{children}</div>
      {sub ? <div style={{ fontSize: 24, color: theme.mutedForeground, marginTop: 4 }}>{sub}</div> : null}
    </div>
  );
}

function Row({ label, value, strong }: { label: string; value: string; strong?: boolean }) {
  return (
    <div
      style={{
        display: 'flex',
        justifyContent: 'space-between',
        gap: 20,
        padding: '10px 0',
        borderTop: `1px solid ${theme.border}`,
        fontSize: strong ? 32 : 28,
        fontWeight: strong ? 800 : 500,
      }}
    >
      <span style={{ color: strong ? theme.foreground : theme.mutedForeground }}>{label}</span>
      <span style={{ textAlign: 'right' }}>{value}</span>
    </div>
  );
}

function Chip({ tone, children }: { tone: FlagTone; children: ReactNode }) {
  const t = TONE[tone];
  return (
    <span
      style={{
        display: 'inline-block',
        backgroundColor: t.bg,
        color: t.fg,
        fontSize: 22,
        fontWeight: 700,
        padding: '6px 16px',
        borderRadius: 999,
        letterSpacing: 0.3,
      }}
    >
      {children}
    </span>
  );
}

/** Reveals list items one after another, `stagger` frames apart. */
function useStagger(start: number, index: number, stagger = 10): CSSProperties {
  const frame = useCurrentFrame();
  const at = start + 10 + index * stagger;
  return {
    opacity: interpolate(frame, [at, at + 10], [0, 1], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' }),
    translate: interpolate(frame, [at, at + 12], ['-10px 0px', '0px 0px'], {
      extrapolateLeft: 'clamp',
      extrapolateRight: 'clamp',
    }),
  };
}

function StaggerItem({ start, index, children }: { start: number; index: number; children: ReactNode }) {
  const style = useStagger(start, index);
  return <div style={style}>{children}</div>;
}

export function CardView({ start, card }: { start: number; card: Card }) {
  switch (card.type) {
    case 'prefs':
      return (
        <Shell start={start} accent={theme.primary}>
          <Title sub={card.sub}>{card.title}</Title>
          {card.rows.map((r, i) => (
            <StaggerItem key={r.label} start={start} index={i}>
              <Row label={r.label} value={r.value} />
            </StaggerItem>
          ))}
        </Shell>
      );

    case 'flags':
      return (
        <Shell start={start} accent="#d44b4b">
          <Title>{card.title}</Title>
          {card.flags.map((f, i) => (
            <StaggerItem key={f.code} start={start} index={i}>
              <div style={{ display: 'flex', gap: 16, alignItems: 'flex-start', padding: '10px 0' }}>
                <div
                  style={{
                    width: 18,
                    height: 18,
                    marginTop: 12,
                    borderRadius: 999,
                    backgroundColor: TONE[f.tone].dot,
                    flexShrink: 0,
                  }}
                />
                <div>
                  <div style={{ fontSize: 22, color: theme.mutedForeground, fontWeight: 700, letterSpacing: 0.5 }}>
                    {f.code}
                  </div>
                  <div style={{ fontSize: 28 }}>{f.text}</div>
                </div>
              </div>
            </StaggerItem>
          ))}
        </Shell>
      );

    case 'quote':
      return (
        <Shell start={start} accent={theme.accentForeground}>
          <Title>{card.title}</Title>
          {card.lines.map((l, i) => (
            <StaggerItem key={l.label} start={start} index={i}>
              <Row label={l.label} value={l.value} />
            </StaggerItem>
          ))}
          <StaggerItem start={start} index={card.lines.length}>
            <Row label={card.totalLabel ?? 'Total'} value={card.total} strong />
          </StaggerItem>
          <StaggerItem start={start} index={card.lines.length + 1}>
            <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'center', marginTop: 12 }}>
              <Chip tone="info">{card.chip}</Chip>
              <span style={{ fontSize: 24, color: theme.mutedForeground }}>{card.floor}</span>
            </div>
            <div style={{ marginTop: 14, fontSize: 28, fontWeight: 600, color: theme.accentForeground }}>
              {card.note}
            </div>
          </StaggerItem>
        </Shell>
      );

    case 'draft':
      return (
        <Shell start={start}>
          <Title sub={card.subject ? `Subject: ${card.subject}` : undefined}>{card.to}</Title>
          <div
            style={{
              backgroundColor: theme.background,
              borderRadius: 18,
              padding: '18px 22px',
              fontSize: 27,
              lineHeight: 1.4,
              color: theme.foreground,
            }}
          >
            {card.body}
          </div>
          <div style={{ marginTop: 14, fontSize: 22, color: theme.mutedForeground }}>{card.footer}</div>
          <div style={{ display: 'flex', gap: 12, marginTop: 18 }}>
            {card.actions.map((a, i) => (
              <div
                key={a}
                style={{
                  fontSize: 26,
                  fontWeight: 700,
                  padding: '12px 26px',
                  borderRadius: 999,
                  backgroundColor: i === 0 ? theme.accentForeground : theme.muted,
                  color: i === 0 ? '#ffffff' : theme.foreground,
                }}
              >
                {a}
              </div>
            ))}
          </div>
        </Shell>
      );

    case 'timeline':
      return (
        <Shell start={start} accent={theme.successForeground}>
          <Title>{card.title}</Title>
          {card.steps.map((s, i) => {
            const color =
              s.state === 'done' ? theme.successForeground : s.state === 'now' ? theme.accentForeground : theme.border;
            return (
              <StaggerItem key={s.label} start={start} index={i}>
                <div style={{ display: 'flex', gap: 18, alignItems: 'flex-start', padding: '10px 0' }}>
                  <div
                    style={{
                      width: 34,
                      height: 34,
                      borderRadius: 999,
                      backgroundColor: s.state === 'next' ? theme.card : color,
                      border: `4px solid ${color}`,
                      color: '#ffffff',
                      fontSize: 22,
                      fontWeight: 800,
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      flexShrink: 0,
                      marginTop: 4,
                    }}
                  >
                    {s.state === 'done' ? '✓' : ''}
                  </div>
                  <div>
                    <div style={{ fontSize: 29, fontWeight: s.state === 'now' ? 800 : 600 }}>{s.label}</div>
                    {s.sub ? <div style={{ fontSize: 24, color: theme.mutedForeground }}>{s.sub}</div> : null}
                  </div>
                </div>
              </StaggerItem>
            );
          })}
          {card.footer ? (
            <div style={{ marginTop: 10, fontSize: 22, color: theme.mutedForeground, borderTop: `1px solid ${theme.border}`, paddingTop: 12 }}>
              {card.footer}
            </div>
          ) : null}
        </Shell>
      );

    case 'snapshot':
      return (
        <Shell start={start} accent={theme.infoForeground}>
          <Title>{card.title}</Title>
          <div style={{ display: 'flex', gap: 16 }}>
            {card.stats.map((s, i) => (
              <StaggerItem key={s.label} start={start} index={i}>
                <div
                  style={{
                    backgroundColor: theme.background,
                    borderRadius: 18,
                    padding: '14px 22px',
                    minWidth: 150,
                    textAlign: 'center',
                  }}
                >
                  <div style={{ fontSize: 40, fontWeight: 800 }}>{s.value}</div>
                  <div style={{ fontSize: 22, color: theme.mutedForeground }}>{s.label}</div>
                </div>
              </StaggerItem>
            ))}
          </div>
          <div style={{ marginTop: 14, fontSize: 22, color: theme.mutedForeground }}>{card.note}</div>
        </Shell>
      );

    case 'health':
      return (
        <Shell start={start} accent={TONE[card.tone].dot}>
          <div style={{ marginBottom: 12 }}>
            <Chip tone={card.tone}>{card.code}</Chip>
          </div>
          <Title>{card.title}</Title>
          <div style={{ fontSize: 27, lineHeight: 1.4 }}>{card.body}</div>
          <div
            style={{
              marginTop: 16,
              fontSize: 26,
              fontWeight: 700,
              color: theme.accentForeground,
              backgroundColor: theme.accent,
              padding: '12px 18px',
              borderRadius: 16,
            }}
          >
            {card.fix}
          </div>
        </Shell>
      );

    case 'note':
      return (
        <Shell start={start} accent={theme.primary}>
          <Title sub={card.when}>{card.title}</Title>
          {card.items.map((it, i) => (
            <StaggerItem key={it} start={start} index={i}>
              <div style={{ display: 'flex', gap: 14, padding: '9px 0', fontSize: 28 }}>
                <span style={{ color: theme.primary, fontWeight: 800 }}>{i + 1}.</span>
                <span>{it}</span>
              </div>
            </StaggerItem>
          ))}
        </Shell>
      );

    case 'brands': {
      const warmthTone: Record<'W0' | 'W1' | 'W2' | 'W3', FlagTone> = { W0: 'green', W1: 'info', W2: 'info', W3: 'amber' };
      return (
        <Shell start={start} accent={theme.primary}>
          <Title>{card.title}</Title>
          {card.brands.map((b, i) => (
            <StaggerItem key={b.name} start={start} index={i}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 16, padding: '12px 0', borderTop: `1px solid ${theme.border}` }}>
                <div>
                  <div style={{ fontSize: 29, fontWeight: 700 }}>{b.name}</div>
                  <div style={{ fontSize: 23, color: theme.mutedForeground }}>{b.why}</div>
                </div>
                <Chip tone={warmthTone[b.warmth]}>{b.tag}</Chip>
              </div>
            </StaggerItem>
          ))}
          <div style={{ marginTop: 10, fontSize: 22, color: theme.mutedForeground }}>{card.footer}</div>
        </Shell>
      );
    }

    case 'hook':
      return <HookCard start={start} title={card.title} sub={card.sub} placeholder={card.placeholder} value={card.value} />;

    default:
      return null;
  }
}

function HookCard({ start, title, sub, placeholder, value }: { start: number; title: string; sub: string; placeholder: string; value: string }) {
  const frame = useCurrentFrame();
  const shown = Math.min(value.length, Math.max(0, Math.floor((frame - start - 14) * 1.6)));
  return (
    <Shell start={start} accent={theme.accentForeground}>
      <Title sub={sub}>{title}</Title>
      <div
        style={{
          border: `2px solid ${theme.ring}`,
          borderRadius: 18,
          padding: '16px 20px',
          minHeight: 120,
          fontSize: 27,
          lineHeight: 1.4,
          color: shown === 0 ? theme.mutedForeground : theme.foreground,
        }}
      >
        {shown === 0 ? placeholder : value.slice(0, shown)}
        <span style={{ visibility: 'hidden' }}>{shown === 0 ? '' : value.slice(shown)}</span>
        <span style={{ opacity: frame % 20 < 10 ? 1 : 0, color: theme.ring }}>|</span>
      </div>
    </Shell>
  );
}
