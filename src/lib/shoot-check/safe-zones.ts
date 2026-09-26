/**
 * Safe zone: the parts of a 9:16 Reel that the app's own buttons, top bar and caption cover
 * (spec 2.6). The numbers are CONFIG, not code: `safe-zones.json` next to this file is the twin of
 * influora-ai's `app/shoot/safe_zones.json` (a parity test fails if the two differ in any value),
 * so Meera's words and every guide the app draws read the same zones.
 *
 * Every value is a share 0..1 of the 9:16 frame. `top` is the covered top bar; text and every main
 * line end above `captionLine`; the band from `captionLine` to `coveredFrom` is for a short CTA on
 * the left only (`ctaBand.xTo`); everything below `coveredFrom` is covered; `side` is kept clear on
 * both sides; `rail` is the right-side button column.
 *
 * Pure: no DOM. A config that fails validation is never half-used: the whole thing falls back to
 * `INTERIM_SAFE_ZONES` with one `console.error`, so a bad edit can never draw a zone that hides text.
 */
import bundledConfig from './safe-zones.json';

export type SafePlatform = 'instagram_reels' | 'youtube_shorts' | 'tiktok';

export interface SafeZones {
  status: string;
  source: string;
  top: number;
  captionLine: number;
  coveredFrom: number;
  side: number;
  rail: { x: number; yFrom: number; yTo: number };
  ctaBand: { xTo: number };
}

/** The interim, unmeasured values (spec 2.6 table). Used whenever the config is unusable; the
 *  measurement task (M) replaces the JSON, never these, so a broken file still draws a sane guide. */
export const INTERIM_SAFE_ZONES: SafeZones = Object.freeze({
  status: 'interim_unmeasured',
  source: 'interim 2026-09-26',
  top: 0.14,
  captionLine: 0.65,
  coveredFrom: 0.78,
  side: 0.04,
  rail: Object.freeze({ x: 0.86, yFrom: 0.5, yTo: 0.78 }),
  ctaBand: Object.freeze({ xTo: 0.55 }),
}) as SafeZones;

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

/** A number that is finite and inside [0, 1]; `NaN`, `Infinity`, strings, booleans and null fail. */
function share(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 1 ? value : null;
}

/** One platform's `default` entry as `SafeZones`, or `null` when any rule in spec 2.6 fails. */
function readZones(entry: unknown): SafeZones | null {
  if (!isRecord(entry)) return null;
  const { status, source, rail, cta_band } = entry;
  if (typeof status !== 'string' || typeof source !== 'string') return null;
  if (!isRecord(rail) || !isRecord(cta_band)) return null;
  const top = share(entry.top);
  const captionLine = share(entry.caption_line);
  const coveredFrom = share(entry.covered_from);
  const side = share(entry.side);
  const railX = share(rail.x);
  const railYFrom = share(rail.y_from);
  const railYTo = share(rail.y_to);
  const ctaXTo = share(cta_band.x_to);
  if (
    top === null ||
    captionLine === null ||
    coveredFrom === null ||
    side === null ||
    railX === null ||
    railYFrom === null ||
    railYTo === null ||
    ctaXTo === null
  ) {
    return null;
  }
  if (!(top < captionLine && captionLine < coveredFrom)) return null;
  if (!(side < railX)) return null;
  if (!(railYFrom < railYTo && railYTo <= coveredFrom)) return null;
  return {
    status,
    source,
    top,
    captionLine,
    coveredFrom,
    side,
    rail: { x: railX, yFrom: railYFrom, yTo: railYTo },
    ctaBand: { xTo: ctaXTo },
  };
}

/**
 * The zones for `platform` from a parsed config object (the shape of `safe-zones.json`). Only the
 * platform's `default` is read: a device class is used only once measured, and none is yet (the
 * app has no device-class detection to pick one with). Anything invalid returns
 * `INTERIM_SAFE_ZONES` and logs one error.
 */
export function parseSafeZonesConfig(raw: unknown, platform: SafePlatform = 'instagram_reels'): SafeZones {
  const platforms = isRecord(raw) ? raw.platforms : undefined;
  const entry = isRecord(platforms) ? platforms[platform] : undefined;
  const zones = isRecord(entry) ? readZones(entry.default) : null;
  if (zones) return zones;
  console.error(`[safe-zones] invalid safe-zone config for ${platform}; using the interim values`);
  return INTERIM_SAFE_ZONES;
}

const cache = new Map<SafePlatform, SafeZones>();

/** The bundled config's zones for `platform` (Instagram Reels at launch, spec 2.6). Parsed once per
 *  platform, so an invalid bundled file logs its one error once, not on every render. */
export function getSafeZones(platform: SafePlatform = 'instagram_reels'): SafeZones {
  let zones = cache.get(platform);
  if (!zones) {
    zones = parseSafeZonesConfig(bundledConfig, platform);
    cache.set(platform, zones);
  }
  return zones;
}
