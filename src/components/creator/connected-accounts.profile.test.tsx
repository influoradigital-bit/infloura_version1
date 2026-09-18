/**
 * ConnectedAccounts — shows WHICH Instagram account is connected (F-0950).
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * `GET /meta/oauth/status` has returned the connected account's handle and follower count for a
 * long time, but this card never displayed them: a connected creator saw only "Profile, media &
 * insights connected". Meta's App Review for `instagram_business_basic` requires the screencast to
 * show the connected account's username or profile picture, so without this the submission is a
 * guaranteed rejection. The card also claimed a Facebook Page was "Connected" for a creator who
 * connected with Instagram login and has no Page.
 *
 * Run: npx vitest run src/components/creator/connected-accounts.profile.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ConnectedAccounts } from './connected-accounts';
import { useMetaConnection } from '@/hooks/creator/useMetaConnection';
import type { MetaConnectedProfile, UseMetaConnectionResult } from '@/hooks/creator/useMetaConnection';

vi.mock('@/hooks/use-toast', () => ({
  useToast: () => ({ toast: vi.fn() }),
}));

vi.mock('@/hooks/creator/useMetaConnection', () => ({
  useMetaConnection: vi.fn(),
}));

const mockedHook = vi.mocked(useMetaConnection);

const IG_PROFILE: MetaConnectedProfile = {
  handle: '@ig_only_creator',
  followers: 12345,
  mediaCount: 87,
  profilePictureUrl: 'https://scontent.cdninstagram.com/pic.jpg',
  authPath: 'INSTAGRAM_LOGIN',
};

function result(
  profile: MetaConnectedProfile | null,
  overrides: Partial<UseMetaConnectionResult> = {},
): UseMetaConnectionResult {
  return {
    data: {
      connected: true,
      scopes: ['instagram_business_basic', 'instagram_business_manage_insights'],
      accountType: 'business',
    },
    profile,
    loading: false,
    error: null,
    refresh: vi.fn(),
    ...overrides,
  };
}

describe('ConnectedAccounts — connected account details (F-0950)', () => {
  beforeEach(() => {
    mockedHook.mockReset();
  });

  it('shows the Instagram handle, linked to the profile, with followers, posts and login type', () => {
    mockedHook.mockReturnValue(result(IG_PROFILE));

    render(<ConnectedAccounts />);

    const link = screen.getByRole('link', { name: '@ig_only_creator' });
    expect(link).toHaveAttribute('href', 'https://www.instagram.com/ig_only_creator/');
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    expect(screen.getByText('12,345 followers · 87 posts · via Instagram login')).toBeInTheDocument();
  });

  it('shows the profile picture', () => {
    mockedHook.mockReturnValue(result(IG_PROFILE));

    render(<ConnectedAccounts />);

    const avatar = screen.getByTestId('instagram-avatar');
    expect(avatar).toHaveAttribute('src', IG_PROFILE.profilePictureUrl);
    expect(avatar).toHaveAttribute('alt', '@ig_only_creator profile picture');
  });

  it('falls back to the Instagram mark when the picture fails to load (signed CDN URLs expire)', () => {
    mockedHook.mockReturnValue(result(IG_PROFILE));

    render(<ConnectedAccounts />);
    fireEvent.error(screen.getByTestId('instagram-avatar'));

    expect(screen.queryByTestId('instagram-avatar')).not.toBeInTheDocument();
    // The handle survives the image failure.
    expect(screen.getByRole('link', { name: '@ig_only_creator' })).toBeInTheDocument();
  });

  it('does not claim a Facebook Page is connected for an Instagram-login creator', () => {
    mockedHook.mockReturnValue(result(IG_PROFILE));

    render(<ConnectedAccounts />);

    expect(screen.getByText('Not needed — you connected with Instagram login')).toBeInTheDocument();
    expect(screen.queryByText('Page list connected')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Connect Facebook Page' })).not.toBeInTheDocument();
    // Exactly one "Connected" badge: the Instagram row's.
    expect(screen.getAllByText('Connected')).toHaveLength(1);
  });

  it('keeps the Facebook Page row for a creator who connected through their Page', () => {
    mockedHook.mockReturnValue(
      result({ ...IG_PROFILE, authPath: 'FACEBOOK_LOGIN', mediaCount: null, profilePictureUrl: null }),
    );

    render(<ConnectedAccounts />);

    expect(screen.getByText('Page list connected')).toBeInTheDocument();
    expect(screen.getByText('12,345 followers · via Facebook Page')).toBeInTheDocument();
    expect(screen.queryByTestId('instagram-avatar')).not.toBeInTheDocument();
  });

  it('labels the Instagram-login permissions in plain words, not raw scope ids', () => {
    mockedHook.mockReturnValue(result(IG_PROFILE));

    render(<ConnectedAccounts />);

    expect(screen.getByText('· Instagram profile & media')).toBeInTheDocument();
    expect(screen.getByText('· Instagram insights')).toBeInTheDocument();
    expect(screen.queryByText(/instagram_business_basic/)).not.toBeInTheDocument();
  });

  it('never builds a link out of something that is not an Instagram username', () => {
    mockedHook.mockReturnValue(result({ ...IG_PROFILE, handle: '@evil"/><script>' }));

    render(<ConnectedAccounts />);

    expect(screen.queryByRole('link')).not.toBeInTheDocument();
    expect(screen.getByText('@evil"/><script>')).toBeInTheDocument();
  });

  it('shows no account details while the connection is still being verified', () => {
    mockedHook.mockReturnValue(result(IG_PROFILE, { loading: true }));

    render(<ConnectedAccounts />);

    expect(screen.queryByText('@ig_only_creator')).not.toBeInTheDocument();
    expect(screen.queryByTestId('instagram-avatar')).not.toBeInTheDocument();
  });

  it('falls back to the generic line when the backend could not read any details', () => {
    mockedHook.mockReturnValue(
      result({ handle: null, followers: null, mediaCount: null, profilePictureUrl: null, authPath: null }),
    );

    render(<ConnectedAccounts />);

    expect(screen.getByText('Instagram')).toBeInTheDocument();
    expect(screen.getByText('Profile, media & insights connected')).toBeInTheDocument();
  });
});
