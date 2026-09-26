/**
 * MeeraCameraSheet's shot guide (spec v2 Phase 5a + 5b): the Guides button and sheet, the settings
 * reaching FramingGuide and storage, the always-on safe zone, the "Set-up for this shot" line with
 * aria-describedby, the set-up chips and the first-run safe-zone hint.
 *
 * Only the browser's camera API is stubbed (the REAL useShootCheck hook runs), like
 * MeeraCameraSheet.test.tsx.
 *
 * Run: npx vitest run src/components/creator/meera/MeeraCameraSheet.guides.test.tsx
 */
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { ShootCheckShot } from '@/components/creator/shoot-check/ShootCheckPanel';
import { CAMERA_GRID_KEY, SAFE_ZONE_HINT_KEY, SHOT_GUIDES_KEY } from '@/lib/shoot-check/guide-prefs';
import { MeeraCameraSheet, type MeeraCameraSheetProps } from './MeeraCameraSheet';

const ORIGINAL_MEDIA_DEVICES = navigator.mediaDevices;
const ORIGINAL_SECURE = window.isSecureContext;

function setMediaDevices(value: unknown): void {
  Object.defineProperty(navigator, 'mediaDevices', { value, configurable: true });
}

function fakeStream(): MediaStream {
  const track = { kind: 'video', stop: () => {}, getSettings: () => ({}) };
  return {
    getTracks: () => [track],
    getVideoTracks: () => [track],
    getAudioTracks: () => [],
  } as unknown as MediaStream;
}

const SHOTS: ShootCheckShot[] = [
  {
    index: 0,
    label: '0-3s · Medium shot - hold the serum',
    seconds: 3,
    target: 'medium',
    context: {
      line: 'Medium shot - hold the serum',
      angle: 'Eye level',
      where: 'Bedroom, by the window and the long cupboard',
      light: 'Window on your left',
      sit_or_walk: 'Sit',
      prop: 'serum bottle',
    },
  },
  { index: 1, label: '3-8s · Overhead - hands pouring', seconds: 5, target: 'hands-overhead' },
];

/** Renders and waits for the camera to be active, so no state update lands outside act(). */
async function setupActive(overrides: Partial<MeeraCameraSheetProps> = {}) {
  const view = setup(overrides);
  await waitFor(() => expect(checkButton()).toBeEnabled());
  return view;
}

function setup(overrides: Partial<MeeraCameraSheetProps> = {}) {
  const props: MeeraCameraSheetProps = {
    open: true,
    onOpenChange: vi.fn(),
    lang: 'en-IN',
    shots: SHOTS,
    initialShotIndex: 0,
    onCapture: vi.fn(),
    ...overrides,
  };
  return { props, ...render(<MeeraCameraSheet {...props} />) };
}

const checkButton = () => screen.getByTestId('camera-sheet-check');
const guide = () => screen.getByTestId('framing-guide');

beforeEach(() => {
  window.localStorage.clear();
  Object.defineProperty(window, 'isSecureContext', { value: true, configurable: true });
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined);
  setMediaDevices({ getUserMedia: vi.fn().mockImplementation(() => Promise.resolve(fakeStream())) });
});

afterEach(() => {
  vi.restoreAllMocks();
  window.localStorage.clear();
  setMediaDevices(ORIGINAL_MEDIA_DEVICES);
  Object.defineProperty(window, 'isSecureContext', { value: ORIGINAL_SECURE, configurable: true });
});

describe('the Guides button and sheet', () => {
  it('is a text button of at least 44 x 44 px in the header', async () => {
    await setupActive();
    const button = screen.getByRole('button', { name: 'Guides' });
    expect(button).toHaveTextContent('Guides');
    expect(button.className).toContain('h-11');
    expect(button.className).toContain('min-w-11');
  });

  it('a grid choice reaches the guide and is remembered; focus returns to Guides on close', async () => {
    const user = userEvent.setup();
    setup();
    await waitFor(() => expect(checkButton()).toBeEnabled());
    expect(within(guide()).getByTestId('camera-grid').getAttribute('data-grid')).toBe('thirds');

    const guidesButton = screen.getByRole('button', { name: 'Guides' });
    await user.click(guidesButton);
    const dialog = await screen.findByRole('dialog', { name: 'Guides' });
    await user.click(within(dialog).getByRole('radio', { name: /Golden grid/ }));
    expect(window.localStorage.getItem(CAMERA_GRID_KEY)).toBe('golden');
    expect(within(guide()).getByTestId('camera-grid').getAttribute('data-grid')).toBe('golden');
    expect(within(guide()).getAllByTestId('grid-point')).toHaveLength(4);

    await user.click(within(dialog).getByRole('radio', { name: 'Off' }));
    expect(within(guide()).queryByTestId('camera-grid')).toBeNull();
    expect(within(guide()).getByTestId('safe-zone')).toBeInTheDocument();

    await user.click(within(dialog).getByRole('switch', { name: 'Shot guides' }));
    expect(window.localStorage.getItem(SHOT_GUIDES_KEY)).toBe('off');
    expect(within(guide()).queryByTestId('shot-guides')).toBeNull();
    expect(within(guide()).getByTestId('safe-zone')).toBeInTheDocument();

    await user.click(within(dialog).getByRole('button', { name: 'Done' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Guides' })).toBeNull());
    await waitFor(() => expect(guidesButton).toHaveFocus());
    // Closing the Guides sheet leaves the camera sheet open.
    expect(screen.getByTestId('meera-camera-sheet')).toBeInTheDocument();
  });

  it('opens with the stored grid, and with Rule of thirds when storage throws', async () => {
    window.localStorage.setItem(CAMERA_GRID_KEY, 'golden');
    const { unmount } = setup();
    await waitFor(() => expect(checkButton()).toBeEnabled());
    expect(within(guide()).getByTestId('camera-grid').getAttribute('data-grid')).toBe('golden');
    unmount();

    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError');
    });
    setup();
    await waitFor(() => expect(checkButton()).toBeEnabled());
    expect(within(guide()).getByTestId('camera-grid').getAttribute('data-grid')).toBe('thirds');
  });
});

describe('the guide gets the current shot and the mirror state', () => {
  it('front camera: "your right" prop on screen right; after a flip, on screen left', async () => {
    setup();
    await waitFor(() => expect(checkButton()).toBeEnabled());
    expect(within(guide()).getByTestId('shot-guides').getAttribute('data-size')).toBe('MS');
    expect(within(guide()).getByTestId('prop-zone').getAttribute('data-screen-side')).toBe('right');

    fireEvent.click(screen.getByRole('button', { name: 'Flip camera' }));
    await waitFor(() => expect(checkButton()).toBeEnabled());
    expect(within(guide()).getByTestId('prop-zone').getAttribute('data-screen-side')).toBe('left');
  });

  it('switches bands with the shot strip', async () => {
    setup();
    await waitFor(() => expect(checkButton()).toBeEnabled());
    fireEvent.click(screen.getByRole('button', { name: 'Next shot' }));
    expect(within(guide()).getByTestId('shot-guides').getAttribute('data-size')).toBe('OVERHEAD');
    expect(within(guide()).queryByTestId('prop-zone')).toBeNull();
  });
});

describe('the set-up line and chips', () => {
  it('the preview is described by the full, uncut "Set-up for this shot" line', async () => {
    await setupActive();
    const line = screen.getByTestId('camera-sheet-setup-line');
    expect(line.textContent).toMatch(/^Set-up for this shot: Medium shot\. Eye level\./);
    expect(line).toHaveTextContent('Where: Bedroom, by the window and the long cupboard.');
    expect(line).toHaveTextContent('Light: Window on your left.');
    expect(line).toHaveTextContent('Prop: serum bottle (suggested spot).');
    expect(line).toHaveTextContent('Stand on the other line.');
    const preview = screen.getByTestId('camera-sheet-preview');
    expect(preview.getAttribute('aria-describedby')).toBe(line.id);
    expect(line.id).not.toBe('');
  });

  it('drops "Stand on the other line." when shot guides are off (no prop zone drawn)', async () => {
    window.localStorage.setItem(SHOT_GUIDES_KEY, 'off');
    await setupActive();
    const line = screen.getByTestId('camera-sheet-setup-line');
    expect(line).toHaveTextContent('Prop: serum bottle.');
    expect(line).not.toHaveTextContent('Stand on the other line.');
  });

  it('shows at most 3 chips, cut at 20 visible characters, in the bottom panel', async () => {
    await setupActive();
    const chips = screen.getAllByTestId('camera-sheet-chip');
    expect(chips.map((c) => c.textContent)).toEqual(['Eye level', 'Bedroom, by the…', 'Window on your left']);
    // Never over the preview, so a chip cannot cover a zone label.
    expect(screen.getByTestId('camera-sheet-preview').contains(chips[0])).toBe(false);
  });

  it('with no shot: one "General guide" chip and a general line', async () => {
    await setupActive({ shots: [], initialShotIndex: undefined });
    expect(screen.getAllByTestId('camera-sheet-chip').map((c) => c.textContent)).toEqual(['General guide']);
    expect(screen.getByTestId('camera-sheet-setup-line').textContent).toMatch(/^Set-up for this shot: General guide\. Medium shot\./);
  });
});

describe('first-run safe-zone hint', () => {
  it('shows the safe-zone note until "Got it", then never again on this device', async () => {
    const user = userEvent.setup();
    const { unmount } = await setupActive();
    const hint = screen.getByTestId('safe-zone-hint');
    expect(hint).toHaveTextContent('The green area keeps your text visible.');
    await user.click(within(hint).getByRole('button', { name: 'Got it' }));
    expect(screen.queryByTestId('safe-zone-hint')).toBeNull();
    expect(window.localStorage.getItem(SAFE_ZONE_HINT_KEY)).toBe('seen');
    unmount();

    await setupActive();
    expect(screen.queryByTestId('safe-zone-hint')).toBeNull();
  });
});

describe('no auto-send, no match', () => {
  it('opening and changing the guides never captures or sends anything', async () => {
    const user = userEvent.setup();
    const { props } = setup();
    await waitFor(() => expect(checkButton()).toBeEnabled());
    await user.click(screen.getByRole('button', { name: 'Guides' }));
    const dialog = await screen.findByRole('dialog', { name: 'Guides' });
    await user.click(within(dialog).getByRole('radio', { name: 'Off' }));
    expect(props.onCapture).not.toHaveBeenCalled();
    expect(screen.queryByText(/matched/i)).toBeNull();
  });
});
