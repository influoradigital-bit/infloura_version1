import * as React from 'react';
import { Flame, Loader2 } from 'lucide-react';

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { IGConnectPrompt } from '@/components/creator/copilot/IGConnectPrompt';
import { ChallengeComparisonRow } from '@/components/creator/challenge/ChallengeComparisonRow';
import { ChallengeDayStrip } from '@/components/creator/challenge/ChallengeDayStrip';
import { useCreatorChallenge } from '@/hooks/useCreatorChallenge';
import { challengeCopy } from '@/lib/copy/creator-challenge';
import { formatWindow } from '@/lib/creator-challenge-format';
import { ApiError } from '@/lib/api';
import { cn } from '@/lib/utils';

export interface ChallengeCardProps {
  /** BCP-47-ish language tag, same convention as `creatorAgentPrefs.creator_language`
   *  ("en-IN" / "hi-IN") — the page already tracks this for Meera. Defaults to English. */
  language?: string;
  /**
   * Fills Meera's chat composer with `prompt` and opens the chat — NEVER sends it (ruling
   * R-U1, same prefill-only contract as `openMeeraWithPrompt` in creator-copilot.tsx, which
   * is what callers pass here). This component never calls a send API itself.
   */
  onAskMeera: (prompt: string) => void;
  className?: string;
}

/** Builds the two Meera prefill prompts for today's planned type — never sent automatically,
 *  only ever handed to `onAskMeera` (prefill-only, ruling R-U1). */
function buildPrompts(
  typeLabel: string,
  daypartPhrase: string,
  timeRange: string,
  hi: boolean,
): { script: string; idea: string } {
  const when = timeRange ? `${daypartPhrase}, ${timeRange}` : daypartPhrase;
  return hi
    ? {
        script: `आज के ${typeLabel} (${when}) के लिए एक script लिख दो।`,
        idea: `आज के ${typeLabel} के लिए एक idea दो।`,
      }
    : {
        script: `Write me a script for today's ${typeLabel.toLowerCase()} — ${when}.`,
        idea: `Give me an idea for today's ${typeLabel.toLowerCase()}.`,
      };
}

/**
 * The creator 7-day challenge card (CHALLENGE-SPEC.md, 2026-09-23, Frontend §2/§3). Single
 * switch-on-state component, same shape as `DailySuggestionSection`: not-connected -> intro ->
 * active -> completed, each a small dedicated body rather than one giant conditional render.
 *
 * Placement: top of the Co-pilot page (`src/pages/creator-copilot.tsx`) and, via
 * `ChallengeTile.tsx`, a compact linking tile on the creator dashboard.
 */
export function ChallengeCard({ language, onAskMeera, className }: ChallengeCardProps) {
  const { data, status, error, starting, startError, start, ending, end, retry } = useCreatorChallenge();
  const copy = React.useMemo(() => challengeCopy(language), [language]);
  const hi = (language ?? '').startsWith('hi');
  // Round 2 QA item 1 — the quiet "End challenge" confirm dialog. Local to this component:
  // the hook already exposes `end`/`ending`, this state only tracks whether the dialog is open.
  const [showEndConfirm, setShowEndConfirm] = React.useState(false);

  if (status === 'loading') {
    return (
      <Card className={className}>
        <CardContent className="space-y-3 py-4">
          <Skeleton className="h-5 w-40" />
          <Skeleton className="h-4 w-full" />
          <Skeleton className="h-11 w-full" />
        </CardContent>
      </Card>
    );
  }

  if (status === 'error' || !data) {
    return (
      <Card className={className}>
        <CardContent className="flex items-center justify-between gap-3 py-4">
          <p className="text-sm text-muted-foreground">{error ?? copy.errorGeneric}</p>
          <Button size="sm" variant="ghost" onClick={retry} className="h-8 text-xs">
            Retry
          </Button>
        </CardContent>
      </Card>
    );
  }

  // -------------------------------------------------------------------------
  // Not connected — short explanation, then the EXISTING connect entry point
  // (IGConnectPrompt) verbatim, no forked OAuth logic.
  // -------------------------------------------------------------------------
  if (!data.instagramConnected) {
    return (
      <div className={cn('space-y-3', className)}>
        <Card>
          <CardContent className="py-4">
            <p className="text-sm font-medium">{copy.notConnectedHeading}</p>
            <p className="mt-1 text-sm text-muted-foreground">{copy.notConnectedBody}</p>
          </CardContent>
        </Card>
        <IGConnectPrompt />
      </div>
    );
  }

  const startErrorMessage = (() => {
    if (!startError) return null;
    if (startError instanceof ApiError) {
      if (startError.code === 'CHALLENGE_ALREADY_ACTIVE') return copy.errorAlreadyActive;
      if (startError.code === 'INSTAGRAM_NOT_CONNECTED') return copy.errorNotConnected;
      return startError.message || copy.errorGeneric;
    }
    return copy.errorGeneric;
  })();

  // -------------------------------------------------------------------------
  // No active challenge — either never started, or the last one finished.
  // -------------------------------------------------------------------------
  if (!data.active) {
    const lastCompleted = data.lastCompleted;
    return (
      <Card className={className}>
        <CardHeader>
          <CardTitle className="text-base">
            {lastCompleted
              ? copy.completedHeadline(lastCompleted.daysDone, lastCompleted.daysPlanned)
              : copy.introHeading}
          </CardTitle>
          {!lastCompleted && <CardDescription>{copy.introBody}</CardDescription>}
        </CardHeader>
        <CardContent className="space-y-3">
          <Button
            className="h-11 w-full sm:w-auto"
            onClick={() => void start()}
            disabled={starting}
          >
            {starting ? (
              <>
                <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
                {copy.startingCta}
              </>
            ) : lastCompleted ? (
              copy.startNextCta
            ) : (
              copy.startCta
            )}
          </Button>
          {startErrorMessage && (
            <p className="text-sm text-muted-foreground" role="alert">
              {startErrorMessage}
            </p>
          )}
        </CardContent>
      </Card>
    );
  }

  // -------------------------------------------------------------------------
  // Active challenge — comparison row, today's task, the 7-day strip, streak.
  // -------------------------------------------------------------------------
  const { active, comparison } = data;
  const today = active.days.find((d) => d.status === 'TODAY') ?? null;
  const isRestToday = today?.plannedType === 'REST';
  const typeLabel = today ? copy.plannedTypeLabel[today.plannedType] : '';
  const daypart = today?.window ? copy.daypartPhrase(today.window.label) : '';
  const timeRange = today?.window ? formatWindow(today.window) : '';
  const prompts = today ? buildPrompts(typeLabel, daypart, timeRange, hi) : null;

  return (
    <Card className={className}>
      <CardHeader className="flex flex-row items-center justify-between space-y-0">
        <div>
          <CardTitle className="text-base">{copy.challengeHeading(active.dayNumber)}</CardTitle>
        </div>
        <div className="flex items-center gap-1.5 text-sm font-medium text-primary">
          <Flame className="h-4 w-4" aria-hidden="true" />
          {copy.streakLabel(active.streak)}
        </div>
      </CardHeader>
      <CardContent className="space-y-4">
        <ChallengeComparisonRow comparison={comparison} copy={copy} />

        {/* Round 2 QA item 6 — a brand-primary outline so this box stands out from the plain
            muted comparison boxes above, per the agreed mockup. */}
        <div className="rounded-lg border-2 border-primary p-3">
          <p className="text-xs font-medium text-muted-foreground">{copy.todayHeading}</p>
          {today && isRestToday ? (
            <>
              <p className="mt-1 text-sm font-medium">{copy.restDayTitle}</p>
              <p className="text-sm text-muted-foreground">{copy.restDayBody}</p>
            </>
          ) : today ? (
            <>
              <div className="mt-1 flex flex-wrap items-center gap-2">
                <p className="text-sm font-medium">
                  {typeLabel}
                  {daypart ? ` · ${daypart}` : ''}
                  {timeRange ? `, ${timeRange}` : ''}
                </p>
                {today.windowSource === 'suggestion' && (
                  <Badge variant="secondary" className="text-[10px] font-normal">
                    {copy.suggestedBadge}
                  </Badge>
                )}
              </div>
              <div className="mt-3 flex flex-wrap gap-2">
                {/* Round 2 QA item 5 — "Write the script" is the main action: solid brand-purple
                    primary, AA-contrast white text (the default Button variant). "Give me an
                    idea" stays the secondary/outline action. */}
                <Button size="sm" className="h-11" onClick={() => prompts && onAskMeera(prompts.script)}>
                  {copy.writeScript}
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  className="h-11"
                  onClick={() => prompts && onAskMeera(prompts.idea)}
                >
                  {copy.giveIdea}
                </Button>
              </div>
            </>
          ) : (
            <p className="mt-1 text-sm text-muted-foreground">{copy.errorGeneric}</p>
          )}
        </div>

        <ChallengeDayStrip days={active.days} copy={copy} />

        {/* Round 2 QA item 1 — a small, quiet text control, not a big red button: ending early
            is a legitimate choice, not a failure to warn someone away from. */}
        <div className="flex justify-end">
          <Button
            size="sm"
            variant="ghost"
            className="h-11 text-xs text-muted-foreground"
            onClick={() => setShowEndConfirm(true)}
          >
            {copy.endChallenge}
          </Button>
        </div>
      </CardContent>

      <AlertDialog open={showEndConfirm} onOpenChange={setShowEndConfirm}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{copy.endConfirmTitle}</AlertDialogTitle>
            <AlertDialogDescription>{copy.endConfirmBody}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            {/* Radix AlertDialog focuses Cancel by default — "Keep going" as the safe default,
                per Round 2 QA item 1. */}
            <AlertDialogCancel disabled={ending}>{copy.keepGoingCta}</AlertDialogCancel>
            <AlertDialogAction
              disabled={ending}
              onClick={(e) => {
                e.preventDefault();
                void end(active.id).then(() => setShowEndConfirm(false));
              }}
            >
              {ending ? (
                <>
                  <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
                  {copy.endingChallenge}
                </>
              ) : (
                copy.endItCta
              )}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </Card>
  );
}

export default ChallengeCard;
