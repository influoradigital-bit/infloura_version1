/**
 * INFLUORA ADMIN PANEL — Creator List data hook
 * Owner: Ananya (Frontend)
 * Reference: Task #8 — admin Users page (src/admin/pages/UsersPage.tsx)
 *
 * Backs the "Creators" tab of UsersPage.tsx. Wired to the live
 * `GET /admin/creators` list endpoint (AdminCreatorController, Task #3) via
 * `creatorApi.list()`. Filters (search/applicationStatus/isSuspended) and
 * pagination are applied server-side; the hook refetches whenever either
 * changes. Rows are `CreatorSummary` (the list DTO's lighter shape), not
 * `Creator`/`CreatorDetail` — see the type's doc comment in admin.types.ts.
 *
 * Search is debounced client-side (350ms), same rationale as useBrandList.ts.
 */

import { useEffect, useState } from 'react';
import type { CreatorSummary, CreatorFilters } from '../types/admin.types';
import { creatorApi } from '../services/api-contracts';

const SEARCH_DEBOUNCE_MS = 350;
export const CREATOR_LIST_PAGE_SIZE = 20;

export interface UseCreatorListResult {
  /** Current page of rows from the server. */
  creators: CreatorSummary[];
  /** Server-side total for the active filter, for "N of M creators" copy. */
  totalCount: number;
  totalPages: number;
  isLoading: boolean;
  error: string | null;
  filters: CreatorFilters;
  /** Replaces the filter set and resets to page 1 (new query context). */
  setFilters: (filters: CreatorFilters) => void;
  page: number;
  setPage: (page: number) => void;
}

/**
 * Returns the admin creator list/search table rows from the live backend,
 * with server-side filtering and pagination.
 */
export function useCreatorList(): UseCreatorListResult {
  const [rows, setRows] = useState<CreatorSummary[]>([]);
  const [totalCount, setTotalCount] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [filters, setFiltersState] = useState<CreatorFilters>({});
  const [debouncedFilters, setDebouncedFilters] = useState<CreatorFilters>({});
  const [page, setPage] = useState(1);

  function setFilters(next: CreatorFilters) {
    setFiltersState(next);
    setPage(1);
  }

  useEffect(() => {
    const handle = setTimeout(() => setDebouncedFilters(filters), SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(handle);
  }, [filters]);

  useEffect(() => {
    let cancelled = false;
    setIsLoading(true);
    setError(null);

    creatorApi
      .list(debouncedFilters, page, CREATOR_LIST_PAGE_SIZE)
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
          setError(res.error ?? 'Failed to load creators');
        }
      })
      .catch(() => {
        if (cancelled) return;
        setRows([]);
        setTotalCount(0);
        setTotalPages(0);
        setError('Failed to load creators');
      })
      .finally(() => {
        if (!cancelled) setIsLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [debouncedFilters, page]);

  return {
    creators: rows,
    totalCount,
    totalPages,
    isLoading,
    error,
    filters,
    setFilters,
    page,
    setPage,
  };
}
