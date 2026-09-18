/**
 * useMetaConnection — exposes WHICH account is connected (F-0950).
 *
 * Run: npx vitest run src/hooks/creator/useMetaConnection.profile.test.ts
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { api } from '@/lib/api';
import { toConnectedProfile, useMetaConnection } from './useMetaConnection';

describe('toConnectedProfile (F-0950)', () => {
  it('maps the status response display fields', () => {
    expect(
      toConnectedProfile(
        {
          connected: true,
          grantedScopes: [],
          handle: '@c',
          followers: 5,
          mediaCount: 2,
          profilePictureUrl: 'https://x/p.jpg',
          authPath: 'INSTAGRAM_LOGIN',
        },
        true,
      ),
    ).toEqual({
      handle: '@c',
      followers: 5,
      mediaCount: 2,
      profilePictureUrl: 'https://x/p.jpg',
      authPath: 'INSTAGRAM_LOGIN',
    });
  });

  it('turns absent fields into null, not undefined', () => {
    expect(toConnectedProfile({ connected: true, grantedScopes: [] }, true)).toEqual({
      handle: null,
      followers: null,
      mediaCount: null,
      profilePictureUrl: null,
      authPath: null,
    });
  });

  it('is null when the connection is not usable (e.g. a personal account), even if a handle came back', () => {
    expect(
      toConnectedProfile({ connected: true, grantedScopes: [], handle: '@personal' }, false),
    ).toBeNull();
  });
});

describe('useMetaConnection profile (F-0950)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.spyOn(api.metaOAuth, 'getLocalConnectionState').mockReturnValue({
      connected: false,
      scopes: [],
      accountType: 'business',
    });
    vi.spyOn(api.metaOAuth, 'setLocalConnectionState').mockImplementation(() => {});
  });

  it('exposes the verified account details after the status check', async () => {
    vi.spyOn(api.metaOAuth, 'status').mockResolvedValue({
      connected: true,
      grantedScopes: ['instagram_business_basic'],
      handle: '@ig_only_creator',
      followers: 12345,
      mediaCount: 87,
      profilePictureUrl: 'https://x/p.jpg',
      authPath: 'INSTAGRAM_LOGIN',
    });

    const { result } = renderHook(() => useMetaConnection());

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.profile).toEqual({
      handle: '@ig_only_creator',
      followers: 12345,
      mediaCount: 87,
      profilePictureUrl: 'https://x/p.jpg',
      authPath: 'INSTAGRAM_LOGIN',
    });
  });

  it('has no profile when the backend says disconnected', async () => {
    vi.spyOn(api.metaOAuth, 'status').mockResolvedValue({ connected: false, grantedScopes: [] });

    const { result } = renderHook(() => useMetaConnection());

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.profile).toBeNull();
  });
});
