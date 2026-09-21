import { act, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { VoicePoweredOrb } from '@/components/ui/voice-powered-orb';
import { VoiceChat } from '@/components/ui/ia-siri-chat';

describe('VoicePoweredOrb — microphone only when asked', () => {
  const getUserMedia = vi.fn();

  beforeEach(() => {
    getUserMedia.mockReset();
    getUserMedia.mockRejectedValue(new Error('denied in test'));
    Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: { getUserMedia } });
  });

  it('never requests the microphone by default', () => {
    render(<VoicePoweredOrb activity={0.7} />);
    expect(getUserMedia).not.toHaveBeenCalled();
  });

  it('requests it once when voice control is turned on, and not again on re-render', () => {
    const { rerender } = render(<VoicePoweredOrb enableVoiceControl />);
    rerender(<VoicePoweredOrb enableVoiceControl activity={0.3} hue={40} />);
    expect(getUserMedia).toHaveBeenCalledTimes(1);
  });

  it('shows the static fallback when WebGL is unavailable (jsdom has none)', () => {
    const { container } = render(<VoicePoweredOrb />);
    expect(container.querySelector('canvas')).toBeNull();
    const fallback = container.querySelector('[aria-hidden="true"]') as HTMLElement;
    expect(fallback.hidden).toBe(false);
  });
});

describe('VoiceChat — controlled by real status', () => {
  afterEach(() => vi.useRealTimers());

  it.each([
    ['idle', 'Tap to speak'],
    ['listening', 'Listening…'],
    ['thinking', 'Thinking…'],
    ['speaking', 'Speaking…'],
  ] as const)('%s shows "%s"', (status, text) => {
    render(<VoiceChat status={status} />);
    expect(screen.getByRole('status')).toHaveTextContent(text);
  });

  it('does not cycle on its own unless demoMode is set', () => {
    vi.useFakeTimers();
    render(<VoiceChat status="idle" />);
    act(() => {
      vi.advanceTimersByTime(10_000);
    });
    expect(screen.getByRole('status')).toHaveTextContent('Tap to speak');
  });

  it('shows no fake volume meter or number while speaking', () => {
    render(<VoiceChat status="speaking" />);
    expect(screen.queryByText(/%/)).toBeNull();
    expect(screen.queryByText(/\d\d:\d\d/)).toBeNull();
  });

  it('calls onToggle from the button and disables it while thinking', () => {
    const onToggle = vi.fn();
    const { rerender } = render(<VoiceChat status="idle" onToggle={onToggle} />);
    screen.getByRole('button', { name: 'Start speaking' }).click();
    expect(onToggle).toHaveBeenCalledTimes(1);
    rerender(<VoiceChat status="thinking" onToggle={onToggle} />);
    expect(screen.getByRole('button')).toBeDisabled();
  });
});

describe('GlowingInput', () => {
  it('submits the trimmed value on Enter and clears', async () => {
    const { GlowingInput } = await import('@/components/ui/glowing-input');
    const onSubmit = vi.fn();
    render(<GlowingInput onSubmit={onSubmit} />);
    const input = screen.getByLabelText('Ask Meera') as HTMLInputElement;
    const { fireEvent } = await import('@testing-library/react');
    fireEvent.change(input, { target: { value: '  hook ideas  ' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(onSubmit).toHaveBeenCalledWith('hook ideas');
    expect(input.value).toBe('');
  });

  it('does not submit on Enter while an IME is composing (Hindi input)', async () => {
    const { GlowingInput } = await import('@/components/ui/glowing-input');
    const { fireEvent } = await import('@testing-library/react');
    const onSubmit = vi.fn();
    render(<GlowingInput onSubmit={onSubmit} />);
    const input = screen.getByLabelText('Ask Meera');
    fireEvent.change(input, { target: { value: 'namaste' } });
    fireEvent.keyDown(input, { key: 'Enter', isComposing: true });
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('keeps the send button disabled when empty, and gives each instance its own id', async () => {
    const { GlowingInput } = await import('@/components/ui/glowing-input');
    render(
      <>
        <GlowingInput />
        <GlowingInput />
      </>,
    );
    const [a, b] = screen.getAllByLabelText('Ask Meera');
    expect(a.id).not.toBe(b.id);
    screen.getAllByRole('button', { name: 'Send' }).forEach((btn) => expect(btn).toBeDisabled());
  });
});
