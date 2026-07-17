import * as React from 'react';
import { Link } from 'react-router-dom';
import { motion, AnimatePresence } from 'framer-motion';
import { CheckCircle2 } from 'lucide-react';
import { cn } from '@/lib/utils';

interface OnboardingLayoutProps {
  children: React.ReactNode;
  currentStep: number;
  totalSteps: number;
}

/**
 * 3-step onboarding (was 6). KYC (GSTIN/PAN) is deferred to first campaign
 * launch; team invites + wallet funding are deferred to first deal acceptance.
 * Backend equivalent endpoints:
 *   - api.auth.brandRegister  →  step 1
 *   - api.onboarding.saveBrandCompany  →  step 2
 *   - api.onboarding.completeBrand  →  step 3
 *   - api.onboarding.submitBrandKyc  →  deferred (called from /campaigns/new)
 */
const steps = [
  { id: 1, name: 'Account', description: 'Create your login' },
  { id: 2, name: 'Company', description: 'Brand details & logo' },
  { id: 3, name: "You're in", description: 'Quick tour' },
];

export function OnboardingLayout({
  children,
  currentStep,
  totalSteps,
}: OnboardingLayoutProps) {
  const progress = (currentStep / totalSteps) * 100;

  return (
    <div className="flex min-h-screen bg-background">
      {/* Left sidebar -- desktop only */}
      <aside className="hidden w-80 shrink-0 border-r border-border bg-card lg:flex lg:flex-col">
        <div className="flex h-full flex-col p-8">
          {/* Logo */}
          <Link to="/" className="flex items-center gap-2.5 mb-10">
            <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-primary">
              <span className="text-sm font-bold text-primary-foreground">IN</span>
            </div>
            <span className="text-lg font-semibold tracking-tight text-foreground">Influora</span>
          </Link>

          {/* Step list */}
          <div className="flex-1">
            <p className="text-xs font-medium uppercase tracking-widest text-muted-foreground mb-6">
              Setup Progress
            </p>
            <nav className="flex flex-col gap-1">
              {steps.map((step) => {
                const isCompleted = currentStep > step.id;
                const isCurrent = currentStep === step.id;
                const isFuture = currentStep < step.id;

                return (
                  <div
                    key={step.id}
                    className={cn(
                      'flex items-center gap-3 rounded-lg px-3 py-2.5 transition-all duration-200',
                      isCurrent && 'bg-primary/8',
                      isCompleted && 'opacity-60',
                    )}
                  >
                    <div
                      className={cn(
                        'flex h-7 w-7 shrink-0 items-center justify-center rounded-full text-xs font-semibold transition-all duration-200',
                        isCompleted && 'bg-success text-success-foreground',
                        isCurrent && 'bg-primary text-primary-foreground',
                        isFuture && 'border border-border text-muted-foreground',
                      )}
                    >
                      {isCompleted ? (
                        <CheckCircle2 className="h-4 w-4" />
                      ) : (
                        step.id
                      )}
                    </div>
                    <div className="min-w-0">
                      <p
                        className={cn(
                          'text-sm font-medium leading-tight',
                          isCurrent ? 'text-foreground' : 'text-muted-foreground',
                        )}
                      >
                        {step.name}
                      </p>
                      <p className="text-xs text-muted-foreground/70 truncate">
                        {step.description}
                      </p>
                    </div>
                  </div>
                );
              })}
            </nav>
          </div>

          {/* Bottom help */}
          <div className="rounded-lg border border-border bg-muted/50 p-4">
            <p className="text-sm font-medium text-foreground">Need help?</p>
            <p className="text-xs text-muted-foreground mt-1">
              Our team is available 24/7.{' '}
              <Link to="/support" className="text-primary hover:underline">
                Get support
              </Link>
            </p>
          </div>
        </div>
      </aside>

      {/* Main area */}
      <main className="flex flex-1 flex-col min-h-screen">
        {/* Mobile top bar */}
        <header className="flex items-center justify-between border-b border-border px-5 py-3.5 lg:hidden">
          <Link to="/" className="flex items-center gap-2">
            <div className="flex h-7 w-7 items-center justify-center rounded-md bg-primary">
              <span className="text-xs font-bold text-primary-foreground">IN</span>
            </div>
            <span className="font-semibold text-foreground">Influora</span>
          </Link>
          <span className="text-xs font-medium text-muted-foreground">
            {currentStep} of {totalSteps}
          </span>
        </header>

        {/* Progress bar (both mobile and desktop) */}
        <div className="h-1 w-full bg-muted">
          <motion.div
            className="h-full bg-primary rounded-r-full"
            initial={{ width: 0 }}
            animate={{ width: `${progress}%` }}
            transition={{ duration: 0.4, ease: 'easeOut' }}
          />
        </div>

        {/* Step content, centered */}
        <div className="flex flex-1 items-start justify-center overflow-y-auto px-5 py-8 lg:px-8 lg:py-12">
          <div className="w-full max-w-lg">
            <AnimatePresence mode="wait">
              <motion.div
                key={currentStep}
                initial={{ opacity: 0, x: 20 }}
                animate={{ opacity: 1, x: 0 }}
                exit={{ opacity: 0, x: -20 }}
                transition={{ duration: 0.25, ease: 'easeOut' }}
              >
                {children}
              </motion.div>
            </AnimatePresence>
          </div>
        </div>
      </main>
    </div>
  );
}
