import * as React from 'react';
import { Camera, ChevronLeft, ChevronRight, Focus, Image as ImageIcon, Mic, RotateCw, Sun, Volume2, VolumeX } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Switch } from '@/components/ui/switch';
import { cn } from '@/lib/utils';
import { meeraApi, type MeeraShootCheckFrameResult } from '@/lib/meera-api';
import type { ShootCheckLang } from '@/lib/shoot-check/advice-copy';
import type { ShotTarget } from '@/lib/shoot-check/metrics';
import { captureDownscaledJpeg } from '@/lib/shoot-check/capture-frame';
import { useShootCheck } from '@/hooks/useShootCheck';
import { FramingGuide } from './FramingGuide';
import { ReadingRow, type ReadingSeverity } from './ReadingRow';

export interface ShootCheckShot {
  index: number;
  label: string;
  seconds: number;
  target: ShotTarget;
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

function shotHeaderText(shot: ShootCheckShot, total: number): string {
  const base = `Shot ${shot.index + 1} of ${total}`;
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
  /** Self-scoped, same convention as `/workspaces/me/...` elsewhere in this codebase — the
   * server derives the real workspace from the auth token; this is only what the multipart
   * contract's `workspace_id` field expects to see. */
  workspaceId?: string;
  /** Creator's language for on-screen fix text and spoken cues — the page reads this from
   * `api.creatorAgentPrefs.getPreferences().creator_language`, same source `creator-copilot.tsx`
   * uses, and passes it down; this component has no opinion on where it comes from. */
  lang?: ShootCheckLang;
}

export function ShootCheckPanel({ shots, workspaceId = 'me', lang = 'en-IN' }: ShootCheckPanelProps) {
  const script = shots && shots.length > 0 ? shots : [FALLBACK_SHOT];
  const [shotIndex, setShotIndex] = React.useState(0);
  const clampedIndex = Math.min(shotIndex, script.length - 1);
  const currentShot = script[clampedIndex];

  const shootCheck = useShootCheck({ target: currentShot.target, lang });
  const online = useOnlineStatus();

  const [frameCheck, setFrameCheck] = React.useState<{
    status: 'idle' | 'loading' | 'done' | 'error';
    result: MeeraShootCheckFrameResult | null;
  }>({ status: 'idle', result: null });

  const goToShot = React.useCallback(
    (nextIndex: number) => {
      setShotIndex(Math.max(0, Math.min(script.length - 1, nextIndex)));
      setFrameCheck({ status: 'idle', result: null });
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

  const handleCheckFrame = React.useCallback(async () => {
    if (frameCheckDisabled) return;
    const video = shootCheck.videoRef.current;
    if (!video) return;

    shootCheck.noteInteraction();
    setFrameCheck({ status: 'loading', result: null });

    const blob = await captureDownscaledJpeg(video);
    if (!blob) {
      setFrameCheck({ status: 'error', result: null });
      return;
    }

    const result = await meeraApi.checkFrame(blob, workspaceId, currentShot.label || undefined, 'creator');
    if (!result) {
      setFrameCheck({ status: 'error', result: null });
      return;
    }
    setFrameCheck({ status: 'done', result });
  }, [frameCheckDisabled, shootCheck, workspaceId, currentShot.label]);

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
          value: `${Math.round(readings.mic.levelDb - readings.mic.noiseFloorDb)} dB gap`,
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
          <CardTitle className="text-base">{shotHeaderText(currentShot, script.length)}</CardTitle>
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
            A deeper, one-time check on a single still photo — takes a few seconds, nothing is saved.
          </p>
          <Button onClick={() => void handleCheckFrame()} disabled={frameCheckDisabled} className="self-start">
            {frameCheck.status === 'loading' ? 'Checking…' : 'Check my frame'}
          </Button>
          {!online ? <p className="text-sm text-muted-foreground">You’re offline — connect to run this check.</p> : null}

          {frameCheck.status === 'error' ? (
            <p className="text-sm text-muted-foreground">Couldn’t check your frame right now. Try again in a moment.</p>
          ) : null}

          {frameCheck.status === 'done' && frameCheck.result ? (
            <div className="flex flex-col gap-3">
              <FrameCheckList title="Fix" items={frameCheck.result.fixes} severity="warn" />
              <FrameCheckList title="Settings" items={frameCheck.result.settings} severity="unknown" />
              <FrameCheckList title="Looking good" items={frameCheck.result.ok} severity="ok" />
            </div>
          ) : null}
        </CardContent>
      </Card>
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

function micSeverity(status: 'poor-separation' | 'weak-separation' | 'ok'): ReadingSeverity {
  if (status === 'poor-separation') return 'bad';
  if (status === 'weak-separation') return 'warn';
  return 'ok';
}
