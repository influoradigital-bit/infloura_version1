import { Link } from 'react-router-dom';
import { ArrowRight, Globe2, MapPin, ShieldCheck, Sparkles } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent } from '@/components/ui/card';
import { FadeUp, StaggerContainer, StaggerItem, CountUp } from '@/components/motion';
import { SiteHeader } from '@/components/site/SiteHeader';
import { SiteFooter } from '@/components/site/SiteFooter';
import { Seo } from '@/lib/seo/Seo';
import { JsonLd, getOrganizationSchema } from '@/lib/seo/schema';

// Content per wiki/website/content-map.md §1.2.
// CEO-DECISIONS.md #4: no client logos until written permission exists —
// anonymized stats + "trusted by 500+ brands" text only.
// CEO-DECISIONS.md #6: no hard follower-count threshold stated publicly.

const STATS = [
  { label: 'Creators on platform', value: 8915, format: (n: number) => `${Math.round(n).toLocaleString('en-IN')}+` },
  { label: 'Paid out via escrow', value: 42600000, format: (n: number) => `₹${(n / 10000000).toFixed(1)}Cr+` },
  { label: 'Avg. payout time', value: 24, format: (n: number) => `${Math.round(n)}h` },
];

const DIFFERENTIATORS = [
  {
    icon: ShieldCheck,
    title: 'Escrow on every deal',
    body: "Not just a marketplace fee — every single deal, from a one-off reel to a 100-creator Hype Campaign, runs on the same escrow rail.",
  },
  {
    icon: MapPin,
    title: 'India-native',
    body: 'TDS, UPI, and local compliance are built into the platform, not bolted on — because this platform was built for the Indian creator economy first.',
  },
  {
    icon: Globe2,
    title: 'Built for DTC and export brands alike',
    body: 'Whether you sell direct-to-consumer in India or run a B2B export business reaching global buyers through Indian creators, the same Deal Room and escrow flow works.',
  },
];

export default function AboutPage() {
  return (
    <div className="min-h-screen bg-background text-foreground">
      <Seo
        title="About Influora"
        description="Influora replaces WhatsApp-chaos influencer deals with one escrow-protected platform for Indian brands and creators. Our story and what makes us different."
        canonical="/about"
      />
      <JsonLd data={getOrganizationSchema()} />

      <SiteHeader />

      <main>
        {/* Mission */}
        <section className="border-b border-border/60 py-20">
          <div className="mx-auto max-w-3xl px-6 text-center">
            <FadeUp>
              <Badge variant="outline" className="gap-1.5">
                <Sparkles className="h-3 w-3" aria-hidden="true" /> About Influora
              </Badge>
              <h1 className="mt-4 text-4xl font-bold leading-tight tracking-tight sm:text-5xl">
                Real deals, without the chaos
              </h1>
              <p className="mt-4 text-lg text-muted-foreground">
                Influora exists to replace the spreadsheet-WhatsApp-invoice mess of influencer marketing
                with one platform Indian brands and creators can trust — where every deal is
                escrow-protected from the moment a contract is signed.
              </p>
            </FadeUp>
          </div>
        </section>

        {/* Problem / solution */}
        <section className="border-b border-border/60 bg-card/50 py-20">
          <div className="mx-auto max-w-5xl px-6">
            <div className="grid gap-6 lg:grid-cols-2">
              <FadeUp>
                <Card className="h-full">
                  <CardContent className="p-6">
                    <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                      Before Influora
                    </p>
                    <p className="mt-3 text-sm text-muted-foreground">
                      Deals get negotiated across Instagram DMs, WhatsApp, and email. Contracts, if they
                      exist at all, are a separate PDF nobody signs on time. Brands pay upfront with no
                      guarantee of delivery; creators deliver with no guarantee of payment. Someone always
                      ends up chasing the other.
                    </p>
                  </CardContent>
                </Card>
              </FadeUp>
              <FadeUp delay={0.1}>
                <Card className="h-full border-accent-foreground/30">
                  <CardContent className="p-6">
                    <p className="text-xs font-semibold uppercase tracking-wide text-accent-foreground">
                      After Influora
                    </p>
                    <p className="mt-3 text-sm text-foreground">
                      One platform: verified creator discovery, a single Deal Room thread, an e-signed
                      contract with clear terms, and escrow that holds the brand's payment until the
                      deliverable is approved. Nobody has to chase anybody.
                    </p>
                  </CardContent>
                </Card>
              </FadeUp>
            </div>
          </div>
        </section>

        {/* Stats */}
        <section className="py-20">
          <div className="mx-auto max-w-4xl px-6 text-center">
            <FadeUp>
              <h2 className="text-3xl font-semibold">Where we stand today</h2>
            </FadeUp>
            <StaggerContainer className="mt-10 grid grid-cols-1 gap-8 sm:grid-cols-3">
              {STATS.map((stat) => (
                <StaggerItem key={stat.label}>
                  <p className="text-3xl font-bold">
                    <CountUp value={stat.value} formatFn={stat.format} viewMargin="0px" />
                  </p>
                  <p className="mt-1 text-sm text-muted-foreground">{stat.label}</p>
                </StaggerItem>
              ))}
            </StaggerContainer>
          </div>
        </section>

        {/* How we're different */}
        <section className="border-t border-border/60 bg-card/50 py-20">
          <div className="mx-auto max-w-6xl px-6">
            <FadeUp className="mx-auto max-w-xl text-center">
              <h2 className="text-3xl font-semibold">How we're different</h2>
            </FadeUp>
            <StaggerContainer className="mt-12 grid gap-4 sm:grid-cols-3">
              {DIFFERENTIATORS.map((item) => {
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

        {/* Trust signals — no fake logos (CEO-DECISIONS.md #4) */}
        <section className="py-20">
          <div className="mx-auto max-w-3xl px-6 text-center">
            <FadeUp>
              <p className="text-sm font-medium text-accent-foreground">Trusted by 500+ Indian brands</p>
              <h2 className="mt-2 text-2xl font-semibold">
                Verified creators of all sizes, from nano to macro
              </h2>
              <p className="mt-3 text-muted-foreground">
                Every creator profile is Instagram-verified before it's discoverable, and every payout
                moves through Influora's licensed payment gateway partner — the same escrow rail whether
                it's a single reel or a 100-creator Hype Campaign.
              </p>
            </FadeUp>
          </div>
        </section>

        {/* Final CTA */}
        <section className="border-t border-border/60 bg-card/50 py-20">
          <FadeUp className="mx-auto max-w-2xl px-6 text-center">
            <h2 className="text-3xl font-semibold">Start your first campaign</h2>
            <p className="mt-3 text-muted-foreground">
              Free to start for brands and creators. Escrow-protected from the first deal.
            </p>
            <div className="mt-8 flex flex-wrap justify-center gap-3">
              <Button size="lg" className="bg-accent-foreground text-white hover:bg-accent-foreground/90" asChild>
                <Link to="/brand/register">
                  Start your first campaign <ArrowRight className="ml-1.5 h-4 w-4" aria-hidden="true" />
                </Link>
              </Button>
              <Button size="lg" variant="outline" asChild>
                <Link to="/brand/discover">Browse creator profiles</Link>
              </Button>
            </div>
          </FadeUp>
        </section>
      </main>

      <SiteFooter />
    </div>
  );
}
