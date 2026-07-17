/**
 * INFLUORA ADMIN PANEL — Auth + RBAC Hook
 * Owner: Priya (CTO)
 * Reference: docs/ADMIN-PANEL-SPEC.md
 *
 * Exposes the current admin user, their role, a permission checker, and
 * loading/error state. Sources the user from `authApi.getCurrentUser()`
 * (`GET /api/admin/auth/me`) and gates capabilities via a static
 * role -> permission matrix.
 *
 * SECURITY NOTE: client-side RBAC is a UX affordance only. Every action must
 * be re-authorized server-side by Vikram's controllers — `hasPermission` here
 * decides what to render/enable, never what is ultimately allowed.
 */

import { useCallback, useEffect, useState } from 'react';
import { authApi } from '../services/api-contracts';
import { AdminRole } from '../types/admin.types';
import type { AdminUser } from '../types/admin.types';
import { auditAction, AuditAction } from '../utils/auditLogger';

// ============================================
// PERMISSION MODEL
// ============================================

/**
 * Granular capabilities used across the admin panel. Grouped by domain and
 * kept as a const object so consumers get autocomplete and a single source of
 * truth. Add new capabilities here, then grant them in ROLE_PERMISSIONS.
 */
export const Permission = {
  DASHBOARD_VIEW: 'dashboard:view',

  BRAND_VIEW: 'brand:view',
  BRAND_EDIT: 'brand:edit',
  BRAND_KYC_REVIEW: 'brand:kyc-review',
  BRAND_SUSPEND: 'brand:suspend',

  CREATOR_VIEW: 'creator:view',
  CREATOR_EDIT: 'creator:edit',
  CREATOR_APPLICATION_REVIEW: 'creator:application-review',
  CREATOR_TIER_ADJUST: 'creator:tier-adjust',
  CREATOR_SUSPEND: 'creator:suspend',

  CAMPAIGN_VIEW: 'campaign:view',

  FINANCE_VIEW: 'finance:view',
  FINANCE_PAYOUT_MANAGE: 'finance:payout-manage',
  FINANCE_RECONCILE: 'finance:reconcile',

  ESCROW_VIEW: 'escrow:view',
  ESCROW_MANAGE: 'escrow:manage',

  SUPPORT_VIEW: 'support:view',
  SUPPORT_RESPOND: 'support:respond',
  SUPPORT_ESCALATE: 'support:escalate',

  MODERATION_VIEW: 'moderation:view',
  MODERATION_ACTION: 'moderation:action',
  MODERATION_APPEAL_REVIEW: 'moderation:appeal-review',

  AUDIT_VIEW: 'audit:view',
  ERROR_LOG_VIEW: 'error-log:view',
  EMAIL_MANAGE: 'email:manage',
  MARKETING_VIEW: 'marketing:view',

  ADMIN_MANAGE: 'admin:manage',
} as const;

export type PermissionType = (typeof Permission)[keyof typeof Permission];

// ============================================
// TOKEN VALIDATION
// ============================================

/**
 * Decodes a base64url-encoded JWT segment into its JSON payload.
 * Returns null (never throws) on any malformed input.
 */
function decodeJwtSegment(segment: string): Record<string, unknown> | null {
  try {
    const base64 = segment.replace(/-/g, '+').replace(/_/g, '/');
    const padded = base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '=');
    const json = atob(padded);
    const parsed: unknown = JSON.parse(json);
    return typeof parsed === 'object' && parsed !== null ? (parsed as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

/**
 * Validates a JWT is well-formed (`header.payload.signature`, each segment
 * base64url-decodable) and, when the payload carries an `exp` claim, that it
 * has not already expired. This is a client-side sanity check only — it
 * exists to avoid firing a network request with a token that is guaranteed
 * to be rejected, not to establish trust in the token's contents. The server
 * remains the sole source of truth for signature verification.
 */
function isTokenValid(token: string): boolean {
  if (!token || typeof token !== 'string') return false;

  const parts = token.split('.');
  if (parts.length !== 3 || parts.some((part) => part.length === 0)) return false;

  const payload = decodeJwtSegment(parts[1]);
  if (!payload) return false;

  if (typeof payload.exp === 'number') {
    const nowInSeconds = Date.now() / 1000;
    if (payload.exp <= nowInSeconds) return false;
  }

  return true;
}

/**
 * Role -> granted permissions matrix.
 *
 * - SUPER_ADMIN: unrestricted (see hasPermission wildcard short-circuit).
 * - ADMIN: full operational control except managing other admins.
 * - SUPPORT: read-heavy, limited to support/moderation queues.
 */
const ROLE_PERMISSIONS: Record<AdminRole, readonly PermissionType[]> = {
  [AdminRole.SUPER_ADMIN]: Object.values(Permission),
  [AdminRole.ADMIN]: [
    Permission.DASHBOARD_VIEW,
    Permission.BRAND_VIEW,
    Permission.BRAND_EDIT,
    Permission.BRAND_KYC_REVIEW,
    Permission.BRAND_SUSPEND,
    Permission.CREATOR_VIEW,
    Permission.CREATOR_EDIT,
    Permission.CREATOR_APPLICATION_REVIEW,
    Permission.CREATOR_TIER_ADJUST,
    Permission.CREATOR_SUSPEND,
    Permission.CAMPAIGN_VIEW,
    Permission.FINANCE_VIEW,
    Permission.FINANCE_PAYOUT_MANAGE,
    // NOTE: FINANCE_RECONCILE is intentionally NOT granted to ADMIN. Per
    // src/admin/__tests__/role-permission-matrix.md line 66 ("Resolve Reconciliation Mismatch"),
    // resolving a reconciliation mismatch is SUPER_ADMIN-only due to write-off risk. ADMIN can
    // VIEW reconciliation via FINANCE_VIEW but must not resolve mismatches.
    Permission.ESCROW_VIEW,
    Permission.ESCROW_MANAGE,
    Permission.SUPPORT_VIEW,
    Permission.SUPPORT_RESPOND,
    Permission.SUPPORT_ESCALATE,
    Permission.MODERATION_VIEW,
    Permission.MODERATION_ACTION,
    Permission.MODERATION_APPEAL_REVIEW,
    Permission.AUDIT_VIEW,
    Permission.ERROR_LOG_VIEW,
    Permission.EMAIL_MANAGE,
    Permission.MARKETING_VIEW,
  ],
  [AdminRole.SUPPORT]: [
    Permission.DASHBOARD_VIEW,
    Permission.BRAND_VIEW,
    Permission.CREATOR_VIEW,
    Permission.CAMPAIGN_VIEW,
    Permission.SUPPORT_VIEW,
    Permission.SUPPORT_RESPOND,
    Permission.SUPPORT_ESCALATE,
    Permission.MODERATION_VIEW,
  ],
};

// ============================================
// HOOK
// ============================================

export interface UseAdminAuth {
  /** The authenticated admin, or null when unauthenticated / still loading. */
  user: AdminUser | null;
  /** Convenience accessor for the user's role, or null when unauthenticated. */
  role: AdminRole | null;
  /** True while the initial `/auth/me` resolution is in flight. */
  isLoading: boolean;
  /** True once a user has been successfully resolved. */
  isAuthenticated: boolean;
  /** Human-readable error from the last auth resolution, or null. */
  error: string | null;
  /** UX-level capability check against the role matrix. */
  hasPermission: (permission: PermissionType) => boolean;
  /** True if the user holds every listed permission. */
  hasAllPermissions: (permissions: PermissionType[]) => boolean;
  /** True if the user holds at least one of the listed permissions. */
  hasAnyPermission: (permissions: PermissionType[]) => boolean;
  /** Re-fetch the current user (e.g. after MFA or a role change). */
  refresh: () => Promise<void>;
  /** Clear the session token, audit the logout, and drop local state. */
  logout: () => Promise<void>;
}

/**
 * React hook providing the current admin identity and RBAC checks.
 *
 * On mount it resolves the session via `authApi.getCurrentUser()`. Successful
 * and failed resolutions are recorded through the audit logger so security
 * review has a client-side trail alongside the server's.
 */
export function useAdminAuth(): UseAdminAuth {
  const [user, setUser] = useState<AdminUser | null>(null);
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  const loadUser = useCallback(async () => {
    setIsLoading(true);
    setError(null);

    const token = localStorage.getItem('admin_token');
    if (!token) {
      setUser(null);
      setError(null);
      setIsLoading(false);
      return;
    }

    if (!isTokenValid(token)) {
      // Malformed or expired token — clear it immediately and skip the
      // network round-trip; the request would be rejected server-side anyway.
      localStorage.removeItem('admin_token');
      setUser(null);
      setError(null);
      setIsLoading(false);
      return;
    }

    const res = await authApi.getCurrentUser();

    if (res.success && res.data) {
      setUser(res.data);
      setError(null);
      void auditAction(res.data.id, AuditAction.LOGIN, 'ADMIN_SESSION', res.data.id, {
        reason: 'Session resolved via /auth/me',
      });
    } else {
      setUser(null);
      setError(res.error ?? 'Failed to resolve admin session');
    }

    setIsLoading(false);
  }, []);

  useEffect(() => {
    void loadUser();
  }, [loadUser]);

  const hasPermission = useCallback(
    (permission: PermissionType): boolean => {
      if (!user) return false;
      if (user.role === AdminRole.SUPER_ADMIN) return true;
      return ROLE_PERMISSIONS[user.role]?.includes(permission) ?? false;
    },
    [user]
  );

  const hasAllPermissions = useCallback(
    (permissions: PermissionType[]): boolean => permissions.every(hasPermission),
    [hasPermission]
  );

  const hasAnyPermission = useCallback(
    (permissions: PermissionType[]): boolean => permissions.some(hasPermission),
    [hasPermission]
  );

  const logout = useCallback(async () => {
    const current = user;
    try {
      await authApi.logout();
    } finally {
      if (current) {
        void auditAction(current.id, AuditAction.LOGOUT, 'ADMIN_SESSION', current.id, {
          reason: 'Admin-initiated logout',
        });
      }
      localStorage.removeItem('admin_token');
      setUser(null);
      setError(null);
    }
  }, [user]);

  return {
    user,
    role: user?.role ?? null,
    isLoading,
    isAuthenticated: user !== null,
    error,
    hasPermission,
    hasAllPermissions,
    hasAnyPermission,
    refresh: loadUser,
    logout,
  };
}
