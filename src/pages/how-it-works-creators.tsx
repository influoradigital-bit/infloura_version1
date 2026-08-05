import { Link } from 'react-router-dom';
import {
  ArrowRight,
  FileSignature,
  MessageSquareText,
  Search,
  UploadCloud,
  Wallet,
  Zap,
} from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { FadeUp, StaggerContainer, StaggerItem } from '@/components/motion';
import { SiteHeader } from '@/components/site/SiteHeader';
import { SiteFooter } from '@/components/site/SiteFooter';
import { Seo } from '@/lib/seo/Seo';
import { JsonLd, getBreadcrumbListSchema } from '@/lib/seo/schema';

// Content per wiki/website/content-map.md §1.4.

const STEPS = [
  {
    icon: Search,
    step: '01',
    title: 'Create your profile, connect Instagram',
    body: 'Link your verified Instagram account, set your rate card (per reel, per carousel, per story), and add past work to your portfolio.',
  },
  {
    icon: Zap,
    step: '02',
    title: 'Get discovered, or join a Hype Campaign',
    body: 'Brands search and find you directly, or browse live Hype Campaigns and accept a slot with one tap — no negotiation required.',
  },
  {
    icon: MessageSquareText,
    step: '03',
    title: 'Negotiate in the Deal Room',
    body: 'A brand sends a proposal. Chat, negotiate the rate, and counter-offer — all in one thread, no lost DMs.',
  },
  {
    icon: FileSignature,
    step: '04',
    title: 'Accept the contract — escrow already funded',
    body: 'E-sign the generated contract. The funds are already locked in escrow before you start work, so payment is guaranteed.',
  },
  {
    icon: UploadCloud,
    step: '05',
    title: 'Create and submit your deliverable',
    body: 'Upload your reel, carousel, or video. The brand reviews it and may request revisions within the contract\'s limit.',
  },
  {
    icon: Wallet,
    step: '06',
    title: 'Get paid, then post',
    body: 'Once the brand approves, escrow releases to you automatically — TDS handled, invoice generated. Post within the campaign window and see the payment land, usually within 24 hours.',
  },
];

export default function HowItWorksCreatorsPage() {
  return (
    <div className="min-h-screen bg-background text-foreground">
      <Seo
        title="How It Works for Creators"
        description="Connect Instagram, get discovered or join a Hype Campaign, negotiate in the Deal Room, and get paid through escrow — usually within 24 hours."
        canonical="/how-it-works/creators"
      />
      <JsonLd
        data={getBreadcrumbListSchema([
          { name: 'Home', url: '/' },
          { name: 'How It Works — Creators', url: '/how-it-works/creators' },
        ])}
      />

      <SiteHeader />

      <main>
        {/* Hero */}
        <section className="border-b border-border/60 py-20">
          <div className="mx-auto max-w-3xl px-6 text-center">
            <FadeUp>
              <Badge variant="outline" className="gap-1.5">
                For creators
              </Badge>
              <h1 className="mt-4 text-4xl font-bold leading-tight tracking-tight sm:text-5xl">
                How creators earn on Influora
              </h1>
              <p className="mt-4 text-lg text-muted-foreground">
                Six steps from profile to payout — verified creators of all sizes, from nano to macro,
                get paid through escrow with no invoice-chasing.
              </p>
              <div className="mt-8 flex flex-wrap justify-center gap-3">
                <Button size="lg" className="bg-accent-foreground text-white hover:bg-accent-foreground/90" asChild>
                  <Link to="/creator/register">
                    Join as a creator <ArrowRight className="ml-1.5 h-4 w-4" aria-hidden="true" />
                  </Link>
                </Button>
                <Button size="lg" variant="outline" asChild>
                  <Link to="/how-it-works/brands">See the brand side</Link>
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

        {/* Hype + TDS cross-links */}
        <section className="border-t border-border/60 bg-card/50 py-20">
          <div className="mx-auto grid max-w-4xl gap-6 px-6 sm:grid-cols-2">
            <FadeUp>
              <div className="h-full rounded-2xl border border-hype-border bg-background p-6">
                <Badge className="gap-1 border-hype-border bg-hype text-hype-foreground hover:bg-hype">
                  <Zap className="h-3 w-3" aria-hidden="true" /> Hype Campaigns
                </Badge>
                <h3 className="mt-3 font-semibold">Want faster, simpler deals?</h3>
                <p className="mt-1.5 text-sm text-muted-foreground">
                  Skip negotiation entirely — browse live Hype Campaigns and accept a fixed-rate slot with
                  one tap.
                </p>
                <Link
                  to="/features/hype"
                  className="mt-3 inline-flex items-center gap-1.5 text-sm font-medium text-accent-foreground hover:underline"
                >
                  Explore Hype Campaigns <ArrowRight className="h-3.5 w-3.5" aria-hidden="true" />
                </Link>
              </div>
            </FadeUp>
            <FadeUp delay={0.1}>
              <div className="h-full rounded-2xl border border-border/60 bg-background p-6">
                <Badge variant="outline">TDS</Badge>
                <h3 className="mt-3 font-semibold">Wondering about tax on your payout?</h3>
                <p className="mt-1.5 text-sm text-muted-foreground">
                  TDS is calculated and deducted automatically, with an invoice generated for every
                  payout.
                </p>
                <Link
                  to="/tds"
                  className="mt-3 inline-flex items-center gap-1.5 text-sm font-medium text-accent-foreground hover:underline"
                >
                  Read the TDS guide <ArrowRight className="h-3.5 w-3.5" aria-hidden="true" />
                </Link>
              </div>
            </FadeUp>
          </div>
        </section>

        {/* Final CTA */}
        <section className="py-20">
          <FadeUp className="mx-auto max-w-2xl px-6 text-center">
            <h2 className="text-3xl font-semibold">Ready to get discovered?</h2>
            <p className="mt-3 text-muted-foreground">
              Free to join. Every deal you accept is escrow-protected before you start work.
            </p>
            <div className="mt-8 flex flex-wrap justify-center gap-3">
              <Button size="lg" className="bg-accent-foreground text-white hover:bg-accent-foreground/90" asChild>
                <Link to="/creator/register">Join as a creator</Link>
              </Button>
              <Button size="lg" variant="outline" asChild>
                <Link to="/features/escrow">How escrow protects you</Link>
              </Button>
            </div>
          </FadeUp>
        </section>
      </main>

      <SiteFooter />
    </div>
  );
}
