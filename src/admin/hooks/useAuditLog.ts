/**
 * INFLUORA ADMIN PANEL — Audit Log data hook
 * Owner: Ananya (Frontend)
 * Reference: A6 (Priya-routed admin-panel audit fix), backs
 * src/admin/pages/AuditLogPage.tsx
 *
 * Backed by the live `auditApi.list()` call (`GET /api/v1/admin/audit`,
 * `AuditLogController.list()`). Mirrors `useBrandList.ts` exactly:
 * server-side filtering + pagination, cancel-flag cleanup on unmount,
 * `setFilters` resets to page 1 on every filter change.
 */

import { useEffect, useState } from 'react';
import type { AuditLogEntry } from '../types/admin.types';
import { auditApi } from '../services/api-contracts';

export const AUDIT_LOG_PAGE_SIZE = 50;

/**
 * Not promoted to `admin.types.ts` — mirrors the inline filter shape
 * `auditApi.list()` already declares in api-contracts.ts (no dedicated
 * `AuditLogFilters` type exists there yet, unlike `BrandFilters`/`TicketFilters`).
 */
export interface AuditLogFilters {
  adminId?: string;
  entityType?: string;
  action?: string;
  startDate?: string;
  endDate?: string;
}

export interface UseAuditLogResult {
  /** Current page of rows from the server. */
  entries: AuditLogEntry[];
  /** Server-side total for the active filter, for "N of M entries" copy. */
  totalCount: number;
  totalPages: number;
  isLoading: boolean;
  error: string | null;
  filters: AuditLogFilters;
  /** Replaces the filter set and resets to page 1 (new query context). */
  setFilters: (filters: AuditLogFilters) => void;
  page: number;
  setPage: (page: number) => void;
}

/**
 * Returns the admin audit log rows from the live backend, with server-side
 * filtering and pagination.
 */
export function useAuditLog(): UseAuditLogResult {
  const [rows, setRows] = useState<AuditLogEntry[]>([]);
  const [totalCount, setTotalCount] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [filters, setFiltersState] = useState<AuditLogFilters>({});
  const [page, setPage] = useState(1);

  function setFilters(next: AuditLogFilters) {
    setFiltersState(next);
    setPage(1);
  }

  useEffect(() => {
    let cancelled = false;
    setIsLoading(true);
    setError(null);

    auditApi
      .list(filters, page, AUDIT_LOG_PAGE_SIZE)
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
          setError(res.error ?? 'Failed to load audit log');
        }
      })
      .catch(() => {
        if (cancelled) return;
        setRows([]);
        setTotalCount(0);
        setTotalPages(0);
        setError('Failed to load audit log');
      })
      .finally(() => {
        if (!cancelled) setIsLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [filters, page]);

  return {
    entries: rows,
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
