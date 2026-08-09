import { describe, it, expect } from 'vitest';
import { EMPTY_WALLET } from './dashboard-page';
import { walletRunwayHealth } from '@/lib/wallet-runway';

/**
 * F-0103 tripwire (fabricated-critical-state, money-path). The dashboard's pre-load / error /
 * 404'd-new-workspace zero-state (EMPTY_WALLET) must classify as 'healthy', NOT 'critical'. The
 * bug set EMPTY_WALLET.runwayDays to 0, which the health rule maps to 'critical' — so a wallet
 * whose balance is merely unknown flashed a red Critical badge + Recharge CTA on every load and
 * permanently on a rejected GET /wallet. If anyone reverts that default to 0 (or any 0–14 value),
 * this fails: that means "unknown balance", never "out of runway".
 */
describe('dashboard EMPTY_WALLET zero-state (F-0103)', () => {
  it('uses null (unknown), not a critical-triggering 0, for runwayDays', () => {
    expect(EMPTY_WALLET.runwayDays).toBeNull();
  });

  it('classifies the unknown/error wallet state as healthy, never critical', () => {
    expect(walletRunwayHealth(EMPTY_WALLET.runwayDays)).toBe('healthy');
  });
});
