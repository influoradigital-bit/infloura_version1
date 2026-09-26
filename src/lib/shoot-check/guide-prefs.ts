/**
 * The camera's guide settings, remembered per device (spec v2 Phase 5a, "Remembered per device,
 * not per creator"): the "Camera grid" choice, the "Shot guides" switch and whether the first-run
 * safe-zone hint has been dismissed.
 *
 * A grid is a screen-and-habit preference, so it lives in `localStorage`, not on the creator's
 * account. Storage can be missing, blocked (private mode, a strict browser setting) or throw on
 * any access, so EVERY read and write is wrapped: a read that fails gives the default, a write
 * that fails is dropped, and the camera works exactly the same without storage.
 *
 * The safe zone has no setting here on purpose: it is always drawn (spec 2.6).
 */

export type CameraGrid = 'thirds' | 'golden' | 'off';

/** The radio order in the Guides sheet; `thirds` is the default. */
export const CAMERA_GRIDS: readonly CameraGrid[] = ['thirds', 'golden', 'off'];
export const DEFAULT_CAMERA_GRID: CameraGrid = 'thirds';

export const CAMERA_GRID_KEY = 'influora.camera-grid.v1';
export const SHOT_GUIDES_KEY = 'influora.shot-guides.v1';
export const SAFE_ZONE_HINT_KEY = 'influora.safe-zone-hint.v1';

function readItem(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

function writeItem(key: string, value: string): void {
  try {
    window.localStorage.setItem(key, value);
  } catch {
    // Storage blocked or full: the choice lasts for this visit only.
  }
}

function isCameraGrid(value: unknown): value is CameraGrid {
  return typeof value === 'string' && (CAMERA_GRIDS as readonly string[]).includes(value);
}

/** The stored grid, or Rule of thirds when it is missing, unknown ("banana") or unreadable. */
export function readCameraGrid(): CameraGrid {
  const stored = readItem(CAMERA_GRID_KEY);
  return isCameraGrid(stored) ? stored : DEFAULT_CAMERA_GRID;
}

export function writeCameraGrid(grid: CameraGrid): void {
  if (!isCameraGrid(grid)) return;
  writeItem(CAMERA_GRID_KEY, grid);
}

/** "Shot guides" is on by default: only a stored "off" turns it off. */
export function readShotGuides(): boolean {
  return readItem(SHOT_GUIDES_KEY) !== 'off';
}

export function writeShotGuides(on: boolean): void {
  writeItem(SHOT_GUIDES_KEY, on ? 'on' : 'off');
}

/** True once the creator tapped "Got it" on the first-run safe-zone hint on this device. With
 *  storage blocked the hint shows each time the camera opens, which is the safe side to fail on. */
export function readSafeZoneHintSeen(): boolean {
  return readItem(SAFE_ZONE_HINT_KEY) === 'seen';
}

export function writeSafeZoneHintSeen(): void {
  writeItem(SAFE_ZONE_HINT_KEY, 'seen');
}
