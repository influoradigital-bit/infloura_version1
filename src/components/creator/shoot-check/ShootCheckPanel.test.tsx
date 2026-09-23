/**
 * Regression tests for three bugs found by running the panel against a SYNTHETIC camera in a real
 * browser (a canvas `captureStream`), which no unit test had covered:
 *
 * 1. The header read "Shot 2 of 3" on the first shot, because it trusted the caller's `index`
 *    field instead of the position in the script array.
 * 2. The tilt row rendered the FOCUS sentence ("Too dark to check focus") on any phone with no
 *    orientation sensor, because focus and tilt shared one `unknown` copy key.
 * 3. The mic row reported "Room noise is drowning your voice" when the stream carried NO audio
 *    track at all — a confident verdict on nothing measured.
 *
 * The hook is mocked here: these are the panel's rendering rules. The measurement maths lives in
 * `src/lib/shoot-check/metrics.test.ts`, and the device path itself is still only provable on real
 * hardware.
 */
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { ShootCheckReadings } from '@/hooks/useShootCheck';

const { useShootCheckMock } = vi.hoisted(() => ({ useShootCheckMock: vi.fn() }));
vi.mock('@/hooks/useShootCheck', () => ({
  useShootCheck: useShootCheckMock,
  SAMPLE_INTERVAL_MS: 250,
  SPEECH_THROTTLE_MS: 3000,
  INACTIVITY_TIMEOUT_MS: 120_000,
}));

const { checkFrameMock } = vi.hoisted(() => ({ checkFrameMock: vi.fn() }));
vi.mock('@/lib/meera-api', () => ({ meeraApi: { checkFrame: checkFrameMock } }));

vi.mock('@/lib/shoot-check/capture-frame', () => ({
  captureDownscaledJpeg: vi.fn().mockResolvedValue(new Blob(['x'], { type: 'image/jpeg' })),
}));

import { ShootCheckPanel, type ShootCheckShot } from './ShootCheckPanel';

/** Readings shaped like a live session on a device with no tilt sensor and no audio track. */
function readingsWithUnknowns(): ShootCheckReadings {
  return {
    light: { luma: 52, status: 'ok', advice: 'ok', text: 'Looking good' },
    focus: { sharpness: 900, status: 'ok', advice: 'ok', text: 'Looking good' },
    framing: { status: 'ok', advice: 'ok', text: 'Looking good' },
    tilt: {
      degrees: 'unknown',
      status: 'unknown',
      advice: 'unknown',
      text: 'This phone doesn’t report tilt — check the edges of the frame look level',
    },
    background: { clutter: 10, status: 'ok', text: 'Looking good' },
    mic: {
      levelDb: 'unknown',
      noiseFloorDb: 'unknown',
      status: 'unknown',
      advice: 'unknown',
      text: 'No microphone reading — check your sound on a test recording',
    },
  };
}

function mockHook(readings: ShootCheckReadings | null): void {
  useShootCheckMock.mockReturnValue({
    supported: true,
    phase: readings ? 'active' : 'idle',
    errorMessage: null,
    readings,
    // Truthy so `handleCheckFrame` (guarded on `videoRef.current`) actually runs — the specific
    // element shape doesn't matter, `captureDownscaledJpeg` is mocked below.
    videoRef: { current: document.createElement('video') },
    start: vi.fn(),
    stop: vi.fn(),
    muted: false,
    setMuted: vi.fn(),
    noteInteraction: vi.fn(),
  });
}

/** A script numbering its own beats from 1, the way a Meera script does. */
const SCRIPT: ShootCheckShot[] = [
  { index: 1, label: 'medium: talking head', seconds: 15, target: 'medium' },
  { index: 2, label: 'overhead: hands only', seconds: 15, target: 'hands-overhead' },
  { index: 3, label: 'extreme close-up', seconds: 10, target: 'closeup' },
];

beforeEach(() => {
  useShootCheckMock.mockReset();
  checkFrameMock.mockReset();
});

describe('shot numbering', () => {
  it('numbers the FIRST shot as 1, even when the script numbers its beats from 1', () => {
    mockHook(readingsWithUnknowns());
    render(<ShootCheckPanel shots={SCRIPT} />);
    expect(screen.getByText(/Shot 1 of 3/)).toBeTruthy();
    expect(screen.queryByText(/Shot 2 of 3/)).toBeNull();
  });

  it('numbers a zero-based script the same way', () => {
    mockHook(readingsWithUnknowns());
    const zeroBased = SCRIPT.map((shot, i) => ({ ...shot, index: i }));
    render(<ShootCheckPanel shots={zeroBased} />);
    expect(screen.getByText(/Shot 1 of 3/)).toBeTruthy();
  });
});

describe('an unknown reading is never a verdict', () => {
  it('explains the tilt sensor instead of borrowing the focus sentence', () => {
    mockHook(readingsWithUnknowns());
    render(<ShootCheckPanel shots={SCRIPT} />);
    expect(screen.getByText(/doesn’t report tilt/)).toBeTruthy();
    // The focus copy must not appear anywhere while focus itself reads ok.
    expect(screen.queryByText(/Too dark to check focus/)).toBeNull();
  });

  it('says there is no microphone reading rather than blaming the room', () => {
    mockHook(readingsWithUnknowns());
    render(<ShootCheckPanel shots={SCRIPT} />);
    expect(screen.getByText(/No microphone reading/)).toBeTruthy();
    expect(screen.queryByText(/drowning your voice/)).toBeNull();
    expect(screen.queryByText(/dB gap/)).toBeNull();
  });
});

describe('before the camera starts', () => {
  it('still shows advice as text rather than a blank panel', () => {
    mockHook(null);
    render(<ShootCheckPanel shots={SCRIPT} />);
    expect(screen.getByText(/Light/)).toBeTruthy();
    expect(screen.getByText(/Mic/)).toBeTruthy();
  });
});

/**
 * C5 — a frame check's result used to be applied unconditionally whenever the request resolved,
 * with no check that the creator was still looking at the same shot. `goToShot` already resets
 * `frameCheck` to idle on Next/Previous, but a check started on shot 1 that only RESOLVES after
 * the creator has already moved to shot 2 was applied afterwards anyway, clobbering that reset
 * and showing shot 1's fixes under shot 2's header.
 */
describe('a frame check belongs to the shot it was taken for', () => {
  it('drops a frame-check result that resolves after the creator already moved to the next shot', async () => {
    let resolveCheck!: (value: unknown) => void;
    checkFrameMock.mockReturnValue(
      new Promise((resolve) => {
        resolveCheck = resolve;
      })
    );
    mockHook(readingsWithUnknowns());
    render(<ShootCheckPanel shots={SCRIPT} />);

    fireEvent.click(screen.getByRole('button', { name: 'Check my frame' }));
    await waitFor(() => expect(checkFrameMock).toHaveBeenCalledTimes(1));

    fireEvent.click(screen.getByRole('button', { name: 'Next shot' }));
    expect(screen.getByText(/Shot 2 of 3/)).toBeTruthy();

    await act(async () => {
      resolveCheck({ kind: 'ok', result: { fixes: ['Stale fix from shot 1'], settings: [], ok: [] } });
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(screen.queryByText('Stale fix from shot 1')).toBeNull();
    // Still on shot 2, still showing the idle "Check my frame" button, not a stray result.
    expect(screen.getByText(/Shot 2 of 3/)).toBeTruthy();
  });

  it('a result that resolves before any shot change is shown normally', async () => {
    checkFrameMock.mockResolvedValue({ kind: 'ok', result: { fixes: ['Move closer'], settings: [], ok: [] } });
    mockHook(readingsWithUnknowns());
    render(<ShootCheckPanel shots={SCRIPT} />);

    fireEvent.click(screen.getByRole('button', { name: 'Check my frame' }));
    await waitFor(() => expect(screen.getByText('Move closer')).toBeTruthy());
  });
});

/**
 * C4 — influora-ai returns HTTP 200 with `fallback: true` on every failure and gate-block path,
 * `fixes` filled with placeholder text (never a real check). `meeraApi.checkFrame` now turns that
 * into `{kind: 'unavailable'}` (or `{kind: 'capped', message}` for the one case with something to
 * say), so the panel must never render a fallback body as a real "Fix" card.
 */
describe('a fallback response from influora-ai is never shown as a real check result', () => {
  it('shows the generic "could not check" line, not a Fix card, for an ordinary fallback', async () => {
    checkFrameMock.mockResolvedValue({ kind: 'unavailable' });
    mockHook(readingsWithUnknowns());
    render(<ShootCheckPanel shots={SCRIPT} />);

    fireEvent.click(screen.getByRole('button', { name: 'Check my frame' }));
    await waitFor(() => expect(screen.getByText(/Couldn.t check your frame right now/)).toBeTruthy());
    expect(screen.queryByText('Fix')).toBeNull();
  });

  it('shows the creator-cap message, not a Fix card, when the cap fallback comes back', async () => {
    checkFrameMock.mockResolvedValue({
      kind: 'capped',
      message: "You've reached your monthly Meera usage limit. It resets on the 1st of next month.",
    });
    mockHook(readingsWithUnknowns());
    render(<ShootCheckPanel shots={SCRIPT} />);

    fireEvent.click(screen.getByRole('button', { name: 'Check my frame' }));
    await waitFor(() => expect(screen.getByText(/monthly Meera usage limit/)).toBeTruthy());
    expect(screen.queryByText('Fix')).toBeNull();
    expect(screen.queryByText(/Couldn.t check your frame right now/)).toBeNull();
  });
});
