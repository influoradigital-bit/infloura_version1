import * as React from 'react';
import { Loader2 } from 'lucide-react';

import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';

/**
 * T-MEERA-CREATOR-PHASE-A (A6, SPEC.md §4.4) — DPDP consent gate shown the first time a creator
 * opens Meera. `chat.py` 403s an un-consented CREATOR turn with `{code: "CONSENT_REQUIRED"}`;
 * the caller (creator-copilot.tsx) catches that and renders this. On Accept we POST
 * `/creator/agent-preferences/consent` and let the caller retry the turn.
 */

/**
 * U-6 (RULINGS-U-0917.md R-U2, NISHA-CONSENT-0917.md "Final — Case A (v2)") — a new THIRD
 * paragraph on both languages' `body`, covering pasted briefs. Copied character for character
 * from that file (Kabir's approved wording, Priya's Round 3 ruling ships it as Case A under
 * consent v2 — no delete control exists yet, so it states the fact and promises nothing about
 * erasure). Case B (the "can be deleted" wording) is held for U-7 and v3; do not ship it now.
 * The existing two paragraphs are unchanged.
 *
 * PRIYA-LASTCALL-U1R-U6-0917.md (UF6-1) — U-7's Case B / v3 switch was reverted from here: it
 * overwrote this already-reviewed Wave U build while the whole tree is still uncommitted, and it
 * promised a delete route that does not exist yet. The Case B/v3 edit is saved as
 * `.proof-os/tasks/T-MEERA-CREATOR-PHASE-B/U7-caseB-v3.patch` and is re-applied only in the U-7
 * change itself, after Wave U is committed — never mixed into it again.
 *
 * Kabir's G-2 (KABIR-CONSENT-0917.md "Last call — U-6 words") — a substring check (`toContain`)
 * on the body would still pass if a line were silently ADDED. `CONSENT_TEXT_VERSION` is exported
 * so the test can pin an exact-equality check against its own constants (not read from here) AND
 * pin this version string, so a future wording change forces a version bump to be visible in the
 * same diff instead of drifting quietly.
 */
export const CONSENT_TEXT_VERSION = 'v2';

const CONSENT_TEXT: Record<string, { title: string; body: string; accept: string; decline: string }> = {
  'hi-IN': {
    title: 'Meera से बात करें',
    body: 'Meera आपकी AI मैनेजर है। वह आपके डील्स, पेमेंट, और मेट्रिक्स को समझने में मदद करती है।\n\nMeera आपके प्रोफाइल डेटा, डील हिस्ट्री, पेमेंट स्टेटस और मेट्रिक्स को access करेगी। आप कभी भी अपनी conversations को Settings में जाकर export या delete कर सकते हैं।\n\nजब आप किसी ब्रांड का brief या message पेस्ट करते हैं, तो Meera की AI उसमें लिखे नाम, email, phone number, पता, या बैंक या UPI details समेत पूरा text पढ़ती है। Influora उसकी एक copy save करता है, और conversations delete करने से वो copy delete नहीं होती। पेस्ट करने से पहले वो सब हटा दें जो आप नहीं चाहते कि Meera पढ़े।',
    accept: 'स्वीकार करें',
    decline: 'अभी नहीं',
  },
  'en-IN': {
    title: 'Talk to Meera',
    body: "Meera is your AI manager. She helps you understand your deals, payments, and metrics.\n\nMeera will access your profile data, deal history, payment status, and metrics. You can export or delete your conversations anytime from Settings.\n\nWhen you paste a brand's brief or message, Meera's AI reads all of it, including any names, emails, phone numbers, addresses, or bank or UPI details in it. Influora saves a copy, and deleting your conversations does not delete it. Before you paste, remove anything you don't want Meera to read.",
    accept: 'Accept',
    decline: 'Not now',
  },
};

export interface ConsentScreenProps {
  open: boolean;
  /** BCP-47 code — falls back to en-IN for anything not hi-IN. */
  language?: string;
  onAccept: () => void | Promise<void>;
  onDecline: () => void;
}

export function ConsentScreen({ open, language, onAccept, onDecline }: ConsentScreenProps) {
  const [accepting, setAccepting] = React.useState(false);
  const text = CONSENT_TEXT[language ?? 'en-IN'] ?? CONSENT_TEXT['en-IN'];

  const handleAccept = async () => {
    setAccepting(true);
    try {
      await onAccept();
    } finally {
      setAccepting(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={(next) => !next && onDecline()}>
      {/* G-3 (KABIR-CONSENT-0917.md MEDIUM) — the shared dialog.tsx is fixed-position, vertically
          centred, with no max-height or scroll. The Hindi notice is now roughly twice as tall as
          before U-6, and on a short phone viewport the top/bottom (including Accept/Not now) can
          clip off-screen. Fixed LOCALLY here, not in the shared component: a max-height capped to
          the viewport plus its own scrollbar. jsdom cannot prove real layout/scroll/clipping —
          this is pinned only as "the class is present", not as a visual proof. */}
      <DialogContent className="max-h-[calc(100dvh-2rem)] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{text.title}</DialogTitle>
          <DialogDescription data-testid="consent-body" className="whitespace-pre-line text-left">
            {text.body}
          </DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button variant="ghost" onClick={onDecline} disabled={accepting}>
            {text.decline}
          </Button>
          <Button onClick={handleAccept} disabled={accepting}>
            {accepting ? <Loader2 className="h-4 w-4 mr-2 animate-spin" /> : null}
            {text.accept}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

export default ConsentScreen;
