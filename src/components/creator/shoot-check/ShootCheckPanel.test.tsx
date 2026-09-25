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

  /**
   * DPDP / consent: "Check my frame" is the only thing on this screen that leaves the phone. The
   * copy used to say just "nothing is saved", which was true but let a creator read it as "the
   * photo stays on my device". The disclosure must sit next to the button, in their language.
   */
  it('tells the creator, next to the button, that the photo is sent to the AI', () => {
    mockHook(readingsWithUnknowns());
    render(<ShootCheckPanel shots={SCRIPT} />);

    const disclosure = screen.getByTestId('frame-check-disclosure');
    expect(disclosure.textContent).toContain('sends one photo');
    expect(disclosure.textContent).toMatch(/AI/);
    expect(disclosure.textContent).toMatch(/never saved/);
    // and it is beside the button, not buried elsewhere on the page
    const button = screen.getByRole('button', { name: 'Check my frame' });
    expect(button.parentElement?.contains(disclosure)).toBe(true);
  });

  it('shows the same disclosure in Hindi for a Hindi creator', () => {
    mockHook(readingsWithUnknowns());
    render(<ShootCheckPanel shots={SCRIPT} lang="hi-IN" />);

    const disclosure = screen.getByTestId('frame-check-disclosure');
    expect(disclosure.textContent).toContain('फ़ोटो');
    expect(disclosure.textContent).toContain('सेव नहीं');
  });

  it('does not claim the live preview is uploaded - only the frame check is', () => {
    mockHook(readingsWithUnknowns());
    render(<ShootCheckPanel shots={SCRIPT} />);

    expect(screen.getByText(/Nothing from this preview is uploaded or saved/)).toBeTruthy();
  });
});

/**
 * The coach layout (2026-09-25): "What I see", the steps in order with the Influora note each
 * comes from, "Can't tell from this photo", and one coach question as tap buttons in the
 * creator's language. A tap re-checks the SAME still (no new capture) with the answers so far.
 */
describe('the coach result', () => {
  const ASK = {
    id: 'other_light',
    questionEn: 'Is there another light you can use?',
    questionHi: 'Koi aur light hai jo use kar sakte ho?',
    options: [
      { en: 'A lamp', hi: 'Lamp' },
      { en: 'Nothing else', hi: 'Aur kuch nahi' },
    ],
  };

  function coachResult(overrides: Record<string, unknown> = {}) {
    return {
      kind: 'ok',
      result: {
        fixes: ['Turn about 30-45 degrees towards the window'],
        settings: ['Lock focus and exposure on your face'],
        ok: ['Background is tidy'],
        whatISee: 'You at a desk, window to your left',
        steps: [
          { kind: 'move_you', text: 'Turn about 30-45 degrees towards the window', note: 'Soft natural window light' },
          { kind: 'settings', text: 'Lock focus and exposure on your face', note: 'Talking Head (Window light)' },
        ],
        cantTell: ['Whether there is a lamp in the room'],
        ask: ASK,
        ...overrides,
      },
    };
  }

  it('shows what I see, the steps in order with their notes, what works, and what one photo cannot show', async () => {
    checkFrameMock.mockResolvedValue(coachResult());
    mockHook(readingsWithUnknowns());
    render(<ShootCheckPanel shots={SCRIPT} />);

    fireEvent.click(screen.getByRole('button', { name: 'Check my frame' }));
    await waitFor(() => expect(screen.getByTestId('frame-check-coach')).toBeTruthy());

    expect(screen.getByText('What I see')).toBeTruthy();
    expect(screen.getByTestId('frame-check-what-i-see').textContent).toBe('You at a desk, window to your left');
    const steps = screen.getAllByTestId('frame-check-step');
    expect(steps).toHaveLength(2);
    expect(steps[0].textContent).toContain('Turn about 30-45 degrees towards the window');
    expect(steps[0].textContent).toContain('from Influora’s notes: Soft natural window light');
    expect(steps[1].textContent).toContain('Lock focus and exposure on your face');
    expect(screen.getByText('Background is tidy')).toBeTruthy();
    expect(screen.getByText('Can’t tell from this photo')).toBeTruthy();
    expect(screen.getByText('Whether there is a lamp in the room')).toBeTruthy();
    // The legacy groups are not shown on top of the coach layout.
    expect(screen.queryByText('Fix')).toBeNull();
    expect(screen.getByText('Is there another light you can use?')).toBeTruthy();
  });

  it('sends the current shot as shot_context (its label as the planned line, plus its set-up)', async () => {
    checkFrameMock.mockResolvedValue(coachResult());
    mockHook(readingsWithUnknowns());
    const shots: ShootCheckShot[] = [
      { ...SCRIPT[0], context: { angle: 'eye level', where: 'bedroom desk', light: 'window on your left' } },
      SCRIPT[1],
    ];
    render(<ShootCheckPanel shots={shots} />);

    fireEvent.click(screen.getByRole('button', { name: 'Check my frame' }));
    await waitFor(() => expect(checkFrameMock).toHaveBeenCalledTimes(1));
    const [, label, role, extras] = checkFrameMock.mock.calls[0];
    expect(label).toBe('medium: talking head');
    expect(role).toBe('creator');
    expect(extras.shotContext).toEqual({
      line: 'medium: talking head',
      angle: 'eye level',
      where: 'bedroom desk',
      light: 'window on your left',
    });
    expect(extras.answers).toEqual([]);
  });

  it('tapping an answer re-checks the SAME still with that answer, and never captures a new photo', async () => {
    const { captureDownscaledJpeg } = await import('@/lib/shoot-check/capture-frame');
    const capture = vi.mocked(captureDownscaledJpeg);
    capture.mockClear();
    checkFrameMock
      .mockResolvedValueOnce(coachResult())
      .mockResolvedValueOnce(
        coachResult({
          steps: [{ kind: 'move_light', text: 'Put the lamp on your right', note: 'Lamp as key light' }],
          ask: null,
        })
      );
    mockHook(readingsWithUnknowns());
    render(<ShootCheckPanel shots={SCRIPT} />);

    fireEvent.click(screen.getByRole('button', { name: 'Check my frame' }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'A lamp' })).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: 'A lamp' }));

    await waitFor(() => expect(checkFrameMock).toHaveBeenCalledTimes(2));
    expect(capture).toHaveBeenCalledTimes(1);
    const firstBlob = checkFrameMock.mock.calls[0][0];
    const secondBlob = checkFrameMock.mock.calls[1][0];
    expect(secondBlob).toBe(firstBlob);
    expect(checkFrameMock.mock.calls[1][3].answers).toEqual([{ id: 'other_light', option: 0 }]);

    await waitFor(() => expect(screen.getByText('Put the lamp on your right')).toBeTruthy());
    // The answer is shown back, and the answered question is not asked again.
    expect(screen.getByTestId('frame-check-answers').textContent).toContain('A lamp');
    expect(screen.queryByTestId('frame-check-ask')).toBeNull();
  });

  it('keeps at most 3 answers and stops asking once 3 are given', async () => {
    const asks = ['other_light', 'can_move', 'room_size', 'window_side'].map((id) => ({ ...ASK, id, questionEn: `Q ${id}` }));
    checkFrameMock
      .mockResolvedValueOnce(coachResult({ ask: asks[0] }))
      .mockResolvedValueOnce(coachResult({ ask: asks[1] }))
      .mockResolvedValueOnce(coachResult({ ask: asks[2] }))
      .mockResolvedValueOnce(coachResult({ ask: asks[3] }));
    mockHook(readingsWithUnknowns());
    render(<ShootCheckPanel shots={SCRIPT} />);

    fireEvent.click(screen.getByRole('button', { name: 'Check my frame' }));
    for (let n = 0; n < 3; n++) {
      await waitFor(() => expect(screen.getByText(`Q ${asks[n].id}`)).toBeTruthy());
      fireEvent.click(screen.getByRole('button', { name: 'Nothing else' }));
      await waitFor(() => expect(checkFrameMock).toHaveBeenCalledTimes(n + 2));
    }

    expect(checkFrameMock.mock.calls[3][3].answers).toEqual([
      { id: 'other_light', option: 1 },
      { id: 'can_move', option: 1 },
      { id: 'room_size', option: 1 },
    ]);
    await waitFor(() => expect(screen.getByTestId('frame-check-coach')).toBeTruthy());
    // A 4th question comes back, but 3 answers is the most per plan: no buttons.
    expect(screen.queryByTestId('frame-check-ask')).toBeNull();
  });

  it('asks in Hinglish for a Hindi creator', async () => {
    checkFrameMock.mockResolvedValue(coachResult());
    mockHook(readingsWithUnknowns());
    render(<ShootCheckPanel shots={SCRIPT} lang="hi-IN" />);

    fireEvent.click(screen.getByRole('button', { name: 'Check my frame' }));
    await waitFor(() => expect(screen.getByText('Koi aur light hai jo use kar sakte ho?')).toBeTruthy());
    expect(screen.getByRole('button', { name: 'Lamp' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Aur kuch nahi' })).toBeTruthy();
    expect(screen.queryByText('Is there another light you can use?')).toBeNull();
  });

  it('an older server response (only fixes/settings/ok) still shows the Fix/Settings/Looking good groups', async () => {
    checkFrameMock.mockResolvedValue({
      kind: 'ok',
      result: { fixes: ['Move closer'], settings: ['Grid on'], ok: ['Good light'], whatISee: null, steps: [], cantTell: [], ask: null },
    });
    mockHook(readingsWithUnknowns());
    render(<ShootCheckPanel shots={SCRIPT} />);

    fireEvent.click(screen.getByRole('button', { name: 'Check my frame' }));
    await waitFor(() => expect(screen.getByText('Move closer')).toBeTruthy());
    expect(screen.getByText('Fix')).toBeTruthy();
    expect(screen.getByText('Settings')).toBeTruthy();
    expect(screen.queryByTestId('frame-check-coach')).toBeNull();
  });
});
