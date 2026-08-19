import {
  BadgeCheck,
  FileSignature,
  MessageSquareText,
  Search,
  ShieldCheck,
  UploadCloud,
  Wallet,
  Zap,
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';

/**
 * The canonical six-step flow, for brands and for creators.
 *
 * Why this file exists: this copy was written, approved and shipped — to logged-out visitors
 * only. It lived as a private `const STEPS` inside `how-it-works-brands.tsx` and
 * `how-it-works-creators.tsx`, so the in-app pages that a confused first-time user actually
 * needs (`/brand/how-it-works`, `/creator/how-it-works`) had no way to reach it without
 * copy-pasting. A second copy is how the marketing page and the in-app page start disagreeing
 * about what the product does — and only one of them is the version the SEO `HowTo` schema is
 * built from, so the drift would be invisible on the surface that matters commercially.
 *
 * Both marketing pages and both in-app pages now render from these arrays, and the `HowTo`
 * JSON-LD is still derived from the same objects the page renders. Content per
 * `wiki/website/content-map.md` §1.3 (brands) and §1.4 (creators) — change it there first.
 */
export interface HowItWorksStep {
  icon: LucideIcon;
  /** Zero-padded ordinal, rendered as the step chip. */
  step: string;
  title: string;
  body: string;
}

/** Content per wiki/website/content-map.md §1.3. */
export const BRAND_STEPS: readonly HowItWorksStep[] = [
  {
    icon: Search,
    step: '01',
    title: 'Create your campaign',
    body: 'Set your goals (reach, engagement, sales), choose a campaign type — one-off collab, Hype Campaign, or seasonal — and define deliverables (reel, carousel, story, YouTube integration).',
  },
  {
    icon: Search,
    step: '02',
    title: 'Discover creators',
    body: 'Search by niche, follower range, city, and engagement rate. Every profile is Instagram-verified with a published rate card and visible past collaborations.',
  },
  {
    icon: MessageSquareText,
    step: '03',
    title: 'Negotiate in the Deal Room',
    body: 'Chat, send a proposal, and handle counter-offers in one thread — no juggling WhatsApp and DMs.',
  },
  {
    icon: FileSignature,
    step: '04',
    title: 'Sign the contract, fund the deal',
    body: 'An auto-generated contract spells out usage rights, exclusivity, and revision limits. Both sides e-sign, then the deal amount locks in a protected balance.',
  },
  {
    icon: BadgeCheck,
    step: '05',
    title: 'Creator delivers, you approve',
    body: 'The creator uploads the deliverable in the Deal Room. Request revisions within the contract limit, or approve it.',
  },
  {
    icon: ShieldCheck,
    step: '06',
    title: 'The payment releases, creator posts',
    body: 'On approval, the payment releases to the creator automatically. They post within the campaign window and you track performance from your dashboard.',
  },
] as const;

/** Content per wiki/website/content-map.md §1.4. */
export const CREATOR_STEPS: readonly HowItWorksStep[] = [
  {
    icon: Search,
    step: '01',
    title: 'Create your profile, connect Instagram',
    body: 'Link your verified Instagram account, set your rate card (per reel, per carousel, per story), and add past work to your portfolio.',
  },
  {
    icon: Zap,
    step: '02',
    title: 'Get discovered, or join a Hype Campaign',
    body: 'Brands search and find you directly, or browse live Hype Campaigns and accept a slot with one tap — no negotiation required.',
  },
  {
    icon: MessageSquareText,
    step: '03',
    title: 'Negotiate in the Deal Room',
    body: 'A brand sends a proposal. Chat, negotiate the rate, and counter-offer — all in one thread, no lost DMs.',
  },
  {
    icon: FileSignature,
    step: '04',
    title: 'Accept the contract — payment already secured',
    body: 'E-sign the generated contract. The funds are already locked and protected before you start work, so payment is guaranteed.',
  },
  {
    icon: UploadCloud,
    step: '05',
    title: 'Create and submit your deliverable',
    body: 'Upload your reel, carousel, or video. The brand reviews it and may request revisions within the contract\'s limit.',
  },
  {
    icon: Wallet,
    step: '06',
    title: 'Get paid, then post',
    body: 'Once the brand approves, the payment releases to you automatically — TDS handled, invoice generated. Post within the campaign window and see the payment land, usually within 24 hours.',
  },
] as const;
