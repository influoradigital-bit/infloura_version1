/**
 * brandDisputes API helper — Kv3b (Kavya)
 * Mock-mode contract for dispute list used by brand-disputes (and future creator-disputes).
 *
 * Run: npx vitest run src/lib/brand-disputes-api.test.ts
 */

import { describe, it, expect } from 'vitest';
import { brandDisputes, isApiLive } from '@/lib/api';

describe('brandDisputes.list (mock mode)', () => {
  it('runs in mock mode under vitest (no VITE_API_MODE=live)', () => {
    expect(isApiLive()).toBe(false);
  });

  it('returns demo rows with lifecycle fields populated', async () => {
    const rows = await brandDisputes.list();

    expect(rows.length).toBeGreaterThanOrEqual(1);
    const first = rows[0];
    expect(first.collaborationId).toBeTruthy();
    expect(first.campaignName).toBeTruthy();
    expect(first.counterpartyName).toBeTruthy();
    expect(first.currency).toBe('INR');
    expect(typeof first.dealValue).toBe('number');
    // Demo fixtures include granular lifecycle (live fallback omits these)
    expect(first.disputeStatus).toBeDefined();
    expect(first.reason).toBeTruthy();
  });
});
