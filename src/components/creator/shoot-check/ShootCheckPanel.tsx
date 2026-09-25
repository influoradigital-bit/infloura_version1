import * as React from 'react';
import {
  BookOpen,
  Camera,
  Check,
  ChevronLeft,
  ChevronRight,
  Focus,
  HelpCircle,
  Image as ImageIcon,
  Lightbulb,
  Mic,
  RotateCw,
  SlidersHorizontal,
  Smartphone,
  Sun,
  User,
  Volume2,
  VolumeX,
  type LucideIcon,
} from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Switch } from '@/components/ui/switch';
import {
  meeraApi,
  type MeeraShootCheckAsk,
  type MeeraShootCheckFrameResult,
  type MeeraShootCheckStep,
  type MeeraShootCheckStepKind,
  type MeeraShootCheckStepPart,
  type MeeraShotContext,
} from '@/lib/meera-api';
import { FRAME_CHECK_DISCLOSURE, type ShootCheckLang } from '@/lib/shoot-check/advice-copy';
import type { ShotTarget } from '@/lib/shoot-check/metrics';
import { captureDownscaledJpeg } from '@/lib/shoot-check/capture-frame';
import { useShootCheck } from '@/hooks/useShootCheck';
import { FramingGuide } from './FramingGuide';
import { ReadingRow, type ReadingSeverity } from './ReadingRow';

export interface ShootCheckShot {
  /** The script's own number for this beat, for the caller's reference only. The header
   * numbers shots by POSITION in the array — trusting this field made a 1-based script read
   * "Shot 2 of 3" on its first shot. */
  index: number;
  label: string;
  seconds: number;
  target: ShotTarget;
  /** The planned set-up for this beat (angle, action, prop, where, light, ...), sent to "Check my
   * frame" as `shot_context` together with `label` (as `line`) so the check knows what the creator
   * is trying to film. Optional: a shot with only a label still sends that. */
  context?: MeeraShotContext;
}

/** One coach question the creator answered by tapping, kept with the question itself so the panel
 * can show "You said" in the creator's language. */
interface CoachAnswer {
  ask: MeeraShootCheckAsk;
  option: number;
}

/** The server asks at most 3 coach questions per plan; the panel sends at most 3 answers. */
const MAX_COACH_ANSWERS = 3;

function shotContextFor(shot: ShootCheckShot): MeeraShotContext | undefined {
  const context: MeeraShotContext = { ...(shot.context ?? {}) };
  if (shot.label.trim() && !context.line?.trim()) context.line = shot.label.trim();
  return Object.values(context).some((v) => typeof v === 'string' && v.trim()) ? context : undefined;
}

/** A result carrying any of the coach fields renders in the coach layout; one from an older
 * server (only the three legacy lists) keeps the old Fix/Settings/Looking good groups. */
function hasCoachShape(result: MeeraShootCheckFrameResult): boolean {
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
function needsRetake(result: MeeraShootCheckFrameResult): boolean {
  if (typeof result.retake === 'boolean') return result.retake;
  return Boolean(
    result.whatISee &&
      (result.steps?.length ?? 0) === 0 &&
      (result.ok?.length ?? 0) === 0 &&
      (result.cantTell?.length ?? 0) === 0 &&
      !result.ask
  );
}

const FALLBACK_SHOT: ShootCheckShot = { index: 0, label: '', seconds: 0, target: 'medium' };

/**
 * Generic, static advice shown whenever there is no live camera reading to describe — the
 * unsupported/denied/idle states, and any reading the hook hasn't produced yet. A creator who
 * declines the camera permission still sees real guidance text, never a blank panel.
 */
const GENERIC_TIPS: Array<{ label: string; fix: string }> = [
  { label: 'Light', fix: 'Face a window or add a light so your face isn’t in shadow.' },
  { label: 'Sharpness', fix: 'Hold the phone with both hands and keep it still.' },
  { label: 'Framing', fix: 'Leave a little space above your head, not too much.' },
  { label: 'Tilt', fix: 'Keep the phone upright and level.' },
  { label: 'Background', fix: 'Clear clutter from behind you, or step a little away from it.' },
  { label: 'Mic', fix: 'Move closer to the phone and away from noisy rooms.' },
];

function shotHeaderText(shot: ShootCheckShot, total: number, position: number): string {
  // Numbered by POSITION (0-based) in the script array, not by `shot.index`: a caller numbering
  // its own beats from 1 made the first shot render as "Shot 2 of 3".
  const base = `Shot ${position + 1} of ${total}`;
  const parts = [shot.label, shot.seconds ? `${shot.seconds}s` : null].filter((p): p is string => Boolean(p));
  return parts.length ? `${base} - ${parts.join(', ')}` : base;
}

function useOnlineStatus(): boolean {
  const [online, setOnline] = React.useState(() => (typeof navigator === 'undefined' ? true : navigator.onLine));
  React.useEffect(() => {
    const goOnline = () => setOnline(true);
    const goOffline = () => setOnline(false);
    window.addEventListener('online', goOnline);
    window.addEventListener('offline', goOffline);
    return () => {
      window.removeEventListener('online', goOnline);
      window.removeEventListener('offline', goOffline);
    };
  }, []);
  return online;
}

export interface ShootCheckPanelProps {
  /** Shot-by-shot script. With none, falls back to a single `medium` target. */
  shots?: ShootCheckShot[];
  /** Creator's language for on-screen fix text and spoken cues — the page reads this from
   * `api.creatorAgentPrefs.getPreferences().creator_language`, same source `creator-copilot.tsx`
   * uses, and passes it down; this component has no opinion on where it comes from. */
  lang?: ShootCheckLang;
}

export function ShootCheckPanel({ shots, lang = 'en-IN' }: ShootCheckPanelProps) {
  const script = shots && shots.length > 0 ? shots : [FALLBACK_SHOT];
  const [shotIndex, setShotIndex] = React.useState(0);
  const clampedIndex = Math.min(shotIndex, script.length - 1);
  const currentShot = script[clampedIndex];

  const shootCheck = useShootCheck({ target: currentShot.target, lang });
  const online = useOnlineStatus();

  const [frameCheck, setFrameCheck] = React.useState<{
    status: 'idle' | 'loading' | 'done' | 'error' | 'capped';
    result: MeeraShootCheckFrameResult | null;
    message: string | null;
  }>({ status: 'idle', result: null, message: null });

  /** Bumped whenever the shot changes and at the start of every check — `handleCheckFrame`
   * compares against this after each `await` and drops a result whose token no longer matches, so
   * a check for shot 1 that resolves after the creator has already moved to shot 2 can never
   * clobber the idle state `goToShot` just set with shot 1's stale fixes. */
  const frameCheckTokenRef = React.useRef(0);

  /** Set when a check was started from the retake box's "Check again": that button unmounts while
   * the check runs, so once it finishes focus is moved to the new result (its first heading, or a
   * retake box's own button) instead of being left on <body> for a keyboard or screen-reader user. */
  const focusResultAfterCheckRef = React.useRef(false);
  const resultRegionRef = React.useRef<HTMLDivElement>(null);

  /** The still the last check was run on, kept in memory only (never stored) so tapping an answer
   * to a coach question re-checks the SAME photo with the answer added, instead of taking a new
   * one. Cleared on every shot change: a still belongs to the shot it was taken for. */
  const lastStillRef = React.useRef<Blob | null>(null);

  /** Answers the creator tapped, at most `MAX_COACH_ANSWERS`, the latest per question. They
   * describe the creator's space (another light, room size, ...), so they are kept across shots
   * for as long as the panel is open, and sent with every later check. */
  const [answers, setAnswers] = React.useState<CoachAnswer[]>([]);

  const goToShot = React.useCallback(
    (nextIndex: number) => {
      setShotIndex(Math.max(0, Math.min(script.length - 1, nextIndex)));
      frameCheckTokenRef.current += 1;
      focusResultAfterCheckRef.current = false;
      lastStillRef.current = null;
      setFrameCheck({ status: 'idle', result: null, message: null });
      shootCheck.noteInteraction();
    },
    [script.length, shootCheck]
  );

  const handleToggleMute = React.useCallback(
    (value: boolean) => {
      shootCheck.setMuted(value);
      shootCheck.noteInteraction();
    },
    [shootCheck]
  );

  const frameCheckDisabled = !online || frameCheck.status === 'loading' || shootCheck.phase !== 'active';

  /** Sends one still (plus the shot's context and the answers so far) and applies the outcome,
   * unless the shot changed or a newer check started while it was in flight (`token`). */
  const runCheck = React.useCallback(
    async (blob: Blob, token: number, withAnswers: CoachAnswer[]) => {
      const outcome = await meeraApi.checkFrame(blob, currentShot.label || undefined, 'creator', {
        shotContext: shotContextFor(currentShot),
        answers: withAnswers.map((a) => ({ id: a.ask.id, option: a.option })),
      });
      if (frameCheckTokenRef.current !== token) return; // shot changed while checking — drop the stale result

      if (outcome.kind === 'ok') {
        setFrameCheck({ status: 'done', result: outcome.result, message: null });
      } else if (outcome.kind === 'capped') {
        setFrameCheck({ status: 'capped', result: null, message: outcome.message });
      } else {
        setFrameCheck({ status: 'error', result: null, message: null });
      }
    },
    [currentShot]
  );

  const handleCheckFrame = React.useCallback(async (options?: { focusResult?: boolean }) => {
    if (frameCheckDisabled) return;
    const video = shootCheck.videoRef.current;
    if (!video) return;

    shootCheck.noteInteraction();
    focusResultAfterCheckRef.current = options?.focusResult === true;
    const token = ++frameCheckTokenRef.current;
    setFrameCheck({ status: 'loading', result: null, message: null });

    const blob = await captureDownscaledJpeg(video);
    if (frameCheckTokenRef.current !== token) return; // shot changed while capturing the frame
    if (!blob) {
      setFrameCheck({ status: 'error', result: null, message: null });
      return;
    }
    lastStillRef.current = blob;

    await runCheck(blob, token, answers);
  }, [frameCheckDisabled, shootCheck, runCheck, answers]);

  /** A tap on one option of the coach question: re-check the SAME still with this answer added. */
  const handleAnswer = React.useCallback(
    async (ask: MeeraShootCheckAsk, option: number) => {
      const blob = lastStillRef.current;
      if (!blob || !online || frameCheck.status === 'loading') return;

      shootCheck.noteInteraction();
      const nextAnswers = [...answers.filter((a) => a.ask.id !== ask.id), { ask, option }].slice(-MAX_COACH_ANSWERS);
      setAnswers(nextAnswers);
      focusResultAfterCheckRef.current = false;
      const token = ++frameCheckTokenRef.current;
      setFrameCheck({ status: 'loading', result: null, message: null });

      await runCheck(blob, token, nextAnswers);
    },
    [answers, online, frameCheck.status, shootCheck, runCheck]
  );

  React.useEffect(() => {
    if (frameCheck.status === 'loading' || !focusResultAfterCheckRef.current) return;
    focusResultAfterCheckRef.current = false;
    const region = resultRegionRef.current;
    // The result's first heading (focusable via tabIndex=-1) or a retake box's own button; with
    // neither (an error line), back to "Check my frame".
    const target =
      region?.querySelector<HTMLElement>('h3, [data-result-focus]') ??
      region?.parentElement?.querySelector<HTMLElement>('[data-frame-check-button]');
    target?.focus();
  }, [frameCheck.status]);

  const readings = shootCheck.readings;
  const rows = readings
    ? [
        {
          icon: <Sun className="size-4" />,
          label: 'Light',
          value: `${Math.round(readings.light.luma)}`,
          severity: lightSeverity(readings.light.status),
          fix: readings.light.text,
        },
        {
          icon: <Focus className="size-4" />,
          label: 'Sharpness',
          value: `${Math.round(readings.focus.sharpness)}`,
          severity: focusSeverity(readings.focus.status),
          fix: readings.focus.text,
        },
        {
          icon: <Camera className="size-4" />,
          label: 'Framing',
          value: framingValueLabel(readings.framing.status),
          severity: framingSeverity(readings.framing.status),
          fix: readings.framing.text,
        },
        {
          icon: <RotateCw className="size-4" />,
          label: 'Tilt',
          value: readings.tilt.degrees === 'unknown' ? 'unknown' : `${Math.round(readings.tilt.degrees)}°`,
          severity: tiltSeverity(readings.tilt.status),
          fix: readings.tilt.text,
        },
        {
          icon: <ImageIcon className="size-4" />,
          label: 'Background',
          value: `${Math.round(readings.background.clutter)}`,
          severity: readings.background.status === 'busy' ? ('warn' as const) : ('ok' as const),
          fix: readings.background.text,
        },
        {
          icon: <Mic className="size-4" />,
          label: 'Mic',
          value:
            readings.mic.levelDb === 'unknown' || readings.mic.noiseFloorDb === 'unknown'
              ? '—'
              : `${Math.round(readings.mic.levelDb - readings.mic.noiseFloorDb)} dB gap`,
          severity: micSeverity(readings.mic.status),
          fix: readings.mic.text,
        },
      ]
    : GENERIC_TIPS.map((tip) => ({
        icon: <Camera className="size-4" />,
        label: tip.label,
        value: '—',
        severity: 'unknown' as ReadingSeverity,
        fix: tip.fix,
      }));

  return (
    <div className="flex flex-col gap-4">
      <Card>
        <CardHeader className="flex-row items-center justify-between gap-2">
          <CardTitle className="text-base">{shotHeaderText(currentShot, script.length, clampedIndex)}</CardTitle>
          <div className="flex items-center gap-1">
            <Button
              variant="outline"
              size="icon-sm"
              aria-label="Previous shot"
              disabled={clampedIndex === 0}
              onClick={() => goToShot(clampedIndex - 1)}
            >
              <ChevronLeft className="size-4" />
            </Button>
            <Button
              variant="outline"
              size="icon-sm"
              aria-label="Next shot"
              disabled={clampedIndex === script.length - 1}
              onClick={() => goToShot(clampedIndex + 1)}
            >
              <ChevronRight className="size-4" />
            </Button>
          </div>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <div className="relative aspect-video w-full overflow-hidden rounded-lg bg-muted">
            {shootCheck.supported ? (
              <video ref={shootCheck.videoRef} className="size-full object-cover" muted playsInline autoPlay />
            ) : null}
            {shootCheck.phase === 'active' ? <FramingGuide /> : null}

            {shootCheck.phase !== 'active' ? (
              <div className="absolute inset-0 flex flex-col items-center justify-center gap-3 p-4 text-center">
                <p className="text-sm text-muted-foreground">
                  {shootCheck.phase === 'unsupported'
                    ? 'Shoot Check needs camera access, which this browser or connection doesn’t support here.'
                    : shootCheck.phase === 'denied'
                      ? (shootCheck.errorMessage ?? 'Camera access was denied.')
                      : shootCheck.phase === 'starting'
                        ? 'Starting camera…'
                        : 'Start the camera to get live coaching for this shot.'}
                </p>
                {shootCheck.supported && shootCheck.phase !== 'starting' ? (
                  <Button onClick={() => shootCheck.start()}>Start camera</Button>
                ) : null}
              </div>
            ) : null}
          </div>

          <div className="flex items-center justify-between gap-2 rounded-lg border border-border bg-card px-3 py-2">
            <div className="flex items-center gap-2">
              {shootCheck.muted ? (
                <VolumeX className="size-4 text-muted-foreground" aria-hidden="true" />
              ) : (
                <Volume2 className="size-4 text-muted-foreground" aria-hidden="true" />
              )}
              <span className="text-sm text-foreground">Spoken cues</span>
            </div>
            <Switch
              checked={!shootCheck.muted}
              onCheckedChange={(checked) => handleToggleMute(!checked)}
              aria-label="Toggle spoken cues"
            />
          </div>

          <p className="text-xs text-muted-foreground">Nothing from this preview is uploaded or saved.</p>

          <div className="flex flex-col gap-2">
            {rows.map((row) => (
              <ReadingRow key={row.label} icon={row.icon} label={row.label} value={row.value} severity={row.severity} fix={row.fix} />
            ))}
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">Check my frame</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          <p className="text-sm text-muted-foreground">
            A deeper, one-time check on a single still photo — takes a few seconds.
          </p>
          <p className="text-xs text-muted-foreground" data-testid="frame-check-disclosure">
            {FRAME_CHECK_DISCLOSURE[lang]}
          </p>
          <Button
            onClick={() => void handleCheckFrame()}
            disabled={frameCheckDisabled}
            className="self-start"
            data-frame-check-button=""
          >
            {frameCheck.status === 'loading' ? 'Checking…' : 'Check my frame'}
          </Button>
          {!online ? <p className="text-sm text-muted-foreground">You’re offline — connect to run this check.</p> : null}

          {/* Always mounted, so a new result (or error) is announced when it replaces the old one;
              while empty it cancels its own flex gap. */}
          <div ref={resultRegionRef} aria-live="polite" className="flex flex-col gap-3 empty:-mt-3" data-testid="frame-check-result">
            {frameCheck.status === 'error' ? (
              <p className="text-sm text-muted-foreground">Couldn’t check your frame right now. Try again in a moment.</p>
            ) : null}

            {frameCheck.status === 'capped' && frameCheck.message ? (
              <p className="text-sm text-muted-foreground">{frameCheck.message}</p>
            ) : null}

            {frameCheck.status === 'done' && frameCheck.result ? (
              hasCoachShape(frameCheck.result) ? (
                <CoachResult
                  result={frameCheck.result}
                  answers={answers}
                  lang={lang}
                  canAnswer={online}
                  onAnswer={(ask, option) => void handleAnswer(ask, option)}
                  canCheckAgain={!frameCheckDisabled}
                  onCheckAgain={() => void handleCheckFrame({ focusResult: true })}
                />
              ) : (
                <div className="flex flex-col gap-3">
                  <FrameCheckList title="Fix" items={frameCheck.result.fixes} tone="fix" />
                  <FrameCheckList title="Settings" items={frameCheck.result.settings} tone="setting" />
                  <FrameCheckList title="Looking good" items={frameCheck.result.ok} tone="ok" />
                </div>
              )
            ) : null}
          </div>
        </CardContent>
      </Card>
    </div>
  );
}

type ReplyLang = 'en' | 'hi';

/** Every word the coach layout adds around the server's text, in both reply languages. `hi` is
 * Hinglish in Latin script, the same register the server writes a Hindi reply in. */
const COACH_COPY: Record<
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
function replyLangFor(result: MeeraShootCheckFrameResult, lang: ShootCheckLang): ReplyLang {
  if (result.lang === 'hi' || result.lang === 'en') return result.lang;
  return lang === 'hi-IN' ? 'hi' : 'en';
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
function CoachResult({
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

function FrameCheckList({
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

function lightSeverity(status: 'dark' | 'dim' | 'ok' | 'bright'): ReadingSeverity {
  if (status === 'dark') return 'bad';
  if (status === 'dim' || status === 'bright') return 'warn';
  return 'ok';
}

function focusSeverity(status: 'unknown' | 'blurry' | 'ok'): ReadingSeverity {
  if (status === 'unknown') return 'unknown';
  if (status === 'blurry') return 'bad';
  return 'ok';
}

function framingSeverity(status: 'ok' | 'adjust' | 'unknown'): ReadingSeverity {
  if (status === 'unknown') return 'unknown';
  if (status === 'adjust') return 'warn';
  return 'ok';
}

function framingValueLabel(status: 'ok' | 'adjust' | 'unknown'): string {
  if (status === 'unknown') return 'Unknown';
  if (status === 'adjust') return 'Adjust';
  return 'OK';
}

function tiltSeverity(status: 'ok' | 'warn' | 'bad' | 'unknown'): ReadingSeverity {
  return status;
}

function micSeverity(status: 'poor-separation' | 'weak-separation' | 'ok' | 'unknown'): ReadingSeverity {
  // 'unknown' is not a fault: with no audio track there is nothing measured to be good or bad.
  if (status === 'unknown') return 'unknown';
  if (status === 'poor-separation') return 'bad';
  if (status === 'weak-separation') return 'warn';
  return 'ok';
}
