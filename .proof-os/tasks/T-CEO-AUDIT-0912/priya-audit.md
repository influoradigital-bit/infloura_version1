# T-CEO-AUDIT-0912 — Priya (CTO) audit: Creator AI Co-pilot / TrendSpark trend chain

Audit only. No source file modified. All paths relative to repo root
`C:/Users/Sage world/Downloads/New Influora Ai/New Influora`.
Branch at audit time: `feat/meera-creator-phase-e`.

Verification actually run:
- `mvn -o -Dtest=CreatorNudgeServiceTest test` with cwd `influora-api` (no `-pl`, not piped).
  Exit 0. `target/surefire-reports/TEST-...CreatorNudgeServiceTest.xml`:
  `tests="34" errors="0" skipped="0" failures="0"`.
- Every other claim below is static reading of the files named, with line numbers.

---

## Q1 — The complete chain for one creator to see one suggestion

| # | Link | Component | Exists? | Deployed? |
|---|------|-----------|---------|-----------|
| 1 | External data | NewsAPI / TMDB / YouTube Data API | keys are config-only | `NEWSAPI_KEY`/`TMDB_API_KEY`/`YOUTUBE_API_KEY` default blank in all three compose files (`deploy/utho/docker-compose.utho.yml:174-176`, `deploy/utho/docker-compose.utho-shared.yml:192-194`, `deploy/hostinger/docker-compose.hostinger.yml:153-155`) |
| 2 | Ingest | n8n workflow `trendspark/n8n/trend-pull-workflow.json` | YES (a JSON file) | **NO.** `"active": false` (workflow root). No `n8n` service exists in any Influora compose file — `grep -n "^  [a-z0-9_-]*:" deploy/utho/docker-compose.utho.yml` lists only caddy, mysql, redis, clamav, influora-api, influora-ai, frontend. The only n8n mentioned is Snapsby's pre-existing one on the shared box (`deploy/utho/docker-compose.utho-shared.yml:1`). |
| 3 | Table | `trends` (`db/migration/V51__trendspark.sql:4-19`) | YES | Migrated, but **nothing we deploy writes it** — see Q2 |
| 4 | Creator IG token | `MetaOAuthToken` (workspace_id IS NULL) | YES | live |
| 5 | Caption fetch | `CreatorCaptionSyncJob` (`influora-api/src/main/java/com/influora/job/CreatorCaptionSyncJob.java:88-94`) | YES | **Body returns immediately**: `if (!props.isEnabled()) { … return; }` at :91-94. `CREATOR_COPILOT_ENABLED` defaults `false` (`application.yml:508`, `docker-compose.utho.yml:180`, `docker-compose.hostinger.yml:159`) and is **not present at all** in `docker-compose.utho-shared.yml` (grep for `COPILOT` in that file returns nothing) |
| 6 | Table | `creator_captions` (`V20260721130000__creator_captions.sql:12-26`) | YES | migrated, empty |
| 7 | Theme tag | `CreatorThemeTaggingJob` (`.../job/CreatorThemeTaggingJob.java:69-75`) | YES | same flag gate at :72-75 → off |
| 8 | Column | `creator_profiles.theme_tags` (`V20260721120000__creator_profile_theme_tags.sql`) | YES | migrated, NULL for everyone |
| 9 | Read API | `GET /creator/copilot/suggestion/today` (`.../web/CreatorCopilotController.java:42-49`) | YES | deployed, auth via `/creator/**` (`SecurityConfig.java:280`) |
| 10 | Orchestration | `CreatorNudgeService.getSuggestion` (`.../service/creatorcopilot/CreatorNudgeService.java:81-173`) | YES | deployed |
| 11 | Match | `ThemeMatchService.score` (`.../service/trendspark/ThemeMatchService.java:58-71`) + `trendspark/theme-taxonomy.json` | YES | deployed |
| 12 | AI phrasing | `CreatorSuggestionAiClient` → `POST /internal/creator-suggestion` (`influora-ai/app/routes/creator_suggestion.py:201-202`, registered `app/main.py:100-102`) | YES | deployed |
| 13 | Table | `creator_nudge_log` (`V20260721140000__creator_nudge_log.sql:17-50`) | YES | migrated |
| 14 | FE | `/creator/copilot` (`src/App.tsx:80,584`), nav item (`src/components/creator/creator-layout.tsx:134`), `useDailySuggestion` (`src/hooks/useDailySuggestion.ts:128-225`) | YES | deployed |

**The chain is broken in two independent places, either of which alone is fatal:**

- **Link 2/3 — no trend supply.** Nothing deployed writes `trends`. `CreatorNudgeService.java:112`
  calls `trendRepository.findActive(Instant.now())` against an empty table, so `bestTrend == null`
  at :123 and every creator gets `no_suggestion_today` forever.
- **Link 5/7 — the two jobs that build a creator's themes are off by default in every environment.**
  So even with trends present, `profile.getThemeTagsJson()` is null at `CreatorNudgeService.java:105-110`
  and every creator gets `pending_tagging` forever.

Cost: the Co-pilot page is live, in the creator nav, and can never produce a real suggestion in any
shipped configuration. Every creator who clicks "Co-pilot" reaches a dead feature.

---

## Q2 — What writes rows into `trends`?

**Nothing we deploy.** Searched the whole repo, not assumed:

- `grep -rn "trendRepository\.\(save\|saveAll\|delete\)\|Trend\.builder()" influora-api/src/` → **zero hits.**
- `TrendRepository` (`influora-api/src/main/java/com/influora/repository/TrendRepository.java:10-16`)
  declares exactly one method, `findActive` — a read. It extends `JpaRepository` so `save()` exists
  inherited, but no caller invokes it.
- `Trend` (`.../domain/entity/Trend.java:20-114`) has **no setters and no builder** — getters only.
  The entity is structurally incapable of being populated by Spring. Its own javadoc says so at
  `Trend.java:16-17`: "n8n (Dev, T3) writes daily; Spring only reads."
- The only writer anywhere is the n8n node `MySQL INSERT trends` in
  `trendspark/n8n/trend-pull-workflow.json`, and that workflow is `"active": false` and is not
  deployed by any compose file (Q1 link 2).
- `grep -rln "INTO trends"` across the repo matches only docs (`influora-api/SERVER-DATABASE-GUIDE.md`)
  and `.proof-os` task notes — no runtime code.

Blunt version: **the `trends` table has no producer in anything we ship. It is empty and stays empty.**

---

## Q3 — Does trend expiry work once the co-pilot has logged suggestions? No.

Expiry is meant to be two things:
1. Filter: `TrendRepository.java:14` — `WHERE t.expiresAt > :now`.
2. Purge: the n8n node `MySQL DELETE expired`, query
   `DELETE FROM trends WHERE expires_at < UTC_TIMESTAMP(6);`
   (`trendspark/n8n/trend-pull-workflow.json`, node "MySQL DELETE expired", schedule 06:30 IST).

The constraint that breaks the purge:

```sql
-- influora-api/src/main/resources/db/migration/V20260721140000__creator_nudge_log.sql:33
CONSTRAINT fk_creator_nudge_log_trend   FOREIGN KEY (trend_id) REFERENCES trends (id)
```

No `ON DELETE` clause → InnoDB default **RESTRICT**. `creator_nudge_log.trend_id` is
`NOT NULL` (:20) and rows are never deleted — there is no retention job for `creator_nudge_log`
(`ls influora-api/src/main/java/com/influora/job/` has no purge for it; the only retention purge job
is `MeeraInteractionLogRetentionPurgeJob` for a different table).

So the first day any creator is shown a suggestion, that trend is pinned forever. The n8n DELETE is a
**single un-filtered statement over the whole table**, so one pinned row fails the entire statement
(MySQL errno 1451) — not just that row. From then on **no expired trend is ever purged** and the
workflow run errors on that node (the node has no `continueOnFail`/`neverError` option set, unlike the
HTTP nodes which do).

Note the contrast: the brand-side `nudge_log` (`V51__trendspark.sql:39-57`) deliberately has **only an
index** `idx_nudge_trend`, no FK. The Co-pilot table introduced the FK that breaks the documented
delete contract that `TrendRepository.java:12-13` explicitly relies on ("n8n … auto-deletes expired
rows").

Cost: unbounded growth of `trends`, and a nightly workflow that fails silently once real usage starts.
It does *not* cause stale cards on its own — the `expiresAt > now` filter still holds — see Q13 for
the separate staleness defect.

---

## Q4 — `TrendIngestProperties`: is it consumed?

**No code consumes it.** `grep -rn "TrendIngestProperties" influora-api/src/` returns exactly three
hits and none is a consumer:

- `influora-api/src/main/java/com/influora/config/TrendIngestProperties.java:23` — the declaration.
- `influora-api/src/main/java/com/influora/InfluoraApiApplication.java:25` — the import.
- `InfluoraApiApplication.java:69` — the `@EnableConfigurationProperties` registration, whose own
  comment at :66-68 says it is registered *in advance* "for … any `@Component` ingest job that
  constructor-injects TrendIngestProperties". **That ingest job does not exist** —
  `ls influora-api/src/main/java/com/influora/job/` contains no trend-ingest class.

`isConfigured()`/`hasNewsapiKey()`/`hasTmdbKey()`/`hasYoutubeKey()`/`isFullyConfigured()`/`getPullCron()`
(`TrendIngestProperties.java:46-70,104-110`) have **zero callers**. `pull-cron` is not referenced by any
`@Scheduled` — `application.yml:525` binds it to a field nobody reads, and no job uses
`${influora.trend-ingest.pull-cron}` as a cron placeholder (contrast the copilot jobs, which do:
`CreatorCaptionSyncJob.java:88`, `CreatorThemeTaggingJob.java:69`).

**If an operator sets `TREND_INGEST_ENABLED=true` and supplies real NewsAPI/TMDB/YouTube keys:
nothing happens.** No request is issued, no row is written, no log line is emitted. The bean is
constructed, bound, and never read. The operator has paid for three API keys and will reasonably
believe the feature is on.

---

## Q5 — Is the per-day cap enforced, and by what?

**By a database constraint only. The application-code half is dead, and two simultaneous requests
produce a 500.**

- The property that *looks* like the cap is inert:
  `CreatorCopilotProperties.getMaxSuggestionsPerCreatorPerDay()` (`.../config/CreatorCopilotProperties.java:63-65`)
  has **no consumer in main source** — `grep -rn "getMaxSuggestionsPerCreatorPerDay" influora-api/src/`
  hits only `src/test/java/com/influora/config/CreatorCopilotConfigWiringTest.java:67`. Setting
  `CREATOR_COPILOT_DAILY_CAP=3` (`application.yml:510`) changes nothing. The class javadoc
  (:11-14) is honest about this; the env var name is not.
- The real enforcement is the DB:
  ```sql
  -- V20260721140000__creator_nudge_log.sql:48-50
  ADD COLUMN shown_day DATE GENERATED ALWAYS AS (DATE(shown_at)) STORED,
  ADD UNIQUE KEY uq_creator_nudge_day (creator_profile_id, shown_day);
  ```
- The normal single-request path is genuinely capped by the idempotent read at
  `CreatorNudgeService.java:98-103` — a same-day repeat returns the existing row with no re-scoring
  and no AI call. That part is correct.

**Two simultaneous first-of-day requests:** both pass the read at :98-103 (no row yet), both score,
both call the AI, both build a row, both `save()`. One commits; the other gets a duplicate-key
violation. The handler written for exactly this, `catch (DataIntegrityViolationException)` at
`CreatorNudgeService.java:162-170`, **never runs.** `CreatorNudgeLog`'s `@Id` is caller-assigned
(`CreatorNudgeService.java:149`, `Ulids.newUlid()`), the entity has no `@Version` and does not
implement `Persistable` (`.../domain/entity/CreatorNudgeLog.java:25-68`), so Spring Data's `isNew()`
is false and `SimpleJpaRepository.save()` calls `em.merge()` — which schedules the INSERT without
flushing. The violation fires at **transaction commit**, inside the `@Transactional` proxy at
`CreatorNudgeService.java:81`, after the try block has already exited. The exception escapes as a 500.

This is not my inference alone — the test class states it as fact and declines to write a test that
would falsely green it: `influora-api/src/test/java/com/influora/service/creatorcopilot/CreatorNudgeServiceTest.java:57-70`
("a genuine concurrent first-of-day race 500s today … Fixing the catch is a main-source change
(and `saveAndFlush` alone is not the fix …)"). That is exactly the `save()`-vs-`saveAndFlush()`-in-a-catch
pattern already on the record for this repo. The honest read: **the race is known, documented, and
still live in main.**

Cost: a creator who double-taps, or has two tabs open, or whose client retries, gets an error toast
on the one screen the whole feature exists to deliver — and we paid for two AI calls to produce it.

---

## Q6 — AI call, failure handling, and billable spend on refresh

**Yes, there is exactly one AI call per produced suggestion:** `CreatorNudgeService.java:132` →
`callAiSafely` (:201-213) → `CreatorSuggestionAiClient.requestSuggestion`
(`.../integration/ai/CreatorSuggestionAiClient.java:102-179`) → `POST /internal/creator-suggestion`
on influora-ai (`influora-ai/app/routes/creator_suggestion.py:201-202`).

**Failure / timeout / null:** handled correctly, and this is one of the better-built parts.
`requestSuggestion` returns `null` on every failure mode — missing id (:103-106), token/serialisation
failure (:115-121), transport failure or timeout (:135-142, request timeout
`props.getRequestTimeoutSeconds()` default 15s at `.../config/CreatorSuggestionAiProperties.java:16`,
connect timeout 5s at :15), non-200 (:144-152), unparseable body (:154-163), and `success:false` /
null / blank `headline` / blank `contentIdea` (:165-176). `callAiSafely` additionally swallows any
throw (`CreatorNudgeService.java:204-212`). On null the service falls through to `templatedFallback`
(:141-145, :220-229) and stamps `NudgeMessageSource.FALLBACK`. The creator never sees an error from
the AI leg. Verified by test: `getSuggestion_aiReturnsNull_usesTemplatedFallbackWithMessageSourceFallback`
and `getSuggestion_aiClientThrows_swallowedIntoFallbackAndNeverPropagates`
(`CreatorNudgeServiceTest.java:434,458`).

**Can a creator trigger billable spend by refreshing? Effectively no, and this is correct as built.**
- Refresh after a row exists → returns at `CreatorNudgeService.java:101-103`, before any AI call.
  Pinned by `getSuggestion_todaysRowExists_returnsSameRowWithNoRescoringAndNoAiSpend`
  (`CreatorNudgeServiceTest.java:163`).
- Refresh with no themes → returns at :109, no AI call.
- Refresh with themes but no trend above threshold → returns at :125, **no AI call and no row**, so
  this path re-scores on every refresh but spends nothing. This is the state every creator is in
  today (empty `trends`), so today's refresh cost is zero AI dollars and one `findActive` query.
- The one leak is the Q5 race: the losing request pays for its AI call and writes no row. Bounded to
  roughly one extra call per concurrent burst, not unbounded.
- Front end additionally caches with `staleTime: Infinity` per day key
  (`src/hooks/useDailySuggestion.ts:158`), so an in-tab refresh usually does not even reach the server.

**What is NOT safe on this path is the fallback copy, not the spend** — see PRODUCT ERRORS below.

---

## Q7 — Creator signs up today, has NOT connected Instagram. What do they see?

**Today.** `useDailySuggestion` gates the suggestion query on `enabled: isConnected`
(`src/hooks/useDailySuggestion.ts:157`), so no backend call is made. `status` is `'idle'`
(:183). `src/pages/creator-copilot.tsx:44-45,191` therefore renders `CopilotPreviewCard`, and
`DailySuggestionSection` renders `IGConnectPrompt` (`DailySuggestionSection.tsx:56-66`).

Concretely on screen:
1. "Talk to Meera" card (`creator-copilot.tsx:144-181`) — a different feature.
2. A dashed-border card labelled **"Preview"**, badge **"Skincare Routine"**, headline "Turn your
   morning routine into a 30-second reel", body about trending audio and quick cuts, footer "Preview —
   connect Instagram for ideas personalised to your audience." All of it is **hard-coded string
   literals** at `src/components/creator/copilot/CopilotPreviewCard.tsx:101,106,108-110,114-115`.
   Nothing about it is real or personalised. It is labelled "Preview" twice, which is the right call.
3. "Get your first daily idea / Link Instagram to unlock Co-pilot." + a **Connect Instagram** button
   (`IGConnectPrompt.tsx:184-197`).

**In six weeks: byte-for-byte identical.** Nothing on this path is time-dependent. The preview card
has no data source; the connect prompt has no state. A creator who never connects sees the same
fabricated skincare example on day 1 and day 42. That is acceptable as an honest teaser, but note
that a *finance* or *food* creator is being shown a skincare example as the representative promise of
the product — and per Q10 the product could not deliver a food suggestion even if they did connect.

---

## Q8 — Creator connects IG but writes captions in Hindi / Hinglish / Marathi / Tamil

**A creator who never writes in English gets nothing, permanently, and is told a comforting lie
every single day.**

The mechanism. `CreatorThemeTaggingJob.java:87` calls `ThemeMatchService.themesForText(captionText)`.
That method (`ThemeMatchService.java:98-120`) is **case-insensitive ASCII substring containment only**:

```java
// ThemeMatchService.java:102-106
String lower = freeText.toLowerCase(Locale.ROOT);
…
if (keyword != null && !keyword.isBlank() && lower.contains(keyword.toLowerCase(Locale.ROOT))) {
```

There is no stemming, no translation, no transliteration, no tokenizer, no language detection.

The vocabulary it matches against is
`influora-api/src/main/resources/trendspark/theme-taxonomy.json` → `keyword_to_theme_mappings`:
**57 keys, every one of them Latin-script English or Latin-script Hindi loanwords.** Full list:
`action film, sports film, romance, family drama, cricket, football, festival, wedding, diwali, holi,
eid, navratri, durga puja, raksha bandhan, karva chauth, bhai dooj, ganesh chaturthi, onam, pongal,
new year, republic day, independence day, champions trophy, world cup, olympics, beauty, fitness,
yoga, glow, skincare, workout, gym, traditional, ethnic, bridal, fashion week, award show, outfit
ideas, glowing skin, home workout, bridal makeup, weight loss, styling tips, korean skincare, saree
draping, mehndi design, monsoon outfit, monsoon skincare, summer dress, summer skincare, winter
skincare, wedding guest dress, ipl jersey, new year outfit, easy recipes, healthy recipes, punjabi suit`.

**Not one Devanagari, Tamil, Telugu, Bengali or Gujarati character appears in the file.**

So:
- **Devanagari Hindi / Marathi, or Tamil script** — e.g. a caption reading `आज का वर्कआउट` or
  `இன்றைய உடற்பயிற்சி`: zero keys match, `themesForText` returns an empty set,
  `CreatorThemeTaggingJob.java:90` skips the union, `creator_profiles.theme_tags` stays NULL,
  and `CreatorNudgeService.java:106-110` returns `pending_tagging` **forever**.
- **Hinglish in Latin script** — partially lucky. A caption containing `gym`, `saree`, `diwali`,
  `beauty` will match; `vyayam`, `kapde`, `khana`, `tyohar`, `mehnat` will not. Matching is
  accidental, driven by which English loanwords the creator happens to use.

What the creator is told, every day, for as long as this lasts:
`src/components/creator/copilot/SuggestionEmptyState.tsx:26` renders, for `pending_tagging`:

> "Reading your recent posts — your first idea lands by tomorrow morning."

`DailySuggestionSection.tsx:69-78` routes `status === 'loading'` here, and
`useDailySuggestion.ts:186` maps the wire status `pending_tagging` → `'loading'`. There is no
timeout, no escalation, no "we could not read your posts" branch. The copy's own comment
(`SuggestionEmptyState.tsx:23-25`) claims the horizon is "the outer bound rather than a hope."
For a non-English creator it is neither — it is a promise the system cannot keep, repeated daily.

This matters more than it sounds because the FE already assumes these creators exist: the Meera
consent/chat language on this same page defaults to **`hi-IN`** (`src/pages/creator-copilot.tsx:55,75,92`).
We ship a Hindi-first creator surface with an English-only tagger behind it.

**Related defect, same mechanism — substring matching produces confident false positives on both
sides.** Because `lower.contains(key)` is unanchored:
- `"onam"` matches inside **"Sonam"** — and the trend source is Indian *entertainment* headlines
  (Q10), where "Sonam Kapoor" is routine. Any such headline is tagged
  `festive, celebration, tradition, family` (taxonomy `onam` mapping) and typed **SEASONAL** with a
  21-day window (`campaign-rulebook.json` SEASONAL `typical: 21`).
- `"holi"` matches inside **"holiday"**.
- `"eid"` matches inside **"Heidi"**.
The identical keyword table and identical `lowered.includes(k)` logic is duplicated inside the n8n
tagger node (`trendspark/n8n/trend-pull-workflow.json`, node "Theme Tagger + row builder"), so the
bug applies to trends *and* captions.

---

## Q9 — Brand-new creator with very few posts, or image posts with no captions

**They get `pending_tagging` — the same "your first idea lands by tomorrow morning" card — and it
never resolves.**

`CreatorCaptionSyncJob.persistIfNew` drops any item with no caption before it is ever stored:

```java
// CreatorCaptionSyncJob.java:273-276
private boolean persistIfNew(String creatorProfileId, InstagramMediaResponse.MediaItem item) {
    if (item.caption() == null || item.caption().isBlank()) {
        return false;
    }
```

So an image-only account writes **zero** `creator_captions` rows → nothing `PENDING` for
`CreatorThemeTaggingJob` → `theme_tags` stays NULL → `CreatorNudgeService.java:106-110` →
`pending_tagging` forever. Identical outcome to Q8, different cause, same misleading copy.

A creator with very few posts is a matter of degree, and the threshold makes it harsh: the co-pilot
requires a theme **overlap of ≥ 2** (`CreatorCopilotProperties.java:30`, `CreatorNudgeService.java:123`).
One post mentioning `gym` yields `{strength, power, discipline, energy}` — enough to clear a 2-overlap
*if* an active trend shares two of them. One post mentioning `easy recipes` yields
`{family, comfort, authenticity}`, which shares **nothing** with entertainment-derived trend themes.

Note also that `theme_tags` is **union-only and never pruned** (`CreatorThemeTaggingJob.java:132-134`).
A creator who pivots niche keeps their old themes forever, so matching drifts further from the truth
the longer an account lives. There is no eviction and no recency weighting anywhere.

---

## Q10 — The NewsAPI request, and the three creators on one morning

The request, verbatim from `trendspark/n8n/trend-pull-workflow.json`, node **"NewsAPI top-headlines (IN)"**:

```
url:   https://newsapi.org/v2/top-headlines
query: country  = in
       category = entertainment
       pageSize = 20
```

**The category is `entertainment`. It is a hard-coded string literal in the workflow JSON. It is not
per-creator, not per-niche, and not parameterised in any way** — there is no expression, no loop over
creators, no second NewsAPI node with a different category. The workflow's own normalizer explicitly
throws the category away:

```js
// node "Normalize raw → {text,source,category}"
out.push({ json: { text: String(a.title), source: 'news',
                   category: '' /* general top-headlines: no fixed niche (Priya) */ } });
```

Because `category` is set to `''` for **all three** sources (news, tmdb, youtube), the tagger's
`NICHE_TO_THEMES` table — the only niche-aware structure in the pipeline — is **dead code at
runtime**: `if (category && NICHE_TO_THEMES[category])` in node "Theme Tagger + row builder" can
never be true. Themes come **only** from English keyword substrings in the headline text.

There is no per-creator anything, anywhere. One global pool of trends; `TrendRepository.java:14`
selects by `expiresAt` only; `CreatorNudgeService.java:112-121` scores every active trend for every
creator against one flat 40-word emotional vocabulary (`theme-taxonomy.json` `themes`).

**The three creators, same morning, assuming everything above were switched on and the table full:**

- **Fitness creator.** `theme_tags` from `gym`/`workout`/`fitness` captions ≈
  `{strength, power, discipline, energy, health, fitness}`. A Bollywood **action-film** headline tags
  `action, strength, energy, power` (taxonomy `action film`). Overlap = `strength, energy, power` = **3 ≥ 2**.
  So: **they get a suggestion — a Salman Khan action-film release** — and the fallback copy tells them
  "There's a trend around \"<that headline>\" that fits your strength niche"
  (`CreatorNudgeService.java:222-227`). Technically a match. Actually relevant to a fitness creator?
  Tenuous at best — it matched on the abstract word *strength*, not on fitness.
- **Finance creator.** **They get nothing, ever, by construction.** The 40-theme controlled vocabulary
  (`theme-taxonomy.json` `themes`) is: strength, action, energy, family, festive, light, tradition,
  pride, victory, beauty, glow, self-care, health, discipline, elegance, masculinity, femininity,
  power, celebration, joy, devotion, spirituality, luxury, comfort, wellness, fitness, confidence,
  style, glamour, radiance, purity, togetherness, heritage, authenticity, innovation, youth, vitality,
  calm, peace, resilience. **There is no finance, money, business, markets, investing, tech or
  education theme.** No keyword maps to one, and the category we query is `entertainment` anyway. A
  finance creator's captions produce an empty theme set → `pending_tagging` forever (Q8's outcome).
- **Food creator.** `easy recipes`/`healthy recipes` give `{family, comfort, authenticity, health,
  wellness}`. Entertainment headlines give `{style, glamour, celebration, joy, action, strength,
  energy, power, festive, tradition, beauty, …}`. The realistic overlap is 0–1, below the threshold of
  2 (`CreatorNudgeService.java:123`) → `no_suggestion_today` almost every day. On a festival-headline
  day they might pick up `festive`+`family` and get a Diwali card — which is the calendar, not the news.

**Does the implementation deliver "news-driven trends matched to a creator's category"? No.**
It delivers *Indian entertainment headlines, matched to a creator's emotional adjectives*. The word
"category" is absent from the runtime path: the one niche-mapping table exists and is unreachable
(`category: ''`), the news query is one fixed vertical for everyone, and the matching key is a
40-word mood vocabulary with no vertical coverage beyond film, festivals, cricket, beauty, fashion
and fitness. Two of your three example creators are structurally unservable.

Supporting note: `campaign-rulebook.json` claims at its `notes[0]` that "This JSON is read by
Vikram's campaign-type assignment logic in Spring Boot." **It is not read by any Java code** —
`grep -rn "campaign-rulebook"` over `influora-api/src/main/java` matches only a javadoc comment in
`.../domain/enums/TrendCampaignType.java:4`. The real logic is hand-duplicated in the n8n node and
again in `influora-ai/app/prompt/trend_tag.py:61`. Three copies, one file claiming to be the source
of truth, and the file itself is inert.

---

## Q11 — Region and language of the trends; the non-India creator

**Region: hard-coded `'IN'` on every row, and never filtered on afterwards.**
- The row builder writes `region: 'IN'` as a literal (node "Theme Tagger + row builder", the `out.push`
  block). The within-run dedup key is likewise `` `IN|${detected}|…` ``.
- All three source calls are India-pinned: NewsAPI `country=in`, TMDB `region=IN&language=en-IN`,
  YouTube `regionCode=IN`.
- `db/migration/V51__trendspark.sql:8` even defaults the column: `region VARCHAR(8) NOT NULL DEFAULT 'IN'`.
- `Trend.getRegion()` exists (`.../domain/entity/Trend.java:79-81`) and **has no caller in the
  suggestion path.** `TrendRepository.findActive` (`TrendRepository.java:14`) has no region predicate,
  and `CreatorNudgeService.java:112-121` does not read it.

**Language: English by construction on the output side, mixed on the input side.**
- TMDB is requested as `language=en-IN` → English titles.
- The `trend_text` is stored raw and surfaced raw. The fallback copy quotes it verbatim
  (`CreatorNudgeService.java:226-227`) and the card prints it unmodified
  (`src/components/creator/copilot/DailySuggestionCard.tsx:93`).
- The AI phrasing request carries **exactly three fields and no language**:
  `SuggestionRequest(creator_profile_id, theme_matched, trend_text)`
  (`.../integration/ai/dto/CreatorSuggestionAiDtos.java:27-30`). `grep -n "language" influora-ai/app/prompt/creator_suggestion.py
  influora-ai/app/routes/creator_suggestion.py` → **no hits.** So the model has no way to answer in
  Hindi, and will not.
- Meanwhile the same page defaults the creator's Meera language to `hi-IN`
  (`src/pages/creator-copilot.tsx:55`). We know the creator's language, store it in
  `creator_agent_preferences.creator_language`, read it on this very page — and do not pass it to the
  one AI call that writes creator-facing copy.

**A creator whose audience is not in India** gets Indian entertainment headlines — Bollywood releases,
Indian festival coverage, IPL — with no region gate to exclude them, and English-only phrasing. There
is no non-IN ingest path to switch on: `region` is a literal, not a parameter. For a diaspora or
international creator the feature is not degraded, it is simply pointed at the wrong country with no
lever to change it.

---

## Q12 — Five opens in one day: same card or different?

**Same card, every time, and this is correct.** Two independent mechanisms agree:
- Backend: the idempotent read at `CreatorNudgeService.java:98-103` returns the identical persisted
  row, with no re-scoring and no AI call. Pinned by `CreatorNudgeServiceTest.java:163`.
- Frontend: `staleTime: Infinity` on a day-keyed query (`src/hooks/useDailySuggestion.ts:158,85,130`),
  so most of the five opens never hit the network at all.

This is the right product behaviour for something branded "your daily idea" — a card that reshuffled
on refresh would train creators to reroll instead of act, and would multiply AI spend by the refresh
count. Dismiss/acted are handled client-side via a per-day sessionStorage marker
(`useDailySuggestion.ts:98-118,165-172`) while the server deliberately keeps returning `'ready'`
(`.../web/CreatorCopilotController.java:38-41`), so a dismissed card stays collapsed within the
session and reappears in a new tab. That is a defensible Tier-1 choice, not a bug.

Two smaller notes, both cosmetic:
- The day boundary is only re-evaluated on mount/render, not on a timer
  (`useDailySuggestion.ts:77-83`) — a tab left open overnight keeps yesterday's card. Documented and
  accepted.
- `expiresAt` is computed on every read (`CreatorNudgeService.java:254,259-267`) and shipped on the
  wire, but **nothing renders it** — `DailySuggestionCard.tsx` prints only `theme`, `headline`,
  `contentIdea` (:87,92,93). Dead field.

---

## Q13 — Can a creator see a suggestion about something already stale? Yes.

Trace, in `trendspark/n8n/trend-pull-workflow.json`, node "Theme Tagger + row builder":

1. `campaign_type` is derived from **keyword substrings in the headline** (`deriveCampaignType`), with
   a final `return 'EDUCATIONAL'` default for anything unmatched.
2. `peak_window_days = raw.peakWindowDays ?? CAMPAIGN_RULES[campaign_type].typical`, where the typicals
   are `PRIDE: 1, HYPE: 3, SEASONAL: 21, EDUCATIONAL: 30` — matching
   `influora-api/src/main/resources/trendspark/campaign-rulebook.json`
   (`HYPE.peak_window_days.typical: 3`, `SEASONAL: 21`, `PRIDE: 1`, `EDUCATIONAL: 30`).
3. `const expires = new Date(now.getTime() + peak * 24 * 60 * 60 * 1000);` and
   `expires_at: fmtDateTime(expires)`.

**`now` is the ingest timestamp. There is no event date anywhere in the calculation.** The
`peak_window_days` in the rulebook is described as the window around the *event* ("Big movie release",
"Festival", "Cricket victory"), but the implementation applies it from the moment we happened to
scrape a headline. `detected_date` is likewise `fmtDate(now)`. TMDB's `release_date` **is** captured
during normalisation — `detected_extra: { release_date: m.release_date || null }` — and is then
**silently discarded**: the tagger reads only `raw.text`, `raw.source`, `raw.category`, and
`detected_extra` never reaches the row. The one piece of real event-date data in the pipeline is
thrown away.

Consequences a creator can actually hit:
- A headline **reporting** a finished event — "Diwali sales cross ₹X", "India's World Cup win one
  month on", "Oscars recap" — matches `diwali` / `world cup` keywords, is typed `SEASONAL` or `PRIDE`,
  and becomes an active trend for 21 days (SEASONAL) from the day we scraped it. The creator is told
  to post about Diwali three weeks after Diwali.
- The `EDUCATIONAL` default is the worst case: anything the keyword rules do not recognise falls
  through to `EDUCATIONAL` with **`typical: 30`**, so an unclassified entertainment headline stays
  "active" and suggestible for a **month**.
- The `onam`-inside-`Sonam` false positive from Q8 lands precisely here: a routine celebrity headline
  becomes a 21-day "festive/celebration/tradition" SEASONAL trend.
- The FK breakage in Q3 makes this worse operationally — once the purge starts failing, the only thing
  keeping expired rows out of a creator's view is the `expiresAt > now` filter in
  `TrendRepository.java:14`, with no backstop.

And when the trend does surface, the creator is shown the **raw scraped headline, verbatim, with no
safety or freshness filter**:

```java
// CreatorNudgeService.java:223-227
"There's a trend around \"%s\" that fits your %s niche — a quick post while"
        + " it's hot could land well.",
trend.getTrendText(), theme
```

`trend_text` on the AI path is at least prompt-wrapped (`influora-ai/app/prompt/creator_suggestion.py`
docstring, `wrap_untrusted`). **On the fallback path there is no filter of any kind** — it is
`String.format` straight onto an Indian-entertainment-category headline. Entertainment headlines
include deaths, arrests, lawsuits and scandals. The fallback path is exactly what runs whenever
influora-ai is unreachable (`CreatorNudgeService.java:141-145`). So the single most likely
failure mode of the AI leg is also the one with no content control, and it tells the creator a
celebrity's death is "hot" and worth a quick post.

---

## Things I checked that are genuinely fine

- **AI failure handling** (Q6) — every failure mode returns null and degrades to a template; the
  creator never sees an error from the AI leg. `CreatorSuggestionAiClient.java:103-176`,
  `CreatorNudgeService.java:201-213`.
- **Idempotent same-day read** (Q12) — correct, cheap, and correctly test-pinned.
  `CreatorNudgeService.java:98-103`; `CreatorNudgeServiceTest.java:163`.
- **IDOR discipline on dismiss/acted** — `requireOwnedSuggestion` resolves by
  `findByIdAndCreatorProfileId` and returns an identical 404 for "missing" and "not yours"
  (`CreatorNudgeService.java:192-199`), and identity always comes from the authenticated principal,
  never the path (`.../web/CreatorCopilotController.java:45,57,65`). Test-pinned at
  `CreatorNudgeServiceTest.java:595,652,703`.
- **`theme` is server-derived, never AI-echoed** — `CreatorNudgeService.java:130,242-250`, request DTO
  carries no theme back (`CreatorSuggestionAiDtos.java:33-35`). Test-pinned at
  `CreatorNudgeServiceTest.java:382`.
- **No caption text reaches any model** — the tagger is pure Java keyword matching
  (`ThemeMatchService.java:98-120`) and the AI request has exactly three fields with no
  `caption_snippet` (`CreatorSuggestionAiDtos.java:27-30`).
- **Fail-closed taxonomy load** — a missing/corrupt `theme-taxonomy.json` yields empty sets and
  silence rather than a boot failure or garbage suggestions (`ThemeMatchService.java:39-53`).
- **`TrendIngestProperties` is registered** in `@EnableConfigurationProperties`
  (`InfluoraApiApplication.java:69`), so it will not repeat the class of boot failure catalogued at
  `InfluoraApiApplication.java:75-100` — it is inert (Q4), not a boot risk.
- **Jobs are resilient** — per-creator and per-item try/catch in both batch jobs
  (`CreatorCaptionSyncJob.java:236-251,253-265`, `CreatorThemeTaggingJob.java:86-113`), ShedLock on
  both (`CreatorCaptionSyncJob.java:89`, `CreatorThemeTaggingJob.java:70`), and the connect-triggered
  sync is `@Async` + swallow-everything (`CreatorCaptionSyncJob.java:156-193`).
- **`shown_day` timezone alignment holds in the shipped config.** `DATE(shown_at)`
  (`V20260721140000__creator_nudge_log.sql:49`) is evaluated in the DB session timezone while the app
  reads `startOfUtcDay()` (`CreatorNudgeService.java:269-271`). These agree only because the `mysql:8.0`
  container sets no `TZ` and no `--default-time-zone`
  (`deploy/utho/docker-compose.utho.yml:79-97`), so the server timezone is UTC, and the JDBC URL pins
  `serverTimezone=UTC` (`docker-compose.utho.yml:128`). Correct today; a latent coupling worth a
  comment, since setting `TZ=Asia/Kolkata` on the DB container would open a 5.5-hour window in which
  the read misses an existing row and the insert then 500s via the Q5 path.
- **`CreatorNudgeServiceTest` is real coverage, not theatre.** 34 tests, 0 failures, 0 skipped
  (verified run). It uses `verifyNoInteractions` rather than `anyString()`-based `never()` verifies and
  says why (`CreatorNudgeServiceTest.java:72-79`), and it **refuses** to write a test that would have
  falsely greened the unreachable catch, documenting the live defect instead (:57-70). That is the
  right call and it is how the Q5 finding surfaced.
- **The pre-connect preview is honestly labelled** — "Preview" badge plus an explicit footer
  disclaimer (`CopilotPreviewCard.tsx:96-99,113-115`); it is never presented as a real suggestion.

## What I could not verify

- Whether the live Utho/Hostinger `.env` actually sets `CREATOR_COPILOT_ENABLED`, `TREND_INGEST_ENABLED`
  or the three data-source keys. Compose defaults are `false`/blank, but the real `.env` is on the VPS
  and is not in the repo. (Independent of this: `TREND_INGEST_*` binds to nothing — Q4.)
- Whether the `trends` table on the live DB is in fact empty, and whether `creator_nudge_log` has any
  rows. No DB access from here. The Q3 FK breakage is a certainty in schema terms but I have not
  observed the n8n DELETE failing.
- Whether the Snapsby n8n instance on the shared Utho box has this workflow imported and activated
  out-of-band. The committed JSON is `"active": false` and no compose file deploys it; an operator
  could have imported it manually.
- The actual language distribution of NewsAPI `country=in&category=entertainment` responses. No key,
  no live call made. TMDB is explicitly `en-IN`; YouTube titles are whatever the uploader wrote.
- Whether the four `@SpringBootTest` classes boot this wiring. Per the standing repo constraint they
  need Docker/Testcontainers and are skipped locally, so I have no context-level proof that
  `CreatorCopilotProperties`/`TrendIngestProperties` bind against the real `application.yml` at boot.
  `CreatorCopilotConfigWiringTest` was not run in this audit.
- Whether an operator has hand-populated `creator_profiles.theme_tags` for any account, which would
  bypass the tagging jobs being off.
