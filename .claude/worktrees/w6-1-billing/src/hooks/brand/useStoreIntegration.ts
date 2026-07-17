/**
 * useStoreIntegration - brand-facing store connection status
 * ----------------------------------------------------------------------------
 * Backed by the real, QA-passed endpoints from P2-9 (see `storeIntegrations`
 * in src/lib/api.ts and StoreIntegrationStatusController.java):
 * `GET /integrations/store/status` reports whichever of Shopify/WooCommerce
 * is connected for the caller's workspace, and `api.storeIntegrations
 * .disconnect(provider)` revokes it (soft-delete). Neither call throws
 * `NOT_IMPLEMENTED` anymore — genuine failures (network, auth, 5xx) surface
 * through `error` like any other async-data hook.
 *
 * Same { data, loading, error, refresh } shape as the other async-data hooks
 * in this codebase (see useCreatorMetrics.ts).
 */

import { useCallback, useEffect, useState } from 'react';
import { api, ApiError, type IntegrationStatus } from '@/lib/api';

export interface UseStoreIntegrationResult {
  data: IntegrationStatus | null;
  loading: boolean;
  error: string | null;
  refresh: () => Promise<void>;
}

export function useStoreIntegration(): UseStoreIntegrationResult {
  const [data, setData] = useState<IntegrationStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await api.storeIntegrations.status();
      setData(result);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load integration status');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  return { data, loading, error, refresh };
}

export default useStoreIntegration;
