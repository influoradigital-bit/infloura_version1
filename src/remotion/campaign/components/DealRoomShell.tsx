import type { ReactNode } from 'react';

import { fontFamily, theme } from '../../theme';
import { BrowserFrame } from './BrowserFrame';

/**
 * The Deal Room, with the product's own five-phase progress rail. The labels
 * are the literal `phases` array from
 * `src/components/brand/deal-room/deal-room-step-progress.tsx:6` — including
 * "Secure funds", which is why this video never says "escrow".
 */
const PHASES = ['Negotiate', 'Contract', 'Secure funds', 'Deliver', 'Pay'];

export function DealRoomShell({
  active,
  title,
  subtitle,
  url = 'app.influora.in/brand/deals/dl_8f21/room',
  children,
}: {
  active: number;
  title: string;
  subtitle: string;
  /** Creator-side films pass the /creator/ path — same room, different signed-in side. */
  url?: string;
  children: ReactNode;
}) {
  return (
    <BrowserFrame url={url}>
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', fontFamily }}>
        <div
          style={{
            padding: '26px 46px 20px',
            borderBottom: `1px solid ${theme.border}`,
            backgroundColor: theme.card,
          }}
        >
          <div style={{ display: 'flex', alignItems: 'baseline', gap: 16 }}>
            <span style={{ fontSize: 34, fontWeight: 800, color: theme.foreground, letterSpacing: -0.6 }}>
              {title}
            </span>
            <span style={{ fontSize: 23, color: theme.mutedForeground }}>{subtitle}</span>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginTop: 20 }}>
            {PHASES.map((phase, index) => (
              <div key={phase} style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                <div
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 10,
                    padding: '9px 18px',
                    borderRadius: 999,
                    backgroundColor: index === active ? theme.primary : index < active ? theme.accent : theme.muted,
                    color:
                      index === active
                        ? theme.primaryForeground
                        : index < active
                          ? theme.accentForeground
                          : theme.mutedForeground,
                    fontSize: 22,
                    fontWeight: 700,
                  }}
                >
                  <span style={{ fontSize: 20 }}>{index < active ? '✓' : index + 1}</span>
                  {phase}
                </div>
                {index < PHASES.length - 1 ? (
                  <div style={{ width: 26, height: 3, borderRadius: 999, backgroundColor: theme.border }} />
                ) : null}
              </div>
            ))}
          </div>
        </div>
        <div style={{ flex: 1, padding: '30px 46px', overflow: 'hidden', position: 'relative' }}>{children}</div>
      </div>
    </BrowserFrame>
  );
}
