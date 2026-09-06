import { describe, expect, it } from 'vitest';

import { consentStorageKeyFor } from '@/pages/festival-box-edition';

/**
 * [Kabir H-6] The half of the edition-scoping fix that lives on the PAGE, not in the hook.
 *
 * `usePageConsent` always isolated distinct storage keys correctly — it was the page that handed
 * it ONE key (`influora:festival-box:sponsor-pixel-consent`) for every edition. A hook-level test
 * passing two different keys therefore proves nothing about this bug: it passes identically
 * against the broken page. This is the assertion that actually fails if the shared key comes back.
 */
describe('festival box consent storage key (H-6)', () => {
  it('is scoped per edition — two editions never share a consent record', () => {
    expect(consentStorageKeyFor('mumbai-2026')).not.toBe(consentStorageKeyFor('delhi-2027'));
  });

  it('embeds the edition slug', () => {
    expect(consentStorageKeyFor('mumbai-2026')).toContain('mumbai-2026');
  });

  it('is namespaced so it cannot collide with unrelated app storage', () => {
    expect(consentStorageKeyFor('mumbai-2026')).toMatch(/^influora:festival-box:/);
  });
});
