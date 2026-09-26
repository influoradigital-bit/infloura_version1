/**
 * Influora's Reel layout guide on the photo-check card (spec Phase 4):
 * - ReelLayoutGuide: hidden with no boxes; an aria-hidden picture with a numbered text legend, the
 *   placement lines, SAFE_ZONE_NOTE and DEVICE_NOTE; the PNG download revokes its URL; the photo's
 *   object URL is revoked on unmount.
 * - MeeraPhotoCheckMessage: the guide and the phone-only backlight line show on the NEWEST card while
 *   its photo is in memory only; older and reloaded cards are text only; code-written quick checks
 *   show on every card.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { MeeraPhotoCheckMessage } from '@/components/creator/meera/MeeraPhotoCheckMessage';
import { ReelLayoutGuide } from '@/components/creator/meera/ReelLayoutGuide';
import { parseShootCheckFrameBody, type MeeraShootCheckLayout } from '@/lib/meera-api';
import { adviceText } from '@/lib/shoot-check/advice-copy';
import type { PixelBuffer } from '@/lib/shoot-check/backlight';
import type { GuideCanvas, GuideCanvasContext } from '@/lib/shoot-check/guide-png';

const readStillPixels = vi.hoisted(() => vi.fn<(blob: Blob) => Promise<PixelBuffer | null>>());
vi.mock('@/lib/shoot-check/backlight', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/shoot-check/backlight')>()),
  readStillPixels,
}));

const en = (key: string) => adviceText(key, 'en-IN');

const FACE = { x: 260 / 1080, y: 540 / 1920, w: 200 / 1080, h: 220 / 1920 };
const LAYOUT: MeeraShootCheckLayout = {
  faces: [FACE],
  product: { x: 620 / 1080, y: 830 / 1920, w: 200 / 1080, h: 160 / 1920 },
};

const PHOTO = new Blob(['jpeg'], { type: 'image/jpeg' });

let urls: string[];
let revoked: string[];

beforeEach(() => {
  urls = [];
  revoked = [];
  Object.defineProperty(URL, 'createObjectURL', {
    value: vi.fn(() => {
      const url = `blob:test-${urls.length + 1}`;
      urls.push(url);
      return url;
    }),
    configurable: true,
    writable: true,
  });
  Object.defineProperty(URL, 'revokeObjectURL', {
    value: vi.fn((url: string) => revoked.push(url)),
    configurable: true,
    writable: true,
  });
  readStillPixels.mockReset();
  readStillPixels.mockResolvedValue(null);
});

afterEach(() => {
  vi.restoreAllMocks();
});

/** jsdom never decodes images: give the <img> a size and fire its load. */
function loadPhoto(width = 1080, height = 1920) {
  const img = screen.getByTestId('reel-layout-visual').querySelector('img')!;
  Object.defineProperty(img, 'naturalWidth', { value: width, configurable: true });
  Object.defineProperty(img, 'naturalHeight', { value: height, configurable: true });
  fireEvent.load(img);
}

function noopContext(): GuideCanvasContext {
  const noop = () => {};
  return {
    clearRect: noop,
    strokeRect: noop,
    beginPath: noop,
    moveTo: noop,
    lineTo: noop,
    closePath: noop,
    stroke: noop,
    fillText: noop,
    strokeText: noop,
    setLineDash: noop,
    save: noop,
    restore: noop,
    lineWidth: 1,
    strokeStyle: '#000000',
    fillStyle: '#000000',
    font: '',
    textAlign: 'left',
    textBaseline: 'top',
    lineJoin: 'miter',
  };
}

describe('ReelLayoutGuide', () => {
  it('is hidden when the reply has no boxes at all (older server, or every box invalid)', () => {
    const { container, rerender } = render(<ReelLayoutGuide photo={PHOTO} layout={undefined} lang="en-IN" />);
    expect(container).toBeEmptyDOMElement();
    rerender(<ReelLayoutGuide photo={PHOTO} layout={{ faces: [], product: null }} lang="en-IN" />);
    expect(container).toBeEmptyDOMElement();
    expect(URL.createObjectURL).not.toHaveBeenCalled();
  });

  it('shows the photo as viewers see the posted Reel: never mirrored, and says so', () => {
    render(<ReelLayoutGuide photo={PHOTO} layout={LAYOUT} lang="en-IN" />);
    loadPhoto();
    expect(screen.getByTestId('reel-layout-viewer-note')).toHaveTextContent(en('viewer_view_note'));
    const visual = screen.getByTestId('reel-layout-visual');
    for (const el of [visual, ...Array.from(visual.querySelectorAll('*'))]) {
      expect(el.getAttribute('class') ?? '').not.toMatch(/scale-x-|-scale-x/);
      expect((el as HTMLElement).style?.transform ?? '').not.toMatch(/scale|rotateY/);
    }
  });

  it('shows the titled guide: aria-hidden picture, numbered legend, placement lines and both notes', () => {
    render(<ReelLayoutGuide photo={PHOTO} layout={LAYOUT} lang="en-IN" />);
    loadPhoto();
    const guide = screen.getByTestId('reel-layout-guide');
    expect(within(guide).getByRole('heading', { name: 'Influora’s Reel layout guide' })).toBeInTheDocument();
    expect(screen.getByTestId('reel-layout-visual')).toHaveAttribute('aria-hidden', 'true');
    expect(screen.getByTestId('reel-layout-overlay')).toHaveAttribute('aria-hidden', 'true');

    const legend = within(screen.getByTestId('reel-layout-legend'))
      .getAllByRole('listitem')
      .map((li) => li.textContent);
    expect(legend).toEqual([
      `1.${en('zone_label_safe')}`,
      `2.${en('zone_label_top')}`,
      `3.${en('zone_label_bottom')}`,
      `4.${en('zone_label_rail')}`,
      `5.${en('zone_label_cta')}`,
      `6.${en('face_keep_clear')}`,
      `7.${en('product_label')}`,
      `8.${en('slot_hook')}`,
      `9.${en('slot_captions')}`,
      `10.${en('slot_logo')}`,
      `11.${en('slot_sticker')}`,
    ]);
    // Every legend number is also drawn on the picture.
    const badges = screen.getByTestId('reel-layout-overlay').querySelectorAll('text');
    expect(Array.from(badges).map((t) => t.textContent)).toEqual(legend.map((_, i) => String(i + 1)));

    expect(within(screen.getByTestId('reel-layout-lines')).getAllByRole('listitem').map((li) => li.textContent)).toEqual([
      'Put your hook text here, above your head.',
      en('captions_line'),
      en('logo_line'),
    ]);
    expect(screen.getByTestId('reel-layout-safe-zone-note').textContent).toBe(en('safe_zone_note'));
    expect(screen.getByTestId('reel-layout-device-note').textContent).toBe(en('device_note'));
    expect(screen.getByRole('button', { name: 'Download CapCut guide (PNG)' })).toBeInTheDocument();
  });

  it('shades the band right of the short CTA as covered, and outlines the green area minus the rail', () => {
    render(<ReelLayoutGuide photo={PHOTO} layout={LAYOUT} lang="en-IN" />);
    loadPhoto();
    const overlay = screen.getByTestId('reel-layout-overlay');
    const caption = overlay.querySelector('[data-zone="covered-caption"]')!;
    expect(caption).not.toBeNull();
    expect(['x', 'y', 'width', 'height'].map((a) => caption.getAttribute(a))).toEqual(['594', '1248', '335', '250']);
    const safe = overlay.querySelector('[data-zone="safe"]')!;
    expect(safe.tagName.toLowerCase()).toBe('path');
    expect(safe.getAttribute('d')).toBe('M43 269 L1037 269 L1037 960 L929 960 L929 1248 L43 1248 Z');
  });

  it('"no space" gives the backing line in the legend lines', () => {
    render(
      <ReelLayoutGuide
        photo={PHOTO}
        layout={{ faces: [{ x: 40 / 1080, y: 250 / 1920, w: 1000 / 1080, h: 1000 / 1920 }], product: null }}
        lang="en-IN"
      />,
    );
    loadPhoto();
    const lines = within(screen.getByTestId('reel-layout-lines'))
      .getAllByRole('listitem')
      .map((li) => li.textContent);
    expect(lines).toEqual([en('no_space_hook'), en('no_space_captions'), en('no_space_logo'), en('no_space_sticker')]);
    expect(lines[1]).toBe('No clear space for captions: use a dark text backing or a cutaway.');
  });

  it('crops a wide photo to 9:16 on screen', () => {
    render(<ReelLayoutGuide photo={PHOTO} layout={LAYOUT} lang="en-IN" />);
    loadPhoto(1920, 1080);
    const img = screen.getByTestId('reel-layout-visual').querySelector('img')!;
    // Crop width 0.316 of the photo: the image is drawn ~316% wide and shifted left.
    expect(parseFloat(img.style.width)).toBeCloseTo(100 / ((1080 * 9) / 16 / 1920), 3);
    expect(parseFloat(img.style.left)).toBeLessThan(0);
    expect(img.style.height).toBe('100%');
  });

  it('the PNG download goes through a Blob URL that is revoked afterwards', async () => {
    const ctx = noopContext();
    const canvas: GuideCanvas = {
      width: 0,
      height: 0,
      getContext: () => ctx,
      toBlob: (cb, type) => cb(new Blob(['png'], { type: type ?? '' })),
    };
    const clicked: HTMLAnchorElement[] = [];
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      clicked.push(this);
    });
    render(<ReelLayoutGuide photo={PHOTO} layout={LAYOUT} lang="en-IN" createCanvas={() => canvas} />);
    loadPhoto();
    fireEvent.click(screen.getByTestId('reel-layout-download'));
    await waitFor(() => expect(clicked).toHaveLength(1));
    expect(canvas.width).toBe(1080);
    expect(canvas.height).toBe(1920);
    const pngUrl = clicked[0].getAttribute('href')!;
    expect(pngUrl).toBe('blob:test-2'); // blob:test-1 is the on-screen photo
    await waitFor(() => expect(revoked).toContain(pngUrl), { timeout: 3000 });
    expect(revoked).not.toContain('blob:test-1');
  });

  it('a phone that cannot draw the PNG says so, and nothing is downloaded', async () => {
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    const canvas: GuideCanvas = { width: 0, height: 0, getContext: () => null, toBlob: () => {} };
    render(<ReelLayoutGuide photo={PHOTO} layout={LAYOUT} lang="en-IN" createCanvas={() => canvas} />);
    loadPhoto();
    fireEvent.click(screen.getByTestId('reel-layout-download'));
    expect(await screen.findByTestId('reel-layout-png-failed')).toHaveTextContent(en('png_failed'));
    expect(click).not.toHaveBeenCalled();
  });

  it('revokes the photo’s object URL on unmount', () => {
    const { unmount } = render(<ReelLayoutGuide photo={PHOTO} layout={LAYOUT} lang="en-IN" />);
    expect(urls).toEqual(['blob:test-1']);
    unmount();
    expect(revoked).toEqual(['blob:test-1']);
  });

  it('Hindi: the guide copy comes from the hi-IN table', () => {
    render(<ReelLayoutGuide photo={PHOTO} layout={LAYOUT} lang="hi-IN" />);
    loadPhoto();
    expect(screen.getByTestId('reel-layout-safe-zone-note').textContent).toBe(adviceText('safe_zone_note', 'hi-IN'));
  });
});

describe('MeeraPhotoCheckMessage with the Reel layout guide', () => {
  const body = {
    what_i_see: 'You at a desk, a bright window behind you.',
    steps: [{ kind: 'move_light', text: 'Turn so the window is in front of you.', note: 'window_light', label: 'Window light' }],
    ok: [],
    cant_tell: [],
    ask: null,
    lang: 'en',
    retake: false,
    layout: { faces: [FACE], product: LAYOUT.product },
    checks: ['Your face is near the edge; the app’s buttons can cover it.'],
  };

  function renderCard(overrides: Partial<Parameters<typeof MeeraPhotoCheckMessage>[0]> = {}) {
    return render(
      <MeeraPhotoCheckMessage
        result={parseShootCheckFrameBody(body)}
        answers={[]}
        lang="en-IN"
        interactive
        busy={false}
        onAnswer={vi.fn()}
        onCheckAgain={vi.fn()}
        {...overrides}
      />,
    );
  }

  /** A 100x100 buffer: dark face box, bright everywhere else. */
  function backlitPixels(): PixelBuffer {
    const data = new Uint8ClampedArray(100 * 100 * 4);
    const x0 = Math.round(FACE.x * 100);
    const x1 = Math.round((FACE.x + FACE.w) * 100);
    const y0 = Math.round(FACE.y * 100);
    const y1 = Math.round((FACE.y + FACE.h) * 100);
    for (let y = 0; y < 100; y++) {
      for (let x = 0; x < 100; x++) {
        const v = x >= x0 && x < x1 && y >= y0 && y < y1 ? 50 : 245;
        data.set([v, v, v, 255], (y * 100 + x) * 4);
      }
    }
    return { data, width: 100, height: 100 };
  }

  it('the newest card with its photo in memory shows the guide and the backlight line', async () => {
    readStillPixels.mockResolvedValue(backlitPixels());
    renderCard({ photo: PHOTO });
    expect(screen.getByTestId('reel-layout-guide')).toBeInTheDocument();
    const checks = await screen.findByText(en('check_backlight'));
    expect(within(screen.getByTestId('photo-check-quick-checks')).getByText(body.checks[0])).toBeInTheDocument();
    expect(checks).toBeInTheDocument();
    expect(readStillPixels).toHaveBeenCalledWith(PHOTO);
  });

  it('a Hindi reply keeps its Quick checks list in one script: the server’s Hinglish lines, title and backlight line', async () => {
    // The server's hi lines, read from their source (influora-ai checklist.py CHECK_LINES), not typed here.
    const checklist = readFileSync(join(process.cwd(), 'influora-ai', 'app', 'shoot', 'checklist.py'), 'utf-8');
    const serverHi = [...checklist.matchAll(/^\s*"hi": "(.+)",$/gm)].map((m) => m[1]);
    expect(serverHi.length).toBeGreaterThanOrEqual(9);
    const devanagari = /[ऀ-ॿ]/;
    expect(serverHi.filter((line) => devanagari.test(line))).toEqual([]);

    readStillPixels.mockResolvedValue(backlitPixels());
    render(
      <MeeraPhotoCheckMessage
        result={parseShootCheckFrameBody({ ...body, lang: 'hi', checks: serverHi.slice(0, 2) })}
        answers={[]}
        lang="hi-IN"
        interactive
        busy={false}
        onAnswer={vi.fn()}
        onCheckAgain={vi.fn()}
        photo={PHOTO}
      />,
    );
    const list = screen.getByTestId('photo-check-quick-checks');
    await within(list).findByText(adviceText('check_backlight', 'hi-IN'));
    expect(list.textContent).toContain(serverHi[0]);
    expect(devanagari.test(list.textContent ?? '')).toBe(false);
  });

  it('an older card (not interactive) is text only, even if a photo were passed', async () => {
    readStillPixels.mockResolvedValue(backlitPixels());
    renderCard({ interactive: false, photo: PHOTO });
    expect(screen.queryByTestId('reel-layout-guide')).toBeNull();
    await act(async () => {});
    expect(readStillPixels).not.toHaveBeenCalled();
    expect(screen.queryByText(en('check_backlight'))).toBeNull();
    // The code-written checks are text, so they stay.
    expect(screen.getByText(body.checks[0])).toBeInTheDocument();
  });

  it('a reloaded card (no photo, geometry stripped) is text only', () => {
    const { layout: _stripped, ...stored } = body;
    render(
      <MeeraPhotoCheckMessage
        result={parseShootCheckFrameBody(stored)}
        answers={[]}
        lang="en-IN"
        interactive
        busy={false}
        onAnswer={vi.fn()}
        onCheckAgain={vi.fn()}
      />,
    );
    expect(screen.queryByTestId('reel-layout-guide')).toBeNull();
    expect(screen.getByTestId('photo-check-quick-checks')).toHaveTextContent('Quick checks');
    expect(readStillPixels).not.toHaveBeenCalled();
  });

  it('with no boxes in the reply the guide is hidden; with no checks there is no Quick checks list', () => {
    const { layout: _l, checks: _c, ...older } = body;
    render(
      <MeeraPhotoCheckMessage
        result={parseShootCheckFrameBody(older)}
        answers={[]}
        lang="en-IN"
        interactive
        busy={false}
        onAnswer={vi.fn()}
        onCheckAgain={vi.fn()}
        photo={PHOTO}
      />,
    );
    expect(screen.queryByTestId('reel-layout-guide')).toBeNull();
    expect(screen.queryByTestId('photo-check-quick-checks')).toBeNull();
  });
});
