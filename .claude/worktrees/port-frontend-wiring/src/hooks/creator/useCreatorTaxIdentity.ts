/**
 * useCreatorTaxIdentity — GSTIN/PAN capture submit state
 * ----------------------------------------------------------------------------
 * `creatorTaxIdentity.submit()` (src/lib/api.ts) always rejects with a typed
 * `NOT_IMPLEMENTED` ApiError — there is no backend endpoint yet for a creator
 * to submit tax identity (D14-B schema-captures GSTIN/PAN on `CreatorProfile`,
 * but only an internal auto-provisioning path writes it today). This hook
 * exposes `notImplemented` so `TaxIdentityForm` can show an honest gap state
 * instead of a fabricated success, matching `useAffiliateEarnings.ts`'s
 * `notImplemented` convention.
 */

import { useCallback, useState } from 'react';
import { ApiError, creatorTaxIdentity, type CreatorTaxIdentitySubmission } from '@/lib/api';

export interface UseCreatorTaxIdentityResult {
  submitting: boolean;
  /** True once a submit attempt has hit the missing-endpoint case. */
  notImplemented: boolean;
  error: string | null;
  submit: (body: CreatorTaxIdentitySubmission) => Promise<void>;
  reset: () => void;
}

export function useCreatorTaxIdentity(): UseCreatorTaxIdentityResult {
  const [submitting, setSubmitting] = useState(false);
  const [notImplemented, setNotImplemented] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = useCallback(async (body: CreatorTaxIdentitySubmission) => {
    setSubmitting(true);
    setError(null);
    setNotImplemented(false);
    try {
      await creatorTaxIdentity.submit(body);
    } catch (err) {
      if (err instanceof ApiError && err.code === 'NOT_IMPLEMENTED') {
        setNotImplemented(true);
      } else {
        setError(err instanceof ApiError ? err.message : 'Failed to submit tax identity');
      }
    } finally {
      setSubmitting(false);
    }
  }, []);

  const reset = useCallback(() => {
    setSubmitting(false);
    setNotImplemented(false);
    setError(null);
  }, []);

  return { submitting, notImplemented, error, submit, reset };
}

export default useCreatorTaxIdentity;
