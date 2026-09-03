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

const CONSENT_TEXT: Record<string, { title: string; body: string; accept: string; decline: string }> = {
  'hi-IN': {
    title: 'Meera से बात करें',
    body: 'Meera आपकी AI मैनेजर है। वह आपके डील्स, पेमेंट, और मेट्रिक्स को समझने में मदद करती है।\n\nMeera आपके प्रोफाइल डेटा, डील हिस्ट्री, पेमेंट स्टेटस और मेट्रिक्स को access करेगी। आप कभी भी अपनी conversations को Settings में जाकर export या delete कर सकते हैं।',
    accept: 'स्वीकार करें',
    decline: 'अभी नहीं',
  },
  'en-IN': {
    title: 'Talk to Meera',
    body: "Meera is your AI manager. She helps you understand your deals, payments, and metrics.\n\nMeera will access your profile data, deal history, payment status, and metrics. You can export or delete your conversations anytime from Settings.",
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
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{text.title}</DialogTitle>
          <DialogDescription className="whitespace-pre-line text-left">{text.body}</DialogDescription>
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
