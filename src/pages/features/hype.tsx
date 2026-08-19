import { Link } from 'react-router-dom';
import { Clapperboard, PartyPopper, ShoppingBag, TrendingUp, Zap } from 'lucide-react';

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

// Content per wiki/website/content-map.md §2.3 + homepage-copy.md §6.

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
    body: 'Upload one source reel or audio, set a flat per-reel rate, and cap the number of slots.',
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
    body: 'Each accepted slot is funded and protected automatically, so every creator who accepts is guaranteed payment.',
  },
  {
    step: '05',
    title: 'Post before the window closes',
    body: 'Creators film and post within the 72-hour window using the source reel or audio.',
  },
  {
    step: '06',
    title: 'Auto-payout on verification',
    body: "Once a post is verified, the payment releases that creator's payout automatically — no manual tracking across 100 creators.",
  },
];

// Like "Deal Room", "Hype Campaign" is Influora's own term — this page is its
// definitional source, so it carries one canonical standalone answer.
const CANONICAL_QUESTION = 'What is a Hype Campaign?';
const CANONICAL_ANSWER =
  'A Hype Campaign is a multi-creator campaign format where a brand posts one source reel at ' +
  'a single flat per-reel rate and caps the number of slots. Up to 100 creators accept a slot ' +
  'with one tap, with no negotiation, and post their own remix inside a 72-hour window. Each ' +
  'accepted slot is funded up front and paid out automatically once the post is verified.';

const FAQS = [
  {
    question: CANONICAL_QUESTION,
    answer: CANONICAL_ANSWER,
  },
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
        {/* Hero */}
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
                A Hype Campaign is a 72-hour blitz: the brand drops a source reel, sets a flat per-reel
                rate, and caps the number of slots. Creators accept with one tap — no negotiation, no
                back-and-forth — and post before the window closes. Each approved reel is paid out
                automatically.
              </p>
              <div className="mt-8 flex flex-wrap gap-3">
                <Button size="lg" className="bg-primary text-primary-foreground hover:bg-primary/90" asChild>
                  <Link to="/brand/register">
                    Launch a Hype Campaign <Zap className="ml-1.5 h-4 w-4" aria-hidden="true" />
                  </Link>
                </Button>
                <Button size="lg" variant="outline" asChild>
                  <Link to="/creator/register">Join as a creator</Link>
                </Button>
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

        {/* Perfect for */}
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

        {/* How it works */}
        <section className="py-20">
          <div className="mx-auto max-w-6xl px-6">
            <FadeUp className="mx-auto max-w-xl text-center">
              <h2 className="text-3xl font-semibold">How a Hype Campaign runs</h2>
            </FadeUp>
            <StaggerContainer className="mt-12 grid gap-6 sm:grid-cols-2 lg:grid-cols-3">
              {HOW_IT_WORKS.map((step) => (
                <StaggerItem key={step.step}>
                  <div className="h-full rounded-2xl border border-border/60 bg-card/50 p-6">
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

        {/* Why brands / creators love it */}
        <section className="border-t border-border/60 bg-card/50 py-20">
          <div className="mx-auto grid max-w-6xl gap-6 px-6 sm:grid-cols-2">
            <FadeUp>
              <Card className="h-full">
                <CardContent className="p-6">
                  <h3 className="font-semibold">Why brands love it</h3>
                  <p className="mt-2 text-sm text-muted-foreground">
                    Instead of running 100 separate negotiations, a brand launches one Hype Campaign and
                    fills every slot in hours. Each approved reel is paid out automatically, so there's no
                    manual payout tracking across 100 creators.
                  </p>
                </CardContent>
              </Card>
            </FadeUp>
            <FadeUp delay={0.1}>
              <Card className="h-full">
                <CardContent className="p-6">
                  <h3 className="font-semibold">Why creators love it</h3>
                  <p className="mt-2 text-sm text-muted-foreground">
                    The rate is fixed and visible upfront. There's nothing to negotiate — tap accept, post
                    within the window, and get paid through Secure Payments as soon as the reel is approved.
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
