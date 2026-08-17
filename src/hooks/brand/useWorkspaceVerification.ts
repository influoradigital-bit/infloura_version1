import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import type { VerificationStatus } from '@/lib/types';

export interface WorkspaceVerification {
  /** Null while unresolved, or if the server sent no status. */
  status: VerificationStatus | null;
  /** True while either the verification status or the caller's role is still on its first fetch. */
  isLoading: boolean;
  isVerified: boolean;
  /**
   * True once the caller's role has been positively identified — a successful fetch that matched
   * a real member row for a known `brand_user_id`. False while `role` is still loading, when the
   * role query rejected, or when there was no id / no matching row to read a role off of. Check
   * this (or `roleError`) before treating `canVerify === false` as "this member is not an admin":
   * it may simply mean the role hasn't been determined yet.
   */
  roleResolved: boolean;
  /**
   * True when the role could not be determined at all — the query rejected, or there was no
   * `brand_user_id` in localStorage (or no member row matched it). This is the "unknown,
   * recoverable" case F-0244 was filed against: it must never be presented as a confirmed
   * "you're not an admin". Use `retryRole` to give the caller a way to recover.
   */
  roleError: boolean;
  /** Re-fetches the role query — the recovery action implied by `roleError`. */
  retryRole: () => void;
  /**
   * Whether the current member may submit verification. OWNER/ADMIN can; everyone else
   * must ask an admin — so the "Start verification" CTA is never shown to someone who
   * cannot action it. Role is read the same way the campaigns list does
   * (`brand_user_id` matched against `workspaceMembers.list()`), the only client-side
   * source of the caller's role.
   *
   * Fails OPEN (true) whenever `roleResolved` is false — i.e. while the role query is loading, if
   * it rejects, or if `brand_user_id` is absent — so an unresolved role can never produce the
   * terminal "only an Owner or Admin can verify" message (F-0244: that message was shown to the
   * sole workspace OWNER because the role query was simply still in flight, with no retry and no
   * error state). The server still enforces the real gate on submit — this is UI guidance only.
   * Only a *resolved* fetch that found a real, non-admin role earns `false`.
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

  // A missing id means the lookup never had anything to match against — the resulting `null`
  // role means "unknown", not "confirmed non-member". Read independently of the query's own
  // outcome so it also covers a stale cached success from before the id was ever set.
  const idMissing = !localStorage.getItem('brand_user_id');
  // "Resolved" requires a successful fetch that actually matched a member row for a known id.
  // Anything short of that (still loading, rejected, no id, or no matching row) is unknown.
  const roleResolved = role.isSuccess && !idMissing && memberRole !== null;
  const roleError = role.isError || (role.isSuccess && (idMissing || memberRole === null));

  return {
    status,
    isLoading: me.isLoading || role.isLoading,
    isVerified: status === 'VERIFIED',
    roleResolved,
    roleError,
    retryRole: () => void role.refetch(),
    canVerify: roleResolved ? memberRole === 'OWNER' || memberRole === 'ADMIN' : true,
  };
}
