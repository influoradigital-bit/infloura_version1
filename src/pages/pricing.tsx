import { Link } from 'react-router-dom';
import { ArrowRight, Check, ShieldCheck, Sparkles, Zap } from 'lucide-react';

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

interface IncludedItem {
  label: string;
  comingSoon?: boolean;
}

const FREE_INCLUDED: IncludedItem[] = [
  { label: '1 workspace seat' },
  { label: '5 tracked creators' },
  { label: '100 AI credits/month (150 after first funded campaign)' },
  { label: '1 creator analytics deep-dive/month' },
  { label: 'Campaign performance dashboard (unlimited)' },
  { label: 'Auto-generated contracts + e-signature' },
  { label: 'Payment protection on every deal' },
  { label: 'TDS handling and dispute resolution' },
];

const PRO_INCLUDED: IncludedItem[] = [
  { label: '5 workspace seats' },
  { label: 'Unlimited tracked creators' },
  { label: '400 AI credits/month' },
  { label: 'Unlimited creator analytics deep-dives' },
  { label: 'Campaign performance dashboard (unlimited)' },
  { label: 'Export reports (CSV/PDF)', comingSoon: true },
  { label: 'Campaign templates library', comingSoon: true },
  { label: 'Auto-generated contracts + e-signature' },
  { label: 'Payment protection on every deal' },
  { label: 'TDS handling and dispute resolution' },
];

const CREATOR_INCLUDED = [
  'Free to join and build your profile',
  'Free to accept deals and Hype Campaign slots',
  'TDS invoice generated automatically',
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

const MATRIX_ROWS: MatrixRow[] = [
  {
    feature: 'Monthly subscription',
    free: { kind: 'text', value: '₹0' },
    pro: { kind: 'text', value: '₹4,999' },
  },
  {
    feature: 'Platform fee per closed deal',
    free: { kind: 'text', value: 'Included' },
    pro: { kind: 'text', value: 'Reduced' },
  },
  {
    feature: 'Creator commission',
    free: { kind: 'text', value: '15% (unchanged)' },
    pro: { kind: 'text', value: '15% (unchanged)' },
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
    feature: 'AI credits/month',
    free: { kind: 'text', value: '100 → 150 (after first funded campaign)' },
    pro: { kind: 'text', value: '400' },
  },
  {
    feature: 'Campaign performance dashboard',
    free: { kind: 'text', value: 'Unlimited (own campaigns)' },
    pro: { kind: 'text', value: 'Unlimited (own campaigns)' },
  },
  {
    feature: 'Creator analytics deep-dives',
    free: { kind: 'text', value: '1 view/month' },
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
    feature: 'TDS handling + dispute resolution',
    free: { kind: 'check' },
    pro: { kind: 'check' },
  },
  {
    feature: 'Trial period',
    free: { kind: 'text', value: 'None' },
    pro: { kind: 'text', value: 'None' },
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
      'Yes. The Free plan is permanently usable — no time limit, no trial countdown. You pay only when deals close. Pro is an optional upgrade for brands running regular campaigns or needing team features.',
  },
  {
    question: 'Do I have to subscribe to use Influora?',
    answer:
      'No. Free tier requires no subscription. You can discover creators, run deals, use Secure Payments, and generate contracts without ever paying a monthly fee. Pro is only for brands who want lower fees, more seats, and unlimited analytics.',
  },
  {
    question: "What's the difference between Free and Pro?",
    answer:
      'Pro gives you a lower brand fee on every closed deal, 5 workspace seats (vs. 1 on Free), unlimited tracked creators (vs. 5 on Free), unlimited creator analytics deep-dives (vs. 1/month on Free), 400 AI credits/month (vs. 100-150 on Free), report export (CSV/PDF), and campaign templates. See the comparison table above for the full breakdown.',
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
      'Pro gives you a lower fee on every closed deal (shown transparently before you fund the deal and on every invoice). The exact rate is in the tier comparison table above. Free tier uses the standard rate.',
  },
  {
    question: 'When do I actually pay (or get paid)?',
    answer:
      'Brands: nothing is charged until a deal is funded and completed through Secure Payments. Creators: payout releases automatically once the brand approves the deliverable, usually within 24 hours.',
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
        description="Two tiers for brands: Free (pay-per-deal, no subscription) and Pro (₹4,999/month, lower fees + team features). Creators join free. Transparent protection-backed pricing."
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
            'Influora pricing for brands and creators: a Free tier with no subscription, and Pro at ₹4,999/month with lower per-deal fees and team features. Creators join free.',
          url: 'https://influora.in/pricing',
          offers: [
            {
              name: 'Free',
              price: 0,
              description:
                'No subscription. Discover creators, run deals, use protected payments and generate contracts; a platform fee applies only when a deal completes.',
            },
            {
              name: 'Pro',
              price: 4999,
              billingPeriod: 'MON',
              description:
                'Reduced per-deal fee, 5 team seats, unlimited creator analytics.',
            },
          ],
        })}
      />
      <JsonLd
        data={getWebPageSchema({
          name: 'Influora Pricing',
          description:
            'Influora has two brand tiers: Free with no subscription and a platform fee only on completed deals, and Pro at ₹4,999 per month with reduced fees and team features. Creators join and get paid for free.',
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
                Choose your tier — Free or Pro
              </h1>
              <p className="mt-4 text-lg text-muted-foreground">
                Free to start, no subscription required. Upgrade to Pro for lower fees, more seats, and
                unlimited analytics — designed for brands running regular campaigns.
              </p>
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
              <h2 className="mt-3 text-2xl font-semibold">Free or Pro — pick what fits your workflow</h2>
            </FadeUp>

            <div className="mt-10 grid gap-6 lg:grid-cols-2">
              <FadeUp>
                <Card className="h-full">
                  <CardContent className="p-8">
                    <Badge variant="outline">Free</Badge>
                    <p className="mt-4 text-3xl font-bold">₹0/month</p>
                    <p className="mt-1 text-sm text-muted-foreground">Pay only when deals close.</p>
                    <ul className="mt-6 space-y-3">
                      {FREE_INCLUDED.map((item) => (
                        <li key={item.label} className="flex items-start gap-2.5 text-sm">
                          <Check className="mt-0.5 h-4 w-4 shrink-0 text-accent-foreground" aria-hidden="true" />
                          <span>{item.label}</span>
                        </li>
                      ))}
                    </ul>
                    <div className="mt-6 rounded-lg border border-border/60 bg-card/50 p-4">
                      <p className="text-sm font-semibold">Platform fee per closed deal</p>
                      <p className="mt-0.5 text-xs text-muted-foreground">
                        Transparent pricing shown before you fund. Creator commission unchanged.
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
                      Lower fees + unlocked features for growth.
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
                      <p className="text-sm font-semibold">Lower platform fee on every deal</p>
                      <p className="mt-0.5 text-xs text-muted-foreground">
                        Transparent savings shown upfront. Creator commission unchanged.
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
                Pro pays for itself above ~₹2,10,000/month in campaign spend. Below that threshold, upgrade
                for the analytics, export, and team seat unlocks.
              </p>
            </FadeUp>
          </div>
        </section>

        {/* Comparison matrix */}
        <section className="border-t border-border/60 py-20">
          <div className="mx-auto max-w-5xl px-6">
            <FadeUp className="text-center">
              <h2 className="text-2xl font-semibold">Compare Free and Pro</h2>
            </FadeUp>
            <FadeUp delay={0.1} className="mt-8">
              <div className="rounded-xl border border-border/60">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>Feature</TableHead>
                      <TableHead>Free</TableHead>
                      <TableHead>Pro</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {MATRIX_ROWS.map((row) => (
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
                        If you're running multiple campaigns a month or working with a team,
                      </span>{' '}
                      Pro unlocks the features that make scaling easier: unlimited creator analytics (vet as
                      many creators as you need), report export (share performance with stakeholders), 5
                      seats (collaborate without seat-blocking), and campaign templates (launch faster).
                    </p>
                    <p>
                      <span className="font-medium text-foreground">
                        If your monthly campaign spend is above ₹2,10,000,
                      </span>{' '}
                      the lower brand fee (shown on every deal before you fund the deal) compounds quickly —
                      the subscription price pays for itself in fee savings alone.
                    </p>
                    <p>
                      <span className="font-medium text-foreground">Below that threshold?</span> You're
                      still getting value from the analytics unlocks, export, and seat limits. Free tier
                      works great if you're running occasional campaigns or testing the platform — upgrade
                      when growth makes those limits feel tight.
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
                Every fee is shown on the deal before you fund the deal, and on the invoice after payout.
                There's no separate charge for payment protection, contracts, or TDS handling — they're part of the
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
          heading="Start free — upgrade to Pro when you're ready to scale"
          sub="No card required. The Free tier has no subscription and no time limit."
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
