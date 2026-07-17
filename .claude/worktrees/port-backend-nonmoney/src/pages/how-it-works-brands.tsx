import { Link } from 'react-router-dom';
import {
  ArrowRight,
  BadgeCheck,
  FileSignature,
  MessageSquareText,
  Search,
  ShieldCheck,
  Zap,
} from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { FadeUp, StaggerContainer, StaggerItem } from '@/components/motion';
import { SiteHeader } from '@/components/site/SiteHeader';
import { SiteFooter } from '@/components/site/SiteFooter';
import { Seo } from '@/lib/seo/Seo';
import { JsonLd, getBreadcrumbListSchema } from '@/lib/seo/schema';

// Content per wiki/website/content-map.md §1.3.

const STEPS = [
  {
    icon: Search,
    step: '01',
    title: 'Create your campaign',
    body: 'Set your goals (reach, engagement, sales), choose a campaign type — one-off collab, Hype Campaign, or seasonal — and define deliverables (reel, carousel, story, YouTube integration).',
  },
  {
    icon: Search,
    step: '02',
    title: 'Discover creators',
    body: 'Search by niche, follower range, city, and engagement rate. Every profile is Instagram-verified with a published rate card and visible past collaborations.',
  },
  {
    icon: MessageSquareText,
    step: '03',
    title: 'Negotiate in the Deal Room',
    body: 'Chat, send a proposal, and handle counter-offers in one thread — no juggling WhatsApp and DMs.',
  },
  {
    icon: FileSignature,
    step: '04',
    title: 'Sign the contract, fund escrow',
    body: 'An auto-generated contract spells out usage rights, exclusivity, and revision limits. Both sides e-sign, then the deal amount locks in escrow.',
  },
  {
    icon: BadgeCheck,
    step: '05',
    title: 'Creator delivers, you approve',
    body: 'The creator uploads the deliverable in the Deal Room. Request revisions within the contract limit, or approve it.',
  },
  {
    icon: ShieldCheck,
    step: '06',
    title: 'Escrow releases, creator posts',
    body: 'On approval, escrow auto-releases payment to the creator. They post within the campaign window and you track performance from your dashboard.',
  },
];

export default function HowItWorksBrandsPage() {
  return (
    <div className="min-h-screen bg-background text-foreground">
      <Seo
        title="How It Works for Brands"
        description="Create a campaign, discover verified creators, negotiate in the Deal Room, and pay through escrow. How brands run deals on Influora, step by step."
        canonical="/how-it-works/brands"
      />
      <JsonLd
        data={getBreadcrumbListSchema([
          { name: 'Home', url: '/' },
          { name: 'How It Works — Brands', url: '/how-it-works/brands' },
        ])}
      />

      <SiteHeader />

      <main>
        {/* Hero */}
        <section className="border-b border-border/60 py-20">
          <div className="mx-auto max-w-3xl px-6 text-center">
            <FadeUp>
              <Badge variant="outline" className="gap-1.5">
                For brands
              </Badge>
              <h1 className="mt-4 text-4xl font-bold leading-tight tracking-tight sm:text-5xl">
                How brands run a deal on Influora
              </h1>
              <p className="mt-4 text-lg text-muted-foreground">
                Six steps from campaign brief to a posted reel — discovery, negotiation, contract, and
                escrow-protected payout, all inside one platform.
              </p>
              <div className="mt-8 flex flex-wrap justify-center gap-3">
                <Button size="lg" className="bg-accent-foreground text-white hover:bg-accent-foreground/90" asChild>
                  <Link to="/brand/register">
                    Launch your first campaign <ArrowRight className="ml-1.5 h-4 w-4" aria-hidden="true" />
                  </Link>
                </Button>
                <Button size="lg" variant="outline" asChild>
                  <Link to="/how-it-works/creators">See the creator side</Link>
                </Button>
              </div>
            </FadeUp>
          </div>
        </section>

        {/* Steps */}
        <section className="py-20">
          <div className="mx-auto max-w-4xl px-6">
            <StaggerContainer className="space-y-6">
              {STEPS.map((step) => {
                const Icon = step.icon;
                return (
                  <StaggerItem key={step.title}>
                    <div className="flex gap-5 rounded-2xl border border-border/60 bg-card/50 p-6">
                      <div className="flex flex-col items-center gap-2">
                        <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-lg bg-accent text-accent-foreground">
                          <Icon className="h-5 w-5" aria-hidden="true" />
                        </span>
                        <span className="text-xs font-semibold tracking-widest text-muted-foreground/70">
                          {step.step}
                        </span>
                      </div>
                      <div>
                        <h2 className="font-semibold">{step.title}</h2>
                        <p className="mt-1.5 text-sm text-muted-foreground">{step.body}</p>
                      </div>
                    </div>
                  </StaggerItem>
                );
              })}
            </StaggerContainer>
          </div>
        </section>

        {/* Hype cross-link */}
        <section className="border-t border-border/60 bg-card/50 py-20">
          <div className="mx-auto max-w-3xl px-6 text-center">
            <FadeUp>
              <Badge className="gap-1 border-hype-border bg-hype text-hype-foreground hover:bg-hype">
                <Zap className="h-3 w-3" aria-hidden="true" /> Need scale, not one-off deals?
              </Badge>
              <h2 className="mt-3 text-2xl font-semibold">
                Run a Hype Campaign instead — 100 creators, 72 hours
              </h2>
              <p className="mt-3 text-muted-foreground">
                For launches and drops, skip individual negotiation entirely: set a flat rate, cap the
                slots, and let creators accept with one tap.
              </p>
              <div className="mt-6">
                <Button size="lg" variant="outline" asChild>
                  <Link to="/features/hype">
                    Explore Hype Campaigns <ArrowRight className="ml-1.5 h-4 w-4" aria-hidden="true" />
                  </Link>
                </Button>
              </div>
            </FadeUp>
          </div>
        </section>

        {/* Final CTA */}
        <section className="py-20">
          <FadeUp className="mx-auto max-w-2xl px-6 text-center">
            <h2 className="text-3xl font-semibold">Ready to launch your first campaign?</h2>
            <p className="mt-3 text-muted-foreground">
              Free to start — no subscription on the Free tier. Upgrade to Pro anytime for lower fees and
              team features.
            </p>
            <div className="mt-8 flex flex-wrap justify-center gap-3">
              <Button size="lg" className="bg-accent-foreground text-white hover:bg-accent-foreground/90" asChild>
                <Link to="/brand/register">Launch your first campaign</Link>
              </Button>
              <Button size="lg" variant="outline" asChild>
                <Link to="/pricing">See pricing</Link>
              </Button>
            </div>
          </FadeUp>
        </section>
      </main>

      <SiteFooter />
    </div>
  );
}
