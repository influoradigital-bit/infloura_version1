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
 * workspace isn't verified (403 WORKSPACE_NOT_VERIFIED). The create/update call throws before
 * anything is persisted, so nothing has actually been saved yet at this point — the box makes
 * that explicit and leads with "Save as draft instead" (an update/create re-submit as DRAFT) as
 * the unmissable way to not lose the work, alongside "Start verification". Replaces the old
 * disappearing toast: it stays on screen instead of vanishing. The raw code is shown only as a
 * quiet support ref.
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
          This campaign hasn’t been saved yet — publishing needs a verified workspace. Save it as
          a draft now so your work isn’t lost, then verify to publish it.
        </p>
        <div className="mt-3 flex flex-wrap items-center gap-2">
          <Button size="sm" onClick={onSaveDraft} disabled={savingDraft}>
            {savingDraft ? (
              <>
                <Loader2 className="mr-2 h-4 w-4 animate-spin" aria-hidden="true" />
                Saving…
              </>
            ) : (
              'Save as draft instead'
            )}
          </Button>
          {canVerify ? (
            <Button asChild size="sm" variant="outline">
              <Link to={VERIFY_HREF}>Start verification</Link>
            </Button>
          ) : (
            <span className="text-xs font-medium opacity-80">Ask a workspace admin to verify</span>
          )}
          <span className="ml-auto font-mono text-[11px] opacity-60">ref: WORKSPACE_NOT_VERIFIED</span>
        </div>
      </div>
    </div>
  );
}
