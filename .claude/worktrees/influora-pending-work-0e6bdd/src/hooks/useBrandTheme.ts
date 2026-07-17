import { useEffect, useRef } from 'react'

/**
 * Injects the brand's scraped hex as --brand on a ref'd root element and
 * derives --meera-accent (+hover/press/soft/glow) from it at runtime.
 * Scoped to the workspace root only — the rest of the app is unaffected.
 *
 * Contrast guardrail (mandatory, handoff §3b): WCAG-checks the brand hex
 * against --meera-surface (#FFFFFF light). If it fails the text/UI contrast
 * thresholds, or the color is near-white/near-grey, falls back to the
 * default Meera indigo (#6D5AE6). --meera-escrow/--meera-danger/--meera-warning
 * are NEVER touched here — only the accent layer is themed.
 */

const DEFAULT_ACCENT = '#6D5AE6'

type RGB = { r: number; g: number; b: number }

function hexToRgb(hex: string): RGB | null {
  const clean = hex.replace('#', '').trim()
  const full =
    clean.length === 3
      ? clean
          .split('')
          .map((c) => c + c)
          .join('')
      : clean
  if (full.length !== 6 || /[^0-9a-fA-F]/.test(full)) return null
  const num = parseInt(full, 16)
  return {
    r: (num >> 16) & 255,
    g: (num >> 8) & 255,
    b: num & 255,
  }
}

function rgbToHex({ r, g, b }: RGB): string {
  const toHex = (n: number) => Math.max(0, Math.min(255, Math.round(n))).toString(16).padStart(2, '0')
  return `#${toHex(r)}${toHex(g)}${toHex(b)}`
}

/** Relative luminance (WCAG 2.x) */
function relativeLuminance({ r, g, b }: RGB): number {
  const srgb = [r, g, b].map((c) => {
    const cs = c / 255
    return cs <= 0.03928 ? cs / 12.92 : Math.pow((cs + 0.055) / 1.055, 2.4)
  })
  return 0.2126 * srgb[0] + 0.7152 * srgb[1] + 0.0722 * srgb[2]
}

/** WCAG contrast ratio between two colors */
function contrastRatio(a: RGB, b: RGB): number {
  const la = relativeLuminance(a)
  const lb = relativeLuminance(b)
  const lighter = Math.max(la, lb)
  const darker = Math.min(la, lb)
  return (lighter + 0.05) / (darker + 0.05)
}

/** Simple HSL lightness clamp — adjusts brand color darker until it passes contrast. */
function clampLightnessForContrast(rgb: RGB, against: RGB, minRatio: number): RGB {
  let current = rgb
  let attempts = 0
  while (contrastRatio(current, against) < minRatio && attempts < 12) {
    current = {
      r: current.r * 0.88,
      g: current.g * 0.88,
      b: current.b * 0.88,
    }
    attempts += 1
  }
  return current
}

function isNearWhiteOrGrey(rgb: RGB): boolean {
  const { r, g, b } = rgb
  const max = Math.max(r, g, b)
  const min = Math.min(r, g, b)
  const saturationLike = max - min
  const luminance = relativeLuminance(rgb)
  return luminance > 0.85 || saturationLike < 12
}

function lighten(rgb: RGB, amountPercent: number): RGB {
  const factor = amountPercent / 100
  return {
    r: rgb.r + (255 - rgb.r) * factor,
    g: rgb.g + (255 - rgb.g) * factor,
    b: rgb.b + (255 - rgb.b) * factor,
  }
}

function darken(rgb: RGB, amountPercent: number): RGB {
  const factor = 1 - amountPercent / 100
  return { r: rgb.r * factor, g: rgb.g * factor, b: rgb.b * factor }
}

/** Derives the accessible accent hex from a brand hex, or falls back to default. */
export function deriveAccent(brandHex: string | undefined | null): string {
  const rgb = brandHex ? hexToRgb(brandHex) : null
  if (!rgb || isNearWhiteOrGrey(rgb)) return DEFAULT_ACCENT

  const surfaceWhite: RGB = { r: 255, g: 255, b: 255 }
  const clamped = clampLightnessForContrast(rgb, surfaceWhite, 3) // large-UI threshold (buttons/chips)
  return rgbToHex(clamped)
}

export interface BrandThemeResult {
  accent: string
  accentHover: string
  accentPress: string
  accentSoft: string
  accentGlow: string
  gradientStart: string
  gradientEnd: string
}

export function computeBrandTheme(brandHex?: string | null): BrandThemeResult {
  const accentHex = deriveAccent(brandHex)
  const rgb = hexToRgb(accentHex) ?? hexToRgb(DEFAULT_ACCENT)!

  const hover = rgbToHex(darken(rgb, 12))
  const press = rgbToHex(darken(rgb, 24))
  const soft = rgbToHex(lighten(rgb, 88))
  const glow = `rgba(${Math.round(rgb.r)}, ${Math.round(rgb.g)}, ${Math.round(rgb.b)}, 0.35)`
  const gradientEnd = rgbToHex(lighten(rgb, 12))

  return {
    accent: accentHex,
    accentHover: hover,
    accentPress: press,
    accentSoft: soft,
    accentGlow: glow,
    gradientStart: accentHex,
    gradientEnd,
  }
}

/**
 * Hook: applies the derived theme as CSS vars on the given ref's element.
 * Usage: const rootRef = useRef<HTMLDivElement>(null); useBrandTheme(rootRef, brandHex)
 */
export function useBrandTheme(rootRef: React.RefObject<HTMLElement | null>, brandHex?: string | null) {
  const theme = useRef<BrandThemeResult>(computeBrandTheme(brandHex))

  useEffect(() => {
    theme.current = computeBrandTheme(brandHex)
    const el = rootRef.current
    if (!el) return

    el.style.setProperty('--brand', brandHex || DEFAULT_ACCENT)
    el.style.setProperty('--meera-accent', theme.current.accent)
    el.style.setProperty('--meera-accent-hover', theme.current.accentHover)
    el.style.setProperty('--meera-accent-press', theme.current.accentPress)
    el.style.setProperty('--meera-accent-soft', theme.current.accentSoft)
    el.style.setProperty('--meera-accent-glow', theme.current.accentGlow)
    // --meera-escrow / --meera-danger / --meera-warning are intentionally never set here.
  }, [brandHex, rootRef])

  return theme.current
}
