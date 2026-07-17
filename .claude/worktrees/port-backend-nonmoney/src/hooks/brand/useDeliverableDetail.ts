/**
 * useDeliverableDetail — brand-facing deliverable viewer (DPF-2)
 * ----------------------------------------------------------------------------
 * Backed by `GET /api/v1/deliverables/:id` (DPF-1, DeliverableController.java).
 * Returns presigned R2 URLs (15-min expiry) for video/image files, plus caption,
 * hashtags, status, and action permissions.
 *
 * Presigned link expiry handling: refetch is triggered ONLY on-error (the
 * `MediaPlayer` in `DeliverableViewer.tsx` calls `refetch()` from its
 * `onError` handler when the presigned URL 403s, then remounts the media
 * element on a fresh URL via a retry nonce). We deliberately do NOT use
 * `refetchOnWindowFocus` here: every focus event would mint a new presigned
 * URL, and if that swapped in under a currently-playing element it would
 * restart playback for a brand who just tabbed away and back. Bump-on-error
 * is the correct trigger, not proactive URL swapping under playing media.
 */

import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import type { DeliverableDetail } from '@/lib/api';

export const deliverableDetailQueryKey = (id: string) => ['deliverable', id] as const;

export interface UseDeliverableDetailResult {
  deliverable: DeliverableDetail | null;
  isLoading: boolean;
  error: string | null;
  refetch: () => void;
}

export function useDeliverableDetail(deliverableId: string): UseDeliverableDetailResult {
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: deliverableDetailQueryKey(deliverableId),
    queryFn: () => api.deliverables.getDetail(deliverableId),
    staleTime: 5 * 60 * 1000, // 5 min
    refetchOnWindowFocus: false, // don't swap the presigned URL under a playing element on tab-back; on-error retry nonce handles expiry instead
    retry: 1,
  });

  return {
    deliverable: data ?? null,
    isLoading,
    error: error ? String(error) : null,
    refetch: () => void refetch(),
  };
}

export default useDeliverableDetail;
