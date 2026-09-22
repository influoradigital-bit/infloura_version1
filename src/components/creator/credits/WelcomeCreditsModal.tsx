import * as React from 'react';
import { Sparkles } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import type { CreatorCreditBalance } from '@/lib/api';
import { creditsCopyWithCta } from '@/lib/copy/creator-credits';

const SEEN_STORAGE_KEY = 'creator-credits:welcome-seen-at';

/** try/catch around every access — F8 requirement, and `localStorage` can throw (private mode,
 *  cleared/blocked site data) or simply be unavailable server-side. */
function hasSeenWelcome(grantedAt: string): boolean {
  try {
    return window.localStorage.getItem(SEEN_STORAGE_KEY) === grantedAt;
  } catch {
    return false;
  }
}

function markWelcomeSeen(grantedAt: string): void {
  try {
    window.localStorage.setItem(SEEN_STORAGE_KEY, grantedAt);
  } catch {
    // Non-fatal — worst case the modal shows again next visit.
  }
}

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md §9.3, F8) — shown once per `welcome.grantedAt` (SPEC.md §9.1
 * `welcome` copy key). The "seen" marker is the `grantedAt` timestamp itself, not a bare boolean:
 * a boolean would never show the modal again for a creator whose account somehow gets re-granted
 * (a different `grantedAt`), while comparing the exact timestamp means a genuinely new grant is
 * treated as new.
 */
export interface WelcomeCreditsModalProps {
  language?: string;
  balance: CreatorCreditBalance | null;
  /** "Start chatting" — the caller decides what that means (focus the composer, close a sheet…). */
  onStartChatting?: () => void;
}

export function WelcomeCreditsModal({ language, balance, onStartChatting }: WelcomeCreditsModalProps) {
  const grantedAt = balance?.enabled ? (balance.welcome?.grantedAt ?? null) : null;
  const [open, setOpen] = React.useState(false);

  React.useEffect(() => {
    if (grantedAt && !hasSeenWelcome(grantedAt)) {
      setOpen(true);
    }
  }, [grantedAt]);

  if (!grantedAt) return null;

  const close = () => {
    markWelcomeSeen(grantedAt);
    setOpen(false);
  };

  const { message, cta } = creditsCopyWithCta('welcome', language);

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) close();
      }}
    >
      <DialogContent data-testid="welcome-credits-modal" className="sm:max-w-sm">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Sparkles className="h-5 w-5 text-primary" aria-hidden="true" />
            {language?.toLowerCase().startsWith('hi') ? 'Meera में आपका स्वागत है' : 'Welcome to Meera'}
          </DialogTitle>
        </DialogHeader>
        <p className="whitespace-pre-line text-sm text-muted-foreground">{message}</p>
        <DialogFooter>
          <Button
            type="button"
            className="h-11 min-h-11 w-full bg-primary text-primary-foreground hover:bg-primary/90 sm:w-auto"
            onClick={() => {
              close();
              onStartChatting?.();
            }}
          >
            {cta}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

export default WelcomeCreditsModal;
