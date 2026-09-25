import * as React from 'react';
import {
  BookOpen,
  Camera,
  Check,
  HelpCircle,
  Lightbulb,
  SlidersHorizontal,
  Smartphone,
  User,
  type LucideIcon,
} from 'lucide-react';

import { Button } from '@/components/ui/button';
import type {
  MeeraShootCheckAsk,
  MeeraShootCheckFrameResult,
  MeeraShootCheckStep,
  MeeraShootCheckStepKind,
  MeeraShootCheckStepPart,
} from '@/lib/meera-api';
import type { ShootCheckLang } from '@/lib/shoot-check/advice-copy';

/**
 * The coach card for one photo check, moved here unchanged from ShootCheckPanel.tsx so the same
 * card renders on the (retired) Shoot Check page and as a Meera message in the chat
 * (MeeraPhotoCheckMessage). Markup, classes, copy and test ids are exactly what the panel had.
 */

/** One coach question the creator answered by tapping, kept with the question itself so the card
 * can show "You said" in the creator's language. */
export interface CoachAnswer {
  ask: MeeraShootCheckAsk;
  option: number;
}

/** The server asks at most 3 coach questions per plan; the client sends at most 3 answers. */
export const MAX_COACH_ANSWERS = 3;

/** A result carrying any of the coach fields renders in the coach layout; one from an older
 * server (only the three legacy lists) keeps the old Fix/Settings/Looking good groups. */
export function hasCoachShape(result: MeeraShootCheckFrameResult): boolean {
  return Boolean(
    result.whatISee ||
      (result.steps?.length ?? 0) > 0 ||
      (result.cantTell?.length ?? 0) > 0 ||
      result.ask ||
      result.retake === true
  );
}

/** Whether the photo has to be taken again. A server that sends `retake` is believed; one that
 * does not (an older server) gives an unusable photo only the one `whatISee` line, so that shape
 * counts as a retake too. */
export function needsRetake(result: MeeraShootCheckFrameResult): boolean {
  if (typeof result.retake === 'boolean') return result.retake;
  return Boolean(
    result.whatISee &&
      (result.steps?.length ?? 0) === 0 &&
      (result.ok?.length ?? 0) === 0 &&
      (result.cantTell?.length ?? 0) === 0 &&
      !result.ask
  );
}

export type ReplyLang = 'en' | 'hi';

/** Every word the coach layout adds around the server's text, in both reply languages. `hi` is
 * Hinglish in Latin script, the same register the server writes a Hindi reply in. */
export const COACH_COPY: Record<
  ReplyLang,
  {
    whatISee: string;
    steps: string;
    fromGuide: string;
    proMode: string;
    oisMode: string;
    lookingGood: string;
    cantTell: string;
    youToldMe: string;
    checkAgain: string;
    retakeFallback: string;
    kinds: Record<MeeraShootCheckStepKind, string>;
  }
> = {
  en: {
    whatISee: "Here's what I see",
    steps: 'Try this, in order',
    fromGuide: 'From the guide:',
    proMode: 'If your camera app has a Pro video mode',
    oisMode: 'If your phone has optical stabilisation (OIS)',
    lookingGood: 'Looking good',
    cantTell: "A photo can't show",
    youToldMe: 'You told me',
    checkAgain: 'Check again',
    retakeFallback: "I couldn't read this photo clearly. Please take it again.",
    kinds: { move_you: 'You', move_phone: 'Phone', move_light: 'Light', settings: 'Settings' },
  },
  hi: {
    whatISee: 'Mujhe yeh dikh raha hai',
    steps: 'Yeh karke dekho, is order mein',
    fromGuide: 'Guide se:',
    proMode: 'Agar aapke camera app mein Pro video mode hai',
    oisMode: 'Agar aapke phone mein OIS (optical stabilisation) hai',
    lookingGood: 'Yeh sahi hai',
    cantTell: 'Photo se pata nahi chalta',
    youToldMe: 'Aapne bataya',
    checkAgain: 'Dobara check karo',
    retakeFallback: 'Yeh photo saaf nahi dikh rahi. Ek baar phir se lo.',
    kinds: { move_you: 'Aap', move_phone: 'Phone', move_light: 'Light', settings: 'Settings' },
  },
};

/** What each step is about, as an icon beside its kind chip. */
const STEP_KIND_ICONS: Record<MeeraShootCheckStepKind, LucideIcon> = {
  move_you: User,
  move_phone: Smartphone,
  move_light: Lightbulb,
  settings: SlidersHorizontal,
};

/** The reply's own language when the server says it (a Hinglish reply gets Hinglish headings
 * even for a creator whose setting is English), else the creator's language setting. */
export function replyLangFor(result: MeeraShootCheckFrameResult, lang: ShootCheckLang): ReplyLang {
  if (result.lang === 'hi' || result.lang === 'en') return result.lang;
  return lang === 'hi-IN' ? 'hi' : 'en';
}

/** The chat's BCP-47 language (`hi-IN`, `hi`, `en-IN`, ...) as the two languages the photo check
 * speaks. Anything that is not Hindi is English, the creator surface's default. */
export function shootCheckLangFor(language: string | null | undefined): ShootCheckLang {
  return (language ?? '').toLowerCase().startsWith('hi') ? 'hi-IN' : 'en-IN';
}

/**
 * The coach layout for a check, read like a coach talking the creator through it: what the photo
 * shows, the steps in order (each with what it is about, and the guide topic it comes from), what
 * is already working, what one photo cannot show, and — when one missing fact would change the
 * steps — one question with tap-to-answer options. Tapping re-checks the same photo with the
 * answer added (`onAnswer`). A photo that could not be judged shows only a retake prompt, whose
 * button runs the same check as "Check my frame" (`onCheckAgain`). Every word around the server's
 * text is in the reply's language (`replyLangFor`).
 */
export function CoachResult({
  result,
  answers,
  lang,
  canAnswer,
  onAnswer,
  canCheckAgain,
  onCheckAgain,
}: {
  result: MeeraShootCheckFrameResult;
  answers: CoachAnswer[];
  lang: ShootCheckLang;
  canAnswer: boolean;
  onAnswer: (ask: MeeraShootCheckAsk, option: number) => void;
  canCheckAgain: boolean;
  onCheckAgain: () => void;
}) {
  const replyLang = replyLangFor(result, lang);
  const hindi = replyLang === 'hi';
  const copy = COACH_COPY[replyLang];

  if (needsRetake(result)) {
    return (
      <div
        className="flex flex-col gap-3 rounded-lg bg-warning p-3 text-warning-foreground"
        data-testid="frame-check-retake"
      >
        <div className="flex items-start gap-2">
          <Camera className="mt-0.5 size-5 shrink-0" aria-hidden="true" />
          <p className="min-w-0 break-words text-sm font-medium sm:text-base" data-testid="frame-check-what-i-see">
            {result.whatISee ?? copy.retakeFallback}
          </p>
        </div>
        <Button
          variant="outline"
          className="min-h-11 self-start"
          disabled={!canCheckAgain}
          onClick={onCheckAgain}
          data-result-focus=""
        >
          {copy.checkAgain}
        </Button>
      </div>
    );
  }

  const steps: MeeraShootCheckStep[] = result.steps ?? [];
  const ask = result.ask;
  // An ask is shown only while it can still be answered: not one already answered (the server
  // should not repeat it, but a repeat must not loop), and not once 3 answers have been given.
  const showAsk =
    Boolean(ask) && answers.length < MAX_COACH_ANSWERS && !answers.some((a) => a.ask.id === ask?.id);

  return (
    <div className="flex flex-col gap-4" data-testid="frame-check-coach">
      {result.whatISee ? (
        <div>
          <h3 className="text-sm font-semibold text-foreground" tabIndex={-1}>
            {copy.whatISee}
          </h3>
          <p className="mt-1 break-words text-sm text-foreground sm:text-base" data-testid="frame-check-what-i-see">
            {result.whatISee}
          </p>
        </div>
      ) : null}

      {steps.length > 0 ? (
        <div>
          <h3 className="text-sm font-semibold text-foreground" tabIndex={-1}>
            {copy.steps}
          </h3>
          <ol className="mt-2 flex flex-col gap-3" data-testid="frame-check-steps">
            {steps.map((step, i) => {
              const KindIcon = STEP_KIND_ICONS[step.kind] ?? Camera;
              const label = step.label || step.note;
              return (
                <li key={i} className="flex items-start gap-2 text-sm" data-testid="frame-check-step">
                  <span
                    className="mt-0.5 flex size-6 shrink-0 items-center justify-center rounded-full bg-muted text-xs font-semibold text-foreground"
                    aria-hidden="true"
                  >
                    {i + 1}
                  </span>
                  <div className="min-w-0 flex-1">
                    <span
                      className="inline-flex items-center gap-1 rounded-full bg-muted px-2 py-0.5 text-xs font-medium text-foreground"
                      data-testid="frame-check-step-kind"
                    >
                      <KindIcon className="size-3.5" aria-hidden="true" />
                      {copy.kinds[step.kind] ?? ''}
                    </span>
                    {step.parts && step.parts.length > 0 ? (
                      <StepParts parts={step.parts} proCaption={copy.proMode} oisCaption={copy.oisMode} />
                    ) : (
                      <p className="mt-1 break-words text-foreground">{step.text}</p>
                    )}
                    {label ? (
                      <p
                        className="mt-1 flex items-start gap-1 text-xs text-muted-foreground"
                        data-testid="frame-check-step-source"
                      >
                        <BookOpen className="mt-0.5 size-3 shrink-0" aria-hidden="true" />
                        <span className="min-w-0 break-words">
                          {copy.fromGuide} {label}
                        </span>
                      </p>
                    ) : null}
                  </div>
                </li>
              );
            })}
          </ol>
        </div>
      ) : null}

      <FrameCheckList title={copy.lookingGood} items={result.ok ?? []} tone="ok" testId="frame-check-ok" />
      <FrameCheckList title={copy.cantTell} items={result.cantTell ?? []} tone="unknown" testId="frame-check-cant-tell" />

      {answers.length > 0 ? (
        <div data-testid="frame-check-answers">
          <p className="text-xs font-medium text-muted-foreground">{copy.youToldMe}</p>
          <ul className="mt-1 flex flex-col gap-0.5">
            {answers.map((a) => {
              const option = a.ask.options[a.option];
              return (
                <li key={a.ask.id} className="break-words text-xs text-muted-foreground">
                  {hindi ? a.ask.questionHi : a.ask.questionEn}{' '}
                  <span className="font-medium text-foreground">{hindi ? option?.hi : option?.en}</span>
                </li>
              );
            })}
          </ul>
        </div>
      ) : null}

      {showAsk && ask ? (
        <div className="rounded-lg border border-border bg-muted/40 p-3" data-testid="frame-check-ask">
          <p className="break-words text-sm font-medium text-foreground">{hindi ? ask.questionHi : ask.questionEn}</p>
          <div className="mt-2 flex flex-wrap gap-2">
            {ask.options.map((option, i) => (
              <Button
                key={i}
                variant="outline"
                size="sm"
                className="h-auto min-h-11 whitespace-normal text-left"
                disabled={!canAnswer}
                onClick={() => onAnswer(ask, i)}
              >
                {hindi ? option.hi : option.en}
              </Button>
            ))}
          </div>
        </div>
      ) : null}
    </div>
  );
}

/** A settings step as a label/value list instead of one dense line; the parts only a Pro video
 * mode can set, and those that hold only on a phone with OIS, go under their own captions (never
 * in the plain list as fact). */
function StepParts({
  parts,
  proCaption,
  oisCaption,
}: {
  parts: MeeraShootCheckStepPart[];
  proCaption: string;
  oisCaption: string;
}) {
  const basic = parts.filter((p) => !p.needsPro && p.needsOis !== true);
  const pro = parts.filter((p) => p.needsPro);
  const ois = parts.filter((p) => !p.needsPro && p.needsOis === true);
  return (
    <div className="mt-1" data-testid="frame-check-step-parts">
      {basic.length > 0 ? <PartList parts={basic} /> : null}
      {pro.length > 0 ? (
        <div className="mt-2" data-testid="frame-check-step-pro-parts">
          <p className="text-xs font-medium text-muted-foreground">{proCaption}</p>
          <PartList parts={pro} />
        </div>
      ) : null}
      {ois.length > 0 ? (
        <div className="mt-2" data-testid="frame-check-step-ois-parts">
          <p className="text-xs font-medium text-muted-foreground">{oisCaption}</p>
          <PartList parts={ois} />
        </div>
      ) : null}
    </div>
  );
}

function PartList({ parts }: { parts: MeeraShootCheckStepPart[] }) {
  return (
    <dl className="mt-1 grid grid-cols-[minmax(0,auto)_minmax(0,1fr)] gap-x-3 gap-y-1">
      {parts.map((part, i) => (
        <React.Fragment key={i}>
          <dt className="break-words text-muted-foreground">{part.label}</dt>
          <dd className="min-w-0 break-words font-medium text-foreground">{part.value}</dd>
        </React.Fragment>
      ))}
    </dl>
  );
}

type FrameCheckTone = 'ok' | 'unknown' | 'fix' | 'setting';

/** One marker per tone. Colours follow globals.css: semantic colours are pale tints, so an icon
 * takes the strong `text-*-foreground`, never a bare `text-success`/`bg-success` dot. */
function ToneMarker({ tone }: { tone: FrameCheckTone }) {
  if (tone === 'ok') return <Check className="mt-0.5 size-4 shrink-0 text-success-foreground" aria-hidden="true" />;
  if (tone === 'unknown') return <HelpCircle className="mt-0.5 size-4 shrink-0 text-muted-foreground" aria-hidden="true" />;
  if (tone === 'setting')
    return <SlidersHorizontal className="mt-0.5 size-4 shrink-0 text-muted-foreground" aria-hidden="true" />;
  return <span className="mt-1.5 size-2 shrink-0 rounded-full bg-warning-foreground" aria-hidden="true" />;
}

/** One titled list of plain lines (the coach's "Looking good" / "A photo can't show", and an older
 * server's Fix/Settings/Looking good groups). Renders nothing for an empty list. */
export function FrameCheckList({
  title,
  items,
  tone,
  testId,
}: {
  title: string;
  items: string[];
  tone: FrameCheckTone;
  testId?: string;
}) {
  if (items.length === 0) return null;
  return (
    <div data-testid={testId}>
      <h3 className="text-sm font-semibold text-foreground" tabIndex={-1}>
        {title}
      </h3>
      <ul className="mt-1 flex flex-col gap-1">
        {items.map((item, i) => (
          <li key={i} className="flex items-start gap-2 text-sm text-foreground">
            <ToneMarker tone={tone} />
            <span className="min-w-0 break-words">{item}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
