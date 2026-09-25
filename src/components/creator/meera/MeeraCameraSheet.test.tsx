/**
 * MeeraCameraSheet with the REAL useShootCheck hook: only the browser's camera API
 * (`navigator.mediaDevices.getUserMedia`), `HTMLMediaElement.play` and the canvas are stubbed, so
 * these tests prove what the sheet asks the browser for and what it releases, not what a mocked
 * hook was told.
 *
 * Run: npx vitest run src/components/creator/meera/MeeraCameraSheet.test.tsx
 */
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { ShootCheckShot } from '@/components/creator/shoot-check/ShootCheckPanel';
import { MeeraCameraSheet, type MeeraCameraSheetProps } from './MeeraCameraSheet';

const ORIGINAL_MEDIA_DEVICES = navigator.mediaDevices;
const ORIGINAL_SECURE = window.isSecureContext;
const ORIGINAL_ONLINE = Object.getOwnPropertyDescriptor(window.navigator, 'onLine');

function setMediaDevices(value: unknown): void {
  Object.defineProperty(navigator, 'mediaDevices', { value, configurable: true });
}

function setOnline(value: boolean): void {
  Object.defineProperty(window.navigator, 'onLine', { value, configurable: true });
}

/** A camera stream whose one video track records `stop()`. */
function fakeCamera(): { stream: MediaStream; stopped: () => number } {
  let stops = 0;
  const track = { kind: 'video', stop: () => { stops += 1; }, getSettings: () => ({}) };
  const stream = {
    getTracks: () => [track],
    getVideoTracks: () => [track],
    getAudioTracks: () => [],
  } as unknown as MediaStream;
  return { stream, stopped: () => stops };
}

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

const SHOTS: ShootCheckShot[] = [
  { index: 0, label: '0-3s · Close-up on your face', seconds: 3, target: 'closeup' },
  { index: 1, label: '3-8s · Overhead - hands pouring', seconds: 5, target: 'hands-overhead' },
];

function setup(overrides: Partial<MeeraCameraSheetProps> = {}) {
  const props: MeeraCameraSheetProps = {
    open: true,
    onOpenChange: vi.fn(),
    lang: 'en-IN',
    shots: [],
    onCapture: vi.fn(),
    ...overrides,
  };
  const view = render(<MeeraCameraSheet {...props} />);
  return { props, ...view };
}

const checkButton = () => screen.getByTestId('camera-sheet-check');

beforeEach(() => {
  Object.defineProperty(window, 'isSecureContext', { value: true, configurable: true });
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined);
  setOnline(true);
});

afterEach(() => {
  vi.restoreAllMocks();
  setMediaDevices(ORIGINAL_MEDIA_DEVICES);
  Object.defineProperty(window, 'isSecureContext', { value: ORIGINAL_SECURE, configurable: true });
  if (ORIGINAL_ONLINE) Object.defineProperty(window.navigator, 'onLine', ORIGINAL_ONLINE);
  else delete (window.navigator as unknown as Record<string, unknown>).onLine;
});

describe('opening the camera', () => {
  it('asks for the front camera ONLY: no audio getUserMedia call (withMic: false)', async () => {
    const cam = fakeCamera();
    const getUserMedia = vi.fn().mockResolvedValue(cam.stream);
    setMediaDevices({ getUserMedia });

    setup();
    await waitFor(() => expect(checkButton()).toBeEnabled());
    expect(getUserMedia).toHaveBeenCalledTimes(1);
    expect(getUserMedia).toHaveBeenCalledWith({ video: { facingMode: 'user' } });
    for (const [constraints] of getUserMedia.mock.calls) expect(constraints).not.toHaveProperty('audio');
  });

  it('keeps "Check my set-up" disabled until the camera is active', async () => {
    const cam = fakeCamera();
    const pending = deferred<MediaStream>();
    setMediaDevices({ getUserMedia: vi.fn().mockReturnValue(pending.promise) });

    setup();
    expect(checkButton()).toBeDisabled();
    expect(screen.getByText('Starting camera…')).toBeInTheDocument();

    await act(async () => {
      pending.resolve(cam.stream);
      await pending.promise;
    });
    await waitFor(() => expect(checkButton()).toBeEnabled());
  });

  it('mirrors the front camera preview only', async () => {
    const front = fakeCamera();
    const rear = fakeCamera();
    setMediaDevices({ getUserMedia: vi.fn().mockResolvedValueOnce(front.stream).mockResolvedValueOnce(rear.stream) });

    setup();
    await waitFor(() => expect(checkButton()).toBeEnabled());
    expect(screen.getByTestId('camera-sheet-video').className).toContain('-scale-x-100');

    fireEvent.click(screen.getByRole('button', { name: 'Flip camera' }));
    await waitFor(() => expect(checkButton()).toBeEnabled());
    expect(screen.getByTestId('camera-sheet-video').className).not.toContain('-scale-x-100');
  });
});

describe('flip camera', () => {
  it('stops the front camera, then starts the rear one (facingMode: environment)', async () => {
    const front = fakeCamera();
    const rear = fakeCamera();
    const getUserMedia = vi.fn().mockResolvedValueOnce(front.stream).mockResolvedValueOnce(rear.stream);
    setMediaDevices({ getUserMedia });

    setup();
    await waitFor(() => expect(checkButton()).toBeEnabled());
    expect(front.stopped()).toBe(0);

    fireEvent.click(screen.getByRole('button', { name: 'Flip camera' }));
    // stop() runs in the click handler, before the new start().
    expect(front.stopped()).toBeGreaterThan(0);
    await waitFor(() => expect(getUserMedia).toHaveBeenCalledTimes(2));
    expect(getUserMedia).toHaveBeenNthCalledWith(2, { video: { facingMode: 'environment' } });
    await waitFor(() => expect(checkButton()).toBeEnabled());
    expect(rear.stopped()).toBe(0);
  });
});

describe('closing', () => {
  it('the close button asks to close, and closing stops the video track', async () => {
    const cam = fakeCamera();
    setMediaDevices({ getUserMedia: vi.fn().mockResolvedValue(cam.stream) });

    const { props, rerender } = setup();
    await waitFor(() => expect(checkButton()).toBeEnabled());

    await userEvent.click(screen.getByRole('button', { name: 'Close camera' }));
    expect(props.onOpenChange).toHaveBeenCalledWith(false);
    expect(cam.stopped()).toBe(0);

    rerender(<MeeraCameraSheet {...props} open={false} />);
    await waitFor(() => expect(cam.stopped()).toBeGreaterThan(0));
  });

  it('Esc closes too', async () => {
    const cam = fakeCamera();
    setMediaDevices({ getUserMedia: vi.fn().mockResolvedValue(cam.stream) });

    const { props } = setup();
    await waitFor(() => expect(checkButton()).toBeEnabled());
    await userEvent.keyboard('{Escape}');
    expect(props.onOpenChange).toHaveBeenCalledWith(false);
  });

  it('closing while the permission prompt is still up releases the camera when it resolves', async () => {
    const cam = fakeCamera();
    const pending = deferred<MediaStream>();
    setMediaDevices({ getUserMedia: vi.fn().mockReturnValue(pending.promise) });

    const { props, rerender } = setup();
    rerender(<MeeraCameraSheet {...props} open={false} />);
    await act(async () => {
      pending.resolve(cam.stream);
      await pending.promise;
    });
    expect(cam.stopped()).toBeGreaterThan(0);
  });
});

describe('states', () => {
  it('denied: says so, offers Close, and the check stays disabled', async () => {
    const error = Object.assign(new Error('Permission denied'), { name: 'NotAllowedError' });
    setMediaDevices({ getUserMedia: vi.fn().mockRejectedValue(error) });

    const { props } = setup();
    await waitFor(() => expect(screen.getByText(/Camera access was denied/)).toBeInTheDocument());
    expect(checkButton()).toBeDisabled();
    const closeButtons = screen.getAllByRole('button', { name: 'Close camera' });
    await userEvent.click(closeButtons[closeButtons.length - 1]);
    expect(props.onOpenChange).toHaveBeenCalledWith(false);
  });

  it('unsupported (no camera API): says so, and the check stays disabled', () => {
    setMediaDevices(undefined);
    setup();
    expect(screen.getByText(/can’t open the camera here/)).toBeInTheDocument();
    expect(checkButton()).toBeDisabled();
  });

  it('offline: the check is disabled with a hint', async () => {
    setOnline(false);
    const cam = fakeCamera();
    setMediaDevices({ getUserMedia: vi.fn().mockResolvedValue(cam.stream) });
    setup();
    await waitFor(() => expect(screen.getByTestId('camera-sheet-video')).toBeInTheDocument());
    await waitFor(() => expect(screen.queryByText('Starting camera…')).toBeNull());
    expect(screen.getByText(/You’re offline/)).toBeInTheDocument();
    expect(checkButton()).toBeDisabled();
  });
});

describe('disclosure', () => {
  it('says the photo goes to the AI and is never saved, next to the button, plus the chat line', async () => {
    setMediaDevices({ getUserMedia: vi.fn().mockResolvedValue(fakeCamera().stream) });
    setup();
    const disclosure = screen.getByTestId('frame-check-disclosure');
    expect(disclosure.textContent).toContain('sends one photo');
    expect(disclosure.textContent).toMatch(/never saved/);
    expect(checkButton().parentElement?.contains(disclosure)).toBe(true);
    const kept = screen.getByTestId('camera-sheet-kept');
    expect(kept.textContent).toBe('Meera keeps the written result in this chat. The photo is never saved.');
    expect(checkButton().parentElement?.contains(kept)).toBe(true);
    await waitFor(() => expect(checkButton()).toBeEnabled());
  });

  it('Hindi creators get the whole sheet in Devanagari: the disclosure and the line under it in one script', async () => {
    setMediaDevices({ getUserMedia: vi.fn().mockResolvedValue(fakeCamera().stream) });
    setup({ lang: 'hi-IN' });
    expect(screen.getByTestId('frame-check-disclosure').textContent).toContain('सेव नहीं');
    const kept = screen.getByTestId('camera-sheet-kept').textContent ?? '';
    expect(kept).toContain('फ़ोटो कभी सेव नहीं होती');
    // No Roman Hinglish word beside the Devanagari disclosure (the product name stays as it is).
    expect(kept.replace('Meera', '')).not.toMatch(/[A-Za-z]/);
    await waitFor(() => expect(screen.getByRole('button', { name: 'सेट-अप चेक करें' })).toBeEnabled());
  });

  it('never says Co-pilot', async () => {
    setMediaDevices({ getUserMedia: vi.fn().mockResolvedValue(fakeCamera().stream) });
    setup({ shots: SHOTS });
    await waitFor(() => expect(checkButton()).toBeEnabled());
    expect(screen.getByTestId('meera-camera-sheet').textContent).not.toMatch(/co-?pilot/i);
  });
});

describe('shot strip', () => {
  it('with a script and no initial shot, opens on "Any shot"; next/prev walk the shots', async () => {
    setMediaDevices({ getUserMedia: vi.fn().mockResolvedValue(fakeCamera().stream) });
    setup({ shots: SHOTS });
    const label = () => screen.getByTestId('camera-sheet-shot-label').textContent;
    expect(label()).toBe('Any shot');
    expect(screen.getByRole('button', { name: 'Previous shot' })).toBeDisabled();

    fireEvent.click(screen.getByRole('button', { name: 'Next shot' }));
    expect(label()).toBe('Shot 1 of 2 · 0-3s · Close-up on your face');
    fireEvent.click(screen.getByRole('button', { name: 'Next shot' }));
    expect(label()).toBe('Shot 2 of 2 · 3-8s · Overhead - hands pouring');
    expect(screen.getByRole('button', { name: 'Next shot' })).toBeDisabled();
    await waitFor(() => expect(checkButton()).toBeEnabled());
  });

  it('opens on the shot it was given', async () => {
    setMediaDevices({ getUserMedia: vi.fn().mockResolvedValue(fakeCamera().stream) });
    setup({ shots: SHOTS, initialShotIndex: 1 });
    expect(screen.getByTestId('camera-sheet-shot-label').textContent).toBe('Shot 2 of 2 · 3-8s · Overhead - hands pouring');
    await waitFor(() => expect(checkButton()).toBeEnabled());
  });

  it('shows no strip without a script', async () => {
    setMediaDevices({ getUserMedia: vi.fn().mockResolvedValue(fakeCamera().stream) });
    setup();
    expect(screen.queryByTestId('camera-sheet-shot-strip')).toBeNull();
    await waitFor(() => expect(checkButton()).toBeEnabled());
  });
});

describe('capture', () => {
  /** Canvas stubs: jsdom has no 2D context. Records drawImage's arguments and the canvas size. */
  function stubCanvas() {
    const drawImage = vi.fn();
    const blob = new Blob(['jpeg'], { type: 'image/jpeg' });
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({ drawImage } as unknown as CanvasRenderingContext2D);
    const toBlob = vi
      .spyOn(HTMLCanvasElement.prototype, 'toBlob')
      .mockImplementation(function (this: HTMLCanvasElement, cb: BlobCallback) {
        cb(blob);
      });
    return { drawImage, blob, toBlob };
  }

  function sizeVideo(video: HTMLElement, v: { w: number; h: number }, box: { w: number; h: number }) {
    Object.defineProperty(video, 'videoWidth', { value: v.w, configurable: true });
    Object.defineProperty(video, 'videoHeight', { value: v.h, configurable: true });
    Object.defineProperty(video, 'clientWidth', { value: box.w, configurable: true });
    Object.defineProperty(video, 'clientHeight', { value: box.h, configurable: true });
  }

  it('captures what the preview shows (object-cover crop), hands it with the shot to onCapture, then closes', async () => {
    setMediaDevices({ getUserMedia: vi.fn().mockResolvedValue(fakeCamera().stream) });
    const { drawImage, blob, toBlob } = stubCanvas();
    const { props } = setup({ shots: SHOTS, initialShotIndex: 1 });
    await waitFor(() => expect(checkButton()).toBeEnabled());
    // A landscape 1280x720 webcam behind a 360x640 portrait preview: the preview shows only the
    // middle 405x720 of the frame.
    sizeVideo(screen.getByTestId('camera-sheet-video'), { w: 1280, h: 720 }, { w: 360, h: 640 });

    fireEvent.click(checkButton());
    await waitFor(() => expect(props.onCapture).toHaveBeenCalledTimes(1));

    const [, sx, sy, sw, sh, dx, dy, dw, dh] = drawImage.mock.calls[0];
    expect(sx).toBeCloseTo(437.5, 1);
    expect(sy).toBe(0);
    expect(sw).toBeCloseTo(405, 1);
    expect(sh).toBe(720);
    expect([dx, dy, dw, dh]).toEqual([0, 0, 405, 720]);
    expect(toBlob.mock.calls[0][1]).toBe('image/jpeg');
    expect(props.onCapture).toHaveBeenCalledWith(blob, SHOTS[1]);
    expect(props.onOpenChange).toHaveBeenCalledWith(false);
  });

  it('downscales by the LONG edge: a tall 1080x1920 frame comes out at most 800 high', async () => {
    setMediaDevices({ getUserMedia: vi.fn().mockResolvedValue(fakeCamera().stream) });
    const { drawImage } = stubCanvas();
    const { props } = setup();
    await waitFor(() => expect(checkButton()).toBeEnabled());
    // Box with the same aspect as the frame: no crop, only the downscale.
    sizeVideo(screen.getByTestId('camera-sheet-video'), { w: 1080, h: 1920 }, { w: 360, h: 640 });

    fireEvent.click(checkButton());
    await waitFor(() => expect(props.onCapture).toHaveBeenCalledTimes(1));
    const [, sx, sy, sw, sh, , , dw, dh] = drawImage.mock.calls[0];
    expect([sx, sy, sw, sh]).toEqual([0, 0, 1080, 1920]);
    expect(dh).toBe(800);
    expect(dw).toBe(450);
    // "Any shot": no shot goes with the photo.
    expect(props.onCapture).toHaveBeenCalledWith(expect.any(Blob), null);
  });

  it('a failed capture keeps the sheet open with a retry line, and calls nothing', async () => {
    setMediaDevices({ getUserMedia: vi.fn().mockResolvedValue(fakeCamera().stream) });
    const { props } = setup();
    await waitFor(() => expect(checkButton()).toBeEnabled());
    // videoWidth stays 0 in jsdom: nothing to capture.
    fireEvent.click(checkButton());
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Couldn’t take the photo'));
    expect(props.onCapture).not.toHaveBeenCalled();
    expect(props.onOpenChange).not.toHaveBeenCalled();
  });
});
