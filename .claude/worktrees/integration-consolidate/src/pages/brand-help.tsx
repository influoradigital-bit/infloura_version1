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
    body: 'TODO: final copy from Nisha. Placeholder — Create a campaign brief (goals, budget, platforms), publish it, and creators apply or you invite them directly.',
  },
  {
    icon: MessagesSquare,
    title: 'Deal Rooms',
    body: 'TODO: final copy from Nisha. Placeholder — Every collaboration gets its own Deal Room: chat with the creator, negotiate terms, and track progress in one place.',
  },
  {
    icon: FileSignature,
    title: 'Contracts',
    body: 'TODO: final copy from Nisha. Placeholder — Once terms are agreed, a contract is generated inside the Deal Room for both sides to sign before work begins.',
  },
  {
    icon: Wallet,
    title: 'Payments & Escrow',
    body: 'TODO: final copy from Nisha. Placeholder — Funds are held in escrow when a deal is signed and only released to the creator after you approve their deliverables.',
  },
  {
    icon: Sparkles,
    title: 'Meera',
    body: 'TODO: final copy from Nisha. Placeholder — Meera is your AI cofounder. Ask her to help draft campaigns, explain how something works, or walk you through your account.',
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
          <Card className="cursor-pointer transition-colors hover:border-primary/40" onClick={handleTakeTour}>
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

          <Card className="cursor-pointer transition-colors hover:border-primary/40" onClick={handleAskMeera}>
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
