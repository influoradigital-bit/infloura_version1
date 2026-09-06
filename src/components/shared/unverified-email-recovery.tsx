/**
 * UnverifiedEmailRecovery — the way out of a `EMAIL_NOT_VERIFIED` 403 at login.
 *
 * Why this exists (F-0601): `AuthService.creatorLogin`/`brandLogin` reject an account whose
 * `users.status` is still `PENDING_VERIFICATION` (AuthService.java:254/:412/:495), and
 * `User.newCreator`/`newBrandOwner` stamp exactly that status on every signup. When
 * `require-email-otp-before-register` is off — its default, and what production has always run —
 * the register pages skip the OTP gate entirely, so nothing ever verifies the address. The
 * account therefore works exactly once (register returns tokens) and then 403s forever, with no
 * control anywhere in the UI able to clear it. This panel is that control: it re-sends a code to
 * the address the user just tried to sign in with, and on success the server promotes the account
 * to ACTIVE (BrandEmailOtpService#verifyOtp) so the retried login goes through.
 *
 * Deliberately shared by both login pages rather than copied — the creator and brand paths differ
 * only in which `/auth/{role}/*` pair `EmailOtpGate` calls, which `role` already selects.
 */
import * as React from 'react';
import { MailWarning, Loader2, ArrowLeft } from 'lucide-react';

import { EmailOtpGate } from '@/components/shared/email-otp-gate';
import { Button } from '@/components/ui/button';
import { api, ApiError, type Role } from '@/lib/api';

export interface UnverifiedEmailRecoveryProps {
  /** The address the rejected sign-in was attempted with. */
  email: string;
  /** Picks the `/auth/brand/*` vs `/auth/creator/*` route pair. */
  role: Role;
  /**
   * Fired once the server confirms the address. The caller re-runs its login call — awaited, so a
   * failed retry can render its own error before this panel stops reporting itself busy.
   */
  onVerified: () => void | Promise<void>;
  /** Returns the user to the sign-in form. */
  onCancel: () => void;
}

export function UnverifiedEmailRecovery({
  email,
  role,
  onVerified,
  onCancel,
}: UnverifiedEmailRecoveryProps) {
  const [stage, setStage] = React.useState<'prompt' | 'otp'>('prompt');
  const [isSending, setIsSending] = React.useState(false);
  const [error, setError] = React.useState('');

  const handleSend = async () => {
    setIsSending(true);
    setError('');
    try {
      // The server suppresses delivery only for an ALREADY-verified address, so this reaches the
      // inbox for exactly the accounts that are stuck. A misconfigured mailer surfaces here as a
      // 503 EMAIL_DELIVERY_FAILED rather than stranding the user on an empty OTP panel.
      await (role === 'creator'
        ? api.auth.sendCreatorEmailOtp(email)
        : api.auth.sendBrandEmailOtp(email));
      setStage('otp');
    } catch (err) {
      setError(
        err instanceof ApiError ? err.message : 'Could not send the verification code. Try again.',
      );
    } finally {
      setIsSending(false);
    }
  };

  if (stage === 'otp') {
    return (
      <EmailOtpGate
        email={email}
        role={role}
        onVerified={onVerified}
        // "Edit" here means "I don't want to do this now" — there is no address field to go back
        // and correct, the address came from the sign-in attempt itself.
        onEditEmail={onCancel}
      />
    );
  }

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="mb-2 text-3xl font-bold text-foreground">Verify your email</h1>
        <p className="text-muted-foreground">
          Your account was created but the email address was never confirmed, so sign-in is
          blocked. Send yourself a code to finish setting it up.
        </p>
      </div>

      <div className="flex items-center gap-3 rounded-lg border border-border bg-muted/30 px-4 py-3">
        <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-primary/10">
          <MailWarning className="h-4 w-4 text-primary" />
        </div>
        <div className="min-w-0 flex-1">
          <p className="truncate text-sm font-medium text-foreground">{email}</p>
          <p className="text-xs text-muted-foreground">We'll send a 6-digit code to this address</p>
        </div>
      </div>

      {error && (
        <div className="rounded-lg border border-destructive-foreground/30 bg-destructive/10 p-4">
          <p className="text-sm text-destructive-foreground">{error}</p>
        </div>
      )}

      <Button
        type="button"
        onClick={handleSend}
        disabled={isSending}
        className="h-auto w-full gap-2 py-3 font-semibold"
      >
        {isSending ? (
          <>
            <Loader2 className="h-4 w-4 animate-spin" />
            Sending code...
          </>
        ) : (
          'Send verification code'
        )}
      </Button>

      <Button
        type="button"
        variant="ghost"
        onClick={onCancel}
        className="gap-2 text-sm text-muted-foreground"
      >
        <ArrowLeft className="h-4 w-4" />
        Back to sign in
      </Button>
    </div>
  );
}
