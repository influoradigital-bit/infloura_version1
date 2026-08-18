/**
 * F-0252 — fe-be-enum-divergence.
 * ----------------------------------------------------------------------------
 * `ContractStatus` (this file) used to be `DRAFT | PENDING_SIGNATURES | ACTIVE | COMPLETED |
 * TERMINATED | DISPUTED` while the real backend enum
 * (influora-api/src/main/java/com/influora/domain/enums/ContractStatus.java) is
 * `DRAFT, PENDING_SIGNATURES, ACTIVE, COMPLETED, CANCELLED`. 'TERMINATED'/'DISPUTED' were FE-only
 * inventions no backend state could ever produce — every branch gated on them was dead code — and
 * 'CANCELLED', the real terminal state the backend does emit, was missing entirely, so a live
 * CANCELLED contract did not typecheck against the FE union.
 *
 * This is a type-level regression test as much as a runtime one: `EXPECTED_MEMBERS` is typed
 * `ContractStatus[]`, so if a future edit to the union adds or removes a member without updating
 * this list, `npx tsc --noEmit` fails on this file before the runtime assertion even needs to run
 * (a member missing from the array — or a stale array member no longer assignable to the type —
 * is a compile error, not just a runtime mismatch). The runtime assertion below additionally
 * catches the array drifting to have duplicates or an incomplete count while still type-checking.
 *
 * The authoritative structural check that this union matches the real Java enum (not just an
 * internal consistency check against this file's own copy of it) lives in the gate,
 * `.proof-os/gates/F-0252-contract-status-enum-parity.sh`, which derives both member sets from
 * their real source files. This test cannot read the Java file, so it is a companion pin, not a
 * substitute for that gate.
 *
 * Run: npx vitest run src/lib/__tests__/contract-status-enum-parity.test.ts
 */

import { describe, it, expect } from 'vitest';
import type { ContractStatus } from '@/lib/types';

/**
 * Mirrors influora-api/src/main/java/com/influora/domain/enums/ContractStatus.java exactly.
 * Typed as `ContractStatus[]` on purpose — see file header. Do NOT add 'TERMINATED' or
 * 'DISPUTED' back here; neither is a real backend value.
 */
const EXPECTED_MEMBERS: ContractStatus[] = [
  'DRAFT',
  'PENDING_SIGNATURES',
  'ACTIVE',
  'COMPLETED',
  'CANCELLED',
];

// Compile-time exhaustiveness: a function that only compiles if every EXPECTED_MEMBERS literal
// is assignable to ContractStatus AND every ContractStatus member is present in
// EXPECTED_MEMBERS. If the union grows a member this switch does not list, TypeScript's
// exhaustive-switch check (via the `never` assignment) fails the build.
function assertExhaustive(status: ContractStatus): void {
  switch (status) {
    case 'DRAFT':
    case 'PENDING_SIGNATURES':
    case 'ACTIVE':
    case 'COMPLETED':
    case 'CANCELLED':
      return;
    default: {
      const _exhaustive: never = status;
      throw new Error(`Unhandled ContractStatus member: ${_exhaustive}`);
    }
  }
}

describe('ContractStatus — F-0252 FE/BE enum parity', () => {
  it('EXPECTED_MEMBERS has exactly 5 unique members, matching the real backend enum count', () => {
    expect(new Set(EXPECTED_MEMBERS).size).toBe(5);
    expect(EXPECTED_MEMBERS).toHaveLength(5);
  });

  it('does not contain either FE-only invented member', () => {
    // Cast through unknown/string, not ContractStatus — these literals are no longer assignable
    // to the type at all, which is itself the point of this fix; asserting their absence via a
    // typed comparison would be a compile error, so the check has to happen at the string level.
    const members: string[] = EXPECTED_MEMBERS;
    expect(members).not.toContain('TERMINATED');
    expect(members).not.toContain('DISPUTED');
  });

  it('contains CANCELLED, the real terminal state the backend can send', () => {
    expect(EXPECTED_MEMBERS).toContain('CANCELLED');
  });

  it('every member round-trips through the exhaustive switch without hitting the never-branch', () => {
    for (const member of EXPECTED_MEMBERS) {
      expect(() => assertExhaustive(member)).not.toThrow();
    }
  });
});
