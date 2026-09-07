import type { ReactNode } from 'react';

import { fontFamily, theme } from '../../theme';
import { TYPE } from '../theme';
import { BrowserFrame } from './BrowserFrame';
import { StepRail } from './StepRail';

/**
 * The app chrome every form step shares: browser window, five-step rail, and
 * the heading of the current step. `heading` and `sub` are the literal step
 * label and description from `campaign-form.tsx:228`.
 */
export function FormShell({
  active,
  heading,
  sub,
  children,
}: {
  active: number;
  heading: string;
  sub: string;
  children: ReactNode;
}) {
  return (
    <BrowserFrame url="app.influora.in/brand/campaigns/new">
      <StepRail active={active} />
      <div
        style={{
          flex: 1,
          position: 'relative',
          padding: '38px 52px',
          fontFamily,
          overflow: 'hidden',
        }}
      >
        <div style={{ fontSize: TYPE.stepHeading, fontWeight: 800, color: theme.foreground, letterSpacing: -0.8 }}>
          {heading}
        </div>
        <div style={{ fontSize: TYPE.helper, color: theme.mutedForeground, marginTop: 6, marginBottom: 30 }}>
          {sub}
        </div>
        {children}
      </div>
    </BrowserFrame>
  );
}
