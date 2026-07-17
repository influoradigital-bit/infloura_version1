/**
 * useCampaignTrackingLinks - brand-facing UTM tracking links for one campaign
 * ----------------------------------------------------------------------------
 * Backed by GET/POST /campaigns/{campaignId}/tracking-links
 * (influora-api CampaignTrackingController, Wave A task A1). Follows this
 * repo's existing async-data hook convention — { data, loading, error,
 * refresh } — rather than TanStack Query, matching useCreatorMetrics.ts.
 *
 * `create` is exposed separately (not folded into `refresh`) so a form
 * component can track its own pending/error state for the submit button
 * without re-triggering the list's loading skeleton.
 */

import { useCallback, useEffect, useState } from 'react';
import { api, ApiError, type CreateTrackingLinkPayload, type TrackingLinkResponse } from '@/lib/api';

export interface UseCampaignTrackingLinksResult {
  data: TrackingLinkResponse[];
  loading: boolean;
  error: string | null;
  refresh: () => Promise<void>;
  create: (payload: CreateTrackingLinkPayload) => Promise<TrackingLinkResponse>;
  creating: boolean;
  createError: string | null;
}

export function useCampaignTrackingLinks(
  campaignId: string | undefined,
): UseCampaignTrackingLinksResult {
  const [data, setData] = useState<TrackingLinkResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    if (!campaignId) {
      setData([]);
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const result = await api.campaignTracking.listTrackingLinks(campaignId);
      setData(result);
    } catch (err) {
      setError(
        err instanceof ApiError ? err.message : 'Failed to load tracking links',
      );
    } finally {
      setLoading(false);
    }
  }, [campaignId]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  const create = useCallback(
    async (payload: CreateTrackingLinkPayload) => {
      if (!campaignId) throw new Error('No campaign selected');
      setCreating(true);
      setCreateError(null);
      try {
        const created = await api.campaignTracking.createTrackingLink(campaignId, payload);
        setData((prev) => {
          const withoutExisting = prev.filter((link) => link.id !== created.id);
          return [created, ...withoutExisting];
        });
        return created;
      } catch (err) {
        const message =
          err instanceof ApiError ? err.message : 'Failed to create tracking link';
        setCreateError(message);
        throw err;
      } finally {
        setCreating(false);
      }
    },
    [campaignId],
  );

  return { data, loading, error, refresh, create, creating, createError };
}

export default useCampaignTrackingLinks;
