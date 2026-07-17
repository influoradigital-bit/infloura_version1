import { Link } from 'react-router-dom';
import {
  ArrowRight,
  FileCheck2,
  MessageSquareText,
  Search,
  ShieldCheck,
  Wallet,
  Zap,
} from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent } from '@/components/ui/card';
import { lazy, Suspense } from 'react';
import { InfluoraLogo } from '@/components/shared/influora-logo';
import { CanvasFallback } from '@/components/3d/CanvasFallback';

// Lazy — keeps three.js out of the critical bundle (Lighthouse mobile ≥ 85)
const HeroGlobeGate = lazy(() =>
  import('@/components/3d/HeroGlobe').then((m) => ({ default: m.HeroGlobeGate })),
);
import { FadeUp, StaggerContainer, StaggerItem, WordReveal, CountUp } from '@/components/motion';
import { EscrowFlowAnimation } from '@/components/motion/EscrowFlowAnimation';
import { HypeLiveIndicator } from '@/components/ui/hype-live-indicator';
import { SlotProgressBar } from '@/components/ui/slot-progress-bar';
import { demoHypeConfig } from '@/lib/demo-data';

// ---------------------------------------------------------------------------
// Content — single source of truth for landing copy
// ---------------------------------------------------------------------------

const HERO = {
  headline: 'Where brands and creators sign real deals',
  sub: 'Discover creators, negotiate in one Deal Room, and pay through escrow — from a single reel to a 100-creator Hype blitz.',
};

const STATS = [
  { label: 'Creators on platform', value: 8915, format: (n: number) => `${Math.round(n).toLocaleString('en-IN')}+` },
  { label: 'Paid out via escrow', value: 42600000, format: (n: number) => `₹${(n / 10000000).toFixed(1)}Cr+` },
  { label: 'Avg. payout time', value: 24, format: (n: number) => `${Math.round(n)}h` },
];

const FEATURES = [
  {
    icon: Search,
    title: 'Discover verified creators',
    body: 'Instagram-verified profiles with real engagement stats, rate cards, and past collab history.',
  },
  {
    icon: MessageSquareText,
    title: 'One Deal Room',
    body: 'Chat, proposals, counter-offers, and contracts in a single thread — no more WhatsApp + email chaos.',
  },
  {
    icon: FileCheck2,
    title: 'Contracts built in',
    body: 'E-sign generated contracts with usage rights, exclusivity, and revision limits spelled out.',
  },
  {
    icon: ShieldCheck,
    title: 'Escrow on every deal',
    body: 'Funds lock before work starts and release on approval. Nobody chases payments.',
  },
  {
    icon: Wallet,
    title: 'Clean payouts',
    body: 'TDS handled, invoices generated, UPI or bank transfer — creators see gross → net clearly.',
  },
  {
    icon: Zap,
    title: 'Hype Campaigns',
    body: '72-hour blitzes where 100 creators remix one reel at a flat rate. One-tap accept.',
    hype: true,
  },
];

export default function LandingPage() {
  return (
    <div className="min-h-screen bg-background text-foreground">
      {/* Nav */}
      <header className="sticky top-0 z-40 border-b border-border/60 bg-background/80 backdrop-blur">
        <nav className="mx-auto flex h-16 max-w-6xl items-center justify-between px-6" aria-label="Main">
          <Link to="/" aria-label="Influora home">
            <InfluoraLogo />
          </Link>
          <div className="flex items-center gap-2">
            <Button variant="ghost" asChild>
              <Link to="/creator/login">I'm a creator</Link>
            </Button>
            <Button asChild>
              <Link to="/brand/login">
                For brands <ArrowRight className="ml-1 h-4 w-4" aria-hidden="true" />
              </Link>
            </Button>
          </div>
        </nav>
      </header>

      <main>
        {/* Hero */}
        <section className="relative overflow-hidden">
          <div className="mx-auto grid max-w-6xl items-center gap-10 px-6 py-16 lg:grid-cols-2 lg:py-24">
            <div>
              <FadeUp>
                <Badge variant="outline" className="gap-1.5">
                  <ShieldCheck className="h-3 w-3" aria-hidden="true" />
                  Escrow-protected influencer deals
                </Badge>
              </FadeUp>
              <WordReveal
                text={HERO.headline}
                className="mt-4 text-4xl font-bold leading-tight tracking-tight sm:text-5xl"
              />
              <FadeUp delay={0.3}>
                <p className="mt-4 max-w-lg text-lg text-muted-foreground">{HERO.sub}</p>
              </FadeUp>
              <FadeUp delay={0.4} className="mt-8 flex flex-wrap gap-3">
                <Button size="lg" asChild>
                  <Link to="/brand/register">
                    Launch a campaign <ArrowRight className="ml-1.5 h-4 w-4" aria-hidden="true" />
                  </Link>
                </Button>
                <Button size="lg" variant="outline" asChild>
                  <Link to="/creator/register">Join as a creator</Link>
                </Button>
              </FadeUp>
              <FadeUp delay={0.5} className="mt-10 grid grid-cols-3 gap-6">
                {STATS.map((stat) => (
                  <div key={stat.label}>
                    <p className="text-2xl font-bold">
                      <CountUp value={stat.value} formatFn={stat.format} viewMargin="0px" />
                    </p>
                    <p className="mt-0.5 text-xs text-muted-foreground">{stat.label}</p>
                  </div>
                ))}
              </FadeUp>
            </div>
            <div className="relative h-[360px] lg:h-[460px]">
              <Suspense fallback={<CanvasFallback variant="portfolio" className="min-h-[320px]" />}>
                <HeroGlobeGate />
              </Suspense>
            </div>
          </div>
        </section>

        {/* Features */}
        <section className="border-t border-border/60 bg-card/50 py-20" aria-label="Platform features">
          <div className="mx-auto max-w-6xl px-6">
            <FadeUp className="mx-auto max-w-xl text-center">
              <h2 className="text-3xl font-semibold">Everything between the DM and the payout</h2>
              <p className="mt-3 text-muted-foreground">
                Influora replaces the spreadsheet-WhatsApp-invoice mess with one flow both sides trust.
              </p>
            </FadeUp>
            <StaggerContainer className="mt-12 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
              {FEATURES.map((feature) => {
                const Icon = feature.icon;
                return (
                  <StaggerItem key={feature.title}>
                    <Card className={feature.hype ? 'h-full border-hype-border hype-glow' : 'h-full'}>
                      <CardContent className="p-6">
                        <span
                          className={
                            feature.hype
                              ? 'flex h-10 w-10 items-center justify-center rounded-lg bg-hype text-hype-foreground'
                              : 'flex h-10 w-10 items-center justify-center rounded-lg bg-accent text-accent-foreground'
                          }
                        >
                          <Icon className="h-5 w-5" aria-hidden="true" />
                        </span>
                        <h3 className="mt-4 font-semibold">{feature.title}</h3>
                        <p className="mt-1.5 text-sm text-muted-foreground">{feature.body}</p>
                      </CardContent>
                    </Card>
                  </StaggerItem>
                );
              })}
            </StaggerContainer>
          </div>
        </section>

        {/* Escrow scroll story */}
        <EscrowFlowAnimation className="border-t border-border/60" />

        {/* Hype spotlight */}
        <section className="border-t border-border/60 bg-card/50 py-20" aria-label="Hype Campaigns">
          <div className="mx-auto grid max-w-6xl items-center gap-10 px-6 lg:grid-cols-2">
            <FadeUp>
              <Badge className="gap-1 border-hype-border bg-hype text-hype-foreground hover:bg-hype">
                <Zap className="h-3 w-3" aria-hidden="true" /> Hype Campaigns
              </Badge>
              <h2 className="mt-3 text-3xl font-semibold leading-tight">
                100 creators. One sound. 72 hours.
              </h2>
              <p className="mt-3 text-muted-foreground">
                Drop a source reel, set a flat per-reel rate and a slot cap. Creators accept with one
                tap and post before the window closes — escrow pays each reel automatically.
              </p>
              <Button size="lg" className="mt-6 bg-hype-solid text-white hover:bg-hype-solid/90" asChild>
                <Link to="/brand/register">
                  Launch a Hype Campaign <Zap className="ml-1.5 h-4 w-4" aria-hidden="true" />
                </Link>
              </Button>
            </FadeUp>
            <FadeUp delay={0.15}>
              <Card className="border-hype-border hype-glow">
                <CardContent className="p-6">
                  <div className="flex items-center justify-between">
                    <p className="font-semibold">{demoHypeConfig.hashtag}</p>
                    <HypeLiveIndicator hoursLeft={41} />
                  </div>
                  <p className="mt-1 text-sm text-muted-foreground">
                    ₹{demoHypeConfig.perReelRate.toLocaleString('en-IN')} per reel · flat rate
                  </p>
                  <SlotProgressBar
                    filled={demoHypeConfig.slotsFilled}
                    total={demoHypeConfig.slotCap}
                    className="mt-5"
                  />
                  <div className="mt-4 flex flex-wrap gap-1.5">
                    {demoHypeConfig.formatLanes.map((lane) => (
                      <Badge key={lane} variant="outline" className="text-[10px] font-normal">
                        {lane}
                      </Badge>
                    ))}
                  </div>
                </CardContent>
              </Card>
            </FadeUp>
          </div>
        </section>

        {/* Final CTA */}
        <section className="py-20" aria-label="Get started">
          <FadeUp className="mx-auto max-w-2xl px-6 text-center">
            <h2 className="text-3xl font-semibold">Sign your next deal on Influora</h2>
            <p className="mt-3 text-muted-foreground">
              Free to start. Pay only when a deal goes through escrow.
            </p>
            <div className="mt-8 flex flex-wrap justify-center gap-3">
              <Button size="lg" asChild>
                <Link to="/brand/register">Create a brand account</Link>
              </Button>
              <Button size="lg" variant="outline" asChild>
                <Link to="/creator/register">Create a creator account</Link>
              </Button>
            </div>
          </FadeUp>
        </section>
      </main>

      <footer className="border-t border-border/60 py-10">
        <div className="mx-auto flex max-w-6xl flex-col items-center justify-between gap-4 px-6 sm:flex-row">
          <InfluoraLogo size="sm" />
          <nav className="flex gap-6 text-sm text-muted-foreground" aria-label="Footer">
            <Link to="/terms" className="hover:text-foreground">Terms</Link>
            <Link to="/privacy" className="hover:text-foreground">Privacy</Link>
            <Link to="/support" className="hover:text-foreground">Support</Link>
          </nav>
          <p className="text-xs text-muted-foreground">© 2026 Influora</p>
        </div>
      </footer>
    </div>
  );
}
