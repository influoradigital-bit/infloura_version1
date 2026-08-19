import * as React from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowLeft, LifeBuoy } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { HowItWorksFlow } from '@/components/shared/HowItWorksFlow';
import { WalkthroughVideo } from '@/components/shared/WalkthroughVideo';
import { BRAND_STEPS } from '@/content/how-it-works-steps';

/**
 * `/brand/how-it-works` — the canonical flow, inside the authenticated shell.
 *
 * The copy on this page has existed and been approved since the marketing site shipped; it was
 * reachable only from `/how-it-works/brands`, a logged-OUT page. A brand who signs up, lands on
 * a dashboard reporting five kinds of nothing, and wants to know what is supposed to happen next
 * had no route to it — they would have had to log out, or guess the public URL. This is the same
 * `BRAND_STEPS` array the marketing page renders and its `HowTo` schema is built from, so the
 * two can never drift.
 *
 * Wrapped by `BrandLayoutWrapper` in App.tsx, so the sidebar and header come from the route.
 */
export default function BrandHowItWorksPage() {
  const navigate = useNavigate();

  return (
    <div className="mx-auto max-w-3xl space-y-6 p-4 sm:p-6">
      <div>
        <Button
          variant="ghost"
          size="sm"
          className="-ml-2 mb-2 gap-1.5 text-muted-foreground"
          onClick={() => navigate('/brand/dashboard')}
        >
          <ArrowLeft className="h-3.5 w-3.5" aria-hidden />
          Back to dashboard
        </Button>
        <h1 className="text-2xl font-semibold tracking-tight">How Influora works</h1>
        <p className="mt-1.5 text-sm text-muted-foreground">
          Six steps from a campaign brief to a posted reel — and where your money sits at each
          one.
        </p>
      </div>

      {/* Renders nothing until a walkthrough URL is configured — see WalkthroughVideo. */}
      <WalkthroughVideo role="brand" title="Influora walkthrough for brands" />

      <HowItWorksFlow steps={BRAND_STEPS} />

      <div className="flex flex-wrap items-center gap-3 rounded-lg border border-border bg-muted/30 p-4">
        <LifeBuoy className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden />
        <p className="min-w-0 flex-1 text-sm text-muted-foreground">
          Still stuck on something specific? The help centre covers campaigns, deal rooms,
          contracts and payments in detail.
        </p>
        <Button variant="outline" size="sm" onClick={() => navigate('/brand/help')}>
          Open help centre
        </Button>
      </div>
    </div>
  );
}
