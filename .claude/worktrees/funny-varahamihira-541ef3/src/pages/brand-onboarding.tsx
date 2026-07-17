import * as React from 'react';
import { useNavigate } from 'react-router-dom';
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

const TOTAL_STEPS = 3;

/**
 * Brand onboarding — reduced from 6 steps to 3.
 *   1. Account            → api.auth.brandRegister
 *   2. Company details    → api.onboarding.saveBrandCompany
 *   3. You're in (tour)   → api.onboarding.completeBrand
 *
 * Deferred (collected just-in-time):
 *   - GSTIN/PAN KYC  → /brand/campaigns/new (api.onboarding.submitBrandKyc)
 *   - Wallet funding → first deal acceptance (api.wallet.recharge)
 *   - Team invites   → /brand/settings  (post-onboarding)
 */
export default function BrandOnboardingPage() {
  const navigate = useNavigate();
  const [currentStep, setCurrentStep] = React.useState(1);
  const [data, setData] = React.useState<OnboardingData>(initialData);
  const [isSubmitting, setIsSubmitting] = React.useState(false);
  const [error, setError] = React.useState('');

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
    try {
      if (!hasBrandToken()) {
        await api.auth.brandRegister({
          email: data.email,
          password: data.password,
          firstName: data.firstName,
          lastName: data.lastName,
          companyName: data.companyName,
          industry: data.industry || 'other',
          companySize: data.companySize || '1-5',
          acceptedTerms: true,
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
      setError(err instanceof ApiError ? err.message : 'Could not save company details');
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleComplete = async () => {
    setIsSubmitting(true);
    setError('');
    try {
      await api.onboarding.completeBrand();
      localStorage.setItem('brand_onboarding_complete', 'true');
      localStorage.setItem('onboarding_complete', 'true');
      navigate('/brand/dashboard');
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not complete onboarding');
    } finally {
      setIsSubmitting(false);
    }
  };

  const renderStep = () => {
    switch (currentStep) {
      case 1:
        return <AccountSetupStep data={data} onUpdate={updateData} onNext={nextStep} />;
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
        <div className="mb-4 rounded-lg border border-destructive/30 bg-destructive/10 px-4 py-3 text-sm text-destructive">
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
  onComplete: () => void;
  isSubmitting: boolean;
}

function YoureInStep({ firstName, onComplete, isSubmitting }: YoureInStepProps) {
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
        />
        <NextActionCard
          icon={Users}
          title="Discover creators"
          subtitle="Browse our verified Indian creator network"
          accent="muted"
        />
      </div>

      <Button
        onClick={onComplete}
        disabled={isSubmitting}
        className="w-full gap-1.5"
        size="lg"
      >
        {isSubmitting ? 'Setting up…' : 'Go to dashboard'}
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
}

function NextActionCard({ icon: Icon, title, subtitle, accent }: NextActionCardProps) {
  return (
    <div
      className={
        accent === 'primary'
          ? 'flex items-center gap-3 rounded-lg border border-primary/30 bg-primary/5 p-4'
          : 'flex items-center gap-3 rounded-lg border border-border bg-card p-4'
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
    </div>
  );
}
