/**
 * INFLUORA ADMIN PANEL — Content Moderation Flag Queue data hook
 * Owner: Ananya (Frontend)
 * Reference: src/admin/TASK_ASSIGNMENTS.md (P2 — "Content flag queue",
 * src/admin/components/moderation/FlagQueue.tsx)
 *
 * `ContentFlag` / `ContentFlagStatus` already exist in
 * `src/admin/types/admin.types.ts` (Priya-owned, folded in earlier this
 * cycle) and match the `content_flags` table exactly (V34__admin_tables.sql,
 * mirrored by `influora-api`'s `ContentFlag` JPA entity).
 *
 * P2-6: Wired to real AdminModerationController (Vikram, 2026-07-12).
 * Backend: GET /api/v1/admin/moderation/flags
 * Frontend: moderationApi.getContentFlags() via api-contracts.ts
 */

import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ContentFlagStatus } from '../types/admin.types';
import type { ContentFlag } from '../types/admin.types';
import { moderationApi } from '../services/api-contracts';

// ============================================
// SORT / FILTER TYPES
// ============================================

export type FlagSortField = 'createdAt' | 'status' | 'contentType' | 'flaggedBy';
export type FlagSortDirection = 'asc' | 'desc';

export interface FlagSort {
  field: FlagSortField;
  direction: FlagSortDirection;
}

/**
 * Not promoted to `admin.types.ts` — `ContentFlag` itself has no dedicated
 * `*Filters` type there yet (unlike `TicketFilters`/`CampaignFilters`), so
 * this stays local to the hook until a real `moderationApi.list(filters)`
 * contract needs it to be shared.
 */
export interface FlagQueueFilters {
  search?: string;
  status?: ContentFlagStatus;
  contentType?: ContentFlag['contentType'];
}

export interface UseFlagQueueResult {
  /** Filtered + sorted rows, ready to render. */
  flags: ContentFlag[];
  /** Unfiltered total, for "N of M flags" copy. */
  totalCount: number;
  /** Unfiltered count of PENDING flags, for a queue-depth badge. */
  pendingCount: number;
  isLoading: boolean;
  error: string | null;
  filters: FlagQueueFilters;
  setFilters: (filters: FlagQueueFilters) => void;
  sort: FlagSort;
  setSort: (sort: FlagSort) => void;
}

/**
 * Returns the admin content-moderation flag queue, with client-side
 * sort/filter applied. Backed by real AdminModerationController.
 */
export function useFlagQueue(): UseFlagQueueResult {
  const [filters, setFilters] = useState<FlagQueueFilters>({});
  const [sort, setSort] = useState<FlagSort>({ field: 'createdAt', direction: 'desc' });

  const { data: response, isLoading, error: queryError } = useQuery({
    queryKey: ['admin', 'content-flags', filters.status],
    queryFn: () => moderationApi.getContentFlags(filters.status),
  });

  const allFlags = response?.data?.data ?? [];
  const error = queryError ? String(queryError) : null;

  const flags = useMemo(() => {
    let rows = allFlags;

    if (filters.search) {
      const term = filters.search.trim().toLowerCase();
      rows = rows.filter(
        (f) =>
          f.flagReason.toLowerCase().includes(term) ||
          f.contentId.toLowerCase().includes(term) ||
          (f.contentPreview?.toLowerCase().includes(term) ?? false),
      );
    }
    if (filters.contentType) {
      rows = rows.filter((f) => f.contentType === filters.contentType);
    }

    const sorted = [...rows].sort((a, b) => {
      const { field, direction } = sort;
      const dir = direction === 'asc' ? 1 : -1;
      const aVal = a[field] ?? '';
      const bVal = b[field] ?? '';
      return String(aVal).localeCompare(String(bVal)) * dir;
    });

    return sorted;
  }, [allFlags, filters, sort]);

  return {
    flags,
    totalCount: allFlags.length,
    pendingCount: allFlags.filter((f) => f.status === ContentFlagStatus.PENDING).length,
    isLoading,
    error,
    filters,
    setFilters,
    sort,
    setSort,
  };
}
