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
import { FunnelCta } from '@/components/site/FunnelCta';
import { TrustBar, CREATOR_TRUST_ITEMS } from '@/components/site/TrustBar';
import { StickyCta, StickyCtaSpacer } from '@/components/site/StickyCta';
import { CREATOR_STEPS as STEPS } from '@/content/how-it-works-steps';
import { Seo } from '@/lib/seo/Seo';
import {
  JsonLd,
  getBreadcrumbListSchema,
  getHowToSchema,
  getWebPageSchema,
} from '@/lib/seo/schema';

/*
  Layout ported from the Stitch design "Influora - How It Works for Creators".
  Same rules as the brands page: their structure and spacing, our palette, our claims.

  Deliberately NOT carried over:
    - "₹0 Forever — zero platform cuts from your agreed brand fees". Flatly false.
      Creator commission is 15%, identical on Free and Pro (see /pricing).
    - "RBI-regulated trustee escrow", "RBI Nodal Protected", "Escrow Vault".
      Our partner is an RBI-authorized Payment Aggregator; we are not a trustee.
      "escrow" is banned in user copy (2026-09-02).
    - "< 3 Seconds" instant payout. Live copy says payout typically inside 24 hours.
    - "99.2% on-time completion score", "78% Tier-1 presence", "Industry Avg 1.8%",
      "auto-generated 194J TDS certificates". None are measured or built.
    - The named creator "Tanvi Shah" and, more seriously, the real third-party brands
      listed as her completed collabs (Nykaa Beauty, Mamaearth, Minimalist Skin).
      Those are real companies and are not our customers.
*/

const HERO_STATS = [
  { value: 'Free', label: 'To join, list a rate card and pitch' },
  { value: 'Before you film', label: 'The brand’s payment is secured first' },
  { value: '~24 hours', label: 'Typical payout time after approval' },
  { value: 'Nano → macro', label: 'No minimum follower count' },
] as const;

const CHAPTERS = [
  { n: '1', label: 'Your verified profile', href: '#chapter-profile' },
  { n: '2', label: 'The Deal Room', href: '#chapter-deal-room' },
  { n: '3', label: 'Scope and revisions', href: '#chapter-scope' },
  { n: '4', label: 'Getting paid', href: '#chapter-payout' },
  { n: '5', label: 'Hype Campaigns', href: '#chapter-hype' },
] as const;

const PROFILE_POINTS = [
  {
    icon: BadgeCheck,
    title: 'Your stats come from your account, not a PDF',
    body: 'Connect Instagram once and your follower count and engagement rate are read from it. Brands see numbers they can trust, so you do not have to argue for them.',
  },
  {
    icon: FileSignature,
    title: 'Your rate card is public',
    body: 'Set your price per reel, per carousel and per story. Brands see it before they message you, which ends the "what’s your budget" dance.',
  },
  {
    icon: ShieldCheck,
    title: 'Your completed deals build the profile',
    body: 'Every deal you finish on Influora shows on your profile, so a new brand can see you deliver without you assembling a deck.',
  },
] as const;

const PAYOUT_FLOW = [
  {
    n: '01',
    icon: Wallet,
    title: 'The brand funds it first',
    body: 'Before you shoot anything, the full amount is deposited with a licensed, RBI-authorized Payment Aggregator. You see “Payment secured” on the invite.',
    tag: 'Before filming',
  },
  {
    n: '02',
    icon: Lock,
    title: 'It stays locked while you work',
    body: 'The brand cannot pull the money back mid-shoot, and you are not filming on a promise made in a DM.',
    tag: 'While you shoot',
  },
  {
    n: '03',
    icon: CheckCircle2,
    title: 'You submit, they approve',
    body: 'Upload the draft in the Deal Room. Revisions are capped at the number written into the contract — not whatever they ask for.',
    tag: 'Capped revisions',
  },
  {
    n: '04',
    icon: ArrowRight,
    title: 'Payout releases to you',
    body: 'Approval releases the money to your UPI or bank account, typically inside 24 hours, with an invoice generated for you showing gross → net and any recorded TDS.',
    tag: '~24 hours',
  },
] as const;

const OLD_WAY = [
  'Rates negotiated in DMs, with the number moving every time someone new joins the thread.',
  'Filming first and invoicing after, then waiting 60–90 days to be paid.',
  'Revisions with no ceiling — "just one more version" until the shoot is unpaid work.',
  'Writing your own invoice and working out the TDS deduction yourself.',
] as const;

const INFLUORA_WAY = [
  'The rate is agreed and written into a contract before you start.',
  'The brand’s money is secured before you film, not after you deliver.',
  'Revision limits and usage rights are in the contract both sides e-signed.',
  'The invoice is generated for you, with any recorded TDS shown on it.',
] as const;

export default function HowItWorksCreatorsPage() {
  return (
    <div className="min-h-screen bg-background text-foreground">
      <Seo
        title="How It Works for Creators"
        description="Build a verified profile, get invited to Deal Rooms, agree scope in a contract, and get paid through Secure Payments after approval. How creators earn on Influora."
        canonical="/how-it-works/creators"
      />
      {/*
        HowTo, derived from the same CREATOR_STEPS array the page renders — see the
        note on the brands page. The redesign wraps these steps in a five-chapter
        narrative, but STEPS is still rendered in full under "The six steps, end to
        end". Do not delete that section without rewriting this schema.
      */}
      <JsonLd
        data={getHowToSchema({
          name: 'How to get paid brand deals as a creator in India',
          description:
            'The six steps a creator takes on Influora, from building a verified profile to the payout releasing after the brand approves the work.',
          url: '/how-it-works/creators',
          steps: STEPS.map((s) => ({ name: s.title, text: s.body })),
        })}
      />
      <JsonLd
        data={getWebPageSchema({
          name: 'How It Works for Creators',
          description:
            'A creator builds a verified profile with a rate card, receives or applies to campaigns, agrees scope in a Deal Room, e-signs a contract, delivers the work, and is paid automatically once the brand approves.',
          url: '/how-it-works/creators',
        })}
      />
      <JsonLd
        data={getBreadcrumbListSchema([
          { name: 'Home', url: '/' },
          { name: 'How It Works — Creators', url: '/how-it-works/creators' },
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
                  For creators
                </Badge>
                <h1 className="mt-4 text-4xl font-bold leading-tight tracking-tight sm:text-5xl">
                  Never film before the money is secured
                </h1>
                <p className="mt-4 text-lg text-muted-foreground">
                  Six steps from profile to payout — creators of all sizes, from nano to macro, get
                  paid through Secure Payments with no invoice-chasing.
                </p>
                <div className="mt-8 flex flex-wrap justify-center gap-3">
                  <Button
                    size="lg"
                    className="bg-accent-foreground text-white hover:bg-accent-foreground/90"
                    asChild
                  >
                    <Link to="/creator/register">
                      Join as a creator <ArrowRight className="ml-1.5 h-4 w-4" aria-hidden="true" />
                    </Link>
                  </Button>
                  <Button size="lg" variant="outline" asChild>
                    <Link to="/how-it-works/brands">
                      <PlayCircle className="mr-1.5 h-4 w-4" aria-hidden="true" />
                      See the brand side
                    </Link>
                  </Button>
                </div>
              </div>

              <dl className="mx-auto mt-14 grid max-w-4xl grid-cols-2 gap-x-6 gap-y-8 border-t border-border/60 pt-10 lg:grid-cols-4">
                {HERO_STATS.map((s) => (
                  <div key={s.value}>
                    <dt className="text-xl font-bold tracking-tight sm:text-2xl">{s.value}</dt>
                    <dd className="mt-1 text-sm text-muted-foreground">{s.label}</dd>
                  </div>
                ))}
              </dl>

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

        <TrustBar items={CREATOR_TRUST_ITEMS} />

        {/* -------------------------------------------------- Chapter 1 — Profile */}
        <section id="chapter-profile" className="scroll-mt-20 py-20">
          <div className="mx-auto grid max-w-6xl items-center gap-10 px-6 lg:grid-cols-2">
            <FadeUp>
              <p className="text-sm font-semibold uppercase tracking-widest text-muted-foreground">
                Chapter 01 — Your verified profile
              </p>
              <h2 className="mt-3 text-3xl font-bold tracking-tight sm:text-4xl">
                Stop sending a media kit nobody opens
              </h2>
              <p className="mt-4 text-muted-foreground">
                A brand manager reads a lot of pitches, and a screenshot of your insights is easy to
                ignore. An Influora profile links to the account itself, so the numbers are not
                something you had to prove.
              </p>
              <div className="mt-8 space-y-6">
                {PROFILE_POINTS.map((p) => {
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

            {/*
              Real capture, not a generated one — see ci/product-shots.mjs. The image
              this replaced showed a tablet running an invented interface; anything in
              this slot depicts the product, so it has to BE the product.
            */}
            <FadeUp>
              <figure>
                <img
                  src="/product-shots/creator-dashboard.png"
                  alt="A creator's Influora dashboard, showing their profile, connected account stats and current deals."
                  loading="lazy"
                  width={2880}
                  height={1800}
                  className="w-full rounded-2xl border border-border/60"
                />
                <figcaption className="mt-3 text-center text-xs text-muted-foreground">
                  A real screen from Influora — figures shown are sample data, not live data.
                </figcaption>
              </figure>
            </FadeUp>
          </div>
        </section>

        {/* ------------------------------------------------ Chapter 2 — Deal Room */}
        <section
          id="chapter-deal-room"
          className="scroll-mt-20 border-y border-border/60 bg-card/50 py-20"
        >
          <div className="mx-auto grid max-w-6xl items-center gap-10 px-6 lg:grid-cols-2">
            <FadeUp className="order-2 lg:order-1">
              <div className="rounded-2xl border border-border/60 bg-card p-5 shadow-sm">
                <div className="flex items-center justify-between gap-3">
                  <p className="font-semibold">Deal Room</p>
                  <span className="inline-flex items-center gap-1 rounded-full bg-success px-2.5 py-1 text-xs font-medium text-success-foreground">
                    <Lock className="h-3 w-3" aria-hidden="true" /> Payment secured
                  </span>
                </div>

                <div className="mt-4 rounded-lg border border-border/60 p-4">
                  <p className="text-xs text-muted-foreground">Secured for this deal</p>
                  <p className="mt-1 text-2xl font-bold tracking-tight">₹35,000</p>
                  <p className="mt-1 text-xs text-muted-foreground">
                    Held before filming · releases on approval
                  </p>
                </div>

                <dl className="mt-4 space-y-2 text-sm">
                  <div className="flex justify-between gap-3">
                    <dt className="text-muted-foreground">Deliverables</dt>
                    <dd className="text-right">1 Reel (30–45s) + 3 Stories</dd>
                  </div>
                  <div className="flex justify-between gap-3">
                    <dt className="text-muted-foreground">Revisions</dt>
                    <dd className="text-right">Maximum 2</dd>
                  </div>
                  <div className="flex justify-between gap-3">
                    <dt className="text-muted-foreground">Usage rights</dt>
                    <dd className="text-right">6 months, digital</dd>
                  </div>
                </dl>
                <p className="mt-4 text-center text-xs text-muted-foreground">
                  Illustrative deal — amounts shown are an example, not live data.
                </p>
              </div>
            </FadeUp>

            <FadeUp className="order-1 lg:order-2">
              <p className="text-sm font-semibold uppercase tracking-widest text-muted-foreground">
                Chapter 02 — The Deal Room
              </p>
              <h2 className="mt-3 text-3xl font-bold tracking-tight sm:text-4xl">
                The money is in place before you pick up the camera
              </h2>
              <p className="mt-4 text-muted-foreground">
                When a brand opens a Deal Room with you, funding is part of the flow, not a promise
                for later. You can see the payment is secured before you agree to shoot.
              </p>
              <ul className="mt-6 space-y-3 text-sm text-muted-foreground">
                <li className="flex gap-2">
                  <ShieldCheck className="mt-0.5 h-4 w-4 shrink-0 text-primary" aria-hidden="true" />
                  No advance to negotiate, and nothing to chase afterwards.
                </li>
                <li className="flex gap-2">
                  <FileSignature
                    className="mt-0.5 h-4 w-4 shrink-0 text-primary"
                    aria-hidden="true"
                  />
                  Proposals and counter-offers stay in one thread with the contract.
                </li>
              </ul>
            </FadeUp>
          </div>
        </section>

        {/* --------------------------------------------------- Chapter 3 — Scope */}
        <section id="chapter-scope" className="scroll-mt-20 py-20">
          <div className="mx-auto max-w-4xl px-6">
            <FadeUp>
              <p className="text-center text-sm font-semibold uppercase tracking-widest text-muted-foreground">
                Chapter 03 — Scope and revisions
              </p>
              <h2 className="mt-3 text-center text-3xl font-bold tracking-tight sm:text-4xl">
                The six steps, end to end
              </h2>
              <p className="mx-auto mt-3 max-w-2xl text-center text-muted-foreground">
                The same flow every deal follows, whether it is one reel or a Hype Campaign slot.
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

        {/* -------------------------------------------------- Chapter 4 — Payout */}
        <section
          id="chapter-payout"
          className="scroll-mt-20 border-y border-border/60 bg-card/50 py-20"
        >
          <div className="mx-auto max-w-6xl px-6">
            <FadeUp>
              <div className="mx-auto max-w-3xl text-center">
                <p className="text-sm font-semibold uppercase tracking-widest text-muted-foreground">
                  Chapter 04 — Getting paid
                </p>
                <h2 className="mt-3 text-3xl font-bold tracking-tight sm:text-4xl">
                  Approved means paid, not invoiced
                </h2>
                <p className="mt-4 text-muted-foreground">
                  You do not raise an invoice and wait. Approval releases the payout and the invoice
                  is generated for you, showing gross to net with any recorded TDS on it.
                </p>
              </div>
            </FadeUp>

            <StaggerContainer className="mt-12 grid gap-5 md:grid-cols-2 lg:grid-cols-4">
              {PAYOUT_FLOW.map((s) => {
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

            <FadeUp>
              <div className="mx-auto mt-12 grid max-w-5xl gap-6 lg:grid-cols-2">
                <div className="rounded-2xl border border-border/60 bg-card p-6">
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
                <div className="rounded-2xl border border-primary/30 bg-card p-6">
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
                    Influora charges creators a 15% commission on a completed deal, the same on every
                    plan — see{' '}
                    <Link
                      to="/pricing"
                      className="underline underline-offset-2 hover:text-foreground"
                    >
                      pricing
                    </Link>
                    .
                  </p>
                </div>
              </div>
            </FadeUp>
          </div>
        </section>

        {/* ----------------------------------------------------- Chapter 5 — Hype */}
        <section id="chapter-hype" className="scroll-mt-20 py-20">
          <div className="mx-auto grid max-w-6xl items-center gap-10 px-6 lg:grid-cols-2">
            <FadeUp>
              <Badge className="gap-1 border-hype-border bg-hype text-hype-foreground hover:bg-hype">
                <Zap className="h-3 w-3" aria-hidden="true" /> Chapter 05 — Hype Campaigns
              </Badge>
              <h2 className="mt-3 text-3xl font-bold tracking-tight sm:text-4xl">
                One tap, a flat rate, 72 hours
              </h2>
              <p className="mt-4 text-muted-foreground">
                A Hype Campaign is a brand dropping one source reel at a fixed per-reel rate with a
                capped number of slots. You accept with one tap — no negotiation — post inside the
                window, and the payout releases automatically once the post is verified.
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

            {/*
              IMAGE REMOVED — do not restore this file.

              clean-realistic-close-up-photo-of-modern-smartphone--2c9467.jpg is a
              fabricated UPI receipt: "Payment Successful — ₹1,250.00 — Rahul Sharma —
              UPI ID rahul.sharma@okbank — Reference ID 34B172P10456 — 24 Oct 2023".

              That is an invented financial transaction record, complete with a named
              individual, a UPI handle and a reference number, published on a payouts
              page as if it were a real receipt. The amount also contradicts this
              page's own illustrative figures. A payout screenshot has to be a real
              one with the details redacted, or nothing.
            */}
          </div>
        </section>

        <FunnelCta
          heading="Ready to get discovered?"
          sub="Free to join, list your rate card, and get invited to Deal Rooms where the payment is secured before you film."
          primary={{ label: 'Create a creator account', to: '/creator/register' }}
          secondary={{
            label: 'First, show me how my payment is protected',
            to: '/features/secure-payments',
          }}
          reassurances={['Free to join', 'Paid after approval', 'TDS and invoices handled']}
          className="py-20"
        />
      </main>

      <SiteFooter />
      <StickyCta label="Join as a creator" to="/creator/register" note="Free to join" />
      <StickyCtaSpacer />
    </div>
  );
}
