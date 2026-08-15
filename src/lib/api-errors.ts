import { ApiError } from '@/lib/api';

/**
 * True when `err` is the backend's 403 raised when a non-VERIFIED workspace tries to
 * publish (or resume) an ACTIVE campaign — `CampaignValidator.validateStatusForWorkspace`
 * → code `WORKSPACE_NOT_VERIFIED`. Draft create/save never raises it (only ACTIVE is
 * gated), so a `true` here always means "the work is safe as a draft, publishing is what
 * was refused." One predicate so every campaign surface classifies this identically.
 */
export function isWorkspaceNotVerified(err: unknown): boolean {
  return err instanceof ApiError && err.code === 'WORKSPACE_NOT_VERIFIED';
}

/**
 * True when `err` is the backend's 409 raised when a PATCH to an ACTIVE campaign carries any
 * non-status field — `CampaignValidator.ensureEditable(status, statusOnlyPatch)` → code
 * `CAMPAIGN_ACTIVE_NOT_EDITABLE`. A status-only body (pause/resume/cancel/complete) is exempted
 * server-side and never raises this.
 *
 * The Edit control itself is hidden/disabled for ACTIVE campaigns everywhere it's rendered
 * (campaigns-list.tsx, brand-campaign-detail.tsx), so this is reachable only via a stale edit
 * link, a typed-in URL, or a race where the campaign went ACTIVE after the edit link rendered —
 * campaign-form.tsx and brand-new-hype-campaign.tsx both check this in their submit handler so
 * that rare path still surfaces a clear, actionable message instead of a generic error toast.
 */
export function isCampaignActiveNotEditable(err: unknown): boolean {
  return err instanceof ApiError && err.code === 'CAMPAIGN_ACTIVE_NOT_EDITABLE';
}
