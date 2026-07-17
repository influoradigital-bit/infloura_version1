/**
 * INFLUORA ADMIN PANEL — Brand Detail data hook
 * Owner: Ananya (Frontend) · live-wired by Priya (CTO), P1-WIRE-3
 *
 * Backed by the live `brandApi.getById(id)` call
 * (`GET /api/v1/admin/brands/{id}`, implemented in AdminBrandController).
 * The previous mock has been removed. Public `UseBrandDetailResult` shape is
 * unchanged so `BrandProfile` needs no edits; `refresh()` lets action handlers
 * (KYC verify / suspend / reinstate) re-pull the record after a mutation.
 *
 * SECURITY: this surface is KYC/compliance-sensitive — server-side
 * re-authorization (AdminContextService role + MFA gate) is the source of
 * truth; this hook only reads. PENDING Kabir review before ship.
 */

import { useCallback, useEffect, useState } from 'react';
import type { BrandDetail } from '../types/admin.types';
import { brandApi } from '../services/api-contracts';

// ============================================
// HOOK
// ============================================

export interface UseBrandDetailResult {
  data: BrandDetail | null;
  isLoading: boolean;
  error: string | null;
  /** Re-fetch the brand (e.g. after a KYC/suspend/reinstate action). */
  refresh: () => void;
}

/**
 * Returns full brand detail for the admin brand profile view from the live backend.
 */
export function useBrandDetail(brandId: string | undefined): UseBrandDetailResult {
  const [data, setData] = useState<BrandDetail | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  const refresh = useCallback(() => setReloadKey((k) => k + 1), []);

  useEffect(() => {
    if (!brandId) {
      setData(null);
      setIsLoading(false);
      setError(null);
      return;
    }

    let cancelled = false;
    const controller = new AbortController();
    setIsLoading(true);
    setError(null);

    brandApi
      .getById(brandId, controller.signal)
      .then((res) => {
        if (cancelled) return;
        if (res.success && res.data) {
          setData(res.data);
          setError(null);
        } else {
          setData(null);
          setError(res.error ?? 'Failed to load brand');
        }
      })
      .catch(() => {
        if (cancelled) return;
        setData(null);
        setError('Failed to load brand');
      })
      .finally(() => {
        if (!cancelled) setIsLoading(false);
      });

    return () => {
      cancelled = true;
      controller.abort();
    };
  }, [brandId, reloadKey]);

  return { data, isLoading, error, refresh };
}
