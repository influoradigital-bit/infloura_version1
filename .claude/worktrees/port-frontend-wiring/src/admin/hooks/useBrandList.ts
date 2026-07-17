/**
 * INFLUORA ADMIN PANEL — Brand List data hook
 * Owner: Ananya (Frontend)
 * Reference: Task #8 — admin Users page (src/admin/pages/UsersPage.tsx)
 *
 * Backs the "Brands" tab of UsersPage.tsx. Wired to the live `GET /admin/brands`
 * list endpoint (AdminBrandController, Task #2) via `brandApi.list()`.
 * Filters (search/kycStatus/isSuspended) and pagination are applied
 * server-side; the hook refetches whenever either changes.
 *
 * Search is debounced client-side (350ms) so free-typing doesn't fire a
 * request per keystroke against a real paginated table — same rationale as
 * every other server-backed admin list, just not needed there because those
 * lists are small enough to filter client-side (see useCampaignList.ts).
 */

import { useEffect, useState } from 'react';
import type { Brand, BrandFilters } from '../types/admin.types';
import { brandApi } from '../services/api-contracts';

const SEARCH_DEBOUNCE_MS = 350;
export const BRAND_LIST_PAGE_SIZE = 20;

export interface UseBrandListResult {
  /** Current page of rows from the server. */
  brands: Brand[];
  /** Server-side total for the active filter, for "N of M brands" copy. */
  totalCount: number;
  totalPages: number;
  isLoading: boolean;
  error: string | null;
  filters: BrandFilters;
  /** Replaces the filter set and resets to page 1 (new query context). */
  setFilters: (filters: BrandFilters) => void;
  page: number;
  setPage: (page: number) => void;
}

/**
 * Returns the admin brand list/search table rows from the live backend,
 * with server-side filtering and pagination.
 */
export function useBrandList(): UseBrandListResult {
  const [rows, setRows] = useState<Brand[]>([]);
  const [totalCount, setTotalCount] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [filters, setFiltersState] = useState<BrandFilters>({});
  const [debouncedFilters, setDebouncedFilters] = useState<BrandFilters>({});
  const [page, setPage] = useState(1);

  function setFilters(next: BrandFilters) {
    setFiltersState(next);
    setPage(1);
  }

  // Debounce the filter set (mainly the free-text `search` field) before it
  // reaches the fetch effect below.
  useEffect(() => {
    const handle = setTimeout(() => setDebouncedFilters(filters), SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(handle);
  }, [filters]);

  useEffect(() => {
    let cancelled = false;
    setIsLoading(true);
    setError(null);

    brandApi
      .list(debouncedFilters, page, BRAND_LIST_PAGE_SIZE)
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
          setError(res.error ?? 'Failed to load brands');
        }
      })
      .catch(() => {
        if (cancelled) return;
        setRows([]);
        setTotalCount(0);
        setTotalPages(0);
        setError('Failed to load brands');
      })
      .finally(() => {
        if (!cancelled) setIsLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [debouncedFilters, page]);

  return {
    brands: rows,
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
