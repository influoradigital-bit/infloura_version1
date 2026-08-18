/**
 * F-0287/F-0303 decline-wording variant switch — CEO ruling 2026-08-18, Decision 1
 * (.proof-os/tasks/T-RULING-0818/SWAPNIL-RULING.md).
 *
 * Isolated in its OWN module, separate from application-status.ts, on purpose: a test needs to
 * flip this in isolation (`vi.doMock` + `vi.resetModules`, then a fresh `import` of
 * application-status.ts) and exercise the REAL `getApplicationStatusLabel`/`getDeclineWording`
 * under the other variant. Mocking those functions themselves — as an earlier version of this
 * feature's test suite did — lets a hardcoded branch inside them go completely undetected,
 * because the mock replaces the exact code path the test was supposed to be exercising
 * (review condition C1, 2026-08-18: a hardcoded `getApplicationStatusLabel` CANCELLED branch
 * stayed green against a test that mocked `getApplicationStatusLabel` itself). Keeping the
 * constant in its own module lets a test swap ONLY the constant and still run the genuine,
 * unmocked function on top of it.
 */

export type DeclineWordingVariant = 'arbitration' | 'spec';

/** DEFAULT — do not flip without the customer explicitly requesting the override the ruling allows. */
export const DECLINE_WORDING_VARIANT: DeclineWordingVariant = 'arbitration';
