import { Link } from 'react-router-dom';
import {
  ArrowRight,
  BadgeCheck,
  CheckCircle2,
  FileSignature,
  Lock,
  PlayCircle,
  ShieldCheck,
  Wallet,
  X,
  Zap,
} from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { FadeUp, StaggerContainer, StaggerItem } from '@/components/motion';
import { SiteHeader } from '@/components/site/SiteHeader';
import { SiteFooter } from '@/components/site/SiteFooter';
import { WalkthroughVideo } from '@/components/shared/WalkthroughVideo';
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

/*
  Layout ported from the Stitch design "Influora - How It Works for Brands"
  (project 13029479870344759024). Structure, chapter rhythm and the mock panels are
  the design's; the palette is ours (Priya's ruling 2026-09-07 — take their layout and
  spacing, reject their colour values), and every claim has been rewritten to
  something the product actually does.

  What was deliberately NOT carried over from the design, and why:
    - "RBI Escrow Vault", "RBI-regulated nodal trustee account", "legally backed escrow".
      Our licensed partner is an RBI-authorized Payment Aggregator; we are not a
      trustee and not RBI-licensed. "escrow" is also banned in user copy (2026-09-02).
    - "Automated 194J TDS", "Auto GST filing", "Real-Time NSDL PAN Check",
      "Form 26Q Export Pack". TDS is recorded and shown on payouts; none of it is filed
      for you. Live copy is hedged on purpose and this page stays hedged.
    - "10x more campaigns", "4.2x faster brief-to-live", "98.4% on-time", "0% ghost-follower
      tolerance", "42 hours wasted per quarter", "3.64x blended ROAS", "₹8.40L attributed GMV".
      None are measured. Campaign performance is creator-reported, not measured by us.
    - The two named founder testimonials and their portraits. Fabricated people.

  Every mock panel below carries a visible "illustrative" caption, same rule the
  homepage Deal Room card follows.
*/

/** Hero stat band — the design's four tiles, refilled with things that are true. */
const HERO_STATS = [
  { value: '₹0', label: 'To start — no subscription on the Free tier' },
  { value: 'On approval', label: 'Payment releases only after you approve the work' },
  { value: 'Every deal', label: 'E-signed contract, before any work begins' },
  { value: 'Nano → macro', label: 'No minimum follower count to work with' },
] as const;

/** The design's chapter rail. Anchors jump down the page. */
const CHAPTERS = [
  { n: '1', label: 'Find creators', href: '#chapter-discovery' },
  { n: '2', label: 'Agree in one place', href: '#chapter-deal-room' },
  { n: '3', label: 'Secure the funds', href: '#chapter-payments' },
  { n: '4', label: 'Scale to 100', href: '#chapter-hype' },
  { n: '5', label: 'Close the books', href: '#chapter-compliance' },
] as const;

const DISCOVERY_POINTS = [
  {
    icon: BadgeCheck,
    title: 'Stats synced from Instagram, not a media kit',
    body: 'Follower count and engagement rate are read from the creator’s connected Instagram account, so you are not reading a number they typed into a PDF.',
  },
  {
    icon: FileSignature,
    title: 'Rate cards published up front',
    body: 'Creators set their own price per reel, per carousel and per story, and it is on the profile before you start a conversation.',
  },
  {
    icon: ShieldCheck,
    title: 'Past collaborations on the profile',
    body: 'See the deals a creator has already completed on Influora, so a first conversation is not a cold one.',
  },
] as const;

const PAYMENT_FLOW = [
  {
    n: '01',
    icon: Wallet,
    title: 'You fund the deal',
    body: 'The full amount is deposited with a licensed, RBI-authorized Payment Aggregator before the creator starts. You are not paying an advance into a DM.',
    tag: 'Secured',
  },
  {
    n: '02',
    icon: Lock,
    title: 'The money is locked',
    body: 'While the reel is filmed and reviewed, neither side can pull the funds back. The creator sees “Payment secured” on the invite.',
    tag: 'In progress',
  },
  {
    n: '03',
    icon: CheckCircle2,
    title: 'You review the deliverable',
    body: 'The creator uploads the draft to the Deal Room. You approve it, or send it back inside the revision limit written into the contract.',
    tag: 'Your call',
  },
  {
    n: '04',
    icon: ArrowRight,
    title: 'Payout releases',
    body: 'Approval releases the payment to the creator and generates the invoice, with any recorded TDS shown on it.',
    tag: 'On approval',
  },
] as const;

const OLD_WAY = [
  'Separate bank transfers to reconcile at month end, one per creator.',
  'Deliverables agreed in a DM, with nothing to point at when they change.',
  'Advances paid before filming, with no recourse if the reel never arrives.',
  'Invoices chased over email, weeks after the work went live.',
] as const;

const INFLUORA_WAY = [
  'One dashboard with every campaign, deal and payout in it.',
  'An e-signed contract on every deal, with usage rights and revision limits written in.',
  'Funds secured before filming and released only when you approve.',
  'An invoice generated on payout, with any recorded TDS shown.',
] as const;

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

        The redesign added a five-chapter narrative around these steps. STEPS is
        still rendered in full, under "The six steps, end to end" — do not remove
        that section without also rewriting this schema, or the schema starts
        quoting copy that is no longer on the page.
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
        {/* ---------------------------------------------------------------- Hero */}
        <section className="relative overflow-hidden border-b border-border/60">
          <div className="mx-auto max-w-6xl px-6 py-16 lg:py-24">
            <FadeUp>
              <div className="mx-auto max-w-3xl text-center">
                <Badge variant="outline" className="gap-1.5">
                  For brands
                </Badge>
                <h1 className="mt-4 text-4xl font-bold leading-tight tracking-tight sm:text-5xl">
                  From a first message to a posted reel — without the chasing
                </h1>
                <p className="mt-4 text-lg text-muted-foreground">
                  Six steps from campaign brief to a live reel: discovery, negotiation, a signed
                  contract, and a payment-protected payout, all inside one platform.
                </p>
                <div className="mt-8 flex flex-wrap justify-center gap-3">
                  <Button
                    size="lg"
                    className="bg-accent-foreground text-white hover:bg-accent-foreground/90"
                    asChild
                  >
                    <Link to="/brand/register">
                      Launch your first campaign{' '}
                      <ArrowRight className="ml-1.5 h-4 w-4" aria-hidden="true" />
                    </Link>
                  </Button>
                  <Button size="lg" variant="outline" asChild>
                    <Link to="/how-it-works/creators">
                      <PlayCircle className="mr-1.5 h-4 w-4" aria-hidden="true" />
                      See the creator side
                    </Link>
                  </Button>
                </div>
              </div>

              {/* The lifecycle film. Same asset the signed-in how-it-works page plays, so a
                  visitor and a customer are shown the same product. Renders nothing if the
                  deployment has switched it off. */}
              <WalkthroughVideo
                role="brand"
                title="How an Influora campaign works, for brands"
                className="mx-auto mt-12 max-w-4xl"
              />

              {/* Stat band */}
              <dl className="mx-auto mt-14 grid max-w-4xl grid-cols-2 gap-x-6 gap-y-8 border-t border-border/60 pt-10 lg:grid-cols-4">
                {HERO_STATS.map((s) => (
                  <div key={s.value}>
                    <dt className="text-xl font-bold tracking-tight sm:text-2xl">{s.value}</dt>
                    <dd className="mt-1 text-sm text-muted-foreground">{s.label}</dd>
                  </div>
                ))}
              </dl>

              {/* Chapter rail */}
              <nav
                aria-label="Sections on this page"
                className="mx-auto mt-10 flex max-w-4xl flex-wrap justify-center gap-2"
              >
                {CHAPTERS.map((c) => (
                  <a
                    key={c.href}
                    href={c.href}
                    className="inline-flex items-center gap-2 rounded-full border border-border/60 bg-card px-4 py-2 text-sm text-muted-foreground transition-colors hover:border-primary/40 hover:text-foreground"
                  >
                    <span className="flex h-5 w-5 items-center justify-center rounded-full bg-accent text-[11px] font-semibold text-accent-foreground">
                      {c.n}
                    </span>
                    {c.label}
                  </a>
                ))}
              </nav>
            </FadeUp>
          </div>
        </section>

        <TrustBar />

        {/* ------------------------------------------------ Chapter 1 — Discovery */}
        <section id="chapter-discovery" className="scroll-mt-20 py-20">
          <div className="mx-auto grid max-w-6xl items-center gap-10 px-6 lg:grid-cols-2">
            <FadeUp>
              <p className="text-sm font-semibold uppercase tracking-widest text-muted-foreground">
                Chapter 01 — Find creators
              </p>
              <h2 className="mt-3 text-3xl font-bold tracking-tight sm:text-4xl">
                Numbers from the account, not from a deck
              </h2>
              <p className="mt-4 text-muted-foreground">
                A media kit is a screenshot someone chose to send you. Influora reads a creator’s
                stats from the Instagram account they connected, and shows the rate card next to
                them.
              </p>
              <div className="mt-8 space-y-6">
                {DISCOVERY_POINTS.map((p) => {
                  const Icon = p.icon;
                  return (
                    <div key={p.title} className="flex gap-4">
                      <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-accent text-accent-foreground">
                        <Icon className="h-5 w-5" aria-hidden="true" />
                      </span>
                      <div>
                        <h3 className="font-semibold">{p.title}</h3>
                        <p className="mt-1 text-sm text-muted-foreground">{p.body}</p>
                      </div>
                    </div>
                  );
                })}
              </div>
            </FadeUp>

            {/* Creator profile card — illustrative */}
            <FadeUp>
              <div className="rounded-2xl border border-border/60 bg-card p-5 shadow-sm">
                <div className="flex items-center gap-3">
                  <img
                    src="/stitch-media/close-up-professional-portrait-of-a-stylish-indian-m-2cca69.jpg"
                    alt=""
                    loading="lazy"
                    width={56}
                    height={56}
                    className="h-14 w-14 rounded-full object-cover"
                  />
                  <div className="min-w-0">
                    <p className="flex items-center gap-1.5 font-semibold">
                      Creator profile
                      <BadgeCheck className="h-4 w-4 text-primary" aria-hidden="true" />
                    </p>
                    <p className="truncate text-sm text-muted-foreground">
                      Instagram connected · Men’s grooming &amp; tech
                    </p>
                  </div>
                </div>

                <dl className="mt-5 grid grid-cols-3 gap-3">
                  {[
                    { k: 'Reel rate', v: '₹12,500' },
                    { k: 'Engagement', v: '4.8%' },
                    { k: 'Deals done', v: '32' },
                  ].map((x) => (
                    <div key={x.k} className="rounded-lg bg-muted/60 p-3">
                      <dt className="text-xs text-muted-foreground">{x.k}</dt>
                      <dd className="mt-0.5 font-semibold">{x.v}</dd>
                    </div>
                  ))}
                </dl>

                <div className="mt-4 rounded-lg border border-border/60 p-3">
                  <p className="text-xs font-medium text-muted-foreground">Audience</p>
                  <div className="mt-2 flex flex-wrap gap-1.5">
                    {['Mumbai 44%', 'Bengaluru 28%', 'Delhi NCR 15%'].map((c) => (
                      <span
                        key={c}
                        className="rounded-full bg-accent px-2.5 py-1 text-xs text-accent-foreground"
                      >
                        {c}
                      </span>
                    ))}
                  </div>
                </div>

                <Button className="mt-5 w-full" variant="outline" asChild>
                  <Link to="/brand/register">Invite to a Deal Room</Link>
                </Button>
                <p className="mt-3 text-center text-xs text-muted-foreground">
                  Illustrative profile — figures shown are an example, not live data.
                </p>
              </div>
            </FadeUp>
          </div>
        </section>

        {/* ------------------------------------------------ Chapter 2 — Deal Room */}
        <section
          id="chapter-deal-room"
          className="scroll-mt-20 border-y border-border/60 bg-card/50 py-20"
        >
          <div className="mx-auto grid max-w-6xl items-center gap-10 px-6 lg:grid-cols-2">
            {/* Chat + scope mock — illustrative */}
            <FadeUp className="order-2 lg:order-1">
              <div className="rounded-2xl border border-border/60 bg-card p-5 shadow-sm">
                <div className="flex items-center justify-between gap-3">
                  <p className="font-semibold">Deal Room</p>
                  <span className="inline-flex items-center gap-1 rounded-full bg-success px-2.5 py-1 text-xs font-medium text-success-foreground">
                    <Lock className="h-3 w-3" aria-hidden="true" /> E-signed
                  </span>
                </div>

                <div className="mt-4 space-y-3">
                  <div className="rounded-lg bg-muted/60 p-3">
                    <p className="text-sm">
                      Offer sent: 1 Instagram Reel (30–45s) + 3 Stories with a link sticker. Total
                      ₹18,000.
                    </p>
                    <p className="mt-1.5 text-xs text-muted-foreground">Brand · 10:24</p>
                  </div>
                  <div className="rounded-lg border border-primary/20 bg-accent/60 p-3">
                    <p className="text-sm">
                      Accepted. Two revision rounds, raw footage handed over within 48h of posting.
                    </p>
                    <p className="mt-1.5 text-xs text-muted-foreground">Creator · 10:31</p>
                  </div>
                </div>

                <div className="mt-5 space-y-2 border-t border-border/60 pt-4">
                  <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                    What the contract locks
                  </p>
                  {[
                    '1 Reel, 30–45s, due 24 Oct',
                    '3 Stories with link sticker, due 25 Oct',
                    'Maximum 2 revisions',
                    '6-month digital usage rights',
                  ].map((x) => (
                    <p key={x} className="flex items-start gap-2 text-sm">
                      <CheckCircle2
                        className="mt-0.5 h-4 w-4 shrink-0 text-primary"
                        aria-hidden="true"
                      />
                      {x}
                    </p>
                  ))}
                </div>
                <p className="mt-4 text-center text-xs text-muted-foreground">
                  Illustrative deal — amounts shown are an example, not live data.
                </p>
              </div>
            </FadeUp>

            <FadeUp className="order-1 lg:order-2">
              <p className="text-sm font-semibold uppercase tracking-widest text-muted-foreground">
                Chapter 02 — Agree in one place
              </p>
              <h2 className="mt-3 text-3xl font-bold tracking-tight sm:text-4xl">
                The conversation and the contract are the same thread
              </h2>
              <p className="mt-4 text-muted-foreground">
                Proposals, counter-offers and the signed contract all live in one Deal Room. When
                the scope changes, it changes in the document both sides signed — not in a message
                someone has to scroll back to find.
              </p>
              <ul className="mt-6 space-y-3 text-sm text-muted-foreground">
                <li className="flex gap-2">
                  <FileSignature
                    className="mt-0.5 h-4 w-4 shrink-0 text-primary"
                    aria-hidden="true"
                  />
                  Contracts are generated for you and e-signed by both sides.
                </li>
                <li className="flex gap-2">
                  <ShieldCheck className="mt-0.5 h-4 w-4 shrink-0 text-primary" aria-hidden="true" />
                  Usage rights, exclusivity and revision limits are written in, not assumed.
                </li>
              </ul>
              {/*
                DELIBERATELY NO IMAGE HERE.

                The Stitch design put a "product screenshot" in this slot
                (high-tech-minimal-clean-software-screen-recording-pr-e9a393.jpg).
                It is an AI-generated FAKE of our own dashboard and it is unusable:
                its nav reads "Escrow Wallet", it has panels titled "Secure Escrow"
                and "Active Escrow Contracts" (banned vocabulary, 2026-09-02), an
                "Escrow Balance $14,850 USD" — in dollars, on an India-first product —
                plus a teal logo and palette that are not our brand, invented creator
                handles and 2023 dates.

                A fabricated screenshot of our own product is worse than a decorative
                stock photo: a reader takes it as what the dashboard actually looks
                like. This slot now holds a REAL capture of the real Deal Room,
                produced by `npm run shots:product` (ci/product-shots.mjs).
              */}
              <figure className="mt-8">
                <img
                  src="/product-shots/brand-deals.png"
                  alt="The Influora Deal Room, showing a deal thread alongside its agreed deliverables and contract status."
                  loading="lazy"
                  width={2880}
                  height={1800}
                  className="w-full rounded-xl border border-border/60"
                />
                <figcaption className="mt-3 text-center text-xs text-muted-foreground">
                  A real screen from Influora — figures shown are sample data, not live data.
                </figcaption>
              </figure>
            </FadeUp>
          </div>
        </section>

        {/* ------------------------------------------- The six steps (schema anchor) */}
        <section className="py-20">
          <div className="mx-auto max-w-4xl px-6">
            <FadeUp>
              <h2 className="text-center text-3xl font-bold tracking-tight sm:text-4xl">
                The six steps, end to end
              </h2>
              <p className="mx-auto mt-3 max-w-2xl text-center text-muted-foreground">
                The same flow every deal follows, whether it is one reel or a hundred.
              </p>
            </FadeUp>
            <StaggerContainer className="mt-10 space-y-6">
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
                        <h3 className="font-semibold">{step.title}</h3>
                        <p className="mt-1.5 text-sm text-muted-foreground">{step.body}</p>
                      </div>
                    </div>
                  </StaggerItem>
                );
              })}
            </StaggerContainer>
          </div>
        </section>

        {/* ------------------------------------------ Chapter 3 — Secure Payments */}
        <section
          id="chapter-payments"
          className="scroll-mt-20 border-y border-border/60 bg-card/50 py-20"
        >
          <div className="mx-auto max-w-6xl px-6">
            <FadeUp>
              <div className="mx-auto max-w-3xl text-center">
                <p className="text-sm font-semibold uppercase tracking-widest text-muted-foreground">
                  Chapter 03 — Secure the funds
                </p>
                <h2 className="mt-3 text-3xl font-bold tracking-tight sm:text-4xl">
                  Your money does not move until you approve the work
                </h2>
                <p className="mt-4 text-muted-foreground">
                  Payments are held with a licensed, RBI-authorized Payment Aggregator from the
                  moment the contract is signed. You never pay in advance for work that has not
                  arrived, and the creator never films without the money already secured.
                </p>
              </div>
            </FadeUp>

            <StaggerContainer className="mt-12 grid gap-5 md:grid-cols-2 lg:grid-cols-4">
              {PAYMENT_FLOW.map((s) => {
                const Icon = s.icon;
                return (
                  <StaggerItem key={s.n}>
                    <div className="flex h-full flex-col rounded-2xl border border-border/60 bg-card p-5">
                      <div className="flex items-center justify-between">
                        <span className="flex h-10 w-10 items-center justify-center rounded-lg bg-accent text-accent-foreground">
                          <Icon className="h-5 w-5" aria-hidden="true" />
                        </span>
                        <span className="text-xs font-semibold tracking-widest text-muted-foreground/70">
                          {s.n}
                        </span>
                      </div>
                      <h3 className="mt-4 font-semibold">{s.title}</h3>
                      <p className="mt-1.5 flex-1 text-sm text-muted-foreground">{s.body}</p>
                      <span className="mt-4 self-start rounded-full bg-muted px-2.5 py-1 text-xs text-muted-foreground">
                        {s.tag}
                      </span>
                    </div>
                  </StaggerItem>
                );
              })}
            </StaggerContainer>
          </div>
        </section>

        {/* ----------------------------------------------------- Chapter 4 — Hype */}
        <section id="chapter-hype" className="scroll-mt-20 py-20">
          <div className="mx-auto grid max-w-6xl items-center gap-10 px-6 lg:grid-cols-2">
            <FadeUp>
              <Badge className="gap-1 border-hype-border bg-hype text-hype-foreground hover:bg-hype">
                <Zap className="h-3 w-3" aria-hidden="true" /> Chapter 04 — Scale to 100
              </Badge>
              <h2 className="mt-3 text-3xl font-bold tracking-tight sm:text-4xl">
                When you need a trend, not a negotiation
              </h2>
              <p className="mt-4 text-muted-foreground">
                A Hype Campaign is a 72-hour blitz. Drop one source reel, set a flat per-reel rate,
                cap the slots. Creators accept with one tap — no back-and-forth — and post before
                the window closes. Each approved reel pays out automatically.
              </p>
              <div className="mt-8">
                <Button size="lg" variant="outline" asChild>
                  <Link to="/features/hype">
                    Explore Hype Campaigns{' '}
                    <ArrowRight className="ml-1.5 h-4 w-4" aria-hidden="true" />
                  </Link>
                </Button>
              </div>
            </FadeUp>

            {/* Hype dashboard mock — illustrative */}
            <FadeUp>
              <div className="rounded-2xl border border-border/60 bg-card p-5 shadow-sm">
                <div className="flex items-center justify-between gap-3">
                  <p className="font-semibold">#GlowDropChallenge</p>
                  <span className="rounded-full bg-hype px-2.5 py-1 text-xs font-medium text-hype-foreground">
                    Live · 28h left
                  </span>
                </div>

                <div className="mt-4">
                  <div className="flex items-baseline justify-between text-sm">
                    <span className="text-muted-foreground">Slots filled</span>
                    <span className="font-semibold">88 / 100</span>
                  </div>
                  <div
                    className="mt-2 h-2 w-full overflow-hidden rounded-full bg-muted"
                    role="presentation"
                  >
                    <div className="h-full w-[88%] rounded-full bg-primary" />
                  </div>
                </div>

                <div className="mt-5 space-y-2">
                  {[
                    { h: 'Reel live · 42k views', s: 'Payout released', ok: true },
                    { h: 'Draft submitted · in review', s: 'Funds secured', ok: false },
                  ].map((r) => (
                    <div
                      key={r.h}
                      className="flex items-center justify-between gap-3 rounded-lg bg-muted/60 p-3"
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
          </div>
        </section>

        {/* ----------------------------------------------- Chapter 5 — Compliance */}
        <section
          id="chapter-compliance"
          className="scroll-mt-20 border-t border-border/60 bg-card/50 py-20"
        >
          <div className="mx-auto max-w-6xl px-6">
            <FadeUp>
              <div className="mx-auto max-w-3xl text-center">
                <p className="text-sm font-semibold uppercase tracking-widest text-muted-foreground">
                  Chapter 05 — Close the books
                </p>
                <h2 className="mt-3 text-3xl font-bold tracking-tight sm:text-4xl">
                  One dashboard your finance team can actually read
                </h2>
                <p className="mt-4 text-muted-foreground">
                  Every deal leaves a record: a signed contract, an approval, a payout and an
                  invoice with any recorded TDS shown on it.
                </p>
              </div>
            </FadeUp>

            <div className="mt-12 grid gap-6 lg:grid-cols-2">
              <FadeUp>
                <div className="h-full rounded-2xl border border-border/60 bg-card p-6">
                  <p className="font-semibold text-muted-foreground">Without a platform</p>
                  <ul className="mt-4 space-y-3">
                    {OLD_WAY.map((x) => (
                      <li key={x} className="flex gap-2.5 text-sm text-muted-foreground">
                        <X
                          className="mt-0.5 h-4 w-4 shrink-0 text-destructive-foreground"
                          aria-hidden="true"
                        />
                        {x}
                      </li>
                    ))}
                  </ul>
                </div>
              </FadeUp>
              <FadeUp>
                <div className="h-full rounded-2xl border border-primary/30 bg-card p-6">
                  <p className="font-semibold">On Influora</p>
                  <ul className="mt-4 space-y-3">
                    {INFLUORA_WAY.map((x) => (
                      <li key={x} className="flex gap-2.5 text-sm">
                        <CheckCircle2
                          className="mt-0.5 h-4 w-4 shrink-0 text-primary"
                          aria-hidden="true"
                        />
                        {x}
                      </li>
                    ))}
                  </ul>
                  <p className="mt-5 text-xs text-muted-foreground">
                    Influora records and shows TDS on payouts and generates the invoice. It does not
                    file your returns for you — see the{' '}
                    <Link to="/tds" className="underline underline-offset-2 hover:text-foreground">
                      TDS policy
                    </Link>
                    .
                  </p>
                </div>
              </FadeUp>
            </div>
          </div>
        </section>

        {/*
          BRAND-FEE TIMING RULE. The brand-side platform fee is charged ONCE PER
          CAMPAIGN, when the campaign goes live, on the campaign's committed budget
          (budgetMax) — not per deal and not on actual spend. See
          BrandCampaignFeeService.chargeOnPublish and the fuller note in
          src/pages/pricing.tsx. Brand-fee copy must say "when a campaign goes live",
          never that it is taken once a deal finishes or closes. That per-transaction
          phrasing belongs to the CREATOR commission
          (PlatformFeeService.deductAtRelease), a separate charge taken at payout.
        */}
        <FunnelCta
          heading="Ready to launch your first campaign?"
          sub="Free to start — no subscription on the Free tier. Upgrade to Pro anytime for lower fees and team features."
          primary={{ label: 'Launch your first campaign', to: '/brand/register' }}
          secondary={{ label: 'See pricing first', to: '/pricing' }}
          reassurances={['Free to start', 'Contracts included', 'Fee only when a campaign goes live']}
          className="py-20"
        />
      </main>

      <SiteFooter />
      <StickyCta label="Launch a campaign" to="/brand/register" note="Free to start" />
      <StickyCtaSpacer />
    </div>
  );
}
