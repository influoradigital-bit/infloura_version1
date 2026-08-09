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
