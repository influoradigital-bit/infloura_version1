import * as React from 'react';
import { Facebook, Instagram } from 'lucide-react';

import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import type { MetaAuthPath } from '@/lib/api';

interface MetaConnectPathDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Called with the creator's answer. The caller starts the OAuth redirect. */
  onChoose: (authPath: MetaAuthPath) => void;
  /** Disables both choices while a redirect is already in flight. */
  busy?: boolean;
  /**
   * Where the creator can change this later. Settings' own card says "this page"; every other
   * surface has to name Settings, because that is where the disconnect/reconnect controls live.
   */
  changeLaterLabel?: string;
}

/**
 * "Is your Instagram linked to a Facebook Page?" — asked BEFORE the OAuth redirect.
 *
 * WHY THIS IS ONE COMPONENT AND NOT THREE COPIES
 * -----------------------------------------------
 * Meta exposes two Instagram configurations and they are not interchangeable:
 * FACEBOOK_LOGIN requires an Instagram professional account already linked to a Facebook Page
 * the creator can administer, while INSTAGRAM_LOGIN requires no Page at all
 * (MetaOAuthService.REQUIRED_SCOPES vs INSTAGRAM_LOGIN_SCOPES — two separate scope
 * vocabularies, not one with a prefix). The choice cannot be changed once Meta's dialog has
 * loaded, so a creator sent down the wrong path dead-ends inside Meta's own UI with nothing
 * explaining why.
 *
 * `api.metaOAuth.authorize()` with no argument defaults to FACEBOOK_LOGIN — the Page-required
 * path. T-IGLOGIN-0820 added this question to the Settings card, but the other two entry points
 * (creator onboarding Step 1, and the Co-pilot nudge) kept calling `authorize()` bare and went
 * on silently sending every creator down the Page-required path, onboarding included — the one
 * screen every single creator passes through. That divergence is the defect this component
 * exists to make impossible: there is now one dialog, and adding a fourth connect surface
 * without the question means not rendering a component that is obviously missing.
 *
 * The answer is a hint, not a fact: creators routinely do not know whether their Instagram is
 * linked to a Page, so a wrong "yes" is recovered on the callback screen rather than
 * dead-ending here.
 */
export function MetaConnectPathDialog({
  open,
  onOpenChange,
  onChoose,
  busy = false,
  changeLaterLabel = 'from Settings',
}: MetaConnectPathDialogProps) {
  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Is your Instagram linked to a Facebook Page?</AlertDialogTitle>
          <AlertDialogDescription>
            Instagram offers two ways to connect. Pick the one that matches your setup — you can
            change it later {changeLaterLabel}.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <div className="space-y-3">
          <button
            type="button"
            onClick={() => onChoose('FACEBOOK_LOGIN')}
            disabled={busy}
            className="w-full rounded-lg border p-3 text-left transition-colors hover:bg-muted disabled:opacity-60"
          >
            <span className="flex items-center gap-2 text-sm font-medium">
              <Facebook className="h-4 w-4 text-[#1877F2]" aria-hidden="true" />
              Yes — I have a Facebook Page
            </span>
            <span className="mt-1 block text-xs text-muted-foreground">
              Connect with Facebook. Needed later for paid partnership ads run from your handle.
              You must be able to manage the Page.
            </span>
          </button>
          <button
            type="button"
            onClick={() => onChoose('INSTAGRAM_LOGIN')}
            disabled={busy}
            className="w-full rounded-lg border p-3 text-left transition-colors hover:bg-muted disabled:opacity-60"
          >
            <span className="flex items-center gap-2 text-sm font-medium">
              <Instagram className="h-4 w-4" aria-hidden="true" />
              No — Instagram only
            </span>
            <span className="mt-1 block text-xs text-muted-foreground">
              Connect with your Instagram login. No Facebook Page needed. Profile, media and
              insights all work; paid partnership ads do not.
            </span>
          </button>
        </div>
        <AlertDialogFooter>
          <AlertDialogCancel disabled={busy}>Cancel</AlertDialogCancel>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
