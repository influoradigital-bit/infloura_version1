/**
 * EmailOtpGate — the send → verify → resend panel that stands between a filled-in signup form
 * and the actual `register` call.
 *
 * Why this exists: the backend has always had `POST /auth/{role}/send-email-otp` +
 * `/verify-email` (AuthController.java:51/57 for brand, :81/:87 for creator, both served by the
 * same `BrandEmailOtpService`), and `AuthService.brandRegister`/`creatorRegister` call
 * `requireVerifiedEmail` whenever `influora.auth.require-email-otp-before-register` is on. But
 * the only UI that ever called those endpoints was the brand onboarding wizard — and only on the
 * branch a user reaches with NO token, which the main `/brand/register` CTA skips. The creator
 * side had no client for them at all. So turning the flag on would have 400'd every signup with
 * nothing in the UI able to satisfy it. This component is the missing half.
 *
 * Rendering is caller-gated on `api.config.public().requireEmailOtp` — when the server isn't
 * enforcing verification we don't add a step to the funnel for nothing.
 */
import * as React from 'react';
import { Info, Loader2, Mail, MailCheck, RefreshCw } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { api, ApiError, isApiLive, type Role } from '@/lib/api';
import { cn } from '@/lib/utils';

const RESEND_COOLDOWN_SECONDS = 30;
const OTP_LENGTH = 6;

/**
 * 6-box OTP input with auto-advance, backspace-to-previous, arrow-key nav and paste support.
 * Lifted verbatim from the brand onboarding wizard so both signup pages and the wizard share one
 * implementation instead of three.
 */
export function OtpInput({
  value,
  onChange,
  length = OTP_LENGTH,
  error,
  disabled,
}: {
  value: string;
  onChange: (val: string) => void;
  length?: number;
  error?: string;
  disabled?: boolean;
}) {
  const inputRefs = React.useRef<(HTMLInputElement | null)[]>([]);

  const handleChange = (idx: number, char: string) => {
    if (char && !/^\d$/.test(char)) return;
    const arr = value.split('');
    arr[idx] = char;
    while (arr.length < length) arr.push('');
    onChange(arr.join('').slice(0, length));
    if (char && idx < length - 1) inputRefs.current[idx + 1]?.focus();
  };

  const handleKeyDown = (idx: number, e: React.KeyboardEvent) => {
    if (e.key === 'Backspace' && !value[idx] && idx > 0) {
      inputRefs.current[idx - 1]?.focus();
      const arr = value.split('');
      arr[idx - 1] = '';
      onChange(arr.join(''));
    }
    if (e.key === 'ArrowLeft' && idx > 0) inputRefs.current[idx - 1]?.focus();
    if (e.key === 'ArrowRight' && idx < length - 1) inputRefs.current[idx + 1]?.focus();
  };

  const handlePaste = (e: React.ClipboardEvent) => {
    e.preventDefault();
    const pasted = e.clipboardData.getData('text').replace(/\D/g, '').slice(0, length);
    onChange(pasted);
    inputRefs.current[Math.min(pasted.length, length - 1)]?.focus();
  };

  return (
    <div>
      <div className="flex items-center justify-center gap-2.5" onPaste={handlePaste}>
        {Array.from({ length }).map((_, idx) => (
          <input
            key={idx}
            ref={(el) => {
              inputRefs.current[idx] = el;
            }}
            type="text"
            inputMode="numeric"
            autoComplete={idx === 0 ? 'one-time-code' : 'off'}
            maxLength={1}
            disabled={disabled}
            value={value[idx] || ''}
            onChange={(e) => handleChange(idx, e.target.value)}
            onKeyDown={(e) => handleKeyDown(idx, e)}
            onFocus={(e) => e.target.select()}
            className={cn(
              'h-12 w-11 rounded-lg border-2 bg-background text-center text-lg font-semibold text-foreground outline-none transition-all',
              'focus:border-primary focus:ring-2 focus:ring-primary/20 disabled:opacity-60',
              error ? 'border-destructive-foreground' : value[idx] ? 'border-primary/50' : 'border-border',
            )}
            autoFocus={idx === 0}
          />
        ))}
      </div>
      {error && <p className="mt-2 text-center text-xs text-destructive-foreground">{error}</p>}
    </div>
  );
}

export interface EmailOtpGateProps {
  /** Address to verify — must be the exact string that will later be sent to `register`. */
  email: string;
  /** Picks the `/auth/brand/*` vs `/auth/creator/*` route pair. */
  role: Role;
  /** Fired once the server confirms `emailVerified`. The caller then runs its register call. */
  onVerified: () => void;
  /** Returns the user to the form so they can correct a typo'd address. */
  onEditEmail: () => void;
}

export function EmailOtpGate({ email, role, onVerified, onEditEmail }: EmailOtpGateProps) {
  const [code, setCode] = React.useState('');
  const [error, setError] = React.useState('');
  const [isSending, setIsSending] = React.useState(false);
  const [isVerifying, setIsVerifying] = React.useState(false);
  const [resendTimer, setResendTimer] = React.useState(RESEND_COOLDOWN_SECONDS);

  const sendOtp = React.useCallback(
    (email_: string) =>
      role === 'creator' ? api.auth.sendCreatorEmailOtp(email_) : api.auth.sendBrandEmailOtp(email_),
    [role],
  );

  const verifyOtp = React.useCallback(
    (email_: string, otp: string) =>
      role === 'creator'
        ? api.auth.verifyCreatorEmail(email_, otp)
        : api.auth.verifyBrandEmail(email_, otp),
    [role],
  );

  // Cooldown tick. Mounting already counts as "just sent" — the caller sends the first code
  // before rendering this panel, so the timer starts hot rather than offering an instant resend.
  React.useEffect(() => {
    if (resendTimer <= 0) return;
    const id = window.setTimeout(() => setResendTimer((t) => t - 1), 1000);
    return () => window.clearTimeout(id);
  }, [resendTimer]);

  const handleResend = async () => {
    if (resendTimer > 0 || isSending) return;
    setIsSending(true);
    setError('');
    try {
      await sendOtp(email);
      setCode('');
      setResendTimer(RESEND_COOLDOWN_SECONDS);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not resend the code');
    } finally {
      setIsSending(false);
    }
  };

  const handleVerify = async () => {
    if (code.length !== OTP_LENGTH) {
      setError('Enter all 6 digits');
      return;
    }
    setIsVerifying(true);
    setError('');
    try {
      const result = await verifyOtp(email, code);
      if (result.emailVerified) {
        onVerified();
      } else {
        // Defensive: the server signals a bad code with a 4xx, not `emailVerified: false`.
        // Handled anyway so a contract change can't silently read as success.
        setError('Verification failed. Please try again.');
      }
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Invalid verification code');
    } finally {
      setIsVerifying(false);
    }
  };

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="mb-2 text-3xl font-bold text-foreground">Verify your email</h1>
        <p className="text-muted-foreground">We sent a 6-digit code to {email}</p>
      </div>

      <div className="flex items-center gap-3 rounded-lg border border-border bg-muted/30 px-4 py-3">
        <div className="flex h-9 w-9 items-center justify-center rounded-full bg-primary/10">
          <Mail className="h-4 w-4 text-primary" />
        </div>
        <div className="min-w-0 flex-1">
          <p className="truncate text-sm font-medium text-foreground">{email}</p>
          <p className="text-xs text-muted-foreground">Check your inbox and spam folder</p>
        </div>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          className="shrink-0 text-xs text-primary"
          onClick={onEditEmail}
        >
          Edit
        </Button>
      </div>

      <div className="flex flex-col items-center gap-4 py-2">
        <OtpInput value={code} onChange={(v) => { setCode(v); setError(''); }} error={error} disabled={isVerifying} />

        {resendTimer > 0 ? (
          <p className="text-xs text-muted-foreground">
            {"Didn't receive it? Resend in "}
            <span className="font-semibold tabular-nums text-foreground">{resendTimer}s</span>
          </p>
        ) : (
          <Button
            type="button"
            variant="ghost"
            size="sm"
            className="gap-1.5 text-xs text-primary"
            onClick={handleResend}
            disabled={isSending}
          >
            {isSending ? <Loader2 className="h-3 w-3 animate-spin" /> : <RefreshCw className="h-3 w-3" />}
            Resend code
          </Button>
        )}
      </div>

      <Button
        type="button"
        onClick={handleVerify}
        disabled={isVerifying || code.length !== OTP_LENGTH}
        className="w-full gap-1.5"
        size="lg"
      >
        {isVerifying ? (
          <>
            <Loader2 className="h-4 w-4 animate-spin" />
            Verifying...
          </>
        ) : (
          <>
            <MailCheck className="h-4 w-4" />
            Verify email
          </>
        )}
      </Button>

      {!isApiLive() && (
        <div className="flex items-center gap-2 rounded-lg border border-border/50 bg-muted/20 px-3 py-2">
          <Info className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />
          <p className="text-xs text-muted-foreground">
            Demo mode: any 6-digit code verifies.
          </p>
        </div>
      )}
    </div>
  );
}

export default EmailOtpGate;
