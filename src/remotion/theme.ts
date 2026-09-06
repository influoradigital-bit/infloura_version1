/**
 * Colour and type tokens for the Meera demo composition.
 *
 * Mirrors the light palette in `src/app/globals.css` (`:root` block) by value,
 * because Remotion renders outside the app's Tailwind pipeline: no `@/` alias,
 * no CSS variables, no utility classes. Keep these in sync by hand.
 */
export const theme = {
  background: '#faf9fd',
  foreground: '#221e35',
  card: '#ffffff',
  primary: '#6d5ae6',
  primaryForeground: '#ffffff',
  secondary: '#ebe4f8',
  muted: '#ebe4f8',
  mutedForeground: '#67617d',
  accent: '#ede9fe',
  accentForeground: '#4c3bc2',
  destructive: '#ffe5e5',
  destructiveForeground: '#a63a3a',
  success: '#ddf5e8',
  successForeground: '#2f7a54',
  warning: '#fff4d6',
  warningForeground: '#8a6b1f',
  info: '#e3f0ff',
  infoForeground: '#3e6fae',
  border: '#d8d4e8',
  ring: '#6d5ae6',
  phoneBezel: '#1c1a26',
  whatsapp: '#dcf8c6',
} as const;

export const fontFamily =
  '"Inter", "Segoe UI", Roboto, "Noto Sans", "Helvetica Neue", Arial, sans-serif';

/** Composition geometry: portrait 1080×1920 at 30 fps. */
export const VIDEO = {
  width: 1080,
  height: 1920,
  fps: 30,
} as const;

/** Phone frame geometry inside the canvas. */
export const PHONE = {
  width: 860,
  height: 1560,
  radius: 72,
  bezel: 22,
  /** Distance from the top of the canvas to the phone's top edge. */
  top: 250,
  /** Chat header height inside the screen. */
  header: 128,
  /** Horizontal padding of the chat column. */
  pad: 34,
} as const;

export const SCREEN = {
  width: PHONE.width - PHONE.bezel * 2,
  height: PHONE.height - PHONE.bezel * 2,
} as const;
