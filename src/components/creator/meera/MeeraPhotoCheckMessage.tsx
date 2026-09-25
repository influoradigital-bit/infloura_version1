import { Camera, RotateCcw } from 'lucide-react';

import { Button } from '@/components/ui/button';
import {
  COACH_COPY,
  CoachResult,
  FrameCheckList,
  MAX_COACH_ANSWERS,
  hasCoachShape,
  needsRetake,
  replyLangFor,
  shootCheckLangFor,
  type CoachAnswer,
} from '@/components/creator/shoot-check/CoachResult';
import type { MeeraShootCheckAsk, MeeraShootCheckFrameResult } from '@/lib/meera-api';
import { cn } from '@/lib/utils';

/**
 * One photo check, shown as a Meera message in the chat: a "Photo check · <shot>" header, the
 * coach card (CoachResult, the same card the Shoot Check page showed), and a "Check again" footer
 * that reopens the camera on the same shot.
 *
 * The chat keeps only the NEWEST photo in memory, so only the newest card can answer its coach
 * question by re-checking that photo (`interactive`). Every older card, and every card rebuilt from
 * history after a reload, shows its question with the answer buttons disabled, and its footer
 * becomes "Take a new photo" (same `onCheckAgain`: it opens the camera).
 *
 * `busy` is true while any check is running: nothing here can start a second one.
 */
export interface MeeraPhotoCheckMessageProps {
  result: MeeraShootCheckFrameResult;
  shotLabel?: string;
  /** Answers already given for THIS photo (the chat's photo-check session), shown as "You told me". */
  answers: CoachAnswer[];
  /** The chat's language (BCP-47, e.g. `en-IN` / `hi-IN`). The reply's own `lang` still wins for
   * every word around the server's text (`replyLangFor`). */
  lang: string;
  interactive: boolean;
  busy: boolean;
  onAnswer: (ask: MeeraShootCheckAsk, option: number) => void;
  onCheckAgain: () => void;
  className?: string;
}

const COPY = {
  en: {
    header: 'Photo check',
    takeNewPhoto: 'Take a new photo',
    olderPhoto: 'Answers work on your newest photo only.',
    fix: 'Fix',
    settings: 'Settings',
  },
  hi: {
    header: 'Photo check',
    takeNewPhoto: 'Nayi photo lo',
    olderPhoto: 'Jawab sirf aapki sabse nayi photo par chalte hain.',
    fix: 'Fix',
    settings: 'Settings',
  },
} as const;

export function MeeraPhotoCheckMessage({
  result,
  shotLabel,
  answers,
  lang,
  interactive,
  busy,
  onAnswer,
  onCheckAgain,
  className,
}: MeeraPhotoCheckMessageProps) {
  const shootLang = shootCheckLangFor(lang);
  const replyLang = replyLangFor(result, shootLang);
  const t = COPY[replyLang];
  const coach = COACH_COPY[replyLang];
  const coachShape = hasCoachShape(result);
  const retake = coachShape && needsRetake(result);
  const label = shotLabel?.trim();

  // Same rule CoachResult uses to show the question: one not yet answered, and fewer than 3 answers.
  const askShown =
    coachShape &&
    !retake &&
    Boolean(result.ask) &&
    answers.length < MAX_COACH_ANSWERS &&
    !answers.some((a) => a.ask.id === result.ask?.id);
  const askLocked = askShown && !interactive;

  return (
    <div
      data-testid="photo-check-message"
      className={cn('w-full space-y-3 rounded-xl border border-border bg-card p-3 sm:max-w-[85%]', className)}
    >
      <p
        data-testid="photo-check-header"
        className="flex items-start gap-1.5 text-xs font-semibold text-muted-foreground"
      >
        <Camera className="mt-px size-3.5 shrink-0" aria-hidden="true" />
        <span className="min-w-0 break-words">
          {t.header}
          {label ? ` · ${label}` : ''}
        </span>
      </p>

      {coachShape ? (
        <CoachResult
          result={result}
          answers={answers}
          lang={shootLang}
          canAnswer={interactive && !busy}
          onAnswer={onAnswer}
          canCheckAgain={!busy}
          onCheckAgain={onCheckAgain}
        />
      ) : (
        <div className="flex flex-col gap-3">
          <FrameCheckList title={t.fix} items={result.fixes ?? []} tone="fix" />
          <FrameCheckList title={t.settings} items={result.settings ?? []} tone="setting" />
          <FrameCheckList title={coach.lookingGood} items={result.ok ?? []} tone="ok" />
        </div>
      )}

      {/* A retake box carries its own "Check again" button; a second one here would be a duplicate. */}
      {retake ? null : (
        <div className="flex flex-col gap-1.5 border-t border-border pt-3">
          {askLocked ? (
            <p data-testid="photo-check-older-photo" className="text-xs text-muted-foreground">
              {t.olderPhoto}
            </p>
          ) : null}
          <Button
            type="button"
            variant="outline"
            className="min-h-11 self-start"
            disabled={busy}
            onClick={onCheckAgain}
            data-testid="photo-check-again"
          >
            <RotateCcw className="size-4" aria-hidden="true" />
            {askLocked ? t.takeNewPhoto : coach.checkAgain}
          </Button>
        </div>
      )}
    </div>
  );
}
