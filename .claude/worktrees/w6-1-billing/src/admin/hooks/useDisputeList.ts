/**
 * INFLUORA ADMIN PANEL — Dispute List data hook
 * Owner: Ananya (Frontend)
 * Reference: Task #9 — admin Disputes page (src/admin/pages/DisputesPage.tsx)
 *
 * Backs `DisputeList.tsx`. Wired to the live `GET /admin/disputes` list endpoint
 * (`AdminDisputeController.list()`, Task #4) via `disputeApi.list()`. Filters
 * (status/campaignId/brandId/creatorId) and pagination are applied server-side;
 * the hook refetches whenever either changes. Same load/paginate shape as
 * `useBrandList.ts` — no client-side search debounce here since the only
 * free-text filters (campaignId/brandId/creatorId) are exact-id lookups, not
 * fuzzy search, so there's no keystroke-storm to protect against.
 */

import { useCallback, useEffect, useState } from 'react';
import type { DisputeSummary, DisputeFilters } from '../types/admin.types';
import { disputeApi } from '../services/api-contracts';

export const DISPUTE_LIST_PAGE_SIZE = 20;

export interface UseDisputeListResult {
  /** Current page of rows from the server. */
  disputes: DisputeSummary[];
  /** Server-side total for the active filter, for "N of M disputes" copy. */
  totalCount: number;
  totalPages: number;
  isLoading: boolean;
  error: string | null;
  filters: DisputeFilters;
  /** Replaces the filter set and resets to page 1 (new query context). */
  setFilters: (filters: DisputeFilters) => void;
  page: number;
  setPage: (page: number) => void;
  /** Re-fetch the current page (e.g. after a dispute is resolved). */
  refresh: () => void;
}

/**
 * Returns the admin disputes list/search table rows from the live backend,
 * with server-side filtering and pagination.
 */
export function useDisputeList(): UseDisputeListResult {
  const [rows, setRows] = useState<DisputeSummary[]>([]);
  const [totalCount, setTotalCount] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [filters, setFiltersState] = useState<DisputeFilters>({});
  const [page, setPageState] = useState(1);
  const [reloadKey, setReloadKey] = useState(0);

  const setFilters = useCallback((next: DisputeFilters) => {
    setFiltersState(next);
    setPageState(1);
  }, []);

  const setPage = useCallback((next: number) => setPageState(next), []);
  const refresh = useCallback(() => setReloadKey((k) => k + 1), []);

  useEffect(() => {
    let cancelled = false;
    setIsLoading(true);
    setError(null);

    disputeApi
      .list(filters, page, DISPUTE_LIST_PAGE_SIZE)
      .then((res) => {
        if (cancelled) return;
        if (res.success && res.data) {
          setRows(res.data.data);
          setTotalCount(res.data.total);
          setTotalPages(res.data.totalPages);
          setError(null);
        } else {
          setRows([]);
          setTotalCount(0);
          setTotalPages(0);
          setError(res.error ?? 'Failed to load disputes');
        }
      })
      .catch(() => {
        if (cancelled) return;
        setRows([]);
        setTotalCount(0);
        setTotalPages(0);
        setError('Failed to load disputes');
      })
      .finally(() => {
        if (!cancelled) setIsLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [filters, page, reloadKey]);

  return {
    disputes: rows,
    totalCount,
    totalPages,
    isLoading,
    error,
    filters,
    setFilters,
    page,
    setPage,
    refresh,
  };
}
