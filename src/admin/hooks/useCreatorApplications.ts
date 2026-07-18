/**
 * INFLUORA ADMIN PANEL — Pending Creator Applications hook
 * Owner: Ananya (Frontend)
 * Reference: Wire-up pass — "Pending Applications" tab (UsersPage.tsx)
 *
 * Backed by the live `creatorApi.getPendingApplications(page, pageSize)` call
 * (`GET /api/v1/admin/creators/applications/pending`, AdminCreatorController).
 * Same paginated fetch-on-page-change shape as `useBrandList.ts`/
 * `useCreatorList.ts`, minus filters — the endpoint takes only page/pageSize.
 *
 * `api-contracts.ts` types this call's response as `PaginatedResponse<CreatorSummary>`,
 * matching the backend's `PagedCreatorsDto` of `CreatorSummaryDto` rows (id, name, email,
 * instagramHandle, followers, applicationStatus, tier, isSuspended, createdAt) — the same
 * lighter shape `creatorApi.list()` returns, NOT the full `Creator` shape (which additionally
 * claims niche/engagementRate/instagramVerified/qualityScore that this endpoint never sends).
 */

import { useEffect, useState } from 'react';
import type { CreatorSummary } from '../types/admin.types';
import { creatorApi } from '../services/api-contracts';

export const PENDING_APPLICATIONS_PAGE_SIZE = 20;

export interface UseCreatorApplicationsResult {
  applications: CreatorSummary[];
  totalCount: number;
  totalPages: number;
  isLoading: boolean;
  error: string | null;
  page: number;
  setPage: (page: number) => void;
  /** Re-fetch the current page (e.g. after approving/rejecting one from the detail view). */
  refresh: () => void;
}

/**
 * Returns the paginated list of pending creator applications from the live backend.
 */
export function useCreatorApplications(): UseCreatorApplicationsResult {
  const [rows, setRows] = useState<CreatorSummary[]>([]);
  const [totalCount, setTotalCount] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [page, setPage] = useState(1);
  const [reloadKey, setReloadKey] = useState(0);

  const refresh = () => setReloadKey((k) => k + 1);

  useEffect(() => {
    let cancelled = false;
    setIsLoading(true);
    setError(null);

    creatorApi
      .getPendingApplications(page, PENDING_APPLICATIONS_PAGE_SIZE)
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
          setError(res.error ?? 'Failed to load pending applications');
        }
      })
      .catch(() => {
        if (cancelled) return;
        setRows([]);
        setTotalCount(0);
        setTotalPages(0);
        setError('Failed to load pending applications');
      })
      .finally(() => {
        if (!cancelled) setIsLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [page, reloadKey]);

  return { applications: rows, totalCount, totalPages, isLoading, error, page, setPage, refresh };
}
