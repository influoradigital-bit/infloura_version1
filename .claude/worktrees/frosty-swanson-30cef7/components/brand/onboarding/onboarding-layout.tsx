'use client';

import * as React from 'react';
import Link from 'next/link';
import { Building2, CheckCircle2 } from 'lucide-react';

import { cn } from '@/lib/utils';
import { Progress } from '@/components/ui/progress';

interface OnboardingLayoutProps {
  children: React.ReactNode;
  currentStep: number;
  totalSteps: number;
}

const steps = [
  { id: 1, name: 'Account Setup', description: 'Create your account' },
  { id: 2, name: 'Company Details', description: 'Tell us about your brand' },
  { id: 3, name: 'Team Setup', description: 'Invite your team members' },
  { id: 4, name: 'Verification', description: 'Verify your business' },
];

export function OnboardingLayout({
  children,
  currentStep,
  totalSteps,
}: OnboardingLayoutProps) {
  const progress = (currentStep / totalSteps) * 100;

  return (
    <div className="flex min-h-screen flex-col bg-background lg:flex-row">
      {/* Left Panel - Progress Sidebar */}
      <aside className="hidden w-full max-w-sm border-r border-border bg-card p-8 lg:block">
        <div className="sticky top-8 flex flex-col gap-8">
          {/* Logo */}
          <Link href="/" className="flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-primary">
              <Building2 className="h-6 w-6 text-primary-foreground" />
            </div>
            <span className="text-xl font-semibold">CollabOS</span>
          </Link>

          {/* Progress Steps */}
          <div className="space-y-6">
            <div className="space-y-2">
              <h2 className="text-lg font-semibold">Get Started</h2>
              <p className="text-sm text-muted-foreground">
                Complete these steps to set up your brand workspace
              </p>
            </div>

            <nav className="space-y-2">
              {steps.map((step, index) => {
                const isCompleted = currentStep > step.id;
                const isCurrent = currentStep === step.id;

                return (
                  <div
                    key={step.id}
                    className={cn(
                      'flex items-start gap-4 rounded-lg p-3 transition-colors',
                      isCurrent && 'bg-primary/10',
                      isCompleted && 'opacity-70'
                    )}
                  >
                    <div
                      className={cn(
                        'flex h-8 w-8 shrink-0 items-center justify-center rounded-full border-2 text-sm font-medium transition-colors',
                        isCompleted && 'border-primary bg-primary text-primary-foreground',
                        isCurrent && 'border-primary text-primary',
                        !isCompleted && !isCurrent && 'border-muted-foreground/30 text-muted-foreground'
                      )}
                    >
                      {isCompleted ? (
                        <CheckCircle2 className="h-5 w-5" />
                      ) : (
                        step.id
                      )}
                    </div>
                    <div className="space-y-1">
                      <p
                        className={cn(
                          'text-sm font-medium',
                          isCurrent ? 'text-foreground' : 'text-muted-foreground'
                        )}
                      >
                        {step.name}
                      </p>
                      <p className="text-xs text-muted-foreground">
                        {step.description}
                      </p>
                    </div>
                  </div>
                );
              })}
            </nav>
          </div>

          {/* Help Text */}
          <div className="mt-auto rounded-lg border border-border bg-muted/50 p-4">
            <p className="text-sm font-medium">Need help?</p>
            <p className="text-xs text-muted-foreground">
              Our support team is available 24/7.{' '}
              <Link href="/support" className="text-primary hover:underline">
                Contact us
              </Link>
            </p>
          </div>
        </div>
      </aside>

      {/* Main Content */}
      <main className="flex flex-1 flex-col">
        {/* Mobile Header */}
        <header className="flex items-center justify-between border-b border-border p-4 lg:hidden">
          <Link href="/" className="flex items-center gap-2">
            <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-primary">
              <Building2 className="h-5 w-5 text-primary-foreground" />
            </div>
            <span className="font-semibold">CollabOS</span>
          </Link>
          <div className="text-sm text-muted-foreground">
            Step {currentStep} of {totalSteps}
          </div>
        </header>

        {/* Mobile Progress */}
        <div className="border-b border-border p-4 lg:hidden">
          <Progress value={progress} className="h-2" />
          <p className="mt-2 text-sm font-medium">{steps[currentStep - 1]?.name}</p>
        </div>

        {/* Content Area */}
        <div className="flex flex-1 items-start justify-center p-4 lg:p-8">
          <div className="w-full max-w-lg">{children}</div>
        </div>
      </main>
    </div>
  );
}
