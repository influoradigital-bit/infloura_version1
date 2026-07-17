/**
 * creatorDisputes API helper — Kv3b (Kavya)
 * Mock-mode contract for dispute list (page UI still Ananya #38).
 * Stand-in until creator-disputes.tsx lands — then add creator-disputes.test.tsx.
 *
 * Run: npx vitest run src/lib/creator-disputes-api.test.ts
 */

import { describe, it, expect } from 'vitest';
import { creatorDisputes, isApiLive } from '@/lib/api';

describe('creatorDisputes.list (mock mode)', () => {
  it('runs in mock mode under vitest (no VITE_API_MODE=live)', () => {
    expect(isApiLive()).toBe(false);
  });

  it('returns demo rows with lifecycle fields populated', async () => {
    const rows = await creatorDisputes.list();

    expect(rows.length).toBeGreaterThanOrEqual(1);
    const first = rows[0];
    expect(first.collaborationId).toBeTruthy();
    expect(first.campaignName).toBeTruthy();
    expect(first.counterpartyName).toBeTruthy();
    expect(first.currency).toBe('INR');
    expect(typeof first.dealValue).toBe('number');
    expect(first.disputeStatus).toBeDefined();
    expect(first.reason).toBeTruthy();
  });

  it('lists eligible deals for open-dispute in mock mode', async () => {
    const eligible = await creatorDisputes.listEligibleDeals();
    expect(eligible.length).toBeGreaterThanOrEqual(1);
    expect(eligible[0].id).toBeTruthy();
    expect(eligible[0].escrowFunded).toBe(true);
  });
});
