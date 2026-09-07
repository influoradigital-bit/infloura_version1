import { useRef, useState, type ReactElement } from 'react';
import { ArrowRight, Sparkles } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent } from '@/components/ui/card';
import { FadeUp, StaggerContainer, StaggerItem } from '@/components/motion';
import { SiteHeader } from '@/components/site/SiteHeader';
import { SiteFooter } from '@/components/site/SiteFooter';
import { FaqSection } from '@/components/site/FaqSection';
import {
  FestivalEnquiryForm,
  type FestivalAudience,
} from '@/components/site/FestivalEnquiryForm';
import { cn } from '@/lib/utils';
import { Seo } from '@/lib/seo/Seo';
import {
  JsonLd,
  getBreadcrumbListSchema,
  getHowToSchema,
  getWebPageSchema,
} from '@/lib/seo/schema';
import {
  FESTIVAL_EDITION_LABEL,
  BRAND_PROBLEMS,
  BRAND_FIX,
  BRAND_FLOW,
  QUEEN_BEE,
  TRACKING_CHAIN,
  TRACKING_CAVEAT,
  MEASUREMENT,
  TWO_CODE_MODEL,
  BRAND_REQUIREMENTS,
  BRAND_TIERS,
  BRAND_TIMELINE,
  BRAND_DELIVERABLES,
  BRAND_FAQ,
  BRAND_MATH,
  CREATOR_BENEFITS,
  CREATOR_COMMITMENTS,
  CREATOR_SELECTION,
  CREATOR_FAQ,
} from '@/content/festival-box';

/**
 * /festival-box (T-FESTIVALBOX-0905) — the public landing page for the Mumbai Festive Edition 2026.
 *
 * One page, two audiences. All copy comes from src/content/festival-box.ts; this file only decides
 * layout and which half of that content is on screen. JSON-LD (HowTo, WebPage, Breadcrumb, and the
 * FAQPage inside FaqSection) is derived from the same arrays the page renders — never re-typed —
 * following the pattern in how-it-works-brands.tsx. Structured data is gated to the audience actually
 * on screen so schema and visible text cannot drift, and so a JS-less crawler snapshotting the
 * default (brand) view never gets structured data for content it did not receive.
 *
 * FestivalEnquiryForm is the only thing on this page that writes to the database; this page's whole
 * job is to make it reachable and to pre-fill the tier a visitor already chose.
 */
export default function FestivalBoxPage(): ReactElement {
  const [audience, setAudience] = useState<FestivalAudience>('BRAND');
  const [presetTier, setPresetTier] = useState<string | undefined>(undefined);
  const formSectionRef = useRef<HTMLDivElement>(null);

  function scrollToForm(tier?: string) {
    if (tier) setPresetTier(tier);
    formSectionRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  function selectAudience(next: FestivalAudience) {
    setAudience(next);
    // A tier preset only means anything on the brand form; carrying it into the
    // creator form would silently pin an irrelevant value the visitor never chose.
    if (next === 'CREATOR') setPresetTier(undefined);
  }

  const isBrand = audience === 'BRAND';

  return (
    <div className="min-h-screen bg-background text-foreground">
      <Seo
        title="Festival Box — Mumbai Festive Edition 2026"
        description="One box in, a full styled shoot day, and sales tracked back to each creator. Sponsor the Festival Box as a brand, or apply for the Mumbai Festive Edition 2026 creator roster."
        canonical="/festival-box"
      />
      <JsonLd
        data={getWebPageSchema({
          name: 'Festival Box — Mumbai Festive Edition 2026',
          description: isBrand
            ? 'Sponsor tiers, the event flow, the Queen Bee creator model, and the tracked-sales chain for the Influora Festival Box, Mumbai Festive Edition 2026.'
            : 'How the Mumbai Festive Edition 2026 creator roster works — what creators get, what Influora asks in return, and how selection is decided.',
          url: '/festival-box',
        })}
      />
      <JsonLd
        data={getBreadcrumbListSchema([
          { name: 'Home', url: '/' },
          { name: 'Festival Box', url: '/festival-box' },
        ])}
      />
      {/*
        HowTo, built from the same BRAND_FLOW array the page renders (see the file header comment on
        src/content/festival-box.ts). Gated to the brand view: the five acts described are the event
        itself, not something a creator visitor is asking "how do I" about, and rendering it while the
        creator view is on screen would advertise steps that section of the page never shows.
      */}
      {isBrand && (
        <JsonLd
          data={getHowToSchema({
            name: 'How the Influora Festival Box event runs',
            description:
              'The five acts of a Festival Box shoot day, from creator-product matching through the live finale drop.',
            url: '/festival-box',
            steps: BRAND_FLOW.map((s) => ({ name: s.title, text: s.body })),
          })}
        />
      )}

      <SiteHeader />

      <main>
        {/* Hero + audience toggle */}
        <section className="border-b border-border/60 py-20">
          <div className="mx-auto max-w-3xl px-6 text-center">
            <FadeUp>
              <Badge variant="outline" className="gap-1.5">
                <Sparkles className="h-3 w-3" aria-hidden="true" /> {FESTIVAL_EDITION_LABEL}
              </Badge>
              <h1 className="mt-4 text-4xl font-bold leading-tight tracking-tight sm:text-5xl">
                {isBrand
                  ? 'One box in. A styled shoot day. Sales tracked back to every creator.'
                  : 'Join the roster for the Mumbai Festive Edition 2026'}
              </h1>
              <p className="mt-4 text-lg text-muted-foreground">
                {isBrand
                  ? 'Ten creators, one festive room, a full production day run by our team — and a scoreboard that ties every reel back to a rupee sold.'
                  : 'A full styled shoot day, paid deliverables in writing, and payment protected end to end. Wear every brand in the room, keep what you shoot.'}
              </p>

              <div
                role="group"
                aria-label="Choose your audience"
                className="mx-auto mt-8 inline-flex rounded-full border border-border/60 bg-card/50 p-1"
              >
                <button
                  type="button"
                  aria-pressed={isBrand}
                  onClick={() => selectAudience('BRAND')}
                  className={cn(
                    'rounded-full px-5 py-2 text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                    isBrand
                      ? 'bg-foreground text-background'
                      : 'text-muted-foreground hover:text-foreground',
                  )}
                >
                  For brands
                </button>
                <button
                  type="button"
                  aria-pressed={!isBrand}
                  onClick={() => selectAudience('CREATOR')}
                  className={cn(
                    'rounded-full px-5 py-2 text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                    !isBrand
                      ? 'bg-foreground text-background'
                      : 'text-muted-foreground hover:text-foreground',
                  )}
                >
                  For creators
                </button>
              </div>

              <div className="mt-8 flex flex-wrap justify-center gap-3">
                {/* Single brand-accent touch for this section: the primary CTA. */}
                <Button
                  size="lg"
                  className="bg-[var(--brand)] text-white hover:bg-[var(--brand)]/90"
                  onClick={() => scrollToForm()}
                >
                  {isBrand ? 'Request the Festival Box deck' : 'Apply for the roster'}
                  <ArrowRight className="ml-1.5 h-4 w-4" aria-hidden="true" />
                </Button>
              </div>
            </FadeUp>
          </div>
        </section>

        {/* Hero visual — ported from the Stitch design's studio photography. Decorative only:
            this is stock/illustrative imagery of a styled shoot, not a photo of a specific past
            edition, so it carries no caption claiming otherwise. */}
        <section className="border-b border-border/60 py-16">
          <div className="mx-auto max-w-5xl px-6">
            <FadeUp>
              <img
                src="/stitch-media/creator-collective-experience-mumbai-edition-shoot-d-e3d1a2.jpg"
                alt=""
                loading="lazy"
                className="aspect-[16/9] w-full rounded-2xl border border-border/60 object-cover"
              />
            </FadeUp>
          </div>
        </section>

        {isBrand ? (
          <BrandSections onSelectTier={scrollToForm} />
        ) : (
          <CreatorSections />
        )}

        {/* Enquiry / application form — shared component, receives the current audience */}
        <section ref={formSectionRef} className="border-t border-border/60 py-20">
          <div className="mx-auto max-w-2xl px-6">
            <FadeUp className="text-center">
              <h2 className="text-3xl font-semibold">
                {isBrand ? 'Request the Festival Box deck' : 'Apply for the roster'}
              </h2>
              <p className="mt-3 text-muted-foreground">
                {isBrand
                  ? 'Tell us about your brand and the tier you have in mind. Our team replies within 2 working days.'
                  : 'Share your handle and a bit about your content. We verify metrics from the platform, not from a media kit.'}
              </p>
            </FadeUp>
            <FadeUp delay={0.1} className="mt-8">
              <FestivalEnquiryForm audience={audience} presetTier={isBrand ? presetTier : undefined} />
            </FadeUp>
          </div>
        </section>
      </main>

      <SiteFooter />
    </div>
  );
}

// ============================================================================
// BRAND VIEW
// ============================================================================

function BrandSections({ onSelectTier }: { onSelectTier: (tier: string) => void }): ReactElement {
  return (
    <>
      {/* The problem */}
      <section className="py-20">
        <div className="mx-auto max-w-5xl px-6">
          <FadeUp className="text-center">
            <Badge variant="outline">The problem</Badge>
            <h2 className="mt-3 text-2xl font-semibold sm:text-3xl">
              Gifting alone doesn't answer what happened next
            </h2>
          </FadeUp>
          <StaggerContainer className="mt-10 grid gap-6 sm:grid-cols-2">
            {BRAND_PROBLEMS.map((problem) => (
              <StaggerItem key={problem.title}>
                <div className="h-full rounded-2xl border border-border/60 bg-card/50 p-6">
                  <h3 className="font-semibold">{problem.title}</h3>
                  <p className="mt-1.5 text-sm text-muted-foreground">{problem.body}</p>
                </div>
              </StaggerItem>
            ))}
          </StaggerContainer>
        </div>
      </section>

      {/* The fix */}
      <section className="border-t border-border/60 bg-card/50 py-20">
        <div className="mx-auto max-w-4xl px-6">
          <FadeUp className="text-center">
            <Badge variant="outline">The fix</Badge>
            <h2 className="mt-3 text-2xl font-semibold sm:text-3xl">Three steps, one box</h2>
          </FadeUp>
          <StaggerContainer className="mt-10 space-y-6">
            {BRAND_FIX.map((step) => (
              <StepRow key={step.step} step={step} tone="plain" />
            ))}
          </StaggerContainer>
        </div>
      </section>

      {/* Event flow */}
      <section className="py-20">
        <div className="mx-auto max-w-6xl px-6">
          <FadeUp className="text-center">
            <Badge variant="outline">The event</Badge>
            <h2 className="mt-3 text-2xl font-semibold sm:text-3xl">Five acts, one shoot day</h2>
          </FadeUp>
          <div className="mt-10 grid gap-8 lg:grid-cols-[1.1fr_0.9fr] lg:items-start">
            <StaggerContainer className="space-y-6">
              {BRAND_FLOW.map((step) => (
                <StepRow key={step.step} step={step} />
              ))}
            </StaggerContainer>
            <FadeUp delay={0.1} className="lg:sticky lg:top-24">
              <img
                src="/stitch-media/curated-luxury-minimalist-creator-experience-unboxin-5bb439.jpg"
                alt=""
                loading="lazy"
                className="w-full rounded-2xl border border-border/60 object-cover"
              />
            </FadeUp>
          </div>
        </div>
      </section>

      {/*
        Queen Bee model — a two-column split (text left, roster visual right) instead of a card
        grid, for rhythm. Roster bars scale by headcount; the Queen row is the section's one
        brand-accent touch — she is the thing being explained.
      */}
      <section className="border-t border-border/60 bg-card/50 py-20">
        <div className="mx-auto max-w-5xl px-6">
          <FadeUp className="text-center">
            <Badge variant="outline">The roster model</Badge>
            <h2 className="mt-3 text-2xl font-semibold sm:text-3xl">{QUEEN_BEE.headline}</h2>
            <p className="mx-auto mt-3 max-w-2xl text-muted-foreground">{QUEEN_BEE.standfirst}</p>
          </FadeUp>

          <div className="mt-12 grid gap-10 lg:grid-cols-2 lg:items-center lg:gap-16">
            <FadeUp className="space-y-6">
              <div>
                {/* dark:text lightens the accent — #6D5AE6 on the dark surface is ~2.9:1, below AA */}
                <h3 className="font-semibold text-[var(--brand)] dark:text-[#b0a3f5]">
                  {QUEEN_BEE.queen.title}
                </h3>
                <p className="mt-1.5 text-sm text-muted-foreground">{QUEEN_BEE.queen.body}</p>
              </div>
              <div>
                <h3 className="font-semibold">{QUEEN_BEE.bees.title}</h3>
                <p className="mt-1.5 text-sm text-muted-foreground">{QUEEN_BEE.bees.body}</p>
              </div>
              <p className="border-t border-border/60 pt-6 font-medium">{QUEEN_BEE.closing}</p>
            </FadeUp>

            <FadeUp delay={0.1}>
              <div
                className="space-y-3 rounded-2xl border border-border/60 bg-background p-6"
                role="img"
                aria-label={`Roster mix: ${QUEEN_BEE.roster.map((t) => `${t.count} ${t.label}, ${t.band} followers`).join('; ')}`}
              >
                {QUEEN_BEE.roster.map((tier, index) => {
                  const isQueen = index === 0;
                  // Widths are illustrative of headcount, not to scale — decorative only.
                  const widthPct = [95, 70, 50, 32][index] ?? 40;
                  return (
                    <div key={tier.label} aria-hidden="true">
                      <div className="flex items-baseline justify-between text-sm">
                        <span
                          className={cn(
                            'font-medium',
                            isQueen && 'text-[var(--brand)] dark:text-[#b0a3f5]',
                          )}
                        >
                          {tier.count} {tier.label}
                        </span>
                        <span className="text-xs text-muted-foreground">{tier.band} followers</span>
                      </div>
                      <div className="mt-1.5 h-2 w-full overflow-hidden rounded-full bg-muted">
                        <div
                          className={cn(
                            'h-full rounded-full',
                            isQueen ? 'bg-[var(--brand)] dark:bg-[#b0a3f5]' : 'bg-muted-foreground/40',
                          )}
                          style={{ width: `${widthPct}%` }}
                        />
                      </div>
                    </div>
                  );
                })}
              </div>
            </FadeUp>
          </div>
        </div>
      </section>

      {/* Tracking chain — a single flow diagram replaces the old 4-card grid. */}
      <section className="py-20">
        <div className="mx-auto max-w-4xl px-6">
          <FadeUp className="text-center">
            <Badge variant="outline">Tracked, not guessed</Badge>
            <h2 className="mt-3 text-2xl font-semibold sm:text-3xl">
              How a reel turns into a tracked sale
            </h2>
          </FadeUp>

          <div className="mt-12">
            <JourneyDiagram />
          </div>

          {/*
            The Amazon caveat. Deliberately inside the tracking section and immediately after the
            chain, NOT in a footnote: the chain promises orders report themselves, and that is only
            true for a connected store. A sponsor selling mainly on a marketplace has to be able to
            see the limit at the moment they read the promise.
          */}
          <FadeUp delay={0.1}>
            <div className="mt-10 rounded-2xl border border-border/60 bg-muted/40 p-6">
              <h3 className="text-sm font-semibold">{TRACKING_CAVEAT.title}</h3>
              <p className="mt-1.5 text-sm text-muted-foreground">{TRACKING_CAVEAT.body}</p>
            </div>
          </FadeUp>
        </div>
      </section>

      {/*
        What a sponsor can measure. Sits right after the tracking chain and its Amazon caveat,
        because this is the "…and what do I actually get to see?" answer to the chain above.
        Every claim in MEASUREMENT is built and verified — see that constant's javadoc for the
        standard, and for what is deliberately NOT claimed (per-sponsor click counts).
      */}
      <section className="py-20">
        <div className="mx-auto max-w-5xl px-6">
          <FadeUp className="text-center">
            <Badge variant="outline">What you can measure</Badge>
            <h2 className="mt-3 text-2xl font-semibold sm:text-3xl">Three numbers, not a guess</h2>
          </FadeUp>
          <StaggerContainer className="mt-10 grid gap-6 md:grid-cols-3">
            {MEASUREMENT.map((item) => {
              const Icon = item.icon;
              return (
                <StaggerItem key={item.title}>
                  <div className="h-full rounded-2xl border border-border/60 bg-card/50 p-6">
                    <span className="flex h-9 w-9 items-center justify-center rounded-lg bg-accent text-accent-foreground">
                      <Icon className="h-4 w-4" aria-hidden="true" />
                    </span>
                    <h3 className="mt-3 font-semibold">{item.title}</h3>
                    <p className="mt-1.5 text-sm text-muted-foreground">{item.body}</p>
                  </div>
                </StaggerItem>
              );
            })}
          </StaggerContainer>
        </div>
      </section>

      {/* Two-code model — the attribution explainer. Two paths converging on one report. */}
      <section className="border-t border-border/60 bg-card/50 py-20">
        <div className="mx-auto max-w-5xl px-6">
          <FadeUp className="text-center">
            <Badge variant="outline">Attribution</Badge>
            <h2 className="mt-3 text-2xl font-semibold sm:text-3xl">{TWO_CODE_MODEL.headline}</h2>
            <p className="mx-auto mt-3 max-w-2xl text-muted-foreground">
              {TWO_CODE_MODEL.standfirst}
            </p>
          </FadeUp>

          <div className="mt-12">
            <TwoCodeDiagram />
          </div>

          <FadeUp delay={0.1}>
            <p className="mt-8 text-center text-sm text-muted-foreground">
              {TWO_CODE_MODEL.closing}
            </p>
          </FadeUp>
        </div>
      </section>

      {/*
        What we need from you. Placed BEFORE the tier prices on purpose — two of these four are hard
        requirements for the tracking sold above, and a brand should be able to disqualify itself
        for free rather than discovering the ask on the kickoff call.
      */}
      <section className="py-20">
        <div className="mx-auto max-w-5xl px-6">
          <FadeUp className="text-center">
            <Badge variant="outline">Your side of it</Badge>
            <h2 className="mt-3 text-2xl font-semibold sm:text-3xl">What we need from you</h2>
            <p className="mx-auto mt-3 max-w-2xl text-muted-foreground">
              Four things, and then nothing until the content lands.
            </p>
          </FadeUp>
          <StaggerContainer className="mt-10 grid gap-6 sm:grid-cols-2 lg:grid-cols-4">
            {BRAND_REQUIREMENTS.map((req) => {
              const Icon = req.icon;
              return (
                <StaggerItem key={req.title}>
                  <div className="h-full rounded-2xl border border-border/60 bg-card/50 p-6">
                    <span className="flex h-9 w-9 items-center justify-center rounded-lg bg-accent text-accent-foreground">
                      <Icon className="h-4 w-4" aria-hidden="true" />
                    </span>
                    <h3 className="mt-3 font-semibold">{req.title}</h3>
                    <p className="mt-1.5 text-sm text-muted-foreground">{req.body}</p>
                  </div>
                </StaggerItem>
              );
            })}
          </StaggerContainer>
        </div>
      </section>

      {/* Tiers */}
      <section className="border-t border-border/60 bg-card/50 py-20">
        <div className="mx-auto max-w-5xl px-6">
          <FadeUp className="text-center">
            <Badge variant="outline">Sponsor tiers</Badge>
            <h2 className="mt-3 text-2xl font-semibold sm:text-3xl">Pick your tier</h2>
          </FadeUp>

          <div className="mt-10 grid gap-6 lg:grid-cols-3">
            {BRAND_TIERS.map((tier, index) => (
              <FadeUp key={tier.value} delay={index * 0.05}>
                <Card
                  className={cn(
                    'relative h-full',
                    tier.highlighted && 'border-[var(--brand)] shadow-md dark:border-[#b0a3f5]',
                  )}
                >
                  {tier.highlighted && (
                    <Badge className="absolute -top-3 left-1/2 -translate-x-1/2 bg-[var(--brand)] text-white hover:bg-[var(--brand)]">
                      Most popular
                    </Badge>
                  )}
                  <CardContent className="flex h-full flex-col p-8">
                    <Badge variant="outline" className="w-fit">
                      {tier.name}
                    </Badge>
                    <p className="mt-4 text-2xl font-bold">{tier.price}</p>
                    <p className="mt-1 text-sm text-muted-foreground">{tier.priceNote}</p>
                    <p className="mt-3 text-sm font-medium">{tier.pitch}</p>
                    <p className="mt-1 text-xs text-muted-foreground">{tier.reach}</p>
                    <ul className="mt-6 flex-1 space-y-3">
                      {tier.features.map((feature) => (
                        <li key={feature} className="flex items-start gap-2.5 text-sm">
                          <span
                            className="mt-1.5 h-1.5 w-1.5 shrink-0 rounded-full bg-muted-foreground/50"
                            aria-hidden="true"
                          />
                          <span>{feature}</span>
                        </li>
                      ))}
                    </ul>
                    <Button
                      size="lg"
                      className={cn(
                        'mt-8 w-full',
                        tier.highlighted
                          ? 'bg-[var(--brand)] text-white hover:bg-[var(--brand)]/90'
                          : '',
                      )}
                      variant={tier.highlighted ? undefined : 'outline'}
                      onClick={() => onSelectTier(tier.value)}
                    >
                      Choose {tier.name} <ArrowRight className="ml-1.5 h-4 w-4" aria-hidden="true" />
                    </Button>
                  </CardContent>
                </Card>
              </FadeUp>
            ))}
          </div>
        </div>
      </section>

      {/*
        Economics — a numbers moment, not another card grid. ₹1.5L vs ~₹50K is the strongest single
        argument on the page, so it gets size and air instead of two equal-weight boxes. The one
        brand-accent touch here is the "shared" figure itself plus the proportion bar under it —
        nothing else in the section carries colour.
      */}
      <section className="py-24">
        <div className="mx-auto max-w-3xl px-6 text-center">
          <FadeUp>
            <Badge variant="outline">The economics</Badge>
            <h2 className="mt-3 text-2xl font-semibold sm:text-3xl">
              The production you'd pay full price for, shared
            </h2>
          </FadeUp>

          <FadeUp delay={0.1}>
            <div className="mt-14 flex flex-col items-center justify-center gap-8 sm:flex-row sm:gap-4">
              <div>
                <p className="text-sm text-muted-foreground">{BRAND_MATH.alone.label}</p>
                <p className="mt-2 text-5xl font-bold tracking-tight text-muted-foreground/70 sm:text-6xl">
                  {BRAND_MATH.alone.amount}
                </p>
              </div>
              <ArrowRight
                className="h-6 w-6 rotate-90 text-muted-foreground/50 sm:rotate-0"
                aria-hidden="true"
              />
              <div>
                {/* dark:text lightens the accent — #6D5AE6 on the dark surface is ~2.9:1, below AA */}
                <p className="text-sm font-medium text-[var(--brand)] dark:text-[#b0a3f5]">
                  {BRAND_MATH.shared.label}
                </p>
                <p className="mt-2 text-6xl font-bold tracking-tight text-[var(--brand)] dark:text-[#b0a3f5] sm:text-7xl">
                  {BRAND_MATH.shared.amount}
                </p>
              </div>
            </div>
          </FadeUp>

          {/* Proportion bar — the same ~1/3 relationship, rendered rather than just stated. */}
          <FadeUp delay={0.15}>
            <div
              className="mx-auto mt-8 h-2 w-full max-w-sm overflow-hidden rounded-full bg-muted"
              role="img"
              aria-label="Your slice is about a third of the full production cost"
            >
              <div className="h-full w-1/3 rounded-full bg-[var(--brand)] dark:bg-[#b0a3f5]" />
            </div>
          </FadeUp>

          <FadeUp delay={0.2}>
            <div className="mx-auto mt-8 grid max-w-xl gap-6 text-sm text-muted-foreground sm:grid-cols-2">
              <p>{BRAND_MATH.alone.body}</p>
              <p>{BRAND_MATH.shared.body}</p>
            </div>
          </FadeUp>

          <FadeUp delay={0.25}>
            <p className="mt-8 text-lg font-medium">{BRAND_MATH.punchline}</p>
          </FadeUp>
        </div>
      </section>

      {/* Timeline */}
      <section className="border-t border-border/60 bg-card/50 py-20">
        <div className="mx-auto max-w-4xl px-6">
          <FadeUp className="text-center">
            <Badge variant="outline">Working together</Badge>
            <h2 className="mt-3 text-2xl font-semibold sm:text-3xl">
              What the next twenty days look like
            </h2>
          </FadeUp>
          <StaggerContainer className="mt-10 space-y-6">
            {BRAND_TIMELINE.map((step) => (
              <StepRow key={step.step} step={step} tone="plain" />
            ))}
          </StaggerContainer>
        </div>
      </section>

      {/* Deliverables */}
      <section className="py-20">
        <div className="mx-auto max-w-5xl px-6">
          <FadeUp className="text-center">
            <Badge variant="outline">What you keep</Badge>
            <h2 className="mt-3 text-2xl font-semibold sm:text-3xl">After the wave ends</h2>
          </FadeUp>
          <StaggerContainer className="mt-10 grid gap-6 sm:grid-cols-2">
            {BRAND_DELIVERABLES.map((item) => {
              const Icon = item.icon;
              return (
                <StaggerItem key={item.title}>
                  <div className="flex h-full gap-4 rounded-2xl border border-border/60 bg-card/50 p-6">
                    <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-lg bg-accent text-accent-foreground">
                      <Icon className="h-5 w-5" aria-hidden="true" />
                    </span>
                    <div>
                      <h3 className="font-semibold">{item.title}</h3>
                      <p className="mt-1.5 text-sm text-muted-foreground">{item.body}</p>
                    </div>
                  </div>
                </StaggerItem>
              );
            })}
          </StaggerContainer>
        </div>
      </section>

      <FaqSection heading="Brand questions, answered" items={BRAND_FAQ} />
    </>
  );
}

// ============================================================================
// CREATOR VIEW
// ============================================================================

function CreatorSections(): ReactElement {
  return (
    <>
      {/* What you get */}
      <section className="py-20">
        <div className="mx-auto max-w-5xl px-6">
          <FadeUp className="text-center">
            <Badge variant="outline">What you get</Badge>
            <h2 className="mt-3 text-2xl font-semibold sm:text-3xl">
              A full production day, paid and protected
            </h2>
          </FadeUp>
          <FadeUp delay={0.05}>
            <img
              src="/stitch-media/luxury-unboxing-gift-box-with-premium-products-a36dd9.jpg"
              alt=""
              loading="lazy"
              className="mt-10 aspect-[21/9] w-full rounded-2xl border border-border/60 object-cover"
            />
          </FadeUp>
          <StaggerContainer className="mt-10 grid gap-6 sm:grid-cols-2 lg:grid-cols-3">
            {CREATOR_BENEFITS.map((item) => {
              const Icon = item.icon;
              return (
                <StaggerItem key={item.title}>
                  <div className="h-full rounded-2xl border border-border/60 bg-card/50 p-6">
                    <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-lg bg-accent text-accent-foreground">
                      <Icon className="h-5 w-5" aria-hidden="true" />
                    </span>
                    <h3 className="mt-3 font-semibold">{item.title}</h3>
                    <p className="mt-1.5 text-sm text-muted-foreground">{item.body}</p>
                  </div>
                </StaggerItem>
              );
            })}
          </StaggerContainer>
        </div>
      </section>

      {/* What we ask */}
      <section className="border-t border-border/60 bg-card/50 py-20">
        <div className="mx-auto max-w-4xl px-6">
          <FadeUp className="text-center">
            <Badge variant="outline">What we ask</Badge>
            <h2 className="mt-3 text-2xl font-semibold sm:text-3xl">Stated up front</h2>
          </FadeUp>
          <StaggerContainer className="mt-10 grid gap-6 sm:grid-cols-2">
            {CREATOR_COMMITMENTS.map((item) => (
              <StaggerItem key={item.title}>
                <div className="h-full rounded-2xl border border-border/60 bg-background p-6">
                  <h3 className="font-semibold">{item.title}</h3>
                  <p className="mt-1.5 text-sm text-muted-foreground">{item.body}</p>
                </div>
              </StaggerItem>
            ))}
          </StaggerContainer>
        </div>
      </section>

      {/* Selection */}
      <section className="py-20">
        <div className="mx-auto max-w-4xl px-6">
          <FadeUp className="text-center">
            <Badge variant="outline">How selection works</Badge>
            <h2 className="mt-3 text-2xl font-semibold sm:text-3xl">A standard, not a favour</h2>
          </FadeUp>
          <StaggerContainer className="mt-10 space-y-6">
            {CREATOR_SELECTION.map((step, index) => {
              const Icon = step.icon;
              return (
                <StaggerItem key={step.title}>
                  <div className="flex gap-5 rounded-2xl border border-border/60 bg-card/50 p-6">
                    <div className="flex flex-col items-center gap-2">
                      <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-lg bg-accent text-accent-foreground">
                        <Icon className="h-5 w-5" aria-hidden="true" />
                      </span>
                      <span className="text-xs font-semibold tracking-widest text-muted-foreground/70">
                        {String(index + 1).padStart(2, '0')}
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

      <FaqSection heading="Creator questions, answered" items={CREATOR_FAQ} />
    </>
  );
}

// ============================================================================
// SHARED
// ============================================================================

interface StepLike {
  step: string;
  title: string;
  body: string;
  icon: (typeof BRAND_FIX)[number]['icon'];
}

/**
 * One row in a numbered step list — shared by BRAND_FIX, BRAND_FLOW and BRAND_TIMELINE.
 *
 * `tone` picks the row's own background so it contrasts with whichever section it sits in:
 * "card" (default, `bg-card/50`) for a plain-background section — mirrors how-it-works-brands.tsx —
 * and "plain" (`bg-background`) for a section that is itself `bg-card/50`.
 */
function StepRow({
  step,
  tone = 'card',
}: {
  step: StepLike;
  tone?: 'card' | 'plain';
}): ReactElement {
  const Icon = step.icon;
  return (
    <StaggerItem>
      <div
        className={cn(
          'flex gap-5 rounded-2xl border border-border/60 p-6',
          tone === 'card' ? 'bg-card/50' : 'bg-background',
        )}
      >
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
}

/**
 * Decorative connector arrow shared by both diagrams below. Points right on desktop, rotates to
 * point down once the layout stacks under `sm`. Brand-accent stroke — this is the ONE coloured
 * element the journey diagram carries; the nodes themselves stay neutral.
 */
function FlowArrow({ className }: { className?: string }): ReactElement {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      // stroke-[var(--brand)] + a dark: override (rather than the stroke="" attribute) so the
      // connector lightens on a dark ground — #6D5AE6 alone is ~2.9:1 there, below AA.
      className={cn(
        'h-5 w-5 shrink-0 rotate-90 stroke-[var(--brand)] sm:rotate-0 dark:stroke-[#b0a3f5]',
        className,
      )}
    >
      <title>leads to</title>
      <path d="M4 12h13M12 6l6 6-6 6" />
    </svg>
  );
}

/** One neutral node icon — a simple monoline glyph, never the diagram's accent colour. */
function NodeIcon({ kind }: { kind: 'reel' | 'page' | 'checkout' | 'report' }): ReactElement {
  const paths: Record<typeof kind, ReactElement> = {
    reel: (
      <>
        <rect x="3.5" y="4.5" width="17" height="15" rx="3" />
        <path d="M10 9.2v5.6l5-2.8-5-2.8z" fill="currentColor" stroke="none" />
      </>
    ),
    page: (
      <>
        <rect x="4.5" y="3.5" width="15" height="17" rx="2.5" />
        <path d="M8 8.5h8M8 12h8M8 15.5h5" />
      </>
    ),
    checkout: (
      <>
        <path d="M6 8h12l-1.2 11.5a1 1 0 0 1-1 .9H8.2a1 1 0 0 1-1-.9L6 8z" />
        <path d="M9 8V6.5a3 3 0 0 1 6 0V8" />
      </>
    ),
    report: (
      <>
        <path d="M4.5 19.5h15" />
        <rect x="6.5" y="13.5" width="3" height="6" fill="currentColor" stroke="none" />
        <rect x="10.7" y="9.5" width="3" height="10" fill="currentColor" stroke="none" />
        <rect x="15" y="5.5" width="3" height="14" fill="currentColor" stroke="none" />
      </>
    ),
  };
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.6}
      strokeLinecap="round"
      strokeLinejoin="round"
      className="h-5 w-5"
      aria-hidden="true"
    >
      {paths[kind]}
    </svg>
  );
}

const JOURNEY_ICONS: Array<'reel' | 'page' | 'checkout' | 'report'> = [
  'reel',
  'page',
  'checkout',
  'report',
];

/**
 * Diagram A — "the journey". Replaces the old TRACKING_CHAIN card grid with a single flow:
 * Creator reel -> Festival Box page -> Your checkout -> Sale reported. Built from real HTML nodes
 * (so captions reflow and stay accessible as ordinary text) connected by small inline-SVG arrows
 * (the diagram's one accent element — see FlowArrow). Stacks to a vertical column under `sm` via
 * the same flex container simply switching axis; no separate mobile markup to keep in sync.
 */
function JourneyDiagram(): ReactElement {
  return (
    <figure aria-label="How a reel becomes a tracked sale, in four steps">
      <div className="flex flex-col items-stretch gap-6 sm:flex-row sm:items-start sm:justify-between sm:gap-3">
        {TRACKING_CHAIN.map((node, index) => (
          <div key={node.title} className="flex flex-col items-center gap-6 sm:flex-1 sm:flex-row">
            {index > 0 && <FlowArrow className="sm:mt-5" />}
            <div className="flex flex-col items-center gap-3 text-center sm:flex-1">
              <span className="flex h-12 w-12 shrink-0 items-center justify-center rounded-full border border-border/60 bg-card text-foreground">
                <NodeIcon kind={JOURNEY_ICONS[index]} />
              </span>
              <div>
                <h3 className="text-sm font-semibold">{node.title}</h3>
                <p className="mx-auto mt-1 max-w-[10rem] text-xs text-muted-foreground">
                  {node.body}
                </p>
              </div>
            </div>
          </div>
        ))}
      </div>
    </figure>
  );
}

/**
 * Small convergence glyph for Diagram B: two lines meeting at a brand-accent point, continuing on
 * as one (or the reverse, for `mirror`, where one line splits into two). Rendered as two explicit,
 * hand-drawn variants — horizontal (desktop) and vertical (stacked mobile) — swapped with
 * `hidden sm:block` / `sm:hidden`, rather than one shape rotated and CSS-mirrored: a 90° rotation
 * plus a horizontal flip do not compose into a correct vertical "split" glyph (verified — it lands
 * back on the "merge" shape), so each orientation gets its own unambiguous path.
 */
function ConvergeGlyph({ mirror, className }: { mirror?: boolean; className?: string }): ReactElement {
  const label = mirror ? 'splits into' : 'converges into';
  return (
    <>
      {/* Desktop: flows left to right. */}
      <svg
        viewBox="0 0 60 60"
        fill="none"
        aria-hidden="true"
        className={cn('hidden h-14 w-14 shrink-0 sm:block', className)}
      >
        <title>{label}</title>
        {mirror ? (
          <>
            <path d="M6 30h24" stroke="var(--brand)" strokeWidth={2} strokeLinecap="round" />
            <circle cx="30" cy="30" r="3.5" fill="var(--brand)" />
            <path d="M30 30c14 0 14-18 24-18" stroke="var(--brand)" strokeWidth={2} strokeLinecap="round" />
            <path d="M30 30c14 0 14 18 24 18" stroke="var(--brand)" strokeWidth={2} strokeLinecap="round" />
          </>
        ) : (
          <>
            <path d="M6 12c14 0 14 18 24 18" stroke="var(--brand)" strokeWidth={2} strokeLinecap="round" />
            <path d="M6 48c14 0 14-18 24-18" stroke="var(--brand)" strokeWidth={2} strokeLinecap="round" />
            <circle cx="30" cy="30" r="3.5" fill="var(--brand)" />
            <path d="M30 30h24" stroke="var(--brand)" strokeWidth={2} strokeLinecap="round" />
          </>
        )}
      </svg>
      {/* Mobile: flows top to bottom, once the layout stacks. */}
      <svg
        viewBox="0 0 60 60"
        fill="none"
        aria-hidden="true"
        className={cn('mx-auto block h-14 w-14 shrink-0 sm:hidden', className)}
      >
        <title>{label}</title>
        {mirror ? (
          <>
            <path d="M30 6v24" stroke="var(--brand)" strokeWidth={2} strokeLinecap="round" />
            <circle cx="30" cy="30" r="3.5" fill="var(--brand)" />
            <path d="M30 30c0 14-18 14-18 24" stroke="var(--brand)" strokeWidth={2} strokeLinecap="round" />
            <path d="M30 30c0 14 18 14 18 24" stroke="var(--brand)" strokeWidth={2} strokeLinecap="round" />
          </>
        ) : (
          <>
            <path d="M12 6c0 14 18 14 18 24" stroke="var(--brand)" strokeWidth={2} strokeLinecap="round" />
            <path d="M48 6c0 14-18 14-18 24" stroke="var(--brand)" strokeWidth={2} strokeLinecap="round" />
            <circle cx="30" cy="30" r="3.5" fill="var(--brand)" />
            <path d="M30 30v24" stroke="var(--brand)" strokeWidth={2} strokeLinecap="round" />
          </>
        )}
      </svg>
    </>
  );
}

/** A code, shown as a monospace chip sitting on its own path into the merge point. */
function CodeSourceCard({ label, code }: { label: string; code: string }): ReactElement {
  return (
    <div className="rounded-xl border border-border/60 bg-card/50 px-4 py-3 text-center sm:text-left">
      <p className="text-sm font-medium">{label}</p>
      <code className="mt-1.5 inline-block rounded-md border border-border/60 bg-muted px-2 py-0.5 font-mono text-xs font-semibold tracking-wider">
        {code}
      </code>
    </div>
  );
}

/**
 * Diagram B — "two codes, two jobs". The section's whole point is that this is NOT two cards side
 * by side: two inputs (creator reel, Festival Box page) converge on one outcome (your store, then
 * a recorded sale), which itself branches into the two questions it answers. The merge point and
 * split point are the only brand-accent pixels in the section — see ConvergeGlyph.
 */
function TwoCodeDiagram(): ReactElement {
  return (
    <figure
      aria-label="Two codes converge into one report: a creator's code and the page's code both lead to your store, a recorded sale, and answer which creator and which brand drove it"
    >
      <div className="flex flex-col items-stretch gap-6 sm:flex-row sm:items-center sm:gap-4">
        <div className="flex flex-col justify-center gap-4 sm:flex-1">
          <CodeSourceCard label="Creator's reel" code={TWO_CODE_MODEL.creatorCode.example} />
          <CodeSourceCard label="Festival Box page" code={TWO_CODE_MODEL.pageCode.example} />
        </div>

        <ConvergeGlyph className="mx-auto sm:mx-0" />

        <div className="rounded-xl border border-border/60 bg-background px-5 py-4 text-center font-medium sm:flex-none">
          Your store
        </div>

        <FlowArrow className="mx-auto sm:mx-0" />

        <div className="rounded-xl border border-border/60 bg-background px-5 py-4 text-center font-medium sm:flex-none">
          Sale recorded
        </div>

        <ConvergeGlyph mirror className="mx-auto sm:mx-0" />

        <div className="flex flex-col justify-center gap-2 text-sm text-muted-foreground sm:flex-1">
          <p>{TWO_CODE_MODEL.creatorCode.answers}</p>
          <p>{TWO_CODE_MODEL.pageCode.answers}</p>
        </div>
      </div>
    </figure>
  );
}
