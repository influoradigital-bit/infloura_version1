import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import type { VerificationStatus } from '@/lib/types';

export interface WorkspaceVerification {
  /** Null while unresolved, or if the server sent no status. */
  status: VerificationStatus | null;
  isLoading: boolean;
  isVerified: boolean;
  /**
   * Whether the current member may submit verification. OWNER/ADMIN can; everyone else
   * must ask an admin — so the "Start verification" CTA is never shown to someone who
   * cannot action it. Role is read the same way the campaigns list does
   * (`brand_user_id` matched against `workspaceMembers.list()`), the only client-side
   * source of the caller's role.
   */
  canVerify: boolean;
}

const STALE_MS = 5 * 60 * 1000;

/**
 * Single source of truth for the workspace verification gate, shared by the proactive
 * banner and the inline "publish blocked" box so the two can never diverge. React Query
 * (mounted app-wide in App.tsx) dedupes the two queries across every consumer by key, so
 * mounting this on multiple surfaces costs one fetch each per stale window, not per mount.
 */
export function useWorkspaceVerification(): WorkspaceVerification {
  const me = useQuery({
    queryKey: ['workspace', 'me'],
    queryFn: () => api.workspaces.getMe(),
    staleTime: STALE_MS,
  });

  const role = useQuery({
    queryKey: ['workspace', 'my-role'],
    queryFn: async () => {
      const members = await api.workspaceMembers.list();
      const myId = localStorage.getItem('brand_user_id');
      return members.find((m) => m.userId === myId)?.role ?? null;
    },
    staleTime: STALE_MS,
  });

  const status = me.data?.verificationStatus ?? null;
  const memberRole = role.data ?? null;

  return {
    status,
    isLoading: me.isLoading,
    isVerified: status === 'VERIFIED',
    canVerify: memberRole === 'OWNER' || memberRole === 'ADMIN',
  };
}
