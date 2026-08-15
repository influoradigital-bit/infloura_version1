import { describe, it, expect } from 'vitest';
import { canManageBilling } from './useBrandBillingAccess';

/**
 * F-0132 regression gate (no-frontend-role-gate, money-path). The billing Upgrade/Cancel controls
 * must be gated to the same roles the server allows: BillingController requires OWNER/ADMIN on
 * POST /billing/checkout and /cancel. MANAGER/MEMBER/VIEWER must NOT see an enabled control that
 * only 403s on click. Unknown role and mock mode fail OPEN (server stays source of truth).
 */
describe('canManageBilling (F-0132)', () => {
  it('allows OWNER and ADMIN (the server-allowed roles)', () => {
    expect(canManageBilling('OWNER', true)).toBe(true);
    expect(canManageBilling('ADMIN', true)).toBe(true);
  });

  it('blocks MANAGER, MEMBER, VIEWER — mirroring the backend requireRole(OWNER, ADMIN) 403', () => {
    expect(canManageBilling('MANAGER', true)).toBe(false);
    expect(canManageBilling('MEMBER', true)).toBe(false);
    expect(canManageBilling('VIEWER', true)).toBe(false);
  });

  it('fails OPEN on an unresolved role (loading / no membership row) so the server stays authoritative', () => {
    expect(canManageBilling(null, true)).toBe(true);
  });

  it('fails OPEN in mock mode — there is no server gate to mirror', () => {
    expect(canManageBilling('MANAGER', false)).toBe(true);
    expect(canManageBilling(null, false)).toBe(true);
  });
});
