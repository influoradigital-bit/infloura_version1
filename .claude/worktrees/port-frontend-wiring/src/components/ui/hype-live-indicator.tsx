import { cn } from '@/lib/utils';

interface HypeLiveIndicatorProps {
  /** Hours remaining in the 72-hr window; shown when provided */
  hoursLeft?: number;
  className?: string;
}

/**
 * Pulsing cyan "LIVE" indicator for Hype campaigns inside their 72-hr window.
 * Pulse is disabled via prefers-reduced-motion (see .hype-pulse in globals.css).
 */
export function HypeLiveIndicator({ hoursLeft, className }: HypeLiveIndicatorProps) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 rounded-full border border-hype-border bg-hype px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-hype-foreground',
        className,
      )}
      role="status"
      aria-label={hoursLeft != null ? `Live, ${hoursLeft} hours left` : 'Live'}
    >
      <span className="hype-pulse h-1.5 w-1.5 rounded-full bg-hype-solid" aria-hidden="true" />
      LIVE
      {hoursLeft != null && (
        <span className="font-mono font-medium normal-case tracking-normal">
          · {hoursLeft}h left
        </span>
      )}
    </span>
  );
}
