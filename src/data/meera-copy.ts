/**
 * Meera AI Cofounder — all UI strings, brand-voice microcopy, and mobile labels.
 * Tejas voice rules (spec §3): sentence case, contractions, verb-first CTAs.
 * No "!", no "please", no "successfully". Nothing hardcoded downstream — every
 * component pulls strings from here.
 */

export const MEERA_IDENTITY = {
  name: 'Meera',
  subtitle: 'Your AI Cofounder',
  onlineLabel: 'Online',
  firstInIndiaBadge: "First AI-first influencer platform in India",
}

export const MEERA_CTAS = {
  fundAndGoLive: (amount: string) => `Fund & go live — ${amount}`,
  payAmount: (amount: string) => `Pay ${amount}`,
  approveAndRelease: 'Approve & release',
  viewCampaign: 'View campaign',
  send: 'Send',
}

/** T7 — release-on-approval copy. Used once at the lock, then per payout. */
export const MEERA_TRUST_COPY = {
  lockCaption: (amount: string) => `${amount} secured. Released only on your approval.`,
  releaseNote: 'Money moves only when you approve.',
  escrowGuarantee: 'Guaranteed.',
}

/** T1 — persistent escrow pill states. Amount leads ("₹17,250 Secured") — the
 * money is the hero of the pill, spec §3 T1 / Swapnil review finding #2. */
export const MEERA_ESCROW_PILL_LABEL = {
  unfunded: 'Unfunded',
  securing: 'Securing…',
  secured: (amount: string) => `${amount} Secured`,
  releasing: (amount: string) => `Releasing ${amount}`,
}

/** PayButton state labels (Priya M3 — no hardcoded strings in components). */
export const MEERA_PAY_BUTTON_LABEL = {
  loading: 'Securing…',
  success: 'Secured',
}

/** T3 — "Meera shows her work" streaming step log */
export const MEERA_THINKING_STEPS = {
  matching: [
    'Scanning 300+ creators',
    'Filtering by city and niche',
    'Ranking by engagement',
    'Done — 38 found',
  ],
  recommend: [
    'Reading your site and catalogue',
    'Sizing the right budget band',
    'Drafting the campaign brief',
  ],
  snapshot: [
    'Opening your website',
    'Reading your product pages',
    'Pulling your brand colours',
  ],
}

export const MEERA_STAGE_TITLES: Record<string, string> = {
  snapshot: 'Your business, at a glance',
  recommend: 'Campaign, built for you',
  matching: 'Matching creators',
  funding: 'Fund the campaign',
  live: 'Campaign is live',
  performance: 'How it performed',
}

export const MEERA_STAGE_SUBTITLES: Record<string, string> = {
  snapshot: 'Analysing your business…',
  recommend: 'Assembling the plan piece by piece',
  matching: 'Verified creators only — Instagram-checked stats',
  funding: 'Money moves only when you approve',
  live: 'Watch invites turn into acceptances',
  performance: 'Verified numbers, straight from the platform',
}

/**
 * Live-mode snapshot IDLE copy. The snapshot stage is the Living Canvas's
 * LANDING stage — it only leaves once a later stage tool succeeds. So when
 * nothing is being analysed (brand hasn't shared a site) or every tool call
 * fails, the canvas must READ as ready-and-waiting, not as a stuck spinner.
 * This replaces the old perpetual "Reading your site…" loader in those cases.
 */
export const MEERA_SNAPSHOT_IDLE = {
  title: 'Your snapshot will appear here',
  body: 'Share your website link in the chat and I’ll pull your brand, products and colours into this space.',
  stalledBody: 'This is taking longer than usual — I’ll fill in your brand, products and colours here as soon as your site’s ready.',
} as const

/** Chat composer + empty/paused states */
export const MEERA_COMPOSER = {
  placeholder: 'Tell Meera what you want to promote…',
  placeholderPaused: "Meera's back on the 1st — or fund a campaign to unlock her now",
  disabledHint: 'Fund your first campaign to keep chatting',
}

/** Credit paywall (soft empty state, invitation not apology) */
export const MEERA_PAYWALL = {
  title: "Fund your first campaign to unlock me fully",
  body: "— or I'm back on the 1st.",
  cta: 'Fund a campaign',
}

/**
 * R6 — tap-to-fill starter templates shown in LIVE mode when the chat is empty.
 * Unlike quick-reply chips (which send instantly), tapping one of these only
 * PRE-FILLS the composer — the brand edits the [bracketed] blanks with their own
 * details, then sends. `[…]` marks the parts the user swaps in.
 */
export const MEERA_STARTER_TEMPLATES: string[] = [
  'My store is [paste your product link] — build me a plan',
  'Set up a review campaign for [product] priced at ₹[price] with [5] creators',
  'Find [kitchen and home] creators in [Mumbai] for a launch',
  "What budget do I need to promote [product] at ₹[price]?",
]

/** Fee breakdown labels (T5) */
export const MEERA_FEE_LABELS = {
  pool: 'Creator pool',
  fee: 'Platform fee (15%)',
  total: 'Total',
}

/** Stat pair labels (T6) */
export const MEERA_STAT_LABELS = {
  reach: 'Estimated reach',
  budget: 'Campaign budget',
  creators: 'Creators matched',
  invitesSent: 'Invites sent',
  slotsAccepted: 'Slots accepted',
  roi: 'Return on spend',
  responseRate: 'Response rate',
  avgCreatorScore: 'Avg CreatorScore',
}

/** Stage 6 — performance card copy (2.4). Numbers only on the card; the
 * one-sentence narrative is Meera's own chat-bubble turn, never duplicated
 * here (Priya/Ash Q2 ruling — card=numbers, bubble=qualitative voice). */
export const MEERA_PERFORMANCE_COPY = {
  roiUnavailable: 'Not enough data yet',
  seeFullBreakdown: 'See full breakdown',
}

/** Payout ledger (T9) */
export const MEERA_LEDGER = {
  title: 'Payout ledger',
  releasedLabel: (creator: string, amount: string) => `${creator} — ${amount} released`,
  emptyState: 'No payouts released yet',
}

/** Mobile-only labels (bottom tab, sheet) */
export const MEERA_MOBILE = {
  viewCampaignTab: 'View campaign',
  sheetTitle: 'Campaign',
  closeSheet: 'Close',
}

/**
 * Voice & Living Presence (spec §5A). Every voice failure path routes to the
 * text UI — this copy is what carries that handoff so it never reads as a
 * dead end.
 */
export const MEERA_VOICE_COPY = {
  /** STT failure — permission denied, onerror, no-speech, unsupported browser. */
  sttFallback: 'Didn’t catch that — type it instead?',
  micIdleLabel: 'Speak to Meera',
  micListeningLabel: 'Listening — tap to stop',
  micTranscribingLabel: 'Cleaning up what you said…',
  voiceToggleOnLabel: 'Voice replies on',
  voiceToggleOffLabel: 'Voice replies off',
  voiceUnavailableHint: 'Voice isn’t supported in this browser — text still works.',
}
