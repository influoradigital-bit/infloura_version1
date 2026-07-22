/**
 * useDeliverableSafetyReview — brand-facing advisory safety check on a
 * submitted deliverable (Brand Surface Audit fix #3,
 * wiki/reports/brand-feature-audit.md item 3). Backed by the EXPECTED
 * `GET /deliverables/:id/safety-review` (api.deliverables.getSafetyReview) —
 * Vikram is building this route in parallel; the shape is not yet confirmed
 * against wiki/build/brand-fixes-backend.md (not written as of this pass).
 * See the DeliverableSafetyReview doc comment in src/lib/api.ts.
 *
 * Advisory-only contract: ANY fetch failure — the route not being live yet,
 * a real server error, or the classifier simply not having run for this
 * deliverable version — degrades to `review: null` with no error surfaced.
 * This card must never block or alarm the brand off approve/reject, so
 * unlike useContentPerformance/useCreatorMetrics there is deliberately no
 * error/notImplemented state here. DeliverableSafetyReviewCard renders
 * nothing when `review` is null.
 */

import { useCallback, useEffect, useState } from 'react';
import { api } from '@/lib/api';
import type { DeliverableSafetyReview } from '@/lib/api';

export interface UseDeliverableSafetyReviewResult {
  review: DeliverableSafetyReview | null;
  loading: boolean;
  refresh: () => Promise<void>;
}

export function useDeliverableSafetyReview(
  deliverableId: string | undefined,
): UseDeliverableSafetyReviewResult {
  const [review, setReview] = useState<DeliverableSafetyReview | null>(null);
  const [loading, setLoading] = useState(true);

  const refresh = useCallback(async () => {
    if (!deliverableId) {
      setReview(null);
      setLoading(false);
      return;
    }
    setLoading(true);
    try {
      const result = await api.deliverables.getSafetyReview(deliverableId);
      setReview(result);
    } catch {
      // Advisory-only — a failed or not-yet-available check degrades silently,
      // never as a scary error state on top of the approve/reject flow.
      setReview(null);
    } finally {
      setLoading(false);
    }
  }, [deliverableId]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  return { review, loading, refresh };
}

export default useDeliverableSafetyReview;
