import * as React from 'react';
import { useNavigate, Link, useSearchParams } from 'react-router-dom';
import { useAuthStore } from '@/lib/store';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Checkbox } from '@/components/ui/checkbox';
import { Eye, EyeOff, ArrowRight, Loader2 } from 'lucide-react';
import { createMockCreatorUser } from '@/lib/mock-user';
import { buildCreatorUser } from '@/lib/creator-identity';
import { AuthLoginShell } from '@/components/shared/auth-login-shell';
import { EmailOtpGate } from '@/components/shared/email-otp-gate';
import { api, ApiError, isApiLive } from '@/lib/api';
// PHONE-0906 - the one shared Indian-mobile rule (src/lib/phone.ts). Never re-derive the
// 10-digit regex here: the client must not be stricter than IndianPhoneUtils, or a pasted
// '+91 98765 43210' gets blocked on a number the server would have accepted.
import { normalizePhone, isValidPhone, filterPhoneInput } from '@/lib/phone';

function FieldError({ message }: { message?: string }) {
  if (!message) return null;
  return <p className="mt-1 text-xs text-destructive-foreground">{message}</p>;
}

export default function CreatorRegisterPage() {
  const navigate = useNavigate();
  const { login } = useAuthStore();
  // Q5.5 (T-CREATORCONNECT-0902, Medium) — the invite email's signup_url
  // (AdminCreatorConnectionService#sendJoinInvitationEmail) carries
  // ?ref=influora-invite&handle={igUsername}&invite_token={signed token}. `handle` is a
  // human-readable hint only (never trusted as a claim — see InviteTokenService's javadoc and
  // Q5.4's spoof class), so it is read here purely to show the invited creator which handle
  // they're joining as; `inviteToken` is the signed, single-use token that actually gets
  // forwarded to POST /auth/creator/register and consumed server-side by
  // RegistrationService#consumeInviteToken. `ref` gates the banner so an unrelated `handle`
  // query param can never make this page claim an invite that wasn't issued.
  const [searchParams] = useSearchParams();
  const inviteToken = searchParams.get('invite_token') || undefined;
  const inviteHandle = searchParams.get('handle') || undefined;
  const isInviteFlow = searchParams.get('ref') === 'influora-invite' && !!inviteToken;
  const [isLoading, setIsLoading] = React.useState(false);
  const [showPassword, setShowPassword] = React.useState(false);
  const [showConfirmPassword, setShowConfirmPassword] = React.useState(false);
  const [name, setName] = React.useState('');
  const [email, setEmail] = React.useState('');
  // PHONE-0906 - required at creator signup (previously captured, optionally, one screen later
  // at onboarding step 2). Holds the RAW input; normalizePhone() runs at validate/submit time.
  const [phone, setPhone] = React.useState('');
  const [password, setPassword] = React.useState('');
  const [confirmPassword, setConfirmPassword] = React.useState('');
  const [acceptTerms, setAcceptTerms] = React.useState(false);
  const [errors, setErrors] = React.useState<Record<string, string>>({});
  // Email-OTP gate. `requireEmailOtp` mirrors the server flag that makes
  // AuthService.creatorRegister reject unverified emails; `showOtp` is true only while the user
  // is on the verification panel. Defaults to false so the form renders immediately and, if the
  // config read is slow or fails, signup still works (the server would reject with a readable
  // EMAIL_NOT_VERIFIED rather than the page hanging).
  const [requireEmailOtp, setRequireEmailOtp] = React.useState(false);
  const [showOtp, setShowOtp] = React.useState(false);
  // PHONE-0906 - the email that has ALREADY cleared the OTP gate in this session. A phone
  // rejection (PHONE_ALREADY_EXISTS / INVALID_PHONE) is only knowable server-side, i.e. after the
  // code was verified, and drops the user back to this form; without this, every retry sent a
  // fresh OTP and walked them into BrandEmailOtpService's per-email hourly RATE_LIMITED. The
  // server's own requireVerifiedEmail() reads the latest challenge for the address, which stays
  // verified - so skipping the second round-trip is safe. A genuinely different email no longer
  // matches this value and is therefore still forced through the gate.
  const [verifiedEmail, setVerifiedEmail] = React.useState<string | null>(null);

  React.useEffect(() => {
    let cancelled = false;
    api.config.public().then((cfg) => {
      if (!cancelled) setRequireEmailOtp(cfg.requireEmailOtp);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  const validate = () => {
    const errs: Record<string, string> = {};
    if (!name.trim()) errs.name = 'Your name is required';
    if (!email.trim()) {
      errs.email = 'Email address is required';
    } else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
      errs.email = 'Please enter a valid email address';
    }
    // PHONE-0906 - validated here, BEFORE the OTP is sent, so a typo costs no verification
    // code. Duplicate-number 409s are still only knowable server-side; those are mapped onto this
    // same field in submitRegistration's catch.
    const normalizedPhone = normalizePhone(phone);
    if (!normalizedPhone) {
      errs.phone = 'Mobile number is required';
    } else if (!isValidPhone(normalizedPhone)) {
      errs.phone = 'Enter a valid 10-digit mobile number';
    }
    if (!password) {
      errs.password = 'Password is required';
    } else if (password.length < 8) {
      errs.password = 'Password must be at least 8 characters';
    }
    if (!confirmPassword) {
      errs.confirmPassword = 'Please confirm your password';
    } else if (password !== confirmPassword) {
      errs.confirmPassword = 'Passwords do not match';
    }
    if (!acceptTerms) errs.acceptTerms = 'You must agree to the Terms of Service to continue';
    setErrors(errs);
    return Object.keys(errs).length === 0;
  };

  /**
   * The actual account creation. Split out of `handleRegister` so it can be invoked either
   * straight from the form (OTP off) or from the OTP gate's `onVerified` (OTP on) — the payload
   * is identical either way; only the moment it fires changes.
   */
  const submitRegistration = async () => {
    setIsLoading(true);
    setErrors({});
    try {
      // api.auth.creatorRegister (src/lib/api.ts) calls POST /auth/creator/register in live
      // mode — verified against AuthController.creatorRegister / CreatorRegisterRequest — and
      // only falls back to a mock token (behind assertMockAuthAllowed's fail-closed guard,
      // Kabir A3) when VITE_API_MODE !== 'live', mirroring how creator-login.tsx now calls
      // api.auth.creatorLogin instead of minting a hardcoded token.
      // Q5.5 — `inviteToken` is intentionally kept off `CreatorRegisterPayload`'s declared
      // shape (src/lib/api.ts) so this work package didn't have to touch a file outside its
      // edit scope; assigning through an untyped `payload` const (rather than passing an
      // object literal straight into creatorRegister) means TS's excess-property check never
      // fires here, while `api.auth.creatorRegister` still JSON-serializes `payload` as-is —
      // `body: payload` at src/lib/api.ts's creatorRegister — so the field reaches the wire
      // exactly the same as every typed field.
      const payload = {
        email,
        password,
        displayName: name.trim(),
        acceptedTerms: acceptTerms,
        inviteToken,
        // PHONE-0906 - send the normalized 10 digits, not the raw '+91 98765 43210' the input
        // holds (the same rule brand-onboarding.tsx follows).
        phone: normalizePhone(phone),
      };
      const result = await api.auth.creatorRegister(payload);
      api.auth.setToken('creator', result.token);

      // CR-06 — populated in BOTH modes. Same defect as creator-login.tsx: a
      // live registration left the auth store empty, so the shell rendered its
      // demo fallbacks ('Creator Account' / '@priya_sharma') for a brand-new
      // creator who had just typed their own name into this form.
      login(
        isApiLive()
          ? buildCreatorUser({
              id: result.userId,
              email: result.email ?? email,
              displayName: result.displayName ?? name.trim(),
            })
          : createMockCreatorUser({ id: 'creator-new', displayName: name.trim() || 'New Creator', email }),
      );

      navigate(result.onboardingComplete ? '/creator/dashboard' : '/creator/onboarding');
    } catch (err) {
      // Drop back to the form so the message is visible next to the fields it refers to.
      setShowOtp(false);
      // PHONE-0906 - branch on the machine-readable `code`, never the bare HTTP status: this
      // endpoint also 409s for EMAIL_ALREADY_EXISTS, and mapping by status alone would label a
      // taken email as a taken mobile. Same convention as brand-onboarding.tsx and
      // creator-settings.tsx.
      if (err instanceof ApiError && err.code === 'PHONE_ALREADY_EXISTS') {
        // Worth knowing when reading a support ticket: users.phone_number is UNIQUE across BOTH
        // user types, so this also fires for a number already on a brand account, and there is
        // no self-serve way out (wiki/decisions/phone-0904-cto-review.md D4) - hence the pointer
        // to support rather than a bare 'already registered'.
        setErrors({
          phone: 'This mobile number is already registered. Sign in instead, or contact support.',
        });
        return;
      }
      if (err instanceof ApiError && err.code === 'PHONE_REQUIRED') {
        setErrors({ phone: 'Mobile number is required' });
        return;
      }
      if (err instanceof ApiError && err.code === 'INVALID_PHONE') {
        setErrors({ phone: 'Enter a valid 10-digit mobile number' });
        return;
      }
      const message = err instanceof ApiError ? err.message : 'Registration failed. Please try again.';
      setErrors({ form: message });
    } finally {
      setIsLoading(false);
    }
  };

  const handleRegister = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!validate()) return;

    // Server isn't enforcing verification — don't add a step to the funnel for nothing.
    // PHONE-0906 - also skip when THIS email already cleared the gate earlier in the session
    // (see `verifiedEmail`): the only way back to this form after verifying is a server-side
    // rejection, and re-sending would burn the per-email hourly OTP budget for nothing.
    if (!requireEmailOtp || verifiedEmail === email.trim().toLowerCase()) {
      await submitRegistration();
      return;
    }

    // Send the first code here rather than inside the gate, so a delivery failure (MSG91
    // unconfigured → 503 EMAIL_DELIVERY_FAILED) surfaces on the form the user is already
    // looking at instead of stranding them on an empty OTP panel.
    setIsLoading(true);
    setErrors({});
    try {
      await api.auth.sendCreatorEmailOtp(email);
      setShowOtp(true);
    } catch (err) {
      const message =
        err instanceof ApiError ? err.message : 'Could not send the verification code. Try again.';
      setErrors({ form: message });
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <AuthLoginShell
      accent="creator"
      heroTitle="Turn collaborations into income"
      heroSubtitle="Manage deals, submit deliverables, and get paid — with a workspace built for Indian creators."
      heroBullets={[
        'Verified brand partnerships',
        'Transparent, protected payouts',
        'Deal room in one place',
      ]}
    >
      {showOtp ? (
        <EmailOtpGate
          email={email}
          role="creator"
          onVerified={() => {
            setVerifiedEmail(email.trim().toLowerCase());
            return submitRegistration();
          }}
          onEditEmail={() => setShowOtp(false)}
        />
      ) : (
      <>
      <div className="mb-8">
        <h1 className="text-3xl font-bold text-foreground mb-2">Create your account</h1>
        <p className="text-muted-foreground">Start earning from brand collaborations</p>
      </div>

      {isInviteFlow && (
        <div className="mb-6 rounded-lg border border-primary/30 bg-primary/5 p-4">
          <p className="text-sm text-foreground">
            {inviteHandle
              ? `You were invited as @${inviteHandle}. Finish creating your account to connect this invite.`
              : 'You were invited to Influora. Finish creating your account to connect this invite.'}
          </p>
        </div>
      )}

      {errors.form && (
        <div className="mb-6 rounded-lg border border-destructive-foreground/30 bg-destructive/10 p-4">
          <p className="text-sm text-destructive-foreground">{errors.form}</p>
        </div>
      )}

      <form onSubmit={handleRegister} className="space-y-4" noValidate>
        <div>
          <Label htmlFor="name" className="mb-2">
            Your Name
          </Label>
          <Input
            id="name"
            value={name}
            onChange={(e) => {
              setName(e.target.value);
              if (errors.name) setErrors((prev) => ({ ...prev, name: '' }));
            }}
            placeholder="Enter your full name"
            className="h-auto py-3 bg-background/60"
          />
          <FieldError message={errors.name} />
        </div>

        <div>
          <Label htmlFor="email" className="mb-2">
            Email Address
          </Label>
          <Input
            id="email"
            type="email"
            value={email}
            onChange={(e) => {
              setEmail(e.target.value);
              if (errors.email) setErrors((prev) => ({ ...prev, email: '' }));
            }}
            placeholder="you@example.com"
            className="h-auto py-3 bg-background/60"
          />
          <FieldError message={errors.email} />
        </div>

        <div>
          <Label htmlFor="phone" className="mb-2">
            Mobile Number
          </Label>
          <div className="flex gap-2">
            <div className="flex items-center rounded-md border border-input bg-muted px-3 py-3 text-sm text-muted-foreground">
              +91
            </div>
            <Input
              id="phone"
              inputMode="tel"
              value={phone}
              // Keep digits, spaces and '+' on keystroke so a pasted '+91 98765 43210' is not
              // mangled mid-typing; normalizePhone() strips them at validate/submit time.
              onChange={(e) => {
                setPhone(filterPhoneInput(e.target.value));
                if (errors.phone) setErrors((prev) => ({ ...prev, phone: '' }));
              }}
              placeholder="98765 43210"
              maxLength={17}
              className="h-auto flex-1 py-3 bg-background/60"
            />
          </div>
          <FieldError message={errors.phone} />
        </div>

        <div>
          <Label htmlFor="password" className="mb-2">
            Password
          </Label>
          <div className="relative">
            <Input
              id="password"
              type={showPassword ? 'text' : 'password'}
              value={password}
              onChange={(e) => {
                setPassword(e.target.value);
                if (errors.password) setErrors((prev) => ({ ...prev, password: '' }));
              }}
              placeholder="Create a password (min 8 characters)"
              className="h-auto py-3 pr-12 bg-background/60"
            />
            <button
              type="button"
              onClick={() => setShowPassword(!showPassword)}
              aria-label={showPassword ? 'Hide password' : 'Show password'}
              className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
            >
              {showPassword ? <EyeOff className="w-5 h-5" /> : <Eye className="w-5 h-5" />}
            </button>
          </div>
          <FieldError message={errors.password} />
        </div>

        <div>
          <Label htmlFor="confirm-password" className="mb-2">
            Confirm Password
          </Label>
          <div className="relative">
            <Input
              id="confirm-password"
              type={showConfirmPassword ? 'text' : 'password'}
              value={confirmPassword}
              onChange={(e) => {
                setConfirmPassword(e.target.value);
                if (errors.confirmPassword) setErrors((prev) => ({ ...prev, confirmPassword: '' }));
              }}
              placeholder="Confirm your password"
              className="h-auto py-3 pr-12 bg-background/60"
            />
            <button
              type="button"
              onClick={() => setShowConfirmPassword(!showConfirmPassword)}
              aria-label={showConfirmPassword ? 'Hide confirm password' : 'Show confirm password'}
              className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
            >
              {showConfirmPassword ? <EyeOff className="w-5 h-5" /> : <Eye className="w-5 h-5" />}
            </button>
          </div>
          <FieldError message={errors.confirmPassword} />
        </div>

        <div className="flex items-start gap-2 pt-1">
          <Checkbox
            id="terms"
            checked={acceptTerms}
            onCheckedChange={(checked) => {
              setAcceptTerms(!!checked);
              if (errors.acceptTerms) setErrors((prev) => ({ ...prev, acceptTerms: '' }));
            }}
          />
          <label htmlFor="terms" className="cursor-pointer text-xs leading-tight text-muted-foreground">
            {/* F-0293 — a same-document navigation here would unmount this form and discard
                name/email/password/confirmPassword, all live useState. target="_blank" opens
                Terms/Privacy in a new tab so following the link cannot lose the in-progress
                registration. */}
            I agree to the <a href="/terms" target="_blank" rel="noopener noreferrer" className="text-primary hover:underline">Terms of Service</a> and{' '}
            <a href="/privacy" target="_blank" rel="noopener noreferrer" className="text-primary hover:underline">Privacy Policy</a>
          </label>
        </div>
        <FieldError message={errors.acceptTerms} />

        <Button type="submit" disabled={isLoading} className="w-full font-semibold py-3 h-auto mt-6 gap-2">
          {isLoading ? (
            <>
              <Loader2 className="w-4 h-4 animate-spin" />
              Creating account...
            </>
          ) : (
            <>
              Create Account
              <ArrowRight className="w-4 h-4" />
            </>
          )}
        </Button>
      </form>

      <div className="mt-6 text-center text-sm text-muted-foreground">
        Already have an account?{' '}
        <Link to="/creator/login" className="text-primary hover:underline font-medium">
          Sign in
        </Link>
      </div>

      <div className="text-center mt-4">
        <Link to="/brand/register" className="text-sm text-muted-foreground hover:text-primary">
          Are you a brand? Register here
        </Link>
      </div>
      </>
      )}
    </AuthLoginShell>
  );
}
