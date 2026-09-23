/**
 * Level 2 "Check my frame" — captures ONE still from the live `<video>` element, downscaled to
 * a max width and re-encoded as JPEG. Kept as its own tiny module (rather than inline in
 * `ShootCheckPanel`) so a component test can mock it directly instead of needing a real
 * `HTMLCanvasElement`/`HTMLVideoElement` pipeline, which jsdom cannot provide.
 */
export const MAX_FRAME_WIDTH = 800;
export const FRAME_JPEG_QUALITY = 0.7;

export async function captureDownscaledJpeg(
  video: HTMLVideoElement,
  maxWidth: number = MAX_FRAME_WIDTH,
  quality: number = FRAME_JPEG_QUALITY
): Promise<Blob | null> {
  if (!video.videoWidth || !video.videoHeight) return null;

  const scale = Math.min(1, maxWidth / video.videoWidth);
  const width = Math.max(1, Math.round(video.videoWidth * scale));
  const height = Math.max(1, Math.round(video.videoHeight * scale));

  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext('2d');
  if (!ctx) return null;

  try {
    ctx.drawImage(video, 0, 0, width, height);
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
