import * as React from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowLeft } from 'lucide-react';

import { CreatorLayout } from '@/components/creator/creator-layout';
import { Button } from '@/components/ui/button';
import { HowItWorksFlow } from '@/components/shared/HowItWorksFlow';
import { WalkthroughVideo } from '@/components/shared/WalkthroughVideo';
import { CREATOR_STEPS } from '@/content/how-it-works-steps';

/**
 * `/creator/how-it-works` — the creator mirror of `/brand/how-it-works`.
 *
 * Renders `CREATOR_STEPS`, the same array `/how-it-works/creators` renders and builds its `HowTo`
 * schema from. Unlike the brand page, this one wraps itself in `CreatorLayout` — that is the
 * convention every creator page follows (creator routes wrap in the page, brand routes wrap in
 * App.tsx via BrandLayoutWrapper).
 */
export default function CreatorHowItWorksPage() {
  const navigate = useNavigate();

  return (
    <CreatorLayout>
      <div className="mx-auto max-w-3xl space-y-6 p-4 sm:p-6">
        <div>
          <Button
            variant="ghost"
            size="sm"
            className="-ml-2 mb-2 gap-1.5 text-muted-foreground"
            onClick={() => navigate('/creator/dashboard')}
          >
            <ArrowLeft className="h-3.5 w-3.5" aria-hidden />
            Back to dashboard
          </Button>
          <h1 className="text-2xl font-semibold tracking-tight">How Influora works</h1>
          <p className="mt-1.5 text-sm text-muted-foreground">
            Six steps from an empty profile to money in your wallet — and when exactly you get
            paid.
          </p>
        </div>

        {/* Renders nothing until a walkthrough URL is configured — see WalkthroughVideo. */}
        <WalkthroughVideo role="creator" title="Influora walkthrough for creators" />

        <HowItWorksFlow steps={CREATOR_STEPS} />
      </div>
    </CreatorLayout>
  );
}
