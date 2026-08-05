/**
 * useCreatorKyc — creator PAN/Aadhaar KYC capture submit state
 * ----------------------------------------------------------------------------
 * `onboarding.submitCreatorKyc()` (src/lib/api.ts) hits the real
 * `POST /onboarding/creator/kyc` endpoint. KYC is deferred to first withdrawal
 * by product design, but the capture surface itself lives in Creator Settings so
 * a creator can complete it ahead of time. On success the endpoint returns
 * `{ kycStatus: 'PENDING' | 'VERIFIED' }`, which this hook exposes as `saved` so
 * the form can render a confirmation state. On an `ApiError` the message is
 * surfaced via `error`.
 */

import { useCallback, useState } from 'react';
import { ApiError, onboarding } from '@/lib/api';

export interface CreatorKycSubmission {
  pan: string;
  aadhaarLast4: string;
  selfieUrl: string;
}

export interface CreatorKycResult {
  kycStatus: 'PENDING' | 'VERIFIED';
}

export interface UseCreatorKycResult {
  submitting: boolean;
  /** The persisted KYC status after a successful submit, or null before/after failure. */
  saved: CreatorKycResult | null;
  error: string | null;
  submit: (body: CreatorKycSubmission) => Promise<void>;
  reset: () => void;
}

export function useCreatorKyc(): UseCreatorKycResult {
  const [submitting, setSubmitting] = useState(false);
  const [saved, setSaved] = useState<CreatorKycResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  const submit = useCallback(async (body: CreatorKycSubmission) => {
    setSubmitting(true);
    setError(null);
    try {
      const result = await onboarding.submitCreatorKyc(body);
      setSaved(result);
    } catch (err) {
      setSaved(null);
      setError(err instanceof ApiError ? err.message : 'Failed to submit KYC details');
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

export default useCreatorKyc;
