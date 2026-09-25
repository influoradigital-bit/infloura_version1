/**
 * CoachResult, moved out of ShootCheckPanel.tsx so the chat can show it as a Meera message
 * (MeeraPhotoCheckMessage), fed with the REAL influora-ai bodies in
 * `src/lib/__fixtures__/shoot-check-frame-bodies.json` through the app's one parser
 * (`parseShootCheckFrameBody`), never hand-built objects.
 *
 * Three things are proved here:
 * 1. The card renders each fixture body's content (steps, chips, settings parts under the right
 *    captions, the question, the retake box) in the reply's language.
 * 2. The card the old page renders and the card the chat renders are the same markup, byte for byte.
 * 3. MeeraPhotoCheckMessage's live/older/busy rules: only the newest card can answer its question.
 *
 * Run: npx vitest run src/components/creator/shoot-check/CoachResult.test.tsx
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { ShootCheckReadings } from '@/hooks/useShootCheck';

const { useShootCheckMock } = vi.hoisted(() => ({ useShootCheckMock: vi.fn() }));
vi.mock('@/hooks/useShootCheck', () => ({ useShootCheck: useShootCheckMock }));

const { checkFrameMock } = vi.hoisted(() => ({ checkFrameMock: vi.fn() }));
vi.mock('@/lib/meera-api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/meera-api')>()),
  meeraApi: { checkFrame: checkFrameMock },
}));

vi.mock('@/lib/shoot-check/capture-frame', () => ({
  captureDownscaledJpeg: vi.fn().mockResolvedValue(new Blob(['x'], { type: 'image/jpeg' })),
  MAX_FRAME_WIDTH: 800,
  FRAME_JPEG_QUALITY: 0.7,
}));

import { parseShootCheckFrameBody, type MeeraShootCheckFrameResult } from '@/lib/meera-api';
import { MeeraPhotoCheckMessage } from '@/components/creator/meera/MeeraPhotoCheckMessage';
import {
  CoachResult,
  MAX_COACH_ANSWERS,
  hasCoachShape,
  needsRetake,
  replyLangFor,
  shootCheckLangFor,
  type CoachAnswer,
} from './CoachResult';
import { ShootCheckPanel } from './ShootCheckPanel';

type RawPart = { label: string; value: string; needs_pro: boolean; needs_ois?: boolean };
type RawStep = { kind: string; text: string; parts?: RawPart[] };
type RawBody = Record<string, unknown> & { steps: RawStep[]; lang: 'en' | 'hi'; retake: boolean; what_i_see: string };

const BODIES = JSON.parse(
  readFileSync(join(process.cwd(), 'src', 'lib', '__fixtures__', 'shoot-check-frame-bodies.json'), 'utf-8')
) as Record<string, RawBody>;

const parsed = (key: string): MeeraShootCheckFrameResult => parseShootCheckFrameBody(BODIES[key]);

const noop = () => {};

function renderCoach(result: MeeraShootCheckFrameResult, overrides: Partial<Parameters<typeof CoachResult>[0]> = {}) {
  return render(
    <CoachResult
      result={result}
      answers={[]}
      lang="en-IN"
      canAnswer
      onAnswer={noop}
      canCheckAgain
      onCheckAgain={noop}
      {...overrides}
    />
  );
}

beforeEach(() => {
  useShootCheckMock.mockReset();
  checkFrameMock.mockReset();
});

describe('the fixture bodies render as the coach card', () => {
  it('has the four real cases', () => {
    expect(Object.keys(BODIES).sort()).toEqual([
      'bedroom_window_behind_en_a78',
      'kitchen_tube_light_hi_no_phone',
      'park_sun_en_find_x8_ultra',
      'too_dark_en',
    ]);
  });

  it('bedroom (English): what I see, three numbered steps with You/Phone/Settings chips, parts as a list, the question as chips', () => {
    const body = BODIES.bedroom_window_behind_en_a78;
    renderCoach(parsed('bedroom_window_behind_en_a78'));

    expect(screen.getByText("Here's what I see")).toBeInTheDocument();
    expect(screen.getByTestId('frame-check-what-i-see').textContent).toBe(body.what_i_see);
    const steps = screen.getAllByTestId('frame-check-step');
    expect(steps).toHaveLength(3);
    expect(steps.map((s) => s.textContent?.charAt(0))).toEqual(['1', '2', '3']);
    expect(screen.getAllByTestId('frame-check-step-kind').map((c) => c.textContent)).toEqual(['You', 'Phone', 'Settings']);
    expect(steps[0].textContent).toContain(body.steps[0].text);

    const parts = screen.getByTestId('frame-check-step-parts');
    expect([...parts.querySelectorAll('dt')].map((d) => d.textContent)).toEqual(body.steps[2].parts!.map((p) => p.label));
    expect([...parts.querySelectorAll('dd')].map((d) => d.textContent)).toEqual(body.steps[2].parts!.map((p) => p.value));
    expect(screen.getAllByTestId('frame-check-step-source')[0].textContent).toMatch(/^From the guide: /);

    const ask = screen.getByTestId('frame-check-ask');
    const rawAsk = BODIES.bedroom_window_behind_en_a78.ask as { question_en: string; options: Array<{ en: string }> };
    expect(ask.textContent).toContain(rawAsk.question_en);
    expect([...ask.querySelectorAll('button')].map((b) => b.textContent)).toEqual(rawAsk.options.map((o) => o.en));
  });

  it('kitchen (Hinglish, no saved phone): Hinglish headings, Pro and OIS parts under their own captions, never in the plain list', () => {
    const body = BODIES.kitchen_tube_light_hi_no_phone;
    // The creator's setting is English; the reply's own language wins.
    renderCoach(parsed('kitchen_tube_light_hi_no_phone'), { lang: 'en-IN' });

    expect(screen.getByText('Mujhe yeh dikh raha hai')).toBeInTheDocument();
    expect(screen.getByText('Yeh karke dekho, is order mein')).toBeInTheDocument();
    const coach = screen.getByTestId('frame-check-coach').textContent ?? '';
    for (const english of ["Here's what I see", 'Try this, in order', 'From the guide', "A photo can't show"]) {
      expect(coach).not.toContain(english);
    }

    const rawParts = body.steps.find((s) => s.parts?.length)!.parts!;
    const plain = screen.getByTestId('frame-check-step-parts').querySelector(':scope > dl');
    expect([...(plain?.querySelectorAll('dt') ?? [])].map((d) => d.textContent)).toEqual(
      rawParts.filter((p) => !p.needs_pro && !p.needs_ois).map((p) => p.label)
    );
    const pro = screen.getByTestId('frame-check-step-pro-parts');
    expect(pro.textContent).toContain('Agar aapke camera app mein Pro video mode hai');
    expect([...pro.querySelectorAll('dt')].map((d) => d.textContent)).toEqual(
      rawParts.filter((p) => p.needs_pro).map((p) => p.label)
    );
    const ois = screen.getByTestId('frame-check-step-ois-parts');
    expect(ois.textContent).toContain('Agar aapke phone mein OIS (optical stabilisation) hai');
    expect([...ois.querySelectorAll('dt')].map((d) => d.textContent)).toEqual(
      rawParts.filter((p) => !p.needs_pro && p.needs_ois).map((p) => p.label)
    );
  });

  it('every settings value in every fixture is on the card', () => {
    for (const key of Object.keys(BODIES)) {
      const { container } = renderCoach(parsed(key));
      for (const step of BODIES[key].steps) {
        for (const part of step.parts ?? []) expect(container.textContent).toContain(part.value);
      }
      cleanup();
    }
  });

  it('park (English, no question): no ask block', () => {
    renderCoach(parsed('park_sun_en_find_x8_ultra'));
    expect(screen.getByTestId('frame-check-coach')).toBeInTheDocument();
    expect(screen.queryByTestId('frame-check-ask')).toBeNull();
  });

  it('too dark: the retake box only, whose "Check again" runs onCheckAgain', () => {
    const onCheckAgain = vi.fn();
    renderCoach(parsed('too_dark_en'), { onCheckAgain });
    const box = screen.getByTestId('frame-check-retake');
    expect(box.className).toContain('bg-warning');
    expect(box.textContent).toContain(BODIES.too_dark_en.what_i_see);
    expect(screen.queryByTestId('frame-check-coach')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Check again' }));
    expect(onCheckAgain).toHaveBeenCalledTimes(1);
  });

  it('exports the helpers the chat needs, with the panel\'s rules', () => {
    expect(MAX_COACH_ANSWERS).toBe(3);
    expect(hasCoachShape(parsed('park_sun_en_find_x8_ultra'))).toBe(true);
    expect(needsRetake(parsed('too_dark_en'))).toBe(true);
    expect(needsRetake(parsed('bedroom_window_behind_en_a78'))).toBe(false);
    expect(replyLangFor(parsed('kitchen_tube_light_hi_no_phone'), 'en-IN')).toBe('hi');
    expect(replyLangFor({ ...parsed('park_sun_en_find_x8_ultra'), lang: null }, 'hi-IN')).toBe('hi');
    expect(shootCheckLangFor('hi-IN')).toBe('hi-IN');
    expect(shootCheckLangFor('hi')).toBe('hi-IN');
    expect(shootCheckLangFor('en-IN')).toBe('en-IN');
    expect(shootCheckLangFor(undefined)).toBe('en-IN');
  });
});

/** The panel still uses the SAME card: its result region holds exactly the markup CoachResult renders. */
describe('the old page and the chat render the same card', () => {
  function readings(): ShootCheckReadings {
    const ok = { text: 'Looking good' };
    return {
      light: { luma: 52, status: 'ok', advice: 'ok', ...ok },
      focus: { sharpness: 900, status: 'ok', advice: 'ok', ...ok },
      framing: { status: 'ok', advice: 'ok', ...ok },
      tilt: { degrees: 0, status: 'ok', advice: 'ok', ...ok },
      background: { clutter: 10, status: 'ok', ...ok },
      mic: { levelDb: 'unknown', noiseFloorDb: 'unknown', status: 'unknown', advice: 'unknown', text: 'x' },
    };
  }

  for (const key of ['bedroom_window_behind_en_a78', 'kitchen_tube_light_hi_no_phone', 'park_sun_en_find_x8_ultra', 'too_dark_en']) {
    it(`${key}: identical markup`, async () => {
      const result = parsed(key);
      useShootCheckMock.mockReturnValue({
        supported: true,
        phase: 'active',
        errorMessage: null,
        readings: readings(),
        videoRef: { current: document.createElement('video') },
        start: vi.fn(),
        stop: vi.fn(),
        muted: false,
        setMuted: vi.fn(),
        noteInteraction: vi.fn(),
      });
      checkFrameMock.mockResolvedValue({ kind: 'ok', result });
      render(<ShootCheckPanel shots={[]} />);
      fireEvent.click(screen.getByRole('button', { name: 'Check my frame' }));
      await waitFor(() => expect(screen.getByTestId('frame-check-result').children.length).toBe(1));
      const fromPanel = screen.getByTestId('frame-check-result').innerHTML;
      cleanup();

      const { container } = renderCoach(result);
      expect(container.innerHTML).toBe(fromPanel);
    });
  }
});

describe('MeeraPhotoCheckMessage', () => {
  const bedroom = () => parsed('bedroom_window_behind_en_a78');
  const rawAsk = () => BODIES.bedroom_window_behind_en_a78.ask as { id: string; options: Array<{ en: string }> };

  function renderMessage(overrides: Partial<Parameters<typeof MeeraPhotoCheckMessage>[0]> = {}) {
    const props = {
      result: bedroom(),
      shotLabel: '0-3s · Close-up on your face',
      answers: [] as CoachAnswer[],
      lang: 'en-IN',
      interactive: true,
      busy: false,
      onAnswer: vi.fn(),
      onCheckAgain: vi.fn(),
      ...overrides,
    };
    render(<MeeraPhotoCheckMessage {...props} />);
    return props;
  }

  it('has a "Photo check · <shot>" header, the coach card, and a full-width-on-phone frame', () => {
    renderMessage();
    expect(screen.getByTestId('photo-check-header').textContent).toBe('Photo check · 0-3s · Close-up on your face');
    expect(screen.getByTestId('frame-check-coach')).toBeInTheDocument();
    const root = screen.getByTestId('photo-check-message');
    expect(root.className).toContain('w-full');
    expect(root.className).toContain('sm:max-w-[85%]');
  });

  it('with no shot the header is just "Photo check"', () => {
    renderMessage({ shotLabel: undefined });
    expect(screen.getByTestId('photo-check-header').textContent).toBe('Photo check');
  });

  it('the live card: a chip calls onAnswer with the question and option; "Check again" calls onCheckAgain', () => {
    const p = renderMessage();
    const option = rawAsk().options[1].en;
    fireEvent.click(screen.getByRole('button', { name: option }));
    expect(p.onAnswer).toHaveBeenCalledTimes(1);
    expect(vi.mocked(p.onAnswer).mock.calls[0][0].id).toBe(rawAsk().id);
    expect(vi.mocked(p.onAnswer).mock.calls[0][1]).toBe(1);
    fireEvent.click(screen.getByRole('button', { name: 'Check again' }));
    expect(p.onCheckAgain).toHaveBeenCalledTimes(1);
    expect(screen.queryByTestId('photo-check-older-photo')).toBeNull();
  });

  it('an older (or reloaded) card: chips disabled, and the footer says "Take a new photo" and opens the camera', () => {
    const p = renderMessage({ interactive: false });
    const ask = screen.getByTestId('frame-check-ask');
    for (const button of ask.querySelectorAll('button')) expect(button).toBeDisabled();
    expect(screen.getByTestId('photo-check-older-photo')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Take a new photo' }));
    expect(p.onCheckAgain).toHaveBeenCalledTimes(1);
    expect(p.onAnswer).not.toHaveBeenCalled();
  });

  it('while a check runs (busy), nothing can start another one', () => {
    renderMessage({ busy: true });
    for (const button of screen.getByTestId('frame-check-ask').querySelectorAll('button')) expect(button).toBeDisabled();
    expect(screen.getByTestId('photo-check-again')).toBeDisabled();
  });

  it('a retake result shows only the retake box\'s own "Check again", no second footer button', () => {
    const p = renderMessage({ result: parsed('too_dark_en') });
    expect(screen.getAllByRole('button', { name: 'Check again' })).toHaveLength(1);
    expect(screen.queryByTestId('photo-check-again')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Check again' }));
    expect(p.onCheckAgain).toHaveBeenCalledTimes(1);
  });

  it('a Hinglish reply gets the Hinglish footer', () => {
    renderMessage({ result: parsed('kitchen_tube_light_hi_no_phone'), lang: 'en-IN' });
    expect(screen.getByRole('button', { name: 'Dobara check karo' })).toBeInTheDocument();
  });

  it('an older server body (only the legacy lists) still renders as Fix / Settings / Looking good', () => {
    renderMessage({
      result: { fixes: ['Move closer'], settings: ['Grid on'], ok: ['Good light'], whatISee: null, steps: [], cantTell: [], ask: null, lang: null, retake: false },
    });
    expect(screen.getByText('Fix')).toBeInTheDocument();
    expect(screen.getByText('Move closer')).toBeInTheDocument();
    expect(screen.getByText('Settings')).toBeInTheDocument();
    expect(screen.queryByTestId('frame-check-coach')).toBeNull();
  });

  it('never says Co-pilot', () => {
    renderMessage({ interactive: false });
    expect(screen.getByTestId('photo-check-message').textContent).not.toMatch(/co-?pilot/i);
  });
});
