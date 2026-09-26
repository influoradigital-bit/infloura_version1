/**
 * Ambient shim for the (Chromium-only, experimental) Shape Detection API's `FaceDetector`.
 * Not in TS's default DOM lib. Mirrors `src/types/speech.d.ts`'s approach: a minimal surface
 * covering only what `useShootCheck` actually calls, gated behind `'FaceDetector' in window` at
 * every call site — never assumed present.
 */

interface DetectedFace {
  readonly boundingBox: DOMRectReadOnly;
}

interface FaceDetector {
  detect(image: CanvasImageSource): Promise<DetectedFace[]>;
}

interface FaceDetectorStatic {
  new (options?: { maxDetectedFaces?: number; fastMode?: boolean }): FaceDetector;
}

interface Window {
  FaceDetector?: FaceDetectorStatic;
}
