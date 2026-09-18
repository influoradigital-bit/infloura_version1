import React from 'react';
import { useAuthGuardState } from '@/lib/use-auth-guard-state';

/**
 * For brand routes that must stay reachable signed-OUT (a brand-new signup on /brand/onboarding,
 * an invitee on /brand/invite) but make authenticated calls when there IS a session.
 *
 * In live mode the access token lives in memory only (F-0551), and the one thing that recovers it
 * after a reload, a restored tab or a click on an emailed link is `api.auth.bootstrap`, which
 * until now only ran inside `ProtectedRoute`. These two routes sit outside it, so on any cold
 * load they saw the storage hint, believed they were signed in, and sent their first request with
 * no Authorization header: onboarding's "Continue" failed UNAUTHENTICATED and an invite could
 * never be accepted, since an emailed link is always a cold load.
 *
 * Unlike `ProtectedRoute` this never redirects: signed-out is a valid state here. It only waits
 * for the answer, so the page underneath decides from the real session rather than the hint.
 */
export const BrandSessionBootstrap = ({ children }: { children: React.ReactNode }) => {
  const authState = useAuthGuardState('brand');
  if (authState === 'checking') return null;
  return <>{children}</>;
};
