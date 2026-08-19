import { Link } from 'react-router-dom';
import { ArrowRight, Zap } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { FadeUp, StaggerContainer, StaggerItem } from '@/components/motion';
import { SiteHeader } from '@/components/site/SiteHeader';
import { SiteFooter } from '@/components/site/SiteFooter';
import { FunnelCta } from '@/components/site/FunnelCta';
import { TrustBar } from '@/components/site/TrustBar';
import { StickyCta, StickyCtaSpacer } from '@/components/site/StickyCta';
import { BRAND_STEPS as STEPS } from '@/content/how-it-works-steps';
import { Seo } from '@/lib/seo/Seo';
import {
  JsonLd,
  getBreadcrumbListSchema,
  getHowToSchema,
  getWebPageSchema,
} from '@/lib/seo/schema';


export default function HowItWorksBrandsPage() {
  return (
    <div className="min-h-screen bg-background text-foreground">
      <Seo
        title="How It Works for Brands"
        description="Create a campaign, discover verified creators, negotiate in the Deal Room, and pay with protection built in. How brands run deals on Influora, step by step."
        canonical="/how-it-works/brands"
      />
      {/*
        HowTo, built from the same STEPS array the page renders.

        This is the highest-leverage schema on the site for AEO: "how do I run an
        influencer campaign in India" is a procedural query, and ChatGPT,
        Perplexity and AI Overviews all lift HowTo steps close to verbatim. The
        steps are mapped, never re-typed, so the quoted answer is always the copy
        actually on the page.
      */}
      <JsonLd
        data={getHowToSchema({
          name: 'How to run an influencer campaign in India on Influora',
          description:
            'The six steps a brand takes on Influora, from creating a campaign to the creator posting and the payment releasing.',
          url: '/how-it-works/brands',
          steps: STEPS.map((s) => ({ name: s.title, text: s.body })),
        })}
      />
      <JsonLd
        data={getWebPageSchema({
          name: 'How It Works for Brands',
          description:
            'A brand creates a campaign, discovers verified creators, negotiates in the Deal Room, e-signs a contract and funds the deal, approves the delivered work, and the payment releases to the creator automatically.',
          url: '/how-it-works/brands',
        })}
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
                payment-protected payout, all inside one platform.
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

        <TrustBar />

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

        <FunnelCta
          heading="Ready to launch your first campaign?"
          sub="Free to start — no subscription on the Free tier. Upgrade to Pro anytime for lower fees and team features."
          primary={{ label: 'Launch your first campaign', to: '/brand/register' }}
          secondary={{ label: 'See pricing first', to: '/pricing' }}
          reassurances={['Free to start', 'Contracts included', 'Pay only on completed deals']}
          className="py-20"
        />
      </main>

      <SiteFooter />
      <StickyCta label="Launch a campaign" to="/brand/register" note="Free to start" />
      <StickyCtaSpacer />
    </div>
  );
}
