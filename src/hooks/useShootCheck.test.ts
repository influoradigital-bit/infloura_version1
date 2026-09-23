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

/** A promise this test controls the resolution of, standing in for a `getUserMedia` call that
 * hasn't returned yet — the exact shape C1's three cancellation regressions need. */
function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
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

/**
 * C1 — `start()` used to have no way to cancel a `getUserMedia` already in flight: pressing stop,
 * navigating away, or hiding the tab while the permission prompt was still up left the camera/mic
 * running (until the 2-minute inactivity timer) the instant the browser resolved that call. Each
 * of these mirrors a real creator action arriving DURING that wait, using a deferred promise this
 * test resolves only after the cancellation has already happened.
 */
describe('cancellable start()', () => {
  it('stop() called while getUserMedia is still pending releases the stream and never goes active', async () => {
    const { stream, stopped } = fakeStream();
    const { promise, resolve } = deferred<MediaStream>();
    setMediaDevices({ getUserMedia: vi.fn().mockReturnValue(promise) });

    const { result } = renderHook(() => useShootCheck({ target: 'medium' }));
    act(() => {
      result.current.start();
    });
    expect(result.current.phase).toBe('starting');

    act(() => {
      result.current.stop();
    });

    await act(async () => {
      resolve(stream);
      await promise;
    });

    expect(stopped()).toBeGreaterThan(0);
    expect(result.current.phase).not.toBe('active');
  });

  it('unmounting while getUserMedia is still pending releases the stream', async () => {
    const { stream, stopped } = fakeStream();
    const { promise, resolve } = deferred<MediaStream>();
    setMediaDevices({ getUserMedia: vi.fn().mockReturnValue(promise) });

    const { result, unmount } = renderHook(() => useShootCheck({ target: 'medium' }));
    act(() => {
      result.current.start();
    });

    unmount();

    await act(async () => {
      resolve(stream);
      await promise;
    });

    expect(stopped()).toBeGreaterThan(0);
  });

  it('the tab going hidden while getUserMedia is still pending releases the stream and never goes active', async () => {
    const { stream, stopped } = fakeStream();
    const { promise, resolve } = deferred<MediaStream>();
    setMediaDevices({ getUserMedia: vi.fn().mockReturnValue(promise) });

    const { result } = renderHook(() => useShootCheck({ target: 'medium' }));
    act(() => {
      result.current.start();
    });
    expect(result.current.phase).toBe('starting');

    Object.defineProperty(document, 'hidden', { value: true, configurable: true });
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'));
    });

    await act(async () => {
      resolve(stream);
      await promise;
    });

    expect(stopped()).toBeGreaterThan(0);
    expect(result.current.phase).not.toBe('active');

    Object.defineProperty(document, 'hidden', { value: false, configurable: true });
  });
});

/**
 * C2 — a single combined `{video, audio}` getUserMedia call failed whole whenever the microphone
 * had no device or was itself blocked, and the one error it threw was reported to the creator as
 * the CAMERA being denied. Video and audio are now requested separately so each can fail on its
 * own.
 */
describe('camera and microphone requested separately', () => {
  it('requests video, then audio, as two separate getUserMedia calls', async () => {
    const { stream } = fakeStream();
    const getUserMedia = vi.fn().mockResolvedValue(stream);
    setMediaDevices({ getUserMedia });

    const { result } = renderHook(() => useShootCheck({ target: 'medium' }));
    await act(async () => {
      result.current.start();
    });

    await waitFor(() => expect(result.current.phase).toBe('active'));
    expect(getUserMedia).toHaveBeenCalledTimes(2);
    expect(getUserMedia).toHaveBeenNthCalledWith(1, { video: { facingMode: 'user' } });
    expect(getUserMedia).toHaveBeenNthCalledWith(2, { audio: true });
  });

  it('a device with no microphone at all still reaches active (this is the actual old bug: a combined call rejects whole)', async () => {
    const { stream } = fakeStream();
    const micError = Object.assign(new Error('no microphone'), { name: 'NotFoundError' });
    // Simulates the real hardware behavior the old single-call code hit: requesting audio+video
    // TOGETHER on a mic-less device rejects the WHOLE call, but each requested alone succeeds.
    const getUserMedia = vi.fn().mockImplementation((constraints: MediaStreamConstraints) => {
      if (constraints.audio && constraints.video) return Promise.reject(micError);
      if (constraints.audio) return Promise.reject(micError);
      return Promise.resolve(stream);
    });
    setMediaDevices({ getUserMedia });

    const { result } = renderHook(() => useShootCheck({ target: 'medium' }));
    await act(async () => {
      result.current.start();
    });

    await waitFor(() => expect(result.current.phase).toBe('active'));
    expect(result.current.errorMessage).toBeNull();
  });

  it('a microphone failure does not block the camera and is never reported as the camera being denied', async () => {
    const { stream } = fakeStream();
    const micError = Object.assign(new Error('no microphone'), { name: 'NotFoundError' });
    const getUserMedia = vi.fn().mockResolvedValueOnce(stream).mockRejectedValueOnce(micError);
    setMediaDevices({ getUserMedia });

    const { result } = renderHook(() => useShootCheck({ target: 'medium' }));
    await act(async () => {
      result.current.start();
    });

    await waitFor(() => expect(result.current.phase).toBe('active'));
    expect(result.current.errorMessage).toBeNull();
  });

  it('a camera failure is reported as denied and audio is never even requested', async () => {
    const error = Object.assign(new Error('Permission denied'), { name: 'NotAllowedError' });
    const getUserMedia = vi.fn().mockRejectedValueOnce(error);
    setMediaDevices({ getUserMedia });

    const { result } = renderHook(() => useShootCheck({ target: 'medium' }));
    await act(async () => {
      result.current.start();
    });

    await waitFor(() => expect(result.current.phase).toBe('denied'));
    expect(result.current.errorMessage).toContain('Camera access was denied');
    expect(getUserMedia).toHaveBeenCalledTimes(1);
  });
});
