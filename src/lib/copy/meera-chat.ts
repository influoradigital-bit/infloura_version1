import type { CreatorKnowledgeTopic, CreatorToolName } from '@/lib/meera-api';

/**
 * MEERA-CHAT-DESIGN-SPEC.md — bilingual (en/hi) copy for the creator Meera chat redesign
 * (shell polish, the work trail, and the "Meera is on it" desk).
 *
 * Pattern: every user-facing string is a `{ en; hi }` pair, and `pickLang(language, pair)`
 * (below) is the one place that decides which half to read — same shape the rest of this
 * codebase's language-aware creator copy already uses (`onboardingGreeting` in
 * MeeraCopilotChat.tsx switches on `language.startsWith('hi')`). No string in this file is
 * the word "escrow" — Meera's creator-facing copy never uses it (see the trust line below,
 * which exists specifically to say plainly that Meera never moves money).
 */

export interface BilingualText {
  en: string;
  hi: string;
}

/** `language` is BCP-47-ish (e.g. `hi-IN`, `en-IN`) — only the leading subtag matters. */
export function pickLang(language: string, pair: BilingualText): string {
  return language.startsWith('hi') ? pair.hi : pair.en;
}

// ---------------------------------------------------------------------------
// Part 0 — shell polish
// ---------------------------------------------------------------------------

export const HEADER_STATUS_ONLINE: BilingualText = {
  en: 'Online · knows your deals, earnings and Instagram stats',
  hi: 'ऑनलाइन · आपकी डील्स, कमाई और Instagram आँकड़े जानती है',
};

export const HEADER_STATUS_WORKING: BilingualText = {
  en: 'Working on it…',
  hi: 'इस पर काम हो रहा है…',
};

export const HEADER_STATUS_LISTENING: BilingualText = {
  en: 'Listening…',
  hi: 'सुन रही हूं…',
};

export const HEADER_STATUS_SPEAKING: BilingualText = {
  en: 'Speaking…',
  hi: 'बोल रही हूं…',
};

export const TRUST_LINE: BilingualText = {
  en: 'Meera reads only your own Influora data and never moves money.',
  hi: 'Meera सिर्फ़ आपका अपना Influora डेटा पढ़ती है और कभी पैसे नहीं भेजती।',
};

/** Round 2 QA — the composer placeholder was hardcoded English; now bilingual. */
export const COMPOSER_PLACEHOLDER: BilingualText = {
  en: 'Ask Meera about your deals, earnings, or metrics…',
  hi: 'Meera से अपनी डील्स, कमाई या मेट्रिक्स के बारे में पूछें…',
};

// ---------------------------------------------------------------------------
// Part A — work trail
// ---------------------------------------------------------------------------

export const TRAIL_UNDERSTANDING: BilingualText = {
  en: 'Understanding your question…',
  hi: 'आपका सवाल समझा जा रहा है…',
};

export interface ToolTrailLabelSet {
  running: BilingualText;
  done: BilingualText;
  failed: BilingualText;
}

/** SPEC §Part A's table, one entry per `CREATOR_TOOL_NAMES` — a missing tool here is a
 *  compile error (see the `Record<CreatorToolName, ...>` below), same discipline as
 *  MeeraCopilotChat.tsx's own `TOOL_PENDING_LABELS`. */
export const TOOL_TRAIL_LABELS: Record<CreatorToolName, ToolTrailLabelSet> = {
  get_my_deals: {
    running: { en: 'Reading your deals…', hi: 'आपकी डील्स देखी जा रही हैं…' },
    done: { en: 'Read your deals', hi: 'आपकी डील्स देख लीं' },
    failed: { en: "Couldn't open your deals", hi: 'आपकी डील्स नहीं खुल पाईं' },
  },
  get_brief: {
    running: { en: 'Opening the brief…', hi: 'ब्रीफ़ खोली जा रही है…' },
    done: { en: 'Read the brief', hi: 'ब्रीफ़ पढ़ ली' },
    failed: { en: "Couldn't open the brief", hi: 'ब्रीफ़ नहीं खुल पाई' },
  },
  estimate_my_rate: {
    running: { en: 'Working out your rate…', hi: 'आपकी दर निकाली जा रही है…' },
    done: { en: 'Worked out your rate', hi: 'आपकी दर निकाल ली' },
    failed: { en: "Couldn't work out your rate", hi: 'आपकी दर नहीं निकाल पाई' },
  },
  get_my_metrics: {
    running: { en: 'Checking your Instagram numbers…', hi: 'आपके Instagram आँकड़े देखे जा रहे हैं…' },
    done: { en: 'Checked your Instagram numbers', hi: 'आपके Instagram आँकड़े देख लिए' },
    failed: { en: "Couldn't load your numbers", hi: 'आपके आँकड़े लोड नहीं हो पाए' },
  },
  check_deal_risks: {
    running: { en: 'Checking the deal for risks…', hi: 'डील में जोखिम जांचे जा रहे हैं…' },
    done: { en: 'Checked the deal for risks', hi: 'डील के जोखिम जांच लिए' },
    failed: { en: "Couldn't check the deal", hi: 'डील जांच नहीं पाई' },
  },
  draft_reply: {
    running: { en: 'Drafting a reply…', hi: 'जवाब का मसौदा बनाया जा रहा है…' },
    done: { en: 'Drafted a reply', hi: 'जवाब का मसौदा बना लिया' },
    failed: { en: "Couldn't draft the reply", hi: 'जवाब का मसौदा नहीं बन पाया' },
  },

  // release/0924: the two planner tools (fix/plan-my-week-gaps) landed after this table was
  // written on feat/meera-creator-design, so the Record<CreatorToolName, ...> above had no
  // labels for them and the build failed — exactly what that type is there to catch.
  get_todays_topics: {
    running: { en: "Checking today's topics…", hi: 'आज के विषय देखे जा रहे हैं…' },
    done: { en: "Checked today's topics", hi: 'आज के विषय देख लिए' },
    failed: { en: "Couldn't load today's topics", hi: 'आज के विषय लोड नहीं हो पाए' },
  },
  plan_my_week: {
    running: { en: 'Planning your week…', hi: 'आपका हफ़्ता प्लान किया जा रहा है…' },
    done: { en: 'Planned your week', hi: 'आपका हफ़्ता प्लान कर लिया' },
    failed: { en: "Couldn't plan your week", hi: 'आपका हफ़्ता प्लान नहीं हो पाया' },
  },
};

/**
 * `get_creator_knowledge` is a LOCAL tool (not in `CREATOR_TOOL_NAMES`, so not in the table
 * above): Meera reading Influora's own notes on one topic. Its step names the topic, e.g.
 * "Checking Influora's notes on audio". Typed `Record<CreatorKnowledgeTopic, ...>` so a topic
 * added to `CREATOR_KNOWLEDGE_TOPICS` without a name here is a compile error. The phrase is
 * what the English label says after "notes on", and in Hindi what comes before "वाले नोट्स".
 */
const KNOWLEDGE_TOPIC_PHRASES: Record<CreatorKnowledgeTopic, BilingualText> = {
  audio: { en: 'audio', hi: 'ऑडियो' },
  moving_between_spots: { en: 'moving between spots', hi: 'एक जगह से दूसरी जगह जाने' },
  delivery_examples: { en: 'delivery examples', hi: 'डिलीवरी के उदाहरणों' },
  // Shoot guide spec v2 (2026-09-26). The hi phrases below are PENDING REVIEW.
  shot_planning: { en: 'shot planning', hi: 'शॉट प्लानिंग' },
  framing_beauty_grwm: { en: 'framing for beauty and GRWM', hi: 'ब्यूटी और GRWM फ़्रेमिंग' },
  framing_fashion: { en: 'framing for fashion', hi: 'फ़ैशन फ़्रेमिंग' },
  framing_food_cooking: { en: 'framing for food and cooking', hi: 'फ़ूड और कुकिंग फ़्रेमिंग' },
  framing_fitness: { en: 'framing for fitness', hi: 'फ़िटनेस फ़्रेमिंग' },
  framing_tech_product: { en: 'framing for tech and products', hi: 'टेक और प्रोडक्ट फ़्रेमिंग' },
  framing_screen_demo: { en: 'framing for screen demos', hi: 'स्क्रीन डेमो फ़्रेमिंग' },
  framing_finance_education: { en: 'framing for finance and education', hi: 'फ़ाइनेंस और एजुकेशन फ़्रेमिंग' },
  framing_travel_vlog: { en: 'framing for travel and vlogs', hi: 'ट्रैवल और व्लॉग फ़्रेमिंग' },
  framing_comedy_lifestyle: { en: 'framing for comedy and lifestyle', hi: 'कॉमेडी और लाइफ़स्टाइल फ़्रेमिंग' },
  framing_groups: { en: 'framing for groups', hi: 'ग्रुप फ़्रेमिंग' },
  framing_motivational: { en: 'framing for motivational talks', hi: 'मोटिवेशनल टॉक फ़्रेमिंग' },
};

function knowledgeTopicPhrase(topic: string | undefined): BilingualText | undefined {
  if (!topic || !Object.prototype.hasOwnProperty.call(KNOWLEDGE_TOPIC_PHRASES, topic)) return undefined;
  return KNOWLEDGE_TOPIC_PHRASES[topic as CreatorKnowledgeTopic];
}

/**
 * The work-trail line for one `get_creator_knowledge` call. A missing or unrecognised topic
 * (an older build, or the tool's own `unknown_topic` error) falls back to the plain "Influora's
 * notes" wording rather than showing the raw topic key.
 */
export function creatorKnowledgeTrailLabel(
  topic: string | undefined,
  state: keyof ToolTrailLabelSet,
  language: string,
): string {
  const phrase = knowledgeTopicPhrase(topic);
  if (language.startsWith('hi')) {
    const notes = phrase ? `Influora के ${phrase.hi} वाले नोट्स` : 'Influora के नोट्स';
    if (state === 'running') return `${notes} देखे जा रहे हैं…`;
    if (state === 'done') return `${notes} देख लिए`;
    return `${notes} नहीं खुल पाए`;
  }
  const notes = phrase ? `Influora's notes on ${phrase.en}` : "Influora's notes";
  if (state === 'running') return `Checking ${notes}…`;
  if (state === 'done') return `Checked ${notes}`;
  return `Couldn't open ${notes}`;
}

export const TRAIL_SHOW: BilingualText = { en: 'Show', hi: 'दिखाएं' };
export const TRAIL_HIDE: BilingualText = { en: 'Hide', hi: 'छुपाएं' };

/** "Meera did 3 things" / "Meera did 1 thing" — count is always a real tool-step count,
 *  never invented (see MeeraWorkTrail.tsx, which only ever counts real `toolResults`). */
export function trailSummary(language: string, count: number): string {
  if (language.startsWith('hi')) {
    return `Meera ने ${count} काम ${count === 1 ? 'किया' : 'किए'}`;
  }
  return `Meera did ${count} thing${count === 1 ? '' : 's'}`;
}

// ---------------------------------------------------------------------------
// Part B — "Meera is on it" desk
// ---------------------------------------------------------------------------

export const DESK_HEADER: BilingualText = {
  en: "Here's what I'm watching for you",
  hi: 'यह है जो मैं आपके लिए देख रही हूं',
};

/**
 * Round 2 QA — relabelled to match the dashboard's own meaning (`creator-dashboard.tsx`'s
 * `pendingTotal`, "N item(s) need your attention"), now that the tile's count is the SAME
 * 3-figure sum the dashboard computes (`computeDealAttentionCounts` + unsigned contracts) via
 * `@/lib/creator-needs-attention` — not just unread + unsigned as it was in round 1.
 */
export const DESK_TILE_ATTENTION_SINGULAR: BilingualText = {
  en: 'item needs your attention',
  hi: 'चीज़ पर आपका ध्यान चाहिए',
};

export const DESK_TILE_ATTENTION_PLURAL: BilingualText = {
  en: 'items need your attention',
  hi: 'चीज़ों पर आपका ध्यान चाहिए',
};

/** Shown instead of a bare "0" when the real count is zero. */
export const DESK_TILE_ALL_CAUGHT_UP: BilingualText = {
  en: 'All caught up',
  hi: 'सब कुछ पूरा है',
};

/** Singular/plural label for the "needs your attention" tile — mirrors the dashboard's own
 *  `${n} item${n === 1 ? '' : 's'} need your attention` singular handling. */
export function attentionLabel(language: string, count: number): string {
  return pickLang(language, count === 1 ? DESK_TILE_ATTENTION_SINGULAR : DESK_TILE_ATTENTION_PLURAL);
}

/** Exact wording the Wallet page (`creator-wallet.tsx`) already uses for these two figures —
 *  reused verbatim rather than re-labelled, per the spec's "use the field the Wallet page
 *  already labels ... with that exact wording". */
export const DESK_TILE_PENDING_PAYOUTS: BilingualText = {
  en: 'Pending Payouts',
  hi: 'पेंडिंग भुगतान',
};

export const DESK_TILE_AVAILABLE_BALANCE: BilingualText = {
  en: 'Available Balance',
  hi: 'उपलब्ध बैलेंस',
};

/** Exact wording the dashboard (`creator-dashboard.tsx`'s `PublicPageCard`) already uses for
 *  this figure. */
export const DESK_TILE_PROFILE_VIEWS: BilingualText = {
  en: 'Profile views · 30d',
  hi: 'प्रोफ़ाइल व्यूज़ · 30 दिन',
};

export const DESK_TILE_ASK_MY_RATE: BilingualText = {
  en: 'Ask Meera my rate',
  hi: 'Meera से अपनी दर पूछें',
};

/** R-U1 — prefill only, never sent automatically. Same text both the tile and any other
 *  "ask my rate" entry point would use. */
export const ASK_MY_RATE_PROMPT: BilingualText = {
  en: 'What rate should I charge for a reel?',
  hi: 'मुझे एक रील के लिए क्या दर लेनी चाहिए?',
};

export interface StarterPrompt {
  key: string;
  text: BilingualText;
}

/** The 4 starter prompts below the desk tiles — prefill only, never sent (same R-U1 rule). */
export const STARTER_PROMPTS: StarterPrompt[] = [
  { key: 'deals-doing', text: { en: 'How are my deals doing?', hi: 'मेरी डील्स कैसी चल रही हैं?' } },
  { key: 'earned-month', text: { en: 'What did I earn this month?', hi: 'मैंने इस महीने कितना कमाया?' } },
  { key: 'reel-script', text: { en: 'Write me a reel script', hi: 'मेरे लिए एक रील स्क्रिप्ट लिखो' } },
  { key: 'review-profile', text: { en: 'Review my profile', hi: 'मेरी प्रोफ़ाइल की समीक्षा करो' } },
];

// ---------------------------------------------------------------------------
// Part C — Meera's results as finished cards (PHASE-C-SPEC.md)
// ---------------------------------------------------------------------------

/**
 * Rich script-card labels — one per line the persona's "Full script format" always emits
 * (`influora-ai/app/prompt/creator_persona.py`), except Idea (the card's own title, no label
 * shown) and Script (never shown as a label — its beats ARE the card's beat list).
 */
export const SCRIPT_CARD_PLAN_LABEL: BilingualText = { en: 'Plan', hi: 'योजना' };
export const SCRIPT_CARD_ACTION_LABEL: BilingualText = { en: 'Action', hi: 'ऐक्शन' };
export const SCRIPT_CARD_SUCCESS_LABEL: BilingualText = {
  en: 'Success looks like',
  hi: 'सफलता ऐसी दिखेगी',
};
export const SCRIPT_CARD_CAPTION_LABEL: BilingualText = { en: 'Caption', hi: 'कैप्शन' };
export const SCRIPT_CARD_BEFORE_YOU_SHOOT_LABEL: BilingualText = {
  en: 'Before you shoot',
  hi: 'शूट करने से पहले',
};
export const SCRIPT_CARD_ON_SCREEN_LABEL: BilingualText = { en: 'On screen', hi: 'स्क्रीन पर' };
export const SCRIPT_CARD_WHY_LABEL: BilingualText = { en: 'Why this works', hi: 'यह क्यों काम करता है' };
/** Per-beat `Stress:`/`Pause:` markers — optional, shown only when the reply's beat line carries
 *  them (see `ScriptBeat.stress`/`.pause` in meera-result-cards.ts). */
export const SCRIPT_CARD_STRESS_LABEL: BilingualText = { en: 'Stress', hi: 'ज़ोर' };
export const SCRIPT_CARD_PAUSE_LABEL: BilingualText = { en: 'Pause', hi: 'विराम' };

export const REVIEW_CARD_WORKING_LABEL: BilingualText = { en: 'Working', hi: 'क्या काम कर रहा है' };
export const REVIEW_CARD_NOT_WORKING_LABEL: BilingualText = {
  en: 'Not working',
  hi: 'क्या काम नहीं कर रहा',
};
export const REVIEW_CARD_NEXT_STEPS_LABEL: BilingualText = { en: 'Next steps', hi: 'अगले कदम' };

/** Copy button, both cards. */
export const RESULT_CARD_COPY: BilingualText = { en: 'Copy', hi: 'कॉपी करें' };
export const RESULT_CARD_COPIED: BilingualText = { en: 'Copied', hi: 'कॉपी हो गया' };

/** R-U1 — prefill only, never sent automatically. Shown as the button's own label, same pattern
 *  as `STARTER_PROMPTS` on the desk. */
/** Short button labels. The *_PROMPT strings below are what gets typed into the composer;
 *  using a whole sentence as a button label made the card look like a wall of text. */
export const REVIEW_CARD_WHAT_FIRST_LABEL: BilingualText = {
  en: 'What should I do first?',
  hi: 'पहले क्या करूँ?',
};

export const REVIEW_CARD_WHAT_FIRST_PROMPT: BilingualText = {
  en: 'What should I do first?',
  hi: 'मुझे पहले क्या करना चाहिए?',
};

/** The toggle that reveals the original message text under a rendered card, and its "back to
 *  card" counterpart. Never discards the original text — see `MeeraCopilotChat.tsx`. */
export const RESULT_CARD_SHOW_AS_TEXT: BilingualText = { en: 'Show as text', hi: 'टेक्स्ट के रूप में दिखाएं' };
export const RESULT_CARD_SHOW_AS_CARD: BilingualText = { en: 'Show as card', hi: 'कार्ड के रूप में दिखाएं' };
