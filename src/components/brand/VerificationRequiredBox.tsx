import { Link } from 'react-router-dom';
import { ShieldAlert, Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';

/** The brand-workspace verification screen (GSTIN/PAN + docs → submitBrandKyc → PENDING). */
const VERIFY_HREF = '/brand/settings/verification';

interface VerificationRequiredBoxProps {
  /** OWNER/ADMIN see the verify CTA; everyone else sees the "ask an admin" note. */
  canVerify: boolean;
  /** Re-submits the campaign as a DRAFT. The parent makes this edit-aware (update vs create). */
  onSaveDraft: () => void;
  /** True while the draft re-submit is in flight. */
  savingDraft?: boolean;
}

/**
 * Persistent inline block shown when publishing an ACTIVE campaign is refused because the
 * workspace isn't verified (403 WORKSPACE_NOT_VERIFIED). Replaces the old disappearing toast:
 * it stays on screen, explains the gate, reassures that the draft is safe, and offers a way
 * forward — verify, or keep it as a draft. The raw code is shown only as a quiet support ref.
 */
export function VerificationRequiredBox({
  canVerify,
  onSaveDraft,
  savingDraft,
}: VerificationRequiredBoxProps) {
  return (
    <div
      role="alert"
      className="flex gap-3 rounded-xl border border-red-400/50 bg-red-50 p-4 text-red-900 dark:border-red-400/30 dark:bg-red-950/40 dark:text-red-100"
    >
      <ShieldAlert className="mt-0.5 h-5 w-5 flex-none" aria-hidden="true" />
      <div className="flex-1">
        <p className="text-sm font-semibold">Your workspace needs to be verified to publish</p>
        <p className="mt-1 text-sm opacity-90">
          Good news — this campaign is saved as a draft, so nothing is lost. Verify your workspace
          and publish it in one step once you’re approved.
        </p>
        <div className="mt-3 flex flex-wrap items-center gap-2">
          {canVerify ? (
            <Button asChild size="sm">
              <Link to={VERIFY_HREF}>Start verification</Link>
            </Button>
          ) : (
            <span className="text-xs font-medium opacity-80">Ask a workspace admin to verify</span>
          )}
          <Button size="sm" variant="outline" onClick={onSaveDraft} disabled={savingDraft}>
            {savingDraft ? (
              <>
                <Loader2 className="mr-2 h-4 w-4 animate-spin" aria-hidden="true" />
                Saving…
              </>
            ) : (
              'Save as draft instead'
            )}
          </Button>
          <span className="ml-auto font-mono text-[11px] opacity-60">ref: WORKSPACE_NOT_VERIFIED</span>
        </div>
      </div>
    </div>
  );
}
