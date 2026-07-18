/**
 * INFLUORA ADMIN PANEL — Approval Queue data hook
 * Owner: Ananya (Frontend)
 * Reference: A7 (Priya-routed admin-panel audit fix), backs
 * src/admin/components/moderation/ApprovalQueue.tsx
 *
 * Wired via `react-query` to the previously-unused `moderationApi
 * .getPendingApprovals()` / `.processApproval()` calls (real
 * `AdminModerationController`, Vikram) — same `useQuery` +
 * `useMutation`/`invalidateQueries` pattern as `useFlagQueue.ts` and
 * `FlagQueue.tsx`'s action mutation.
 */

import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ApprovalWorkflow, WorkflowType } from '../types/admin.types';
import { moderationApi } from '../services/api-contracts';

export interface UseApprovalQueueResult {
  /** Pending approvals for the active type filter (or all types). */
  approvals: ApprovalWorkflow[];
  totalCount: number;
  isLoading: boolean;
  error: string | null;
  typeFilter: WorkflowType | undefined;
  setTypeFilter: (type: WorkflowType | undefined) => void;
  /** Approve or reject a workflow; refetches the queue on success. */
  processApproval: (id: string, action: 'APPROVE' | 'REJECT', notes: string) => void;
  isProcessing: boolean;
  processError: string | null;
}

/**
 * Returns the admin approval queue (pending `ApprovalWorkflow` rows) from
 * the live backend, with an optional workflow-type filter and an
 * approve/reject mutation.
 */
export function useApprovalQueue(): UseApprovalQueueResult {
  const [typeFilter, setTypeFilter] = useState<WorkflowType | undefined>(undefined);
  const queryClient = useQueryClient();

  const {
    data: response,
    isLoading,
    error: queryError,
  } = useQuery({
    queryKey: ['admin', 'approvals', typeFilter],
    queryFn: () => moderationApi.getPendingApprovals(typeFilter),
  });

  const approvals = response?.data ?? [];
  const error = queryError
    ? String(queryError)
    : response && !response.success
      ? (response.error ?? 'Failed to load approvals')
      : null;

  const mutation = useMutation({
    mutationFn: ({ id, action, notes }: { id: string; action: 'APPROVE' | 'REJECT'; notes: string }) =>
      moderationApi.processApproval(id, action, notes),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin', 'approvals'] });
    },
  });

  return {
    approvals,
    totalCount: approvals.length,
    isLoading,
    error,
    typeFilter,
    setTypeFilter,
    processApproval: (id, action, notes) => mutation.mutate({ id, action, notes }),
    isProcessing: mutation.isPending,
    processError: mutation.error ? String(mutation.error) : null,
  };
}
