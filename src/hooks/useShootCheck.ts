import { useCallback, useEffect, useRef, useState } from 'react';

import {
  averageLuma,
  clutter,
  focusVerdict,
  framingVerdict,
  lightVerdict,
  micVerdict,
  sharpness,
  tiltVerdict,
  THRESHOLDS,
  type Box,
  type FocusAdvice,
  type FocusStatus,
  type FramingAdvice,
  type FramingStatus,
  type LightAdvice,
  type LightStatus,
  type MicAdvice,
  type MicStatus,
  type ShotTarget,
  type TiltAdvice,
  type TiltStatus,
} from '@/lib/shoot-check/metrics';
import { adviceText, type ShootCheckLang } from '@/lib/shoot-check/advice-copy';
import { initialSpeechThrottleState, shouldSpeak, type SpeechThrottleState } from '@/lib/shoot-check/speech-throttle';

/**
 * Camera Shoot Check — Level 1 live coaching (spec: creator "Shoot check" screen).
 *
 * Mirrors `useVoiceInput`'s discipline for a video capability instead of an audio one: a
 * secure-context + capability guard produces a distinct `'unsupported'` phase, permission denial
 * is its own phase with a message (never a thrown error), and every acquired resource — the
 * camera stream, the WebAudio graph, the `deviceorientation` listener, the wake lock, the sample
 * timer, the inactivity timer, and any in-flight speech — is released on `stop()`, on unmount, on
 * `visibilitychange` to hidden, and after `INACTIVITY_TIMEOUT_MS` of no interaction.
 *
 * What this hook does NOT do: judge exposure/focus/etc. itself. All of that lives in
 * `src/lib/shoot-check/metrics.ts` as pure functions over a `Uint8ClampedArray` — this hook's
 * only job is sampling the live preview into a small offscreen canvas ~4x/second and feeding the
 * result through those functions, so the maths stays unit-testable without a camera.
 *
 * Face detection uses the browser's built-in `FaceDetector` (Shape Detection API) ONLY when
 * `'FaceDetector' in window` — there is no bundled fallback library, ever, per the no-new-deps
 * ruling for this feature. Without it (most browsers, as of this writing), `face` stays `null`
 * and `framingVerdict` falls back to its "can't find your face" / hands-overhead-appropriate
 * behavior — the hook still works, it just can't give face-relative framing advice.
 */

export type ShootCheckPhase = 'unsupported' | 'idle' | 'starting' | 'active' | 'denied' | 'error';

/** Offscreen analysis canvas size — small on purpose: this is a coarse, on-device, ~4Hz signal,
 * not a frame-grade quality check (Level 2's `/ai/shoot-check/frame` does that server-side). */
const ANALYSIS_WIDTH = 160;
const ANALYSIS_HEIGHT = 90;

export const SAMPLE_INTERVAL_MS = 250; // ~4x/second
export const SPEECH_THROTTLE_MS = 3000;
export const INACTIVITY_TIMEOUT_MS = 120_000; // 2 minutes — battery/heat guard

export interface ShootCheckReadings {
  light: { luma: number; status: LightStatus; advice: LightAdvice; text: string };
  focus: { sharpness: number; status: FocusStatus; advice: FocusAdvice; text: string };
  framing: { status: FramingStatus; advice: FramingAdvice; text: string };
  /** `degrees`/`status`/`advice` are `'unknown'` together, never `0`, when the device never
   * reports orientation — a silent 0 would read as "perfectly level" instead of "we don't know". */
  tilt: { degrees: number | 'unknown'; status: TiltStatus | 'unknown'; advice: TiltAdvice | 'unknown'; text: string };
  background: { clutter: number; status: 'ok' | 'busy'; text: string };
  mic: { levelDb: number; noiseFloorDb: number; status: MicStatus; advice: MicAdvice; text: string };
}

export interface UseShootCheckOptions {
  target: ShotTarget;
  facingMode?: 'user' | 'environment';
  /** Creator's language for spoken cues (`hi-IN` / `en-IN`) — read from wherever the app already
   * holds it (`api.creatorAgentPrefs.getPreferences().creator_language`); this hook has no
   * opinion on where that comes from. Defaults to `'en-IN'`, matching the rest of the creator
   * Meera surface's default. */
  lang?: ShootCheckLang;
}

export interface UseShootCheckResult {
  supported: boolean;
  phase: ShootCheckPhase;
  errorMessage: string | null;
  readings: ShootCheckReadings | null;
  videoRef: React.RefObject<HTMLVideoElement | null>;
  start: () => void;
  stop: () => void;
  muted: boolean;
  setMuted: (value: boolean) => void;
  /** Call on any explicit user interaction (Next/Previous shot, mute toggle, "Check my frame")
   * to reset the 2-minute inactivity clock without restarting the camera. */
  noteInteraction: () => void;
}

function detectSupport(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof navigator !== 'undefined' &&
    Boolean(navigator.mediaDevices?.getUserMedia) &&
    window.isSecureContext
  );
}

/** Time-domain RMS of the current buffer, converted to a rough dBFS-like scale, floored at -60. */
function readMicLevelDb(analyser: AnalyserNode): number {
  const buffer = new Uint8Array(analyser.fftSize);
  analyser.getByteTimeDomainData(buffer);
  let sumSquares = 0;
  for (let i = 0; i < buffer.length; i++) {
    const centered = (buffer[i] - 128) / 128;
    sumSquares += centered * centered;
  }
  const rms = Math.sqrt(sumSquares / buffer.length);
  if (rms <= 0) return -60;
  return Math.max(-60, 20 * Math.log10(rms));
}

export function useShootCheck({ target, facingMode = 'user', lang = 'en-IN' }: UseShootCheckOptions): UseShootCheckResult {
  const supportedRef = useRef(detectSupport());
  const supported = supportedRef.current;

  const [phase, setPhase] = useState<ShootCheckPhase>(supported ? 'idle' : 'unsupported');
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [readings, setReadings] = useState<ShootCheckReadings | null>(null);
  const [muted, setMutedState] = useState(false);

  const phaseRef = useRef<ShootCheckPhase>(phase);
  useEffect(() => {
    phaseRef.current = phase;
  }, [phase]);

  const mutedRef = useRef(false);
  useEffect(() => {
    mutedRef.current = muted;
  }, [muted]);

  const langRef = useRef<ShootCheckLang>(lang);
  useEffect(() => {
    langRef.current = lang;
  }, [lang]);

  const targetRef = useRef<ShotTarget>(target);
  useEffect(() => {
    targetRef.current = target;
  }, [target]);

  const videoRef = useRef<HTMLVideoElement | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const sampleTimerRef = useRef<number | null>(null);
  const inactivityTimerRef = useRef<number | null>(null);
  const wakeLockRef = useRef<WakeLockSentinel | null>(null);

  const tiltRef = useRef<number | 'unknown'>('unknown');
  const orientationSupportedRef = useRef(typeof window !== 'undefined' && 'DeviceOrientationEvent' in window);
  const orientationListenerAttachedRef = useRef(false);

  const audioContextRef = useRef<AudioContext | null>(null);
  const analyserRef = useRef<AnalyserNode | null>(null);
  const noiseFloorDbRef = useRef(-50);

  const faceDetectorRef = useRef<FaceDetector | null>(null);
  const faceDetectionBusyRef = useRef(false);
  const latestFaceRef = useRef<Box | null>(null);

  const throttleStateRef = useRef<SpeechThrottleState>(initialSpeechThrottleState());

  const speak = useCallback((key: string, text: string) => {
    if (mutedRef.current) return;
    const now = Date.now();
    if (!shouldSpeak(key, now, throttleStateRef.current, SPEECH_THROTTLE_MS)) return;
    throttleStateRef.current = { lastKey: key, lastAt: now };

    if (typeof window === 'undefined' || !('speechSynthesis' in window)) return;
    try {
      const utterance = new SpeechSynthesisUtterance(text);
      utterance.lang = langRef.current;
      window.speechSynthesis.speak(utterance);
    } catch {
      // Best-effort — a synth failure must never break the visual readings.
    }
  }, []);

  const handleOrientation = useCallback((event: DeviceOrientationEvent) => {
    // `gamma` (left-right tilt, -90..90) is the axis relevant to a phone held upright for a
    // portrait shot — `beta` (front-back) matters more for a flat-on-a-table overhead shot, but
    // Level 1 only ever coaches the upright hold, so gamma is the one signal read here.
    if (typeof event.gamma === 'number') tiltRef.current = event.gamma;
  }, []);

  const sampleOnce = useCallback(() => {
    const video = videoRef.current;
    const canvas = canvasRef.current;
    if (!video || !canvas) return;
    if (video.readyState < 2 /* HAVE_CURRENT_DATA */) return;

    const ctx = canvas.getContext('2d');
    // No 2D canvas context — e.g. jsdom in tests, or a locked-down environment. Sampling is a
    // silent no-op rather than a crash; `readings` simply never populates.
    if (!ctx) return;

    canvas.width = ANALYSIS_WIDTH;
    canvas.height = ANALYSIS_HEIGHT;
    try {
      ctx.drawImage(video, 0, 0, ANALYSIS_WIDTH, ANALYSIS_HEIGHT);
    } catch {
      return;
    }

    let data: Uint8ClampedArray;
    try {
      data = ctx.getImageData(0, 0, ANALYSIS_WIDTH, ANALYSIS_HEIGHT).data;
    } catch {
      // e.g. a tainted canvas — never let this take down the sampling loop.
      return;
    }

    const luma = averageLuma(data);
    const sharp = sharpness(data, ANALYSIS_WIDTH, ANALYSIS_HEIGHT);
    const light = lightVerdict(luma);
    const focus = focusVerdict(sharp, luma);

    // Face detection runs on the full-resolution <video>, not the 160x90 analysis canvas — a
    // downscaled buffer is too small for FaceDetector to find much. Independent cadence: skip
    // this tick if a previous detect() call from a slow device is still in flight, rather than
    // piling up promises.
    if (faceDetectorRef.current && !faceDetectionBusyRef.current && video.videoWidth > 0) {
      faceDetectionBusyRef.current = true;
      faceDetectorRef.current
        .detect(video)
        .then((faces) => {
          const best = faces[0];
          latestFaceRef.current = best
            ? {
                x: best.boundingBox.x,
                y: best.boundingBox.y,
                width: best.boundingBox.width,
                height: best.boundingBox.height,
              }
            : null;
        })
        .catch(() => {
          latestFaceRef.current = null;
        })
        .finally(() => {
          faceDetectionBusyRef.current = false;
        });
    }

    const face = latestFaceRef.current;
    const frameSize = { width: video.videoWidth || ANALYSIS_WIDTH, height: video.videoHeight || ANALYSIS_HEIGHT };
    const framing = framingVerdict(face, frameSize, targetRef.current);

    // Clutter needs the subject box rescaled into the 160x90 analysis canvas's own space — the
    // face box above is in full video-resolution pixels.
    let subjectBoxForClutter: Box | undefined;
    if (face && video.videoWidth > 0 && video.videoHeight > 0) {
      const scaleX = ANALYSIS_WIDTH / video.videoWidth;
      const scaleY = ANALYSIS_HEIGHT / video.videoHeight;
      subjectBoxForClutter = {
        x: face.x * scaleX,
        y: face.y * scaleY,
        width: face.width * scaleX,
        height: face.height * scaleY,
      };
    }
    const clutterValue = clutter(data, ANALYSIS_WIDTH, ANALYSIS_HEIGHT, subjectBoxForClutter);
    const backgroundStatus: 'ok' | 'busy' = clutterValue > THRESHOLDS.CLUTTER_BUSY ? 'busy' : 'ok';

    const tiltDegrees = tiltRef.current;
    const tiltResult = tiltDegrees === 'unknown' ? null : tiltVerdict(tiltDegrees);

    let micLevelDb = -60;
    const analyser = analyserRef.current;
    if (analyser) {
      micLevelDb = readMicLevelDb(analyser);
      // Slow-moving noise-floor estimate: drop instantly toward a quieter reading (the room just
      // got quiet — trust it), drift up slowly otherwise (a momentary loud voice must not drag
      // the "room" floor up with it, or every reading would look artificially fine).
      noiseFloorDbRef.current =
        micLevelDb < noiseFloorDbRef.current ? micLevelDb : noiseFloorDbRef.current + (micLevelDb - noiseFloorDbRef.current) * 0.02;
    }
    const mic = micVerdict(micLevelDb, noiseFloorDbRef.current);

    const lang = langRef.current;
    const nextReadings: ShootCheckReadings = {
      light: { luma, status: light.status, advice: light.advice, text: adviceText(light.advice, lang) },
      focus: { sharpness: sharp, status: focus.status, advice: focus.advice, text: adviceText(focus.advice, lang) },
      framing: { status: framing.status, advice: framing.advice, text: adviceText(framing.advice, lang) },
      tilt: tiltResult
        ? { degrees: tiltDegrees as number, status: tiltResult.status, advice: tiltResult.advice, text: adviceText(tiltResult.advice, lang) }
        : { degrees: 'unknown', status: 'unknown', advice: 'unknown', text: adviceText('unknown', lang) },
      background: {
        clutter: clutterValue,
        status: backgroundStatus,
        text: adviceText(backgroundStatus === 'busy' ? 'tidy-background' : 'ok', lang),
      },
      mic: { levelDb: micLevelDb, noiseFloorDb: noiseFloorDbRef.current, status: mic.status, advice: mic.advice, text: adviceText(mic.advice, lang) },
    };
    setReadings(nextReadings);

    // Speak the single most urgent non-ok reading this tick, in a fixed priority order —
    // framing first (the biggest "redo the shot" issue), then light/focus (unusable footage),
    // then tilt/background/mic (polish). Only one utterance ever goes out per tick.
    const candidates: Array<{ key: string; ok: boolean; text: string }> = [
      { key: `framing:${framing.advice}`, ok: framing.advice === 'ok', text: nextReadings.framing.text },
      { key: `light:${light.advice}`, ok: light.advice === 'ok', text: nextReadings.light.text },
      { key: `focus:${focus.advice}`, ok: focus.advice === 'ok' || focus.advice === 'unknown', text: nextReadings.focus.text },
      {
        key: `tilt:${nextReadings.tilt.advice}`,
        ok: nextReadings.tilt.advice === 'ok' || nextReadings.tilt.advice === 'unknown',
        text: nextReadings.tilt.text,
      },
      { key: `background:${backgroundStatus}`, ok: backgroundStatus === 'ok', text: nextReadings.background.text },
      { key: `mic:${mic.advice}`, ok: mic.advice === 'ok', text: nextReadings.mic.text },
    ];
    const urgent = candidates.find((c) => !c.ok);
    if (urgent) {
      speak(urgent.key, urgent.text);
    } else {
      speak('all:ok', adviceText('ok', lang));
    }
  }, [speak]);

  const clearInactivityTimer = useCallback(() => {
    if (inactivityTimerRef.current !== null) {
      clearTimeout(inactivityTimerRef.current);
      inactivityTimerRef.current = null;
    }
  }, []);

  // Declared with `let` + assigned below so `stop` and `armInactivityTimer` can reference each
  // other without a hoisting problem — `armInactivityTimer`'s timeout callback calls `stop()`.
  const stopRef = useRef<() => void>(() => {});

  const armInactivityTimer = useCallback(() => {
    clearInactivityTimer();
    inactivityTimerRef.current = window.setTimeout(() => {
      stopRef.current();
    }, INACTIVITY_TIMEOUT_MS);
  }, [clearInactivityTimer]);

  const noteInteraction = useCallback(() => {
    if (phaseRef.current === 'active') armInactivityTimer();
  }, [armInactivityTimer]);

  const stop = useCallback(() => {
    if (sampleTimerRef.current !== null) {
      clearInterval(sampleTimerRef.current);
      sampleTimerRef.current = null;
    }
    clearInactivityTimer();

    streamRef.current?.getTracks().forEach((track) => track.stop());
    streamRef.current = null;
    if (videoRef.current) videoRef.current.srcObject = null;

    if (orientationListenerAttachedRef.current) {
      window.removeEventListener('deviceorientation', handleOrientation);
      orientationListenerAttachedRef.current = false;
    }
    tiltRef.current = 'unknown';

    const audioCtx = audioContextRef.current;
    audioContextRef.current = null;
    analyserRef.current = null;
    if (audioCtx && audioCtx.state !== 'closed') void audioCtx.close().catch(() => {});

    const wakeLock = wakeLockRef.current;
    wakeLockRef.current = null;
    if (wakeLock) void wakeLock.release().catch(() => {});

    faceDetectorRef.current = null;
    latestFaceRef.current = null;

    if (typeof window !== 'undefined' && 'speechSynthesis' in window) window.speechSynthesis.cancel();
    throttleStateRef.current = initialSpeechThrottleState();

    setReadings(null);
    setPhase((current) => (current === 'unsupported' ? current : 'idle'));
  }, [clearInactivityTimer, handleOrientation]);

  useEffect(() => {
    stopRef.current = stop;
  }, [stop]);

  const start = useCallback(() => {
    if (!supportedRef.current) return;
    if (phaseRef.current === 'starting' || phaseRef.current === 'active') return;

    setErrorMessage(null);
    setPhase('starting');

    void (async () => {
      try {
        const stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode }, audio: true });
        streamRef.current = stream;
        if (videoRef.current) {
          videoRef.current.srcObject = stream;
          void videoRef.current.play().catch(() => {});
        }

        if (!canvasRef.current) canvasRef.current = document.createElement('canvas');

        if (typeof window !== 'undefined' && window.FaceDetector) {
          try {
            faceDetectorRef.current = new window.FaceDetector({ fastMode: true, maxDetectedFaces: 1 });
          } catch {
            faceDetectorRef.current = null;
          }
        } else {
          faceDetectorRef.current = null;
        }

        try {
          const AudioCtxCtor =
            window.AudioContext || (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
          if (AudioCtxCtor) {
            const audioCtx = new AudioCtxCtor();
            audioContextRef.current = audioCtx;
            if (audioCtx.state === 'suspended') void audioCtx.resume().catch(() => {});
            const source = audioCtx.createMediaStreamSource(stream);
            const analyser = audioCtx.createAnalyser();
            analyser.fftSize = 2048;
            source.connect(analyser);
            analyserRef.current = analyser;
          }
        } catch {
          analyserRef.current = null;
        }

        if (orientationSupportedRef.current) {
          window.addEventListener('deviceorientation', handleOrientation);
          orientationListenerAttachedRef.current = true;
        }

        // Best-effort screen wake lock — battery/heat guard is a hard cap (INACTIVITY_TIMEOUT_MS)
        // regardless, so a browser without wake-lock support just relies on that.
        try {
          if (navigator.wakeLock) {
            wakeLockRef.current = await navigator.wakeLock.request('screen');
          }
        } catch {
          wakeLockRef.current = null;
        }

        sampleTimerRef.current = window.setInterval(sampleOnce, SAMPLE_INTERVAL_MS);
        armInactivityTimer();
        setPhase('active');
      } catch {
        // getUserMedia permission denied, no camera, or any other acquisition failure — a
        // distinct phase with a message, never a thrown error reaching the caller.
        streamRef.current?.getTracks().forEach((track) => track.stop());
        streamRef.current = null;
        setErrorMessage('Camera access was denied — allow camera access in your browser settings to use Shoot Check.');
        setPhase('denied');
      }
    })();
  }, [facingMode, handleOrientation, sampleOnce, armInactivityTimer]);

  const setMuted = useCallback((value: boolean) => {
    setMutedState(value);
    if (value && typeof window !== 'undefined' && 'speechSynthesis' in window) {
      window.speechSynthesis.cancel();
    }
  }, []);

  // Stop on tab hide — a backgrounded tab has no business keeping the camera/mic hot.
  useEffect(() => {
    const handleVisibility = () => {
      if (typeof document !== 'undefined' && document.hidden && phaseRef.current === 'active') {
        stopRef.current();
      }
    };
    document.addEventListener('visibilitychange', handleVisibility);
    return () => document.removeEventListener('visibilitychange', handleVisibility);
  }, []);

  // Unconditional teardown on unmount — same resources `stop()` releases, called directly
  // (not via the `stop` callback, whose identity may have changed) so nothing is left running
  // after the component holding this hook is gone.
  useEffect(() => {
    return () => {
      if (sampleTimerRef.current !== null) clearInterval(sampleTimerRef.current);
      if (inactivityTimerRef.current !== null) clearTimeout(inactivityTimerRef.current);
      streamRef.current?.getTracks().forEach((track) => track.stop());
      if (orientationListenerAttachedRef.current) window.removeEventListener('deviceorientation', handleOrientation);
      const audioCtx = audioContextRef.current;
      if (audioCtx && audioCtx.state !== 'closed') void audioCtx.close().catch(() => {});
      const wakeLock = wakeLockRef.current;
      if (wakeLock) void wakeLock.release().catch(() => {});
      if (typeof window !== 'undefined' && 'speechSynthesis' in window) window.speechSynthesis.cancel();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- intentional: unmount-only teardown, reads current refs
  }, []);

  return { supported, phase, errorMessage, readings, videoRef, start, stop, muted, setMuted, noteInteraction };
}
