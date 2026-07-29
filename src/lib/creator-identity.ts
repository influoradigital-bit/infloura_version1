/**
 * CR-06 — building a real creator `User` for the auth store.
 *
 * Kept deliberately separate from `mock-user.ts`. That module fabricates a
 * plausible person for demo mode; this one only ever reflects fields that came
 * back from the server. Nothing here invents a name, a handle, or an email —
 * if the backend didn't send it, it stays empty and the UI renders a neutral
 * placeholder rather than someone else's identity.
 */
import type { User } from './types';

export interface CreatorUserSeed {
  id: string;
  email: string;
  displayName?: string | null;
  avatarUrl?: string | null;
}

export function buildCreatorUser(seed: CreatorUserSeed): User {
  const now = new Date();
  return {
    id: seed.id,
    email: seed.email,
    userType: 'CREATOR',
    status: 'ACTIVE',
    // The creator login endpoint doesn't report verification state; these are
    // structural requirements of the `User` type, not claims about the account.
    // Nothing in the creator shell reads them.
    emailVerified: false,
    phoneVerified: false,
    // Empty, never a stand-in name. Consumers must treat '' as "not loaded yet"
    // and render a skeleton — see `useCreatorIdentity`.
    displayName: seed.displayName?.trim() || '',
    avatarUrl: seed.avatarUrl || undefined,
    createdAt: now,
    updatedAt: now,
  };
}
