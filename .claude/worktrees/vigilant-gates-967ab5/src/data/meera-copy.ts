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
}

export const MEERA_STAGE_SUBTITLES: Record<string, string> = {
  snapshot: 'Analysing your business…',
  recommend: 'Assembling the plan piece by piece',
  matching: 'Verified creators only — Instagram-checked stats',
  funding: 'Money moves only when you approve',
  live: 'Watch invites turn into acceptances',
}

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
 * Quick-reply chips are now sourced per-turn from
 * `MeeraTurn.suggestedReplies` in `data/meera-mock.ts` — never a static row.
 * This constant is kept only as the empty-state fallback (no active turn).
 */
export const MEERA_QUICK_REPLIES: string[] = []

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

/** Message roles for the mock scripted conversation */
export const MEERA_MESSAGE_ROLE = {
  meera: 'meera',
  brand: 'brand',
} as const

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
