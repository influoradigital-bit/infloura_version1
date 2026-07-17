'use client';

import * as React from 'react';
import { OnboardingLayout } from '@/components/brand/onboarding/onboarding-layout';
import { BrandOnboarding } from '@/components/brand/onboarding/onboarding-steps';

export default function BrandOnboardingPage() {
  const [currentStep, setCurrentStep] = React.useState(1);
  const totalSteps = 4;

  // Listen to step changes from child component
  React.useEffect(() => {
    const handleStepChange = () => {
      // This would be connected to the actual step state in a real implementation
    };
  }, []);

  return (
    <OnboardingLayout currentStep={currentStep} totalSteps={totalSteps}>
      <BrandOnboarding />
    </OnboardingLayout>
  );
}
