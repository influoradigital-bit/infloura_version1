# Theme Taxonomy Expansion — CTO Architectural Ruling

**Author:** Priya (CTO)
**Date:** 2026-07-22
**Reviewing:** Vikram's `theme-taxonomy-implementation.md` + `theme-taxonomy-expansion-DRAFT.json`, Nisha's `theme-taxonomy-expansion-strategy.md`, Aditya's `theme-taxonomy-seo-validation.md`
**Verified against live code:** `ThemeMatchService.java`, `CreatorNudgeService.java`, `trend_tag.py`, `CreatorProfile.java`, `tagger-sync.test.js`, `trendspark-tagger-sync.yml`

---

## VERDICT

**Split decision — both parts actionable in one revision pass:**

- **GREEN** — the `keyword_to_theme_mappings` +20 patch (Layer 2). Ship it **now**, on its own, decoupled from everything else. One fix required first (the `"fashion"` off-vocab, below).
- **CHANGES-REQUIRED** — the `india_events` block. The *data* is good; its *placement and scope* are not. Four specific changes below. All are Tier-2 reference-data landing, **not** a build.

Vikram did exactly the right thing bringing the three open questions up instead of guessing. His draft is accurate — I verified every load-bearing claim against the source. The changes below are structural rulings, not corrections to his analysis.

---

## THE CANONICAL STRUCTURE

Two axes, **physically separate files**, not one file with two sections:

```
influora-api/src/main/resources/trendspark/
  theme-taxonomy.json          ← Axis 1: LOCKED emotion vocab. CI-drift-checked. 3-way embed.
    themes[]                     — untouched (40 items)
    niche_to_theme_mappings      — untouched
    keyword_to_theme_mappings    — +20 P0 keywords (Layer 2 bridge). SHIP NOW.
    notes

  india-events-taxonomy.json   ← Axis 2: NEW FILE. Calendar/region/priority. Tier-2 reference data.
    india_events { groups, tags[], gap_candidates }   — inert until a consumer is built
```

**Why two files, not one (this is the main change from the draft):** Vikram parked `india_events` inside `theme-taxonomy.json`. It's safe there (`@JsonIgnoreProperties(ignoreUnknown=true)` — verified), but it's the wrong home:

1. `theme-taxonomy.json` is a locked, triple-embedded, CI-drift-checked vocabulary. It should stay small and stable. Bolting a 65-tag second axis onto it makes every future `india_events` edit a diff against the file the `tagger-sync` CI job watches — noise, merge-conflict surface, and a standing invitation for someone to "while I'm in here" touch `themes[]`.
2. Separation of axes should be **physical**, not just a comment. The emotion vocab and the calendar are different data owned by different logic on different cadences. Different files.
3. When Tier-2 builds the `india_events` consumer, it loads its own file with clean ownership — it never has to reason about the locked vocab at all.

Cost to action: move one JSON block to a new file. Cheap. Do it.

---

## OPEN QUESTION 1 — folded into `themes[]`, or separate axis?

**RULING: Separate axis. `india_events` never enters `themes[]`. (Vikram's Option B.)**

- `themes[]` is a 40-word **emotion** vocabulary consumed by an overlap-**count** scorer. Festival ids are a categorically different thing — dated, regional, priority-weighted. Overloading one array with two semantics is a textbook taxonomy smell.
- Widening `themes[]` to 105 forces synchronized edits to three embeds (`trend_tag.py` manual, `theme-tagger.js` CI-checked, the JSON itself), re-validates every existing `themesJson`/`theme_tags` row, **and buys nothing** — `ThemeMatchService.score()` is still a plain overlap count that cannot express region, date, or priority. Pure cost, zero capability gain. I verified this: `score()` (lines 58-71) has no axis for any of it.
- Separate axis keeps the CI-checked pipeline at **zero** risk.

**But "separate axis" ≠ "build a second matching subsystem now."** That's the scope trap. See Q2.

---

## OPEN QUESTION 2 — merge the daily slot, or two surfaces?

**RULING: Two surfaces — and neither is built in Tier-1. `CreatorNudgeService` stays FROZEN.**

- The just-shipped Tier-1 co-pilot is a guidance pilot. A live `india_events` matching path is **Tier-2 scope**. For this pass, `india_events` lands as inert reference data (new file) and gets **no service, no table, no endpoint**.
- When it is built (Tier-2), the answer is **two surfaces, not a merged ranked pick**:
  - The daily AI nudge (`CreatorNudgeService.getSuggestion`) is emotion-theme trend-matching with a per-day cap and per-call AI spend (verified: idempotent-read-first cap at lines 98-103, AI call at 132).
  - The festival calendar is a deterministic, date-windowed lookahead with **no AI spend** and a different cadence.
  - Merging them into one scored pick would force a scoring function that reconciles overlap-count with priority-score — apples and oranges — and conflate two different value props and two different cost/rate-limit profiles.
- The clean seam for later: the daily nudge may eventually **consume a festival signal as an input feature** (e.g., boost a trend that coincides with an active festival window). The festival calendar remains its own read path. One direction of dependency, never a merge.

Net: `CreatorNudgeService` is not touched by this initiative until a separately-scoped Tier-2 plan exists.

---

## OPEN QUESTION 3 — region/niche schema gap

**RULING: Real prerequisite, Tier-2 scope, resolve by mapping not migration. Nothing here blocks the Layer-2 patch.**

Verified on `CreatorProfile.java`: `city` (free-text, len 100), `categoriesJson` (17-value niche list), `languagesJson`. No `primary_region` state enum, no `primary_niche`/`secondary_niches`. The gap is exactly as Vikram described.

Three sub-rulings:

1. **Niche vocabulary — reconcile TO the existing creator vocab, not away from it.** Nisha's `activates`/`priority_scores` use 14 content-type strings (`home`, `beauty`, `finance`, `devotional`, `gifting`, `civic-pride`, ...) that don't map to the 17-value `categoriesJson` vocab (`skincare`, `cosmetics`, `saree`, `handloom`, ...). The creator profile is the **source of truth** for what a creator is. We do not get to invent 14 new content-type strings that nothing in the DB can be. **Deliverable: a `niche_alias` mapping table** (`categoriesJson` value → `india_events.activates` bucket), owned by **Nisha + Aditya**, **Priya-approved**, produced **before** any matching code is written. This is a mapping problem, not a schema migration.

2. **Region matching — deferred, and do not build it on free-text `city`.** A state enum is a migration + backfill for every existing creator. That is its own schema-design proposal, Tier-2, gated on my sign-off. For Tier-1 / first Tier-2 cut, **pan-india-only matching is acceptable** (ignore region entirely). Never wire region logic onto free-text `city`.

3. Both (1) and (2) are Tier-2 prerequisites. Neither blocks shipping the Layer-2 keyword patch, which touches no profile field.

---

## EMOTION-THEMES vs SEO-KEYWORDS (Nisha's hierarchy vs Aditya's dual-layer)

**RULING: Not competing — different layers, both partly right. I accept the bridge, reject the parallel tree, accept the hierarchy-as-flattened-tags.**

- **Layer 1 — `themes[]` (emotion vocab):** stays the brand↔trend / creator↔trend matching vocabulary. This is the B2B revenue-matching axis. Locked, untouched. Aditya is correct that these are invisible to Google — and that's *fine*, because Google discoverability is not what this array is for.
- **Layer 2 — `keyword_to_theme_mappings` (the bridge):** real search phrases mapping **down** into the emotion vocab. This is the correct and only home for SEO-keyword-awareness, because it's already wired (`themesForText()`, verified) and it preserves the closed-vocab guarantee. Aditya's 20 P0 keywords go here. **Approved.**
- **Aditya's standalone `seo_keywords` block (P1, §5.2) — REJECTED as a separate top-level structure.** Nothing reads it; it's a second parallel keyword tree = two places to update = maintenance liability. Where SEO metadata *is* wanted, it rides **per-tag on `india_events`** — which is exactly what Vikram already did (folding Aditya's search-volume data into each tag's `seo` field). That was the right call. No third tree.
- **Nisha's 6-group hierarchy — ACCEPTED as the *shape* of `india-events-taxonomy.json`,** in the flattened tag-array-with-metadata form Vikram used (`{id, category, region, activates, priority_scores, seo}`). Nisha herself said the hierarchy is conceptual/documentation and the implementation may flatten (her §2 note). Concur.
- **Naming tension (rakhi vs raksha-bandhan, ganpati vs ganesh-chaturthi, karva/karwa) has a clean architectural answer that removes the debate as a blocker:** internal `id` = stable/formal (`raksha-bandhan`), search-facing alias = whatever ranks (`rakhi`), both resolving to one tag via the `seo.variant` field the draft already carries. This is standard canonical-id + alias. It means the SEO-vs-convention naming choice is **not** a Priya decision and doesn't gate anything — it's a content-governance sign-off (Nisha/Aditya/Tejas) on which string is `id` vs `alias`, with no architectural consequence either way.

---

## CHANGES REQUIRED (Vikram — one revision pass)

1. **Split the file.** Move the entire `india_events` block out of the DRAFT into a new file `influora-api/src/main/resources/trendspark/india-events-taxonomy.json`. `theme-taxonomy.json` receives **only** the +20 keyword patch.
2. **Fix the off-vocab `"fashion"`.** The real `theme-taxonomy.json` must never ship a theme string outside the 40-item vocab. `"outfit ideas"` → drop `"fashion"`, e.g. `["style", "elegance", "confidence"]`. Exact replacement is Nisha/Aditya's content call, but it must be in-vocab. (It's silently dropped today via `knownThemes.contains` — verified line 112 — so this is correctness/honesty, not a live bug.)
3. **Ship `theme-taxonomy.json` (+20 keywords) as its own PR now** — after change 2. Backward-compatible, zero-schema, already-wired. Independent of everything else in this ruling.
4. **`india-events-taxonomy.json` lands as labeled reference data.** Top-level `"_status": "reference-only — no consumer exists; not loaded by any service"`. Keep `_priority_scores_are_placeholders` flag. Placeholders are acceptable **only** because nothing reads them; Nisha's real `festival-priority-scores.json` must replace them before any consumer is built.
5. **Hygiene while `theme-taxonomy.json` is open:** fix the stale `description` — the array is **40** themes, not 45. Correct the draft's `_themes_change_note` too (says "45 -> 45"; should be 40).

---

## SCOPE FENCE — Tier-1 (now) vs Tier-2 (planned separately)

**Tier-1, ship now — clean additive win:**
- The +20 `keyword_to_theme_mappings` patch. That is the entire Tier-1 deliverable. Already-wired, zero-schema, backward-compatible.
- The `india-events-taxonomy.json` file landing as inert reference data (data only, no code).

**Tier-2, do NOT build on the pilot — needs its own scoped plan + my sign-off:**
- `india_events` consumer service, `events_calendar` table, `GET /api/creator-copilot/active-trends`, region matching, `CreatorProfile` schema changes, priority scoring, festival-aware AI templating. All of Nisha's §6/§9 checklist.
- **Prerequisites before any Tier-2 code:** (a) `niche_alias` mapping table [Nisha+Aditya, Priya-approve]; (b) real `festival-priority-scores.json` [Nisha]; (c) region schema proposal if state-level matching is in scope [Vikram, Priya-approve]; (d) the JPA/Flyway translation — Nisha's DDL is Postgres/Prisma, this codebase is Spring Boot + JPA + Flyway (verified — `CreatorProfile` is JPA `@Entity`), so it's a rewrite, not a copy-paste.

---

## SIGN-OFFS THIS RULING DOES NOT MAKE (not CTO-domain)

- Which festival spelling is `id` vs `alias` → Nisha/Aditya/Tejas (architecture is indifferent; canonical-id+alias absorbs either).
- `ipl` + `cricket` dedup, `rakhi` colloquial-exclusion vs SEO volume → Nisha content governance.
- The exact in-vocab replacement for `"fashion"` in `"outfit ideas"` → Nisha/Aditya (constraint: must be in the 40-item vocab).

---

**Status: Vikram to revise per the 5 changes above. Layer-2 patch is GREEN to ship immediately after change 2. `india_events` file lands inert. No Tier-2 code until its prerequisites and a scoped plan clear my sign-off.**
