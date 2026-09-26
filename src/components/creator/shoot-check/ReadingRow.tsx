import * as React from 'react';

import { cn } from '@/lib/utils';

export type ReadingSeverity = 'ok' | 'warn' | 'bad' | 'unknown';

export interface ReadingRowProps {
  icon: React.ReactNode;
  label: string;
  /** Short measured value shown next to the label, e.g. "62", "3°", "-8 dB gap". */
  value: string;
  severity: ReadingSeverity;
  /** The one-line fix / status text — always rendered, even for `unknown` (empty/denied/
   * unsupported states must still show advice as TEXT, never a blank row). */
  fix: string;
}

/**
 * `bg-*`/`text-*-foreground` pairs, not `text-*` alone — this theme is pale-bg/strong-fg
 * (see globals.css), so a bare `text-destructive` reads as near-invisible on the card
 * background. Pairing the pale background with its matching strong foreground keeps every
 * severity at WCAG AA contrast.
 */
const SEVERITY_CLASSES: Record<ReadingSeverity, string> = {
  ok: 'bg-success text-success-foreground',
  warn: 'bg-warning text-warning-foreground',
  bad: 'bg-destructive text-destructive-foreground',
  unknown: 'bg-muted text-muted-foreground',
};

export function ReadingRow({ icon, label, value, severity, fix }: ReadingRowProps) {
  return (
    <div className="flex items-start gap-3 rounded-lg border border-border bg-card p-3">
      <span
        className={cn('mt-0.5 flex size-8 shrink-0 items-center justify-center rounded-full', SEVERITY_CLASSES[severity])}
        aria-hidden="true"
      >
        {icon}
      </span>
      <div className="min-w-0 flex-1">
        <div className="flex items-center justify-between gap-2">
          <span className="text-sm font-medium text-foreground">{label}</span>
          <span className="shrink-0 text-xs tabular-nums text-muted-foreground">{value}</span>
        </div>
        <p className="mt-0.5 text-sm text-muted-foreground">{fix}</p>
      </div>
    </div>
  );
}
