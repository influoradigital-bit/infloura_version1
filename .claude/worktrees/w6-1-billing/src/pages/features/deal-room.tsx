import { Link } from 'react-router-dom';
import {
  ArrowRight,
  Check,
  FileCheck2,
  MessageSquareText,
  Repeat,
  UploadCloud,
  X,
} from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent } from '@/components/ui/card';
import { FadeUp, StaggerContainer, StaggerItem } from '@/components/motion';
import { SiteHeader } from '@/components/site/SiteHeader';
import { SiteFooter } from '@/components/site/SiteFooter';
import { Seo } from '@/lib/seo/Seo';
import { JsonLd, getBreadcrumbListSchema } from '@/lib/seo/schema';

// Content per wiki/website/content-map.md §2.2.

const OLD_WAY = [
  'Instagram DM → WhatsApp → email → lost proposal',
  'Re-negotiating the same terms across three apps',
  'No single record of what was agreed',
  'Contracts (if any) live in a separate PDF, unsigned',
];

const NEW_WAY = [
  'One thread: chat, proposal, counter-offer, contract, deliverable',
  'Full history saved — nothing gets lost mid-negotiation',
  'Both sides e-sign the generated contract in the same thread',
  'Deliverables upload and get approved without leaving the Deal Room',
];

const INCLUDED = [
  {
    icon: MessageSquareText,
    title: 'In-thread messaging',
    body: 'Chat directly with the other side — no switching to WhatsApp or DMs mid-deal.',
  },
  {
    icon: FileCheck2,
    title: 'Proposal builder',
    body: 'Deliverable type, rate, timeline, and revision count are structured fields, not a paragraph of text.',
  },
  {
    icon: Repeat,
    title: 'Counter-offer flow',
    body: 'Either side can counter a proposal without starting the conversation over.',
  },
  {
    icon: FileCheck2,
    title: 'Contract + e-sign',
    body: 'Once terms are agreed, Influora generates the contract and both sides e-sign in the thread.',
  },
  {
    icon: UploadCloud,
    title: 'Deliverable upload + approval',
    body: 'Creators submit work directly in the Deal Room; brands approve or request revisions on the spot.',
  },
];

export default function DealRoomFeaturePage() {
  return (
    <div className="min-h-screen bg-background text-foreground">
      <Seo
        title="Deal Room — Negotiate Brand Deals in One Thread"
        description="The Deal Room replaces Instagram DMs, WhatsApp, and email with one thread: chat, proposals, counter-offers, contracts, and deliverables — all in one place."
        canonical="/features/deal-room"
      />
      <JsonLd
        data={getBreadcrumbListSchema([
          { name: 'Home', url: '/' },
          { name: 'Features', url: '/features/deal-room' },
          { name: 'Deal Room', url: '/features/deal-room' },
        ])}
      />

      <SiteHeader />

      <main>
        {/* Hero */}
        <section className="border-b border-border/60 py-20">
          <div className="mx-auto max-w-3xl px-6 text-center">
            <FadeUp>
              <Badge variant="outline" className="gap-1.5">
                <MessageSquareText className="h-3 w-3" aria-hidden="true" /> Deal Room
              </Badge>
              <h1 className="mt-4 text-4xl font-bold leading-tight tracking-tight sm:text-5xl">
                Every negotiation, one thread
              </h1>
              <p className="mt-4 text-lg text-muted-foreground">
                Chat, proposals, counter-offers, contracts, and deliverables — the Deal Room replaces the
                Instagram DM → WhatsApp → email → lost-proposal cycle with a single record both sides can
                trust.
              </p>
              <div className="mt-8 flex flex-wrap justify-center gap-3">
                <Button size="lg" className="bg-accent-foreground text-white hover:bg-accent-foreground/90" asChild>
                  <Link to="/brand/register">
                    Try Deal Room <ArrowRight className="ml-1.5 h-4 w-4" aria-hidden="true" />
                  </Link>
                </Button>
                <Button size="lg" variant="outline" asChild>
                  <Link to="/creator/register">Join as a creator</Link>
                </Button>
              </div>
            </FadeUp>
          </div>
        </section>

        {/* Old way vs Deal Room way */}
        <section className="border-b border-border/60 bg-card/50 py-20">
          <div className="mx-auto max-w-6xl px-6">
            <FadeUp className="mx-auto max-w-xl text-center">
              <h2 className="text-3xl font-semibold">The old way vs. the Deal Room way</h2>
            </FadeUp>
            <div className="mt-12 grid gap-6 lg:grid-cols-2">
              <FadeUp>
                <Card className="h-full border-destructive/30">
                  <CardContent className="p-6">
                    <h3 className="font-semibold text-muted-foreground">The old way</h3>
                    <ul className="mt-4 space-y-3">
                      {OLD_WAY.map((line) => (
                        <li key={line} className="flex items-start gap-2.5 text-sm text-muted-foreground">
                          <X className="mt-0.5 h-4 w-4 shrink-0 text-destructive/70" aria-hidden="true" />
                          {line}
                        </li>
                      ))}
                    </ul>
                  </CardContent>
                </Card>
              </FadeUp>
              <FadeUp delay={0.1}>
                <Card className="h-full border-accent-foreground/30">
                  <CardContent className="p-6">
                    <h3 className="font-semibold">The Deal Room way</h3>
                    <ul className="mt-4 space-y-3">
                      {NEW_WAY.map((line) => (
                        <li key={line} className="flex items-start gap-2.5 text-sm text-foreground">
                          <Check className="mt-0.5 h-4 w-4 shrink-0 text-accent-foreground" aria-hidden="true" />
                          {line}
                        </li>
                      ))}
                    </ul>
                  </CardContent>
                </Card>
              </FadeUp>
            </div>
          </div>
        </section>

        {/* What's included */}
        <section className="py-20">
          <div className="mx-auto max-w-6xl px-6">
            <FadeUp className="mx-auto max-w-xl text-center">
              <h2 className="text-3xl font-semibold">What's included</h2>
            </FadeUp>
            <StaggerContainer className="mt-12 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
              {INCLUDED.map((item) => {
                const Icon = item.icon;
                return (
                  <StaggerItem key={item.title}>
                    <Card className="h-full">
                      <CardContent className="p-6">
                        <span className="flex h-10 w-10 items-center justify-center rounded-lg bg-accent text-accent-foreground">
                          <Icon className="h-5 w-5" aria-hidden="true" />
                        </span>
                        <h3 className="mt-4 font-semibold">{item.title}</h3>
                        <p className="mt-1.5 text-sm text-muted-foreground">{item.body}</p>
                      </CardContent>
                    </Card>
                  </StaggerItem>
                );
              })}
            </StaggerContainer>
          </div>
        </section>

        {/* Cross-link + final CTA */}
        <section className="border-t border-border/60 bg-card/50 py-20">
          <FadeUp className="mx-auto max-w-2xl px-6 text-center">
            <h2 className="text-3xl font-semibold">Contracts and escrow live in the same thread</h2>
            <p className="mt-3 text-muted-foreground">
              Every Deal Room negotiation ends in an e-signed contract and an escrow-funded deal.
            </p>
            <div className="mt-8 flex flex-wrap justify-center gap-3">
              <Button size="lg" className="bg-accent-foreground text-white hover:bg-accent-foreground/90" asChild>
                <Link to="/features/escrow">
                  See how escrow works <ArrowRight className="ml-1.5 h-4 w-4" aria-hidden="true" />
                </Link>
              </Button>
              <Button size="lg" variant="outline" asChild>
                <Link to="/how-it-works/brands">Full walkthrough for brands</Link>
              </Button>
            </div>
          </FadeUp>
        </section>
      </main>

      <SiteFooter />
    </div>
  );
}
