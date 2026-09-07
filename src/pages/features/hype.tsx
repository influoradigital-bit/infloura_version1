import { Link } from 'react-router-dom';
import {
  Clapperboard,
  Layers,
  Lock,
  PartyPopper,
  ShieldCheck,
  ShoppingBag,
  TrendingUp,
  UploadCloud,
  Zap,
} from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent } from '@/components/ui/card';
import { FadeUp, StaggerContainer, StaggerItem } from '@/components/motion';
import { HypeLiveIndicator } from '@/components/ui/hype-live-indicator';
import { SlotProgressBar } from '@/components/ui/slot-progress-bar';
import { demoHypeConfig } from '@/lib/demo-data';
import { SiteHeader } from '@/components/site/SiteHeader';
import { SiteFooter } from '@/components/site/SiteFooter';
import { FaqSection } from '@/components/site/FaqSection';
import { FunnelCta } from '@/components/site/FunnelCta';
import { StickyCta, StickyCtaSpacer } from '@/components/site/StickyCta';
import { Seo } from '@/lib/seo/Seo';
import {
  JsonLd,
  getBreadcrumbListSchema,
  getQaPageSchema,
  getWebPageSchema,
} from '@/lib/seo/schema';

/*
  Layout ported from the Stitch design "72-Hour Hype Blitzes". Their four-hour
  execution timeline, the old-way/new-way lifestyle split (for brand teams and for
  creators), and the live command-console mock are kept; the palette is ours, and
  every claim has been rewritten to something the product actually does. Content
  per wiki/website/content-map.md §2.3 + homepage-copy.md §6.

  What was deliberately NOT carried over from the design, and why:
    - "escrow", "RBI-regulated Protected Settlement Vault", "Scheduled Bank
      Trustee", "RBI Licensed Escrow Partner". "escrow" is banned in user copy
      (2026-09-02); our partner is a licensed, RBI-authorized Payment Aggregator,
      Influora is not a trustee.
    - "Bank-Grade 256-Bit Rails". No security/certification claims about Influora.
    - "3.8x Velocity", "+340% GMV", "2.4M Views", "100% Payout Rate", "300%
      production quality spike", "120+ Hours Saved". None of this is measured —
      campaign performance on Influora is creator-reported, not measured by us.
    - "< 12 Hours" / "12h Fast Review Auto-Unlock" and "48-hour auto-release to a
      backup creator pool". No enforced SLA or backup-creator reassignment exists;
      live copy says slots that go unfilled or unposted are simply not paid out.
    - "Automated Form 16A / Section 194J TDS deduction in real time". TDS is
      recorded and shown on the payout — not deducted or filed automatically.
    - Named people ("Aditya Kashyap", "Pooja Sharma", "@riya.lifestyle",
      "@rohit.fits", "@tanvi.beauty") and real third-party brands ("Bombay Shaving
      Co", "Urban Botanics", "PureSkin Labs"). Fabricated people and companies.
    - Fixed package pricing (₹1,25,000 / ₹2,50,000 / ₹4,80,000 tiers). Influora
      does not publish a brand-side platform fee or fixed package pricing — see
      /pricing for the real plan structure.

  Every mock/illustrative panel below carries a visible caption, same rule the
  homepage Deal Room card and the how-it-works pages follow.
*/

const HERO_STATS = [
  { value: 'Up to 100', label: 'Creators can accept a slot' },
  { value: '72 hours', label: 'From launch to the posting window closing' },
  { value: 'One tap', label: 'Creators accept — no negotiation' },
  { value: 'Capped', label: 'Maximum spend is fixed before you launch' },
] as const;

const USE_CASES = [
  { icon: Clapperboard, label: 'Movie launches' },
  { icon: ShoppingBag, label: 'Product drops' },
  { icon: PartyPopper, label: 'Festival campaigns' },
  { icon: TrendingUp, label: 'Trend-jacking viral moments' },
];

const HOW_IT_WORKS = [
  {
    step: '01',
    title: 'Brand sets up the blitz',
    body: 'Upload one source reel or audio, set a flat per-reel rate, and cap the number of slots. Maximum spend is fixed before launch: rate multiplied by slot cap.',
  },
  {
    step: '02',
    title: 'The 72-hour clock starts',
    body: 'A live countdown goes up the moment the Hype Campaign launches.',
  },
  {
    step: '03',
    title: 'Creators one-tap accept',
    body: 'No negotiation, no back-and-forth — creators browse open Hype Campaigns and accept a slot instantly.',
  },
  {
    step: '04',
    title: 'Funding locks automatically per slot',
    body: 'Each accepted slot is funded and payment-protected automatically, so every creator who accepts is guaranteed the rate if they deliver.',
  },
  {
    step: '05',
    title: 'Post before the window closes',
    body: 'Creators film and post within the 72-hour window using the source reel or audio.',
  },
  {
    step: '06',
    title: 'Payout on verification',
    body: "Once a post is verified, that creator's payout releases automatically — no manual tracking across 100 creators.",
  },
] as const;

const BRAND_OLD_WAY = [
  'Juggling spreadsheets and chasing 50 creators over DMs, one at a time',
  'Reviewing staggered, off-brief drafts that arrive on no fixed schedule',
  'Manually handling bank details, invoices and TDS for every single creator',
  'Paying an advance with no guarantee the content ever gets posted',
] as const;

const BRAND_NEW_WAY = [
  'One flat rate and one brief, posted once — creators self-select into slots',
  'A fixed maximum spend, known before you launch',
  'Every accepted slot is funded and payment-protected automatically',
  'Payouts release per creator on verification — no manual tracking across 100 people',
] as const;

const CREATOR_OLD_WAY = [
  'Sending cold pitches and waiting weeks for a brand to even reply',
  'Vague revision requests with no pay guarantee attached',
  'Chasing an accounts team for months to release a small fee',
] as const;

const CREATOR_NEW_WAY = [
  'Browse open Hype Campaigns and accept a slot with one tap — no pitching',
  'The rate is fixed and visible before you accept, nothing to negotiate',
  'Post within the window, and the payout is protected as soon as your slot is confirmed',
] as const;

const CANONICAL_QUESTION = 'What is a Hype Campaign?';
const CANONICAL_ANSWER =
  'A Hype Campaign is a multi-creator campaign format where a brand posts one source reel at ' +
  'a single flat per-reel rate and caps the number of slots. Up to 100 creators accept a slot ' +
  'with one tap, with no negotiation, and post their own remix inside a 72-hour window. Each ' +
  'accepted slot is funded up front and paid out automatically once the post is verified.';

const FAQS = [
  { question: CANONICAL_QUESTION, answer: CANONICAL_ANSWER },
  {
    question: 'How is a Hype Campaign different from a normal influencer deal?',
    answer:
      'A normal deal is negotiated one-to-one: rate, scope and timeline are agreed per creator in a Deal Room. A Hype Campaign removes the negotiation entirely — the brand sets one flat rate and one brief, and creators either take a slot at that rate or they do not. It trades per-creator control for speed and volume.',
  },
  {
    question: 'What does a Hype Campaign cost a brand?',
    answer:
      'The brand chooses the per-reel rate and the number of slots, so the maximum spend is fixed before launch: rate multiplied by slot cap. Only slots that are actually accepted and delivered are paid for, so an under-filled campaign costs less than the cap rather than more.',
  },
  {
    question: 'What happens if a creator accepts a slot and does not post?',
    answer:
      'That slot is not paid out. Payment releases only against a verified post inside the campaign window, so an accepted-but-undelivered slot returns its funds to the brand rather than being lost.',
  },
  {
    question: 'Do brands get usage rights to the creator content?',
    answer:
      'Usage rights for a Hype Campaign are set in the campaign brief the same way they are for a standard deal, and are visible to a creator before they accept a slot — not assumed after the fact.',
  },
];

export default function HypeFeaturePage() {
  return (
    <div className="min-h-screen bg-background text-foreground">
      <Seo
        title="Hype Campaigns — 100 Creators, 72 Hours"
        description="Launch a 72-hour Hype Campaign: set a flat per-reel rate, cap the slots, and let up to 100 creators accept with one tap. Each reel is paid out automatically."
        canonical="/features/hype"
      />
      <JsonLd
        data={getWebPageSchema({
          name: 'Hype Campaigns — 100 creators, one sound, 72 hours',
          description: CANONICAL_ANSWER,
          url: '/features/hype',
        })}
      />
      <JsonLd
        data={getQaPageSchema({
          question: CANONICAL_QUESTION,
          answer: CANONICAL_ANSWER,
          url: '/features/hype',
        })}
      />
      <JsonLd
        data={getBreadcrumbListSchema([
          { name: 'Home', url: '/' },
          { name: 'Features', url: '/features/hype' },
          { name: 'Hype Campaigns', url: '/features/hype' },
        ])}
      />

      <SiteHeader />

      <main>
        {/* ---------------------------------------------------------------- Hero */}
        <section className="border-b border-hype-border/60 py-20">
          <div className="mx-auto grid max-w-6xl items-center gap-10 px-6 lg:grid-cols-2">
            <FadeUp>
              <Badge className="gap-1 border-hype-border bg-hype text-hype-foreground hover:bg-hype">
                <Zap className="h-3 w-3" aria-hidden="true" /> Hype Campaigns
              </Badge>
              <h1 className="mt-4 text-4xl font-bold leading-tight tracking-tight sm:text-5xl">
                100 creators. One sound. 72 hours.
              </h1>
              <p className="mt-4 text-lg text-muted-foreground">
                A Hype Campaign is a 72-hour blitz: the brand drops a source reel, sets a flat
                per-reel rate, and caps the number of slots. Creators accept with one tap — no
                negotiation, no back-and-forth — and post before the window closes. Each approved
                reel is paid out automatically.
              </p>
              <div className="mt-8 flex flex-wrap gap-3">
                <Button
                  size="lg"
                  className="bg-primary text-primary-foreground hover:bg-primary/90"
                  asChild
                >
                  <Link to="/brand/register">
                    Launch a Hype Campaign <Zap className="ml-1.5 h-4 w-4" aria-hidden="true" />
                  </Link>
                </Button>
                <Button size="lg" variant="outline" asChild>
                  <Link to="/creator/register">Join as a creator</Link>
                </Button>
              </div>

              <dl className="mt-10 grid grid-cols-2 gap-x-6 gap-y-6 border-t border-border/60 pt-8">
                {HERO_STATS.map((s) => (
                  <div key={s.value}>
                    <dt className="text-xl font-bold tracking-tight sm:text-2xl">{s.value}</dt>
                    <dd className="mt-1 text-sm text-muted-foreground">{s.label}</dd>
                  </div>
                ))}
              </dl>
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
                  <p className="mt-4 text-center text-xs text-muted-foreground">
                    Illustrative campaign — figures shown are an example, not live data.
                  </p>
                </CardContent>
              </Card>
            </FadeUp>
          </div>
        </section>

        {/* ------------------------------------------------------------ Perfect for */}
        <section className="border-b border-border/60 bg-card/50 py-20">
          <div className="mx-auto max-w-6xl px-6">
            <FadeUp className="mx-auto max-w-xl text-center">
              <h2 className="text-3xl font-semibold">Perfect for moments that can't wait</h2>
              <p className="mt-3 text-muted-foreground">
                Hype Campaigns exist for launches where speed and scale matter more than one-off
                negotiation.
              </p>
            </FadeUp>
            <StaggerContainer className="mt-12 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
              {USE_CASES.map((item) => {
                const Icon = item.icon;
                return (
                  <StaggerItem key={item.label}>
                    <div className="flex h-full flex-col items-center gap-3 rounded-2xl border border-border/60 bg-background p-6 text-center">
                      <span className="flex h-11 w-11 items-center justify-center rounded-lg bg-hype text-hype-foreground">
                        <Icon className="h-5 w-5" aria-hidden="true" />
                      </span>
                      <p className="text-sm font-medium">{item.label}</p>
                    </div>
                  </StaggerItem>
                );
              })}
            </StaggerContainer>
          </div>
        </section>

        {/* -------------------------------------------------- The old way / new way */}
        <section className="py-20">
          <div className="mx-auto max-w-6xl px-6">
            <FadeUp className="mx-auto max-w-2xl text-center">
              <h2 className="text-3xl font-semibold">From chasing creators to setting one rate</h2>
              <p className="mt-3 text-muted-foreground">
                How a Hype Campaign changes the workflow for a brand growth team, and for the
                creators filling it.
              </p>
            </FadeUp>

            <div className="mt-12 grid gap-6 lg:grid-cols-2">
              {/* Brand side */}
              <FadeUp>
                <div className="h-full rounded-2xl border border-border/60 bg-card p-6">
                  <p className="text-sm font-semibold uppercase tracking-widest text-muted-foreground">
                    For brand growth teams
                  </p>
                  <div className="mt-4 grid gap-4 sm:grid-cols-2">
                    <div>
                      <p className="text-xs font-medium text-muted-foreground">Without a platform</p>
                      <ul className="mt-2 space-y-2">
                        {BRAND_OLD_WAY.map((x) => (
                          <li key={x} className="flex gap-2 text-sm text-muted-foreground">
                            <span className="mt-1.5 h-1 w-1 shrink-0 rounded-full bg-muted-foreground" />
                            {x}
                          </li>
                        ))}
                      </ul>
                    </div>
                    <div>
                      <p className="text-xs font-medium text-primary">On Influora</p>
                      <ul className="mt-2 space-y-2">
                        {BRAND_NEW_WAY.map((x) => (
                          <li key={x} className="flex gap-2 text-sm">
                            <ShieldCheck
                              className="mt-0.5 h-3.5 w-3.5 shrink-0 text-primary"
                              aria-hidden="true"
                            />
                            {x}
                          </li>
                        ))}
                      </ul>
                    </div>
                  </div>
                </div>
              </FadeUp>

              {/* Creator side */}
              <FadeUp delay={0.1}>
                <div className="h-full rounded-2xl border border-border/60 bg-card p-6">
                  <p className="text-sm font-semibold uppercase tracking-widest text-muted-foreground">
                    For creators
                  </p>
                  <div className="mt-4 grid gap-4 sm:grid-cols-2">
                    <div>
                      <p className="text-xs font-medium text-muted-foreground">Without a platform</p>
                      <ul className="mt-2 space-y-2">
                        {CREATOR_OLD_WAY.map((x) => (
                          <li key={x} className="flex gap-2 text-sm text-muted-foreground">
                            <span className="mt-1.5 h-1 w-1 shrink-0 rounded-full bg-muted-foreground" />
                            {x}
                          </li>
                        ))}
                      </ul>
                    </div>
                    <div>
                      <p className="text-xs font-medium text-primary">On Influora</p>
                      <ul className="mt-2 space-y-2">
                        {CREATOR_NEW_WAY.map((x) => (
                          <li key={x} className="flex gap-2 text-sm">
                            <ShieldCheck
                              className="mt-0.5 h-3.5 w-3.5 shrink-0 text-primary"
                              aria-hidden="true"
                            />
                            {x}
                          </li>
                        ))}
                      </ul>
                    </div>
                  </div>
                </div>
              </FadeUp>
            </div>
          </div>
        </section>

        {/* ------------------------------------------------------------ How it works */}
        <section className="border-t border-border/60 bg-card/50 py-20">
          <div className="mx-auto max-w-6xl px-6">
            <FadeUp className="mx-auto max-w-xl text-center">
              <h2 className="text-3xl font-semibold">How a Hype Campaign runs</h2>
            </FadeUp>
            <StaggerContainer className="mt-12 grid gap-6 sm:grid-cols-2 lg:grid-cols-3">
              {HOW_IT_WORKS.map((step) => (
                <StaggerItem key={step.step}>
                  <div className="h-full rounded-2xl border border-border/60 bg-background p-6">
                    <span className="text-xs font-semibold tracking-widest text-muted-foreground/70">
                      {step.step}
                    </span>
                    <h3 className="mt-3 font-semibold">{step.title}</h3>
                    <p className="mt-1.5 text-sm text-muted-foreground">{step.body}</p>
                  </div>
                </StaggerItem>
              ))}
            </StaggerContainer>
          </div>
        </section>

        {/* --------------------------------------------------- Live execution mock */}
        <section className="py-20">
          <div className="mx-auto max-w-6xl px-6">
            <FadeUp className="mx-auto max-w-xl text-center">
              <h2 className="text-3xl font-semibold">Watching a blitz run</h2>
              <p className="mt-3 text-muted-foreground">
                One dashboard tracking every slot from claim to verified post.
              </p>
            </FadeUp>

            <div className="mt-12 grid gap-6 lg:grid-cols-5 lg:items-start">
              <FadeUp className="lg:col-span-3">
                <div className="rounded-2xl border border-hype-border bg-card p-5 shadow-sm">
                  <div className="flex items-center justify-between gap-3">
                    <p className="font-semibold">{demoHypeConfig.hashtag}</p>
                    <HypeLiveIndicator hoursLeft={8} />
                  </div>
                  <p className="mt-1 text-sm text-muted-foreground">
                    Live in progress · Hour 64 of 72
                  </p>
                  <SlotProgressBar
                    filled={92}
                    total={demoHypeConfig.slotCap}
                    className="mt-5"
                  />
                  <div className="mt-5 space-y-2">
                    {[
                      { h: 'Reel posted · verification in progress', s: 'Payment protected', ok: false },
                      { h: 'Reel posted · verified', s: 'Payout released', ok: true },
                    ].map((r) => (
                      <div
                        key={r.h}
                        className="flex flex-wrap items-center justify-between gap-2 rounded-lg bg-muted/60 p-3"
                      >
                        <p className="text-sm">{r.h}</p>
                        <span
                          className={
                            r.ok
                              ? 'shrink-0 rounded-full bg-success px-2.5 py-1 text-xs font-medium text-success-foreground'
                              : 'shrink-0 rounded-full bg-info px-2.5 py-1 text-xs font-medium text-info-foreground'
                          }
                        >
                          {r.s}
                        </span>
                      </div>
                    ))}
                  </div>
                  <p className="mt-4 text-center text-xs text-muted-foreground">
                    Illustrative campaign — figures shown are an example, not live data.
                  </p>
                </div>
              </FadeUp>

              <FadeUp delay={0.1} className="lg:col-span-2">
                <div className="overflow-hidden rounded-2xl border border-border/60 bg-card shadow-sm">
                  {/*
                    Real capture (ci/product-shots.mjs). Replaced an AI photo of a
                    laptop showing invented dashboard charts.
                  */}
                  <img
                    src="/product-shots/brand-campaigns.png"
                    alt="The Influora campaigns list, where a Hype Campaign's slots and payouts are tracked."
                    loading="lazy"
                    className="h-40 w-full border-b border-border/60 object-cover object-top"
                  />
                  <p className="px-5 pt-3 text-[11px] text-muted-foreground">Real Influora screen · sample data, not live data</p>
                  <div className="p-5">
                    <p className="font-semibold">Per-slot payout</p>
                    <div className="mt-3 space-y-2 text-sm">
                      <div className="flex items-center gap-2">
                        <UploadCloud className="h-4 w-4 shrink-0 text-primary" aria-hidden="true" />
                        Reel submitted with the source audio
                      </div>
                      <div className="flex items-center gap-2">
                        <Lock className="h-4 w-4 shrink-0 text-primary" aria-hidden="true" />
                        Slot funding stays protected until the post is verified
                      </div>
                      <div className="flex items-center gap-2">
                        <Layers className="h-4 w-4 shrink-0 text-primary" aria-hidden="true" />
                        Raw asset handed over per the campaign brief's usage rights
                      </div>
                    </div>
                    <p className="mt-4 text-center text-xs text-muted-foreground">
                      Illustrative flow — not a guaranteed processing time.
                    </p>
                  </div>
                </div>
              </FadeUp>
            </div>
          </div>
        </section>

        {/* ------------------------------------------------------- Why they love it */}
        <section className="border-t border-border/60 bg-card/50 py-20">
          <div className="mx-auto grid max-w-6xl gap-6 px-6 sm:grid-cols-2">
            <FadeUp>
              <Card className="h-full">
                <CardContent className="p-6">
                  <h3 className="font-semibold">Why brands love it</h3>
                  <p className="mt-2 text-sm text-muted-foreground">
                    Instead of running 100 separate negotiations, a brand launches one Hype
                    Campaign and fills every slot in hours. Each approved reel is paid out
                    automatically, so there's no manual payout tracking across 100 creators.
                  </p>
                </CardContent>
              </Card>
            </FadeUp>
            <FadeUp delay={0.1}>
              <Card className="h-full">
                <CardContent className="p-6">
                  <h3 className="font-semibold">Why creators love it</h3>
                  <p className="mt-2 text-sm text-muted-foreground">
                    The rate is fixed and visible upfront. There's nothing to negotiate — tap
                    accept, post within the window, and get paid through Secure Payments as soon
                    as the reel is approved.
                  </p>
                </CardContent>
              </Card>
            </FadeUp>
          </div>
        </section>

        <FaqSection heading="Hype Campaign questions, answered" items={FAQS} />

        <FunnelCta
          heading="Launch a Hype Campaign"
          sub="Set one flat rate, cap the slots, and let creators fill them. Your maximum spend is fixed before you launch."
          primary={{ label: 'Launch a Hype Campaign', to: '/brand/register' }}
          secondary={{ label: 'See how the payment is protected', to: '/features/secure-payments' }}
          reassurances={['Spend capped up front', 'Paid only on verified posts', 'Free to start']}
          tone="hype"
        />
      </main>

      <SiteFooter />
      <StickyCta label="Launch a Hype Campaign" to="/brand/register" note="Spend capped up front" />
      <StickyCtaSpacer />
    </div>
  );
}
