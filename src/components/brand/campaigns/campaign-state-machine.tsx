import * as React from 'react';
import {
  FileEdit,
  Wallet,
  Radio,
  Users,
  MessageSquare,
  FileText,
  Camera,
  Eye,
  CheckCircle2,
  Lock,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { cssVars } from '@/lib/css-vars';
import { IconBadge } from '@/components/shared/icon-badge';
import { getCampaignStateIconVariant } from '@/lib/icon-theme';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip';

// 9 Campaign States per PDF Blueprint Section 4.4
export type CampaignState = 
  | 'DRAFT'
  | 'FUNDED'
  | 'LIVE'
  | 'MATCHED'
  | 'NEGOTIATING'
  | 'CONTRACTED'
  | 'IN_PRODUCTION'
  | 'IN_REVIEW'
  | 'SETTLED';

interface StateConfig {
  id: CampaignState;
  label: string;
  shortLabel: string;
  description: string;
  icon: typeof FileEdit;
  color: string;
  bgColor: string;
  borderColor: string;
}

const STATES: StateConfig[] = [
  {
    id: 'DRAFT',
    label: 'Draft',
    shortLabel: 'Draft',
    description: 'Campaign is being created',
    icon: FileEdit,
    color: 'text-stage-draft-fg',
    bgColor: 'bg-stage-draft',
    borderColor: 'border-stage-draft-border',
  },
  {
    id: 'FUNDED',
    label: 'Funded',
    shortLabel: 'Funded',
    description: 'Budget allocated, ready to go live',
    icon: Wallet,
    color: 'text-stage-outreach-fg',
    bgColor: 'bg-stage-outreach',
    borderColor: 'border-stage-outreach-border',
  },
  {
    id: 'LIVE',
    label: 'Live',
    shortLabel: 'Live',
    description: 'Accepting applications from creators',
    icon: Radio,
    color: 'text-stage-progress-fg',
    bgColor: 'bg-stage-progress',
    borderColor: 'border-stage-progress-border',
  },
  {
    id: 'MATCHED',
    label: 'Matched',
    shortLabel: 'Match',
    description: 'Creators matched, awaiting outreach',
    icon: Users,
    color: 'text-stage-outreach-fg',
    bgColor: 'bg-stage-outreach',
    borderColor: 'border-stage-outreach-border',
  },
  {
    id: 'NEGOTIATING',
    label: 'Negotiating',
    shortLabel: 'Nego',
    description: 'Deal room discussions in progress',
    icon: MessageSquare,
    color: 'text-stage-negotiating-fg',
    bgColor: 'bg-stage-negotiating',
    borderColor: 'border-stage-negotiating-border',
  },
  {
    id: 'CONTRACTED',
    label: 'Contracted',
    shortLabel: 'Contract',
    description: 'Contracts signed, escrow locked',
    icon: FileText,
    color: 'text-stage-contracted-fg',
    bgColor: 'bg-stage-contracted',
    borderColor: 'border-stage-contracted-border',
  },
  {
    id: 'IN_PRODUCTION',
    label: 'In Production',
    shortLabel: 'Produce',
    description: 'Creators creating content',
    icon: Camera,
    color: 'text-stage-progress-fg',
    bgColor: 'bg-stage-progress',
    borderColor: 'border-stage-progress-border',
  },
  {
    id: 'IN_REVIEW',
    label: 'In Review',
    shortLabel: 'Review',
    description: 'Content submitted, awaiting approval',
    icon: Eye,
    color: 'text-stage-review-fg',
    bgColor: 'bg-stage-review',
    borderColor: 'border-stage-review-border',
  },
  {
    id: 'SETTLED',
    label: 'Settled',
    shortLabel: 'Done',
    description: 'Campaign complete, payments released',
    icon: CheckCircle2,
    color: 'text-stage-approved-fg',
    bgColor: 'bg-stage-approved',
    borderColor: 'border-stage-approved-border',
  },
];

interface CampaignStateMachineProps {
  currentState: CampaignState;
  collaboratorStates?: Record<string, CampaignState>; // Individual collaborator states
  onStateClick?: (state: CampaignState) => void;
  variant?: 'full' | 'compact' | 'mini';
  showEscrowIndicator?: boolean;
  escrowLocked?: number;
}

export function CampaignStateMachine({
  currentState,
  collaboratorStates,
  onStateClick,
  variant = 'full',
  showEscrowIndicator = false,
  escrowLocked = 0,
}: CampaignStateMachineProps) {
  const currentIndex = STATES.findIndex(s => s.id === currentState);
  
  // Count collaborators in each state
  const stateCount = React.useMemo(() => {
    if (!collaboratorStates) return {};
    const counts: Record<string, number> = {};
    Object.values(collaboratorStates).forEach(state => {
      counts[state] = (counts[state] || 0) + 1;
    });
    return counts;
  }, [collaboratorStates]);

  if (variant === 'mini') {
    return (
      <div className="flex items-center gap-1">
        {STATES.map((state, idx) => {
          const isCompleted = idx < currentIndex;
          const isCurrent = idx === currentIndex;

          return (
            <TooltipProvider key={state.id}>
              <Tooltip>
                <TooltipTrigger asChild>
                  <div
                    className={cn(
                      'h-2 w-2 rounded-full transition-all',
                      isCompleted ? 'bg-stage-approved-fg' :
                      isCurrent ? 'bg-stage-contracted-fg' :
                      'bg-muted'
                    )}
                  />
                </TooltipTrigger>
                <TooltipContent>
                  <p className="font-medium">{state.label}</p>
                  <p className="text-xs text-muted-foreground">{state.description}</p>
                </TooltipContent>
              </Tooltip>
            </TooltipProvider>
          );
        })}
      </div>
    );
  }

  if (variant === 'compact') {
    return (
      <div className="flex items-center gap-2 overflow-x-auto pb-2">
        {STATES.map((state, idx) => {
          const isCompleted = idx < currentIndex;
          const isCurrent = idx === currentIndex;
          const StateIcon = state.icon;
          const count = stateCount[state.id];
          
          return (
            <React.Fragment key={state.id}>
              <TooltipProvider>
                <Tooltip>
                  <TooltipTrigger asChild>
                    <button
                      onClick={() => onStateClick?.(state.id)}
                      className={cn(
                        'flex items-center gap-1.5 px-2 py-1 rounded-full text-xs font-medium transition-all whitespace-nowrap',
                        isCompleted ? 'bg-stage-approved text-stage-approved-fg' :
                        isCurrent ? `${state.bgColor} ${state.color}` :
                        'bg-muted text-muted-foreground',
                        onStateClick && 'hover:opacity-80 cursor-pointer'
                      )}
                    >
                      <StateIcon className="h-3 w-3" />
                      <span>{state.shortLabel}</span>
                      {count && count > 0 && (
                        <span className="ml-0.5 h-4 min-w-4 px-1 rounded-full bg-background text-[10px] flex items-center justify-center">
                          {count}
                        </span>
                      )}
                    </button>
                  </TooltipTrigger>
                  <TooltipContent>
                    <p className="font-medium">{state.label}</p>
                    <p className="text-xs text-muted-foreground">{state.description}</p>
                  </TooltipContent>
                </Tooltip>
              </TooltipProvider>
              {idx < STATES.length - 1 && (
                <div className={cn(
                  'h-0.5 w-4 flex-shrink-0',
                  idx < currentIndex ? 'bg-stage-approved-border' : 'bg-muted'
                )} />
              )}
            </React.Fragment>
          );
        })}
      </div>
    );
  }

  // Full variant - visual stepper
  return (
    <div className="relative">
      {/* Progress line background */}
      <div className="absolute top-6 left-6 right-6 h-1 bg-muted rounded-full" />
      
      {/* Progress line filled */}
      <div
        className="absolute top-6 left-6 h-1 bg-stage-approved-fg rounded-full transition-all duration-500 w-[var(--sm-progress-w)]"
        ref={cssVars({ '--sm-progress-w': `calc(${(currentIndex / (STATES.length - 1)) * 100}% - 24px)` })}
      />

      {/* State nodes */}
      <div className="relative flex justify-between">
        {STATES.map((state, idx) => {
          const isCompleted = idx < currentIndex;
          const isCurrent = idx === currentIndex;
          const isFuture = idx > currentIndex;
          const StateIcon = state.icon;
          const count = stateCount[state.id];
          
          return (
            <TooltipProvider key={state.id}>
              <Tooltip>
                <TooltipTrigger asChild>
                  <button
                    onClick={() => onStateClick?.(state.id)}
                    className={cn(
                      'flex flex-col items-center gap-2 transition-all group',
                      onStateClick && 'cursor-pointer'
                    )}
                  >
                    {/* Icon Circle */}
                    <div className="relative">
                      {isCompleted ? (
                        <IconBadge
                          icon={CheckCircle2}
                          variant="approved"
                          size="lg"
                          rounded="full"
                          className="!h-12 !w-12 ring-2 ring-stage-approved-border"
                        />
                      ) : (
                        <IconBadge
                          icon={StateIcon}
                          variant={getCampaignStateIconVariant(state.id)}
                          size="lg"
                          rounded="full"
                          active={isCurrent}
                          className={cn(
                            '!h-12 !w-12',
                            isFuture && 'opacity-60',
                            isCurrent && 'ring-2 ring-offset-2 ring-offset-background ring-primary/30',
                          )}
                        />
                      )}
                      
                      {/* Count badge */}
                      {count && count > 0 && (
                        <span className={cn(
                          'absolute -top-1 -right-1 h-5 min-w-5 px-1 rounded-full text-[10px] font-bold flex items-center justify-center',
                          isCurrent ? 'bg-primary text-primary-foreground' : 'bg-muted-foreground text-background'
                        )}>
                          {count}
                        </span>
                      )}
                    </div>
                    
                    {/* Label */}
                    <span className={cn(
                      'text-xs font-medium text-center max-w-16 leading-tight',
                      isCompleted ? 'text-stage-approved-fg' :
                      isCurrent ? state.color :
                      'text-muted-foreground'
                    )}>
                      {state.shortLabel}
                    </span>
                  </button>
                </TooltipTrigger>
                <TooltipContent side="bottom" className="max-w-xs">
                  <p className="font-semibold">{state.label}</p>
                  <p className="text-xs text-muted-foreground mt-1">{state.description}</p>
                  {count && count > 0 && (
                    <p className="text-xs mt-1 font-medium">{count} collaborator{count > 1 ? 's' : ''} in this state</p>
                  )}
                </TooltipContent>
              </Tooltip>
            </TooltipProvider>
          );
        })}
      </div>

      {/* Escrow indicator */}
      {showEscrowIndicator && escrowLocked > 0 && (
        <div className="mt-6 flex items-center justify-center gap-2 text-sm">
          <Lock className="h-4 w-4 text-stage-negotiating-fg" />
          <span className="text-muted-foreground">Escrow Locked:</span>
          <span className="font-semibold text-stage-negotiating-fg">
            {escrowLocked >= 100000 
              ? `₹${(escrowLocked / 100000).toFixed(1)}L`
              : `₹${(escrowLocked / 1000).toFixed(0)}K`
            }
          </span>
        </div>
      )}
    </div>
  );
}
