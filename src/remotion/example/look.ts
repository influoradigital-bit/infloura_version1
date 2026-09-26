/**
 * Look tokens for the product-demo films.
 *
 * These are the Meera palette from `src/app/globals.css` (L103-131) copied by value, because
 * Remotion renders outside Tailwind: no CSS variables, no utility classes. That palette is
 * deliberately 60/30/10 — calm surface, ink text, ONE accent — with trust colours that are
 * never themed. Keep this file in sync with globals.css by hand.
 */
export const M = {
  /** 60 — surfaces */
  bg: '#F7F8FB',
  bgSubtle: '#EEF1F6',
  surface: '#FFFFFF',
  surface2: '#F2F4F9',
  /** 30 — ink */
  text: '#0E1626',
  textMuted: '#5B667C',
  border: '#DDE3EC',
  borderStrong: '#C3CCDC',
  /** 10 — the one accent, on the action only */
  accent: '#6D5AE6',
  accentPress: '#4C3BC2',
  accentSoft: '#EDEAFB',
  /** Trust colours — never themed, never decorative */
  escrow: '#12A150',
  escrowSoft: '#E4F7EC',
  warning: '#E8A317',
  warningSoft: '#FDF3E0',
  danger: '#E0344B',
  dangerSoft: '#FCE9EC',
  info: '#2C7BE5',
  /** The dark stage the film opens and closes on */
  stage: '#0B0F1A',
  stage2: '#151C2C',
} as const;

export const FONT = '"Inter", "Segoe UI", Roboto, "Noto Sans", "Helvetica Neue", Arial, sans-serif';

/** Type scale at 1080p. Scale by `u` for other sizes. */
export const TYPE = {
  timestamp: 30,
  caption: 46,
  captionLead: 62,
  cardTitle: 30,
  body: 25,
  small: 21,
  tiny: 18,
  money: 44,
} as const;

/** Indian-format rupees, the way `formatINR` renders them in the app. */
export function inr(value: number): string {
  return `₹${value.toLocaleString('en-IN')}`;
}

/** A window shadow built from three layers, the way a real elevation reads. */
export const WINDOW_SHADOW = [
  '0 2px 6px rgba(14,22,38,0.06)',
  '0 18px 40px rgba(14,22,38,0.10)',
  '0 60px 120px rgba(14,22,38,0.18)',
].join(', ');

export const CARD_SHADOW = '0 1px 2px rgba(14,22,38,0.05), 0 8px 24px rgba(14,22,38,0.06)';
