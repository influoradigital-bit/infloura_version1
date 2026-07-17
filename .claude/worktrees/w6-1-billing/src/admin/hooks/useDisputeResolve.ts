/**
 * INFLUORA ADMIN PANEL — Dispute Resolve mutation hook
 * Owner: Ananya (Frontend)
 * Reference: Task #9 — admin Disputes page (src/admin/pages/DisputesPage.tsx)
 *
 * Backs `DisputeResolveModal` (src/admin/components/disputes/DisputeList.tsx).
 * Wraps the live `POST /admin/disputes/:id/resolve` call (`disputeApi.resolve()`)
 * — which now both flips the dispute's terminal status AND settles the frozen
 * escrow (release to creator / refund to brand / custom split) — same
 * load/mutate-state shape as `useFeeConfig.ts`'s mutation caller convention:
 * the panel calls this hook's `resolve()` directly and re-pulls the list via
 * `useDisputeList().refresh()` on success, rather than this hook owning the
 * list cache itself.
 *
 * `resolution.creatorSplitPercent` is REQUIRED by the caller when
 * `resolution.resolution === DisputeStatus.RESOLVED_SPLIT` — this hook does not
 * re-validate that (the modal does, and `EscrowService.adminSplitForDispute` is
 * the authoritative server-side check) — it only surfaces the resulting 400 as
 * `error` if the caller gets it wrong.
 */

import { useCallback, useState } from 'react';
import type { DisputeDetail, DisputeResolution } from '../types/admin.types';
import { disputeApi } from '../services/api-contracts';

export interface UseDisputeResolveResult {
  /** The full dispute record returned by a successful resolve, or null before/after reset. */
  data: DisputeDetail | null;
  isResolving: boolean;
  error: string | null;
  /** Submits the resolution decision for `disputeId`. Returns the resolved detail on success, null on failure. */
  resolve: (disputeId: string, resolution: DisputeResolution) => Promise<DisputeDetail | null>;
  /** Clears `data`/`error` (e.g. when the modal is reopened for a different dispute). */
  reset: () => void;
}

export function useDisputeResolve(): UseDisputeResolveResult {
  const [data, setData] = useState<DisputeDetail | null>(null);
  const [isResolving, setIsResolving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const resolve = useCallback(
    async (disputeId: string, resolution: DisputeResolution): Promise<DisputeDetail | null> => {
      setIsResolving(true);
      setError(null);

      try {
        const res = await disputeApi.resolve(disputeId, resolution);
        if (res.success && res.data) {
          setData(res.data);
          setError(null);
          return res.data;
        }

        setError(res.error ?? 'Failed to resolve dispute');
        return null;
      } catch {
        setError('Failed to resolve dispute');
        return null;
      } finally {
        setIsResolving(false);
      }
    },
    []
  );

  const reset = useCallback(() => {
    setData(null);
    setError(null);
  }, []);

  return { data, isResolving, error, resolve, reset };
}
