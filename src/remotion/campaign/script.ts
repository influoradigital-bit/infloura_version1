/**
 * Script for "How to create a campaign" — the brand-side product demo.
 *
 * Marketing direction: Tejas (CMO). Copy: Ishaan. Every on-screen field label
 * is the literal string from the real form, cited per scene below; only the
 * demo brand ("Bloomveda") and its sample values are invented.
 *
 * Timing note: `seconds` is authored, `frames` is derived at 30fps in
 * `scene-meta.ts`. Keep edits in seconds — it is what the voiceover is read to.
 */

export interface CampaignScene {
  id: string;
  /** Uppercase kicker above the caption. */
  kicker: string;
  /** On-screen caption, max six words. */
  caption: string;
  /** Voiceover line, read at ~150 wpm. */
  voice: string;
  seconds: number;
}

export const CAMPAIGN_SCRIPT: CampaignScene[] = [
  {
    id: 'hook',
    kicker: 'Influora for brands',
    caption: 'Create a campaign',
    voice: 'Every campaign on Influora starts here, on the New Campaign screen.',
    seconds: 5,
  },
  {
    id: 'type',
    kicker: 'Choose a type',
    caption: 'Open, Direct or Hype',
    voice:
      'First, pick a campaign type. An Open Campaign posts your brief publicly, so creators apply to you.',
    seconds: 9,
  },
  {
    id: 'basics-a',
    kicker: 'Step 1 — Basics',
    caption: 'Campaign Title and Description',
    voice:
      'On the Basics step, give your campaign a Title and a short Description: what you need, and who you are looking for.',
    seconds: 8.5,
  },
  {
    id: 'basics-b',
    kicker: 'Step 1 — Basics',
    caption: 'End Brand and Objectives',
    voice:
      'Fill in the End Brand Name and Category, then choose your Campaign Objectives, like Product Launch or Drive Sales.',
    seconds: 8.5,
  },
  {
    id: 'content',
    kicker: 'Step 2 — Content',
    caption: 'Platforms and content types',
    voice:
      'Move to Content. Pick your Target Platforms, and the Content Types you want, from Reels to Static Posts.',
    seconds: 9,
  },
  {
    id: 'budget',
    kicker: 'Step 3 — Budget',
    caption: 'Timeline, budget, collaborators',
    voice:
      'In Budget, set your Start and End Date, drag the Budget Range slider, and set your Maximum Collaborators.',
    seconds: 8,
  },
  {
    id: 'requirements',
    kicker: 'Step 4 — Requirements',
    caption: 'Rules, hashtags and audience',
    voice:
      'On Requirements, list what creators must include, add your Campaign Hashtags, and describe your Target Audience.',
    seconds: 8,
  },
  {
    id: 'review',
    kicker: 'Step 5 — Review',
    caption: 'Review and suggested creators',
    voice:
      'The Review step shows everything at a glance, and suggests creators who match your campaign.',
    seconds: 7,
  },
  {
    id: 'publish',
    kicker: 'Step 5 — Review',
    caption: 'Publish your campaign',
    /**
     * Deliberately scoped to what publishing actually does. Publishing creates
     * the campaign and opens it to applications — it moves no money. Funds are
     * secured later, at the deal stage, so that promise lives in the outro.
     */
    voice: 'Hit Publish Campaign, and your brief goes live for creators to apply to.',
    seconds: 7,
  },
  {
    id: 'outro',
    kicker: 'Secure Payments',
    caption: 'Create yours today',
    voice:
      'When you hire, Secure Payments holds the funds until the work is delivered. Create your campaign on Influora today.',
    seconds: 5.5,
  },
];
