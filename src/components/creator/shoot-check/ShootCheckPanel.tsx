import * as React from 'react';
import { Camera, ChevronLeft, ChevronRight, Focus, Image as ImageIcon, Mic, RotateCw, Sun, Volume2, VolumeX } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Switch } from '@/components/ui/switch';
import { cn } from '@/lib/utils';
import {
  meeraApi,
  type MeeraShootCheckAsk,
  type MeeraShootCheckFrameResult,
  type MeeraShootCheckStep,
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
    result.whatISee || (result.steps?.length ?? 0) > 0 || (result.cantTell?.length ?? 0) > 0 || result.ask
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

  const handleCheckFrame = React.useCallback(async () => {
    if (frameCheckDisabled) return;
    const video = shootCheck.videoRef.current;
    if (!video) return;

    shootCheck.noteInteraction();
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
      const token = ++frameCheckTokenRef.current;
      setFrameCheck({ status: 'loading', result: null, message: null });

      await runCheck(blob, token, nextAnswers);
    },
    [answers, online, frameCheck.status, shootCheck, runCheck]
  );

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
          <Button onClick={() => void handleCheckFrame()} disabled={frameCheckDisabled} className="self-start">
            {frameCheck.status === 'loading' ? 'Checking…' : 'Check my frame'}
          </Button>
          {!online ? <p className="text-sm text-muted-foreground">You’re offline — connect to run this check.</p> : null}

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
              />
            ) : (
              <div className="flex flex-col gap-3">
                <FrameCheckList title="Fix" items={frameCheck.result.fixes} severity="warn" />
                <FrameCheckList title="Settings" items={frameCheck.result.settings} severity="unknown" />
                <FrameCheckList title="Looking good" items={frameCheck.result.ok} severity="ok" />
              </div>
            )
          ) : null}
        </CardContent>
      </Card>
    </div>
  );
}

/**
 * The coach layout for a check: what the photo shows, the steps in order (each with the name of
 * the Influora note it comes from), what is already working, what one photo cannot show, and —
 * when one missing fact would change the steps — one question with tap-to-answer options in the
 * creator's language. Tapping re-checks the same photo with the answer added (`onAnswer`).
 */
function CoachResult({
  result,
  answers,
  lang,
  canAnswer,
  onAnswer,
}: {
  result: MeeraShootCheckFrameResult;
  answers: CoachAnswer[];
  lang: ShootCheckLang;
  canAnswer: boolean;
  onAnswer: (ask: MeeraShootCheckAsk, option: number) => void;
}) {
  const hindi = lang === 'hi-IN';
  const steps: MeeraShootCheckStep[] = result.steps ?? [];
  const ask = result.ask;
  // An ask is shown only while it can still be answered: not one already answered (the server
  // should not repeat it, but a repeat must not loop), and not once 3 answers have been given.
  const showAsk =
    ask !== null && answers.length < MAX_COACH_ANSWERS && !answers.some((a) => a.ask.id === ask.id);

  return (
    <div className="flex flex-col gap-3" data-testid="frame-check-coach">
      {result.whatISee ? (
        <div>
          <p className="text-sm font-medium text-foreground">What I see</p>
          <p className="mt-1 text-sm text-muted-foreground" data-testid="frame-check-what-i-see">
            {result.whatISee}
          </p>
        </div>
      ) : null}

      {steps.length > 0 ? (
        <div>
          <p className="text-sm font-medium text-foreground">Try this, in order</p>
          <ol className="mt-1 flex flex-col gap-2" data-testid="frame-check-steps">
            {steps.map((step, i) => (
              <li key={i} className="flex items-start gap-2 text-sm" data-testid="frame-check-step">
                <span
                  className="mt-0.5 flex size-5 shrink-0 items-center justify-center rounded-full bg-muted text-xs font-medium text-foreground"
                  aria-hidden="true"
                >
                  {i + 1}
                </span>
                <div className="min-w-0">
                  <p className="break-words text-foreground">{step.text}</p>
                  {step.note ? (
                    <p className="break-words text-xs text-muted-foreground">from Influora&rsquo;s notes: {step.note}</p>
                  ) : null}
                </div>
              </li>
            ))}
          </ol>
        </div>
      ) : null}

      <FrameCheckList title="Looking good" items={result.ok} severity="ok" />
      <FrameCheckList title="Can’t tell from this photo" items={result.cantTell ?? []} severity="unknown" />

      {answers.length > 0 ? (
        <div data-testid="frame-check-answers">
          <p className="text-xs font-medium text-muted-foreground">{hindi ? 'Aapne bataya' : 'You told me'}</p>
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
          <p className="text-sm font-medium text-foreground">{hindi ? ask.questionHi : ask.questionEn}</p>
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

function FrameCheckList({ title, items, severity }: { title: string; items: string[]; severity: ReadingSeverity }) {
  if (items.length === 0) return null;
  const dotClass =
    severity === 'ok' ? 'bg-success' : severity === 'warn' ? 'bg-warning' : severity === 'bad' ? 'bg-destructive' : 'bg-muted-foreground';
  return (
    <div>
      <p className="text-sm font-medium text-foreground">{title}</p>
      <ul className="mt-1 flex flex-col gap-1">
        {items.map((item, i) => (
          <li key={i} className="flex items-start gap-2 text-sm text-muted-foreground">
            <span className={cn('mt-1.5 size-1.5 shrink-0 rounded-full', dotClass)} aria-hidden="true" />
            {item}
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
