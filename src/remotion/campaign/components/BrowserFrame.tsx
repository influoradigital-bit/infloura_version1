import type { ReactNode } from 'react';

import { fontFamily, theme } from '../../theme';
import { BROWSER, VIEWPORT } from '../theme';

/**
 * A desktop browser window the app screens are drawn inside. Static chrome —
 * the traffic lights and URL bar never animate, so the viewer's eye goes to
 * the form, not the window.
 */
export function BrowserFrame({ url, children }: { url: string; children: ReactNode }) {
  return (
    <div
      style={{
        position: 'absolute',
        left: 180,
        top: BROWSER.top,
        width: BROWSER.width,
        height: BROWSER.height,
        borderRadius: BROWSER.radius,
        backgroundColor: theme.card,
        boxShadow: '0 40px 120px rgba(34,30,53,0.28)',
        overflow: 'hidden',
        fontFamily,
        display: 'flex',
        flexDirection: 'column',
      }}
    >
      <div
        style={{
          height: BROWSER.chrome,
          flexShrink: 0,
          backgroundColor: '#f1eefa',
          borderBottom: `1px solid ${theme.border}`,
          display: 'flex',
          alignItems: 'center',
          paddingLeft: 24,
          paddingRight: 24,
          gap: 18,
        }}
      >
        <div style={{ display: 'flex', gap: 9 }}>
          <div style={{ width: 13, height: 13, borderRadius: 999, backgroundColor: '#e5837d' }} />
          <div style={{ width: 13, height: 13, borderRadius: 999, backgroundColor: '#e3bd76' }} />
          <div style={{ width: 13, height: 13, borderRadius: 999, backgroundColor: '#84c194' }} />
        </div>
        <div
          style={{
            flex: 1,
            height: 34,
            borderRadius: 999,
            backgroundColor: '#ffffff',
            border: `1px solid ${theme.border}`,
            display: 'flex',
            alignItems: 'center',
            paddingLeft: 18,
            fontSize: 21,
            color: theme.mutedForeground,
            letterSpacing: 0.2,
          }}
        >
          {url}
        </div>
      </div>
      <div
        style={{
          width: VIEWPORT.width,
          height: VIEWPORT.height,
          backgroundColor: theme.background,
          display: 'flex',
          overflow: 'hidden',
        }}
      >
        {children}
      </div>
    </div>
  );
}
