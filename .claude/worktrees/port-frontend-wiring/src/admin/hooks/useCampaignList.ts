/**
 * INFLUORA ADMIN PANEL — Campaign List data hook
 * Owner: Ananya (Frontend)
 * Reference: src/admin/TASK_ASSIGNMENTS.md (P1 — Campaign table)
 *
 * P2-7: Wired to real `AdminCampaignController` (Vikram, 2026-07-13).
 * Backend: GET /api/v1/admin/campaigns (no pagination/filter/sort params —
 * MVP returns every campaign platform-wide; see AdminCampaignController
 * class javadoc). Frontend: campaignApi.listAll() via api-contracts.ts.
 *
 * Sorting/filtering stays client-side against the full list returned by the
 * API, same as before the real endpoint shipped (server-side params are
 * flagged as follow-up scope in the P2-7 packet).
 */

import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import type { CampaignSummary, CampaignFilters } from '../types/admin.types';
import { campaignApi } from '../services/api-contracts';

// ============================================
// SORT / FILTER TYPES
// ============================================

export type CampaignSortField = 'name' | 'brandName' | 'status' | 'budget' | 'createdAt' | 'slaBreachRate';
export type SortDirection = 'asc' | 'desc';

export interface CampaignSort {
  field: CampaignSortField;
  direction: SortDirection;
}

export interface UseCampaignListResult {
  /** Filtered + sorted rows, ready to render. */
  campaigns: CampaignSummary[];
  /** Unfiltered total, for "N of M campaigns" copy. */
  totalCount: number;
  isLoading: boolean;
  error: string | null;
  filters: CampaignFilters;
  setFilters: (filters: CampaignFilters) => void;
  sort: CampaignSort;
  setSort: (sort: CampaignSort) => void;
}

// ============================================
// HOOK
// ============================================

/**
 * Returns the admin campaign-monitoring list, with client-side sort/filter
 * applied against the real `AdminCampaignController` response.
 */
export function useCampaignList(): UseCampaignListResult {
  const [filters, setFilters] = useState<CampaignFilters>({});
  const [sort, setSort] = useState<CampaignSort>({ field: 'createdAt', direction: 'desc' });

  const { data: response, isLoading, error: queryError } = useQuery({
    queryKey: ['admin', 'campaigns'],
    queryFn: () => campaignApi.listAll(),
  });

  const allCampaigns = response?.data ?? [];
  const error = queryError ? String(queryError) : response?.success === false ? (response.error ?? 'Failed to load campaigns') : null;

  const campaigns = useMemo(() => {
    let rows = allCampaigns;

    if (filters.search) {
      const term = filters.search.trim().toLowerCase();
      rows = rows.filter(
        (c) => c.name.toLowerCase().includes(term) || c.brandName.toLowerCase().includes(term),
      );
    }
    if (filters.status) {
      rows = rows.filter((c) => c.status === filters.status);
    }
    if (filters.type) {
      rows = rows.filter((c) => c.type === filters.type);
    }
    if (filters.brandId) {
      rows = rows.filter((c) => c.brandName === filters.brandId);
    }
    if (filters.atRisk) {
      rows = rows.filter((c) => c.slaBreachRate >= 10);
    }

    const sorted = [...rows].sort((a, b) => {
      const { field, direction } = sort;
      const dir = direction === 'asc' ? 1 : -1;
      const aVal = a[field];
      const bVal = b[field];

      if (typeof aVal === 'number' && typeof bVal === 'number') {
        return (aVal - bVal) * dir;
      }
      return String(aVal).localeCompare(String(bVal)) * dir;
    });

    return sorted;
  }, [allCampaigns, filters, sort]);

  return {
    campaigns,
    totalCount: allCampaigns.length,
    isLoading,
    error,
    filters,
    setFilters,
    sort,
    setSort,
  };
}
