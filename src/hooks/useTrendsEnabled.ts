/**
 * useTrendsEnabled - T-TSOFF-0920
 * ----------------------------------------------------------------------------
 * The single frontend read of the server's trend switch. Every trend-derived
 * surface (brand Trend-Spark nudge, creator Co-pilot daily idea, and the entry
 * points that link to them) gates on THIS hook and nothing else.
 *
 * Why a hook over `import { TRENDS_ENABLED }`: a frontend constant would need a
 * frontend release to flip, and would drift from `TrendFeatureGate` the moment
 * someone changed one and not the other. `GET /config/public` publishes
 * `TrendIngestProperties.canProduceTrends()` verbatim, so the UI and the
 * endpoints can only ever agree - a surface renders exactly when its endpoint
 * would answer, and hides exactly when its endpoint would 404 TRENDS_DISABLED.
 *
 * Shared react-query key: several surfaces mount at once (the creator Co-pilot
 * page renders the disabled state, the section and the preview card all in one
 * tree), so they must share one request. `staleTime: Infinity` because a server
 * feature flag does not change inside a session - a redeploy/reload picks it up.
 *
 * `api.config.public()` never rejects (it catches and falls back to
 * `trendsEnabled: false`), so this hook has no error state: the worst case is a
 * hidden surface, never a fabricated one.
 */

import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';

export const publicConfigQueryKey = ['config', 'public'] as const;

export interface UseTrendsEnabledResult {
  /**
   * `false` until the first read resolves. Surfaces render their off-state (or
   * nothing) during that window rather than flashing a trend card that may then
   * vanish - and rather than a spinner for a feature that is usually off.
   */
  trendsEnabled: boolean;
  /**
   * True while the first `/config/public` read is in flight. Callers that would
   * otherwise fire a second request (e.g. the nudge query) wait on this instead
   * of racing it, so a disabled feature never issues its own endpoint call.
   */
  isLoading: boolean;
}

export function useTrendsEnabled(): UseTrendsEnabledResult {
  const { data, isPending } = useQuery({
    queryKey: publicConfigQueryKey,
    queryFn: () => api.config.public(),
    staleTime: Infinity,
    retry: 1,
  });

  return { trendsEnabled: data?.trendsEnabled === true, isLoading: isPending };
}

export default useTrendsEnabled;
