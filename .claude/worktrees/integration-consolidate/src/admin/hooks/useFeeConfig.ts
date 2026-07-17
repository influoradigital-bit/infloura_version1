/**
 * INFLUORA ADMIN PANEL — Platform Fee Config data hook
 * Owner: Ananya (Frontend) · live-wired P1-FE-1
 *
 * Backed by the live `financeApi.getFeeConfig()` / `financeApi.getFeeConfigHistory()`
 * calls (`GET /api/v1/admin/finance/fee-config`, `GET /api/v1/admin/finance/fee-config/history`,
 * `PlatformFeeAdminController`). Mock data removed. Same load/refresh pattern as
 * `useBrandDetail.ts` / `usePulseData.ts`.
 *
 * The mutation (`financeApi.updateFeeConfig`) is called directly by
 * `FeeControlPanel.tsx`, not from this hook — the panel calls `refresh()`
 * after a successful update to re-pull the now-current config + history.
 */

import { useCallback, useEffect, useState } from 'react';
import type {
  PlatformFeeConfig,
  PlatformFeeConfigWire,
  PlatformFeeHistoryEntry,
  PlatformFeeHistoryEntryWire,
} from '../types/admin.types';
import { financeApi } from '../services/api-contracts';

// ============================================
// WIRE COERCION
//
// Backend percent fields (`brandFeePercent`/`creatorFeePercent`/`previousBrandFeePercent`/
// `previousCreatorFeePercent`) are `java.math.BigDecimal` (PlatformFeeConfigDtos.java) and can
// serialize as a bare JSON number OR a string (e.g. "10.00") for precision. Coerce explicitly and
// reject anything that doesn't parse to a finite number, instead of letting a stringified value
// silently propagate as a NaN or a concatenation bug downstream.
// ============================================

function coercePercent(value: number | string, field: string): number {
  const num = typeof value === 'number' ? value : Number(value);
  if (!Number.isFinite(num)) {
    throw new Error(`Received invalid ${field} value from server: ${JSON.stringify(value)}`);
  }
  return num;
}

function normalizeFeeConfig(wire: PlatformFeeConfigWire): PlatformFeeConfig {
  return {
    ...wire,
    brandFeePercent: coercePercent(wire.brandFeePercent, 'brandFeePercent'),
    creatorFeePercent: coercePercent(wire.creatorFeePercent, 'creatorFeePercent'),
  };
}

function normalizeHistoryEntry(wire: PlatformFeeHistoryEntryWire): PlatformFeeHistoryEntry {
  return {
    ...wire,
    brandFeePercent: coercePercent(wire.brandFeePercent, 'brandFeePercent'),
    creatorFeePercent: coercePercent(wire.creatorFeePercent, 'creatorFeePercent'),
    previousBrandFeePercent: coercePercent(wire.previousBrandFeePercent, 'previousBrandFeePercent'),
    previousCreatorFeePercent: coercePercent(wire.previousCreatorFeePercent, 'previousCreatorFeePercent'),
  };
}

// ============================================
// HOOK
// ============================================

export interface UseFeeConfigResult {
  data: PlatformFeeConfig | null;
  history: PlatformFeeHistoryEntry[];
  isLoading: boolean;
  error: string | null;
  /** Re-fetch the config + history (e.g. after a successful update). */
  refresh: () => void;
}

/**
 * Returns the current platform fee schedule and its change history for the
 * admin finance fee-control panel, from the live backend.
 */
export function useFeeConfig(): UseFeeConfigResult {
  const [data, setData] = useState<PlatformFeeConfig | null>(null);
  const [history, setHistory] = useState<PlatformFeeHistoryEntry[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [refreshKey, setRefreshKey] = useState(0);

  const refresh = useCallback(() => setRefreshKey((k) => k + 1), []);

  useEffect(() => {
    let cancelled = false;
    setIsLoading(true);
    setError(null);

    Promise.all([financeApi.getFeeConfig(), financeApi.getFeeConfigHistory()])
      .then(([configRes, historyRes]) => {
        if (cancelled) return;

        // Coercion (BigDecimal -> number) can fail if the server sends a genuinely malformed
        // value — treat that the same as a failed fetch rather than letting a NaN/garbage value
        // reach the form or the KPI cards.
        let configError: string | null = null;
        if (configRes.success && configRes.data) {
          try {
            setData(normalizeFeeConfig(configRes.data));
          } catch (e) {
            setData(null);
            configError = e instanceof Error ? e.message : 'Received malformed fee configuration data from server';
          }
        } else {
          setData(null);
        }

        let historyError: string | null = null;
        if (historyRes.success && historyRes.data) {
          try {
            setHistory(historyRes.data.map(normalizeHistoryEntry));
          } catch (e) {
            setHistory([]);
            historyError = e instanceof Error ? e.message : 'Received malformed fee history data from server';
          }
        } else {
          setHistory([]);
        }

        if (!configRes.success) {
          setError(configRes.error ?? 'Failed to load platform fee configuration');
        } else if (configError) {
          setError(configError);
        } else if (!historyRes.success) {
          // Config loaded fine; history failing shouldn't block the edit form,
          // but it's still surfaced since the panel renders a history table.
          setError(historyRes.error ?? 'Failed to load fee change history');
        } else if (historyError) {
          setError(historyError);
        } else {
          setError(null);
        }
      })
      .catch(() => {
        if (cancelled) return;
        setData(null);
        setHistory([]);
        setError('Failed to load platform fee configuration');
      })
      .finally(() => {
        if (!cancelled) setIsLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [refreshKey]);

  return { data, history, isLoading, error, refresh };
}
