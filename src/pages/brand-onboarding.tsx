import * as React from 'react';
import { useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { ArrowRight, CheckCircle2, Sparkles, Target, Users } from 'lucide-react';

import { OnboardingLayout } from '@/components/brand/onboarding/onboarding-layout';
import {
  AccountSetupStep,
  CompanyDetailsStep,
  initialData,
  type OnboardingData,
} from '@/components/brand/onboarding/onboarding-steps';
import { Button } from '@/components/ui/button';
import { api, ApiError } from '@/lib/api';
import { hasBrandToken } from '@/lib/auth-session';
import { normalizePhone } from '@/lib/phone';

const TOTAL_STEPS = 3;

/**
 * Brand onboarding — reduced from 6 steps to 3.
 *   1. Account            → api.auth.brandRegister
 *   2. Company details    → api.onboarding.saveBrandCompany
 *   3. You're in (tour)   → api.onboarding.completeBrand
 *
 * Deferred (collected just-in-time):
 *   - GSTIN/PAN KYC  → /brand/campaigns/new (api.onboarding.submitBrandKyc)
 *   - Wallet funding → first deal acceptance (api.wallet.topUp)
 *   - Team invites   → /brand/settings  (post-onboarding)
 */
export default function BrandOnboardingPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  // Step 1 collects registration credentials (email/password) -- only relevant to a brand-new
  // signup landing here mid-wizard. A user who arrives already authenticated (e.g. logged into an
  // existing account whose onboarding was never finished) has no reason to re-enter a password,
  // so start them straight at step 2 instead of re-showing the account-creation form.
  const [currentStep, setCurrentStep] = React.useState(() => (hasBrandToken() ? 2 : 1));
  const [data, setData] = React.useState<OnboardingData>(initialData);
  const [isSubmitting, setIsSubmitting] = React.useState(false);
  const [error, setError] = React.useState('');
  // PHONE-0904 Q6 fix — brandRegister's duplicate 409s (PHONE_ALREADY_EXISTS,
  // EMAIL_ALREADY_EXISTS — AuthService.java:123, :139-143, :195-200) used to fall into `error`
  // above as a generic page-level banner rendered on step 2, while both fields live on step 1
  // (onboarding-steps.tsx). Keyed by field so AccountSetupStep can show the right message next
  // to the right input, and the two duplicate reasons stay distinguishable from each other.
  const [fieldErrors, setFieldErrors] = React.useState<{ phone?: string; email?: string }>({});

  const updateData = (updates: Partial<OnboardingData>) => {
    setData((prev) => ({ ...prev, ...updates }));
  };

  const nextStep = () => {
    if (currentStep < TOTAL_STEPS) {
      setCurrentStep((prev) => prev + 1);
      window.scrollTo(0, 0);
    }
  };

  const prevStep = () => {
    if (currentStep > 1) {
      setCurrentStep((prev) => prev - 1);
      window.scrollTo(0, 0);
    }
  };

  const handleCompanySaveAndNext = async () => {
    setIsSubmitting(true);
    setError('');
    setFieldErrors({});
    try {
      if (!hasBrandToken()) {
        await api.auth.brandRegister({
          email: data.email,
          password: data.password,
          firstName: data.firstName,
          lastName: data.lastName,
          companyName: data.companyName,
          industry: data.industry || 'other',
          // F-0395 — 'STARTUP', not '1-5'. `companySize` is the closed STARTUP/SMB/ENTERPRISE
          // union `AdminBrandService.KNOWN_SIZES` enforces; the old fallback was a fourth
          // vocabulary that matched neither the dropdown nor the admin allow-list.
          companySize: data.companySize || 'STARTUP',
          // F-0394 — the real value from the Terms checkbox in AccountSetupStep. This was
          // hardcoded `true`, which made the server's @AssertTrue permanently unfailable and
          // recorded an agreement the user was never shown.
          acceptedTerms: data.acceptTerms,
          // P2/PHONE-0829 — onboarding-steps.tsx (AccountSetupStep) already collects and
          // validates this (Required, /^[6-9]\d{9}$/), but it was never included in the
          // registration payload despite BrandRegisterPayload.phone already existing —
          // every brand's mobile number was collected and silently discarded.
          // PHONE-0904 — send the normalized 10-digit value, not the raw '+91 9876543210'
          // the user may have pasted; onboarding-steps.tsx's onChange no longer strips '+'.
          phone: normalizePhone(data.phone),
        });
      }

      await api.onboarding.saveBrandCompany({
        companyName: data.companyName,
        companySlug: data.companySlug,
        workspaceType: data.workspaceType,
        industry: data.industry,
        companySize: data.companySize,
        websiteUrl: data.websiteUrl,
        description: data.description,
        logoUrl: data.logoUpload?.url,
      });
      nextStep();
    } catch (err) {
      // PHONE-0904 Q6 fix — distinguish the two 409s brandRegister can throw by `code`
      // (src/lib/api.ts:276), not a generic message, and route the user back to step 1 where
      // the field that caused it actually lives, instead of a page-level banner on step 2.
      if (err instanceof ApiError && err.code === 'PHONE_ALREADY_EXISTS') {
        setFieldErrors({ phone: 'This mobile number is already registered' });
        // Step 1 (AccountSetupStep) short-circuits to an "email verified" / OTP-entry screen
        // once data.emailOtpVerified/emailOtpSent is set from the earlier pass through step 1,
        // which would otherwise strand the user on a screen with no editable phone field and no
        // further auto-advance. Resetting these puts step 1 back in its normal editable-form
        // state so the corrected number can actually be resubmitted (re-verifying the email is
        // the cost of that route, but it is not a dead end).
        updateData({ emailOtpSent: false, emailOtpVerified: false });
        setCurrentStep(1);
        window.scrollTo(0, 0);
        return;
      }
      if (err instanceof ApiError && err.code === 'EMAIL_ALREADY_EXISTS') {
        setFieldErrors({ email: 'An account with this email already exists' });
        updateData({ emailOtpSent: false, emailOtpVerified: false });
        setCurrentStep(1);
        window.scrollTo(0, 0);
        return;
      }
      // PHONE-0904 (Defect B follow-up) — Vikram's phone-required-for-brand backend can also
      // return PHONE_REQUIRED (blank phone slipped past the client check below, e.g. a stale
      // form state) or INVALID_PHONE (malformed) from this same brandRegister call. Both used to
      // fall into the generic `error` banner on step 2 even though the phone field lives on step
      // 1 — same bug class as the duplicate-409 fix above, same fix shape.
      if (err instanceof ApiError && err.code === 'PHONE_REQUIRED') {
        setFieldErrors({ phone: 'Mobile number is required' });
        updateData({ emailOtpSent: false, emailOtpVerified: false });
        setCurrentStep(1);
        window.scrollTo(0, 0);
        return;
      }
      if (err instanceof ApiError && err.code === 'INVALID_PHONE') {
        setFieldErrors({ phone: 'Enter a valid 10-digit mobile number' });
        updateData({ emailOtpSent: false, emailOtpVerified: false });
        setCurrentStep(1);
        window.scrollTo(0, 0);
        return;
      }
      setError(err instanceof ApiError ? err.message : 'Could not save company details');
    } finally {
      setIsSubmitting(false);
    }
  };

  /**
   * F-0341 — takes the destination as an argument. Step 3 tells the user to "pick where to go
   * first" and then offers three destinations; every one of them completes onboarding exactly
   * the same way and differs only in where it lands. Hardcoding '/brand/dashboard' here is what
   * left `NextActionCard` with nothing to call, so the cards shipped as inert divs.
   */
  const handleComplete = async (destination = '/brand/dashboard') => {
    setIsSubmitting(true);
    setError('');
    try {
      await api.onboarding.completeBrand();
      localStorage.setItem('brand_onboarding_complete', 'true');
      localStorage.setItem('onboarding_complete', 'true');
      // OB-2: App.tsx's ProtectedRoute caches GET /onboarding/brand/status under
      // queryKey ['brand-onboarding-status', isAuthenticated] with a 5min staleTime.
      // Without invalidating it here, a freshly-onboarded brand hits /brand/dashboard
      // on a still-fresh cached `false` and gets bounced straight back to
      // /brand/onboarding until the cache expires or a hard reload. Partial key match
      // (no isAuthenticated segment) invalidates every variant of this query.
      await queryClient.invalidateQueries({ queryKey: ['brand-onboarding-status'] });
      navigate(destination);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not complete onboarding');
    } finally {
      setIsSubmitting(false);
    }
  };

  const renderStep = () => {
    switch (currentStep) {
      case 1:
        return (
          <AccountSetupStep
            data={data}
            onUpdate={updateData}
            onNext={nextStep}
            serverErrors={fieldErrors}
            onClearServerError={(field) =>
              setFieldErrors((prev) => ({ ...prev, [field]: undefined }))
            }
          />
        );
      case 2:
        return (
          <CompanyDetailsStep
            data={data}
            onUpdate={updateData}
            onNext={handleCompanySaveAndNext}
            onBack={prevStep}
          />
        );
      case 3:
        return (
          <YoureInStep
            firstName={data.firstName}
            onComplete={handleComplete}
            isSubmitting={isSubmitting}
          />
        );
      default:
        return null;
    }
  };

  return (
    <OnboardingLayout currentStep={currentStep} totalSteps={TOTAL_STEPS}>
      {error && (
        <div className="mb-4 rounded-lg border border-destructive-foreground/30 bg-destructive/10 px-4 py-3 text-sm text-destructive-foreground">
          {error}
        </div>
      )}
      {renderStep()}
    </OnboardingLayout>
  );
}

// ---------------------------------------------------------------------------
// Step 3 — terminal "you're in" screen with 3 next-action shortcuts.
// Replaces the old Verification + Team + Trust + Wallet steps. KYC and wallet
// funding are collected just-in-time when the user takes those actions.
// ---------------------------------------------------------------------------

interface YoureInStepProps {
  firstName?: string;
  onComplete: (destination?: string) => void;
  isSubmitting: boolean;
}

/** Exported for test — F-0341 pins that these cards are live controls carrying a destination,
 *  which is not observable from the page without driving the whole wizard past a required form. */
export function YoureInStep({ firstName, onComplete, isSubmitting }: YoureInStepProps) {
  const name = firstName?.trim() || 'there';

  return (
    <div className="space-y-7">
      <div className="space-y-3 text-center">
        <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-success/10">
          <CheckCircle2 className="h-7 w-7 text-success" />
        </div>
        <h1 className="text-2xl font-semibold tracking-tight">
          You're in, {name} <Sparkles className="inline-block h-5 w-5 text-amber-500" />
        </h1>
        <p className="text-sm leading-relaxed text-muted-foreground">
          Your workspace is ready. Add KYC and fund your wallet when you start your first
          campaign — we'll prompt you then. Pick where to go first:
        </p>
      </div>

      <div className="space-y-3">
        <NextActionCard
          icon={Target}
          title="Create your first campaign"
          subtitle="Set a brief, budget and timeline"
          accent="primary"
          disabled={isSubmitting}
          onSelect={() => onComplete('/brand/campaigns/new')}
        />
        <NextActionCard
          icon={Users}
          title="Discover creators"
          subtitle="Browse our verified Indian creator network"
          accent="muted"
          disabled={isSubmitting}
          onSelect={() => onComplete('/brand/discover')}
        />
      </div>

      <Button
        onClick={() => onComplete('/brand/dashboard')}
        disabled={isSubmitting}
        variant="outline"
        className="w-full gap-1.5"
        size="lg"
      >
        {isSubmitting ? 'Setting up…' : 'Take me to the dashboard instead'}
        <ArrowRight className="h-4 w-4" />
      </Button>
    </div>
  );
}

interface NextActionCardProps {
  icon: React.ComponentType<{ className?: string }>;
  title: string;
  subtitle: string;
  accent: 'primary' | 'muted';
  /** F-0341 — required, not optional. These cards sit under the instruction "Pick where to go
   *  first:"; they shipped as plain `<div>`s with no handler, so the one screen that asks the
   *  user to choose a destination had nothing to click. Making this non-optional is what stops
   *  a future call site from silently re-introducing an inert card. */
  onSelect: () => void;
  disabled?: boolean;
}

function NextActionCard({ icon: Icon, title, subtitle, accent, onSelect, disabled }: NextActionCardProps) {
  return (
    <button
      type="button"
      onClick={onSelect}
      disabled={disabled}
      className={
        accent === 'primary'
          ? 'flex w-full items-center gap-3 rounded-lg border border-primary/30 bg-primary/5 p-4 text-left transition-colors hover:bg-primary/10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:pointer-events-none disabled:opacity-60'
          : 'flex w-full items-center gap-3 rounded-lg border border-border bg-card p-4 text-left transition-colors hover:bg-muted/60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:pointer-events-none disabled:opacity-60'
      }
    >
      <div
        className={
          accent === 'primary'
            ? 'flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-primary/15 text-primary'
            : 'flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-muted text-muted-foreground'
        }
      >
        <Icon className="h-5 w-5" />
      </div>
      <div className="min-w-0 flex-1">
        <p className="text-sm font-medium text-foreground">{title}</p>
        <p className="text-xs text-muted-foreground">{subtitle}</p>
      </div>
      <ArrowRight className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden />
    </button>
  );
}
