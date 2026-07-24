/**
 * useCreatorTaxIdentity — GSTIN/PAN capture submit state
 * ----------------------------------------------------------------------------
 * `creatorTaxIdentity.submit()` (src/lib/api.ts) now hits the real
 * `POST /me/tax-identity` endpoint. On success it returns the persisted,
 * masked record (`gstin`, `maskedPan`, `taxRegistrationStatus`) which this
 * hook exposes as `saved` so the form can render a confirmation state. On a
 * 400 (`INVALID_GSTIN` / `INVALID_PAN` / `TAX_IDENTITY_REQUIRED`) or other
 * `ApiError`, the message is surfaced via `error`.
 */

import { useCallback, useState } from 'react';
import {
  ApiError,
  creatorTaxIdentity,
  type CreatorTaxIdentityResponse,
  type CreatorTaxIdentitySubmission,
} from '@/lib/api';

export interface UseCreatorTaxIdentityResult {
  submitting: boolean;
  /** The persisted record after a successful submit, or null before/after failure. */
  saved: CreatorTaxIdentityResponse | null;
  error: string | null;
  submit: (body: CreatorTaxIdentitySubmission) => Promise<void>;
  reset: () => void;
}

export function useCreatorTaxIdentity(): UseCreatorTaxIdentityResult {
  const [submitting, setSubmitting] = useState(false);
  const [saved, setSaved] = useState<CreatorTaxIdentityResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  const submit = useCallback(async (body: CreatorTaxIdentitySubmission) => {
    setSubmitting(true);
    setError(null);
    try {
      const result = await creatorTaxIdentity.submit(body);
      setSaved(result);
    } catch (err) {
      setSaved(null);
      setError(err instanceof ApiError ? err.message : 'Failed to submit tax identity');
    } finally {
      setSubmitting(false);
    }
  }, []);

  const reset = useCallback(() => {
    setSubmitting(false);
    setSaved(null);
    setError(null);
  }, []);

  return { submitting, saved, error, submit, reset };
}

export default useCreatorTaxIdentity;
