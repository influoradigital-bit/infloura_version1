import { Link } from 'react-router-dom';
import { ArrowRight, Ban, Check, Percent, ShieldCheck, Sparkles, Wallet, Zap } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent } from '@/components/ui/card';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { FadeUp } from '@/components/motion';
import { SiteHeader } from '@/components/site/SiteHeader';
import { SiteFooter } from '@/components/site/SiteFooter';
import { FaqSection } from '@/components/site/FaqSection';
import { FunnelCta } from '@/components/site/FunnelCta';
import { StickyCta, StickyCtaSpacer } from '@/components/site/StickyCta';
import { Seo } from '@/lib/seo/Seo';
import {
  JsonLd,
  getSoftwareApplicationSchema,
  getWebPageSchema,
} from '@/lib/seo/schema';

// Content per wiki/website/pricing-subscription-copy.md (Tejas CMO draft,
// CTO-corrected 2026-07-14) + Nisha content-QA refinements
// (wiki/website/pricing-presentation-nisha.md, 2026-07-14) + CEO-DECISIONS.md
// P-3 (digit-free fee rule). CTO OVERRIDE (Priya, 2026-07-14): there is NO
// sign-off to print 7%/10% anywhere on this page — the earlier "scoped
// exception" claim was incorrect. Platform fee is word-based only
// ("Included" / "Reduced" per Nisha's stronger framing) in the cards AND the
// comparison matrix. The only price digit on the page is ₹4,999 (Pro price).
// Feature-count numbers (seats, creators, credits, analytics views, 15%
// creator commission) are unaffected and stay as-is.
// "Export reports" and "Campaign templates" are Pro-tier roadmap items whose
// endpoints don't exist yet — labeled "Coming soon" on the card and matrix,
// never presented as active today. FAQ's Export/Templates timing question
// deliberately omits a build date (Priya correction, 2026-07-14) — no
// schedule is confirmed with engineering yet.
//
// CEO RULING (Swapnil, 2026-09-05): "platform is free, AI is paid".
// ------------------------------------------------------------------
// Four items — the campaign dashboard, auto-generated contracts + e-signature,
// payment protection, and TDS-on-payout + dispute resolution — used to appear
// verbatim in BOTH FREE_INCLUDED and PRO_INCLUDED, and as four identical rows
// in the matrix. Repeating a baseline capability inside a paid tier's bullet
// list reads as a paid unlock; a reader scanning the Pro card cannot tell that
// Free has the same thing. They are now declared ONCE, in EVERY_PLAN_INCLUDED,
// and rendered in a band that says so — and the matrix carries the same split,
// because the cards and the matrix are separate structures in this file and a
// reader who scrolls sees both.
//
// The tier lists below therefore hold ONLY what actually differs, AI first,
// because AI credits are the differentiator the ruling names. No price, cap or
// commission changed in this pass — only how they are grouped and described.
//
// HONEST-QUALIFIER RULE FOR THIS PAGE. Three of Pro's unlocks (seats, tracked
// creators, analytics deep-dives) and the reduced brand publish fee are
// platform gates, not AI. Copy therefore says "no subscription to use the
// platform" and never a bare "the platform is free", and it never implies the
// caps are gone. Removing those caps would be a revenue decision and is
// Swapnil's alone — see the report filed with this change.
//
// BRAND-FEE TIMING RULE FOR THIS PAGE. The brand-side platform fee is
// charged ONCE PER CAMPAIGN, at the moment the campaign goes live, and it
// is computed on the campaign's committed budget (budgetMax), not on what
// the campaign actually spends. It is a wallet debit inside the same
// transaction as the DRAFT -> ACTIVE flip, so an underfunded wallet blocks
// the publish; there is deliberately no refund path if the campaign later
// spends less. See BrandCampaignFeeService.chargeOnPublish and its two
// callers (CampaignService.update, ConfirmLaunchExecutor.doExecute).
// Copy on this page must therefore say "when a campaign goes live" and
// never phrase the BRAND fee as something taken once a deal finishes or
// closes. That per-transaction phrasing belongs to the CREATOR commission,
// a separate charge deducted at escrow release
// (PlatformFeeService.deductAtRelease); the creator-facing wording further
// down this file is correct and must stay.
//
// STITCH PORT (2026-09-07). Two Stitch designs targeted this page and
// contradicted each other:
//   - scratchpad/txt/transparent-pricing-free-pro-plans.txt — USED. Its cards
//     and comparison-matrix copy already matched this file's verified
//     constants almost verbatim (same tier names, same allowances, same
//     matrix groups, same FAQ facts), which is why it's the one this page
//     takes design cues from.
//   - scratchpad/txt/transparent-pricing-economics.txt — REJECTED. Invents a
//     "7% platform fee" that the CTO ruling above forbids printing on this
//     page, and separately claims creators pay 0%, which is false (15%, see
//     MATRIX_GROUPS "Identical on both plans"). Ananya flagged the pick to
//     Swapnil for override rather than deciding it unilaterally.
//
// The CHOSEN file still contradicted itself: its hero stat tiles read
// "0% Platform Fee — Creators take home 100% of agreed baseline rate", while
// its OWN comparison-matrix rows two screens down read "Creator commission
// 15% (unchanged)". The 15% is correct — it's the same figure already locked
// in MATRIX_GROUPS and the FAQ below. The 0%/100% tile was deleted outright,
// not reworded; nothing on this page states or implies a 0% creator
// commission.
//
// Also NOT carried over from the chosen file, and why:
//   - "Sovereign Creator[s]" / "Sovereign Creator Charter" — banned word, cut
//     wherever it appeared (hero H1, a whole mid-page section).
//   - "<3s Instant UPI" / "3-Second UPI Release" — unmeasured; the actual,
//     already-published figure elsewhere on this site is "typically within
//     24 hours" (see how-it-works/creators). Never printed here.
//   - "Instant automated TDS challans and Form 26Q reporting" / "Tax
//     Compliance Auto-Pilot" / "claim complete 18% Input Tax Credit on every
//     single platform invoice" — TDS is recorded and shown on the payout and
//     invoice; Influora does not file returns or give tax advice. Rule 4.
//   - "RBI-regulated safety vault" / "RBI Licensed Banking Partner" — we are
//     not RBI-licensed; only our payments partner is an RBI-authorized
//     Payment Aggregator, stated as such where this page names it. "ISO 27001
//     Certified" deleted outright, per standing rule.
//   - A testimonial quote attributed to a fabricated "Kavya Sharma, Fashion &
//     Tech Creator" (name collision with this team's own QA lead is
//     coincidental and irrelevant — it's fabricated either way), plus a
//     second fabricated pair (Deepika R./SkinBloom Mumbai, Arjun Sen) citing
//     invented stats ("+88% ROAS Lift", "1.8x to 3.4x", "850+ brands",
//     "14,000+ creators"). No real or fabricated names, no unmeasured stats.
//   - A "High-Volume Brands" enterprise tier at a "4.0%" volume fee floor with
//     SAP/Tally ERP integration claims — not a tier this page's verified
//     pricing facts include; not added.
//   - The one background image the design used was captioned "Instant UPI
//     payment notification" and sits inside the rejected 3-second-payout
//     claim above, so it wasn't ported either — this page stays image-free.
//
// What WAS taken from the design: the four-tile fact band under the hero
// (HERO_FACTS below) borrows its layout — icon, big value, short caption —
// but every value in it is one already stated and sourced elsewhere on this
// page (the EVERY_PLAN_INCLUDED band, MATRIX_GROUPS, the tier cards).

interface IncludedItem {
  label: string;
  comingSoon?: boolean;
}

/**
 * Platform baseline. Every workspace has these on day one, on Free, with no
 * subscription. Never duplicate one of these into a tier list — that is the
 * exact defect this constant exists to prevent.
 */
const EVERY_PLAN_INCLUDED: string[] = [
  'Campaign performance dashboard (unlimited)',
  'Auto-generated contracts + e-signature',
  'Payment protection on every deal',
  'TDS recorded on payouts + dispute resolution',
];

const FREE_INCLUDED: IncludedItem[] = [
  { label: '100 AI credits/month (150 after first funded campaign)' },
  { label: '1 creator analytics deep-dive/month' },
  { label: '1 workspace seat' },
  { label: '5 tracked creators' },
];

const PRO_INCLUDED: IncludedItem[] = [
  { label: '400 AI credits/month' },
  { label: 'Unlimited creator analytics deep-dives' },
  { label: '5 workspace seats' },
  { label: 'Unlimited tracked creators' },
  { label: 'Export reports (CSV/PDF)', comingSoon: true },
  { label: 'Campaign templates library', comingSoon: true },
];

/**
 * Hero fact band — layout borrowed from the Stitch design's stat tiles (see
 * the STITCH PORT note above), values are not. Every entry here restates a
 * fact already established elsewhere on this page (EVERY_PLAN_INCLUDED,
 * MATRIX_GROUPS, the tier cards) — this band adds no new claim.
 */
const HERO_FACTS = [
  { icon: Wallet, value: '₹0', label: 'To start — no subscription on Free' },
  { icon: Percent, value: '15%', label: 'Creator commission, identical on every plan' },
  { icon: Ban, value: 'None', label: 'Trial period, on either plan' },
  { icon: ShieldCheck, value: 'Every deal', label: 'Payment protection, Free and Pro' },
] as const;

const CREATOR_INCLUDED = [
  'Free to join and build your profile',
  'Free to accept deals and Hype Campaign slots',
  'Invoice with any recorded TDS shown',
  'UPI or direct bank payout',
  'Payment protection before you start work',
];

type MatrixCellValue =
  | { kind: 'text'; value: string }
  | { kind: 'check' }
  | { kind: 'dash' }
  | { kind: 'comingSoon' };

interface MatrixRow {
  feature: string;
  free: MatrixCellValue;
  pro: MatrixCellValue;
}

interface MatrixGroup {
  title: string;
  note: string;
  rows: MatrixRow[];
}

// The matrix is grouped so it tells the SAME story as the cards above it: what
// a subscription actually buys, then what every workspace already has. Before
// this pass the four baseline rows sat interleaved with the tier rows, so a
// reader scanning the "Pro" column read fourteen consecutive Pro entitlements.
const MATRIX_GROUPS: MatrixGroup[] = [
  {
    title: 'What a Pro subscription changes',
    note: 'AI credits are the headline. The rest are limit increases.',
    rows: [
      {
        feature: 'Monthly subscription',
        free: { kind: 'text', value: '₹0' },
        pro: { kind: 'text', value: '₹4,999' },
      },
      {
        feature: 'AI credits/month',
        free: { kind: 'text', value: '100 → 150 (after first funded campaign)' },
        pro: { kind: 'text', value: '400' },
      },
      {
        feature: 'Creator analytics deep-dives',
        free: { kind: 'text', value: '1 view/month' },
        pro: { kind: 'text', value: 'Unlimited' },
      },
      {
        feature: 'Workspace seats',
        free: { kind: 'text', value: '1' },
        pro: { kind: 'text', value: '5' },
      },
      {
        feature: 'Tracked creators',
        free: { kind: 'text', value: 'Up to 5' },
        pro: { kind: 'text', value: 'Unlimited' },
      },
      {
        feature: 'Report export (CSV/PDF)',
        free: { kind: 'dash' },
        pro: { kind: 'comingSoon' },
      },
      {
        feature: 'Campaign templates library',
        free: { kind: 'dash' },
        pro: { kind: 'comingSoon' },
      },
      {
        feature: 'Platform fee when a campaign goes live',
        free: { kind: 'text', value: 'Included' },
        pro: { kind: 'text', value: 'Reduced' },
      },
    ],
  },
  {
    title: 'Included on every plan, including Free',
    note: 'Not a Pro unlock. These are how the platform works for everyone.',
    rows: [
      {
        feature: 'Campaign performance dashboard',
        free: { kind: 'text', value: 'Unlimited (own campaigns)' },
        pro: { kind: 'text', value: 'Unlimited (own campaigns)' },
      },
      {
        feature: 'Auto-generated contracts + e-signature',
        free: { kind: 'check' },
        pro: { kind: 'check' },
      },
      {
        feature: 'Payment protection',
        free: { kind: 'text', value: 'Every deal' },
        pro: { kind: 'text', value: 'Every deal' },
      },
      {
        feature: 'TDS recorded on payouts + dispute resolution',
        free: { kind: 'check' },
        pro: { kind: 'check' },
      },
    ],
  },
  {
    title: 'Identical on both plans',
    note: 'Your plan does not move these.',
    rows: [
      {
        feature: 'Creator commission',
        free: { kind: 'text', value: '15% (unchanged)' },
        pro: { kind: 'text', value: '15% (unchanged)' },
      },
      {
        feature: 'Trial period',
        free: { kind: 'text', value: 'None' },
        pro: { kind: 'text', value: 'None' },
      },
    ],
  },
];

function MatrixCell({ value }: { value: MatrixCellValue }) {
  if (value.kind === 'check') {
    return (
      <span role="img" aria-label="Included">
        <Check className="h-4 w-4 text-accent-foreground" aria-hidden="true" />
      </span>
    );
  }
  if (value.kind === 'dash') {
    return (
      <span className="text-muted-foreground" aria-label="Not available">
        —
      </span>
    );
  }
  if (value.kind === 'comingSoon') {
    return (
      <Badge variant="secondary" className="whitespace-nowrap">
        Coming soon
      </Badge>
    );
  }
  return <span>{value.value}</span>;
}

const FAQS = [
  {
    question: 'Is there a free plan?',
    answer:
      'Yes. The Free plan is permanently usable — no time limit, no trial countdown. The platform itself carries no subscription: you pay a platform fee when you take a campaign live. Pro is an optional upgrade for brands who want more AI credits, and it lifts the seat, tracked-creator and analytics limits that Free caps.',
  },
  {
    question: 'Do I have to subscribe to use Influora?',
    answer:
      'No. Using the platform requires no subscription. Discovering creators, running deals, Secure Payments, auto-generated contracts, dispute resolution, TDS shown on payouts and your campaign dashboard are all on the Free tier, permanently. A subscription buys AI capacity — and, alongside it, higher limits on seats, tracked creators and analytics.',
  },
  {
    question: "What's the difference between Free and Pro?",
    answer:
      'The headline difference is AI: Pro gives you 400 AI credits a month instead of 100 (150 after your first funded campaign). Pro also raises the limits Free caps — unlimited creator analytics deep-dives (vs. 1/month), 5 workspace seats (vs. 1), unlimited tracked creators (vs. 5) — adds report export (CSV/PDF) and campaign templates when they launch, and reduces the brand fee charged when a campaign goes live. Everything else — contracts, payment protection, dispute resolution, TDS on payouts, the campaign dashboard — is the same on both. See the comparison table above.',
  },
  {
    question: 'Does upgrading to Pro change what creators earn?',
    answer:
      'No. The creator commission (15%) is the same on both tiers. Your plan choice only affects the brand-side fee — creators are paid identically whether you\'re on Free or Pro.',
  },
  {
    question: 'Is there a trial for Pro?',
    answer:
      "No trial. Free tier is permanently usable (not a time-boxed trial), so you can test the platform as long as you need. When you're ready to upgrade, Pro starts immediately — no trial period.",
  },
  {
    question: 'When am I charged for Pro?',
    answer:
      'Pro is billed monthly via Razorpay Subscriptions. Your first charge happens the moment you subscribe. Renewal charges automatically each month on the same date unless you cancel.',
  },
  {
    question: 'Can I cancel Pro?',
    answer:
      "Yes. You can cancel anytime from your billing settings. You'll keep Pro features through the end of your current billing period, then automatically drop back to Free — no data loss, no lock-in.",
  },
  {
    question: 'How is the brand fee different on Pro?',
    answer:
      'Pro reduces the brand fee. Free uses the standard rate. We do not publish either rate on this page — your workspace’s current rate is shown on the campaign before you take it live, and the fee itself appears in rupees on the invoice raised at that moment, so you are never estimating from a percentage.',
  },
  {
    // The honest qualifier on "the platform is free". Free is genuinely
    // permanent and genuinely carries the full protection stack — but it is
    // capped, and a page that says "free" without saying where the ceiling is
    // is selling a surprise. This answer names every cap in one place.
    question: 'Is Free actually free, or is it a limited version?',
    answer:
      'Both, honestly. Every plan — Free included — gets auto-generated contracts, payment protection on every deal, dispute resolution, TDS shown on payouts and an unlimited campaign dashboard. None of that is behind the subscription. Free is capped, though: 1 workspace seat, up to 5 tracked creators, 1 creator analytics deep-dive a month, and 100 AI credits a month (150 after your first funded campaign). A platform fee applies when a campaign goes live, on either plan. Pro raises the caps and cuts that fee.',
  },
  {
    question: 'When do I actually pay (or get paid)?',
    answer:
      'Brands: the platform fee is charged once, from your wallet, at the moment you take a campaign live — it is calculated on the campaign budget you commit, and it is not refunded if the campaign later spends less. Creator payments are separate: they stay in Secure Payments until you approve the work. Creators: payout releases automatically once the brand approves the deliverable, usually within 24 hours.',
  },
  {
    question: 'What if the deal falls through?',
    answer:
      "If a deal doesn't complete — for example, the creator never delivers — the protected amount is returned to the brand once the dispute (if any) is resolved. You're not charged for work that never happened.",
  },
  {
    question: 'When will Export reports and Campaign templates be available?',
    answer:
      'Both features are included in your Pro subscription and are in active development. Pro subscribers get immediate access the moment they launch — no extra charge, no separate upgrade needed.',
  },
];

export default function PricingPage() {
  return (
    <div className="min-h-screen bg-background text-foreground">
      <Seo
        title="Pricing"
        description="No subscription to use Influora: contracts, payment protection, dispute resolution, TDS on payouts and the campaign dashboard are on the Free tier. Pro (₹4,999/month) is for more AI credits and higher seat, creator and analytics limits. Creators join free."
        canonical="/pricing"
      />
      {/*
        The priced offer lives here as well as on the homepage, because /pricing
        is the URL an answer engine actually retrieves for a cost question. Only
        the two TIER PRICES appear (₹0 and ₹4,999) — never a platform-fee
        percentage. That is a standing CTO ruling (see the header comment on this
        file, CEO-DECISIONS.md P-3): the fee is word-based on this page, and
        putting a digit in the structured data would leak exactly the number the
        rendered page is forbidden to state, while also creating a schema/visible
        mismatch that costs the rich result.
      */}
      <JsonLd
        data={getSoftwareApplicationSchema({
          description:
            'Influora pricing for brands and creators: the platform carries no subscription — the Free tier includes contracts, payment protection, dispute resolution, TDS on payouts and the campaign dashboard. Pro at ₹4,999/month buys 400 AI credits a month and higher seat, creator and analytics limits. Creators join free.',
          url: 'https://influora.in/pricing',
          offers: [
            {
              name: 'Free',
              price: 0,
              description:
                'No subscription. Discover creators, run deals, use protected payments, generate contracts and resolve disputes; a platform fee applies when you take a campaign live.',
            },
            {
              name: 'Pro',
              price: 4999,
              billingPeriod: 'MON',
              description:
                '400 AI credits a month, plus 5 team seats, unlimited tracked creators, unlimited creator analytics and a reduced platform fee on every campaign you take live.',
            },
          ],
        })}
      />
      <JsonLd
        data={getWebPageSchema({
          name: 'Influora Pricing',
          description:
            'Using Influora needs no subscription: the Free tier includes contracts, payment protection, dispute resolution, TDS on payouts and the campaign dashboard, with a platform fee charged when a campaign goes live. Pro at ₹4,999 per month buys more AI credits and raises the seat, tracked-creator and analytics limits. Creators join and get paid for free.',
          url: '/pricing',
        })}
      />

      <SiteHeader />

      <main>
        {/* Hero */}
        <section className="border-b border-border/60 py-20">
          <div className="mx-auto max-w-3xl px-6 text-center">
            <FadeUp>
              <Badge variant="outline" className="gap-1.5">
                <ShieldCheck className="h-3 w-3" aria-hidden="true" /> Simple, transparent pricing
              </Badge>
              <h1 className="mt-4 text-4xl font-bold leading-tight tracking-tight sm:text-5xl">
                No subscription to use Influora. AI is what you pay for.
              </h1>
              <p className="mt-4 text-lg text-muted-foreground">
                Contracts, payment protection, dispute resolution, TDS shown on payouts and your campaign
                dashboard are included on every plan — Free included. Pro adds AI credits and raises the
                limits on seats, tracked creators and analytics.
              </p>

              <dl className="mx-auto mt-10 grid max-w-2xl grid-cols-2 gap-x-6 gap-y-6 border-t border-border/60 pt-8 sm:grid-cols-4">
                {HERO_FACTS.map((f) => {
                  const Icon = f.icon;
                  return (
                    <div key={f.label} className="text-center">
                      <span className="mx-auto flex h-9 w-9 items-center justify-center rounded-full bg-accent text-accent-foreground">
                        <Icon className="h-4 w-4" aria-hidden="true" />
                      </span>
                      <dt className="mt-2 text-xl font-bold tracking-tight">{f.value}</dt>
                      <dd className="mt-1 text-xs text-muted-foreground">{f.label}</dd>
                    </div>
                  );
                })}
              </dl>
            </FadeUp>
          </div>
        </section>

        {/* Brand tier comparison — Free | Pro. Platform fee is word-based only
            ("Included" / "Reduced") in both the cards and the matrix below —
            no fee percentages anywhere on this page. */}
        <section className="py-20">
          <div className="mx-auto max-w-5xl px-6">
            <FadeUp className="text-center">
              <Badge variant="outline">For brands</Badge>
              <h2 className="mt-3 text-2xl font-semibold">
                Start with everything. Pay when you want more AI.
              </h2>
            </FadeUp>

            {/*
              The universal band. These four used to be repeated inside both
              tier bullet lists, which made a platform baseline look like a Pro
              unlock. Stating them ONCE, above the tiers, is the whole point —
              the reader learns what they already have before they are asked to
              compare. The tier cards below must never restate one of these.
            */}
            <FadeUp delay={0.05} className="mt-8">
              <div className="rounded-xl border border-accent-foreground/30 bg-card/50 p-6">
                <div className="flex items-center justify-center gap-2 text-center">
                  <ShieldCheck className="h-4 w-4 shrink-0 text-accent-foreground" aria-hidden="true" />
                  <h3 className="text-sm font-semibold">Included on every plan, including Free</h3>
                </div>
                <ul className="mt-4 grid gap-3 sm:grid-cols-2">
                  {EVERY_PLAN_INCLUDED.map((line) => (
                    <li key={line} className="flex items-start gap-2.5 text-sm">
                      <Check
                        className="mt-0.5 h-4 w-4 shrink-0 text-accent-foreground"
                        aria-hidden="true"
                      />
                      <span>{line}</span>
                    </li>
                  ))}
                </ul>
                <p className="mt-4 text-center text-xs text-muted-foreground">
                  None of this sits behind the subscription. The tiers below differ only in AI credits and
                  in how far the seat, tracked-creator and analytics limits go.
                </p>
              </div>
            </FadeUp>

            <div className="mt-8 grid gap-6 lg:grid-cols-2">
              {/*
                Card bullet lists hold ONLY what differs between the tiers, AI
                credits first — everything universal lives in the band above.
              */}
              <FadeUp>
                <Card className="h-full">
                  <CardContent className="p-8">
                    <Badge variant="outline">Free</Badge>
                    <p className="mt-4 text-3xl font-bold">₹0/month</p>
                    <p className="mt-1 text-sm text-muted-foreground">
                      The full platform, no subscription. A fee applies when you take a campaign live.
                    </p>
                    <p className="mt-4 text-xs font-medium uppercase tracking-wide text-muted-foreground">
                      Monthly allowances
                    </p>
                    <ul className="mt-6 space-y-3">
                      {FREE_INCLUDED.map((item) => (
                        <li key={item.label} className="flex items-start gap-2.5 text-sm">
                          <Check className="mt-0.5 h-4 w-4 shrink-0 text-accent-foreground" aria-hidden="true" />
                          <span>{item.label}</span>
                        </li>
                      ))}
                    </ul>
                    <div className="mt-6 rounded-lg border border-border/60 bg-card/50 p-4">
                      <p className="text-sm font-semibold">Platform fee when a campaign goes live</p>
                      <p className="mt-0.5 text-xs text-muted-foreground">
                        Your rate is shown on the campaign before you publish. Creator commission unchanged.
                      </p>
                    </div>
                    <Button
                      size="lg"
                      className="mt-8 w-full bg-accent-foreground text-white hover:bg-accent-foreground/90"
                      asChild
                    >
                      <Link to="/brand/register">
                        Start free <ArrowRight className="ml-1.5 h-4 w-4" aria-hidden="true" />
                      </Link>
                    </Button>
                  </CardContent>
                </Card>
              </FadeUp>

              <FadeUp delay={0.1}>
                <Card className="h-full border-accent-foreground/30">
                  <CardContent className="p-8">
                    <Badge className="gap-1">
                      <Sparkles className="h-3 w-3" aria-hidden="true" /> Pro
                    </Badge>
                    <p className="mt-4 text-3xl font-bold">₹4,999/month</p>
                    <p className="mt-1 text-sm text-muted-foreground">
                      400 AI credits a month — and the caps on Free lift with them.
                    </p>
                    <p className="mt-4 text-xs font-medium uppercase tracking-wide text-muted-foreground">
                      What Pro raises and adds
                    </p>
                    <ul className="mt-6 space-y-3">
                      {PRO_INCLUDED.map((item) => (
                        <li key={item.label} className="flex items-start gap-2.5 text-sm">
                          <Check className="mt-0.5 h-4 w-4 shrink-0 text-accent-foreground" aria-hidden="true" />
                          <span className="flex flex-wrap items-center gap-1.5">
                            {item.label}
                            {item.comingSoon && (
                              <Badge variant="secondary" className="text-[10px]">
                                Coming soon
                              </Badge>
                            )}
                          </span>
                        </li>
                      ))}
                    </ul>
                    <div className="mt-6 rounded-lg border border-accent-foreground/30 bg-card/50 p-4">
                      <p className="text-sm font-semibold">Lower platform fee on every campaign you take live</p>
                      <p className="mt-0.5 text-xs text-muted-foreground">
                        Your reduced rate is shown before you publish. Creator commission unchanged.
                      </p>
                    </div>
                    {/*
                      F-0340 class. This pointed at /brand/settings/billing — a
                      route behind the auth guard. /pricing is a PUBLIC page, so
                      its typical reader is logged out and has no account yet:
                      the highest-intent click on the most commercially important
                      page landed them on a login wall for an account they do not
                      have. Registration is the actual next step for that reader;
                      an existing brand upgrading is already inside the app and
                      reaches billing from Settings, not from public pricing.
                    */}
                    <Button size="lg" variant="outline" className="mt-8 w-full" asChild>
                      <Link to="/brand/register">
                        Get started with Pro <ArrowRight className="ml-1.5 h-4 w-4" aria-hidden="true" />
                      </Link>
                    </Button>
                  </CardContent>
                </Card>
              </FadeUp>
            </div>

            <FadeUp delay={0.15}>
              <p className="mt-6 text-center text-sm text-muted-foreground">
                Pro is priced for the AI credits. The reduced fee is a second effect: above
                ~₹1,66,600/month in published campaign budget it covers the subscription on its own.
              </p>
            </FadeUp>
          </div>
        </section>

        {/* Comparison matrix */}
        <section className="border-t border-border/60 py-20">
          <div className="mx-auto max-w-5xl px-6">
            <FadeUp className="text-center">
              <h2 className="text-2xl font-semibold">Compare Free and Pro</h2>
              <p className="mx-auto mt-3 max-w-2xl text-sm text-muted-foreground">
                Grouped the way the plans actually work: what the subscription changes, then what every
                workspace already has.
              </p>
            </FadeUp>
            <FadeUp delay={0.1} className="mt-8">
              <div className="overflow-x-auto rounded-xl border border-border/60">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>Feature</TableHead>
                      <TableHead>Free</TableHead>
                      <TableHead>Pro</TableHead>
                    </TableRow>
                  </TableHeader>
                  {MATRIX_GROUPS.map((group) => (
                    <TableBody key={group.title}>
                      <TableRow className="bg-card/60 hover:bg-card/60">
                        <TableCell colSpan={3} className="whitespace-normal py-3">
                          <span className="text-sm font-semibold">{group.title}</span>
                          {/*
                            The separator is a literal character, not margin.
                            Spacing alone runs the two spans together for a
                            screen reader and for anything reading the DOM as
                            text — including the answer engines this page is
                            written for.
                          */}
                          <span className="ml-2 text-xs text-muted-foreground">
                            &middot; {group.note}
                          </span>
                        </TableCell>
                      </TableRow>
                      {group.rows.map((row) => (
                        <TableRow key={row.feature}>
                          <TableCell className="whitespace-normal font-medium">{row.feature}</TableCell>
                          <TableCell>
                            <MatrixCell value={row.free} />
                          </TableCell>
                          <TableCell>
                            <MatrixCell value={row.pro} />
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  ))}
                </Table>
              </div>
            </FadeUp>
          </div>
        </section>

        {/* Is Pro worth it? — honest framing */}
        <section className="border-t border-border/60 bg-card/50 py-20">
          <div className="mx-auto max-w-3xl px-6">
            <FadeUp>
              <Card>
                <CardContent className="p-8 text-center">
                  <h2 className="text-2xl font-semibold sm:text-3xl">When does Pro make sense?</h2>
                  <div className="mt-6 space-y-4 text-left text-muted-foreground">
                    <p>
                      <span className="font-medium text-foreground">
                        When 100 AI credits a month stop being enough.
                      </span>{' '}
                      That is the honest trigger. If you're briefing, matching and drafting with Meera on
                      every campaign, Free's allowance goes quickly; Pro's 400 credits are what the ₹4,999
                      is for.
                    </p>
                    <p>
                      <span className="font-medium text-foreground">When Free's caps start blocking you.</span>{' '}
                      One seat means nobody else in your team can be in the workspace. Five tracked creators
                      and one analytics deep-dive a month are enough to run occasional campaigns, not enough
                      to vet a shortlist. Pro lifts all three.
                    </p>
                    <p>
                      <span className="font-medium text-foreground">
                        When your published campaign budget is above ₹1,66,600 a month.
                      </span>{' '}
                      Above that, the reduced fee — applied to every campaign budget you publish — covers
                      the subscription by itself, whatever you do with the credits.
                    </p>
                    <p>
                      <span className="font-medium text-foreground">And when none of that is true,</span>{' '}
                      stay on Free. It is not a trial and it does not expire: the contracts, the payment
                      protection, the dispute process and the dashboard are the same ones Pro brands use.
                    </p>
                  </div>
                </CardContent>
              </Card>
            </FadeUp>
          </div>
        </section>

        {/* Creator panel — unaffected by brand tiers */}
        <section className="border-t border-border/60 py-20">
          <div className="mx-auto max-w-3xl px-6">
            <FadeUp>
              <Card className="h-full">
                <CardContent className="p-8">
                  <Badge variant="outline">For creators</Badge>
                  <p className="mt-4 text-3xl font-bold">Free to join</p>
                  <p className="mt-1 text-sm text-muted-foreground">
                    A commission is deducted transparently from your payout only when a deal closes — shown
                    on every invoice.
                  </p>
                  <ul className="mt-6 space-y-3">
                    {CREATOR_INCLUDED.map((line) => (
                      <li key={line} className="flex items-start gap-2.5 text-sm">
                        <Check className="mt-0.5 h-4 w-4 shrink-0 text-accent-foreground" aria-hidden="true" />
                        {line}
                      </li>
                    ))}
                  </ul>
                  <Button size="lg" variant="outline" className="mt-8 w-full" asChild>
                    <Link to="/creator/register">
                      Join free <ArrowRight className="ml-1.5 h-4 w-4" aria-hidden="true" />
                    </Link>
                  </Button>
                </CardContent>
              </Card>
            </FadeUp>
          </div>
        </section>

        {/* Hype pricing note */}
        <section className="border-t border-border/60 bg-card/50 py-20">
          <div className="mx-auto max-w-3xl px-6 text-center">
            <FadeUp>
              <Badge className="gap-1 border-hype-border bg-hype text-hype-foreground hover:bg-hype">
                <Zap className="h-3 w-3" aria-hidden="true" /> Hype Campaigns
              </Badge>
              <h2 className="mt-3 text-2xl font-semibold">One flat rate, no per-deal negotiation</h2>
              <p className="mt-3 text-muted-foreground">
                Brands set a flat per-reel rate and a slot cap upfront. Every accepted slot is funded and protected up front
                at that same flat rate — no back-and-forth on price.
              </p>
              <div className="mt-6">
                <Button variant="outline" asChild>
                  <Link to="/features/hype">See how Hype Campaigns work</Link>
                </Button>
              </div>
            </FadeUp>
          </div>
        </section>

        {/* No hidden fees */}
        <section className="py-20">
          <div className="mx-auto max-w-3xl px-6 text-center">
            <FadeUp>
              <h2 className="text-3xl font-semibold">No hidden fees</h2>
              <p className="mt-3 text-muted-foreground">
                Your platform fee rate is shown on the campaign before you take it live, and every fee
                lands in rupees on an invoice — the brand fee when the campaign goes live, the creator
                commission at payout.
                There's no separate charge for payment protection, contracts, or invoicing — they're part of the
                same transparent flow.
              </p>
            </FadeUp>
          </div>
        </section>

        <FaqSection
          heading="Pricing questions, answered"
          items={FAQS}
          className="border-t border-border/60 bg-card/50 py-20"
        />

        <FunnelCta
          heading="Start on Free — subscribe when you want more AI"
          sub="No card required. Contracts, payment protection and dispute resolution are included from the first deal."
          primary={{ label: 'Start free as a brand', to: '/brand/register' }}
          secondary={{ label: "I'm a creator — joining is free", to: '/creator/register' }}
          reassurances={['No subscription on Free', 'No setup fee', 'Cancel Pro anytime']}
          className="py-20"
        />
      </main>

      <SiteFooter />
      <StickyCta label="Start free" to="/brand/register" note="No subscription on Free" />
      <StickyCtaSpacer />
    </div>
  );
}
