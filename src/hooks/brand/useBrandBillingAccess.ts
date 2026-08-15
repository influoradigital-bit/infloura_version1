import { useQuery } from '@tanstack/react-query';
import { api, isApiLive } from '@/lib/api';
import type { MemberRole } from '@/lib/types';

const STALE_MS = 5 * 60 * 1000;

/**
 * F-0132 (no-frontend-role-gate, money-path): the client-side mirror of the server gate on the
 * billing write endpoints. `BillingController` requires `requireRole(OWNER, ADMIN)` on both
 * `POST /billing/checkout` (upgrade) and `POST /billing/cancel` — so MANAGER/MEMBER/VIEWER get a
 * 403. Without this, they saw fully-enabled Upgrade/Cancel controls and only learned they couldn't
 * act by clicking and reading an error toast.
 *
 * Pure so it can be unit-pinned. Fails OPEN on the unknown cases — mock mode (no server gate to
 * mirror) and an unresolved role (loading / no membership row) leave the control enabled, keeping
 * the server the source of truth exactly as the campaigns-list Edit/Delete gate does.
 */
export function canManageBilling(role: MemberRole | null, liveApi: boolean): boolean {
  if (!liveApi) return true;
  if (role == null) return true;
  return role === 'OWNER' || role === 'ADMIN';
}

export interface BrandBillingAccess {
  role: MemberRole | null;
  /** true when the current member may upgrade/cancel (OWNER/ADMIN), or when it's unknown/mock. */
  canManage: boolean;
  isLoading: boolean;
}

/**
 * Reads the caller's workspace role the one client-side way that exists (`brand_user_id` matched
 * against `workspaceMembers.list()` — the same source `useWorkspaceVerification` and the campaigns
 * list use), and derives the billing gate. Shares the `['workspace','my-role']` React Query key so
 * it dedupes with those other consumers rather than firing its own fetch.
 */
export function useBrandBillingAccess(): BrandBillingAccess {
  const liveApi = isApiLive();
  const query = useQuery({
    queryKey: ['workspace', 'my-role'],
    queryFn: async () => {
      const members = await api.workspaceMembers.list();
      const myId = localStorage.getItem('brand_user_id');
      return (members.find((m) => m.userId === myId)?.role ?? null) as MemberRole | null;
    },
    staleTime: STALE_MS,
    enabled: liveApi,
  });

  const role = (query.data ?? null) as MemberRole | null;
  return { role, canManage: canManageBilling(role, liveApi), isLoading: query.isLoading };
}
