import { AlertTriangle, CheckCircle2, Lock, Wallet } from 'lucide-react';

import { cn } from '@/lib/utils';

export type EscrowStage = 'FUNDED' | 'LOCKED' | 'RELEASED' | 'DISPUTED';

interface EscrowStatusBarProps {
  stage: EscrowStage;
  className?: string;
}

const STEPS: Array<{ id: Exclude<EscrowStage, 'DISPUTED'>; label: string; icon: typeof Wallet }> = [
  { id: 'FUNDED', label: 'Funded', icon: Wallet },
  { id: 'LOCKED', label: 'Locked', icon: Lock },
  { id: 'RELEASED', label: 'Released', icon: CheckCircle2 },
];

/**
 * Visual escrow lifecycle: Funded → Locked → Released, with a Disputed
 * override state that replaces the final step.
 */
export function EscrowStatusBar({ stage, className }: EscrowStatusBarProps) {
  const disputed = stage === 'DISPUTED';
  const activeIndex = disputed ? 1 : STEPS.findIndex((s) => s.id === stage);

  return (
    <div className={cn('flex items-center gap-1', className)} aria-label={`Escrow status: ${stage.toLowerCase()}`}>
      {STEPS.map((step, i) => {
        const isDisputedFinal = disputed && i === STEPS.length - 1;
        const reached = !isDisputedFinal && i <= activeIndex;
        const Icon = isDisputedFinal ? AlertTriangle : step.icon;
        return (
          <div key={step.id} className="flex flex-1 items-center gap-1 last:flex-none">
            <div className="flex flex-col items-center gap-1">
              <span
                className={cn(
                  'flex h-7 w-7 items-center justify-center rounded-full border',
                  isDisputedFinal && 'border-stage-disputed-border bg-stage-disputed text-stage-disputed-fg',
                  !isDisputedFinal && reached && 'border-stage-approved-border bg-stage-approved text-stage-approved-fg',
                  !isDisputedFinal && !reached && 'border-border bg-muted text-muted-foreground',
                )}
              >
                <Icon className="h-3.5 w-3.5" />
              </span>
              <span
                className={cn(
                  'text-[10px] font-medium',
                  isDisputedFinal ? 'text-stage-disputed-fg' : reached ? 'text-foreground' : 'text-muted-foreground',
                )}
              >
                {isDisputedFinal ? 'Disputed' : step.label}
              </span>
            </div>
            {i < STEPS.length - 1 && (
              <div
                className={cn(
                  'mb-4 h-px flex-1',
                  i < activeIndex && !disputed ? 'bg-stage-approved-fg/40' : 'bg-border',
                )}
                aria-hidden="true"
              />
            )}
          </div>
        );
      })}
    </div>
  );
}
