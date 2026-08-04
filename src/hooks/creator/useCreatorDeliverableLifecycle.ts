/**
 * useCreatorDeliverableLifecycle — post-approval deliverable actions (verified-first)
 * ----------------------------------------------------------------------------
 * verified-analytics-0804: Meta-verified metrics are the default and only primary source. This
 * hook wraps:
 *   - GET  /creator/deliverables/:id/status       → refreshStatus()  (cached source/verifiedAt)
 *   - POST /creator/deliverables/:id/verify        → verify()         (live Meta attempt)
 *   - POST /creator/deliverables/:id/mark-posted   → markPosted()     (gives verify() its post URL)
 *   - POST /creator/deliverables/:id/metrics       → reportMetrics()  (MANUAL — server rejects
 *                                                     unless Meta genuinely failed)
 *   - POST /creator/deliverables/:id/proof         → uploadProof()
 *
 * The manual form is a failure-only escape hatch: it opens ONLY when `verify()` returns a
 * `manualFallbackAllowed` outcome. `verification` holds the last live attempt's state.
 */

import { useCallback, useState } from 'react';
import {
  ApiError,
  creatorDeliverables,
  type CreatorDeliverableMarkPostedResponse,
  type CreatorDeliverableMetricsPayload,
  type CreatorDeliverableMetricsReportResponse,
  type CreatorDeliverableProofResponse,
  type CreatorDeliverableRowStatus,
  type CreatorDeliverableStatusResponse,
  type CreatorDeliverableVerificationState,
} from '@/lib/api';

export interface UseCreatorDeliverableLifecycleResult {
  status: CreatorDeliverableStatusResponse | null;
  verification: CreatorDeliverableVerificationState | null;
  busy: boolean;
  verifying: boolean;
  error: string | null;
  refreshStatus: () => Promise<void>;
  verify: () => Promise<CreatorDeliverableVerificationState | null>;
  markPosted: (livePostUrl: string) => Promise<CreatorDeliverableMarkPostedResponse | null>;
  reportMetrics: (payload: {
    metrics: CreatorDeliverableMetricsPayload;
    proofScreenshots?: string[];
    reportedDaysAfterPosting?: number;
  }) => Promise<CreatorDeliverableMetricsReportResponse | null>;
  uploadProof: (screenshot: File) => Promise<CreatorDeliverableProofResponse | null>;
  clearError: () => void;
}

function messageFrom(err: unknown, fallback: string): string {
  return err instanceof ApiError ? err.message : fallback;
}

export function useCreatorDeliverableLifecycle(
  deliverableId: string,
  initialStatus?: CreatorDeliverableRowStatus,
): UseCreatorDeliverableLifecycleResult {
  const [status, setStatus] = useState<CreatorDeliverableStatusResponse | null>(
    initialStatus
      ? {
          id: deliverableId,
          collaborationId: '',
          title: '',
          status: initialStatus,
          versionNumber: 0,
          revisionCount: 0,
          actions: { canUploadNewVersion: false, canSubmit: false, canReportMetrics: false },
          metricSource: null,
          lastVerifiedAt: null,
          metaConnected: false,
        }
      : null,
  );
  const [verification, setVerification] = useState<CreatorDeliverableVerificationState | null>(null);
  const [busy, setBusy] = useState(false);
  const [verifying, setVerifying] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const refreshStatus = useCallback(async () => {
    setBusy(true);
    setError(null);
    try {
      setStatus(await creatorDeliverables.getStatus(deliverableId));
    } catch (err) {
      setError(messageFrom(err, 'Failed to load deliverable status'));
    } finally {
      setBusy(false);
    }
  }, [deliverableId]);

  const verify = useCallback(async () => {
    setVerifying(true);
    setError(null);
    try {
      const result = await creatorDeliverables.verifyNow(deliverableId);
      setVerification(result);
      setStatus((prev) =>
        prev
          ? {
              ...prev,
              metricSource: result.metricSource,
              lastVerifiedAt: result.lastVerifiedAt,
              metaConnected: result.metaConnected,
            }
          : prev,
      );
      return result;
    } catch (err) {
      setError(messageFrom(err, 'Failed to verify with Instagram'));
      return null;
    } finally {
      setVerifying(false);
    }
  }, [deliverableId]);

  const markPosted = useCallback(
    async (livePostUrl: string) => {
      setBusy(true);
      setError(null);
      try {
        const result = await creatorDeliverables.markPosted(deliverableId, livePostUrl);
        setStatus((prev) => (prev ? { ...prev, status: result.status } : prev));
        return result;
      } catch (err) {
        setError(messageFrom(err, 'Failed to mark as posted'));
        return null;
      } finally {
        setBusy(false);
      }
    },
    [deliverableId],
  );

  const reportMetrics = useCallback(
    async (payload: {
      metrics: CreatorDeliverableMetricsPayload;
      proofScreenshots?: string[];
      reportedDaysAfterPosting?: number;
    }) => {
      setBusy(true);
      setError(null);
      try {
        const result = await creatorDeliverables.reportMetrics(deliverableId, payload);
        setStatus((prev) => (prev ? { ...prev, status: result.status } : prev));
        return result;
      } catch (err) {
        setError(messageFrom(err, 'Failed to report metrics'));
        return null;
      } finally {
        setBusy(false);
      }
    },
    [deliverableId],
  );

  const uploadProof = useCallback(
    async (screenshot: File) => {
      setBusy(true);
      setError(null);
      try {
        return await creatorDeliverables.uploadProof(deliverableId, screenshot);
      } catch (err) {
        setError(messageFrom(err, 'Failed to upload proof'));
        return null;
      } finally {
        setBusy(false);
      }
    },
    [deliverableId],
  );

  const clearError = useCallback(() => setError(null), []);

  return {
    status,
    verification,
    busy,
    verifying,
    error,
    refreshStatus,
    verify,
    markPosted,
    reportMetrics,
    uploadProof,
    clearError,
  };
}

export default useCreatorDeliverableLifecycle;
