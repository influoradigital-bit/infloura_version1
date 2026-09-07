import { cn } from '@/lib/utils';
import { CheckCircle2 } from 'lucide-react';

export type DealPhase = 'negotiate' | 'contract' | 'escrow' | 'deliver' | 'pay';

const phases: { id: DealPhase; label: string }[] = [
  { id: 'negotiate', label: 'Negotiate' },
  { id: 'contract', label: 'Contract' },
  { id: 'escrow', label: 'Secure funds' },
  { id: 'deliver', label: 'Deliver' },
  { id: 'pay', label: 'Pay' },
];

export function getDealPhase(dealStatus: string, contractStatus?: string): DealPhase {
  if (dealStatus === 'negotiating') return 'negotiate';
  // 'pending_signature' (F-0250 follow-up): one signature exists, party unknown — still the
  // "contract" phase, not a fall-through back to "negotiate".
  if (
    dealStatus === 'contracted' ||
    contractStatus === 'generated' ||
    contractStatus === 'pending_signature' ||
    contractStatus === 'brand_signed'
  ) {
    return 'contract';
  }
  if (contractStatus === 'creator_signed' && dealStatus !== 'in_progress') return 'escrow';
  if (dealStatus === 'in_progress' || dealStatus === 'review') return 'deliver';
  if (dealStatus === 'completed') return 'pay';
  return 'negotiate';
}

interface DealRoomStepProgressProps {
  currentPhase: DealPhase;
  onPhaseClick?: (phase: DealPhase) => void;
  className?: string;
}

export function DealRoomStepProgress({
  currentPhase,
  onPhaseClick,
  className,
}: DealRoomStepProgressProps) {
  const currentIndex = phases.findIndex((p) => p.id === currentPhase);

  /*
    `min-w-0` below is load-bearing, do not remove it.

    Without it this box keeps `min-width: auto`, so its min-content width (~564px —
    five never-wrapping phase chips) propagates up through every ancestor. On the
    landing hero that made the single-column grid in `landing.tsx` resolve a 612px
    track inside a 375px viewport, and the section's `overflow-hidden` then clipped
    the whole text column: the subhead, both CTAs and the stat row rendered
    off-screen on any phone. `overflow-x-auto` alone does not shrink a box — it only
    scrolls one that is already allowed to be narrower than its contents.
  */
  return (
    <div className={cn('flex min-w-0 items-center gap-1 overflow-x-auto pb-1', className)}>
      {phases.map((phase, index) => {
        const isComplete = index < currentIndex;
        const isCurrent = phase.id === currentPhase;
        const isClickable = !!onPhaseClick;

        return (
          <div key={phase.id} className="flex items-center shrink-0">
            <button
              type="button"
              disabled={!isClickable}
              onClick={() => onPhaseClick?.(phase.id)}
              className={cn(
                'flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium transition-colors',
                isCurrent && 'bg-primary/15 text-primary',
                // `text-success` is the pale mint SURFACE token (#ddf5e8) — 1.15:1 on a
                // white card, i.e. invisible. Foreground text uses `-foreground`.
                isComplete && !isCurrent && 'text-success-foreground',
                !isCurrent && !isComplete && 'text-muted-foreground',
                isClickable && 'hover:bg-muted cursor-pointer',
                !isClickable && 'cursor-default',
              )}
            >
              {isComplete ? (
                <CheckCircle2 className="h-3.5 w-3.5 text-success-foreground shrink-0" />
              ) : (
                <span
                  className={cn(
                    'flex h-5 w-5 items-center justify-center rounded-full text-[10px] font-semibold shrink-0',
                    isCurrent ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground',
                  )}
                >
                  {index + 1}
                </span>
              )}
              <span className="whitespace-nowrap">{phase.label}</span>
            </button>
            {index < phases.length - 1 && (
              <div
                className={cn(
                  'mx-1 h-px w-4 sm:w-6',
                  index < currentIndex ? 'bg-success/50' : 'bg-border',
                )}
              />
            )}
          </div>
        );
      })}
    </div>
  );
}
