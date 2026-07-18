/**
 * INFLUORA ADMIN PANEL — Approval Queue
 * Owner: Ananya (Frontend)
 * Reference: A7 (Priya-routed admin-panel audit fix)
 *
 * Surfaces pending `ApprovalWorkflow` rows (creator applications, brand KYC,
 * escrow release, content moderation, account suspension — see
 * `WorkflowType` in admin.types.ts) via the previously-unused
 * `moderationApi.getPendingApprovals()` / `.processApproval()` calls.
 * Mounted as the "Approvals" tab of `ModerationPage.tsx` alongside the
 * existing `FlagQueue` — `FlagQueue.tsx` itself is untouched.
 *
 * Approve/Reject follow the same reason-required `AlertDialog` + `Textarea`
 * pattern as `FlagQueue.tsx`'s `ModerationActionButton` and
 * `BrandProfile`/`CreatorProfile`'s suspend/reject actions: every decision
 * requires a note for the audit trail, with `queryClient.invalidateQueries`
 * on success (see `useApprovalQueue.ts`) and mutation errors surfaced
 * inline rather than swallowed.
 */

import { useState } from 'react';
import { CheckCircle2, ClipboardCheck, Clock, XCircle } from 'lucide-react';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '@/components/ui/alert-dialog';
import { cn } from '@/lib/utils';
import { useApprovalQueue } from '../../hooks/useApprovalQueue';
import { WorkflowType, type ApprovalWorkflow } from '../../types/admin.types';

// ============================================
// FORMATTING
// ============================================

function formatDateTime(iso: string | undefined): string {
  if (!iso) return '—';
  return new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(iso));
}

function workflowTypeLabel(type: WorkflowType): string {
  return type
    .toLowerCase()
    .split('_')
    .map((word) => word[0].toUpperCase() + word.slice(1))
    .join(' ');
}

// ============================================
// APPROVE / REJECT — reason-required pattern, same shape as
// FlagQueue.tsx's ModerationActionButton.
// ============================================

function ApprovalActionButton({
  action,
  disabled,
  onConfirm,
}: {
  action: 'APPROVE' | 'REJECT';
  disabled: boolean;
  onConfirm: (notes: string) => void;
}) {
  const [notes, setNotes] = useState('');
  const isApprove = action === 'APPROVE';

  return (
    <AlertDialog
      onOpenChange={(open) => {
        if (!open) setNotes('');
      }}
    >
      <AlertDialogTrigger asChild>
        <Button
          type="button"
          size="sm"
          variant={isApprove ? 'default' : 'destructive'}
          disabled={disabled}
          aria-label={isApprove ? 'Approve workflow' : 'Reject workflow'}
        >
          {isApprove ? <CheckCircle2 aria-hidden="true" /> : <XCircle aria-hidden="true" />}
          {isApprove ? 'Approve' : 'Reject'}
        </Button>
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{isApprove ? 'Approve this request?' : 'Reject this request?'}</AlertDialogTitle>
          <AlertDialogDescription>
            {isApprove
              ? 'This confirms the workflow and records your decision to the audit log.'
              : 'This rejects the workflow and records your decision to the audit log.'}
          </AlertDialogDescription>
        </AlertDialogHeader>
        <Textarea
          value={notes}
          onChange={(e) => setNotes(e.target.value)}
          placeholder={isApprove ? 'Notes (e.g. docs verified)' : 'Reason for rejection'}
        />
        <AlertDialogFooter>
          <AlertDialogCancel>Cancel</AlertDialogCancel>
          <AlertDialogAction disabled={!notes.trim()} onClick={() => onConfirm(notes.trim())}>
            {isApprove ? 'Approve' : 'Reject'}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

// ============================================
// COMPONENT
// ============================================

export interface ApprovalQueueProps {
  className?: string;
}

export default function ApprovalQueue({ className }: ApprovalQueueProps) {
  const {
    approvals,
    totalCount,
    isLoading,
    error,
    typeFilter,
    setTypeFilter,
    processApproval,
    isProcessing,
    processError,
  } = useApprovalQueue();

  if (error) {
    return (
      <div className="rounded-lg border border-destructive-foreground/30 bg-card p-4 text-sm text-destructive-foreground">
        Failed to load approvals: {error}
      </div>
    );
  }

  return (
    <div className={cn('flex flex-col gap-4', className)}>
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <Select
          value={typeFilter ?? 'ALL'}
          onValueChange={(value) => setTypeFilter(value === 'ALL' ? undefined : (value as WorkflowType))}
        >
          <SelectTrigger className="sm:w-56" aria-label="Filter by workflow type">
            <SelectValue placeholder="All workflow types" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="ALL">All workflow types</SelectItem>
            {Object.values(WorkflowType).map((type) => (
              <SelectItem key={type} value={type}>
                {workflowTypeLabel(type)}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>

        <span className="whitespace-nowrap text-sm text-muted-foreground">
          {isLoading ? 'Loading…' : `${totalCount} pending approval${totalCount === 1 ? '' : 's'}`}
        </span>
      </div>

      {processError && (
        <div className="rounded-lg border border-destructive-foreground/30 bg-card p-3 text-sm text-destructive-foreground">
          Action failed: {processError}
        </div>
      )}

      <div className="overflow-hidden rounded-lg border border-border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Type</TableHead>
              <TableHead>Entity</TableHead>
              <TableHead>Submitted</TableHead>
              <TableHead className="text-right">Actions</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {isLoading ? (
              Array.from({ length: 4 }).map((_, i) => (
                <TableRow key={i}>
                  {Array.from({ length: 4 }).map((__, j) => (
                    <TableCell key={j}>
                      <div className="h-4 w-full max-w-24 animate-pulse rounded bg-muted" />
                    </TableCell>
                  ))}
                </TableRow>
              ))
            ) : approvals.length === 0 ? (
              <TableRow>
                <TableCell colSpan={4} className="py-10 text-center text-sm text-muted-foreground">
                  <div className="flex flex-col items-center gap-2">
                    <ClipboardCheck className="size-6 text-muted-foreground/60" aria-hidden="true" />
                    No pending approvals.
                  </div>
                </TableCell>
              </TableRow>
            ) : (
              approvals.map((approval: ApprovalWorkflow) => (
                <TableRow key={approval.id}>
                  <TableCell>
                    <Badge variant="outline">{workflowTypeLabel(approval.type)}</Badge>
                  </TableCell>
                  <TableCell className="max-w-64 whitespace-normal font-medium text-foreground">
                    {approval.entityName}
                    <div className="text-xs font-normal text-muted-foreground">{approval.entityId}</div>
                  </TableCell>
                  <TableCell className="whitespace-nowrap text-muted-foreground">
                    <span className="inline-flex items-center gap-1.5">
                      <Clock className="size-3.5 shrink-0" aria-hidden="true" />
                      {formatDateTime(approval.submittedAt)}
                    </span>
                  </TableCell>
                  <TableCell className="text-right">
                    <div className="inline-flex items-center gap-2">
                      <ApprovalActionButton
                        action="APPROVE"
                        disabled={isProcessing}
                        onConfirm={(notes) => processApproval(approval.id, 'APPROVE', notes)}
                      />
                      <ApprovalActionButton
                        action="REJECT"
                        disabled={isProcessing}
                        onConfirm={(notes) => processApproval(approval.id, 'REJECT', notes)}
                      />
                    </div>
                  </TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </div>
    </div>
  );
}
