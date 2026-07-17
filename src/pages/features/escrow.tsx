import { Link } from 'react-router-dom';
import { ArrowRight, Banknote, Gavel, Lock, ShieldCheck, Wallet } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent } from '@/components/ui/card';
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from '@/components/ui/accordion';
import { FadeUp, StaggerContainer, StaggerItem } from '@/components/motion';
import { EscrowFlowAnimation } from '@/components/motion/EscrowFlowAnimation';
import { SiteHeader } from '@/components/site/SiteHeader';
import { SiteFooter } from '@/components/site/SiteFooter';
import { Seo } from '@/lib/seo/Seo';
import { JsonLd, getBreadcrumbListSchema, getFaqPageSchema } from '@/lib/seo/schema';

// Content per wiki/website/content-map.md §2.1 + homepage-copy.md §5.
// Primary GEO escrow page — CEO-DECISIONS.md Tier 2 build scope item 1.

const WHY_IT_MATTERS = [
  {
    icon: ShieldCheck,
    title: 'For brands',
    body: 'No advance-payment risk. The creator must deliver an approved deliverable before escrow releases a single rupee — you never pay for work that never shows up.',
  },
  {
    icon: Wallet,
    title: 'For creators',
    body: "Guaranteed payment. The brand's money is already locked in escrow before you film a single reel, so there's no invoice-chasing, no \"will pay you next week.\"",
  },
];

const DISPUTE_STEPS = [
  {
    icon: Lock,
    title: 'Escrow freezes',
    body: 'The moment a dispute is opened in the Deal Room, the escrowed funds are frozen — neither side can touch them while it\'s reviewed.',
  },
  {
    icon: Gavel,
    title: 'Influora mediates',
    body: 'Both sides submit their side of the story and any evidence (briefs, deliverables, message history) directly in the Deal Room thread.',
  },
  {
    icon: Banknote,
    title: 'Funds move on resolution',
    body: 'Depending on the outcome, escrow either releases to the creator or refunds to the brand. No manual bank transfers, no chasing either side.',
  },
];

const FAQS = [
  {
    question: 'What is escrow, in plain terms?',
    answer:
      'Escrow is money held by a neutral third party — Influora — until both sides of a deal have done their part. The brand deposits the deal amount up front; Influora holds it; the creator delivers; the brand approves; escrow releases the payment. Neither side can access the funds outside that flow.',
  },
  {
    question: 'When does the brand fund escrow?',
    answer:
      'As soon as the contract is signed in the Deal Room — before the creator starts producing content. This is what guarantees payment for the creator.',
  },
  {
    question: "What happens if the creator doesn't deliver?",
    answer:
      "If the creator never submits a deliverable within the agreed window, the brand can open a dispute and the escrowed funds are refunded once it's resolved — the brand never loses money for work that never happened.",
  },
  {
    question: "What if the brand doesn't approve the deliverable?",
    answer:
      "The brand can request revisions within the contract's revision limit. If the two sides still can't agree after that, either party can open a dispute and Influora mediates using the deliverable, the brief, and the Deal Room message history as evidence.",
  },
  {
    question: 'How fast does payout happen after approval?',
    answer:
      "Escrow releases automatically the moment the brand approves the deliverable. Payout typically reaches the creator's UPI or bank account within 24 hours.",
  },
  {
    question: 'Is escrow a separate fee?',
    answer:
      "Escrow protection is part of the platform's core flow, not a separate line-item fee. See the Pricing page for the current fee structure.",
  },
];

export default function EscrowFeaturePage() {
  return (
    <div className="min-h-screen bg-background text-foreground">
      <Seo
        title="Escrow Protection for Influencer Deals"
        description="Every Influora deal is paid through escrow — locked at signing, released only after the brand approves the work. No advance-payment risk, no chasing invoices."
        canonical="/features/escrow"
      />
      <JsonLd
        data={[
          getFaqPageSchema(FAQS),
          getBreadcrumbListSchema([
            { name: 'Home', url: '/' },
            { name: 'Features', url: '/features/escrow' },
            { name: 'Escrow Protection', url: '/features/escrow' },
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
                <ShieldCheck className="h-3 w-3" aria-hidden="true" /> Escrow Protection
              </Badge>
              <h1 className="mt-4 text-4xl font-bold leading-tight tracking-tight sm:text-5xl">
                Every Influora deal is paid through escrow
              </h1>
              <p className="mt-4 text-lg text-muted-foreground">
                Escrow is money held by a neutral third party until both sides deliver. On Influora, the
                brand funds the deal when the contract is signed, and Influora releases payment to the
                creator only after the brand approves the work.
              </p>
              <div className="mt-8 flex flex-wrap justify-center gap-3">
                <Button size="lg" className="bg-accent-foreground text-white hover:bg-accent-foreground/90" asChild>
                  <Link to="/brand/register">
                    Start a campaign <ArrowRight className="ml-1.5 h-4 w-4" aria-hidden="true" />
                  </Link>
                </Button>
                <Button size="lg" variant="outline" asChild>
                  <Link to="/how-it-works/brands">See how it works</Link>
                </Button>
              </div>
            </FadeUp>
          </div>
        </section>

        {/* Escrow flow — reused scroll animation from the homepage */}
        <EscrowFlowAnimation />

        {/* Why it matters */}
        <section className="border-t border-border/60 bg-card/50 py-20">
          <div className="mx-auto max-w-6xl px-6">
            <FadeUp className="mx-auto max-w-xl text-center">
              <h2 className="text-3xl font-semibold">Why escrow matters</h2>
              <p className="mt-3 text-muted-foreground">
                Influencer deals break down for one reason more than any other: someone doesn't get paid,
                or someone doesn't deliver. Escrow removes that risk for both sides.
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
                Escrow funds move through Influora's payment gateway partner, not a private bank account.
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
                Disputes are rare, but escrow means neither side is exposed while one is resolved.
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

        {/* FAQ */}
        <section className="border-t border-border/60 py-20" aria-label="Frequently asked questions">
          <div className="mx-auto max-w-3xl px-6">
            <FadeUp className="text-center">
              <h2 className="text-3xl font-semibold">Escrow questions, answered</h2>
            </FadeUp>
            <FadeUp delay={0.1} className="mt-10">
              <Accordion type="single" collapsible className="w-full">
                {FAQS.map((faq) => (
                  <AccordionItem key={faq.question} value={faq.question}>
                    <AccordionTrigger className="text-left text-base font-medium">
                      {faq.question}
                    </AccordionTrigger>
                    <AccordionContent className="text-muted-foreground">{faq.answer}</AccordionContent>
                  </AccordionItem>
                ))}
              </Accordion>
            </FadeUp>
          </div>
        </section>

        {/* Cross-links + final CTA */}
        <section className="border-t border-border/60 py-20">
          <FadeUp className="mx-auto max-w-2xl px-6 text-center">
            <h2 className="text-3xl font-semibold">See escrow in the full deal flow</h2>
            <p className="mt-3 text-muted-foreground">
              Walk through exactly where escrow fits — from campaign creation to payout.
            </p>
            <div className="mt-8 flex flex-wrap justify-center gap-3">
              <Button size="lg" className="bg-accent-foreground text-white hover:bg-accent-foreground/90" asChild>
                <Link to="/how-it-works/brands">
                  How It Works for brands <ArrowRight className="ml-1.5 h-4 w-4" aria-hidden="true" />
                </Link>
              </Button>
              <Button size="lg" variant="outline" asChild>
                <Link to="/pricing">See pricing</Link>
              </Button>
            </div>
          </FadeUp>
        </section>
      </main>

      <SiteFooter />
    </div>
  );
}
