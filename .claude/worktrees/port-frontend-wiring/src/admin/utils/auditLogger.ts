/**
 * INFLUORA ADMIN PANEL — Audit Logger
 * Owner: Priya (CTO)
 * Reference: docs/ADMIN-PANEL-SPEC.md
 *
 * Client-side audit logging utility for privileged admin actions.
 * Records WHO did WHAT, WHEN, and on WHICH entity, then ships the record
 * to the backend audit trail.
 *
 * Usable from both the auth hook (useAdminAuth.ts) and any controller-facing
 * API call. Server enriches each entry with id, adminEmail, ipAddress and the
 * authoritative timestamp — the client only supplies what it knows.
 *
 * PATH: posts to `POST /api/v1/admin/audit` (the codebase context-path is
 * `/api/v1`; every other admin call carries the `/v1` segment). NOTE: the
 * backend `AuditLogController` currently exposes only the READ paths
 * (`GET /audit`, `GET /audit/entity/...`) — a POST write endpoint is NOT yet
 * confirmed to exist, so this client trail is best-effort and may 404 until
 * Vikram adds it. This is non-authoritative: the source-of-truth audit rows
 * are written server-side inside the service layer (e.g.
 * PlatformFeeAdminService#update), so a failed client post loses no
 * compliance data — it only affects this convenience/queue trail.
 */

import type { AuditLogEntry, ApiResponse } from '../types/admin.types';

// ============================================
// TYPES
// ============================================

/**
 * Fields the client is responsible for. Server-generated fields
 * (`id`, `adminEmail`, `ipAddress`, `timestamp`) are intentionally omitted.
 */
export type AuditLogInput = Pick<AuditLogEntry, 'adminId' | 'action' | 'entityType' | 'entityId'> &
  Partial<Pick<AuditLogEntry, 'oldValue' | 'newValue' | 'reason'>>;

/**
 * Canonical admin action verbs. Kept as a const object (not an enum) so
 * callers may still pass a free-form string for actions not yet enumerated,
 * while getting autocomplete for the common cases.
 */
export const AuditAction = {
  LOGIN: 'LOGIN',
  LOGOUT: 'LOGOUT',
  SESSION_EXPIRED: 'SESSION_EXPIRED',
  VIEW: 'VIEW',
  CREATE: 'CREATE',
  UPDATE: 'UPDATE',
  DELETE: 'DELETE',
  APPROVE: 'APPROVE',
  REJECT: 'REJECT',
  SUSPEND: 'SUSPEND',
  REINSTATE: 'REINSTATE',
  ESCROW_RELEASE: 'ESCROW_RELEASE',
  ESCROW_HOLD: 'ESCROW_HOLD',
  ESCROW_REFUND: 'ESCROW_REFUND',
  PAYOUT_RETRY: 'PAYOUT_RETRY',
  BUDGET_OVERRIDE: 'BUDGET_OVERRIDE',
  TIER_ADJUST: 'TIER_ADJUST',
  PERMISSION_DENIED: 'PERMISSION_DENIED',
} as const;

export type AuditActionType = (typeof AuditAction)[keyof typeof AuditAction] | string;

// ============================================
// CONFIG
// ============================================

const API_BASE = '/api/v1/admin';
const AUDIT_ENDPOINT = '/audit';
const RETRY_QUEUE_KEY = 'admin_audit_retry_queue';
const MAX_QUEUE_SIZE = 100; // Prevent unbounded localStorage growth

// ============================================
// INTERNAL
// ============================================

/**
 * Serialize an arbitrary value for the `oldValue` / `newValue` columns,
 * which the audit schema types as `string`. Objects are JSON-encoded so the
 * caller can pass structured diffs without stringifying at every call site.
 */
export function serializeAuditValue(value: unknown): string | undefined {
  if (value === undefined || value === null) return undefined;
  if (typeof value === 'string') return value;
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}

/**
 * Retry queue: localStorage-backed FIFO queue for failed audit entries.
 * Prevents audit trail gaps when the backend is temporarily unreachable.
 *
 * Queue entries are timestamped so we can age them out if they sit undelivered
 * for too long (e.g., 7 days). No cryptographic protection — this is a
 * durability mechanism, not a security boundary.
 */

type QueuedEntry = {
  entry: AuditLogInput;
  queuedAt: number; // Unix timestamp (ms)
};

function getRetryQueue(): QueuedEntry[] {
  try {
    const raw = localStorage.getItem(RETRY_QUEUE_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function setRetryQueue(queue: QueuedEntry[]): void {
  try {
    // Enforce max size: drop oldest entries if queue exceeds limit
    const bounded = queue.slice(-MAX_QUEUE_SIZE);
    localStorage.setItem(RETRY_QUEUE_KEY, JSON.stringify(bounded));
  } catch (err) {
    if (import.meta.env?.DEV) {
      console.warn('[auditLogger] failed to persist retry queue:', err);
    }
  }
}

function enqueueFailedEntry(entry: AuditLogInput): void {
  const queue = getRetryQueue();
  queue.push({ entry, queuedAt: Date.now() });
  setRetryQueue(queue);

  if (import.meta.env?.DEV) {
    console.info(`[auditLogger] queued failed entry (queue size: ${queue.length})`);
  }
}

/**
 * Process the retry queue: attempt to deliver all queued entries.
 * Called automatically on app load (via init()) and after successful delivery.
 *
 * Entries older than 7 days are discarded (likely the user was offline for
 * an extended period; keeping them would bloat localStorage).
 */
async function processRetryQueue(): Promise<void> {
  const queue = getRetryQueue();
  if (queue.length === 0) return;

  const now = Date.now();
  const SEVEN_DAYS_MS = 7 * 24 * 60 * 60 * 1000;
  const remaining: QueuedEntry[] = [];

  for (const queued of queue) {
    // Age out old entries
    if (now - queued.queuedAt > SEVEN_DAYS_MS) {
      if (import.meta.env?.DEV) {
        console.warn('[auditLogger] dropping stale queued entry:', queued.entry);
      }
      continue;
    }

    // Attempt delivery (without re-queuing on failure to avoid infinite loop)
    const delivered = await deliverEntry(queued.entry, false);
    if (!delivered) {
      remaining.push(queued);
    }
  }

  setRetryQueue(remaining);

  if (import.meta.env?.DEV && queue.length > remaining.length) {
    console.info(`[auditLogger] processed queue: ${queue.length - remaining.length} delivered, ${remaining.length} remaining`);
  }
}

/**
 * Internal delivery helper. Extracted from logAdminAction so retry logic
 * can call it without re-queuing on failure.
 */
async function deliverEntry(entry: AuditLogInput, enqueueOnFailure: boolean): Promise<boolean> {
  const token = localStorage.getItem('admin_token');

  try {
    const response = await fetch(`${API_BASE}${AUDIT_ENDPOINT}`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(token && { Authorization: `Bearer ${token}` }),
      },
      body: JSON.stringify(entry),
      keepalive: true,
    });

    if (!response.ok) {
      const error: ApiResponse<AuditLogEntry> = await response
        .json()
        .catch(() => ({ success: false, error: 'Audit log request failed' }));
      if (import.meta.env?.DEV) {
        console.warn('[auditLogger] failed to persist entry:', error.error, entry);
      }

      if (enqueueOnFailure) {
        enqueueFailedEntry(entry);
      }
      return false;
    }

    return true;
  } catch (err) {
    if (import.meta.env?.DEV) {
      console.warn('[auditLogger] network error while persisting entry:', err, entry);
    }

    if (enqueueOnFailure) {
      enqueueFailedEntry(entry);
    }
    return false;
  }
}

// ============================================
// PUBLIC API
// ============================================

/**
 * Persist a single audit entry to the backend.
 *
 * Fire-and-forget by design: audit logging must never block or break the
 * admin action it is recording. Failures are swallowed (and surfaced to the
 * console in dev) rather than thrown, and the boolean result lets callers
 * that care about delivery react without a try/catch.
 *
 * NEW (Kavya cycle 4): Failed entries are now queued to localStorage and
 * retried on next app load. Prevents audit trail gaps during temporary
 * backend unavailability.
 */
export async function logAdminAction(entry: AuditLogInput): Promise<boolean> {
  const delivered = await deliverEntry(entry, true);

  // After successful delivery, opportunistically process any queued entries
  if (delivered) {
    processRetryQueue().catch(() => {
      // Swallow retry errors to keep this fire-and-forget
    });
  }

  return delivered;
}

/**
 * Convenience wrapper that builds an entry from loose arguments and serializes
 * structured before/after values. Intended for controller-facing call sites:
 *
 *   await auditAction(admin.id, AuditAction.UPDATE, 'CREATOR', creator.id, {
 *     oldValue: prevTier,
 *     newValue: nextTier,
 *     reason: 'Manual tier bump after quality review',
 *   });
 */
export function auditAction(
  adminId: string,
  action: AuditActionType,
  entityType: string,
  entityId: string,
  options: { oldValue?: unknown; newValue?: unknown; reason?: string } = {}
): Promise<boolean> {
  return logAdminAction({
    adminId,
    action,
    entityType,
    entityId,
    oldValue: serializeAuditValue(options.oldValue),
    newValue: serializeAuditValue(options.newValue),
    reason: options.reason,
  });
}

/**
 * Initialize the audit logger on app load. Call this from the admin app's
 * entry point (e.g., AdminLayout useEffect or main.tsx).
 *
 * Automatically processes any queued entries from previous sessions.
 */
export function initAuditLogger(): void {
  processRetryQueue().catch(() => {
    // Swallow errors — don't let retry queue processing break app startup
  });
}

/**
 * Debugging helper: view the current retry queue state.
 * Useful for verifying queue behavior in dev/test environments.
 */
export function getAuditRetryQueueStatus(): {
  size: number;
  oldestEntry: number | null;
  newestEntry: number | null;
} {
  const queue = getRetryQueue();
  return {
    size: queue.length,
    oldestEntry: queue[0]?.queuedAt ?? null,
    newestEntry: queue[queue.length - 1]?.queuedAt ?? null,
  };
}
