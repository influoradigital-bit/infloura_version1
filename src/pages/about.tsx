import { Link } from 'react-router-dom';
import {
  Building2,
  CheckCircle2,
  Eye,
  FileSignature,
  Landmark,
  ShieldCheck,
  Sparkles,
  Zap,
} from 'lucide-react';

import { Badge } from '@/components/ui/badge';
import { Card, CardContent } from '@/components/ui/card';
import { FadeUp, StaggerContainer, StaggerItem } from '@/components/motion';
import { SiteHeader } from '@/components/site/SiteHeader';
import { SiteFooter } from '@/components/site/SiteFooter';
import { FunnelCta } from '@/components/site/FunnelCta';
import { StickyCta, StickyCtaSpacer } from '@/components/site/StickyCta';
import { PROOF_POINTS } from '@/components/site/proof-points';
import { COMPANY } from '@/lib/company';
import { Seo } from '@/lib/seo/Seo';
import {
  JsonLd,
  getBreadcrumbListSchema,
  getOrganizationSchema,
  getWebPageSchema,
} from '@/lib/seo/schema';

/*
  Layout ported from the Stitch design "Influora - About Us & Leadership"
  (about-us-leadership). Same rule as the how-it-works pages (Priya's ruling
  2026-09-07): take the design's chapter rhythm and spacing, reject its palette
  and every unverified claim.

  Deliberately NOT carried over from the design, and why:
    - The founder portrait (public/stitch-media/swapnil-maruti-shinde-founder-ceo-of-influora-d7c93a.jpg).
      It is an AI-generated image captioned as a real named person — using it
      would misrepresent the CEO. The founder section below is text-only until
      a real photograph exists. This is a CEO decision, not an engineering one —
      do not swap in a placeholder or stock photo as a workaround.
    - "850+ Verified D2C Brands", "14,000+ Creators Empowered", "<3s Instant UPI
      Settlement", "₹0 Hidden Intermediary Fees", "Join 850+ Brands and 14,000+
      Creators". None are measured. Same class of invented traction figure that
      F-0342/F-0343 removed from this page previously — see PROOF_POINTS.
    - "creators keep 100% of what they earn" / "0% creator fee clawbacks" / "100%
      Payout Retention". False — Influora charges creators a 15% commission on a
      completed deal (see /pricing). The founder narrative below keeps its shape
      but drops this claim.
    - "RBI-Regulated Safety Vault", "RBI Licensed Escrow Partner", "Scheduled Bank
      Escrow Trustee", "protected payment vaults", "legally enforceable under the
      Information Technology Act 2000" as a claim about Influora itself. Our
      licensed partner is an RBI-authorized Payment Aggregator; Influora is not a
      trustee and not RBI-licensed. "escrow" is banned in user copy (2026-09-02).
    - "ISO 27001 Certified" — we hold no such certification.
    - "Automated Section 194J & 194R TDS deductions", "real-time GST
      reconciliation". TDS is recorded and shown on payouts; nothing is filed
      for you (see /tds).
    - "Sovereign Creators" / "creators as sovereign business owners" — "sovereign"
      cut everywhere as a house adjective; it appears nowhere in our live voice.
    - "Integrated GMV Analytics" / direct Shopify+WooCommerce conversion
      telemetry. Campaign performance is creator-reported, not measured by us.
    - "100% IN-HOUSE" badge and the second, wrong registered entity name
      ("Influora Technologies Private Limited") alongside a second, wrong
      CIN/GSTIN pair. The one correct entity, CIN and GSTIN are sourced from
      COMPANY (src/lib/company.ts) below — never hardcoded.
    - A specific founding city. COMPANY confirms the registered STATE
      (Maharashtra, from the GSTIN prefix); the street-level registered address
      is still a TODO(CEO) in company.ts, so this page does not assert a city.

  What was kept: the founder's real name and title (Swapnil Maruti Shinde,
  Founder & CEO) per direction, and the shape of the founder narrative — the
  problem it describes (agency markups, slow creator payouts) and the reason
  Influora exists — rewritten so every factual claim inside it is true.
*/

const HERO_PILLS = [
  { icon: Building2, label: `Registered in ${COMPANY.state}, India` },
  { icon: ShieldCheck, label: 'Payment-protected deals' },
  { icon: FileSignature, label: 'E-signed contract on every deal' },
  { icon: Sparkles, label: 'Nano → macro — no minimum follower count' },
] as const;

const PRINCIPLES = [
  {
    n: '01',
    icon: Eye,
    title: 'Transparent by default',
    body: 'Rate, scope and fees are visible to both sides before a contract is signed — not renegotiated after the shoot, and never a deduction nobody explained.',
    tag: 'No surprise deductions',
  },
  {
    n: '02',
    icon: ShieldCheck,
    title: 'Creators treated like businesses',
    body: 'Funds are secured with a licensed, RBI-authorized Payment Aggregator before filming starts, and payout follows the milestone written into the contract — not a follow-up message.',
    tag: 'Payment-protected from day one',
    link: { label: 'How Secure Payments works', to: '/features/secure-payments' },
  },
  {
    n: '03',
    icon: Zap,
    title: 'Built for speed at scale',
    body: 'A Hype Campaign lets a brand run a 72-hour, up-to-100-creator drop at one flat rate with no back-and-forth — replacing a spreadsheet with a single dashboard.',
    tag: 'One dashboard, every deal',
    link: { label: 'Explore Hype Campaigns', to: '/features/hype' },
  },
  {
    n: '04',
    icon: Landmark,
    title: 'Compliance you can actually see',
    body: 'Every payout shows any recorded TDS deduction and generates an invoice automatically. Influora does not file your returns for you.',
    tag: 'TDS recorded and shown, not filed',
    link: { label: 'Read the TDS policy', to: '/tds' },
  },
] as const;

const CULTURE_POINTS = [
  {
    title: 'Contracts held to the same rigor as the payment flow',
    body: 'Deal Room terms, revision caps and usage rights get the same scrutiny as the money that moves against them.',
  },
  {
    title: 'A short feedback loop',
    body: 'Deal Room and Hype Campaign features ship from issues the team sees brands and creators hit first-hand, not a five-step approval chain.',
  },
] as const;

export default function AboutPage() {
  return (
    <div className="min-h-screen bg-background text-foreground">
      <Seo
        title="About Influora"
        description="Influora replaces WhatsApp-chaos influencer deals with one payment-protected platform for Indian brands and creators. Our story, our principles, and who's behind it."
        canonical="/about"
      />
      <JsonLd data={getOrganizationSchema()} />
      <JsonLd
        data={getWebPageSchema({
          name: 'About Influora',
          description:
            'Influora is an influencer marketing platform built for the Indian market, replacing WhatsApp-and-invoice brand deals with one workspace where terms, contract and payment live together.',
          url: '/about',
        })}
      />
      <JsonLd
        data={getBreadcrumbListSchema([
          { name: 'Home', url: '/' },
          { name: 'About', url: '/about' },
        ])}
      />

      <SiteHeader />

      <main>
        {/* ---------------------------------------------------------------- Hero */}
        <section className="border-b border-border/60 py-20">
          <div className="mx-auto max-w-3xl px-6 text-center">
            <FadeUp>
              <Badge variant="outline" className="gap-1.5">
                <Sparkles className="h-3 w-3" aria-hidden="true" /> About Influora
              </Badge>
              <h1 className="mt-4 text-4xl font-bold leading-tight tracking-tight sm:text-5xl">
                Built to fix how brands and creators do deals
              </h1>
              <p className="mt-4 text-lg text-muted-foreground">
                Influora exists to replace the spreadsheet-WhatsApp-invoice mess of influencer
                marketing with one platform Indian brands and creators can trust — where every
                deal is payment-protected from the moment a contract is signed.
              </p>
            </FadeUp>

            <FadeUp>
              <dl className="mx-auto mt-12 grid max-w-3xl grid-cols-2 gap-x-6 gap-y-6 border-t border-border/60 pt-8 sm:grid-cols-4">
                {HERO_PILLS.map((p) => {
                  const Icon = p.icon;
                  return (
                    <div key={p.label} className="flex flex-col items-center gap-2 text-center">
                      <span className="flex h-10 w-10 items-center justify-center rounded-lg bg-accent text-accent-foreground">
                        <Icon className="h-5 w-5" aria-hidden="true" />
                      </span>
                      <dd className="text-xs text-muted-foreground">{p.label}</dd>
                    </div>
                  );
                })}
              </dl>
            </FadeUp>
          </div>
        </section>

        {/* Problem / solution */}
        <section className="border-b border-border/60 bg-card/50 py-20">
          <div className="mx-auto max-w-5xl px-6">
            <div className="grid gap-6 lg:grid-cols-2">
              <FadeUp>
                <Card className="h-full">
                  <CardContent className="p-6">
                    <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                      Before Influora
                    </p>
                    <p className="mt-3 text-sm text-muted-foreground">
                      Deals get negotiated across Instagram DMs, WhatsApp, and email. Contracts, if
                      they exist at all, are a separate PDF nobody signs on time. Brands pay
                      upfront with no guarantee of delivery; creators deliver with no guarantee of
                      payment. Someone always ends up chasing the other.
                    </p>
                  </CardContent>
                </Card>
              </FadeUp>
              <FadeUp delay={0.1}>
                <Card className="h-full border-accent-foreground/30">
                  <CardContent className="p-6">
                    <p className="text-xs font-semibold uppercase tracking-wide text-accent-foreground">
                      After Influora
                    </p>
                    <p className="mt-3 text-sm text-foreground">
                      One platform: verified creator discovery, a single Deal Room thread, an
                      e-signed contract with clear terms, and protection that holds the brand's
                      payment until the deliverable is approved. Nobody has to chase anybody.
                    </p>
                  </CardContent>
                </Card>
              </FadeUp>
            </div>
          </div>
        </section>

        {/* ------------------------------------------------------------- Founder */}
        <section className="py-20">
          <div className="mx-auto max-w-3xl px-6">
            <FadeUp>
              <Card>
                <CardContent className="p-6 sm:p-8">
                  {/*
                    No portrait here — see the file-level note above. A real
                    photograph of the founder is needed before one is added;
                    this is awaiting the CEO's own decision, so do not
                    substitute a placeholder, illustration, or stock photo.
                  */}
                  <div className="flex flex-wrap items-center gap-3">
                    <Badge variant="outline">Founder &amp; CEO</Badge>
                    <p className="text-xs text-muted-foreground">A note from our founder</p>
                  </div>
                  <p className="mt-4 text-lg font-medium leading-relaxed text-foreground">
                    “Trust isn't an afterthought in creator partnerships — it has to be built into
                    how the platform works.”
                  </p>
                  <div className="mt-5 space-y-4 text-sm text-muted-foreground">
                    <p>
                      For years working inside India's fast-moving D2C ecommerce world, I watched
                      strong brands put real budget into influencer marketing only to lose
                      visibility to agency markups, vague scopes, and WhatsApp threads nobody
                      could point back to later.
                    </p>
                    <p>
                      At the same time, some of India's best creators — the ones actually
                      scripting, filming, and driving real conversions — were left chasing
                      invoices for 60 to 90 days, with deductions applied however the other side
                      decided that month.
                    </p>
                    <p>
                      We built Influora to close that gap. When a brand's payment is secured
                      before a creator ever picks up a camera, when scope and revision limits are
                      written into a contract both sides e-signed, and when payout follows
                      approval instead of a phone call, the relationship stops being adversarial.
                    </p>
                    <p>
                      Our commitment is simple: transparent pricing, a payment-protected deal from
                      day one, and a platform that treats creators like the businesses they are.
                    </p>
                  </div>
                  <div className="mt-6 border-t border-border/60 pt-4">
                    <p className="font-semibold">Swapnil Maruti Shinde</p>
                    <p className="text-sm text-muted-foreground">Founder &amp; Chief Executive Officer</p>
                  </div>
                </CardContent>
              </Card>
            </FadeUp>
          </div>
        </section>

        {/* ----------------------------------------------------------- Principles */}
        <section className="border-y border-border/60 bg-card/50 py-20">
          <div className="mx-auto max-w-6xl px-6">
            <FadeUp className="mx-auto max-w-xl text-center">
              <h2 className="text-3xl font-semibold">The principles behind the product</h2>
              <p className="mt-3 text-muted-foreground">
                Every feature on Influora traces back to one of these.
              </p>
            </FadeUp>
            <StaggerContainer className="mt-12 grid gap-5 sm:grid-cols-2">
              {PRINCIPLES.map((p) => {
                const Icon = p.icon;
                return (
                  <StaggerItem key={p.title}>
                    <Card className="h-full">
                      <CardContent className="p-6">
                        <div className="flex items-center justify-between">
                          <span className="flex h-10 w-10 items-center justify-center rounded-lg bg-accent text-accent-foreground">
                            <Icon className="h-5 w-5" aria-hidden="true" />
                          </span>
                          <span className="text-xs font-semibold tracking-widest text-muted-foreground/70">
                            {p.n}
                          </span>
                        </div>
                        <h3 className="mt-4 font-semibold">{p.title}</h3>
                        <p className="mt-1.5 text-sm text-muted-foreground">{p.body}</p>
                        <span className="mt-4 inline-block rounded-full bg-muted px-2.5 py-1 text-xs text-muted-foreground">
                          {p.tag}
                        </span>
                        {'link' in p && p.link && (
                          <Link
                            to={p.link.to}
                            className="mt-3 block text-sm font-medium text-accent-foreground hover:underline"
                          >
                            {p.link.label} →
                          </Link>
                        )}
                      </CardContent>
                    </Card>
                  </StaggerItem>
                );
              })}
            </StaggerContainer>
          </div>
        </section>

        {/* ------------------------------------------------------- Culture / team */}
        <section className="py-20">
          <div className="mx-auto grid max-w-6xl items-center gap-10 px-6 lg:grid-cols-2">
            <FadeUp>
              <p className="text-sm font-semibold uppercase tracking-widest text-muted-foreground">
                Culture &amp; engineering
              </p>
              <h2 className="mt-3 text-3xl font-bold tracking-tight sm:text-4xl">
                Built for India's creator economy, not adapted for it
              </h2>
              <p className="mt-4 text-muted-foreground">
                The team behind Influora works out of one product, engineering and growth group —
                the people who write the Deal Room contract logic sit next to the people who talk
                to the brands and creators using it.
              </p>
              <div className="mt-8 space-y-5">
                {CULTURE_POINTS.map((c) => (
                  <div key={c.title} className="flex gap-3">
                    <CheckCircle2
                      className="mt-0.5 h-5 w-5 shrink-0 text-primary"
                      aria-hidden="true"
                    />
                    <div>
                      <h3 className="font-semibold">{c.title}</h3>
                      <p className="mt-1 text-sm text-muted-foreground">{c.body}</p>
                    </div>
                  </div>
                ))}
              </div>
            </FadeUp>

            <FadeUp>
              <img
                src="/stitch-media/influora-engineering-product-and-growth-team-collabo-c76372.jpg"
                alt=""
                loading="lazy"
                className="w-full rounded-2xl border border-border/60 object-cover"
              />
            </FadeUp>
          </div>
        </section>

        {/* Proof points — capability, not traction (F-0342) */}
        <section className="border-t border-border/60 bg-card/50 py-20">
          <div className="mx-auto max-w-4xl px-6 text-center">
            <FadeUp>
              <h2 className="text-3xl font-semibold">What you get from day one</h2>
            </FadeUp>
            <StaggerContainer className="mt-10 grid grid-cols-1 gap-8 sm:grid-cols-3">
              {/* F-0342 — see src/components/site/proof-points.ts. */}
              {PROOF_POINTS.map((point) => (
                <StaggerItem key={point.label}>
                  <p className="text-3xl font-bold">{point.value}</p>
                  <p className="mt-1 text-sm text-muted-foreground">{point.label}</p>
                </StaggerItem>
              ))}
            </StaggerContainer>
          </div>
        </section>

        {/* ------------------------------------------------- Legal / corporate identity */}
        <section className="py-20">
          <div className="mx-auto max-w-3xl px-6">
            <FadeUp>
              <h2 className="text-center text-2xl font-semibold">Legal &amp; corporate identity</h2>
              <p className="mx-auto mt-2 max-w-xl text-center text-sm text-muted-foreground">
                Every value below is sourced from our single company record — never retyped by
                hand on this page.
              </p>
              <Card className="mt-8">
                <CardContent className="flex flex-col gap-6 p-6 sm:flex-row sm:items-start sm:justify-between">
                  <div className="text-sm">
                    <p className="font-semibold text-foreground">{COMPANY.legalName}</p>
                    <p className="mt-2 text-muted-foreground">
                      CIN: <span className="font-mono">{COMPANY.cin}</span>
                    </p>
                    <p className="mt-0.5 text-muted-foreground">
                      GSTIN: <span className="font-mono">{COMPANY.gstin}</span>
                    </p>
                    <p className="mt-0.5 text-muted-foreground">
                      {COMPANY.registeredAddress || `Registered in ${COMPANY.state}, India`}
                    </p>
                  </div>
                  <div className="flex flex-col gap-1.5 text-sm text-muted-foreground">
                    <a href={`mailto:${COMPANY.email}`} className="hover:text-foreground">
                      {COMPANY.email}
                    </a>
                    <a href={`tel:${COMPANY.phoneHref}`} className="hover:text-foreground">
                      {COMPANY.phone}
                    </a>
                  </div>
                </CardContent>
              </Card>
            </FadeUp>
          </div>
        </section>

        {/* Trust signals — no fake logos, and no unsubstantiated counts (F-0342, F-0343) */}
        <section className="border-t border-border/60 bg-card/50 py-20">
          <div className="mx-auto max-w-3xl px-6 text-center">
            <FadeUp>
              {/*
                F-0343: an eyebrow line here asserted a specific count of Indian
                brands using the platform. No source produced that figure, so it
                was removed. What replaced it is the mechanism the heading and
                body already describe: verification and a licensed payment
                partner are things the product does, checkable by using it.
              */}
              <p className="text-sm font-medium text-accent-foreground">
                Verified profiles · licensed payment partner
              </p>
              <h2 className="mt-2 text-2xl font-semibold">
                Verified creators of all sizes, from nano to macro
              </h2>
              <p className="mt-3 text-muted-foreground">
                Every creator profile is Instagram-verified before it's discoverable, and every
                payout moves through Influora's licensed payment gateway partner — the same
                protected payment rail whether it's a single reel or a 100-creator Hype Campaign.
              </p>
            </FadeUp>
          </div>
        </section>

        <FunnelCta
          heading="Start your first campaign"
          sub="Free to start for brands and creators. Payment-protected from the first deal."
          primary={{ label: 'Start your first campaign', to: '/brand/register' }}
          secondary={{ label: 'See how a deal works first', to: '/how-it-works/brands' }}
          reassurances={['Free to start', 'No subscription', 'Contracts and TDS included']}
          className="py-20"
        />
      </main>

      <SiteFooter />
      <StickyCta label="Start your first campaign" to="/brand/register" note="Free to start" />
      <StickyCtaSpacer />
    </div>
  );
}
