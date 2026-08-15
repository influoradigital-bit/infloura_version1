import { cn } from '@/lib/utils';
import { cssVars } from '@/lib/css-vars';

interface SlotProgressBarProps {
  filled: number;
  total: number;
  className?: string;
  /** Hide the "X/Y slots" caption when the parent renders its own */
  showLabel?: boolean;
  /**
   * Color tone. Default `hype` keeps the existing cyan Hype-campaign palette
   * for all current consumers. Pass `tone="meera"` inside the Meera surface
   * so the tracker uses the indigo/green money palette instead of a cyan
   * stripe (Priya M2) — do NOT recolor this component globally.
   */
  tone?: 'hype' | 'meera';
}

const TONE_CLASSES: Record<
  NonNullable<SlotProgressBarProps['tone']>,
  { track: string; fill: string; label: string }
> = {
  hype: { track: 'bg-hype', fill: 'bg-hype-solid', label: 'text-hype-foreground' },
  meera: { track: 'bg-meera-surface-2', fill: 'bg-meera-escrow', label: 'text-meera-text' },
};

/** Progress bar showing campaign slots filled vs total — tone-able per surface. */
export function SlotProgressBar({ filled, total, className, showLabel = true, tone = 'hype' }: SlotProgressBarProps) {
  const pct = total > 0 ? Math.min(100, Math.round((filled / total) * 100)) : 0;
  const toneClasses = TONE_CLASSES[tone];
  return (
    <div className={cn('space-y-1', className)}>
      <div
        className={cn('h-1.5 w-full overflow-hidden rounded-full', toneClasses.track)}
        role="progressbar"
        aria-valuenow={filled}
        aria-valuemin={0}
        aria-valuemax={total}
        aria-label={`${filled} of ${total} slots filled`}
      >
        <div
          className={cn('h-full rounded-full transition-[width] duration-500 w-[var(--slot-progress-w)]', toneClasses.fill)}
          ref={cssVars({ '--slot-progress-w': `${pct}%` })}
        />
      </div>
      {showLabel && (
        <p className={cn('text-xs', tone === 'meera' ? 'text-meera-text-muted' : 'text-muted-foreground')}>
          <span className={cn('font-medium', toneClasses.label)}>{filled}</span>/{total} slots filled
        </p>
      )}
    </div>
  );
}
