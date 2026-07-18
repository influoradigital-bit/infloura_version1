/**
 * INFLUORA ADMIN PANEL — Email Queue data hook
 * Owner: Ananya (Frontend)
 * Reference: Wire-up pass — Email Queue console (src/admin/pages/EmailQueuePage.tsx)
 *
 * Backed by the live `emailApi.getQueue(status, page, pageSize)` +
 * `emailApi.getStats()` + `emailApi.getTemplates()` calls
 * (`GET /api/v1/admin/emails/queue`, `.../emails/stats`, `.../emails/templates`,
 * AdminEmailController-equivalent). Three independent reads (queue is
 * paginated + status-filtered, stats and templates are unfiltered snapshots)
 * plus a `retry(id)` action wired to `emailApi.retry()` that refreshes the
 * queue + stats on success.
 *
 * `emailApi.sendBulk()` is deliberately NOT called anywhere in this hook —
 * the backend returns 501 for it pending abuse controls. `EmailQueuePage.tsx`
 * renders that control disabled with an explanatory tooltip instead of
 * wiring it up.
 */

import { useCallback, useEffect, useState } from 'react';
import type { EmailQueueItem } from '../types/admin.types';
import { emailApi } from '../services/api-contracts';

export const EMAIL_QUEUE_PAGE_SIZE = 20;

// ============================================
// TYPES
// ============================================

export interface EmailQueueStats {
  sent24h: number;
  failed24h: number;
  pending: number;
  avgDeliveryTime: number;
}

export interface EmailTemplateSummary {
  id: string;
  name: string;
  subject: string;
}

export interface UseEmailQueueResult {
  items: EmailQueueItem[];
  totalCount: number;
  totalPages: number;
  stats: EmailQueueStats | null;
  templates: EmailTemplateSummary[];
  isLoading: boolean;
  error: string | null;
  statusFilter: string | undefined;
  setStatusFilter: (status: string | undefined) => void;
  page: number;
  setPage: (page: number) => void;
  /** Retry one queued/failed email; refreshes the queue + stats on success. */
  retry: (id: string) => Promise<{ success: boolean; error?: string }>;
}

// ============================================
// HOOK
// ============================================

/**
 * Returns the admin email queue console's queue/stats/templates from the live backend.
 */
export function useEmailQueue(): UseEmailQueueResult {
  const [items, setItems] = useState<EmailQueueItem[]>([]);
  const [totalCount, setTotalCount] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [stats, setStats] = useState<EmailQueueStats | null>(null);
  const [templates, setTemplates] = useState<EmailTemplateSummary[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [statusFilter, setStatusFilterState] = useState<string | undefined>(undefined);
  const [page, setPage] = useState(1);
  const [reloadKey, setReloadKey] = useState(0);

  function setStatusFilter(status: string | undefined) {
    setStatusFilterState(status);
    setPage(1);
  }

  const refresh = useCallback(() => setReloadKey((k) => k + 1), []);

  useEffect(() => {
    let cancelled = false;
    setIsLoading(true);
    setError(null);

    Promise.all([
      emailApi.getQueue(statusFilter, page, EMAIL_QUEUE_PAGE_SIZE),
      emailApi.getStats(),
      emailApi.getTemplates(),
    ])
      .then(([queueRes, statsRes, templatesRes]) => {
        if (cancelled) return;

        if (queueRes.success && queueRes.data) {
          setItems(queueRes.data.data);
          setTotalCount(queueRes.data.total);
          setTotalPages(queueRes.data.totalPages);
        } else {
          setItems([]);
          setTotalCount(0);
          setTotalPages(0);
        }

        setStats(statsRes.success && statsRes.data ? statsRes.data : null);
        setTemplates(templatesRes.success && templatesRes.data ? templatesRes.data : []);

        if (!queueRes.success) {
          setError(queueRes.error ?? 'Failed to load email queue');
        } else {
          setError(null);
        }
      })
      .catch(() => {
        if (cancelled) return;
        setItems([]);
        setTotalCount(0);
        setTotalPages(0);
        setStats(null);
        setTemplates([]);
        setError('Failed to load email queue');
      })
      .finally(() => {
        if (!cancelled) setIsLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [statusFilter, page, reloadKey]);

  const retry = useCallback(
    async (id: string) => {
      const res = await emailApi.retry(id);
      if (res.success) {
        refresh();
        return { success: true };
      }
      return { success: false, error: res.error ?? 'Failed to retry email.' };
    },
    [refresh],
  );

  return {
    items,
    totalCount,
    totalPages,
    stats,
    templates,
    isLoading,
    error,
    statusFilter,
    setStatusFilter,
    page,
    setPage,
    retry,
  };
}
