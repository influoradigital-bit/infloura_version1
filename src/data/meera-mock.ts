/**
 * Mock-first data for the Meera workspace (handoff §7 build order item 4).
 * No backend AI endpoints exist yet — everything here is demo data consumed
 * by the stage components and useMeeraStage glue.
 */

export interface MockBrandSnapshot {
  name: string
  siteUrl: string
  logoInitials: string
  brandColorHex: string
  products: Array<{ name: string; price: number; imageEmoji: string }>
}

export const MOCK_BRAND_SNAPSHOT: MockBrandSnapshot = {
  name: 'Kavala Skincare',
  siteUrl: 'kavalaskincare.com',
  logoInitials: 'KS',
  brandColorHex: '#E8927C',
  products: [
    { name: 'Vitamin C Serum', price: 899, imageEmoji: '🧴' },
    { name: 'Clay Mask', price: 649, imageEmoji: '🫙' },
    { name: 'SPF 50 Gel', price: 549, imageEmoji: '☀️' },
  ],
}

export interface MockCampaignPlan {
  type: string
  creatorCount: number
  windowHours: number
  reach: number
  pool: number
  feePercent: number
}

export const MOCK_CAMPAIGN_PLAN: MockCampaignPlan = {
  type: 'Product Seeding + Reels',
  creatorCount: 15,
  windowHours: 72,
  reach: 420000,
  pool: 15000,
  feePercent: 15,
}

export function computeFee(pool: number, feePercent: number) {
  const fee = Math.round(pool * (feePercent / 100))
  return { pool, fee, total: pool + fee }
}

export interface MockCreator {
  id: string
  name: string
  handle: string
  city: string
  niche: string
  followers: number
  avatarEmoji: string
  verified: boolean
}

export const MOCK_CREATORS: MockCreator[] = [
  { id: 'c1', name: 'Ananya Iyer', handle: '@ananya.glows', city: 'Mumbai', niche: 'Skincare', followers: 84000, avatarEmoji: '💁‍♀️', verified: true },
  { id: 'c2', name: 'Rhea Kapoor', handle: '@rheastyle', city: 'Mumbai', niche: 'Beauty', followers: 62000, avatarEmoji: '💄', verified: true },
  { id: 'c3', name: 'Zara Sheikh', handle: '@zara.skin', city: 'Mumbai', niche: 'Skincare', followers: 133000, avatarEmoji: '✨', verified: true },
  { id: 'c4', name: 'Kabir Malhotra', handle: '@kabir.grooms', city: 'Mumbai', niche: 'Grooming', followers: 45000, avatarEmoji: '🧔', verified: true },
  { id: 'c5', name: 'Naina Desai', handle: '@naina.beauty', city: 'Mumbai', niche: 'Skincare', followers: 97000, avatarEmoji: '🌸', verified: true },
  { id: 'c6', name: 'Ishita Rao', handle: '@ishita.glow', city: 'Mumbai', niche: 'Beauty', followers: 51000, avatarEmoji: '🌟', verified: true },
  { id: 'c7', name: 'Meher Chopra', handle: '@meher.mua', city: 'Mumbai', niche: 'Beauty', followers: 76000, avatarEmoji: '💅', verified: true },
  { id: 'c8', name: 'Tara Nair', handle: '@tara.skincare', city: 'Mumbai', niche: 'Skincare', followers: 112000, avatarEmoji: '🧖‍♀️', verified: true },
]

export const MOCK_TOTAL_FOUND = 38
export const MOCK_TOP_MATCHED = 15

export interface MockPayoutEntry {
  id: string
  creatorHandle: string
  amount: number
  releasedAt: string
}

export const MOCK_PAYOUT_LEDGER: MockPayoutEntry[] = [
  { id: 'p1', creatorHandle: '@ananya.glows', amount: 1000, releasedAt: '2 min ago' },
  { id: 'p2', creatorHandle: '@zara.skin', amount: 1000, releasedAt: '1 hr ago' },
]

export const MOCK_LIVE_STATS = {
  invitesSent: 15,
  slotsAccepted: 8,
  slotsTotal: 15,
}

/** Stage 6 mock — mirrors the locked `GetCampaignPerformanceResult` shape
 * (roi ratio, responseRate 0..1, avgCreatorScore 0..100) so the `!live`
 * branch of `StagePerformance` exercises the same fields the real payload
 * carries. No narrative string here — the narrative is Meera's own
 * chat-bubble line (`MEERA_CONVERSATION_SCRIPT`), never a card field. */
export interface MockCampaignPerformance {
  roi: number
  responseRate: number
  avgCreatorScore: number
}

export const MOCK_CAMPAIGN_PERFORMANCE: MockCampaignPerformance = {
  roi: 2.4,
  responseRate: 0.72,
  avgCreatorScore: 84,
}

/**
 * Turn-based mock conversation engine for the left panel.
 *
 * DESIGN — why turns, not a flat replaying array:
 * The old `MOCK_CONVERSATION` was a flat list that auto-played end to end on
 * mount, alternating meera/brand rows on a timer. That can't represent a real
 * back-and-forth because there's no seam for the user's actual input to slot
 * in — the "brand" rows were pre-written strings, not something typed.
 *
 * A `MeeraTurn` is one full round: Meera says something and waits
 * (`meeraResponses`, played in order on entry), the user replies (typed or a
 * suggested chip — `suggestedReplies`), and only THEN does the turn resolve:
 * an optional thinking beat (`showThinking`) plays, the stage-driving
 * function call fires (`triggersStage`), and the engine advances to the next
 * turn's Meera lines. The turn cursor only moves forward on a user send —
 * nothing plays on a timer except the thinking-step micro-animation inside a
 * single turn's resolution. `MeeraChatPanel` owns the cursor; this file only
 * owns the script.
 */
export interface MeeraTurn {
  id: string
  /** Meera's line(s) for this turn, revealed in order when the turn opens. */
  meeraResponses: string[]
  /** Contextual quick-reply chips offered while this turn is awaiting input. */
  suggestedReplies: string[]
  /** Thinking/typing beat shown after the user replies, before resolution. */
  showThinking?: 'snapshot' | 'recommend' | 'matching'
  /** Function call fired (and Living Canvas stage advanced) on resolution. */
  triggersStage?: 'snapshot' | 'recommend' | 'matching' | 'funding' | 'live'
  /** Shown if the user sends free text that isn't a URL yet (turn 0 only). */
  nudge?: string
}

/**
 * IMPORTANT — trigger timing: `showThinking`/`triggersStage` on turn N fire
 * the moment the USER sends their reply to turn N (i.e. on exit from turn
 * N), immediately before turn N+1's `meeraResponses` are revealed. This
 * means turn N's payoff (its own `meeraResponses`) already reflects the
 * result — e.g. turn 0 asks for the site, and it's turn 0's own exit
 * (site sent) that fires `analyze_site`; turn 1 then opens already having
 * "read the site" in its lines.
 */
export const MEERA_CONVERSATION_SCRIPT: MeeraTurn[] = [
  {
    id: 't0-opening',
    meeraResponses: ["Hey — I'm Meera, your AI cofounder. Give me your website and I'll take it from there."],
    suggestedReplies: ['kavalaskincare.com'],
    nudge: "Drop your website URL and I'll start there — try kavalaskincare.com.",
    showThinking: 'snapshot',
    triggersStage: 'snapshot',
  },
  {
    id: 't1-snapshot',
    meeraResponses: [
      "Got it. Kavala Skincare — clean, minimal, vitamin C serum is your hero product. Let's build a campaign around it.",
    ],
    suggestedReplies: ['What would that cost?', 'Make it Mumbai only'],
    showThinking: 'recommend',
    triggersStage: 'recommend',
  },
  {
    id: 't2-recommend',
    meeraResponses: ["Here's the plan: 15 creators, a 72-hour window, and a transparent fee breakdown. Nothing hidden."],
    suggestedReplies: ['Show me the creators'],
    showThinking: 'matching',
    triggersStage: 'matching',
  },
  {
    id: 't3-matching',
    meeraResponses: ['38 found, top 15 ranked by engagement — all Instagram-verified. Ready to fund?'],
    suggestedReplies: ["I'm ready to fund it"],
    triggersStage: 'funding',
  },
  {
    id: 't4-funding',
    meeraResponses: ['Locking your funds into escrow now.'],
    suggestedReplies: [],
  },
]

/** Legacy alias kept for anything still importing the old flat-array name. */
export type MockScriptedMessage = MeeraTurn
