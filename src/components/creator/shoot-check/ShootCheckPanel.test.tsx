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
 * The coach layout (2026-09-25): "Here's what I see", the steps in order (each with a kind chip and
 * the guide topic it comes from), "Looking good", "A photo can't show", and one coach question as
 * tap buttons. A tap re-checks the SAME still (no new capture) with the answers so far.
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
          {
            kind: 'move_you',
            text: 'Turn about 30-45 degrees towards the window',
            note: 'Soft natural window light',
            label: 'Soft window light',
          },
          {
            kind: 'settings',
            text: 'Lock focus and exposure on your face',
            note: 'Talking Head (Window light)',
            label: 'Talking head by a window',
          },
        ],
        cantTell: ['Whether there is a lamp in the room'],
        ask: ASK,
        lang: 'en',
        retake: false,
        ...overrides,
      },
    };
  }

  async function renderChecked(result: unknown, lang?: 'en-IN' | 'hi-IN') {
    checkFrameMock.mockResolvedValue(result);
    mockHook(readingsWithUnknowns());
    render(<ShootCheckPanel shots={SCRIPT} lang={lang} />);
    fireEvent.click(screen.getByRole('button', { name: 'Check my frame' }));
    await waitFor(() => expect(checkFrameMock).toHaveBeenCalledTimes(1));
  }

  it('shows what I see, the steps in order, what works, and what one photo cannot show', async () => {
    await renderChecked(coachResult());
    await waitFor(() => expect(screen.getByTestId('frame-check-coach')).toBeTruthy());

    expect(screen.getByTestId('frame-check-what-i-see').textContent).toBe('You at a desk, window to your left');
    const steps = screen.getAllByTestId('frame-check-step');
    expect(steps).toHaveLength(2);
    expect(steps[0].textContent).toContain('Turn about 30-45 degrees towards the window');
    expect(steps[1].textContent).toContain('Lock focus and exposure on your face');
    expect(screen.getByText('Background is tidy')).toBeTruthy();
    expect(screen.getByText("A photo can't show")).toBeTruthy();
    expect(screen.getByText('Whether there is a lamp in the room')).toBeTruthy();
    // The legacy groups are not shown on top of the coach layout.
    expect(screen.queryByText('Fix')).toBeNull();
    expect(screen.getByText('Is there another light you can use?')).toBeTruthy();
  });

  // Fix 1: the opening reads as the coach speaking, in full-strength text.
  it('opens with "Here\'s what I see" and the sentence in foreground text, not muted grey', async () => {
    await renderChecked(coachResult());
    await waitFor(() => expect(screen.getByText("Here's what I see")).toBeTruthy());
    expect(screen.queryByText('What I see')).toBeNull();
    const sentence = screen.getByTestId('frame-check-what-i-see');
    expect(sentence.className).toContain('text-foreground');
    expect(sentence.className).not.toContain('text-muted-foreground');
  });

  // Fix 2: the caption under a step names a guide topic, never an internal knowledge-row name.
  it('captions each step "From the guide: {label}", never "Influora\'s notes" or the raw row name', async () => {
    await renderChecked(coachResult());
    await waitFor(() => expect(screen.getAllByTestId('frame-check-step')).toHaveLength(2));
    const sources = screen.getAllByTestId('frame-check-step-source');
    expect(sources.map((s) => s.textContent)).toEqual([
      'From the guide: Soft window light',
      'From the guide: Talking head by a window',
    ]);
    expect(sources[0].querySelector('svg')?.getAttribute('aria-hidden')).toBe('true');
    const coach = screen.getByTestId('frame-check-coach');
    expect(coach.textContent).not.toMatch(/Influora/);
    expect(coach.textContent).not.toContain('Soft natural window light');
    expect(coach.textContent).not.toContain('Talking Head (Window light)');
  });

  // Fix 3: a settings step is a label/value list, with Pro-only parts under their own caption.
  it('shows a settings step as a label/value list, with the Pro video parts under their own caption', async () => {
    const settingsStep = {
      kind: 'settings',
      text: 'Lens: 1x Main. Distance: 0.8-1m. EV: +0.5. If your camera app has a Pro video mode: Shutter: 1/50.',
      note: 'Talking Head (Window light)',
      label: 'Talking head by a window',
      parts: [
        { label: 'Lens', value: '1x Main', needsPro: false },
        { label: 'Distance', value: '0.8-1m', needsPro: false },
        { label: 'EV', value: '+0.5', needsPro: false },
        { label: 'Shutter', value: '1/50', needsPro: true },
      ],
    };
    await renderChecked(
      coachResult({
        steps: [{ kind: 'move_phone', text: 'Raise the phone to eye height', note: 'Eye-level', label: 'Eye-level phone' }, settingsStep],
      })
    );
    await waitFor(() => expect(screen.getByTestId('frame-check-step-parts')).toBeTruthy());

    const steps = screen.getAllByTestId('frame-check-step');
    // The step without parts shows its text as before.
    expect(steps[0].textContent).toContain('Raise the phone to eye height');
    // The settings step shows its parts as a definition list, not the dense line.
    expect(steps[1].textContent).not.toContain('Lens: 1x Main.');
    const parts = screen.getByTestId('frame-check-step-parts');
    const terms = [...parts.querySelectorAll('dt')].map((dt) => dt.textContent);
    const values = [...parts.querySelectorAll('dd')].map((dd) => dd.textContent);
    expect(terms).toEqual(['Lens', 'Distance', 'EV', 'Shutter']);
    expect(values).toEqual(['1x Main', '0.8-1m', '+0.5', '1/50']);
    const pro = screen.getByTestId('frame-check-step-pro-parts');
    expect(pro.textContent).toContain('If your camera app has a Pro video mode');
    expect([...pro.querySelectorAll('dt')].map((dt) => dt.textContent)).toEqual(['Shutter']);
    // Long values may wrap instead of forcing a horizontal scroll at 375px.
    expect(parts.querySelector('dd')?.className).toContain('break-words');
  });

  it('shows no Pro caption when no part needs a Pro video mode', async () => {
    await renderChecked(
      coachResult({
        steps: [
          {
            kind: 'settings',
            text: 'Lens: 1x Main.',
            note: 'x',
            label: 'Talking head by a window',
            parts: [{ label: 'Lens', value: '1x Main', needsPro: false }],
          },
        ],
      })
    );
    await waitFor(() => expect(screen.getByTestId('frame-check-step-parts')).toBeTruthy());
    expect(screen.queryByTestId('frame-check-step-pro-parts')).toBeNull();
    expect(screen.queryByText('If your camera app has a Pro video mode')).toBeNull();
    expect(screen.queryByTestId('frame-check-step-ois-parts')).toBeNull();
  });

  // No saved phone: a part that holds only with OIS is conditional, never stated as fact.
  it('puts an OIS-only part under its own caption, in the reply language, never in the plain list', async () => {
    const parts = [
      { label: 'Lens', value: '1x Main', needsPro: false },
      { label: 'FPS', value: '25', needsPro: true },
      { label: 'Phone steady', value: 'OIS only', needsPro: false, needsOis: true },
    ];
    await renderChecked(
      coachResult({ steps: [{ kind: 'settings', text: 'Lens: 1x Main.', note: 'x', label: 'Khaane ka close-up', parts }], lang: 'hi' })
    );
    await waitFor(() => expect(screen.getByTestId('frame-check-step-ois-parts')).toBeTruthy());
    const ois = screen.getByTestId('frame-check-step-ois-parts');
    expect(ois.textContent).toContain('Agar aapke phone mein OIS (optical stabilisation) hai');
    expect([...ois.querySelectorAll('dt')].map((dt) => dt.textContent)).toEqual(['Phone steady']);
    const pro = screen.getByTestId('frame-check-step-pro-parts');
    expect([...pro.querySelectorAll('dt')].map((dt) => dt.textContent)).toEqual(['FPS']);
    // The plain list (the first <dl>, outside both captions) holds only the unconditional part.
    const plain = screen.getByTestId('frame-check-step-parts').querySelector(':scope > dl');
    expect([...(plain?.querySelectorAll('dt') ?? [])].map((dt) => dt.textContent)).toEqual(['Lens']);
  });

  // Fix 4: each step says what it is about, with a numbered <ol> kept.
  it('gives each numbered step a kind chip: You, Phone, Light, Settings', async () => {
    await renderChecked(
      coachResult({
        steps: [
          { kind: 'move_you', text: 'Step one', note: 'a', label: 'A' },
          { kind: 'move_phone', text: 'Step two', note: 'b', label: 'B' },
          { kind: 'move_light', text: 'Step three', note: 'c', label: 'C' },
          { kind: 'settings', text: 'Step four', note: 'd', label: 'D' },
        ],
      })
    );
    await waitFor(() => expect(screen.getAllByTestId('frame-check-step')).toHaveLength(4));
    expect(screen.getByText('Try this, in order')).toBeTruthy();
    expect(screen.getByTestId('frame-check-steps').tagName).toBe('OL');
    const chips = screen.getAllByTestId('frame-check-step-kind');
    expect(chips.map((c) => c.textContent)).toEqual(['You', 'Phone', 'Light', 'Settings']);
    for (const chip of chips) expect(chip.querySelector('svg')?.getAttribute('aria-hidden')).toBe('true');
    const steps = screen.getAllByTestId('frame-check-step');
    expect(steps.map((s) => s.textContent?.charAt(0))).toEqual(['1', '2', '3', '4']);
  });

  // Fix 5: visible markers in the strong foreground colours, not a pale 6px dot.
  it('marks "Looking good" items with a check in the strong success colour, and unknowns with a muted help icon', async () => {
    await renderChecked(coachResult({ ok: ['Background is tidy', 'Framing is chest up'] }));
    const okList = await screen.findByTestId('frame-check-ok');
    // (The live reading rows above also say "Looking good", so the heading is read in its block.)
    expect(okList.querySelector('h3')?.textContent).toBe('Looking good');
    const okIcons = okList.querySelectorAll('li svg');
    expect(okIcons).toHaveLength(2);
    for (const icon of okIcons) {
      expect(icon.getAttribute('class')).toContain('text-success-foreground');
      expect(icon.getAttribute('aria-hidden')).toBe('true');
    }
    expect(okList.querySelector('.bg-success')).toBeNull();

    const unknownIcons = screen.getByTestId('frame-check-cant-tell').querySelectorAll('li svg');
    expect(unknownIcons).toHaveLength(1);
    expect(unknownIcons[0].getAttribute('class')).toContain('text-muted-foreground');
  });

  // Fix 6: an unusable photo is a retake prompt, not "Here's what I see" over "retake".
  it('shows a retake box whose "Check again" takes and checks a new photo, with no "Here\'s what I see"', async () => {
    const { captureDownscaledJpeg } = await import('@/lib/shoot-check/capture-frame');
    const capture = vi.mocked(captureDownscaledJpeg);
    capture.mockClear();
    checkFrameMock
      .mockResolvedValueOnce(
        coachResult({
          whatISee: "It's too dark to see you. Turn on a light and retake the photo.",
          steps: [],
          ok: [],
          cantTell: [],
          ask: null,
          retake: true,
        })
      )
      .mockResolvedValueOnce(coachResult());
    mockHook(readingsWithUnknowns());
    render(<ShootCheckPanel shots={SCRIPT} />);

    fireEvent.click(screen.getByRole('button', { name: 'Check my frame' }));
    const box = await screen.findByTestId('frame-check-retake');
    expect(box.className).toContain('bg-warning');
    expect(box.className).toContain('text-warning-foreground');
    expect(box.textContent).toContain("It's too dark to see you");
    expect(box.querySelector('svg')?.getAttribute('aria-hidden')).toBe('true');
    expect(screen.queryByText("Here's what I see")).toBeNull();
    expect(screen.queryByTestId('frame-check-coach')).toBeNull();

    const again = screen.getByRole('button', { name: 'Check again' });
    expect(again.className).toContain('min-h-11');
    fireEvent.click(again);
    await waitFor(() => expect(checkFrameMock).toHaveBeenCalledTimes(2));
    // The same action as "Check my frame": a NEW photo is taken.
    expect(capture).toHaveBeenCalledTimes(2);
    await waitFor(() => expect(screen.getByText("Here's what I see")).toBeTruthy());
    expect(screen.queryByTestId('frame-check-retake')).toBeNull();
  });

  it('announces results politely and, after "Check again", moves focus to the new result instead of <body>', async () => {
    const retake = coachResult({
      whatISee: "It's too dark to see you. Turn on a light and retake the photo.",
      steps: [],
      ok: [],
      cantTell: [],
      ask: null,
      retake: true,
    });
    checkFrameMock.mockResolvedValueOnce(retake).mockResolvedValueOnce(retake).mockResolvedValueOnce(coachResult());
    mockHook(readingsWithUnknowns());
    render(<ShootCheckPanel shots={SCRIPT} />);

    const region = screen.getByTestId('frame-check-result');
    expect(region.getAttribute('aria-live')).toBe('polite');

    const main = screen.getByRole('button', { name: 'Check my frame' });
    main.focus();
    fireEvent.click(main);
    await screen.findByTestId('frame-check-retake');
    // A check from the main button leaves focus where the creator put it.
    expect(region.contains(screen.getByTestId('frame-check-retake'))).toBe(true);
    expect(document.activeElement).not.toBe(screen.getByRole('button', { name: 'Check again' }));

    // Still unusable: focus lands on the NEW retake box's own button.
    let again = screen.getByRole('button', { name: 'Check again' });
    again.focus();
    fireEvent.click(again);
    await waitFor(() => expect(checkFrameMock).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Check again' })));

    // Usable now: focus lands on the result's first heading.
    again = screen.getByRole('button', { name: 'Check again' });
    fireEvent.click(again);
    await waitFor(() => expect(checkFrameMock).toHaveBeenCalledTimes(3));
    await waitFor(() => expect(document.activeElement?.textContent).toBe("Here's what I see"));
    expect(document.activeElement?.tagName).toBe('H3');
    expect(document.activeElement?.getAttribute('tabindex')).toBe('-1');
  });

  it('after "Check again" fails, focus goes back to "Check my frame", never <body>', async () => {
    checkFrameMock
      .mockResolvedValueOnce(
        coachResult({ whatISee: 'Too dark to see you.', steps: [], ok: [], cantTell: [], ask: null, retake: true })
      )
      .mockResolvedValueOnce({ kind: 'unavailable' });
    mockHook(readingsWithUnknowns());
    render(<ShootCheckPanel shots={SCRIPT} />);
    fireEvent.click(screen.getByRole('button', { name: 'Check my frame' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Check again' }));
    await waitFor(() => expect(checkFrameMock).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Check my frame' })));
  });

  it('reads an older server\'s unusable photo (only what_i_see, no retake field) as a retake too', async () => {
    await renderChecked({
      kind: 'ok',
      result: {
        fixes: [],
        settings: [],
        ok: [],
        whatISee: 'The lens looks covered. Please retake the photo.',
        steps: [],
        cantTell: [],
        ask: null,
      },
    });
    const box = await screen.findByTestId('frame-check-retake');
    expect(box.textContent).toContain('The lens looks covered');
    expect(screen.getByRole('button', { name: 'Check again' })).toBeTruthy();
    expect(screen.queryByText("Here's what I see")).toBeNull();
  });

  it('a result that says retake: false is never a retake box, even with only what_i_see', async () => {
    await renderChecked(coachResult({ steps: [], ok: [], cantTell: [], ask: null, retake: false }));
    await waitFor(() => expect(screen.getByText("Here's what I see")).toBeTruthy());
    expect(screen.queryByTestId('frame-check-retake')).toBeNull();
  });

  it('says "Dobara check karo" on the retake button for a Hinglish reply', async () => {
    await renderChecked(
      coachResult({ whatISee: 'Photo bahut dark hai, dobara lo.', steps: [], ok: [], cantTell: [], ask: null, retake: true, lang: 'hi' })
    );
    await screen.findByTestId('frame-check-retake');
    expect(screen.getByRole('button', { name: 'Dobara check karo' })).toBeTruthy();
  });

  // Fix 7: the reply's own language wins over the panel's language setting, for every word.
  it('a Hinglish reply gets Hinglish headings, chips, captions and ask even when the panel is English', async () => {
    await renderChecked(
      coachResult({
        lang: 'hi',
        whatISee: 'Aap desk pe ho, window left mein hai',
        steps: [
          { kind: 'move_you', text: 'Window ki taraf thoda ghoomo', note: 'Soft natural window light', label: 'Window ki soft light' },
          {
            kind: 'settings',
            text: 'Lens: 1x Main.',
            note: 'x',
            label: 'Window ke paas talking head',
            parts: [
              { label: 'Lens', value: '1x Main', needsPro: false },
              { label: 'Shutter', value: '1/50', needsPro: true },
            ],
          },
        ],
      }),
      'en-IN'
    );
    await waitFor(() => expect(screen.getByText('Mujhe yeh dikh raha hai')).toBeTruthy());
    expect(screen.getByText('Yeh karke dekho, is order mein')).toBeTruthy();
    expect(screen.getByText('Yeh sahi hai')).toBeTruthy();
    expect(screen.getByText('Photo se pata nahi chalta')).toBeTruthy();
    expect(screen.getAllByTestId('frame-check-step-kind').map((c) => c.textContent)).toEqual(['Aap', 'Settings']);
    expect(screen.getAllByTestId('frame-check-step-source')[0].textContent).toBe('Guide se: Window ki soft light');
    expect(screen.getByText('Agar aapke camera app mein Pro video mode hai')).toBeTruthy();
    expect(screen.getByText('Koi aur light hai jo use kar sakte ho?')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Lamp' })).toBeTruthy();
    // Scoped to the coach block: the live reading rows above say "Looking good" in the app's own copy.
    const coachText = screen.getByTestId('frame-check-coach').textContent ?? '';
    for (const english of ["Here's what I see", 'Try this, in order', 'Looking good', "A photo can't show", 'From the guide']) {
      expect(coachText).not.toContain(english);
    }
    expect(screen.queryByText('Is there another light you can use?')).toBeNull();
  });

  it('an English reply gets English headings even when the panel is set to Hindi', async () => {
    await renderChecked(coachResult({ lang: 'en' }), 'hi-IN');
    await waitFor(() => expect(screen.getByText("Here's what I see")).toBeTruthy());
    expect(screen.getByText('Is there another light you can use?')).toBeTruthy();
    expect(screen.queryByText('Mujhe yeh dikh raha hai')).toBeNull();
  });

  it('"You told me" follows the reply language too', async () => {
    checkFrameMock
      .mockResolvedValueOnce(coachResult({ lang: 'en' }))
      .mockResolvedValueOnce(coachResult({ lang: 'hi', ask: null }));
    mockHook(readingsWithUnknowns());
    render(<ShootCheckPanel shots={SCRIPT} />);

    fireEvent.click(screen.getByRole('button', { name: 'Check my frame' }));
    fireEvent.click(await screen.findByRole('button', { name: 'A lamp' }));
    await waitFor(() => expect(screen.getByTestId('frame-check-answers').textContent).toContain('Aapne bataya'));
    expect(screen.getByTestId('frame-check-answers').textContent).toContain('Lamp');
  });

  it('an older server body without lang, retake, label or parts still renders, falling back to the note and the panel language', async () => {
    await renderChecked({
      kind: 'ok',
      result: {
        fixes: ['Turn towards the window'],
        settings: ['Lens: 1x Main. Distance: 0.8-1m.'],
        ok: ['Background is tidy'],
        whatISee: 'You at a desk, window to your left',
        steps: [
          { kind: 'move_you', text: 'Turn towards the window', note: 'Soft natural window light' },
          { kind: 'settings', text: 'Lens: 1x Main. Distance: 0.8-1m.', note: 'Talking Head (Window light)' },
        ],
        cantTell: [],
        ask: null,
      },
    });
    await waitFor(() => expect(screen.getByText("Here's what I see")).toBeTruthy());
    expect(screen.queryByTestId('frame-check-retake')).toBeNull();
    const steps = screen.getAllByTestId('frame-check-step');
    expect(steps[1].textContent).toContain('Lens: 1x Main. Distance: 0.8-1m.');
    expect(screen.queryByTestId('frame-check-step-parts')).toBeNull();
    expect(screen.getAllByTestId('frame-check-step-source')[0].textContent).toBe(
      'From the guide: Soft natural window light'
    );
  });

  it('with no lang from the server, a Hindi creator still gets Hinglish headings', async () => {
    await renderChecked(coachResult({ lang: undefined }), 'hi-IN');
    await waitFor(() => expect(screen.getByText('Mujhe yeh dikh raha hai')).toBeTruthy());
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
          steps: [{ kind: 'move_light', text: 'Put the lamp on your right', note: 'Lamp as key light', label: 'Lamp as your main light' }],
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

  it('asks in Hinglish for a Hindi creator when the server does not say the reply language', async () => {
    checkFrameMock.mockResolvedValue(coachResult({ lang: null }));
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
    expect(screen.queryByTestId('frame-check-retake')).toBeNull();
  });
});
