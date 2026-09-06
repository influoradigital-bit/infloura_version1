import { lazy, Suspense } from 'react';
import { Link } from 'react-router-dom';
import { ArrowRight, BellRing, FileText, ListChecks, Search, Wallet } from 'lucide-react';

import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { FadeUp, StaggerContainer, StaggerItem } from '@/components/motion';
import { FunnelCta } from '@/components/site/FunnelCta';
import { MeeraPricingPoll } from '@/components/site/MeeraPricingPoll';
import { SiteFooter } from '@/components/site/SiteFooter';
import { SiteHeader } from '@/components/site/SiteHeader';
import { Seo } from '@/lib/seo/Seo';
import { JsonLd, getBreadcrumbListSchema, getWebPageSchema } from '@/lib/seo/schema';

/**
 * Meera for Creators — "coming soon" page with a scripted phone demo and a
 * three-option pricing poll.
 *
 * The phone plays a Remotion composition (`src/remotion/MeeraDemo.tsx`): every
 * line Meera says is predefined in `src/remotion/script.ts` (Hinglish, with a
 * Sarvam voice), nothing calls the real AI. The Player and its dependencies
 * are lazy-loaded so the rest of the marketing site does not carry Remotion in
 * its main chunk.
 *
 * Page copy is English and future tense: Phase A is coded but not deployed,
 * later phases are specs. The storyboard was truth-checked by the CTO against
 * the phase specs (2026-09-05); keep the five cards in sync with the demo
 * script if either changes.
 */

const MeeraDemoPlayer = lazy(() =>
  import('@/components/site/MeeraDemoPlayer').then((m) => ({ default: m.MeeraDemoPlayer })),
);

const CARDS = [
  {
    icon: ListChecks,
    title: 'Your rules come first',
    body: 'Your floor rate, your language, the brands you will not work with. A brand never sees your floor.',
  },
  {
    icon: FileText,
    title: 'Paste a brief, get a straight answer',
    body: 'What is on offer, what is wrong with it, and what to ask for. Meera drafts the reply; you send it.',
  },
  {
    icon: Wallet,
    title: 'Know where the money is',
    body: 'Funds secured, released, in your bank. Every step visible, on WhatsApp too.',
  },
  {
    icon: BellRing,
    title: 'A short note every Monday',
    body: 'One thing to post, one thing to fix. A flag before a mistake, not after.',
  },
  {
    icon: Search,
    title: 'Find new brands',
    body: 'Meera brings the list, you write two lines in your own words, and you read every pitch before it goes.',
  },
] as const;

function PhoneSkeleton() {
  return (
    <div
      className="mx-auto aspect-[9/16] w-full max-w-[360px] animate-pulse rounded-[2.5rem] bg-muted"
      aria-hidden="true"
    />
  );
}

export default function MeeraForCreatorsPage() {
  return (
    <div className="min-h-screen bg-background text-foreground">
      <Seo
        title="Meera for Creators"
        description="Your own PR manager, on your side. Paste a brand brief and Meera reads it, tells you the rate, and drafts the reply. Coming soon on Influora."
        canonical="/meera-for-creators"
      />
      <JsonLd
        data={getWebPageSchema({
          name: 'Meera for Creators',
          description:
            'A scripted preview of Meera, the creator-side AI PR manager coming to Influora: brief reading, rate guidance, approval-gated replies, money tracking, weekly notes, and brand discovery.',
          url: '/meera-for-creators',
        })}
      />
      <JsonLd
        data={getBreadcrumbListSchema([
          { name: 'Home', url: '/' },
          { name: 'Meera for Creators', url: '/meera-for-creators' },
        ])}
      />

      <SiteHeader />

      <main>
        {/* Hero + phone */}
        <section className="border-b border-border/60 py-16 sm:py-20">
          <div className="mx-auto grid max-w-6xl items-center gap-12 px-6 lg:grid-cols-[1.1fr_0.9fr]">
            <FadeUp>
              <Badge variant="outline" className="gap-1.5">
                In the works · coming soon
              </Badge>
              <h1 className="mt-4 text-4xl font-bold leading-tight tracking-tight sm:text-5xl">
                Your own PR manager. On your side.
              </h1>
              <p className="mt-4 text-lg text-muted-foreground">
                Paste a brand brief. Meera reads it, tells you what to charge, and drafts the reply.
                The send button is always yours.
              </p>
              <div className="mt-8 flex flex-wrap gap-3">
                <Button
                  size="lg"
                  className="bg-accent-foreground text-white hover:bg-accent-foreground/90"
                  asChild
                >
                  <Link to="/creator/register">
                    Join the waitlist <ArrowRight className="ml-1.5 h-4 w-4" aria-hidden="true" />
                  </Link>
                </Button>
                <Button size="lg" variant="outline" asChild>
                  <Link to="/how-it-works/creators">How Influora works</Link>
                </Button>
              </div>
              <p className="mt-6 text-sm text-muted-foreground">
                Every line in the demo is scripted. Switch it to Hinglish, English or Marathi above
                the phone. The real Meera will work on your deals, in your language.
              </p>
            </FadeUp>

            <FadeUp delay={0.1}>
              <Suspense fallback={<PhoneSkeleton />}>
                <MeeraDemoPlayer />
              </Suspense>
            </FadeUp>
          </div>
        </section>

        {/* What Meera does */}
        <section className="py-20">
          <div className="mx-auto max-w-5xl px-6">
            <FadeUp>
              <h2 className="text-center text-3xl font-bold tracking-tight">What Meera will do for you</h2>
              <p className="mx-auto mt-3 max-w-2xl text-center text-muted-foreground">
                Five jobs, all with your approval. Meera never sends anything on her own.
              </p>
            </FadeUp>
            <StaggerContainer className="mt-12 grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
              {CARDS.map((card) => {
                const Icon = card.icon;
                return (
                  <StaggerItem key={card.title}>
                    <div className="h-full rounded-2xl border border-border/60 bg-card/50 p-6">
                      <span className="flex h-11 w-11 items-center justify-center rounded-lg bg-accent text-accent-foreground">
                        <Icon className="h-5 w-5" aria-hidden="true" />
                      </span>
                      <h3 className="mt-4 font-semibold">{card.title}</h3>
                      <p className="mt-1.5 text-sm text-muted-foreground">{card.body}</p>
                    </div>
                  </StaggerItem>
                );
              })}
              <StaggerItem>
                <div className="flex h-full flex-col justify-center rounded-2xl border border-dashed border-border bg-background p-6">
                  <Badge variant="outline" className="w-fit">
                    Always free
                  </Badge>
                  <h3 className="mt-4 font-semibold">Money tracking, proof, and the Monday note</h3>
                  <p className="mt-1.5 text-sm text-muted-foreground">
                    Credits only pay for the AI work: chat, brief reads, voice, and brand search.
                  </p>
                </div>
              </StaggerItem>
            </StaggerContainer>
          </div>
        </section>

        {/* Pricing poll */}
        <section id="pricing-poll" className="border-t border-border/60 bg-card/50 py-20">
          <div className="mx-auto max-w-5xl px-6">
            <FadeUp>
              <Badge variant="outline">Help us set the price</Badge>
              <h2 className="mt-4 text-3xl font-bold tracking-tight">Which plan would you pick?</h2>
              <p className="mt-3 max-w-2xl text-muted-foreground">
                Nothing is charged today. Pick the monthly plan you would actually pay for, and we
                will build around what creators choose.
              </p>
            </FadeUp>
            <FadeUp delay={0.1}>
              <div className="mt-10">
                <MeeraPricingPoll />
              </div>
            </FadeUp>
          </div>
        </section>

        {/* Three promises */}
        <section className="py-14">
          <div className="mx-auto max-w-3xl px-6 text-center">
            <FadeUp>
              <h2 className="text-2xl font-bold tracking-tight">Three promises</h2>
              <ul className="mt-6 grid gap-4 text-left sm:grid-cols-3">
                <li className="rounded-xl border border-border/60 bg-background p-4 text-sm">
                  <span className="font-semibold">You decide.</span> Every message goes out only after
                  you approve it.
                </li>
                <li className="rounded-xl border border-border/60 bg-background p-4 text-sm">
                  <span className="font-semibold">Brands will know.</span> Every reply is labelled:
                  drafted with Meera, approved by you.
                </li>
                <li className="rounded-xl border border-border/60 bg-background p-4 text-sm">
                  <span className="font-semibold">Your floor is yours.</span> A brand never sees it, and
                  Meera never says yes below it.
                </li>
              </ul>
            </FadeUp>
          </div>
        </section>

        <FunnelCta
          heading="You will hear about it first"
          sub="Create a creator account. When Meera is ready, you get her before anyone else."
          primary={{ label: 'Join the waitlist', to: '/creator/register' }}
          secondary={{ label: 'See pricing', to: '/pricing' }}
          reassurances={['Free to join', 'No card needed', 'Hinglish, Hindi or English']}
        />
      </main>

      <SiteFooter />
    </div>
  );
}
