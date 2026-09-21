import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { MeeraVoiceMode, type MeeraVoiceModeProps } from './MeeraVoiceMode';

function setup(overrides: Partial<MeeraVoiceModeProps> = {}) {
  const props: MeeraVoiceModeProps = {
    open: true,
    onOpenChange: vi.fn(),
    status: 'idle',
    transcript: '',
    micSupported: true,
    onMicToggle: vi.fn(),
    onSend: vi.fn(),
    onSpeakAgain: vi.fn(),
    ...overrides,
  };
  render(<MeeraVoiceMode {...props} />);
  return props;
}

describe('MeeraVoiceMode', () => {
  it('idle: one mic button, nothing sendable', async () => {
    const p = setup();
    expect(screen.getByRole('status')).toHaveTextContent('Tap the mic and speak');
    expect(screen.queryByRole('button', { name: 'Send' })).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: 'Start speaking' }));
    expect(p.onMicToggle).toHaveBeenCalledTimes(1);
  });

  it('listening: the mic becomes a pressed stop button', () => {
    setup({ status: 'listening' });
    expect(screen.getByRole('button', { name: 'Stop listening' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('status')).toHaveTextContent('Listening…');
  });

  it('a transcript is shown and sent ONLY when Send is pressed; Speak again clears', async () => {
    const p = setup({ transcript: 'what should I post this week' });
    expect(screen.getByText('what should I post this week')).toBeInTheDocument();
    expect(p.onSend).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole('button', { name: 'Send' }));
    expect(p.onSend).toHaveBeenCalledTimes(1);
    await userEvent.click(screen.getByRole('button', { name: /Speak again/ }));
    expect(p.onSpeakAgain).toHaveBeenCalledTimes(1);
  });

  it('thinking: mic disabled; speaking: shows Meera\'s last reply', () => {
    setup({ status: 'thinking' });
    expect(screen.getByRole('button', { name: 'Start speaking' })).toBeDisabled();
  });

  it('speaking shows the last reply', () => {
    setup({ status: 'speaking', lastReply: 'Try a form-check reel this week.' });
    expect(screen.getByText('Try a form-check reel this week.')).toBeInTheDocument();
  });

  it('Hindi creators get Hindi copy', () => {
    setup({ language: 'hi-IN' });
    expect(screen.getByRole('status')).toHaveTextContent('माइक दबाएँ और बोलें');
  });

  it('no mic: says so and offers no mic button', () => {
    setup({ micSupported: false });
    expect(screen.getByText(/Voice input is not available/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Start speaking' })).toBeNull();
  });

  it('close button closes', async () => {
    const p = setup();
    await userEvent.click(screen.getByRole('button', { name: 'Close voice mode' }));
    expect(p.onOpenChange).toHaveBeenCalledWith(false);
  });
});
