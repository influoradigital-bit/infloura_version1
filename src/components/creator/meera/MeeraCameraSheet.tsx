import * as React from 'react';
import { ArrowUpDown, Camera, ChevronLeft, ChevronRight, Loader2, MapPin, PersonStanding, Sun, SwitchCamera, X } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Dialog, DialogClose, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog';
import { CameraGuidesSheet } from '@/components/creator/meera/CameraGuidesSheet';
import { shootCheckLangFor } from '@/components/creator/shoot-check/CoachResult';
import { FramingGuide } from '@/components/creator/shoot-check/FramingGuide';
import type { ShootCheckShot } from '@/components/creator/shoot-check/ShootCheckPanel';
import { useShootCheck } from '@/hooks/useShootCheck';
import { FRAME_CHECK_DISCLOSURE, adviceText, type ShootCheckLang } from '@/lib/shoot-check/advice-copy';
import { CARD_UNKNOWN, parseCardLight, parseCardProp, type ShotCard, type ShotSize } from '@/lib/shoot-check/beat-to-shot';
import { FRAME_JPEG_QUALITY, MAX_FRAME_WIDTH } from '@/lib/shoot-check/capture-frame';
import {
  readCameraGrid,
  readSafeZoneHintSeen,
  readShotGuides,
  writeCameraGrid,
  writeSafeZoneHintSeen,
  writeShotGuides,
  type CameraGrid,
} from '@/lib/shoot-check/guide-prefs';
import { getSafeZones } from '@/lib/shoot-check/safe-zones';
import {
  cleanShotText,
  clipVisible,
  propZone,
  setupChips,
  shotBands,
  shotSizeFor,
  textArea,
  type CardChipWords,
  type ChipKind,
  type TextAreaKind,
} from '@/lib/shoot-check/shot-zones';
import { cn } from '@/lib/utils';

/**
 * The in-chat camera for a photo check. A full-screen black sheet on phones, a centred panel from
 * `sm` up, on the same Radix dialog MeeraVoiceMode uses (focus trap, Esc closes, portalled above the
 * fixed phone chat).
 *
 * It shows the live preview with the shot guide (spec v2 Phase 5a + 5b: the 9:16 Reel frame, the
 * always-on safe zone, the camera grid and the shot bands for the current shot), a "Guides" button
 * for the grid and shot-guide settings, a flip-camera button and one "Check my set-up" button. The
 * guide is drawn, never read: no live readings, no match state and no spoken cues (Swapnil's
 * ruling, 2026-09-26). The hook is opened camera-only (`withMic: false`) and muted.
 *
 * Under the preview, the "Set-up for this shot: ..." line is the guide's text version (the SVG is
 * aria-hidden; the preview points at the line with `aria-describedby`), with the set-up chips.
 * The first time on a device, the safe-zone note shows until the creator taps "Got it".
 *
 * Decision 3 (Swapnil, 2026-09-26): while framing a beat, the bottom panel also shows the beat's
 * "Say:" line and "On screen:" text as plain text (cut by visible characters, so a Devanagari
 * syllable is never split). The first chip is the shot SIZE (it used to be called "angle"). When
 * the beat has a shot card, the chips add its camera height, light and place, and the guide draws
 * the prop zone from the card's prop; a card field that is `?` gets no chip and draws nothing.
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
    preview: 'Camera preview',
    generalGuide: 'General guide',
    where: 'Where:',
    light: 'Light:',
    prop: 'Prop:',
    suggestedSpot: '(suggested spot)',
    wideAsFull: 'The guide shows a full shot for a wide shot.',
    startingPoint: 'The shot bands are a starting point.',
    say: 'Say:',
    onScreen: 'On screen:',
    camera: 'Camera:',
    chipLabels: {
      size: 'Shot size',
      height: 'Camera height',
      light: 'Light',
      where: 'Place',
      sit_or_walk: 'Sit or walk',
    },
    sizes: {
      ECU: 'Extreme close-up',
      CU: 'Close-up',
      MCU: 'Medium close-up',
      MS: 'Medium shot',
      MLS: 'Medium long shot',
      FS: 'Full shot',
      LS: 'Wide shot',
      OVERHEAD: 'Overhead, hands',
    },
    heights: {
      eye: 'Eye level',
      chest: 'Chest height',
      above: 'Above eye level',
      below: 'Below eye level',
      overhead: 'Overhead',
    },
    lightKinds: {
      window: 'Window',
      sun: 'Sunlight',
      shade: 'Shade',
      lamp: 'Lamp',
      ring_light: 'Ring light',
      tube_light: 'Tube light',
      mixed: 'Mixed light',
    },
    lightSides: { left: 'your left', right: 'your right', front: 'in front', behind: 'behind you' },
    propSides: { left: 'your left', centre: 'centre', right: 'your right' },
    propSurfaces: { hand: 'in hand', table: 'on a table', floor: 'on the floor' },
    textPositions: {
      top: 'Text at the top',
      opposite_face: 'Text on the side away from your face',
      lower_middle: 'Text in the lower middle',
    },
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
    // The shot-guide lines below are pending Hindi review (spec 2.8), like the advice-copy ones.
    preview: 'कैमरा प्रीव्यू',
    generalGuide: 'सामान्य गाइड',
    where: 'जगह:',
    light: 'रोशनी:',
    prop: 'प्रॉप:',
    suggestedSpot: '(सुझाई गई जगह)',
    wideAsFull: 'वाइड शॉट के लिए गाइड फ़ुल शॉट दिखाती है।',
    startingPoint: 'शॉट बैंड बस शुरुआत के लिए हैं।',
    say: 'बोलें:',
    onScreen: 'स्क्रीन पर:',
    camera: 'कैमरा:',
    chipLabels: {
      size: 'शॉट साइज़',
      height: 'कैमरा की ऊँचाई',
      light: 'रोशनी',
      where: 'जगह',
      sit_or_walk: 'बैठना या चलना',
    },
    sizes: {
      ECU: 'एक्सट्रीम क्लोज़-अप',
      CU: 'क्लोज़-अप',
      MCU: 'मीडियम क्लोज़-अप',
      MS: 'मीडियम शॉट',
      MLS: 'मीडियम लॉन्ग शॉट',
      FS: 'फ़ुल शॉट',
      LS: 'वाइड शॉट',
      OVERHEAD: 'ऊपर से, हाथ',
    },
    heights: {
      eye: 'आँखों के लेवल पर',
      chest: 'छाती की ऊँचाई पर',
      above: 'आँखों से ऊपर',
      below: 'आँखों से नीचे',
      overhead: 'ऊपर से',
    },
    lightKinds: {
      window: 'खिड़की',
      sun: 'धूप',
      shade: 'छाँव',
      lamp: 'लैंप',
      ring_light: 'रिंग लाइट',
      tube_light: 'ट्यूबलाइट',
      mixed: 'मिली-जुली रोशनी',
    },
    lightSides: { left: 'आपके बाएँ', right: 'आपके दाएँ', front: 'सामने', behind: 'आपके पीछे' },
    propSides: { left: 'आपके बाएँ', centre: 'बीच में', right: 'आपके दाएँ' },
    propSurfaces: { hand: 'हाथ में', table: 'टेबल पर', floor: 'ज़मीन पर' },
    textPositions: {
      top: 'टेक्स्ट ऊपर',
      opposite_face: 'टेक्स्ट चेहरे की दूसरी तरफ़',
      lower_middle: 'टेक्स्ट नीचे बीच में',
    },
  },
} as const;

type SheetCopy = (typeof COPY)[keyof typeof COPY];

/** The Say line and On-screen text are cut at these many visible characters (never mid-syllable). */
const SAY_MAX_CHARS = 120;
const ON_SCREEN_MAX_CHARS = 60;

/** A card's camera height in the chat's language, or null when the card does not know it. */
function cardHeightWord(t: SheetCopy, card: ShotCard | undefined): string | null {
  if (!card || card.height === CARD_UNKNOWN) return null;
  return t.heights[card.height];
}

/** A card's light ("Window, your left"), or null when the card does not know it. */
function cardLightWord(t: SheetCopy, card: ShotCard | undefined): string | null {
  const light = card ? parseCardLight(card.light) : null;
  if (!light) return null;
  const kind = t.lightKinds[light.kind];
  return light.side ? `${kind}, ${t.lightSides[light.side]}` : kind;
}

/** A card's prop position ("your right, in hand"), or null for none / `?`. */
function cardPropWords(t: SheetCopy, card: ShotCard | undefined): string | null {
  const prop = card ? parseCardProp(card.prop) : null;
  if (!prop) return null;
  return `${t.propSides[prop.side]}, ${t.propSurfaces[prop.surface]}`;
}

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

  // Guide settings, remembered per device (guide-prefs: every storage access is try/catch).
  const [grid, setGrid] = React.useState<CameraGrid>(readCameraGrid);
  const [shotGuides, setShotGuides] = React.useState<boolean>(readShotGuides);
  const [hintSeen, setHintSeen] = React.useState<boolean>(readSafeZoneHintSeen);
  const [guidesOpen, setGuidesOpen] = React.useState(false);
  const guidesButtonRef = React.useRef<HTMLButtonElement>(null);
  const zones = React.useMemo(() => getSafeZones('instagram_reels'), []);
  const setupLineId = React.useId();
  const mirrored = facingMode === 'user';

  const handleGridChange = (next: CameraGrid) => {
    setGrid(next);
    writeCameraGrid(next);
    noteInteraction();
  };
  const handleShotGuidesChange = (on: boolean) => {
    setShotGuides(on);
    writeShotGuides(on);
    noteInteraction();
  };
  const dismissHint = () => {
    setHintSeen(true);
    writeSafeZoneHintSeen();
    noteInteraction();
  };

  const size = shotSizeFor(currentShot);
  const prop = shotGuides ? propZone({ shot: currentShot, size, grid, mirrored, zones }) : null;
  // The text area the guide places for this shot (null when it places none, as for opposite_face
  // with no known side or no eye line), so the set-up line never names a zone the guide lacks.
  const textKind = textArea({ shot: currentShot, bands: shotBands(size), zones, mirrored })?.kind ?? null;
  const setupLine = setupLineText({ t, shootLang, shot: currentShot, size, propShown: prop !== null, sidedProp: prop?.snappedTo != null, textKind, shotGuides });
  const cardWords: CardChipWords = {
    height: cardHeightWord(t, currentShot?.card),
    light: cardLightWord(t, currentShot?.card),
  };
  const chips = currentShot
    ? setupChips(currentShot, t.sizes[size], cardWords)
    : [{ kind: 'size' as const, text: t.generalGuide, full: t.generalGuide }];
  // The beat's own lines, for framing while reading them (decision 3). Untrusted script text:
  // React text only, cut by visible characters.
  const sayText = currentShot?.say ? clipVisible(currentShot.say, SAY_MAX_CHARS) : '';
  const onScreenText = currentShot?.onScreen ? clipVisible(currentShot.onScreen, ON_SCREEN_MAX_CHARS) : '';

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
      {/* Top bar: close, title, guides, flip. */}
      <div className="flex shrink-0 items-center justify-between gap-2 px-2 pt-[max(0.75rem,env(safe-area-inset-top))] pb-2">
        <DialogClose aria-label={t.close} className={ICON_BUTTON}>
          <X className="h-5 w-5" aria-hidden />
        </DialogClose>
        <DialogTitle className="min-w-0 truncate text-center text-base font-semibold text-white">{t.title}</DialogTitle>
        <div className="flex shrink-0 items-center gap-1">
          {/* A text label, not an icon alone, and at least 44 x 44 (spec v2 Phase 5a). */}
          <button
            ref={guidesButtonRef}
            type="button"
            onClick={() => setGuidesOpen(true)}
            aria-haspopup="dialog"
            className="inline-flex h-11 min-w-11 shrink-0 items-center justify-center rounded-full px-3 text-sm font-medium text-white/90 transition-colors hover:bg-white/10 hover:text-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-violet-300"
            data-testid="camera-sheet-guides"
          >
            {adviceText('guides_button', shootLang)}
          </button>
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
      <div
        className="relative min-h-0 flex-1 overflow-hidden bg-black"
        role="group"
        aria-label={t.preview}
        aria-describedby={setupLineId}
        data-testid="camera-sheet-preview"
      >
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
        {active ? (
          <FramingGuide shot={currentShot} shotGuides={shotGuides} grid={grid} mirrored={mirrored} zones={zones} lang={shootLang} />
        ) : null}

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

      {/* Bottom: the first-run safe-zone note, the set-up line and chips (here, so they never
          cover a zone label), what leaves the phone, what is kept, then the one action. */}
      <div className="flex shrink-0 flex-col gap-2 px-4 pt-3 pb-[max(1rem,env(safe-area-inset-bottom))]">
        {!hintSeen ? (
          <div className="flex items-center gap-3 rounded-lg bg-white/10 px-3 py-2" data-testid="safe-zone-hint">
            <p className="min-w-0 flex-1 text-xs text-white/90">{adviceText('safe_zone_note', shootLang)}</p>
            <Button type="button" variant="secondary" size="sm" className="min-h-11 shrink-0" onClick={dismissHint}>
              {adviceText('got_it', shootLang)}
            </Button>
          </div>
        ) : null}
        {sayText || onScreenText ? (
          <div className="flex flex-col gap-0.5 text-sm text-white" data-testid="camera-sheet-beat-lines">
            {sayText ? (
              <p className="break-words" data-testid="camera-sheet-say">
                <span className="font-semibold text-white/80">{t.say}</span> {sayText}
              </p>
            ) : null}
            {onScreenText ? (
              <p className="break-words" data-testid="camera-sheet-on-screen">
                <span className="font-semibold text-white/80">{t.onScreen}</span> {onScreenText}
              </p>
            ) : null}
          </div>
        ) : null}
        <p id={setupLineId} className="text-xs text-white/90" data-testid="camera-sheet-setup-line">
          {setupLine}
        </p>
        {chips.length > 0 ? (
          <ul className="flex flex-wrap gap-1.5" data-testid="camera-sheet-chips">
            {chips.map((chip) => {
              const Icon = CHIP_ICON[chip.kind];
              return (
                <li
                  key={chip.kind}
                  className="inline-flex items-center gap-1 rounded-full bg-white/15 px-2.5 py-1 text-xs text-white"
                  data-testid="camera-sheet-chip"
                  data-kind={chip.kind}
                >
                  <Icon className="h-3.5 w-3.5" aria-hidden />
                  {/* What the chip is, for screen readers: the first one is the shot size. */}
                  <span className="sr-only">{`${t.chipLabels[chip.kind]}: `}</span>
                  <span data-testid="camera-sheet-chip-text">{chip.text}</span>
                </li>
              );
            })}
          </ul>
        ) : null}
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

      <CameraGuidesSheet
        open={guidesOpen}
        onOpenChange={setGuidesOpen}
        lang={shootLang}
        shotGuides={shotGuides}
        onShotGuidesChange={handleShotGuidesChange}
        grid={grid}
        onGridChange={handleGridChange}
        returnFocusRef={guidesButtonRef}
      />
    </>
  );
}

const CHIP_ICON: Record<ChipKind, typeof Camera> = {
  size: Camera,
  height: ArrowUpDown,
  where: MapPin,
  light: Sun,
  sit_or_walk: PersonStanding,
};

/**
 * The guide's text version, in full with no cuts: "Set-up for this shot: <size>. Camera: <height>.
 * <angle>. Where: ... Light: ... <sit or walk>. Prop: ... (suggested spot). <text position>." plus
 * "Stand on the other line." when a side prop zone is drawn. With a shot card, its known place,
 * light, camera height, prop position and text position are used (a `?` field adds nothing, and
 * a card-placed prop is the plan, not a "suggested spot"). The card's text position is named only
 * when `textKind` (the area `textArea` places) is that position, so the line never describes a
 * text zone the guide does not have. The values are the script's (untrusted) text, rendered only
 * as React text.
 */
function setupLineText({
  t,
  shootLang,
  shot,
  size,
  propShown,
  sidedProp,
  textKind,
  shotGuides,
}: {
  t: SheetCopy;
  shootLang: ShootCheckLang;
  shot: ShootCheckShot | null;
  size: ShotSize;
  propShown: boolean;
  sidedProp: boolean;
  textKind: TextAreaKind | null;
  shotGuides: boolean;
}): string {
  const context = shot?.context ?? {};
  const card = shot?.card;
  const parts: string[] = [];
  if (!shot) parts.push(t.generalGuide);
  parts.push(t.sizes[size]);
  const height = cardHeightWord(t, card);
  if (height) parts.push(`${t.camera} ${height}`);
  const angle = cleanShotText(context.angle);
  if (angle) parts.push(angle);
  const cardPlace = card && card.place !== CARD_UNKNOWN ? cleanShotText(card.place) : '';
  const where = cardPlace || cleanShotText(context.where);
  if (where) parts.push(`${t.where} ${where}`);
  const light = cardLightWord(t, card) ?? cleanShotText(context.light);
  if (light) parts.push(`${t.light} ${light}`);
  const sitOrWalk = cleanShotText(context.sit_or_walk);
  if (sitOrWalk) parts.push(sitOrWalk);
  const propText = cleanShotText(context.prop);
  const propPlan = cardPropWords(t, card);
  if (propPlan) parts.push(propText ? `${t.prop} ${propText} (${propPlan})` : `${t.prop} ${propPlan}`);
  else if (propText) parts.push(propShown ? `${t.prop} ${propText} ${t.suggestedSpot}` : `${t.prop} ${propText}`);
  if (card && card.text !== CARD_UNKNOWN && card.text !== 'none' && textKind === card.text) parts.push(t.textPositions[card.text]);

  const stop = shootLang === 'hi-IN' ? '।' : '.';
  const sentence = (text: string) => (/[.!?।…]$/.test(text) ? text : `${text}${stop}`);
  const lines = [adviceText('setup_line_prefix', shootLang), ...parts.map(sentence)];
  if (sidedProp) lines.push(adviceText('stand_other_line', shootLang));
  if (shotGuides && size === 'LS') lines.push(t.wideAsFull);
  if (shotGuides) lines.push(t.startingPoint);
  return lines.join(' ');
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
