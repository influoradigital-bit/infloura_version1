/**
 * Camera knowledge v5 (2026-09-24) — the Shoot Check page asks which phone the creator films on
 * and saves it to their Meera settings (PUT /creator/agent-preferences/phone). The frame check
 * reads the SAVED phone on the server, so this field is the creator's way to make the tips fit.
 *
 * Run: npx vitest run src/pages/creator-shoot-check.phone.test.tsx
 */
import * as React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

const { getPreferences, updatePhoneModel } = vi.hoisted(() => ({
  getPreferences: vi.fn(),
  updatePhoneModel: vi.fn(),
}));

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    api: {
      ...actual.api,
      creatorAgentPrefs: {
        ...actual.api.creatorAgentPrefs,
        getPreferences: (...args: unknown[]) => getPreferences(...args),
        updatePhoneModel: (...args: unknown[]) => updatePhoneModel(...args),
      },
    },
  };
});

// The page's own job here is the phone field; the layout chrome and the camera panel are
// covered by their own tests and need a router / camera this test does not provide.
vi.mock('@/components/creator/creator-layout', () => ({
  CreatorLayout: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));
vi.mock('@/components/creator/shoot-check/ShootCheckPanel', () => ({
  ShootCheckPanel: () => <div data-testid="panel" />,
}));

import CreatorShootCheckPage from './creator-shoot-check';

describe('Shoot Check page — which phone', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows the saved phone and keeps Save off until it changes', async () => {
    getPreferences.mockResolvedValue({ creator_language: 'en-IN', phone_model: 'OPPO F25 Pro' });
    render(<CreatorShootCheckPage />);

    const field = await screen.findByLabelText('Which phone are you filming on?');
    await waitFor(() => expect(field).toHaveValue('OPPO F25 Pro'));
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
  });

  it('saves a new phone and says so', async () => {
    getPreferences.mockResolvedValue({ creator_language: 'en-IN', phone_model: null });
    updatePhoneModel.mockResolvedValue({ creator_language: 'en-IN', phone_model: 'OPPO Reno 14 Pro' });
    const user = userEvent.setup();
    render(<CreatorShootCheckPage />);

    const field = await screen.findByLabelText('Which phone are you filming on?');
    await waitFor(() => expect(field).toBeEnabled());
    await user.type(field, 'OPPO Reno 14 Pro');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(updatePhoneModel).toHaveBeenCalledWith('OPPO Reno 14 Pro'));
    expect(await screen.findByText('Saved. Tips will fit this phone.')).toBeInTheDocument();
  });

  it('never blocks the camera when preferences fail to load', async () => {
    getPreferences.mockRejectedValue(new Error('down'));
    render(<CreatorShootCheckPage />);
    expect(screen.getByTestId('panel')).toBeInTheDocument();
    expect(screen.getByLabelText('Which phone are you filming on?')).toBeDisabled();
  });
});
