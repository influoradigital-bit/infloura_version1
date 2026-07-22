/**
 * theme-tagger.js — Trend-Spark AI (Task 3, Dev)
 *
 * Canonical, pure, secret-free theme + campaign tagger for the n8n daily trend pull.
 * The SAME logic is pasted into the "Theme Tagger" Code node of trend-pull-workflow.json.
 * This file is the canonical JS copy; keep the node in sync with it.
 *
 * Vocab is an embed of Nisha's LOCKED config (do NOT invent themes):
 *   influora-api/src/main/resources/trendspark/theme-taxonomy.json   (themes + keyword/niche maps)
 *   influora-api/src/main/resources/trendspark/campaign-rulebook.json (campaign_type + peak_window_days)
 * Those JSON configs are the source of truth. n8n Code nodes cannot require() a repo
 * file, so the closed vocab is embedded inline here AND in the workflow node.
 * If either JSON changes, re-sync this file and the node.
 *
 * DRIFT IS CI-ENFORCED — do not rely on discipline alone:
 *   trendspark/n8n/tagger-sync.test.js (run: `node trendspark/n8n/tagger-sync.test.js`)
 *   asserts THEMES exactly equals the taxonomy, the maps are supersets of the locked
 *   config with identical shared values, campaign typicals match, and this file is
 *   byte-equivalent to the inline node. Gated in CI by
 *   .github/workflows/trendspark-tagger-sync.yml. A vocab change that misses any copy
 *   fails the build. Never edit the tagger's behavior just to make the check pass —
 *   propagate the real vocab change to every copy instead.
 *
 * Contract (Priya schema-lock §1a): produces { themes:[closed vocab], campaign_type, peak_window_days }
 *   campaign_type ∈ { HYPE, SEASONAL, PRIDE, EDUCATIONAL }
 *   themes ⊆ THEMES (empty array => caller must DROP the row, never write garbage)
 */

'use strict';

// ── Closed theme vocabulary (theme-taxonomy.json → themes) ───────────────────
const THEMES = new Set([
  'strength', 'action', 'energy', 'family', 'festive', 'light', 'tradition',
  'pride', 'victory', 'beauty', 'glow', 'self-care', 'health', 'discipline',
  'elegance', 'masculinity', 'femininity', 'power', 'celebration', 'joy',
  'devotion', 'spirituality', 'luxury', 'comfort', 'wellness', 'fitness',
  'confidence', 'style', 'glamour', 'radiance', 'purity', 'togetherness',
  'heritage', 'authenticity', 'innovation', 'youth', 'vitality', 'calm',
  'peace', 'resilience',
]);

// ── keyword → themes (theme-taxonomy.json → keyword_to_theme_mappings) ────────
const KEYWORD_TO_THEMES = {
  'action film': ['action', 'strength', 'energy', 'power'],
  'sports film': ['strength', 'victory', 'pride', 'discipline'],
  'romance': ['beauty', 'joy', 'celebration', 'elegance'],
  'family drama': ['family', 'togetherness', 'tradition', 'heritage'],
  'cricket': ['pride', 'victory', 'energy', 'strength'],
  'football': ['strength', 'energy', 'victory', 'pride'],
  'festival': ['festive', 'celebration', 'joy', 'tradition'],
  'wedding': ['celebration', 'family', 'tradition', 'joy'],
  'diwali': ['festive', 'light', 'celebration', 'family', 'tradition'],
  'holi': ['festive', 'celebration', 'joy', 'tradition'],
  'eid': ['festive', 'family', 'tradition', 'celebration', 'devotion'],
  'navratri': ['festive', 'celebration', 'tradition', 'spirituality', 'devotion'],
  'durga puja': ['festive', 'celebration', 'tradition', 'spirituality', 'devotion'],
  'raksha bandhan': ['family', 'togetherness', 'tradition', 'celebration'],
  'karva chauth': ['tradition', 'devotion', 'celebration', 'family'],
  'bhai dooj': ['family', 'togetherness', 'tradition', 'celebration'],
  'ganesh chaturthi': ['festive', 'celebration', 'tradition', 'spirituality', 'devotion'],
  'onam': ['festive', 'celebration', 'tradition', 'family'],
  'pongal': ['festive', 'celebration', 'tradition', 'family'],
  'new year': ['celebration', 'joy', 'energy', 'innovation'],
  'republic day': ['pride', 'tradition', 'heritage', 'celebration'],
  'independence day': ['pride', 'tradition', 'heritage', 'celebration'],
  'champions trophy': ['pride', 'victory', 'energy', 'strength'],
  'world cup': ['pride', 'victory', 'energy', 'strength'],
  'olympics': ['strength', 'victory', 'pride', 'discipline'],
  'beauty': ['beauty', 'glow', 'elegance', 'confidence'],
  'fitness': ['strength', 'health', 'energy', 'discipline'],
  'yoga': ['calm', 'peace', 'discipline', 'wellness'],
  'glow': ['beauty', 'glow', 'radiance', 'confidence'],
  'skincare': ['beauty', 'glow', 'self-care', 'radiance'],
  'workout': ['strength', 'energy', 'discipline', 'fitness'],
  'gym': ['strength', 'power', 'discipline', 'energy'],
  'traditional': ['tradition', 'heritage', 'authenticity', 'elegance'],
  'ethnic': ['tradition', 'heritage', 'elegance', 'celebration'],
  'bridal': ['celebration', 'beauty', 'elegance', 'tradition'],
  'fashion week': ['style', 'glamour', 'innovation', 'elegance'],
  'award show': ['glamour', 'celebration', 'style', 'luxury'],
  'outfit ideas': ['style', 'glamour', 'confidence'],
  'glowing skin': ['beauty', 'glow', 'radiance'],
  'home workout': ['fitness', 'health', 'discipline'],
  'bridal makeup': ['beauty', 'glamour', 'celebration'],
  'weight loss': ['health', 'fitness', 'discipline'],
  'styling tips': ['style', 'confidence', 'elegance'],
  'korean skincare': ['beauty', 'innovation', 'self-care'],
  'saree draping': ['tradition', 'elegance', 'heritage'],
  'mehndi design': ['beauty', 'celebration', 'tradition'],
  'monsoon outfit': ['style', 'comfort', 'innovation'],
  'monsoon skincare': ['beauty', 'self-care', 'wellness'],
  'summer dress': ['style', 'comfort', 'youth'],
  'summer skincare': ['beauty', 'self-care', 'radiance'],
  'winter skincare': ['beauty', 'self-care', 'wellness'],
  'wedding guest dress': ['style', 'elegance', 'celebration'],
  'ipl jersey': ['pride', 'energy', 'style'],
  'new year outfit': ['style', 'celebration', 'confidence'],
  'easy recipes': ['family', 'comfort', 'authenticity'],
  'healthy recipes': ['health', 'wellness', 'authenticity'],
  'punjabi suit': ['tradition', 'style', 'heritage'],
};

// ── niche → themes (theme-taxonomy.json → niche_to_theme_mappings) ───────────
// Used when a source hands us a category/niche label (e.g. NewsAPI category, catalog niche).
const NICHE_TO_THEMES = {
  fitness: ['strength', 'health', 'energy', 'discipline', 'power', 'resilience', 'vitality'],
  supplement: ['strength', 'health', 'energy', 'discipline', 'wellness', 'vitality'],
  apparel: ['festive', 'tradition', 'elegance', 'style', 'confidence', 'heritage'],
  saree: ['festive', 'tradition', 'elegance', 'femininity', 'heritage', 'celebration', 'beauty'],
  skincare: ['beauty', 'glow', 'self-care', 'radiance', 'wellness', 'confidence', 'purity'],
  cosmetics: ['beauty', 'glamour', 'confidence', 'style', 'radiance', 'elegance'],
  food: ['tradition', 'celebration', 'family', 'joy', 'festive', 'heritage', 'authenticity'],
  jewelry: ['luxury', 'elegance', 'celebration', 'tradition', 'beauty', 'glamour'],
  fashion: ['style', 'elegance', 'confidence', 'innovation', 'youth', 'glamour'],
  sports: ['strength', 'energy', 'victory', 'pride', 'power', 'discipline', 'resilience'],
  athleisure: ['fitness', 'energy', 'style', 'confidence', 'comfort', 'youth'],
  wellness: ['health', 'calm', 'peace', 'self-care', 'wellness', 'vitality'],
  yoga: ['calm', 'peace', 'discipline', 'wellness', 'spirituality', 'vitality'],
  home: ['comfort', 'family', 'togetherness', 'tradition', 'peace'],
  handloom: ['heritage', 'tradition', 'authenticity', 'elegance', 'pride'],
  organic: ['health', 'purity', 'authenticity', 'wellness', 'self-care'],
  luxury: ['luxury', 'elegance', 'glamour', 'style', 'confidence'],
  // common NewsAPI categories mapped onto the closed niche vocab:
  entertainment: ['style', 'glamour', 'celebration', 'joy'],
  health: ['health', 'wellness', 'self-care', 'vitality'],
};

// ── campaign-rulebook.json → keyword_patterns + peak_window_days.typical ──────
const CAMPAIGN_RULES = {
  PRIDE: {
    typical: 1,
    keywords: ['india win', 'cricket victory', 'world cup', 'champions trophy',
      'olympics', 'medal', 'republic day', 'independence day', 'indian achievement',
      'national pride', 'team india', 'victory', 'cricket'],
  },
  SEASONAL: {
    typical: 21,
    keywords: ['diwali', 'holi', 'eid', 'navratri', 'durga puja', 'raksha bandhan',
      'ganesh chaturthi', 'onam', 'pongal', 'new year', 'festival', 'celebration',
      'sale', 'shopping season'],
  },
  HYPE: {
    typical: 3,
    keywords: ['movie', 'film release', 'trailer launch', 'celeb launch', 'ott premiere',
      'surprise', 'viral', 'trending now', 'award winner', 'film', 'trailer'],
  },
  EDUCATIONAL: {
    typical: 30,
    keywords: ['health', 'fitness', 'wellness', 'yoga', 'nutrition', 'immunity',
      'mental health', 'awareness', 'self-care', 'tips', 'how to', 'benefits of'],
  },
};

// ── helpers ──────────────────────────────────────────────────────────────────
function matchesAny(haystack, keywords) {
  return keywords.some((k) => haystack.includes(k));
}

/**
 * deriveCampaignType — implements campaign-rulebook.json type_assignment_logic.
 * Precedence: patriotic override (PRIDE) → festival (SEASONAL) → movie/celeb (HYPE)
 *             → health/wellness (EDUCATIONAL) → peak-window fallback.
 * `source` may contain hint tokens: 'tmdb','festival_calendar','youtube_trending','news','google_trends'.
 */
function deriveCampaignType(loweredText, sources, peakWindowDays) {
  const src = (sources || []).map((s) => String(s).toLowerCase());
  const has = (name) => src.includes(name);

  // Precedence is source/keyword-disambiguated (rulebook type_assignment_logic), because a single
  // source can map to two types (e.g. youtube_trending → HYPE viral OR EDUCATIONAL fitness).
  // 1. Patriotic / sports-win keywords win over everything (rulebook: festival "overridden by patriotic keywords").
  if (matchesAny(loweredText, CAMPAIGN_RULES.PRIDE.keywords)) return 'PRIDE';
  // 2. Health/wellness/evergreen keywords → EDUCATIONAL. Checked before HYPE so an ambiguous
  //    youtube_trending/news wellness item is treated as evergreen, not false-urgency (anti-spam: fail long).
  if (matchesAny(loweredText, CAMPAIGN_RULES.EDUCATIONAL.keywords)) return 'EDUCATIONAL';
  // 3. Movie/OTT release SOURCE (tmdb, unambiguously HYPE) or an explicit hype keyword → HYPE.
  //    A film releasing on a festival weekend is still HYPE — source beats a stray festival keyword (rulebook step 1).
  if (has('tmdb') || matchesAny(loweredText, CAMPAIGN_RULES.HYPE.keywords)) return 'HYPE';
  // 4. Festival calendar source or festival keyword → SEASONAL.
  if (has('festival_calendar') || matchesAny(loweredText, CAMPAIGN_RULES.SEASONAL.keywords)) return 'SEASONAL';
  // 5. Default fallback by peak window (rulebook step 5)
  if (typeof peakWindowDays === 'number' && !Number.isNaN(peakWindowDays)) {
    if (peakWindowDays <= 3) return 'HYPE';
    if (peakWindowDays <= 7) return 'PRIDE';
    return 'SEASONAL';
  }
  return 'EDUCATIONAL';
}

/**
 * tagTrend — pure function. No I/O, no secrets.
 * @param {{text:string, source?:string|string[], category?:string, peakWindowDays?:number}} raw
 * @returns {{trend_text:string, source:string[], themes:string[], campaign_type:string, peak_window_days:number}}
 *          themes is [] when nothing matched — the caller MUST drop such rows (don't write garbage).
 */
function tagTrend(raw) {
  const text = String((raw && raw.text) || '').trim();
  const lowered = text.toLowerCase();
  const sources = Array.isArray(raw && raw.source)
    ? raw.source
    : (raw && raw.source ? [raw.source] : []);
  const category = String((raw && raw.category) || '').trim().toLowerCase();

  // Collect themes from keyword hits in the trend text.
  const collected = new Set();
  for (const [keyword, themes] of Object.entries(KEYWORD_TO_THEMES)) {
    if (lowered.includes(keyword)) themes.forEach((t) => collected.add(t));
  }
  // Add themes from the category/niche label, if present.
  if (category && NICHE_TO_THEMES[category]) {
    NICHE_TO_THEMES[category].forEach((t) => collected.add(t));
  }

  // Closed-vocab guard: drop anything not in Nisha's locked list.
  const themes = [...collected].filter((t) => THEMES.has(t));

  const campaign_type = deriveCampaignType(lowered, sources, raw && raw.peakWindowDays);
  const peak_window_days = (typeof (raw && raw.peakWindowDays) === 'number' && !Number.isNaN(raw.peakWindowDays))
    ? raw.peakWindowDays
    : CAMPAIGN_RULES[campaign_type].typical;

  return {
    trend_text: text,
    source: sources.map((s) => String(s).toLowerCase()),
    themes,
    campaign_type,
    peak_window_days,
  };
}

// ── Per-run row cap (defense-in-depth against an unbounded MySQL INSERT) ─────
// The "Theme Tagger + row builder" Code node in trend-pull-workflow.json caps
// LLM recovery calls per run (MAX_RECOVERY/TREND_TAG_MAX_RECOVERY_PER_RUN),
// but had NO cap on how many rows it emits for the MySQL INSERT node — a
// burst of upstream data (TMDb + NewsAPI + YouTube all firing the same run)
// could balloon a single insert with no ceiling. This constant/helper is the
// canonical source for that cap; the node inlines the equivalent logic
// (same env var, same default, applied in the same place: AFTER
// normalization/dedup, so the cap counts real distinct rows, not
// pre-dedup duplicates).
const DEFAULT_MAX_ROWS_PER_RUN = 100;

/**
 * capRows — keep at most `maxRows` of the already-normalized/deduped rows.
 * There is no priority field on a built row today, so this is a first-N cap
 * in input order (not a "highest priority survives" sort). Logs (via the
 * supplied `log`, default `console.warn`) how many rows were dropped when the
 * cap is hit. Pure aside from that log call; never mutates `rows`.
 * @param {Array} rows
 * @param {number} [maxRows]
 * @param {(msg: string) => void} [log]
 * @returns {Array}
 */
function capRows(rows, maxRows = DEFAULT_MAX_ROWS_PER_RUN, log = console.warn) {
  if (!Array.isArray(rows) || rows.length <= maxRows) return rows;
  const dropped = rows.length - maxRows;
  log(`trend-pull: per-run row cap reached (${maxRows}) — dropping ${dropped} of ${rows.length} row(s)`);
  return rows.slice(0, maxRows);
}

// CommonJS export for reuse / testing (n8n Code node inlines the body instead).
if (typeof module !== 'undefined' && module.exports) {
  module.exports = {
    tagTrend, deriveCampaignType, THEMES, KEYWORD_TO_THEMES, NICHE_TO_THEMES, CAMPAIGN_RULES,
    capRows, DEFAULT_MAX_ROWS_PER_RUN,
  };
}

// ── inline self-test: `node theme-tagger.js` ────────────────────────────────
if (typeof require !== 'undefined' && require.main === module) {
  const cases = [
    {
      name: 'Salman Khan action film (TMDb) → HYPE',
      raw: { text: 'Salman Khan — new action film releases this Eid weekend', source: ['tmdb', 'google_trends'] },
      expect: { campaign_type: 'HYPE', themesInclude: ['action', 'strength', 'energy'], peak_window_days: 3 },
    },
    {
      name: 'Diwali festival (calendar) → SEASONAL',
      raw: { text: 'Diwali shopping season begins across India', source: ['festival_calendar'] },
      expect: { campaign_type: 'SEASONAL', themesInclude: ['festive', 'light', 'celebration'], peak_window_days: 21 },
    },
    {
      name: 'India cricket World Cup win → PRIDE',
      raw: { text: 'Team India wins the World Cup', source: ['news', 'google_trends'] },
      expect: { campaign_type: 'PRIDE', themesInclude: ['pride', 'victory'], peak_window_days: 1 },
    },
    {
      name: 'Yoga Day wellness (evergreen) → EDUCATIONAL',
      raw: { text: 'International Yoga Day fitness and wellness tips', source: ['youtube_trending'], category: 'wellness' },
      expect: { campaign_type: 'EDUCATIONAL', themesInclude: ['calm', 'wellness', 'discipline'], peak_window_days: 30 },
    },
    {
      name: 'Leather bag brand vs cricket (no theme overlap check) still tags cricket',
      raw: { text: 'Cricket fever grips the nation', source: ['google_trends'] },
      expect: { campaign_type: 'PRIDE', themesInclude: ['pride', 'victory'], peak_window_days: 1 },
    },
  ];

  let failed = 0;
  for (const c of cases) {
    const out = tagTrend(c.raw);
    const okType = out.campaign_type === c.expect.campaign_type;
    const okPeak = out.peak_window_days === c.expect.peak_window_days;
    const okThemes = c.expect.themesInclude.every((t) => out.themes.includes(t));
    const okVocab = out.themes.every((t) => THEMES.has(t));
    const pass = okType && okPeak && okThemes && okVocab;
    if (!pass) failed++;
    console.log(
      `${pass ? 'PASS' : 'FAIL'} | ${c.name}\n` +
      `      → campaign_type=${out.campaign_type} peak=${out.peak_window_days} themes=[${out.themes.join(', ')}]` +
      (pass ? '' : `\n      EXPECT type=${c.expect.campaign_type} peak=${c.expect.peak_window_days} themes⊇[${c.expect.themesInclude.join(', ')}] vocabOK=${okVocab}`)
    );
  }

  // capRows — per-run row cap self-test.
  const capLogs = [];
  const underCap = Array.from({ length: 50 }, (_, i) => i);
  const overCap = Array.from({ length: 150 }, (_, i) => i);
  const underResult = capRows(underCap, 100, (msg) => capLogs.push(msg));
  const overResult = capRows(overCap, 100, (msg) => capLogs.push(msg));
  const capOk =
    underResult.length === 50 &&
    underResult === underCap && // untouched when under the cap
    overResult.length === 100 &&
    overResult[0] === 0 && overResult[99] === 99 && // first-N, input order preserved
    capLogs.length === 1 && capLogs[0].includes('dropping 50 of 150');
  console.log(`${capOk ? 'PASS' : 'FAIL'} | capRows per-run cap (100 default, first-N, logs dropped count)`);
  if (!capOk) failed++;

  console.log(`\n${failed === 0 ? 'ALL PASS' : failed + ' FAILED'} (${cases.length + 1} cases)`);
  process.exit(failed === 0 ? 0 : 1);
}
