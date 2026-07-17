import { cn } from '@/lib/utils';
import { CheckCircle2 } from 'lucide-react';

export type DealPhase = 'negotiate' | 'contract' | 'escrow' | 'deliver' | 'pay';

const phases: { id: DealPhase; label: string }[] = [
  { id: 'negotiate', label: 'Negotiate' },
  { id: 'contract', label: 'Contract' },
  { id: 'escrow', label: 'Fund escrow' },
  { id: 'deliver', label: 'Deliver' },
  { id: 'pay', label: 'Pay' },
];

export function getDealPhase(dealStatus: string, contractStatus?: string): DealPhase {
  if (dealStatus === 'negotiating') return 'negotiate';
  if (dealStatus === 'contracted' || contractStatus === 'generated' || contractStatus === 'brand_signed') {
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

  return (
    <div className={cn('flex items-center gap-1 overflow-x-auto pb-1', className)}>
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
                isComplete && !isCurrent && 'text-success',
                !isCurrent && !isComplete && 'text-muted-foreground',
                isClickable && 'hover:bg-muted cursor-pointer',
                !isClickable && 'cursor-default',
              )}
            >
              {isComplete ? (
                <CheckCircle2 className="h-3.5 w-3.5 text-success shrink-0" />
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
