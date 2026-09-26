/**
 * guide-prefs: the camera grid, Shot guides and first-run hint, remembered per device with every
 * storage access wrapped (spec v2 Phase 5a/5b).
 *
 * Run: npx vitest run src/lib/shoot-check/guide-prefs.test.ts
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  CAMERA_GRID_KEY,
  SAFE_ZONE_HINT_KEY,
  SHOT_GUIDES_KEY,
  readCameraGrid,
  readSafeZoneHintSeen,
  readShotGuides,
  writeCameraGrid,
  writeSafeZoneHintSeen,
  writeShotGuides,
} from './guide-prefs';

beforeEach(() => {
  window.localStorage.clear();
});

afterEach(() => {
  vi.restoreAllMocks();
  window.localStorage.clear();
});

function storageThrows(): void {
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
    throw new DOMException('blocked', 'SecurityError');
  });
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
    throw new DOMException('blocked', 'SecurityError');
  });
}

describe('camera grid', () => {
  it('uses the key influora.camera-grid.v1', () => {
    expect(CAMERA_GRID_KEY).toBe('influora.camera-grid.v1');
  });

  it('missing -> Rule of thirds', () => {
    expect(readCameraGrid()).toBe('thirds');
  });

  it('an unknown value ("banana") -> Rule of thirds', () => {
    window.localStorage.setItem(CAMERA_GRID_KEY, 'banana');
    expect(readCameraGrid()).toBe('thirds');
  });

  it('storage that throws -> Rule of thirds, and a write does not throw', () => {
    storageThrows();
    expect(readCameraGrid()).toBe('thirds');
    expect(() => writeCameraGrid('golden')).not.toThrow();
    expect(readCameraGrid()).toBe('thirds');
  });

  it('golden and off are stored and read back', () => {
    writeCameraGrid('golden');
    expect(window.localStorage.getItem(CAMERA_GRID_KEY)).toBe('golden');
    expect(readCameraGrid()).toBe('golden');
    writeCameraGrid('off');
    expect(readCameraGrid()).toBe('off');
    writeCameraGrid('thirds');
    expect(readCameraGrid()).toBe('thirds');
  });

  it('never stores a value it would not read back', () => {
    writeCameraGrid('banana' as never);
    expect(window.localStorage.getItem(CAMERA_GRID_KEY)).toBeNull();
  });
});

describe('shot guides switch', () => {
  it('is on by default, and on for anything but a stored "off"', () => {
    expect(readShotGuides()).toBe(true);
    window.localStorage.setItem(SHOT_GUIDES_KEY, 'banana');
    expect(readShotGuides()).toBe(true);
  });

  it('off is stored and read back; on again too', () => {
    writeShotGuides(false);
    expect(readShotGuides()).toBe(false);
    writeShotGuides(true);
    expect(readShotGuides()).toBe(true);
  });

  it('storage that throws -> on, and a write does not throw', () => {
    storageThrows();
    expect(readShotGuides()).toBe(true);
    expect(() => writeShotGuides(false)).not.toThrow();
  });
});

describe('first-run safe-zone hint', () => {
  it('is unseen until "Got it" is stored', () => {
    expect(readSafeZoneHintSeen()).toBe(false);
    writeSafeZoneHintSeen();
    expect(window.localStorage.getItem(SAFE_ZONE_HINT_KEY)).toBe('seen');
    expect(readSafeZoneHintSeen()).toBe(true);
  });

  it('storage that throws -> shown again (the safe side), no throw', () => {
    storageThrows();
    expect(readSafeZoneHintSeen()).toBe(false);
    expect(() => writeSafeZoneHintSeen()).not.toThrow();
  });
});
