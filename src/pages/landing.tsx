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
import { FaqSection } from '@/components/site/FaqSection';
import { FunnelCta } from '@/components/site/FunnelCta';
import { TrustBar } from '@/components/site/TrustBar';
import { StickyCta, StickyCtaSpacer } from '@/components/site/StickyCta';
import { Seo } from '@/lib/seo/Seo';
import {
  JsonLd,
  getOrganizationSchema,
  getWebsiteSchema,
  getSoftwareApplicationSchema,
  getWebPageSchema,
} from '@/lib/seo/schema';

// Lazy — keeps three.js out of the critical bundle (Lighthouse mobile ≥ 85)
const HeroGlobeGate = lazy(() =>
  import('@/components/3d/HeroGlobe').then((m) => ({ default: m.HeroGlobeGate })),
);
import { FadeUp, StaggerContainer, StaggerItem, WordReveal, CountUp } from '@/components/motion';
import { PaymentFlowAnimation } from '@/components/motion/PaymentFlowAnimation';
import { HypeLiveIndicator } from '@/components/ui/hype-live-indicator';
import { SlotProgressBar } from '@/components/ui/slot-progress-bar';
import { MeeraOrb } from '@/components/feature/meera/MeeraOrb';
import { demoHypeConfig } from '@/lib/demo-data';

// ---------------------------------------------------------------------------
// Content — single source of truth for landing copy
// ---------------------------------------------------------------------------

const HERO = {
  headline: 'Where brands and creators sign real deals',
  sub: 'Discover creators, negotiate in one Deal Room, and pay with protection built in — from a single reel to a 100-creator Hype blitz.',
};

/**
 * Homepage FAQ.
 *
 * These are not "questions we wish people asked" — each one is a high-intent
 * query an Indian brand actually types before it picks a platform, phrased the
 * way they phrase it. That matters twice over: it is what a searcher scans for
 * before converting, and it is the literal string an answer engine matches a
 * prompt against. Answers are written to stand alone (no "it", no "we", no
 * dependency on the paragraph above) because ChatGPT, Perplexity and AI
 * Overviews quote the answer detached from both the question and the page.
 *
 * Every claim below is one the product actually implements — see
 * wiki/website/content-map.md and the feature pages each answer links to. Do not
 * add an answer here that the app cannot back up; an answer engine that cites us
 * and is then contradicted by the product is worse than not being cited.
 */
const FAQS = [
  {
    question: 'How do brands find influencers in India on Influora?',
    answer:
      'Brands search Influora\u2019s creator directory by niche, city, follower count and engagement rate, with every profile\u2019s stats synced from the creator\u2019s connected Instagram account rather than self-reported. A brand can invite any creator straight into a Deal Room, or post a campaign brief and let creators apply to it.',
  },
  {
    question: 'How much does influencer marketing cost in India?',
    answer:
      'Rates on Influora are set by each creator and shown on their profile as a rate card, so a brand sees the price before it starts a conversation. Nano and micro creators commonly price a single reel in the low thousands of rupees, while larger accounts price higher; Influora itself charges no subscription on the Free tier and takes a platform fee only when a deal completes.',
  },
  {
    question: 'How do I pay an influencer safely?',
    answer:
      'The safe pattern is to never pay in full up front and never expect a creator to work with nothing secured. On Influora the brand deposits the deal amount with a licensed payment partner when the contract is e-signed, the creator delivers, the brand approves, and only then does the payment release \u2014 so neither side is ever exposed to the other.',
  },
  {
    question: 'What is a Deal Room?',
    answer:
      'A Deal Room is the single shared workspace on Influora where one brand and one creator settle a collaboration: the chat, the proposal and counter-offers, the deliverables list, the e-signed contract and the payment all live in that one thread. It replaces a negotiation spread across WhatsApp, email and a separate invoice.',
  },
  {
    question: 'Do creators pay to join Influora?',
    answer:
      'No. Creating a creator account, building a portfolio page, applying to campaigns and accepting Hype Campaign slots are all free. Influora takes a commission only when a deal actually pays out, so a creator who earns nothing pays nothing.',
  },
  {
    question: 'How long does it take to launch a campaign?',
    answer:
      'A brand can create an account, build a brief and send its first creator invites the same day \u2014 Meera, the built-in AI co-pilot, drafts the campaign from a plain-language description of the product and goal. The slower step is usually creator response time, not setup.',
  },
];

const STATS = [
  { label: 'Creators on platform', value: 8915, format: (n: number) => `${Math.round(n).toLocaleString('en-IN')}+` },
  { label: 'Paid out to creators', value: 42600000, format: (n: number) => `₹${(n / 10000000).toFixed(1)}Cr+` },
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
    title: 'Protection on every deal',
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
    { icon: ShieldCheck, text: 'Proposes the funding step — you confirm every money step' },
  ],
};

// Condensed from the 6-step flow on /features/hype — a real sequence, so the
// numbers carry information (order matters).
const HYPE_STEPS = [
  { step: '1', text: 'Drop one source reel and set a flat per-reel rate' },
  { step: '2', text: 'Creators accept slots with one tap — no negotiation' },
  { step: '3', text: 'Funding locks automatically each slot as it fills' },
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
  // Per-creator breakdown — the answer to "which creator actually sold".
  topCreators: [
    { handle: '@ananya.creates', revenue: '₹1.2L' },
    { handle: '@rohan.fits', revenue: '₹86K' },
    { handle: '@meghna.eats', revenue: '₹54K' },
  ],
};

// Creator income streams — all three verified as built (deal room, Hype slot
// accept, affiliate earnings UI + settlement backend shipped 2026-07-17).
const CREATOR_EARNINGS = [
  {
    icon: MessageSquareText,
    title: 'Brand deals',
    body: "Negotiate your rate in the Deal Room. The brand's payment is locked in full before you shoot a single frame.",
  },
  {
    icon: Zap,
    title: 'Hype slots',
    body: 'One-tap accept on flat-rate remix campaigns. Post inside the window, payout auto-releases.',
  },
  {
    icon: Ticket,
    title: 'Affiliate commission',
    body: 'Your coupon code and tracking link earn commission on every sale they drive — settled monthly.',
  },
];

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
        title="Influencer Marketing Platform for India | Influora"
        description="Hire verified Indian creators, agree terms in one Deal Room, and pay only after the work is approved. Contracts, TDS and payment protection built in. Free to start."
        canonical="/"
      />
      <JsonLd data={getOrganizationSchema()} />
      <JsonLd data={getWebsiteSchema()} />
      <JsonLd
        data={getWebPageSchema({
          name: 'Influencer Marketing Platform for India',
          description:
            'Influora is an influencer marketing platform for India where brands hire verified creators, agree terms in a Deal Room, and pay only after approving the delivered work.',
          url: '/',
        })}
      />
      {/*
        SoftwareApplication + AggregateOffer. "What does it cost" is the single
        most-asked commercial query in this category, and without a priced offer
        an answer engine either omits us from a cost comparison or invents a
        number. The prices here must stay in step with /pricing — see the
        PRICING_OFFERS constant there, which is the same data.
      */}
      <JsonLd
        data={getSoftwareApplicationSchema({
          description:
            'Influencer marketing platform for Indian brands and creators, with a creator directory, Deal Room negotiation, auto-generated contracts, protected payments and campaign sales tracking.',
          featureList: [
            'Verified creator discovery',
            'Deal Room negotiation',
            'Auto-generated e-signed contracts',
            'Protected payments released on approval',
            'TDS handling and invoice generation',
            'Hype multi-creator campaigns',
            'Per-creator sales and coupon tracking',
          ],
          offers: [
            {
              name: 'Free',
              price: 0,
              description: 'No subscription. Pay a platform fee only when a deal completes.',
            },
            {
              name: 'Pro',
              price: 4999,
              billingPeriod: 'MON',
              description: 'Lower per-deal fee, 5 seats, unlimited creator analytics.',
            },
          ],
        })}
      />

      <SiteHeader />

      <main>
        {/* Hero */}
        <section className="relative overflow-hidden">
          <div className="mx-auto grid max-w-6xl items-center gap-10 px-6 py-16 lg:grid-cols-2 lg:py-24">
            <div>
              <FadeUp>
                <Badge variant="outline" className="gap-1.5">
                  <ShieldCheck className="h-3 w-3" aria-hidden="true" />
                  Payment-protected influencer deals
                </Badge>
              </FadeUp>
              <WordReveal
                text={HERO.headline}
                className="mt-4 text-4xl font-bold leading-tight tracking-tight sm:text-5xl"
              />
              <FadeUp delay={0.3}>
                <p className="mt-4 max-w-lg text-lg text-muted-foreground" data-speakable>
                  {HERO.sub}
                </p>
              </FadeUp>
              {/*
                CRO — single-intent hero.

                This used to be two `size="lg"` buttons side by side: "Launch a
                campaign" (brand) next to "Join as a creator" (creator). Two
                audiences, two destinations, near-equal visual weight, at the one
                point on the site where intent is highest. A visitor who arrived
                ready to act was handed a decision instead of an action.

                The brand path is now the only button, because that is the side
                that pays and the side the rest of this page is written for. The
                creator path is not removed — a creator who lands here still has a
                one-tap route directly under it, plus "I'm a creator" in the
                header and a full creator section further down. It simply stops
                competing with the primary action for the same click.
              */}
              <FadeUp delay={0.4} className="mt-8">
                <Button size="lg" asChild>
                  <Link to="/brand/register">
                    Launch a campaign <ArrowRight className="ml-1.5 h-4 w-4" aria-hidden="true" />
                  </Link>
                </Button>
                <p className="mt-3 text-sm text-muted-foreground">
                  Free to start · no subscription ·{' '}
                  <Link
                    to="/creator/register"
                    className="font-medium text-primary hover:underline"
                  >
                    I'm a creator
                  </Link>
                </p>
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

        <TrustBar />

        {/* Features */}
        <section className="bg-card/50 py-20" aria-label="Platform features">
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
                  <div className="flex items-center gap-3">
                    <MeeraOrb state="idle" size={40} />
                    <div>
                      <p className="text-sm font-semibold">Meera</p>
                      <p className="text-xs text-muted-foreground">AI campaign co-pilot · online</p>
                    </div>
                  </div>
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

        {/* Payment-protection scroll story */}
        <PaymentFlowAnimation className="border-t border-border/60" />

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
                  <div className="mt-4 rounded-lg bg-muted/60 p-3">
                    <p className="text-xs font-semibold text-muted-foreground">Top creators by revenue</p>
                    <ul className="mt-2 space-y-1.5">
                      {TRACKING.topCreators.map((creator) => (
                        <li key={creator.handle} className="flex items-center justify-between text-sm">
                          <span className="text-muted-foreground">{creator.handle}</span>
                          <span className="font-semibold tabular-nums">{creator.revenue}</span>
                        </li>
                      ))}
                    </ul>
                  </div>
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

        {/* Creator earnings — three income streams, one protected rail */}
        <section className="border-t border-border/60 py-20" aria-label="How creators earn">
          <div className="mx-auto max-w-6xl px-6">
            <FadeUp className="mx-auto max-w-xl text-center">
              <h2 className="text-3xl font-semibold">Creators earn three ways</h2>
              <p className="mt-3 text-muted-foreground">
                Every stream pays through the same protected rail — funds lock before you start, payout lands
                after approval, TDS invoice generated for you.
              </p>
            </FadeUp>
            <StaggerContainer className="mt-12 grid gap-8 md:grid-cols-3 md:gap-0 md:divide-x md:divide-border/60">
              {CREATOR_EARNINGS.map((stream) => {
                const Icon = stream.icon;
                return (
                  <StaggerItem key={stream.title} className="md:px-8">
                    <span className="flex h-10 w-10 items-center justify-center rounded-lg bg-accent text-accent-foreground">
                      <Icon className="h-5 w-5" aria-hidden="true" />
                    </span>
                    <h3 className="mt-4 font-semibold">{stream.title}</h3>
                    <p className="mt-1.5 text-sm text-muted-foreground">{stream.body}</p>
                  </StaggerItem>
                );
              })}
            </StaggerContainer>
            <FadeUp delay={0.2} className="mt-10 text-center">
              <Button size="lg" variant="outline" asChild>
                <Link to="/creator/register">
                  Start earning on Influora <ArrowRight className="ml-1.5 h-4 w-4" aria-hidden="true" />
                </Link>
              </Button>
            </FadeUp>
          </div>
        </section>

        {/* Pricing strip — teaser only; full detail lives at /pricing */}
        <section className="border-t border-border/60 bg-card/50 py-16" aria-label="Pricing summary">
          <div className="mx-auto max-w-4xl px-6 text-center">
            <FadeUp>
              <h2 className="text-2xl font-semibold">Free to start. Pay when deals close.</h2>
              <div className="mt-8 grid gap-6 sm:grid-cols-3">
                <div>
                  <p className="text-2xl font-bold">₹0</p>
                  <p className="mt-1 text-sm text-muted-foreground">
                    Brand Free tier — no subscription, payment protection and contracts included
                  </p>
                </div>
                <div>
                  <p className="text-2xl font-bold">₹4,999<span className="text-sm font-normal text-muted-foreground">/mo</span></p>
                  <p className="mt-1 text-sm text-muted-foreground">
                    Brand Pro — lower fees, 5 seats, unlimited creator analytics
                  </p>
                </div>
                <div>
                  <p className="text-2xl font-bold">Free</p>
                  <p className="mt-1 text-sm text-muted-foreground">
                    Creators always — commission only when a deal pays out
                  </p>
                </div>
              </div>
              <Button size="lg" variant="outline" className="mt-8" asChild>
                <Link to="/pricing">
                  See full pricing <ArrowRight className="ml-1.5 h-4 w-4" aria-hidden="true" />
                </Link>
              </Button>
            </FadeUp>
          </div>
        </section>

        {/*
          FAQ sits between the last proof section and the close on purpose: it is
          the last-objection handler. It also carries the homepage's FAQPage
          JSON-LD, which is the block most likely to be lifted into an AI Overview
          for the queries above.
        */}
        <FaqSection
          heading="Influencer marketing in India, answered"
          intro="The questions brands and creators ask before they pick a platform."
          items={FAQS}
        />

        <FunnelCta
          heading="Sign your next deal on Influora"
          sub="Create a brand account, post a brief, and have your first creator invites out today."
          primary={{ label: 'Create a brand account', to: '/brand/register' }}
          secondary={{ label: "I'm a creator — create a creator account", to: '/creator/register' }}
          reassurances={['Free to start', 'No subscription', 'Pay only when a deal completes']}
          className="py-20"
        />
      </main>

      <SiteFooter />
      <StickyCta label="Launch a campaign" to="/brand/register" note="Free to start" />
      <StickyCtaSpacer />
    </div>
  );
}
