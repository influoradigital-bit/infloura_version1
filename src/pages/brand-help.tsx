import * as React from 'react';
import { useNavigate } from 'react-router-dom';
import {
  HelpCircle,
  Megaphone,
  MessagesSquare,
  FileSignature,
  Wallet,
  Sparkles,
  Compass,
  ArrowRight,
} from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { useUIStore } from '@/lib/store';
import { MEERA_HELP_PRESEED_PARAM, MEERA_HELP_PRESEED_PROMPT } from '@/lib/meera-help';

/**
 * Static "How it works" help page (SaaS help layer #4). Copy below is
 * placeholder — TODO: final copy from Nisha. Sections mirror the product's
 * real surfaces so a brand can self-serve without leaving the app.
 */
const SECTIONS = [
  {
    icon: Megaphone,
    title: 'Campaigns',
    body: `Create a campaign brief with your goals, budget, and platform. Choose Open (creators apply publicly), Direct (invite specific creators), or Hype (72-hour blitz with many creators on one reel). Once you publish, creators apply or you can reach out directly from Discover.`,
  },
  {
    icon: MessagesSquare,
    title: 'Deal Rooms',
    body: `Every collaboration gets its own Deal Room. Chat with the creator, send and counter proposals (budget, deliverables, timeline), and track every milestone in one place. All your deal rooms appear on your dashboard — filter by stage to see what needs attention.`,
  },
  {
    icon: FileSignature,
    title: 'Contracts',
    body: `Once you and the creator agree on terms, a contract is generated right inside the Deal Room. Both sides e-sign before work starts. The contract locks in deliverables, payment schedule, and usage rights — once it's signed, ask Meera to fund escrow for the deal (escrow funding isn't automatic; it's a step you or Meera trigger).`,
  },
  {
    icon: Wallet,
    title: 'Payments & Escrow',
    body: `When a deal is signed, the agreed amount is held in escrow and released to the creator only after you approve their deliverables. You can top up your wallet, view transaction history, and track escrow holds — all in one dashboard. Payment processing is powered by Razorpay.`,
  },
  {
    icon: Sparkles,
    title: 'Meera',
    body: `Meera is your AI cofounder. She can help you draft campaign briefs, explain how features work, answer product questions, or walk you through your account. Just open the chat and ask — she's available anytime from the sidebar.`,
  },
];

export default function BrandHelpPage() {
  const navigate = useNavigate();
  const { openTour } = useUIStore();

  const handleTakeTour = () => {
    navigate('/brand/dashboard');
    // Let the dashboard mount first, then open — avoids racing the route change.
    setTimeout(openTour, 150);
  };

  const handleAskMeera = () => {
    navigate(`/brand/meera?${MEERA_HELP_PRESEED_PARAM}=${encodeURIComponent(MEERA_HELP_PRESEED_PROMPT)}`);
  };

  // H-2 (BrandF.md §76): quick-action cards were plain `<Card onClick>` divs —
  // no role, no tabIndex, no Enter/Space handler, so keyboard and
  // screen-reader users had no path to either action. Fires the same
  // handler on click or on Enter/Space, matching native button semantics.
  const handleActionKeyDown = (handler: () => void) => (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      handler();
    }
  };

  return (
    <div className="flex-1 overflow-auto">
      <div className="p-8 max-w-3xl mx-auto">
        <div className="mb-8 text-center">
          <div className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-primary/10">
            <HelpCircle className="h-6 w-6 text-primary" />
          </div>
          <h1 className="mt-3 text-2xl font-semibold tracking-tight">How Influora works</h1>
          <p className="mt-1 text-sm text-muted-foreground">
            A quick reference for campaigns, deal rooms, contracts, and payments.
          </p>
        </div>

        {/* Quick actions */}
        <div className="mb-8 grid gap-3 sm:grid-cols-2">
          <Card
            role="button"
            tabIndex={0}
            aria-label="Take the tour again — replay the nav walkthrough"
            className="cursor-pointer transition-colors hover:border-primary/40 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2"
            onClick={handleTakeTour}
            onKeyDown={handleActionKeyDown(handleTakeTour)}
          >
            <CardContent className="flex items-center gap-3 p-4">
              <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-primary/10">
                <Compass className="h-4.5 w-4.5 text-primary" />
              </div>
              <div className="flex-1">
                <p className="text-sm font-medium">Take the tour again</p>
                <p className="text-xs text-muted-foreground">Replay the nav walkthrough</p>
              </div>
              <ArrowRight className="h-4 w-4 text-muted-foreground" />
            </CardContent>
          </Card>

          <Card
            role="button"
            tabIndex={0}
            aria-label="Ask Meera — get a walkthrough from your AI cofounder"
            className="cursor-pointer transition-colors hover:border-primary/40 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2"
            onClick={handleAskMeera}
            onKeyDown={handleActionKeyDown(handleAskMeera)}
          >
            <CardContent className="flex items-center gap-3 p-4">
              <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-primary/10">
                <Sparkles className="h-4.5 w-4.5 text-primary" />
              </div>
              <div className="flex-1">
                <p className="text-sm font-medium">Ask Meera</p>
                <p className="text-xs text-muted-foreground">Get a walkthrough from your AI cofounder</p>
              </div>
              <ArrowRight className="h-4 w-4 text-muted-foreground" />
            </CardContent>
          </Card>
        </div>

        {/* Sections */}
        <div className="space-y-4">
          {SECTIONS.map((section) => (
            <Card key={section.title}>
              <CardHeader className="pb-2">
                <CardTitle className="flex items-center gap-2 text-base">
                  <section.icon className="h-4.5 w-4.5 text-primary" />
                  {section.title}
                </CardTitle>
              </CardHeader>
              <CardContent>
                <p className="text-sm text-muted-foreground">{section.body}</p>
              </CardContent>
            </Card>
          ))}
        </div>

        <p className="mt-8 text-center text-xs text-muted-foreground">
          Still stuck?{' '}
          <Button variant="link" size="sm" className="h-auto p-0 text-xs" onClick={handleAskMeera}>
            Ask Meera
          </Button>{' '}
          — she can walk you through anything above.
        </p>
      </div>
    </div>
  );
}
