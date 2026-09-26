import * as React from 'react';
import {
  Camera,
  ChevronLeft,
  ChevronRight,
  Focus,
  Image as ImageIcon,
  Mic,
  RotateCw,
  Sun,
  Volume2,
  VolumeX,
} from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Switch } from '@/components/ui/switch';
import {
  meeraApi,
  type MeeraShootCheckAsk,
  type MeeraShootCheckFrameResult,
  type MeeraShotContext,
} from '@/lib/meera-api';
import { FRAME_CHECK_DISCLOSURE, type ShootCheckLang } from '@/lib/shoot-check/advice-copy';
import type { ShotCard } from '@/lib/shoot-check/beat-to-shot';
import type { ShotTarget } from '@/lib/shoot-check/metrics';
import { captureDownscaledJpeg } from '@/lib/shoot-check/capture-frame';
import { useShootCheck } from '@/hooks/useShootCheck';
import { CoachResult, FrameCheckList, MAX_COACH_ANSWERS, hasCoachShape, type CoachAnswer } from './CoachResult';
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
  /** The beat's Say line, shown in the camera sheet while framing (never sent with the check).
   * Optional: shots rebuilt from a stored label (`shotFromLabel`) do not have it. */
  say?: string;
  /** The beat's On-screen text, shown in the camera sheet (never sent with the check). */
  onScreen?: string;
  /** The beat's validated shot card (spec v2 Phase 6), when the reply had one. */
  card?: ShotCard;
}

function shotContextFor(shot: ShootCheckShot): MeeraShotContext | undefined {
  const context: MeeraShotContext = { ...(shot.context ?? {}) };
  if (shot.label.trim() && !context.line?.trim()) context.line = shot.label.trim();
  return Object.values(context).some((v) => typeof v === 'string' && v.trim()) ? context : undefined;
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
