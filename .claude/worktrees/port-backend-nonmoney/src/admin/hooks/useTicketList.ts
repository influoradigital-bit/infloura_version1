/**
 * INFLUORA ADMIN PANEL — Support Ticket List + Detail data hooks
 * Owner: Ananya (Frontend) · live-wired by Priya (CTO), P1-WIRE-2
 * Reference: src/admin/components/support/TicketList.tsx
 *
 * Backed by the live `supportApi.list()` / `supportApi.getById()` calls
 * (AdminSupportController: `GET /api/v1/admin/support/tickets` and
 * `/tickets/{id}`). Filters are sent to the server on the list request and the
 * hook refetches when they change; sorting stays client-side (the backend does
 * not expose a sort param yet). The previous mock data has been removed.
 *
 * `useTicketDetail` is a `react-query` `useQuery` (queryKey
 * `['admin', 'support-ticket', ticketId]`) so the reply/assign/escalate
 * mutations wired up in `TicketList.tsx`'s detail drawer can invalidate it
 * and get a fresh ticket back, same pattern as `useFlagQueue`/`FlagQueue.tsx`.
 * `useTicketList` stays a hand-rolled fetch (not react-query) but now exposes
 * `refetch` so the drawer can also refresh the list row (assignee/status/
 * updatedAt) after an action succeeds.
 *
 * The public `UseTicketListResult` / `UseTicketDetailResult` shapes are
 * additive only (both gained a field), so existing consumers are unaffected.
 * `totalCount` reflects the server-side total for the current filter, not the
 * length of the loaded page.
 */

import { useCallback, useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { TicketPriority } from '../types/admin.types';
import type { SupportTicket, TicketDetail, TicketFilters } from '../types/admin.types';
import { supportApi } from '../services/api-contracts';

// ============================================
// SORT / FILTER TYPES
// ============================================

export type TicketSortField = 'subject' | 'userName' | 'status' | 'priority' | 'assignedToName' | 'updatedAt';
export type SortDirection = 'asc' | 'desc';

export interface TicketSort {
  field: TicketSortField;
  direction: SortDirection;
}

export interface UseTicketListResult {
  /** Filtered + sorted rows, ready to render. */
  tickets: SupportTicket[];
  /** Server-side total for the active filter, for "N of M tickets" copy. */
  totalCount: number;
  isLoading: boolean;
  error: string | null;
  filters: TicketFilters;
  setFilters: (filters: TicketFilters) => void;
  sort: TicketSort;
  setSort: (sort: TicketSort) => void;
  /** Re-runs the list fetch with the current filters (e.g. after a reply/
   *  assign/escalate action changes a row's status, assignee, or updatedAt). */
  refetch: () => void;
}

const PRIORITY_RANK: Record<TicketPriority, number> = {
  [TicketPriority.URGENT]: 3,
  [TicketPriority.HIGH]: 2,
  [TicketPriority.MEDIUM]: 1,
  [TicketPriority.LOW]: 0,
};

/**
 * Returns the admin support-ticket list from the live backend. Filters are
 * applied server-side (refetch on change); sort is applied client-side over
 * the returned page.
 */
export function useTicketList(): UseTicketListResult {
  const [rows, setRows] = useState<SupportTicket[]>([]);
  const [totalCount, setTotalCount] = useState(0);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [filters, setFilters] = useState<TicketFilters>({});
  const [sort, setSort] = useState<TicketSort>({ field: 'updatedAt', direction: 'desc' });
  const [refetchIndex, setRefetchIndex] = useState(0);

  useEffect(() => {
    let cancelled = false;
    setIsLoading(true);
    setError(null);

    supportApi
      .list(filters)
      .then((res) => {
        if (cancelled) return;
        if (res.success && res.data) {
          setRows(res.data.data);
          setTotalCount(res.data.total);
          setError(null);
        } else {
          setRows([]);
          setTotalCount(0);
          setError(res.error ?? 'Failed to load support tickets');
        }
      })
      .catch(() => {
        if (cancelled) return;
        setRows([]);
        setTotalCount(0);
        setError('Failed to load support tickets');
      })
      .finally(() => {
        if (!cancelled) setIsLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [filters, refetchIndex]);

  const refetch = useCallback(() => {
    setRefetchIndex((i) => i + 1);
  }, []);

  const tickets = useMemo(() => {
    const sorted = [...rows].sort((a, b) => {
      const { field, direction } = sort;
      const dir = direction === 'asc' ? 1 : -1;

      if (field === 'priority') {
        return (PRIORITY_RANK[a.priority] - PRIORITY_RANK[b.priority]) * dir;
      }

      const aVal = a[field] ?? '';
      const bVal = b[field] ?? '';
      return String(aVal).localeCompare(String(bVal)) * dir;
    });

    return sorted;
  }, [rows, sort]);

  return {
    tickets,
    totalCount,
    isLoading,
    error,
    filters,
    setFilters,
    sort,
    setSort,
    refetch,
  };
}

// ============================================
// TICKET DETAIL — message thread for the drawer
// ============================================

export interface UseTicketDetailResult {
  data: TicketDetail | null;
  isLoading: boolean;
  error: string | null;
}

/** Shared react-query key so the reply/assign/escalate mutations in
 *  `TicketList.tsx` can invalidate exactly this ticket's detail query. */
export function ticketDetailQueryKey(ticketId: string) {
  return ['admin', 'support-ticket', ticketId] as const;
}

/**
 * Returns the full ticket detail (message thread + related entities) for the
 * detail drawer, from the live `supportApi.getById(id)` endpoint via
 * react-query — same pattern as `useFlagQueue`. Deliberately a separate
 * hook/fetch from `useTicketList` (list rows don't carry `messages`).
 */
export function useTicketDetail(ticketId: string | undefined): UseTicketDetailResult {
  const { data, isLoading, error } = useQuery({
    queryKey: ticketDetailQueryKey(ticketId ?? ''),
    queryFn: async () => {
      const res = await supportApi.getById(ticketId as string);
      if (res.success && res.data) return res.data;
      throw new Error(res.error ?? 'Ticket not found.');
    },
    enabled: Boolean(ticketId),
  });

  return {
    data: data ?? null,
    isLoading,
    error: error ? error.message : null,
  };
}
