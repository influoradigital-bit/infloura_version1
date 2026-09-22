import type { ReactNode } from 'react';
import { FileText, PenLine, UserRound, X } from 'lucide-react';

import { Button } from '@/components/ui/button';
import type { CreatorCreditBalance } from '@/lib/api';
import { creditsCopy } from '@/lib/copy/creator-credits';
import { FALLBACK_COST, actionBlockedReason, actionCost } from '@/lib/creator-quick-actions';
import type { CreatorTurnAction } from '@/lib/meera-api';
import { cn } from '@/lib/utils';

/**
 * Quick-action buttons above the creator chat box (2026-09-22, Swapnil, option A):
 * "Write a script", "Analyse a brief", "Review my profile".
 *
 * A button never sends by itself. Script and profile put the chat box into that action (the strip
 * below says what it does and what it costs) and the creator still presses Send, so a stray tap on
 * a phone costs nothing. "Analyse a brief" hands off to the existing brief card on the page.
 *
 * With credits switched off the buttons still work and show no price.
 */

export interface MeeraQuickActionsProps {
  language?: string;
  balance: CreatorCreditBalance | null;
  /** The action the chat box is currently in, if any (its button shows as pressed). */
  active: CreatorTurnAction | null;
  /** Tapping the active button again turns it off (passes null). */
  onPick: (action: CreatorTurnAction | null) => void;
  /** Hidden when the page has no brief card to hand off to. */
  onAnalyseBrief?: () => void;
  disabled?: boolean;
  className?: string;
}

export function MeeraQuickActions({
  language,
  balance,
  active,
  onPick,
  onAnalyseBrief,
  disabled,
  className,
}: MeeraQuickActionsProps) {
  const showCost = balance?.enabled === true;
  const briefCost = balance?.costs?.brief ?? FALLBACK_COST;

  const chip = (
    key: string,
    label: string,
    icon: ReactNode,
    cost: number,
    onClick: () => void,
    pressed?: boolean,
  ) => (
    <Button
      key={key}
      type="button"
      size="sm"
      variant={pressed ? 'default' : 'outline'}
      className="h-8 shrink-0 gap-1.5 rounded-full px-3 text-xs"
      aria-pressed={pressed}
      aria-label={showCost ? `${label}, ${creditsCopy('action.cost', language, { n: cost })}` : label}
      onClick={onClick}
      disabled={disabled}
      data-testid={`quick-action-${key}`}
    >
      {icon}
      <span>{label}</span>
      {showCost ? <span className="opacity-70" aria-hidden="true">· {cost}</span> : null}
    </Button>
  );

  return (
    <div
      role="group"
      aria-label="Quick actions"
      className={cn('-mx-1 flex gap-2 overflow-x-auto px-1 pb-1 [scrollbar-width:none]', className)}
    >
      {chip(
        'script',
        creditsCopy('action.script', language),
        <PenLine className="h-3.5 w-3.5" aria-hidden="true" />,
        actionCost('SCRIPT', balance),
        () => onPick(active === 'SCRIPT' ? null : 'SCRIPT'),
        active === 'SCRIPT',
      )}
      {onAnalyseBrief
        ? chip(
            'brief',
            creditsCopy('action.brief', language),
            <FileText className="h-3.5 w-3.5" aria-hidden="true" />,
            briefCost,
            () => {
              onPick(null);
              onAnalyseBrief();
            },
          )
        : null}
      {chip(
        'profile',
        creditsCopy('action.profile', language),
        <UserRound className="h-3.5 w-3.5" aria-hidden="true" />,
        actionCost('PROFILE_REVIEW', balance),
        () => onPick(active === 'PROFILE_REVIEW' ? null : 'PROFILE_REVIEW'),
        active === 'PROFILE_REVIEW',
      )}
    </div>
  );
}

export interface MeeraActionStripProps {
  action: CreatorTurnAction;
  language?: string;
  balance: CreatorCreditBalance | null;
  onCancel: () => void;
  onBuy: () => void;
  className?: string;
}

/** The line under the buttons while an action is on: what it does, what it costs, or why not now. */
export function MeeraActionStrip({ action, language, balance, onCancel, onBuy, className }: MeeraActionStripProps) {
  const n = actionCost(action, balance);
  const blocked = actionBlockedReason(action, balance, language);
  const hint = creditsCopy(action === 'SCRIPT' ? 'action.scriptHint' : 'action.profileHint', language, { n });
  const lacksCredits = blocked !== null && (balance?.total ?? 0) < n;

  return (
    <div
      data-testid="meera-action-strip"
      className={cn('flex items-start gap-2 rounded-md bg-muted px-2.5 py-2 text-xs', className)}
    >
      <p className="min-w-0 flex-1 leading-snug" role={blocked ? 'alert' : undefined}>
        {blocked ?? hint}
        {lacksCredits ? (
          <>
            {' '}
            <button type="button" className="font-medium text-primary underline underline-offset-2" onClick={onBuy}>
              {creditsCopy('action.buy', language)}
            </button>
          </>
        ) : null}
      </p>
      <button
        type="button"
        onClick={onCancel}
        className="shrink-0 rounded p-0.5 text-muted-foreground hover:text-foreground"
        aria-label={creditsCopy('action.cancel', language)}
      >
        <X className="h-3.5 w-3.5" aria-hidden="true" />
      </button>
    </div>
  );
}
