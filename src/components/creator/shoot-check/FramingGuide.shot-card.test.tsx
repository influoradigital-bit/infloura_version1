/**
 * FramingGuide + shot card (spec v2 Phase 6, owner decision 3): the text area over the live camera
 * follows the card's `text` field (top / opposite_face / lower_middle); `none` and `?` draw no text
 * area; a shot without a card keeps the size table's hook area, unchanged.
 *
 * Run: npx vitest run src/components/creator/shoot-check/FramingGuide.shot-card.test.tsx
 */
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import type { ShootCheckShot } from '@/components/creator/shoot-check/ShootCheckPanel';
import type { ShotCard } from '@/lib/meera-result-cards';
import { INTERIM_SAFE_ZONES } from '@/lib/shoot-check/safe-zones';
import { FramingGuide } from './FramingGuide';

const PLANE_BOX = { width: 1080, height: 1920 };

const CARD: ShotCard = {
  size: 'MS',
  height: 'eye',
  distance: '1.5 m',
  place: 'Bedroom desk',
  light: 'window-left',
  stand: 'left',
  headroom: 'small',
  eyes: 'lens',
  background: 'plain wall',
  space: 'right',
  text: 'top',
  prop: 'right-hand',
  move: 'still',
};

function shot(card?: Partial<ShotCard>): ShootCheckShot {
  return {
    index: 0,
    label: '0-3s · Medium shot - hold the serum',
    seconds: 3,
    target: 'medium',
    context: { line: 'Medium shot - hold the serum' },
    ...(card ? { card: { ...CARD, ...card } } : {}),
  };
}

function hookBand(): Element | null {
  return screen.getByTestId('shot-guides').querySelector('[data-band="hook-text"]');
}

describe('the text area follows the shot card', () => {
  it('without a card: the size table hook area, labelled above your head', () => {
    render(<FramingGuide shot={shot()} zones={INTERIM_SAFE_ZONES} box={PLANE_BOX} />);
    expect(hookBand()).not.toBeNull();
    expect(screen.getByText('Put your hook text here, above your head.')).toBeInTheDocument();
  });

  it('lower_middle: drawn in the lower half, above the caption line, and not called "above your head"', () => {
    render(<FramingGuide shot={shot({ text: 'lower_middle' })} zones={INTERIM_SAFE_ZONES} box={PLANE_BOX} />);
    const band = hookBand()!;
    expect(band.getAttribute('data-text-kind')).toBe('lower_middle');
    expect(Number(band.getAttribute('y'))).toBeGreaterThan(960);
    expect(Number(band.getAttribute('y')) + Number(band.getAttribute('height'))).toBeLessThanOrEqual(
      INTERIM_SAFE_ZONES.captionLine * 1920,
    );
    expect(screen.queryByText('Put your hook text here, above your head.')).toBeNull();
    expect(screen.getByText('Hook text')).toBeInTheDocument();
  });

  it('opposite_face: on the screen side away from the face, and it follows mirroring', () => {
    // Stand on your left. Back camera (not mirrored): your left is the screen's right, so text goes left.
    const { unmount } = render(
      <FramingGuide shot={shot({ text: 'opposite_face' })} mirrored={false} zones={INTERIM_SAFE_ZONES} box={PLANE_BOX} />,
    );
    let band = hookBand()!;
    expect(band.getAttribute('data-text-kind')).toBe('opposite_face');
    expect(Number(band.getAttribute('x')) + Number(band.getAttribute('width'))).toBeLessThanOrEqual(540);
    unmount();

    // Front camera (mirrored): your left is the screen's left, so text goes right.
    render(<FramingGuide shot={shot({ text: 'opposite_face' })} mirrored zones={INTERIM_SAFE_ZONES} box={PLANE_BOX} />);
    band = hookBand()!;
    expect(Number(band.getAttribute('x'))).toBeGreaterThanOrEqual(540);
  });

  it('none and ? draw no text area (nothing guessed)', () => {
    for (const text of ['none', '?'] as const) {
      const { unmount } = render(<FramingGuide shot={shot({ text })} zones={INTERIM_SAFE_ZONES} box={PLANE_BOX} />);
      expect(hookBand(), text).toBeNull();
      expect(screen.queryByText('Put your hook text here, above your head.')).toBeNull();
      unmount();
    }
  });

  it('top: the hook area, labelled above your head', () => {
    render(<FramingGuide shot={shot({ text: 'top' })} zones={INTERIM_SAFE_ZONES} box={PLANE_BOX} />);
    expect(hookBand()!.getAttribute('data-text-kind')).toBe('top');
    expect(screen.getByText('Put your hook text here, above your head.')).toBeInTheDocument();
  });
});
