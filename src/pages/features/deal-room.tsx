import { Link } from 'react-router-dom';
import {
  ArrowRight,
  Check,
  CheckCircle2,
  Clock,
  FileCheck2,
  FileSignature,
  Lock,
  MessageSquareText,
  Repeat,
  ShieldCheck,
  UploadCloud,
  Wallet,
  X,
} from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent } from '@/components/ui/card';
import { FadeUp, StaggerContainer, StaggerItem } from '@/components/motion';
import { SiteHeader } from '@/components/site/SiteHeader';
import { SiteFooter } from '@/components/site/SiteFooter';
import { FaqSection } from '@/components/site/FaqSection';
import { FunnelCta } from '@/components/site/FunnelCta';
import { TrustBar } from '@/components/site/TrustBar';
import { StickyCta, StickyCtaSpacer } from '@/components/site/StickyCta';
import { Seo } from '@/lib/seo/Seo';
import {
  JsonLd,
  getBreadcrumbListSchema,
  getQaPageSchema,
  getWebPageSchema,
} from '@/lib/seo/schema';

/*
  Layout ported from the Stitch design "Influora Deal Room OS" (deal-room-feature-os).
  Their chapter rhythm (a "lifestyle shift" split into an old-way/new-way pair, a
  five-engine feature grid, a live UI walkthrough, testimonials, FAQ) is kept; the
  palette is ours, and every claim has been rewritten to something the product
  actually does. Content per wiki/website/content-map.md §2.2.

  What was deliberately NOT carried over from the design, and why:
    - "escrow", "RBI-regulated nodal vault/trustee", "Scheduled Bank Escrow Trustee",
      "RBI Licensed Escrow Partner". "escrow" is banned in user copy (2026-09-02);
      our partner is a licensed, RBI-authorized Payment Aggregator, and Influora is
      not a trustee.
    - "IT Act 2000 Section 10A Compliant", "ISO 27001 Certified Security",
      "Bank-Grade 256-bit Encryption", "Validated Court Precedent". No regulatory or
      certification claims about Influora itself are permitted.
    - "< 3s" / "Sub-3-Second Settlement" instant UPI, "120h auto-release",
      "99.8% Dispute-Free", "14,000+ deals", "28+ hours saved", "5.1x ROI",
      "48 Creators Synced". None of this is measured. Live copy says payout is
      typically inside 24 hours and campaign performance is creator-reported.
    - "Automated 194J TDS & 26Q Engine", "Real-Time NSDL PAN Validation", "1-Click
      Form 26Q filing". TDS is recorded and shown on payouts and an invoice is
      generated — Influora does not file returns, does not run PAN checks, and does
      not produce Form 26Q.
    - Named people ("Tanvi Shah", "Ananya Sen", "Pooja Malhotra", "Deepika R.",
      "Arjun Sen") and their fabricated testimonials/quotes. Fabricated people.
    - The revision add-on price, the exact vault amount and UPI reference number —
      all invented specifics rendered as if real.

  Every mock/illustrative panel below carries a visible caption, same rule the
  homepage Deal Room card and the how-it-works pages follow.
*/

const HERO_STATS = [
  { value: 'One thread', label: 'Chat, proposal, contract and payment together' },
  { value: 'E-signed', label: 'Every deal, before any work begins' },
  { value: 'Capped', label: 'Revision limit is written into the contract' },
  { value: 'On approval', label: 'Payment releases only after you approve the work' },
] as const;

const OLD_WAY = [
  'Instagram DM → WhatsApp → email → lost proposal',
  '11 PM voice notes with vague feedback and no extra budget agreed',
  'Re-negotiating the same terms across three apps',
  'Revisions with no ceiling — "just one more version" until it is unpaid work',
  'Chasing an invoice for weeks after the work went live',
] as const;

const NEW_WAY = [
  'One thread: chat, proposal, counter-offer, contract, deliverable',
  'Full history saved — nothing gets lost mid-negotiation',
  'Both sides e-sign the generated contract in the same thread',
  'A revision limit is written into the contract, not negotiated after the fact',
  'Deliverables upload and get approved without leaving the Deal Room',
] as const;

const ENGINES = [
  {
    icon: FileSignature,
    title: 'Proposal builder + e-sign',
    body: 'Deliverable type, rate, timeline, revision count and usage rights are structured fields. Once agreed, Influora generates the contract and both sides e-sign in the thread.',
  },
  {
    icon: Wallet,
    title: 'Funds secured before filming',
    body: 'The brand deposits the full deal amount with a licensed, RBI-authorized Payment Aggregator before the creator starts producing content.',
  },
  {
    icon: Lock,
    title: 'Locked while the work is in review',
    body: 'Neither side can pull the funds back while the deliverable is being reviewed — the creator sees the payment is secured on the invite.',
  },
  {
    icon: MessageSquareText,
    title: 'Timestamped deliverable review',
    body: 'Creators upload the draft directly in the Deal Room; brands approve or request revisions against it, within the revision limit written into the contract.',
  },
  {
    icon: Repeat,
    title: 'Payout on approval',
    body: 'Approving the deliverable releases the payment and generates the invoice, with any recorded TDS shown on it.',
  },
] as const;

const INCLUDED = [
  {
    icon: MessageSquareText,
    title: 'In-thread messaging',
    body: 'Chat directly with the other side — no switching to WhatsApp or DMs mid-deal.',
  },
  {
    icon: FileCheck2,
    title: 'Proposal builder',
    body: 'Deliverable type, rate, timeline, and revision count are structured fields, not a paragraph of text.',
  },
  {
    icon: Repeat,
    title: 'Counter-offer flow',
    body: 'Either side can counter a proposal without starting the conversation over.',
  },
  {
    icon: FileCheck2,
    title: 'Contract + e-sign',
    body: 'Once terms are agreed, Influora generates the contract and both sides e-sign in the thread.',
  },
  {
    icon: UploadCloud,
    title: 'Deliverable upload + approval',
    body: 'Creators submit work directly in the Deal Room; brands approve or request revisions on the spot.',
  },
];

const CANONICAL_QUESTION = 'What is a Deal Room in influencer marketing?';
const CANONICAL_ANSWER =
  'A Deal Room is a single shared workspace where one brand and one creator settle one ' +
  'collaboration. The chat, the proposal and counter-offers, the deliverables list, the ' +
  'e-signed contract and the payment all live in that one thread, instead of being spread ' +
  'across WhatsApp, email and a separate invoice.';

const FAQS = [
  { question: CANONICAL_QUESTION, answer: CANONICAL_ANSWER },
  {
    question: 'How is a Deal Room different from a group chat with a creator?',
    answer:
      'A group chat holds messages; a Deal Room holds the agreement. Scope, rate, deliverables, deadlines and revision limits are structured fields rather than things someone typed and both sides half-remember, and the contract and payment are attached to those fields — so what was agreed and what is owed cannot drift apart.',
  },
  {
    question: 'Who can see a Deal Room?',
    answer:
      'Only the brand team members on that campaign and the one creator in that deal. Each brand-creator collaboration gets its own Deal Room, so creators never see each other’s rates or terms.',
  },
  {
    question: 'When does the brand fund the deal, and what happens if it stays silent?',
    answer:
      'The brand funds the deal as soon as the contract is e-signed, before the creator starts producing content. If a brand does not review a submitted deliverable, the creator can open the issue in the Deal Room rather than the deal simply stalling.',
  },
  {
    question: 'Can terms be changed after the contract is signed?',
    answer:
      'Not silently. Once both sides e-sign, the agreed scope and amount are locked to that contract. A change means a new proposal in the same Deal Room, which both sides have to accept — so there is always a record of what changed and when.',
  },
];

export default function DealRoomFeaturePage() {
  return (
    <div className="min-h-screen bg-background text-foreground">
      <Seo
        title="Deal Room — Negotiate Brand Deals in One Thread"
        description="The Deal Room replaces Instagram DMs, WhatsApp, and email with one thread: chat, proposals, counter-offers, contracts, and deliverables — all in one place."
        canonical="/features/deal-room"
      />
      <JsonLd
        data={getWebPageSchema({
          name: 'Deal Room — negotiate, contract and pay in one thread',
          description: CANONICAL_ANSWER,
          url: '/features/deal-room',
        })}
      />
      <JsonLd
        data={getQaPageSchema({
          question: CANONICAL_QUESTION,
          answer: CANONICAL_ANSWER,
          url: '/features/deal-room',
        })}
      />
      <JsonLd
        data={getBreadcrumbListSchema([
          { name: 'Home', url: '/' },
          { name: 'Features', url: '/features/deal-room' },
          { name: 'Deal Room', url: '/features/deal-room' },
        ])}
      />

      <SiteHeader />

      <main>
        {/* ---------------------------------------------------------------- Hero */}
        <section className="relative overflow-hidden border-b border-border/60">
          <div className="mx-auto grid max-w-6xl items-center gap-10 px-6 py-16 lg:grid-cols-2 lg:py-24">
            <FadeUp>
              <Badge variant="outline" className="gap-1.5">
                <MessageSquareText className="h-3 w-3" aria-hidden="true" /> Deal Room
              </Badge>
              <h1 className="mt-4 text-4xl font-bold leading-tight tracking-tight sm:text-5xl">
                Every negotiation, one thread
              </h1>
              <p className="mt-4 text-lg text-muted-foreground">
                Chat, proposals, counter-offers, contracts, and deliverables — the Deal Room
                replaces the Instagram DM → WhatsApp → email → lost-proposal cycle with a single
                record both sides can trust, and a payment that is secured before any work
                begins.
              </p>
              <div className="mt-8 flex flex-wrap gap-3">
                <Button
                  size="lg"
                  className="bg-accent-foreground text-white hover:bg-accent-foreground/90"
                  asChild
                >
                  <Link to="/brand/register">
                    Try Deal Room <ArrowRight className="ml-1.5 h-4 w-4" aria-hidden="true" />
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

            {/* Active deal mock — illustrative */}
            <FadeUp delay={0.1}>
              <div className="rounded-2xl border border-border/60 bg-card p-5 shadow-sm">
                <div className="flex items-center justify-between gap-3">
                  <p className="font-semibold">Deal Room</p>
                  <span className="inline-flex items-center gap-1 rounded-full bg-success px-2.5 py-1 text-xs font-medium text-success-foreground">
                    <Lock className="h-3 w-3" aria-hidden="true" /> Payment secured
                  </span>
                </div>
                <p className="mt-3 text-sm text-muted-foreground">
                  1 Instagram Reel (4K) + 2 Story sequences · Mumbai
                </p>

                <dl className="mt-4 grid grid-cols-2 gap-3">
                  <div className="rounded-lg bg-muted/60 p-3">
                    <dt className="text-xs text-muted-foreground">Deal amount</dt>
                    <dd className="mt-0.5 font-semibold">₹45,000</dd>
                  </div>
                  <div className="rounded-lg bg-muted/60 p-3">
                    <dt className="text-xs text-muted-foreground">Revisions</dt>
                    <dd className="mt-0.5 font-semibold">2 of 2 left</dd>
                  </div>
                </dl>

                <div className="mt-4 flex items-center gap-2 rounded-lg border border-border/60 p-3 text-sm">
                  <FileSignature className="h-4 w-4 shrink-0 text-primary" aria-hidden="true" />
                  Contract e-signed by both sides
                </div>
                <p className="mt-4 text-center text-xs text-muted-foreground">
                  Illustrative deal — amounts shown are an example, not live data.
                </p>
              </div>
            </FadeUp>
          </div>
        </section>

        <TrustBar />

        {/* ---------------------------------------------------- Old way vs new way */}
        <section className="border-b border-border/60 bg-card/50 py-20">
          <div className="mx-auto max-w-6xl px-6">
            <FadeUp className="mx-auto max-w-xl text-center">
              <h2 className="text-3xl font-semibold">The old way vs. the Deal Room way</h2>
              <p className="mt-3 text-muted-foreground">
                Moving a collaboration out of scattered DMs and into one workspace changes what a
                negotiation actually feels like, for both sides.
              </p>
            </FadeUp>
            <div className="mt-12 grid gap-6 lg:grid-cols-2">
              <FadeUp>
                <Card className="h-full border-destructive/30">
                  <CardContent className="p-6">
                    <h3 className="font-semibold text-muted-foreground">The old way</h3>
                    <ul className="mt-4 space-y-3">
                      {OLD_WAY.map((line) => (
                        <li
                          key={line}
                          className="flex items-start gap-2.5 text-sm text-muted-foreground"
                        >
                          <X
                            className="mt-0.5 h-4 w-4 shrink-0 text-destructive-foreground"
                            aria-hidden="true"
                          />
                          {line}
                        </li>
                      ))}
                    </ul>
                  </CardContent>
                </Card>
              </FadeUp>
              <FadeUp delay={0.1}>
                <Card className="h-full border-accent-foreground/30">
                  <CardContent className="p-6">
                    <h3 className="font-semibold">The Deal Room way</h3>
                    <ul className="mt-4 space-y-3">
                      {NEW_WAY.map((line) => (
                        <li key={line} className="flex items-start gap-2.5 text-sm text-foreground">
                          <Check
                            className="mt-0.5 h-4 w-4 shrink-0 text-accent-foreground"
                            aria-hidden="true"
                          />
                          {line}
                        </li>
                      ))}
                    </ul>
                  </CardContent>
                </Card>
              </FadeUp>
            </div>
          </div>
        </section>

        {/* --------------------------------------------- Five engines / what's inside */}
        <section className="py-20">
          <div className="mx-auto max-w-6xl px-6">
            <FadeUp className="mx-auto max-w-xl text-center">
              <h2 className="text-3xl font-semibold">What powers every Deal Room</h2>
              <p className="mt-3 text-muted-foreground">
                Five parts working together to replace fragmented DMs and verbal promises with one
                structured, e-signed agreement.
              </p>
            </FadeUp>
            <StaggerContainer className="mt-12 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
              {ENGINES.map((item, i) => {
                const Icon = item.icon;
                return (
                  <StaggerItem key={item.title}>
                    <Card className="h-full">
                      <CardContent className="p-6">
                        <div className="flex items-center justify-between">
                          <span className="flex h-10 w-10 items-center justify-center rounded-lg bg-accent text-accent-foreground">
                            <Icon className="h-5 w-5" aria-hidden="true" />
                          </span>
                          <span className="text-xs font-semibold tracking-widest text-muted-foreground/70">
                            {String(i + 1).padStart(2, '0')}
                          </span>
                        </div>
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

        {/* ------------------------------------------------------- Live UI preview */}
        <section className="border-y border-border/60 bg-card/50 py-20">
          <div className="mx-auto max-w-6xl px-6">
            <FadeUp className="mx-auto max-w-xl text-center">
              <h2 className="text-3xl font-semibold">Inside the Deal Room</h2>
              <p className="mt-3 text-muted-foreground">
                Where the review, the scope and the payment converge — instead of living in three
                different apps.
              </p>
            </FadeUp>

            <div className="mt-12 grid gap-6 lg:grid-cols-5 lg:items-start">
              {/* Deliverable review mock */}
              <FadeUp className="lg:col-span-3">
                <div className="overflow-hidden rounded-2xl border border-border/60 bg-card shadow-sm">
                  {/*
                    Real capture (ci/product-shots.mjs), cropped from the top so the
                    header and first rows read as a banner. Replaced an AI photo of a
                    tablet running an invented interface.
                  */}
                  <img
                    src="/product-shots/brand-deals.png"
                    alt="The Influora Deal Room listing active deals and their current stage."
                    loading="lazy"
                    className="h-56 w-full border-b border-border/60 object-cover object-top"
                  />
                  <p className="px-5 pt-3 text-[11px] text-muted-foreground">Real Influora screen · sample data, not live data</p>
                  <div className="p-5">
                    <div className="flex items-center justify-between gap-3">
                      <p className="font-semibold">Deliverable review</p>
                      <span className="inline-flex items-center gap-1 rounded-full bg-muted px-2.5 py-1 text-xs text-muted-foreground">
                        <Clock className="h-3 w-3" aria-hidden="true" /> 1st cut submitted
                      </span>
                    </div>
                    <div className="mt-4 space-y-3 border-t border-border/60 pt-4">
                      <div className="rounded-lg bg-muted/60 p-3">
                        <p className="text-sm">
                          Comment at 0:14 — could we hold the product shot for a second longer
                          before the cut?
                        </p>
                        <p className="mt-1.5 text-xs text-muted-foreground">Brand · pinned to 0:14</p>
                      </div>
                      <div className="rounded-lg border border-primary/20 bg-accent/60 p-3">
                        <p className="text-sm">
                          Done — re-uploaded as v1.2 with the extended hold.
                        </p>
                        <p className="mt-1.5 text-xs text-muted-foreground">Creator · 20 min ago</p>
                      </div>
                    </div>
                    <p className="mt-4 text-center text-xs text-muted-foreground">
                      Illustrative review thread — figures shown are an example, not live data.
                    </p>
                  </div>
                </div>
              </FadeUp>

              {/* Deal terms mock */}
              <FadeUp delay={0.1} className="lg:col-span-2">
                <div className="rounded-2xl border border-border/60 bg-card p-5 shadow-sm">
                  <div className="flex items-center justify-between gap-3">
                    <p className="font-semibold">Deal terms</p>
                    <span className="inline-flex items-center gap-1 rounded-full bg-success px-2.5 py-1 text-xs font-medium text-success-foreground">
                      <ShieldCheck className="h-3 w-3" aria-hidden="true" /> Secured
                    </span>
                  </div>
                  <dl className="mt-4 space-y-2 text-sm">
                    <div className="flex justify-between gap-3">
                      <dt className="text-muted-foreground">Deal amount</dt>
                      <dd className="text-right font-medium">₹45,000</dd>
                    </div>
                    <div className="flex justify-between gap-3">
                      <dt className="text-muted-foreground">Recorded TDS (194J)</dt>
                      <dd className="text-right">₹4,500</dd>
                    </div>
                    <div className="flex justify-between gap-3 border-t border-border/60 pt-2">
                      <dt className="text-muted-foreground">Net payout</dt>
                      <dd className="text-right font-semibold">₹40,500</dd>
                    </div>
                  </dl>
                  <div className="mt-4 flex items-center gap-2 rounded-lg border border-border/60 p-3 text-sm">
                    <CheckCircle2 className="h-4 w-4 shrink-0 text-primary" aria-hidden="true" />
                    1 of 2 revisions used
                  </div>
                  <p className="mt-4 text-center text-xs text-muted-foreground">
                    Illustrative terms — TDS is recorded and shown on the payout, not filed for
                    you. See the{' '}
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

        {/* ---------------------------------------------------------- What's included */}
        <section className="py-20">
          <div className="mx-auto max-w-6xl px-6">
            <FadeUp className="mx-auto max-w-xl text-center">
              <h2 className="text-3xl font-semibold">What&apos;s included</h2>
            </FadeUp>
            <StaggerContainer className="mt-12 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
              {INCLUDED.map((item) => {
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

        {/* ---------------------------------------------------------- Testimonial-ish */}
        <section className="border-t border-border/60 bg-card/50 py-20">
          <div className="mx-auto grid max-w-6xl gap-6 px-6 sm:grid-cols-2">
            <FadeUp>
              <Card className="h-full overflow-hidden">
                <img
                  src="/product-shots/brand-dashboard.png"
                  alt="A brand's Influora dashboard showing campaigns and their current status."
                  loading="lazy"
                  className="h-40 w-full border-b border-border/60 object-cover object-top"
                />
                <p className="px-6 pt-3 text-[11px] text-muted-foreground">Real Influora screen · sample data, not live data</p>
                <CardContent className="p-6">
                  <h3 className="font-semibold">Why brand teams use it</h3>
                  <p className="mt-2 text-sm text-muted-foreground">
                    One thread per creator instead of a spreadsheet of DMs and forwarded PDFs.
                    Scope, revisions and the contract stay attached to the deal they belong to.
                  </p>
                </CardContent>
              </Card>
            </FadeUp>
            <FadeUp delay={0.1}>
              <Card className="h-full overflow-hidden">
                <img
                  src="/product-shots/creator-deals.png"
                  alt="A creator's Influora deals list, showing each deal and its payout status."
                  loading="lazy"
                  className="h-40 w-full border-b border-border/60 object-cover object-top"
                />
                <p className="px-6 pt-3 text-[11px] text-muted-foreground">Real Influora screen · sample data, not live data</p>
                <CardContent className="p-6">
                  <h3 className="font-semibold">Why creators use it</h3>
                  <p className="mt-2 text-sm text-muted-foreground">
                    The rate, the deliverables and the revision cap are in the contract before
                    filming starts — not something to re-argue after the shoot.
                  </p>
                </CardContent>
              </Card>
            </FadeUp>
          </div>
        </section>

        <FaqSection heading="Deal Room questions, answered" items={FAQS} />

        <FunnelCta
          heading="Open your first Deal Room"
          sub="Every negotiation ends in an e-signed contract and a funded deal — in the same thread it started in."
          primary={{ label: 'Create a brand account', to: '/brand/register' }}
          secondary={{ label: 'See how the payment is protected', to: '/features/secure-payments' }}
          reassurances={['Free to start', 'Contracts generated for you', 'No separate invoicing']}
        />
      </main>

      <SiteFooter />
      <StickyCta label="Create a brand account" to="/brand/register" note="Free to start" />
      <StickyCtaSpacer />
    </div>
  );
}
