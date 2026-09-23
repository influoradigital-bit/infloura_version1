/**
 * `useShootCheck` — the paths a browser test environment can actually prove.
 *
 * jsdom has no camera, no orientation sensor and no speech synthesizer, so this file deliberately
 * does NOT claim to test the meter. It tests the refusals and the cleanup, which are the parts
 * that decide whether a creator sees a blank screen or useful text: capability detection, an
 * insecure context, permission denial, and releasing the camera on unmount.
 *
 * The measurement maths is tested in `src/lib/shoot-check/metrics.test.ts`; the spoken-cue rule in
 * `src/lib/shoot-check/speech-throttle.test.ts`. None of the three proves the feature works on a
 * real handset — that is a release gate, not something automation can do here.
 *
 * Run: npx vitest run src/hooks/useShootCheck.test.ts
 */
import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { useShootCheck } from './useShootCheck';

const ORIGINAL_MEDIA_DEVICES = navigator.mediaDevices;
const ORIGINAL_SECURE = window.isSecureContext;

function setSecureContext(value: boolean): void {
  Object.defineProperty(window, 'isSecureContext', { value, configurable: true });
}

function setMediaDevices(value: unknown): void {
  Object.defineProperty(navigator, 'mediaDevices', { value, configurable: true });
}

/** A stream whose tracks record that they were stopped. */
function fakeStream(): { stream: MediaStream; stopped: () => number } {
  let stops = 0;
  const track = { kind: 'video', stop: () => { stops += 1; }, getSettings: () => ({}) };
  const stream = {
    getTracks: () => [track],
    getVideoTracks: () => [track],
    getAudioTracks: () => [],
  } as unknown as MediaStream;
  return { stream, stopped: () => stops };
}

beforeEach(() => {
  setSecureContext(true);
});

afterEach(() => {
  vi.restoreAllMocks();
  setMediaDevices(ORIGINAL_MEDIA_DEVICES);
  setSecureContext(ORIGINAL_SECURE);
});

describe('capability detection', () => {
  it('reports unsupported when the browser has no camera API', () => {
    setMediaDevices(undefined);
    const { result } = renderHook(() => useShootCheck({ target: 'medium' }));
    expect(result.current.supported).toBe(false);
    expect(result.current.phase).toBe('unsupported');
  });

  it('reports unsupported on an insecure origin, where getUserMedia can never work', () => {
    setSecureContext(false);
    setMediaDevices({ getUserMedia: vi.fn() });
    const { result } = renderHook(() => useShootCheck({ target: 'medium' }));
    expect(result.current.supported).toBe(false);
    expect(result.current.phase).toBe('unsupported');
  });

  it('reports supported and idle when the API is present on a secure origin', () => {
    setMediaDevices({ getUserMedia: vi.fn() });
    const { result } = renderHook(() => useShootCheck({ target: 'medium' }));
    expect(result.current.supported).toBe(true);
    expect(result.current.phase).toBe('idle');
    expect(result.current.readings).toBeNull();
  });

  it('start() on an unsupported browser stays unsupported instead of throwing', () => {
    setMediaDevices(undefined);
    const { result } = renderHook(() => useShootCheck({ target: 'medium' }));
    act(() => result.current.start());
    expect(result.current.phase).toBe('unsupported');
  });
});

describe('permission denial', () => {
  it('becomes denied with a message, and never throws at the caller', async () => {
    const error = Object.assign(new Error('Permission denied'), { name: 'NotAllowedError' });
    setMediaDevices({ getUserMedia: vi.fn().mockRejectedValue(error) });

    const { result } = renderHook(() => useShootCheck({ target: 'medium' }));
    await act(async () => {
      result.current.start();
    });

    await waitFor(() => expect(result.current.phase).toBe('denied'));
    expect(result.current.errorMessage).toBeTruthy();
    // A denied creator must still be told what to do, not left with a null everything.
    expect(typeof result.current.errorMessage).toBe('string');
  });

  it('a device already in use is an error phase, not a silent idle', async () => {
    const error = Object.assign(new Error('Device in use'), { name: 'NotReadableError' });
    setMediaDevices({ getUserMedia: vi.fn().mockRejectedValue(error) });

    const { result } = renderHook(() => useShootCheck({ target: 'medium' }));
    await act(async () => {
      result.current.start();
    });

    await waitFor(() => expect(['denied', 'error']).toContain(result.current.phase));
    expect(result.current.errorMessage).toBeTruthy();
  });
});

describe('camera release', () => {
  it('stops every track on unmount, so the camera light goes out', async () => {
    const { stream, stopped } = fakeStream();
    setMediaDevices({ getUserMedia: vi.fn().mockResolvedValue(stream) });

    const { result, unmount } = renderHook(() => useShootCheck({ target: 'medium' }));
    await act(async () => {
      result.current.start();
    });
    unmount();

    await waitFor(() => expect(stopped()).toBeGreaterThan(0));
  });

  it('stop() releases the camera and returns to idle', async () => {
    const { stream, stopped } = fakeStream();
    setMediaDevices({ getUserMedia: vi.fn().mockResolvedValue(stream) });

    const { result } = renderHook(() => useShootCheck({ target: 'medium' }));
    await act(async () => {
      result.current.start();
    });
    await act(async () => {
      result.current.stop();
    });

    expect(stopped()).toBeGreaterThan(0);
    expect(result.current.phase).toBe('idle');
  });
});

describe('mute', () => {
  it('starts unmuted and toggles', () => {
    setMediaDevices({ getUserMedia: vi.fn() });
    const { result } = renderHook(() => useShootCheck({ target: 'medium' }));
    expect(result.current.muted).toBe(false);
    act(() => result.current.setMuted(true));
    expect(result.current.muted).toBe(true);
  });
});
