import { Link } from 'react-router-dom';
import { ArrowRight, Banknote, Gavel, Lock, ShieldCheck, Wallet } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent } from '@/components/ui/card';
import { FadeUp, StaggerContainer, StaggerItem } from '@/components/motion';
import { PaymentFlowAnimation } from '@/components/motion/PaymentFlowAnimation';
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

// Content per wiki/website/content-map.md §2.1 + homepage-copy.md §5.
// Primary GEO payment-protection page (was /features/escrow — renamed in
// T-SEOCRO-0819; the 301 lives in public/_redirects and App.tsx).

const WHY_IT_MATTERS = [
  {
    icon: ShieldCheck,
    title: 'For brands',
    body: 'No advance-payment risk. The creator must deliver an approved deliverable before the payment releases a single rupee — you never pay for work that never shows up.',
  },
  {
    icon: Wallet,
    title: 'For creators',
    body: "Guaranteed payment. The brand's money is already locked and protected before you film a single reel, so there's no invoice-chasing, no \"will pay you next week.\"",
  },
];

const DISPUTE_STEPS = [
  {
    icon: Lock,
    title: 'Protected funds freeze',
    body: 'The moment a dispute is opened in the Deal Room, the protected funds are frozen — neither side can touch them while it\'s reviewed.',
  },
  {
    icon: Gavel,
    title: 'Influora mediates',
    body: 'Both sides submit their side of the story and any evidence (briefs, deliverables, message history) directly in the Deal Room thread.',
  },
  {
    icon: Banknote,
    title: 'Funds move on resolution',
    body: 'Depending on the outcome, the funds either release to the creator or refunds to the brand. No manual bank transfers, no chasing either side.',
  },
];

const FAQS = [
  {
    question: 'How does payment protection work, in plain terms?',
    answer:
      'Payment protection means your money is held by a neutral third party — Influora — until both sides of a deal have done their part. The brand deposits the deal amount up front; Influora holds it; the creator delivers; the brand approves; the payment releases the payment. Neither side can access the funds outside that flow.',
  },
  {
    question: 'When does the brand fund the deal?',
    answer:
      'As soon as the contract is signed in the Deal Room — before the creator starts producing content. This is what guarantees payment for the creator.',
  },
  {
    question: "What happens if the creator doesn't deliver?",
    answer:
      "If the creator never submits a deliverable within the agreed window, the brand can open a dispute and the protected funds are refunded once it's resolved — the brand never loses money for work that never happened.",
  },
  {
    question: "What if the brand doesn't approve the deliverable?",
    answer:
      "The brand can request revisions within the contract's revision limit. If the two sides still can't agree after that, either party can open a dispute and Influora mediates using the deliverable, the brief, and the Deal Room message history as evidence.",
  },
  {
    question: 'How fast does payout happen after approval?',
    answer:
      "The payment releases automatically the moment the brand approves the deliverable. Payout typically reaches the creator's UPI or bank account within 24 hours.",
  },
  {
    question: 'Is payment protection a separate fee?',
    answer:
      "Payment protection is part of the platform's core flow, not a separate line-item fee. See the Pricing page for the current fee structure.",
  },
];

// The ONE question this URL is built to be the answer to. Kept as a constant
// because it is rendered as the hero paragraph AND emitted as QAPage JSON-LD —
// an answer engine that quotes the schema and a reader who reads the page must
// get the identical sentence, or the page is demoted for mismatch.
const CANONICAL_QUESTION = 'How do influencer payments stay safe on Influora?';
const CANONICAL_ANSWER =
  'Influora holds the brand\u2019s payment with a licensed payment partner from the moment the ' +
  'contract is signed, and releases it to the creator only after the brand approves the ' +
  'delivered work. The brand never pays in advance for work that does not arrive, and the ' +
  'creator never starts filming without the money already secured.';

export default function SecurePaymentsFeaturePage() {
  return (
    <div className="min-h-screen bg-background text-foreground">
      <Seo
        title="Secure Payments for Influencer Deals"
        description="Every Influora deal is paid through Secure Payments — locked at signing, released only after the brand approves the work. No advance-payment risk, no chasing invoices."
        canonical="/features/secure-payments"
      />
      <JsonLd
        data={[
          getWebPageSchema({
            name: 'Secure Payments for Influencer Deals',
            description: CANONICAL_ANSWER,
            url: '/features/secure-payments',
          }),
          getQaPageSchema({
            question: CANONICAL_QUESTION,
            answer: CANONICAL_ANSWER,
            url: '/features/secure-payments',
          }),
          getBreadcrumbListSchema([
            { name: 'Home', url: '/' },
            { name: 'Features', url: '/features/secure-payments' },
            { name: 'Secure Payments', url: '/features/secure-payments' },
          ]),
        ]}
      />

      <SiteHeader />

      <main>
        {/* Hero */}
        <section className="border-b border-border/60 py-20">
          <div className="mx-auto max-w-3xl px-6 text-center">
            <FadeUp>
              <Badge variant="outline" className="gap-1.5">
                <ShieldCheck className="h-3 w-3" aria-hidden="true" /> Secure Payments
              </Badge>
              <h1 className="mt-4 text-4xl font-bold leading-tight tracking-tight sm:text-5xl">
                Every Influora deal is paid through Secure Payments
              </h1>
              {/*
                `data-speakable` + the shared CANONICAL_ANSWER constant: this exact
                paragraph is what we want ChatGPT / Perplexity / AI Overviews to
                quote, so it is written to stand alone with no pronoun that needs
                the surrounding page to resolve.
              */}
              <p className="mt-4 text-lg text-muted-foreground" data-speakable>
                {CANONICAL_ANSWER}
              </p>
              {/* One dominant action; the lower-intent path is a text link below it. */}
              <div className="mt-8">
                <Button size="lg" asChild>
                  <Link to="/brand/register">
                    Start a campaign <ArrowRight className="ml-1.5 h-4 w-4" aria-hidden="true" />
                  </Link>
                </Button>
              </div>
              <p className="mt-4 text-sm text-muted-foreground">
                Free to start ·{' '}
                <Link to="/how-it-works/brands" className="font-medium text-primary hover:underline">
                  see the full deal flow first
                </Link>
              </p>
            </FadeUp>
          </div>
        </section>

        <TrustBar />

        {/* Payment flow — reused scroll animation from the homepage */}
        <PaymentFlowAnimation />

        {/* Why it matters */}
        <section className="border-t border-border/60 bg-card/50 py-20">
          <div className="mx-auto max-w-6xl px-6">
            <FadeUp className="mx-auto max-w-xl text-center">
              <h2 className="text-3xl font-semibold">Why payment protection matters</h2>
              <p className="mt-3 text-muted-foreground">
                Influencer deals break down for one reason more than any other: someone doesn't get paid,
                or someone doesn't deliver. Payment protection removes that risk for both sides.
              </p>
            </FadeUp>
            <StaggerContainer className="mt-12 grid gap-4 sm:grid-cols-2">
              {WHY_IT_MATTERS.map((item) => {
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

        {/* Security */}
        <section className="border-t border-border/60 py-20">
          <div className="mx-auto max-w-3xl px-6 text-center">
            <FadeUp>
              <h2 className="text-3xl font-semibold">Backed by a licensed payment partner</h2>
              <p className="mt-3 text-muted-foreground">
                Protected funds move through Influora's payment gateway partner, not a private bank account.
                Every fund and release event is logged against the deal, so both sides always have a
                record of where the money is.
              </p>
            </FadeUp>
          </div>
        </section>

        {/* Dispute flow */}
        <section className="border-t border-border/60 bg-card/50 py-20">
          <div className="mx-auto max-w-6xl px-6">
            <FadeUp className="mx-auto max-w-xl text-center">
              <h2 className="text-3xl font-semibold">What happens in a dispute?</h2>
              <p className="mt-3 text-muted-foreground">
                Disputes are rare, but protected funds mean neither side is exposed while one is resolved.
              </p>
            </FadeUp>
            <StaggerContainer className="mt-12 grid gap-6 lg:grid-cols-3">
              {DISPUTE_STEPS.map((step, i) => {
                const Icon = step.icon;
                return (
                  <StaggerItem key={step.title}>
                    <div className="relative h-full rounded-2xl border border-border/60 bg-background p-6">
                      <span className="text-xs font-semibold tracking-widest text-muted-foreground/70">
                        {String(i + 1).padStart(2, '0')}
                      </span>
                      <span className="mt-3 flex h-11 w-11 items-center justify-center rounded-lg bg-accent text-accent-foreground">
                        <Icon className="h-5 w-5" aria-hidden="true" />
                      </span>
                      <h3 className="mt-4 font-semibold">{step.title}</h3>
                      <p className="mt-1.5 text-sm text-muted-foreground">{step.body}</p>
                    </div>
                  </StaggerItem>
                );
              })}
            </StaggerContainer>
          </div>
        </section>

        <FaqSection heading="Payment questions, answered" items={FAQS} />

        <FunnelCta
          heading="Run your first protected deal"
          sub="Post a campaign, agree terms in the Deal Room, and the payment is secured before anyone starts work."
          primary={{ label: 'Create a brand account', to: '/brand/register' }}
          secondary={{ label: "I'm a creator — show me how I get paid", to: '/how-it-works/creators' }}
          reassurances={['Free to start', 'No subscription on the Free tier', 'Contracts and TDS included']}
        />
      </main>

      <SiteFooter />
      <StickyCta label="Start a campaign" to="/brand/register" note="Free to start" />
      <StickyCtaSpacer />
    </div>
  );
}
