/**
 * FramingGuide: the shot guide over the live camera (spec v2 Phase 5a + 5b). Rendered with a fixed
 * `box` so every coordinate is checkable: the plane is 1080 x 1920 and a 390 x 600 preview box holds
 * a 337.5 x 600 Reel frame at x 26.25.
 *
 * Run: npx vitest run src/components/creator/shoot-check/FramingGuide.test.tsx
 */
import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import type { ShootCheckShot } from '@/components/creator/shoot-check/ShootCheckPanel';
import { INTERIM_SAFE_ZONES } from '@/lib/shoot-check/safe-zones';
import type { CameraGrid } from '@/lib/shoot-check/guide-prefs';
import { FramingGuide } from './FramingGuide';
import frameGuideSource from './FramingGuide.tsx?raw';

const PLANE_BOX = { width: 1080, height: 1920 };
const PHONE_BOX = { width: 390, height: 600 };
const GRIDS: CameraGrid[] = ['thirds', 'golden', 'off'];

const MEDIUM_WITH_PROP: ShootCheckShot = {
  index: 0,
  label: '0-3s · Medium shot - hold the serum',
  seconds: 3,
  target: 'medium',
  context: { line: 'Medium shot - hold the serum', prop: 'serum bottle' },
};

function num(el: Element, attr: string): number {
  return Number(el.getAttribute(attr));
}

describe('the safe zone is always drawn', () => {
  it('in all 6 combinations of grid x shot guides, with its three text labels', () => {
    for (const grid of GRIDS) {
      for (const shotGuides of [true, false]) {
        const { unmount } = render(
          <FramingGuide grid={grid} shotGuides={shotGuides} shot={MEDIUM_WITH_PROP} zones={INTERIM_SAFE_ZONES} box={PHONE_BOX} />
        );
        const safe = screen.getByTestId('safe-zone');
        for (const zone of ['top', 'bottom', 'side-left', 'side-right', 'rail', 'cta', 'safe']) {
          expect(safe.querySelector(`[data-zone="${zone}"]`), `${grid}/${shotGuides}/${zone}`).not.toBeNull();
        }
        expect(within(safe).getByText('Covered: app top bar')).toBeInTheDocument();
        expect(within(safe).getByText('Covered: username, caption, music')).toBeInTheDocument();
        expect(within(safe).getByText('Short CTA, left side')).toBeInTheDocument();
        unmount();
      }
    }
  });

  it('reads its zones from the config it is given (top bar height follows `top`)', () => {
    render(<FramingGuide zones={{ ...INTERIM_SAFE_ZONES, top: 0.2 }} box={PLANE_BOX} />);
    const top = screen.getByTestId('safe-zone').querySelector('[data-zone="top"]')!;
    expect(num(top, 'height')).toBeCloseTo(0.2 * 1920);
  });

  it('draws the rail notch out of the green area: x 929-1037, y 960-1498 on the plane', () => {
    render(<FramingGuide zones={INTERIM_SAFE_ZONES} box={PLANE_BOX} />);
    const rail = screen.getByTestId('safe-zone').querySelector('[data-zone="rail"]')!;
    expect(num(rail, 'x')).toBeCloseTo(928.8);
    expect(num(rail, 'y')).toBeCloseTo(960);
    expect(num(rail, 'x') + num(rail, 'width')).toBeCloseTo(1036.8);
    expect(num(rail, 'y') + num(rail, 'height')).toBeCloseTo(1497.6);
    const cta = screen.getByTestId('safe-zone').querySelector('[data-zone="cta"]')!;
    expect(num(cta, 'x') + num(cta, 'width')).toBeCloseTo(594);
    expect(num(cta, 'y')).toBeCloseTo(1248);
  });
});

describe('camera grid', () => {
  it('Rule of thirds: 4 lines at x 360/720 and y 640/1280 inside the Reel frame', () => {
    render(<FramingGuide grid="thirds" box={PLANE_BOX} zones={INTERIM_SAFE_ZONES} />);
    const grid = screen.getByTestId('camera-grid');
    const lines = grid.querySelectorAll('line');
    expect(lines).toHaveLength(4);
    const xs = [...grid.querySelectorAll('line[data-axis="x"]')].map((l) => num(l, 'x1'));
    const ys = [...grid.querySelectorAll('line[data-axis="y"]')].map((l) => num(l, 'y1'));
    expect(xs).toEqual([360, 720]);
    expect(ys).toEqual([640, 1280]);
    expect(grid.querySelectorAll('[data-testid="grid-point"]')).toHaveLength(0);
  });

  it('Off: no grid lines at all (the safe zone stays)', () => {
    const { container } = render(<FramingGuide grid="off" box={PLANE_BOX} zones={INTERIM_SAFE_ZONES} />);
    expect(screen.queryByTestId('camera-grid')).toBeNull();
    expect(container.querySelectorAll('line')).toHaveLength(0);
    expect(screen.getByTestId('safe-zone')).toBeInTheDocument();
  });

  it('Golden grid, scaled into a 390 x 600 preview: lines at x 412/668, y 733/1187 and exactly 4 dots', () => {
    render(<FramingGuide grid="golden" box={PHONE_BOX} zones={INTERIM_SAFE_ZONES} />);
    const grid = screen.getByTestId('camera-grid');
    const s = 337.5 / 1080;
    const xs = [...grid.querySelectorAll('line[data-axis="x"]')].map((l) => num(l, 'x1'));
    const ys = [...grid.querySelectorAll('line[data-axis="y"]')].map((l) => num(l, 'y1'));
    expect(xs[0]).toBeCloseTo(26.25 + 412 * s);
    expect(xs[1]).toBeCloseTo(26.25 + 668 * s);
    expect(ys[0]).toBeCloseTo(733 * s);
    expect(ys[1]).toBeCloseTo(1187 * s);
    // Vertical lines run the Reel frame's height only, not the dimmed sides.
    const v = grid.querySelector('line[data-axis="x"]')!;
    expect(num(v, 'y1')).toBe(0);
    expect(num(v, 'y2')).toBe(600);
    const h = grid.querySelector('line[data-axis="y"]')!;
    expect(num(h, 'x1')).toBeCloseTo(26.25);
    expect(num(h, 'x2')).toBeCloseTo(363.75);

    const dots = screen.getAllByTestId('grid-point');
    expect(dots).toHaveLength(4);
    const centres = dots.map((d) => [num(d, 'cx'), num(d, 'cy')]);
    for (const [px, py] of [
      [412, 733],
      [668, 733],
      [412, 1187],
      [668, 1187],
    ]) {
      expect(centres.some(([cx, cy]) => Math.abs(cx - (26.25 + px * s)) < 1e-6 && Math.abs(cy - py * s) < 1e-6)).toBe(true);
    }
    // Each dot has a dark outline so it reads on any background.
    for (const d of dots) expect(d.getAttribute('stroke')).toBe('black');
  });

  it('draws no spiral: only straight lines and 4 dots in the grid layer', () => {
    render(<FramingGuide grid="golden" box={PHONE_BOX} zones={INTERIM_SAFE_ZONES} />);
    const grid = screen.getByTestId('camera-grid');
    expect(grid.querySelectorAll('path')).toHaveLength(0);
    expect(grid.children).toHaveLength(8);
  });
});

describe('the 9:16 Reel frame', () => {
  it('is centred in a portrait and a landscape box', () => {
    const { unmount } = render(<FramingGuide box={PHONE_BOX} zones={INTERIM_SAFE_ZONES} />);
    let frame = screen.getByTestId('reel-frame');
    expect(num(frame, 'x')).toBeCloseTo(26.25);
    expect(num(frame, 'width')).toBeCloseTo(337.5);
    expect(num(frame, 'height')).toBe(600);
    unmount();
    render(<FramingGuide box={{ width: 600, height: 390 }} zones={INTERIM_SAFE_ZONES} />);
    frame = screen.getByTestId('reel-frame');
    expect(num(frame, 'x')).toBeCloseTo(190.3125);
    expect(num(frame, 'width')).toBeCloseTo(219.375);
  });
});

describe('shot guides', () => {
  it('draw the size\'s bands, the hook-text area and the prop zone only while on', () => {
    const { rerender } = render(
      <FramingGuide shot={MEDIUM_WITH_PROP} shotGuides mirrored zones={INTERIM_SAFE_ZONES} box={PLANE_BOX} />
    );
    const guides = screen.getByTestId('shot-guides');
    expect(guides.getAttribute('data-size')).toBe('MS');
    const eye = guides.querySelector('[data-band="eye-line"]')!;
    expect(num(eye, 'y')).toBe(640);
    expect(num(eye, 'y') + num(eye, 'height')).toBe(733);
    expect(guides.querySelector('[data-band="hook-text"]')).not.toBeNull();
    expect(screen.getByText('Put your hook text here, above your head.')).toBeInTheDocument();
    expect(guides.querySelector('[data-band="lower-crop"]')?.getAttribute('data-part')).toBe('waist');
    expect(screen.getByTestId('prop-zone')).toBeInTheDocument();

    rerender(<FramingGuide shot={MEDIUM_WITH_PROP} shotGuides={false} mirrored zones={INTERIM_SAFE_ZONES} box={PLANE_BOX} />);
    expect(screen.queryByTestId('shot-guides')).toBeNull();
    expect(screen.queryByTestId('prop-zone')).toBeNull();
  });

  it('mirror on: "your right" prop zone on the screen\'s right thirds line; mirror off: flipped', () => {
    const { rerender } = render(
      <FramingGuide shot={MEDIUM_WITH_PROP} mirrored zones={INTERIM_SAFE_ZONES} box={PLANE_BOX} />
    );
    let zone = screen.getByTestId('prop-zone');
    expect(zone.getAttribute('data-side')).toBe('right');
    expect(zone.getAttribute('data-screen-side')).toBe('right');
    expect(num(zone, 'x') + num(zone, 'width') / 2).toBeCloseTo(720);
    // Left of the rail, above the caption line.
    expect(num(zone, 'x') + num(zone, 'width')).toBeLessThanOrEqual(928.8 - 16);
    expect(num(zone, 'y') + num(zone, 'height')).toBeLessThanOrEqual(1248);

    rerender(<FramingGuide shot={MEDIUM_WITH_PROP} mirrored={false} zones={INTERIM_SAFE_ZONES} box={PLANE_BOX} />);
    zone = screen.getByTestId('prop-zone');
    expect(zone.getAttribute('data-screen-side')).toBe('left');
    expect(num(zone, 'x') + num(zone, 'width') / 2).toBeCloseTo(360);
  });

  it('no prop in the shot, no prop zone', () => {
    const noProp = { ...MEDIUM_WITH_PROP, context: { line: 'Medium shot - talk' } };
    render(<FramingGuide shot={noProp} mirrored zones={INTERIM_SAFE_ZONES} box={PLANE_BOX} />);
    expect(screen.queryByTestId('prop-zone')).toBeNull();
  });

  it('an overhead shot draws the surface and hands, and no eye line', () => {
    const overhead: ShootCheckShot = { index: 1, label: '3-8s · Overhead - hands pouring', seconds: 5, target: 'hands-overhead' };
    render(<FramingGuide shot={overhead} zones={INTERIM_SAFE_ZONES} box={PLANE_BOX} />);
    const guides = screen.getByTestId('shot-guides');
    expect(guides.querySelector('[data-band="surface"]')).not.toBeNull();
    expect(guides.querySelectorAll('[data-band="hand"]')).toHaveLength(2);
    expect(guides.querySelector('[data-band="eye-line"]')).toBeNull();
  });
});

describe('accessibility and motion', () => {
  it('the SVG is aria-hidden and takes no taps', () => {
    render(<FramingGuide zones={INTERIM_SAFE_ZONES} box={PHONE_BOX} />);
    const svg = screen.getByTestId('framing-guide');
    expect(svg.getAttribute('aria-hidden')).toBe('true');
    expect(svg.getAttribute('class')).toContain('pointer-events-none');
  });

  it('its only transition is removed under reduced motion', () => {
    render(<FramingGuide zones={INTERIM_SAFE_ZONES} box={PHONE_BOX} />);
    const cls = screen.getByTestId('framing-guide').getAttribute('class') ?? '';
    expect(cls).toContain('motion-reduce:transition-none');
  });

  it('draws in Hindi when the chat is in Hindi', () => {
    render(<FramingGuide zones={INTERIM_SAFE_ZONES} box={PHONE_BOX} lang="hi-IN" />);
    expect(screen.queryByText('Covered: app top bar')).toBeNull();
  });
});

describe('no false green', () => {
  it('has no match state: no reading or verdict goes in, no "matched" state comes out', () => {
    // The guide is drawn, never read (2026-09-26 "no live readings" ruling). It must not import the
    // checker or the camera hook, and nothing in it can say a shot matches.
    expect(frameGuideSource).not.toMatch(/framingVerdict|useShootCheck|readings/);
    expect(frameGuideSource).not.toMatch(/matched|isMatch|data-state/i);
    for (const grid of GRIDS) {
      const { container, unmount } = render(
        <FramingGuide grid={grid} shot={MEDIUM_WITH_PROP} mirrored zones={INTERIM_SAFE_ZONES} box={PHONE_BOX} />
      );
      expect(container.querySelector('[data-state]')).toBeNull();
      expect(container.textContent ?? '').not.toMatch(/matched|looking good/i);
      unmount();
    }
  });
});
