# Theme Taxonomy Expansion — Implementation Draft & Changes Log

**Author:** Vikram (Backend)
**Date:** 2026-07-22 (revised same day per Priya's ruling)
**Status:** REVISED per Priya's CHANGES-REQUIRED ruling (`wiki/build/theme-taxonomy-priya-review.md`). All 5
requested changes applied in this pass. Going to Kavya (QA) + Meera (build verify) next.
**Inputs read:** `wiki/build/theme-taxonomy-expansion-strategy.md` (Nisha), `wiki/build/theme-taxonomy-seo-validation.md`
+ `wiki/build/theme-taxonomy-p0-keywords.json` (Aditya), the real `theme-taxonomy.json` (v1.0), the code that
actually consumes it (`ThemeMatchService.java`, `CreatorNudgeService.java`, `CreatorThemeTaggingJob.java`,
`trend_tag.py`, `creator_suggestion.py`, `theme-tagger.js`), and Priya's ruling.
**Deliverables (now three files, split per Priya's ruling — see §0):**
- `wiki/build/theme-taxonomy-keyword-patch-DRAFT.json` — **Tier-1, ships now.** The entire shippable surface of this
  initiative: the +20 keyword patch, isolated as its own standalone PR-ready file.
- `wiki/build/theme-taxonomy-expansion-DRAFT.json` — reference draft showing the post-patch state of the real file's
  `themes[]` / `niche_to_theme_mappings` / `keyword_to_theme_mappings`. No longer contains `india_events`.
- `wiki/build/india-events-taxonomy-DRAFT.json` — **Tier-2, inert, parked.** The former `india_events` block,
  physically split into its own file. No consumer exists. Gated on Priya's sign-off before any Tier-2 code.

---

## 0. Priya's ruling — the split, and confirmation all 5 changes were applied

Priya's ruling (`wiki/build/theme-taxonomy-priya-review.md`) was **CHANGES-REQUIRED**, split decision:
- **GREEN** — ship the `keyword_to_theme_mappings` +20 patch now, on its own, after fixing the `"fashion"` bug.
- **CHANGES-REQUIRED** — `india_events` data is good, but placement/scope were wrong: physically separate file,
  Tier-2, no consumer, gated on her sign-off.

**All 5 requested changes applied in this revision pass:**

1. **Split done.** The `india_events` block (65 tags + groups + gap_candidates, verbatim content, zero data changes)
   moved out of `theme-taxonomy-expansion-DRAFT.json` into the new `wiki/build/india-events-taxonomy-DRAFT.json`.
   The main draft file no longer contains an `india_events` key at all (verified: `'india_events' in d` → `False`).
2. **`"fashion"` off-vocab bug fixed.** `"outfit ideas"` now maps to `["style", "glamour", "confidence"]` — all
   three are members of the closed 40-item `themes[]` vocabulary. (Previously `["style", "fashion", "confidence"]`,
   where `"fashion"` is not in-vocab and would have been silently dropped by `ThemeMatchService`'s
   `knownThemes.contains(theme)` fail-closed guard.) Exact word choice remains Nisha/Aditya's content call per
   Priya — flagged for their sign-off, not a blocker for QA/build-verify.
3. **Keyword patch isolated as its own standalone deliverable.** `wiki/build/theme-taxonomy-keyword-patch-DRAFT.json`
   contains ONLY the merged `keyword_to_theme_mappings` layer (37 pre-existing + 20 new = 57 entries, fashion bug
   fixed), with its own apply-instructions, ready to become its own PR against the real file — independent of
   `india_events` and everything else in this initiative.
4. **`india-events-taxonomy-DRAFT.json` confirmed inert and labeled.** Top-level `_status: "TIER-2 — REFERENCE-ONLY.
   No consumer exists; not loaded by any service."` plus an explicit `_gate` field naming the Tier-2 prerequisites
   (niche_alias mapping, real festival-priority-scores.json, region schema proposal, JPA/Flyway translation) and
   stating `CreatorNudgeService` stays frozen. No service, table, or endpoint reads this file.
5. **Stale "45 themes" note fixed → 40.** `theme-taxonomy-expansion-DRAFT.json`'s `_themes_change_note` previously
   read `"UNCHANGED (45 -> 45)"`; corrected to `"UNCHANGED (40 -> 40)"` with an explanatory note that the array has
   40 entries and the old "45" was a stale header claim, not the real count. (Note: this fixes the DRAFT's internal
   note only — the real `influora-api/.../theme-taxonomy.json` header comment was not touched, per the instruction
   to leave the real file alone; that one-line real-file fix is still pending whenever the file is next opened for
   the Tier-1 PR.)

**One correctness fix beyond the 5, caught during revision:** the original draft's keyword-mapping count note said
"36 -> 56". Verified against the live real file — `keyword_to_theme_mappings` actually has **37** pre-existing
entries, not 36 (recounted directly from `influora-api/src/main/resources/trendspark/theme-taxonomy.json`). The
correct patch size is **37 -> 57** (+20). Fixed in both `theme-taxonomy-expansion-DRAFT.json` and the new
`theme-taxonomy-keyword-patch-DRAFT.json`. This was a pre-existing miscount in the prior draft, not something Priya
flagged — surfaced here for accuracy since Kavya/Meera will check counts against the real file next.

**Blocking bug fixed 2026-07-22, flagged by Kavya + Meera on the first submission of the keyword patch:** a stray
`"_comment_below"` key was sitting *inside* the `keyword_to_theme_mappings` object (between `"award show"` and
`"outfit ideas"`), as a plain string value rather than a `string -> array-of-strings` entry. `ThemeMatchService`
deserializes that object as `Map<String, List<String>>` (via its `TaxonomyFile` record); a string value there
throws Jackson's `MismatchedInputException`, which is caught by the blanket `catch (IOException)` in
`loadTaxonomy()` — and that catch block fail-closes the **entire** taxonomy (both `knownThemes` and
`keywordToThemeMappings` reset to empty), silently disabling all theme matching app-wide, not just this patch.
Fixed by moving the note out of the typed map into a new top-level sibling field, `_notes` (safely ignored by
`TaxonomyFile`'s `@JsonIgnoreProperties(ignoreUnknown = true)`, same as the existing `description`/`notes` fields).
`keyword_to_theme_mappings` now contains ONLY the 57 string -> array-of-strings entries — verified by parsing the
draft file directly (`json.load` + type-check every key/value). `_apply_instructions` updated to say the
copy-verbatim step is now safe. Re-submitted to Kavya/Meera for another pass.

---

## 1. What I found before drafting anything

Nisha's and Aditya's docs both target `theme-taxonomy.json`, but they're solving different problems on top of
a codebase neither doc fully accounts for. Before merging, I traced every place that actually reads the file:

| Consumer | Reads | Behavior |
|---|---|---|
| `ThemeMatchService.java` (Java) | `themes[]`, `keyword_to_theme_mappings` | Loads once at `@PostConstruct`. `score()` = raw overlap count between `trend.themesJson` and `brand/creator.theme_tags`, both restricted to `themes[]`. `themesForText()` keyword-matches captions, fail-closed to `themes[]` membership. |
| `CreatorNudgeService.java` (Java) | via `ThemeMatchService` | Picks the best-scoring active trend for a creator, extracts ONE matched theme string, sends it to the AI as `theme_matched`. |
| `CreatorThemeTaggingJob.java` (Java, nightly) | via `ThemeMatchService.themesForText` | Tags creator captions against `keyword_to_theme_mappings`, unions results into `creator_profile.theme_tags`. |
| `influora-ai/app/prompt/trend_tag.py` (Python) | **verbatim hardcoded copy** of `themes[]` as `THEME_SET` | LLM recovery tagger may ONLY select from this set; anything else is dropped. Comment in the file: *"If either JSON changes, re-sync THEMES ... (and theme-tagger.js)"* — **manual, not CI-enforced.** |
| `influora-ai/app/routes/creator_suggestion.py` (Python) | imports `THEME_SET` from `trend_tag.py` | `_normalize_theme()` fails closed to `""` for anything not in `THEME_SET` before it's allowed near the phrasing prompt. |
| `trendspark/n8n/theme-tagger.js` | **verbatim hardcoded copy** of `themes[]` | n8n's deterministic daily tagger. **CI-enforced**: `trendspark/n8n/tagger-sync.test.js`, gated by `.github/workflows/trendspark-tagger-sync.yml`, asserts this array exactly equals the JSON's `themes[]`. |

**The load-bearing fact:** `themes[]` (the 40-item emotion vocabulary — not 45, I recount below) is a **closed
vocabulary embedded in three places**, one of which (n8n) is CI-drift-checked and one of which (Python) is not.
Every downstream consumer — brand↔trend matching, creator↔trend matching, caption tagging, and the AI phrasing
call's input validation — depends on a value being a *member of this exact set*, not on any hierarchy, region,
date, or priority score. Nisha's and Aditya's docs both undersell how tightly locked this is.

(Side note: the real file's header comment says "45 themes" but the array has 40 entries — pre-existing, not
introduced by this draft. Worth a one-line fix whenever the file is next touched for real.)

---

## 2. The architectural tension (Nisha vs. Aditya) — restated precisely

- **Nisha's design** treats `theme-taxonomy.json` as the place to add ~65 NEW tags (`diwali`, `tamil-nadu`,
  `monsoon`, `ipl`, ...) organized into 6 groups, each carrying `activates` (content-type array) + `priority_scores`,
  consumed by a **not-yet-built** `GET /api/creator-copilot/active-trends` endpoint and multi-tag activation logic.
  None of this exists in the current pipeline — it's a genuinely new matching axis (calendar/geography), not an
  extension of the emotion-theme overlap score.
- **Aditya's design** correctly diagnoses that the *existing* 40 emotion-themes are useless as SEO keywords, and
  proposes (a) a small P0 patch — 20 keyword phrases into the *existing* `keyword_to_theme_mappings`, which is
  genuinely safe and already-wired (his own doc verifies it against `ThemeMatchService.themesForText()`) — and
  (b) a much larger P1 `seo_keywords` structural layer that, like Nisha's `india_events`, **nothing currently reads**.

Neither doc is wrong; they're proposing two different unconsumed structures on top of one heavily-consumed one.
My draft keeps them separate rather than picking a winner, because picking a winner is Priya's call, not mine.

---

## 3. What the drafts actually do (revised — now three files, see §0)

**`wiki/build/theme-taxonomy-keyword-patch-DRAFT.json` — the Tier-1 shippable, standalone:**

1. **`keyword_to_theme_mappings` — 37 → 57 entries** (Aditya's 20 P0 keywords appended verbatim, plus one fix).
   Low-risk and, per Aditya's own verification, requires **no code change** — `ThemeMatchService.themesForText()`
   already keyword-matches this map. The one real bug in his batch is now FIXED (Priya's ruling change 2):
   `"outfit ideas"` shipped as `["style", "fashion", "confidence"]` in Aditya's source — **`"fashion"` is not in the
   closed `themes[]` vocabulary**. `ThemeMatchService`'s `knownThemes.contains(theme)` guard would have silently
   dropped it, so it wouldn't have broken anything, but the keyword would only ever have contributed 2 themes
   instead of the advertised 3. This patch now ships `["style", "glamour", "confidence"]` — all in-vocab. Exact
   word choice remains Aditya/Nisha's content call per Priya; flagged for their sign-off, not a blocker.
2. **`themes[]`, `niche_to_theme_mappings` — not part of this file at all.** This patch touches nothing else.

**`wiki/build/theme-taxonomy-expansion-DRAFT.json` — reference draft of the post-patch real-file state:**

1. **`themes[]`, `niche_to_theme_mappings` — untouched.** Zero risk to the live matching pipeline.
2. **`keyword_to_theme_mappings`** — same 37 → 57 content as the standalone patch file above (kept in sync here
   for reference/context; the actual PR should be cut from the standalone patch file, not this one).
3. **`india_events` — REMOVED from this file, moved to its own file (Priya's ruling change 1).** See below.

**`wiki/build/india-events-taxonomy-DRAFT.json` — Tier-2, inert, physically separate (NEW per Priya's ruling):**

1. **65 tags, additive, inert.** Nisha's 6-group hierarchy (26 group buckets → I
   deduplicated to 65 unique tag ids, matching her stated "~65"), each tag as an object with `id`, `category`,
   `region`, `activates`, `priority_scores` (**placeholders — see below**), and `seo` (Aditya's search-volume data
   folded in per-tag where he supplied it, rather than mirrored in a second parallel structure — my organizational
   call, not an architecture decision, since it doesn't change what anything reads). Plus 8 `gap_candidates` Nisha
   explicitly said not to add yet.
2. **Priority scores are MY estimates, not Nisha's.** Her own doc (§6) says she owns
   `wiki/processes/festival-priority-scores.json` and it doesn't exist yet. Every `priority_scores` value in the
   file is illustrative only — flagged inline via `_priority_scores_are_placeholders`. Do not ship these.
3. **Naming reconciliation flagged, not resolved:** `ganesh-chaturthi` vs `ganpati`, `raksha-bandhan` vs `rakhi`
   (rakhi has *higher* search volume — 823k vs 550k — directly contradicting Nisha's "avoid colloquial" naming
   rule), and `karwa-chauth` vs `karva chauth` spelling. These are Nisha/Aditya sign-off items, not Priya-level.
4. **Labeled inert and gated, per Priya's ruling change 4.** Top-level `_status`/`_gate` fields state plainly: no
   consumer exists, nothing loads this file, and no Tier-2 code may be written against it until its prerequisites
   (niche_alias mapping, real priority-scores file, region schema proposal, JPA/Flyway translation) clear Priya's
   sign-off. `CreatorNudgeService` stays frozen until then.

---

## 4. OPEN QUESTIONS for Priya — RESOLVED, see her ruling

**Status: all three questions below are now answered.** Priya's ruling document
(`wiki/build/theme-taxonomy-priya-review.md`) is the binding answer to each — summarized here, full reasoning there.
Original questions kept verbatim below for the record.

**Ruling summary:**
- **Q1 → Option B, separate axis.** `india_events` never enters `themes[]`. Confirmed: it now lives in its own file.
- **Q2 → Two independent surfaces, neither built in Tier-1.** `CreatorNudgeService` is FROZEN — not touched by this
  initiative until a separately-scoped Tier-2 plan with its own Priya sign-off exists.
- **Q3 → Real prerequisite, Tier-2 scope, resolve by mapping not migration.** A `niche_alias` mapping table
  (Nisha+Aditya, Priya-approved) reconciles the vocabularies; region matching is pan-India-only until a scoped
  schema proposal clears Priya. Neither blocks the Tier-1 keyword patch, which touches no profile field.

<details>
<summary>Original open questions (as posed, for the record)</summary>


**1. Does `india_events` ever become part of the closed, CI-checked `themes[]` vocabulary, or does it stay a
fully separate second axis with its own scoring path?**
   - Option A: widen `themes[]` from 40 to 105 (40 + 65). Forces edits to `trend_tag.py` `THEME_SET` (manual sync
     today — real drift risk), `theme-tagger.js` (CI-enforced, so drift is at least caught), and re-validates every
     existing `theme_tags`/`themesJson` row still parses. `ThemeMatchService.score()`'s plain overlap-count can't
     express region-matching or priority-weighting, so this buys naming consistency but not Nisha's actual
     multi-tag/priority logic — that still needs new code regardless.
   - Option B: `india_events` stays a second, independent structure with its own new service, own DB table(s) for
     dated events (Nisha's `events_calendar` proposal), and its own scoring function that never touches
     `ThemeMatchService`. Cleaner separation, zero risk to the CI-checked pipeline, but means building a second
     matching subsystem from scratch (new Prisma-equivalent JPA entities/Flyway migration, new repository, new
     service, new controller endpoint, new tests) rather than "adding rows to a JSON array."

**2. If Option B: does the Creator Co-pilot's ONE-suggestion-per-day slot (`CreatorNudgeService.getSuggestion`,
DB-constraint-backed per-day cap) merge emotion-theme trend matches and india-event calendar matches into a single
ranked pick, or do these ship as two independent surfaces** (e.g., today's existing AI nudge stays exactly as-is,
and a new "upcoming festivals" widget is a wholly separate read path with its own cap/rate-limit)? This determines
whether `CreatorNudgeService` needs any changes at all, or whether it's frozen and a new service sits beside it.

**3. Regional/niche matching needs creator-profile schema Nisha's design assumes but doesn't exist.**
`CreatorProfile.java` has no `primary_region` (state enum) or `primary_niche`/`secondary_niches` fields today —
the closest proxies are `city` (free-text, not a state enum) and `categoriesJson` (niche list, but a *different*
17-value vocabulary — `fitness`/`supplement`/`apparel`/`saree`/`skincare`/`cosmetics`/`food`/`jewelry`/`fashion`/
`sports`/`athleisure`/`wellness`/`yoga`/`home`/`handloom`/`organic`/`luxury` — that doesn't line up with Nisha's
`activates` content-type strings, `home`/`beauty`/`fashion`/`finance`/`food`/`devotional`/`gifting`/`travel`/
`tech`/`comedy`/`education`/`civic-pride`/`shopping`/`entertainment`). Someone has to own reconciling these two
vocabularies (or mapping between them) and, if state-level regions are needed, a schema migration + backfill
strategy for existing creators with no region set. This is a prerequisite for Nisha's region-matching logic to
run at all, independent of Question 1/2's answer.

</details>

---

## 5. Worked example (as requested)

> **Diwali, Nov 8, 2026. A beauty creator with no explicit `diwali` tag today.**

**Today (live code, nothing in this draft changes this path):**
`CreatorThemeTaggingJob` has already tagged this creator's captions against `keyword_to_theme_mappings` — e.g. a
caption mentioning "glowing skin" or "bridal makeup" (Aditya's new keywords) tags them with `beauty`, `glow`,
`radiance`/`glamour`, `celebration`. Those land in `creator_profile.theme_tags`. Separately, if a `trend` row this
week has `themesJson` containing `["festive", "light", "celebration", "family", "tradition"]` (the existing
`"diwali"` keyword→theme mapping, already in v1.0), `ThemeMatchService.score()` counts the overlap with the
creator's `theme_tags`. If `celebration` is common to both, score ≥ 1, `CreatorNudgeService` picks it if it's the
best-scoring active trend, and the AI gets `theme_matched: "celebration"` — a real but generic emotion word, not
"Diwali" or anything festival-specific. **The creator is never told it's Diwali** — only that something
"celebration"-themed is trending.

**If `india_events` existed and were wired up (NOT true today, and explicitly NOT in scope for Tier-1 — this is
what a future Tier-2 build would add, per `wiki/build/india-events-taxonomy-DRAFT.json`):**
The beauty creator would be tagged `[beauty, festivals, diwali]` (or matched via the new `diwali` event row +
their `activates` intersection with `beauty`), the system would know the specific festival, its date window
(14-day pre-window per Nisha's `events_calendar` proposal, since `diwali` is Nov 8, tagging starts ~Oct 25), and
a `suggested_angle` like "festive GRWM / Diwali glow routine" could be generated — matching the `diwali` tag's
`priority_scores.beauty: 8` entry in `india-events-taxonomy-DRAFT.json`. **This is the gap the india_events
proposal exists to close** — today's system can say "something celebration-y is trending," a future Tier-2 build
would let it say "Diwali is in 12 days, here's a beauty angle for it." Getting from the first to the second
requires a separately-scoped Tier-2 plan clearing its prerequisites and Priya's sign-off (see §0/§4) and then real
service/schema code — the india-events file supplies the taxonomy data only, nothing else.

---

## 6. Does the co-pilot matcher or the AI prompt need code changes?

**As drafted (`keyword_to_theme_mappings` patch only, `wiki/build/theme-taxonomy-keyword-patch-DRAFT.json`): NO
code changes needed.** `ThemeMatchService.themesForText()` and the `CreatorThemeTaggingJob` nightly batch are
already generic over the contents of `keyword_to_theme_mappings` — Aditya verified this himself. The `"fashion"`
off-vocab issue noted in §3 is now fixed in the patch file. This is the entire Tier-1 deliverable and is GREEN to
ship per Priya's ruling.

**For `india_events` to do anything: YES, extensive new code, regardless of which side of the (now-resolved)
Question 1 was picked — and Priya picked Option B (separate axis, §0/§4).**
- New JPA entities + Flyway migration for `theme_taxonomy`/`india_events` and `events_calendar` (Nisha's schema
  sketch in her doc uses Postgres/Prisma DDL syntax — this codebase is Spring Boot + JPA + Flyway on what appears
  to be MySQL/Postgres via Spring `DataSource`, not Prisma; the DDL needs translating, not copy-pasting).
- A new service (parallel to `ThemeMatchService`) to load `india_events` and score region/date/priority matches —
  `ThemeMatchService.score()`'s plain overlap-count cannot express Nisha's priority-weighted, region-gated logic.
- `CreatorNudgeService` — per Priya's ruling, stays FROZEN; a sibling service is built instead, never a merge into
  the existing daily-nudge scoring path (see §0/§4, Q2 resolution).
- `influora-ai/app/prompt/creator_suggestion.py` and `trend_tag.py`: not affected — Priya picked Option B
  (separate axis), so `theme_matched` values do not become festival ids and `THEME_SET`/`THEMES` in `trend_tag.py`
  is not touched by this initiative. Festival-aware templating remains a Tier-2 nice-to-have, contingent on the
  daily nudge later consuming a festival signal as an input feature (Priya's ruling: "one direction of dependency,
  never a merge").
- New `CreatorProfile` fields / niche_alias mapping / region schema per Question 3's resolution — Tier-2
  prerequisites, not started, gated on Nisha+Aditya (niche_alias) and Priya-approval (any schema change).

**Estimated effort for Tier-2, if and when it is scoped:** in line with Nisha's own 3-5 day estimate for the
schema+API+tests portion, plus the prerequisite work in §0 item 4 (niche_alias table, real priority scores, region
schema proposal, JPA/Flyway translation) that must land first.

---

## 7. Before/after counts (revised — corrected 36→56 miscount to 37→57, see §0)

| | v1.0 (real file, live) | Tier-1 patch (`theme-taxonomy-keyword-patch-DRAFT.json`) | Tier-2 file (`india-events-taxonomy-DRAFT.json`) |
|---|---|---|---|
| `themes[]` | 40 (header says "45" — pre-existing doc/array mismatch, real file not touched by this pass) | not present in this file | n/a |
| `niche_to_theme_mappings` | 17 keys | not present in this file | n/a |
| `keyword_to_theme_mappings` | 37 keys (recounted directly from the live file — corrects the prior draft's "36") | 57 keys (+20, Aditya P0, fashion bug fixed) | n/a |
| `india_events` tags | 0 (doesn't exist in the real file) | n/a | 65 (inert, no consumer) |
| `india_events` gap candidates | — | n/a | 8 (explicitly not added) |

(`wiki/build/theme-taxonomy-expansion-DRAFT.json` mirrors the `themes[]`/`niche_to_theme_mappings`/
`keyword_to_theme_mappings` columns above as reference context — the standalone patch file above is the one to
actually cut a PR from.)

---

## 8. Recommended immediate action

**Ship now, standalone:** `wiki/build/theme-taxonomy-keyword-patch-DRAFT.json` — the 37→57 `keyword_to_theme_mappings`
patch — merged into the real file on its own PR. Low-risk, already-wired, no schema/code changes, `"fashion"`
off-vocab bug already fixed. Approved GREEN by Priya. Optional: get Nisha/Aditya's nod on the `"outfit ideas"` →
`"glamour"` word choice before merging (not a correctness blocker).

**Hold, Tier-2:** `wiki/build/india-events-taxonomy-DRAFT.json` lands as inert reference data only — no PR against
service code, no schema migration, no `CreatorNudgeService` changes — until a separately-scoped Tier-2 plan clears
its prerequisites and Priya's sign-off (§0 item 4).

**Next stop for this revision:** Kavya (QA) + Meera (build verify), per the task that produced this revision pass.
