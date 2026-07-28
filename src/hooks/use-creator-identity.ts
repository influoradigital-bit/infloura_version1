/**
 * CR-06 — the single source of the logged-in creator's identity for the shell.
 *
 * The defect this exists to close: `creator-login.tsx` only called the auth
 * store's `login()` when NOT in live mode, so on the deployed build `user` was
 * `null` after every real login. Every `user?.displayName || 'Creator Account'`
 * and `user?.email || '@priya_sharma'` in `creator-layout.tsx` then fell
 * through to its demo default, and a creator logged in as Tejas saw initials
 * "IN", the name "Creator Account", and Priya Sharma's handle. Shipping one
 * user's handle as another user's fallback is an identity-leak pattern, so the
 * literals are gone and this hook returns `null` fields instead — the layout
 * renders a neutral skeleton when it doesn't know who someone is.
 *
 * Two sources, in order, because the auth store is not persisted
 * (`partialize: () => ({})` in `lib/store.ts`) and therefore empties on every
 * hard reload:
 *   1. `getCreatorSession()` — synchronous, from what login wrote to
 *      localStorage. Avoids a skeleton flash on reload.
 *   2. `GET /me/creator-profile` — authoritative display name, @handle and
 *      avatar, and the only source of the handle at all.
 *
 * The store is back-filled from (2) so other consumers of `useAuthStore` see a
 * real user too.
 */
import * as React from 'react';
import { api, isApiLive } from '@/lib/api';
import { getCreatorSession } from '@/lib/auth-session';
import { buildCreatorUser } from '@/lib/creator-identity';
import { useAuthStore } from '@/lib/store';

export interface CreatorIdentity {
  /** Full name. `null` when unknown — render a skeleton, never a placeholder name. */
  displayName: string | null;
  email: string | null;
  /** Public handle including the leading '@'. `null` when the creator has no username set. */
  handle: string | null;
  avatarUrl: string | null;
}

/** Normalises '' / whitespace / undefined to null, so `??` chains behave. */
const orNull = (v: string | null | undefined): string | null => v?.trim() || null;

export function useCreatorIdentity(): { identity: CreatorIdentity; loading: boolean } {
  const { user, setUser } = useAuthStore();
  const [profile, setProfile] = React.useState<CreatorIdentity | null>(null);
  // Seeded from the token rather than flipped to `true` inside the effect —
  // a synchronous setState in an effect body triggers a cascading render
  // (react-hooks/set-state-in-effect). A creator with a token is loading from
  // the very first paint anyway, which is exactly what this initialiser says.
  const [loading, setLoading] = React.useState(
    () => typeof window !== 'undefined' && !!localStorage.getItem('creator_token'),
  );
  // One fetch per mount. Without this the `setUser` below would re-run the
  // effect through the `user` dependency and loop.
  const fetchedRef = React.useRef(false);

  React.useEffect(() => {
    if (fetchedRef.current) return;
    if (!localStorage.getItem('creator_token')) return;
    fetchedRef.current = true;

    let cancelled = false;
    (async () => {
      try {
        const me = await api.creatorProfile.getMe();
        if (cancelled) return;
        const next: CreatorIdentity = {
          displayName: me.displayName?.trim() || null,
          email: getCreatorSession()?.email ?? null,
          handle: me.username ? `@${me.username}` : null,
          avatarUrl: me.avatarUrl || null,
        };
        setProfile(next);

        // Back-fill the store so anything else reading `useAuthStore().user`
        // (and `logout()`) sees the real creator.
        const session = getCreatorSession();
        if (isApiLive() && session) {
          setUser(
            buildCreatorUser({
              id: session.userId,
              email: session.email,
              displayName: next.displayName,
              avatarUrl: next.avatarUrl,
            }),
          );
        }
      } catch (err) {
        // Non-fatal and deliberately not toasted: the shell still renders, just
        // without a name. Logged because a persistently failing /me is worth
        // seeing in the console — it means every creator is browsing anonymously.
        console.error('[creator-identity] Failed to load creator profile', err);
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [setUser]);

  const identity = React.useMemo<CreatorIdentity>(() => {
    const session = getCreatorSession();
    // Most specific source wins; every branch can only echo a value that came
    // from the server or from this session's own login. There is no default.
    return {
      displayName:
        orNull(profile?.displayName) ??
        orNull(user?.displayName) ??
        orNull(session?.displayName),
      email: orNull(profile?.email) ?? orNull(user?.email) ?? orNull(session?.email),
      handle: orNull(profile?.handle),
      avatarUrl: orNull(profile?.avatarUrl) ?? orNull(user?.avatarUrl),
    };
  }, [profile, user]);

  return { identity, loading };
}
