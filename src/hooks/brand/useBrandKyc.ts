/**
 * useBrandKyc — brand workspace KYC (GSTIN / PAN + docs) submit state.
 * ----------------------------------------------------------------------------
 * Mirrors `useCreatorKyc`, but for the brand side: `onboarding.submitBrandKyc()`
 * (src/lib/api.ts:995) hits the real `POST /onboarding/brand/kyc`, which runs
 * `Workspace.applyKyc()` server-side and moves the workspace to PENDING (an admin
 * later flips it to VERIFIED via AdminBrandService). On success the endpoint
 * returns `{ kycStatus: 'PENDING' | 'VERIFIED' }`, surfaced as `saved`; an
 * `ApiError` message is surfaced via `error`.
 */

import { useCallback, useState } from 'react';
import { ApiError, onboarding } from '@/lib/api';

export interface BrandKycSubmission {
  gstin: string;
  pan: string;
  gstinDocUrl: string;
  panDocUrl: string;
}

export interface BrandKycResult {
  kycStatus: 'PENDING' | 'VERIFIED';
}

export interface UseBrandKycResult {
  submitting: boolean;
  /** The persisted KYC status after a successful submit, or null before/after failure. */
  saved: BrandKycResult | null;
  error: string | null;
  /** Resolves to the result on success, or null on failure (never throws). */
  submit: (body: BrandKycSubmission) => Promise<BrandKycResult | null>;
  reset: () => void;
}

export function useBrandKyc(): UseBrandKycResult {
  const [submitting, setSubmitting] = useState(false);
  const [saved, setSaved] = useState<BrandKycResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  const submit = useCallback(async (body: BrandKycSubmission) => {
    setSubmitting(true);
    setError(null);
    try {
      const result = await onboarding.submitBrandKyc(body);
      setSaved(result);
      return result;
    } catch (err) {
      setSaved(null);
      setError(err instanceof ApiError ? err.message : 'Failed to submit verification details');
      return null;
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

export default useBrandKyc;
