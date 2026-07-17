/**
 * H-30: in-memory access-token store.
 *
 * Access JWTs used to live in `localStorage` (`brand_token` / `creator_token`), which is
 * readable by any injected script — an XSS bug anywhere in the app (or a compromised
 * third-party script) could steal a durable, replayable token. The refresh token was
 * already correctly HttpOnly-cookie-only (Kabir A1); this closes the matching gap on the
 * access token by keeping it only in a module-level variable, never in Web Storage.
 *
 * Trade-off (intended): a hard page reload wipes this store, since JS memory doesn't
 * survive a reload. `App.tsx`'s `AuthBootstrap` gate calls `auth.bootstrap(role)` — which
 * hits `POST /auth/refresh` using the HttpOnly refresh cookie — to repopulate it before any
 * `ProtectedRoute` renders, so a returning user with a valid refresh cookie never sees a
 * false "logged out" state; an attacker who can only read `localStorage` (not memory) gets
 * nothing replayable.
 */
export type TokenRole = 'brand' | 'creator';

const tokens: Partial<Record<TokenRole, string>> = {};

export function getMemoryToken(role: TokenRole): string | null {
  return tokens[role] ?? null;
}

export function setMemoryToken(role: TokenRole, token: string): void {
  tokens[role] = token;
}

export function clearMemoryToken(role: TokenRole): void {
  delete tokens[role];
}

export function hasMemoryToken(role: TokenRole): boolean {
  return !!tokens[role];
}
