import * as React from 'react';
import { ChevronLeft, ChevronRight, Loader2, SwitchCamera, X } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Dialog, DialogClose, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog';
import { shootCheckLangFor } from '@/components/creator/shoot-check/CoachResult';
import { FramingGuide } from '@/components/creator/shoot-check/FramingGuide';
import type { ShootCheckShot } from '@/components/creator/shoot-check/ShootCheckPanel';
import { useShootCheck } from '@/hooks/useShootCheck';
import { FRAME_CHECK_DISCLOSURE, type ShootCheckLang } from '@/lib/shoot-check/advice-copy';
import { FRAME_JPEG_QUALITY, MAX_FRAME_WIDTH } from '@/lib/shoot-check/capture-frame';
import { cn } from '@/lib/utils';

/**
 * The in-chat camera for a photo check. A full-screen black sheet on phones, a centred panel from
 * `sm` up, on the same Radix dialog MeeraVoiceMode uses (focus trap, Esc closes, portalled above the
 * fixed phone chat).
 *
 * It shows the live preview, the rule-of-thirds guide, a flip-camera button and one "Check my
 * set-up" button. No live readings and no spoken cues (Swapnil's ruling, 2026-09-26): the hook is
 * opened camera-only (`withMic: false`) and muted.
 *
 * The camera lives in `CameraSheetBody`, which is rendered only while `open`: closing the sheet
 * unmounts it at once, and the hook's unmount teardown stops the video track, the wake lock and any
 * speech — the camera light goes out even while the dialog is still animating closed.
 *
 * The capture is what the creator SAW: the preview is `object-cover`, so the still is cropped to the
 * part of the frame the preview shows (a portrait panel over a landscape webcam hides the sides),
 * then downscaled so its LONG edge is at most MAX_FRAME_WIDTH.
 */
export interface MeeraCameraSheetProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** The chat's language (BCP-47, e.g. `en-IN` / `hi-IN`). */
  lang: string;
  /** The shots of the script the check is for; empty for a check with no script. */
  shots: ShootCheckShot[];
  /** The shot to open on. Left out, the sheet opens on "Any shot" (no shot). */
  initialShotIndex?: number;
  /** Called with the captured still and the shot it was taken for (`null` for "Any shot"). The
   * sheet closes itself right after. */
  onCapture: (blob: Blob, shot: ShootCheckShot | null) => void;
}

const COPY = {
  en: {
    title: 'Check my set-up',
    description: 'Point the camera at your set-up, then tap Check my set-up. Meera looks at one photo.',
    check: 'Check my set-up',
    taking: 'Taking the photo…',
    close: 'Close camera',
    flip: 'Flip camera',
    prevShot: 'Previous shot',
    nextShot: 'Next shot',
    anyShot: 'Any shot',
    shotOf: (n: number, total: number) => `Shot ${n} of ${total}`,
    kept: 'Meera keeps the written result in this chat. The photo is never saved.',
    offline: 'You’re offline. Connect to check your set-up.',
    starting: 'Starting camera…',
    paused: 'The camera turned off to save battery.',
    startCamera: 'Start camera',
    unsupported: 'This browser can’t open the camera here. Try Chrome or Safari on your phone.',
    denied: 'Camera access was denied. Allow camera access in your browser settings, then open this again.',
    captureFailed: 'Couldn’t take the photo. Try again.',
  },
  // Hindi chrome is Devanagari, like the disclosure and framing-guide lines shown with it (the
  // chat's own Hindi copy is Devanagari too). Only the check's result card keeps the server's
  // Hinglish register (COACH_COPY).
  hi: {
    title: 'सेट-अप चेक करें',
    description: 'कैमरा अपने सेट-अप की तरफ़ करें, फिर सेट-अप चेक करें दबाएँ। Meera एक फ़ोटो देखती है।',
    check: 'सेट-अप चेक करें',
    taking: 'फ़ोटो ले रहे हैं…',
    close: 'कैमरा बंद करें',
    flip: 'कैमरा बदलें',
    prevShot: 'पिछला शॉट',
    nextShot: 'अगला शॉट',
    anyShot: 'कोई भी शॉट',
    shotOf: (n: number, total: number) => `शॉट ${n} / ${total}`,
    kept: 'Meera लिखा हुआ नतीजा इसी चैट में रखती है। फ़ोटो कभी सेव नहीं होती।',
    offline: 'आप ऑफ़लाइन हैं। सेट-अप चेक करने के लिए इंटरनेट से जुड़ें।',
    starting: 'कैमरा शुरू हो रहा है…',
    paused: 'बैटरी बचाने के लिए कैमरा बंद हो गया।',
    startCamera: 'कैमरा शुरू करें',
    unsupported: 'यह ब्राउज़र यहाँ कैमरा नहीं खोल सकता। फ़ोन पर Chrome या Safari आज़माएँ।',
    denied: 'कैमरा की अनुमति नहीं मिली। ब्राउज़र सेटिंग्स में कैमरा की अनुमति दें, फिर इसे दोबारा खोलें।',
    captureFailed: 'फ़ोटो नहीं ले पाए। फिर से कोशिश करें।',
  },
} as const;

type SheetCopy = (typeof COPY)[keyof typeof COPY];

export function MeeraCameraSheet({ open, onOpenChange, lang, shots, initialShotIndex, onCapture }: MeeraCameraSheetProps) {
  const shootLang = shootCheckLangFor(lang);
  const t = shootLang === 'hi-IN' ? COPY.hi : COPY.en;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        showCloseButton={false}
        data-testid="meera-camera-sheet"
        className={cn(
          // Phone: full screen. sm+: centred panel. Same geometry as MeeraVoiceMode, including the
          // scrollbar-gutter fix (right: 100% - 100vw).
          'inset-0 right-[calc(100%-100vw)] flex h-[100dvh] w-auto max-w-none translate-x-0 translate-y-0 flex-col gap-0 overflow-hidden rounded-none border-0 bg-black p-0 text-white',
          'sm:top-1/2 sm:right-auto sm:bottom-auto sm:left-1/2 sm:h-[min(42rem,90dvh)] sm:w-full sm:max-w-md sm:-translate-x-1/2 sm:-translate-y-1/2 sm:rounded-2xl',
        )}
      >
        <DialogDescription className="sr-only">{t.description}</DialogDescription>
        {open ? (
          <CameraSheetBody
            t={t}
            shootLang={shootLang}
            shots={shots}
            initialShotIndex={initialShotIndex}
            onCapture={onCapture}
            onClose={() => onOpenChange(false)}
          />
        ) : (
          <DialogTitle className="sr-only">{t.title}</DialogTitle>
        )}
      </DialogContent>
    </Dialog>
  );
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

const ICON_BUTTON =
  'grid h-11 w-11 shrink-0 place-items-center rounded-full text-white/85 transition-colors hover:bg-white/10 hover:text-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-violet-300 disabled:opacity-40 disabled:hover:bg-transparent';

function CameraSheetBody({
  t,
  shootLang,
  shots,
  initialShotIndex,
  onCapture,
  onClose,
}: {
  t: SheetCopy;
  shootLang: ShootCheckLang;
  shots: ShootCheckShot[];
  initialShotIndex?: number;
  onCapture: (blob: Blob, shot: ShootCheckShot | null) => void;
  onClose: () => void;
}) {
  // -1 is "Any shot" (no shot). With no initial shot the sheet opens there.
  const [shotIndex, setShotIndex] = React.useState(() =>
    typeof initialShotIndex === 'number' && shots.length > 0
      ? Math.max(0, Math.min(shots.length - 1, initialShotIndex))
      : -1
  );
  const currentShot = shotIndex >= 0 ? (shots[shotIndex] ?? null) : null;
  const [facingMode, setFacingMode] = React.useState<'user' | 'environment'>('user');
  const [capturing, setCapturing] = React.useState(false);
  const [captureFailed, setCaptureFailed] = React.useState(false);
  const online = useOnlineStatus();

  const shootCheck = useShootCheck({
    target: currentShot?.target ?? 'medium',
    facingMode,
    lang: shootLang,
    withMic: false,
  });
  const { start, stop, setMuted, noteInteraction, phase, supported, videoRef } = shootCheck;

  // No spoken cues in the sheet: mute before the camera is ever active.
  React.useEffect(() => {
    setMuted(true);
  }, [setMuted]);

  // Open the camera on mount, and again after a flip: `start` changes identity with facingMode
  // (it reads facingMode from its closure), and the flip handler has already stopped the old one.
  React.useEffect(() => {
    start();
  }, [start]);

  const mountedRef = React.useRef(true);
  React.useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const handleFlip = () => {
    stop();
    setCaptureFailed(false);
    setFacingMode((current) => (current === 'user' ? 'environment' : 'user'));
  };

  const goToShot = (next: number) => {
    setShotIndex(Math.max(-1, Math.min(shots.length - 1, next)));
    noteInteraction();
  };

  const active = phase === 'active';
  const checkDisabled = !active || !online || capturing;

  const handleCheck = async () => {
    if (checkDisabled) return;
    const video = videoRef.current;
    if (!video) return;
    noteInteraction();
    setCaptureFailed(false);
    setCapturing(true);
    const blob = await captureVisibleJpeg(video);
    if (!mountedRef.current) return;
    setCapturing(false);
    if (!blob) {
      setCaptureFailed(true);
      return;
    }
    onCapture(blob, currentShot);
    onClose();
  };

  const blocked = phase === 'denied' || phase === 'error' || phase === 'unsupported' || !supported;
  const deniedText = shootLang === 'hi-IN' ? t.denied : (shootCheck.errorMessage ?? t.denied);

  return (
    <>
      {/* Top bar: close, title, flip. */}
      <div className="flex shrink-0 items-center justify-between gap-2 px-2 pt-[max(0.75rem,env(safe-area-inset-top))] pb-2">
        <DialogClose aria-label={t.close} className={ICON_BUTTON}>
          <X className="h-5 w-5" aria-hidden />
        </DialogClose>
        <DialogTitle className="min-w-0 truncate text-center text-base font-semibold text-white">{t.title}</DialogTitle>
        <button
          type="button"
          aria-label={t.flip}
          onClick={handleFlip}
          disabled={blocked || phase === 'starting' || capturing}
          className={ICON_BUTTON}
          data-testid="camera-sheet-flip"
        >
          <SwitchCamera className="h-5 w-5" aria-hidden />
        </button>
      </div>

      {/* Shot strip, only for a script. */}
      {shots.length > 0 ? (
        <div className="flex shrink-0 items-center gap-1 px-2 pb-2" data-testid="camera-sheet-shot-strip">
          <button
            type="button"
            aria-label={t.prevShot}
            onClick={() => goToShot(shotIndex - 1)}
            disabled={shotIndex <= -1}
            className={ICON_BUTTON}
          >
            <ChevronLeft className="h-5 w-5" aria-hidden />
          </button>
          <p
            className="min-w-0 flex-1 truncate text-center text-sm text-white/90"
            title={currentShot ? `${t.shotOf(shotIndex + 1, shots.length)} · ${currentShot.label}` : t.anyShot}
            data-testid="camera-sheet-shot-label"
          >
            {currentShot ? `${t.shotOf(shotIndex + 1, shots.length)} · ${currentShot.label}` : t.anyShot}
          </p>
          <button
            type="button"
            aria-label={t.nextShot}
            onClick={() => goToShot(shotIndex + 1)}
            disabled={shotIndex >= shots.length - 1}
            className={ICON_BUTTON}
          >
            <ChevronRight className="h-5 w-5" aria-hidden />
          </button>
        </div>
      ) : null}

      {/* Preview: fills the height on a portrait phone, no 16:9 box. */}
      <div className="relative min-h-0 flex-1 overflow-hidden bg-black">
        {supported ? (
          <video
            ref={videoRef}
            className={cn('absolute inset-0 size-full object-cover', facingMode === 'user' && '-scale-x-100')}
            muted
            playsInline
            autoPlay
            data-testid="camera-sheet-video"
          />
        ) : null}
        {active ? <FramingGuide /> : null}

        {!active ? (
          <div className="absolute inset-0 flex flex-col items-center justify-center gap-4 p-6 text-center" role="status">
            {blocked ? (
              <>
                <p className="max-w-xs text-sm text-white/85">
                  {phase === 'unsupported' || !supported ? t.unsupported : deniedText}
                </p>
                <Button type="button" variant="secondary" className="min-h-11" onClick={onClose}>
                  {t.close}
                </Button>
              </>
            ) : phase === 'idle' ? (
              <>
                <p className="max-w-xs text-sm text-white/85">{t.paused}</p>
                <Button type="button" variant="secondary" className="min-h-11" onClick={() => start()}>
                  {t.startCamera}
                </Button>
              </>
            ) : (
              <p className="inline-flex items-center gap-2 text-sm text-white/85">
                <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
                {t.starting}
              </p>
            )}
          </div>
        ) : null}
      </div>

      {/* Bottom: what leaves the phone, what is kept, then the one action. */}
      <div className="flex shrink-0 flex-col gap-2 px-4 pt-3 pb-[max(1rem,env(safe-area-inset-bottom))]">
        <p className="text-xs text-white/75" data-testid="frame-check-disclosure">
          {FRAME_CHECK_DISCLOSURE[shootLang]}
        </p>
        <p className="text-xs text-white/75" data-testid="camera-sheet-kept">
          {t.kept}
        </p>
        {!online ? (
          <p className="text-sm text-white/90" role="status">
            {t.offline}
          </p>
        ) : null}
        {captureFailed ? (
          <p className="text-sm text-white/90" role="alert">
            {t.captureFailed}
          </p>
        ) : null}
        <Button
          type="button"
          className="h-12 w-full text-base"
          disabled={checkDisabled}
          onClick={() => void handleCheck()}
          data-testid="camera-sheet-check"
        >
          {capturing ? t.taking : t.check}
        </Button>
      </div>
    </>
  );
}

/**
 * The part of the video frame an `object-cover` preview of `boxWidth` x `boxHeight` shows, in the
 * video's own pixels. With no layout (a zero-sized box) it is the whole frame.
 */
function visibleCrop(
  videoWidth: number,
  videoHeight: number,
  boxWidth: number,
  boxHeight: number
): { sx: number; sy: number; sw: number; sh: number } {
  if (!boxWidth || !boxHeight) return { sx: 0, sy: 0, sw: videoWidth, sh: videoHeight };
  const scale = Math.max(boxWidth / videoWidth, boxHeight / videoHeight);
  const sw = Math.min(videoWidth, boxWidth / scale);
  const sh = Math.min(videoHeight, boxHeight / scale);
  return { sx: (videoWidth - sw) / 2, sy: (videoHeight - sh) / 2, sw, sh };
}

/**
 * One still of what the preview shows (see `visibleCrop`), downscaled so its long edge is at most
 * `maxEdge`, as a JPEG. Never mirrored: the preview mirrors the front camera for the creator, but
 * the check reads the real scene, so "the window on your left" means the creator's left.
 */
async function captureVisibleJpeg(
  video: HTMLVideoElement,
  maxEdge: number = MAX_FRAME_WIDTH,
  quality: number = FRAME_JPEG_QUALITY
): Promise<Blob | null> {
  if (!video.videoWidth || !video.videoHeight) return null;
  const { sx, sy, sw, sh } = visibleCrop(video.videoWidth, video.videoHeight, video.clientWidth, video.clientHeight);
  const scale = Math.min(1, maxEdge / Math.max(sw, sh));
  const width = Math.max(1, Math.round(sw * scale));
  const height = Math.max(1, Math.round(sh * scale));

  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext('2d');
  if (!ctx) return null;
  try {
    ctx.drawImage(video, sx, sy, sw, sh, 0, 0, width, height);
  } catch {
    return null;
  }
  return new Promise((resolve) => {
    try {
      canvas.toBlob((blob) => resolve(blob), 'image/jpeg', quality);
    } catch {
      resolve(null);
    }
  });
}
