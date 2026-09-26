/**
 * safe-zones.ts (spec 2.6): the bundled config parses to the interim values, and a config that breaks
 * any rule falls back to the built-in interim constants with exactly one error log.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';

import bundled from './safe-zones.json';
import { INTERIM_SAFE_ZONES, getSafeZones, parseSafeZonesConfig, type SafePlatform } from './safe-zones';

const PLATFORMS: SafePlatform[] = ['instagram_reels', 'youtube_shorts', 'tiktok'];

/** A deep copy of the bundled file with one platform's default edited. */
function configWith(edit: (d: Record<string, unknown>) => void, platform: SafePlatform = 'instagram_reels') {
  const copy = JSON.parse(JSON.stringify(bundled)) as {
    platforms: Record<string, { default: Record<string, unknown> }>;
  };
  edit(copy.platforms[platform].default);
  return copy;
}

afterEach(() => vi.restoreAllMocks());

describe('safe-zones config', () => {
  it('the bundled JSON is the shared contract: interim values, identical for all three platforms', () => {
    for (const platform of PLATFORMS) {
      const entry = (bundled.platforms as Record<string, { default: unknown; device_classes: unknown }>)[platform];
      expect(entry.default).toEqual({
        status: 'interim_unmeasured',
        source: 'interim 2026-09-26',
        top: 0.14,
        caption_line: 0.65,
        covered_from: 0.78,
        side: 0.04,
        rail: { x: 0.86, y_from: 0.5, y_to: 0.78 },
        cta_band: { x_to: 0.55 },
      });
      expect(entry.device_classes).toEqual({});
    }
    expect(bundled.schema).toBe(1);
  });

  it('getSafeZones reads the bundled file (Instagram Reels by default) without logging', () => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => {});
    const zones = getSafeZones();
    expect(zones).toEqual({
      status: 'interim_unmeasured',
      source: 'interim 2026-09-26',
      top: 0.14,
      captionLine: 0.65,
      coveredFrom: 0.78,
      side: 0.04,
      rail: { x: 0.86, yFrom: 0.5, yTo: 0.78 },
      ctaBand: { xTo: 0.55 },
    });
    for (const platform of PLATFORMS) expect(getSafeZones(platform)).toEqual(zones);
    expect(error).not.toHaveBeenCalled();
  });

  it('reads the config it is given, not constants: a valid edited value comes through', () => {
    const zones = parseSafeZonesConfig(configWith((d) => (d.caption_line = 0.6)));
    expect(zones.captionLine).toBe(0.6);
    expect(zones).not.toBe(INTERIM_SAFE_ZONES);
  });

  it.each([
    ['top above caption_line', (d: Record<string, unknown>) => (d.top = 0.7)],
    ['caption_line not above covered_from', (d: Record<string, unknown>) => (d.caption_line = 0.78)],
    ['a NaN', (d: Record<string, unknown>) => (d.side = Number.NaN)],
    ['Infinity', (d: Record<string, unknown>) => (d.covered_from = Number.POSITIVE_INFINITY)],
    ['a value above 1', (d: Record<string, unknown>) => (d.covered_from = 1.2)],
    ['a negative value', (d: Record<string, unknown>) => (d.top = -0.01)],
    ['a string number', (d: Record<string, unknown>) => (d.top = '0.14')],
    ['side not left of the rail', (d: Record<string, unknown>) => (d.side = 0.9)],
    ['rail y_from not above y_to', (d: Record<string, unknown>) => ((d.rail as Record<string, unknown>).y_from = 0.8)],
    ['rail ending below covered_from', (d: Record<string, unknown>) => ((d.rail as Record<string, unknown>).y_to = 0.9)],
    ['cta_band.x_to above 1', (d: Record<string, unknown>) => ((d.cta_band as Record<string, unknown>).x_to = 1.5)],
    ['a missing rail', (d: Record<string, unknown>) => delete d.rail],
    ['a missing status', (d: Record<string, unknown>) => delete d.status],
  ])('rejects %s: interim constants and one error log', (_name, edit) => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => {});
    const zones = parseSafeZonesConfig(configWith(edit));
    expect(zones).toBe(INTERIM_SAFE_ZONES);
    expect(error).toHaveBeenCalledTimes(1);
  });

  it.each([null, undefined, 'x', [], {}, { platforms: {} }, { platforms: { instagram_reels: {} } }])(
    'rejects a structurally wrong config (%j)',
    (raw) => {
      const error = vi.spyOn(console, 'error').mockImplementation(() => {});
      expect(parseSafeZonesConfig(raw)).toBe(INTERIM_SAFE_ZONES);
      expect(error).toHaveBeenCalledTimes(1);
    },
  );

  it('the fallback constants are the spec 2.6 interim values and cannot be mutated', () => {
    expect(INTERIM_SAFE_ZONES.top).toBe(0.14);
    expect(INTERIM_SAFE_ZONES.captionLine).toBe(0.65);
    expect(INTERIM_SAFE_ZONES.coveredFrom).toBe(0.78);
    expect(Object.isFrozen(INTERIM_SAFE_ZONES)).toBe(true);
    expect(Object.isFrozen(INTERIM_SAFE_ZONES.rail)).toBe(true);
  });
});
