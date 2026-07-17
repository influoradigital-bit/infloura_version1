import { Link } from 'react-router-dom';
import {
  ArrowRight,
  BadgeCheck,
  FileCheck2,
  MessageSquareText,
  MousePointerClick,
  Search,
  ShieldCheck,
  Sparkles,
  Ticket,
  TrendingUp,
  Wallet,
  Zap,
} from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent } from '@/components/ui/card';
import { lazy, Suspense } from 'react';
import { CanvasFallback } from '@/components/3d/CanvasFallback';
import { SiteHeader } from '@/components/site/SiteHeader';
import { SiteFooter } from '@/components/site/SiteFooter';
import { Seo } from '@/lib/seo/Seo';
import { JsonLd, getOrganizationSchema, getWebsiteSchema } from '@/lib/seo/schema';

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

// Meera — claims verified against influora-ai + MeeraSessionService: she suggests,
// drafts, and proposes; a human confirms every money step. Never claim autonomy.
const MEERA = {
  headline: 'Meet Meera, your AI campaign co-pilot',
  sub: 'Every brand workspace comes with Meera built in. Describe your product and goal in plain language — she does the campaign math with you.',
  points: [
    { icon: Search, text: 'Suggests matching creators by niche, city, and engagement' },
    { icon: TrendingUp, text: 'Works out a budget and per-reel rate from your goal' },
    { icon: FileCheck2, text: 'Drafts the campaign so you launch in minutes, not days' },
    { icon: ShieldCheck, text: 'Proposes escrow funding — you confirm every money step' },
  ],
};

// Condensed from the 6-step flow on /features/hype — a real sequence, so the
// numbers carry information (order matters).
const HYPE_STEPS = [
  { step: '1', text: 'Drop one source reel and set a flat per-reel rate' },
  { step: '2', text: 'Creators accept slots with one tap — no negotiation' },
  { step: '3', text: 'Escrow auto-funds each slot as it fills' },
  { step: '4', text: 'Verified posts auto-release payouts inside the 72-hour window' },
];

// Tracking claims verified against CampaignTrackingController + conversion
// webhooks: measurement starts at the click — never claim impressions/funnel.
const TRACKING = {
  headline: 'Know exactly what every deal sold',
  sub: 'Each creator gets a unique tracking link and coupon code. Your store reports orders back over a signed webhook, so the scoreboard shows real transactions — not screenshots or estimates.',
  scoreboard: [
    { icon: MousePointerClick, label: 'Link clicks', value: '4,812' },
    { icon: TrendingUp, label: 'Conversions', value: '231' },
    { icon: Ticket, label: 'Coupon redemptions', value: '187' },
    { icon: Wallet, label: 'Revenue attributed', value: '₹3.4L' },
  ],
};

// Portfolio claims verified against PortfolioService: live stats + campaign
// history are real; do NOT claim PDF media kit or page-view analytics yet.
const PORTFOLIO = {
  headline: 'Every creator gets a page brands can trust',
  sub: 'A public profile at influora.com/@yourhandle — built from synced platform stats and real campaign history, not self-reported numbers.',
  points: [
    'Verified follower and engagement stats, synced from your platforms',
    'Past brand collabs with ratings and on-time delivery record',
    'Your rate card — public, brands-only, or hidden. Your call.',
    'A hire button that drops brands straight into a Deal Room',
  ],
};

export default function LandingPage() {
  return (
    <div className="min-h-screen bg-background text-foreground">
      <Seo
        title="Influora — Escrow-protected influencer deals for India"
        description="Discover verified creators, negotiate in one Deal Room, and pay through escrow. From a single reel to a 100-creator Hype campaign — contracts, TDS and payouts built in."
        canonical="/"
      />
      <JsonLd data={getOrganizationSchema()} />
      <JsonLd data={getWebsiteSchema()} />

      <SiteHeader />

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

        {/* Meera AI co-pilot */}
        <section className="border-t border-border/60 py-20" aria-label="Meera AI co-pilot">
          <div className="mx-auto grid max-w-6xl items-center gap-10 px-6 lg:grid-cols-2">
            <FadeUp>
              <Badge variant="outline" className="gap-1.5">
                <Sparkles className="h-3 w-3" aria-hidden="true" />
                Built-in AI
              </Badge>
              <h2 className="mt-3 text-3xl font-semibold leading-tight">{MEERA.headline}</h2>
              <p className="mt-3 text-muted-foreground">{MEERA.sub}</p>
              <ul className="mt-6 space-y-3">
                {MEERA.points.map((point) => {
                  const Icon = point.icon;
                  return (
                    <li key={point.text} className="flex items-start gap-3">
                      <span className="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-md bg-accent text-accent-foreground">
                        <Icon className="h-3.5 w-3.5" aria-hidden="true" />
                      </span>
                      <span className="text-sm">{point.text}</span>
                    </li>
                  );
                })}
              </ul>
              <Button size="lg" className="mt-7" asChild>
                <Link to="/brand/register">
                  Meet Meera in your dashboard <ArrowRight className="ml-1.5 h-4 w-4" aria-hidden="true" />
                </Link>
              </Button>
            </FadeUp>
            <FadeUp delay={0.15}>
              <Card>
                <CardContent className="space-y-4 p-6" aria-hidden="true">
                  <div className="ml-auto max-w-[85%] rounded-2xl rounded-br-sm bg-primary px-4 py-3 text-sm text-primary-foreground">
                    I'm launching a ₹799 face serum and want reels from skincare creators in Mumbai.
                  </div>
                  <div className="max-w-[90%] rounded-2xl rounded-bl-sm bg-muted px-4 py-3 text-sm">
                    For that price point I'd run a ₹40,000 pool at ₹800 per reel. I found 12
                    skincare creators in Mumbai with 3%+ engagement — want me to draft the
                    campaign so you can review it?
                  </div>
                  <div className="flex items-center gap-2 text-xs text-muted-foreground">
                    <Sparkles className="h-3.5 w-3.5" aria-hidden="true" />
                    Meera proposes. You approve. Money only moves on your confirmation.
                  </div>
                </CardContent>
              </Card>
            </FadeUp>
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
                When you need a trend, not a negotiation: one reel, remixed by a fleet of creators
                inside 72 hours, at one flat rate. Here's the whole flow:
              </p>
              <ol className="mt-5 space-y-3">
                {HYPE_STEPS.map((item) => (
                  <li key={item.step} className="flex items-start gap-3">
                    <span className="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-hype text-xs font-semibold text-hype-foreground">
                      {item.step}
                    </span>
                    <span className="text-sm">{item.text}</span>
                  </li>
                ))}
              </ol>
              <div className="mt-7 flex flex-wrap items-center gap-4">
                <Button size="lg" className="bg-hype-solid text-white hover:bg-hype-solid/90" asChild>
                  <Link to="/brand/register">
                    Launch a Hype Campaign <Zap className="ml-1.5 h-4 w-4" aria-hidden="true" />
                  </Link>
                </Button>
                <Link
                  to="/features/hype"
                  className="text-sm font-medium text-accent-foreground hover:underline"
                >
                  See exactly how it runs
                </Link>
              </div>
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

        {/* Sales tracking */}
        <section className="border-t border-border/60 py-20" aria-label="Sales tracking">
          <div className="mx-auto grid max-w-6xl items-center gap-10 px-6 lg:grid-cols-2">
            <FadeUp className="lg:order-2">
              <Badge variant="outline" className="gap-1.5">
                <MousePointerClick className="h-3 w-3" aria-hidden="true" />
                Sales tracking
              </Badge>
              <h2 className="mt-3 text-3xl font-semibold leading-tight">{TRACKING.headline}</h2>
              <p className="mt-3 text-muted-foreground">{TRACKING.sub}</p>
              <p className="mt-3 text-sm text-muted-foreground">
                Broken down per creator link and per coupon — so you know which creators actually
                drove sales, not just likes.
              </p>
            </FadeUp>
            <FadeUp delay={0.15} className="lg:order-1">
              <Card>
                <CardContent className="p-6" aria-hidden="true">
                  <div className="flex items-center justify-between">
                    <p className="font-semibold">Campaign scoreboard</p>
                    <Badge variant="outline" className="text-[10px] font-normal">Live</Badge>
                  </div>
                  <ul className="mt-4 divide-y divide-border/60">
                    {TRACKING.scoreboard.map((row) => {
                      const Icon = row.icon;
                      return (
                        <li key={row.label} className="flex items-center justify-between py-3">
                          <span className="flex items-center gap-2.5 text-sm text-muted-foreground">
                            <Icon className="h-4 w-4" aria-hidden="true" />
                            {row.label}
                          </span>
                          <span className="font-semibold tabular-nums">{row.value}</span>
                        </li>
                      );
                    })}
                  </ul>
                  <p className="mt-3 text-xs text-muted-foreground">
                    Illustrative numbers. Yours come from your own store's webhook.
                  </p>
                </CardContent>
              </Card>
            </FadeUp>
          </div>
        </section>

        {/* Creator portfolio */}
        <section className="border-t border-border/60 bg-card/50 py-20" aria-label="Creator portfolios">
          <div className="mx-auto grid max-w-6xl items-center gap-10 px-6 lg:grid-cols-2">
            <FadeUp>
              <Badge variant="outline" className="gap-1.5">
                <BadgeCheck className="h-3 w-3" aria-hidden="true" />
                For creators
              </Badge>
              <h2 className="mt-3 text-3xl font-semibold leading-tight">{PORTFOLIO.headline}</h2>
              <p className="mt-3 text-muted-foreground">{PORTFOLIO.sub}</p>
              <ul className="mt-6 space-y-3">
                {PORTFOLIO.points.map((point) => (
                  <li key={point} className="flex items-start gap-3">
                    <BadgeCheck className="mt-0.5 h-4 w-4 shrink-0 text-accent-foreground" aria-hidden="true" />
                    <span className="text-sm">{point}</span>
                  </li>
                ))}
              </ul>
              <Button size="lg" variant="outline" className="mt-7" asChild>
                <Link to="/creator/register">
                  Claim your handle <ArrowRight className="ml-1.5 h-4 w-4" aria-hidden="true" />
                </Link>
              </Button>
            </FadeUp>
            <FadeUp delay={0.15}>
              <Card>
                <CardContent className="p-6" aria-hidden="true">
                  <p className="text-xs text-muted-foreground">influora.com/<span className="font-medium text-foreground">@ananya.creates</span></p>
                  <div className="mt-4 flex items-center gap-3">
                    <span className="flex h-12 w-12 items-center justify-center rounded-full bg-accent text-lg font-semibold text-accent-foreground">
                      A
                    </span>
                    <div>
                      <p className="flex items-center gap-1.5 font-semibold">
                        Ananya <BadgeCheck className="h-4 w-4 text-accent-foreground" aria-hidden="true" />
                      </p>
                      <p className="text-xs text-muted-foreground">Skincare · Mumbai · 218K followers</p>
                    </div>
                  </div>
                  <div className="mt-5 grid grid-cols-3 gap-3 text-center">
                    <div className="rounded-lg bg-muted px-2 py-3">
                      <p className="text-sm font-semibold">47</p>
                      <p className="mt-0.5 text-[10px] text-muted-foreground">Brand collabs</p>
                    </div>
                    <div className="rounded-lg bg-muted px-2 py-3">
                      <p className="text-sm font-semibold">4.9★</p>
                      <p className="mt-0.5 text-[10px] text-muted-foreground">Avg rating</p>
                    </div>
                    <div className="rounded-lg bg-muted px-2 py-3">
                      <p className="text-sm font-semibold">98%</p>
                      <p className="mt-0.5 text-[10px] text-muted-foreground">On-time delivery</p>
                    </div>
                  </div>
                  <div className="mt-4 rounded-lg border border-border/60 px-4 py-3 text-center text-sm font-medium text-accent-foreground">
                    Invite to Campaign
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

      <SiteFooter />
    </div>
  );
}
