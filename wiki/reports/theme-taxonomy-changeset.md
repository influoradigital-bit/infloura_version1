# Creator Co-pilot Theme-Taxonomy Expansion — Authoritative Change-Set Manifest

**Compiled by:** Tara (Reporting) | **Date:** 2026-07-22
**Purpose:** Single source of truth for Kabir's security gate. Maps everything the team produced across the taxonomy-expansion trail to what actually ships, what's parked, and what's still open. This is a manifest — pointers and status, not a paste of file contents.
**Branch reality (read this first):** Nothing in this initiative is applied to the real repo yet. All artifacts below are DRAFT files in `wiki/build/`. `git status --porcelain` on the three real target files (`influora-api/src/main/resources/trendspark/theme-taxonomy.json`, `trendspark/n8n/theme-tagger.js`, `trendspark/n8n/trend-pull-workflow.json`) is clean — confirmed at manifest time. This is a **documented, verified, ready-to-apply package**, not a committed change. Kabir is reviewing DRAFTs against live code, not a diff.

---

## 1. What Ships in Tier-1 (coordinated 3-file PR)

One PR, three files, landed together (CI requires this — see §4). Nothing here is applied yet; each row is DRAFT → real-file target.

| # | Real file (not yet edited) | DRAFT source | Owner | Change | Why |
|---|---|---|---|---|
| 1 | `influora-api/src/main/resources/trendspark/theme-taxonomy.json` | `wiki/build/theme-taxonomy-keyword-patch-DRAFT.json` | **Vikram** (Backend) | `keyword_to_theme_mappings`: **37 → 57 entries** (+20). `themes[]` (40) and `niche_to_theme_mappings` (17) untouched. | Aditya's SEO research found the existing 20+ high-volume India-creator search phrases ("bridal makeup" 301k/mo, "glowing skin" 135k/mo, "home workout" 201k/mo, etc.) had no keyword→theme bridge. This is the already-wired, backward-compatible bridge layer (`ThemeMatchService.themesForText()`) — zero schema change, zero code change. |
| 2 | `trendspark/n8n/theme-tagger.js` | `wiki/build/theme-taxonomy-n8n-patch.md` (Edit 1) | **Dev** (n8n owner) | Same 20 entries appended to the `KEYWORD_TO_THEMES` module object, verbatim values/order, after `'award show'`. | Required to keep `trendspark/n8n/tagger-sync.test.js` (CI: `.github/workflows/trendspark-tagger-sync.yml`) green — check **B2** asserts this module is a superset of `theme-taxonomy.json`'s `keyword_to_theme_mappings` with identical shared values. Without this, CI goes red the moment file #1 merges. |
| 3 | `trendspark/n8n/trend-pull-workflow.json` | `wiki/build/theme-taxonomy-n8n-patch.md` (Edit 2) | **Dev** (n8n owner) | Same 20 entries mirrored into the inline `code-theme-tagger` Code node's `jsCode` string (compact single-line style, matches existing paste convention). | Check **A2** requires this inline copy to be structurally identical (via `eval`) to `theme-tagger.js`'s module. Two n8n-side places must move together, not one. |

**Why these three and only these three:** `ThemeMatchService.themesForText()` (Java) is already generic over the contents of `keyword_to_theme_mappings` — no Java code change needed. All 20 new keywords resolve only to themes already inside the closed 40-item `themes[]` vocabulary (verified table in `theme-taxonomy-n8n-patch.md`) — no vocabulary widening, no re-validation of existing `theme_tags`/`themesJson` rows needed.

---

## 2. What's Parked Tier-2 (per Priya's ruling)

**Nothing in this section ships in the Tier-1 PR. No service, table, or endpoint reads any of it.**

| Item | Status | Detail |
|---|---|---|
| `india-events-taxonomy.json` (new file) | Lands as **inert reference data only**, DRAFT: `wiki/build/india-events-taxonomy-DRAFT.json` | 65 tags (28 festivals, 14 regions, 6 seasons, 10 events, 7 commercial) + 8 `gap_candidates` explicitly not added. Carries `_status: "TIER-2 — REFERENCE-ONLY. No consumer exists; not loaded by any service."` Physically separate from `theme-taxonomy.json` (Priya's ruling change 1) — the locked, CI-drift-checked emotion vocab must not take a 65-tag second axis as a diff-noise/merge-conflict risk. |
| Matching subsystem (`india_events` consumer service, `events_calendar` table, `GET /api/creator-copilot/active-trends`) | **Not built.** Explicitly out of scope for this pass. | Priya: `ThemeMatchService.score()` is a plain overlap count that cannot express region/date/priority — a second matching subsystem is genuinely new code (JPA entities, Flyway migration, service, controller, tests), not a JSON-array extension. |
| `CreatorNudgeService` | **FROZEN.** Not touched by this initiative. | Priya's ruling: two independent surfaces when Tier-2 is eventually built (daily AI nudge vs. deterministic festival lookahead), never a merged scoring function. The daily nudge may later *consume* a festival signal as an input feature — one direction of dependency, never a merge — but that is a separately-scoped future plan. |
| `primary_region` / `primary_niche` schema fields on `CreatorProfile` | **Real prerequisite, not started.** | `CreatorProfile.java` today has only free-text `city` and a 17-value `categoriesJson` niche list — neither maps to Nisha's proposed region/niche vocab. Region matching must not be wired onto free-text `city`; a state-enum migration is its own Tier-2 schema proposal gated on Priya's sign-off. |
| `niche_alias` mapping table | **Real prerequisite, not started.** | Reconciles the 17-value `categoriesJson` creator vocab against Nisha's 14 `activates` content-type strings — a mapping problem, owned by Nisha+Aditya, Priya-approved, that must exist **before** any Tier-2 matching code is written. |

**Explicit, for Kabir:** the festival calendar (Diwali, Onam, Pongal, regional New Years, IPL, e-commerce sale windows, etc.) does **not fire** for any creator today or after this PR merges. It is reference data sitting in a file nothing loads. There is no new attack surface from `india-events-taxonomy.json` itself — no parser, no endpoint, no DB write path exists yet to review.

---

## 3. Decision Trail

**Priya's 3 rulings** (`wiki/build/theme-taxonomy-priya-review.md`), all in response to Vikram's open questions in `wiki/build/theme-taxonomy-implementation.md` §4:

1. **Q1 — does `india_events` widen the closed `themes[]` vocab, or stay a separate axis?** → **Separate axis (Option B).** Widening to 105 items would force synchronized edits across 3 embeds (Java JSON, Python `THEME_SET` manual-sync, n8n CI-checked module) and buys nothing — the overlap-count scorer still can't express region/date/priority. `india_events` never enters `themes[]`.
2. **Q2 — merge the daily nudge with festival matching, or two surfaces?** → **Two surfaces, neither built in Tier-1.** `CreatorNudgeService` stays frozen. When Tier-2 is scoped, the festival calendar is a separate deterministic, no-AI-spend read path — never a merged ranked pick with the AI-spend-metered daily nudge.
3. **Q3 — region/niche schema gap** → **Real prerequisite, Tier-2 scope, resolve by mapping not migration.** `niche_alias` table (Nisha+Aditya, Priya-approved) before any Tier-2 code; pan-India-only matching is acceptable until a scoped region-schema proposal clears Priya; never wire region logic onto free-text `city`. Neither blocks the Tier-1 keyword patch, which touches no profile field.

**The emotion-theme-vs-keyword resolution** (Nisha's hierarchy vs. Aditya's dual-layer proposal), also ruled by Priya:
- **Layer 1 — `themes[]`** (40-item emotion vocab): stays the brand↔trend / creator↔trend matching axis. Locked, untouched. Invisible to Google by design — that's fine, it's not for SEO.
- **Layer 2 — `keyword_to_theme_mappings`**: the correct and *only* home for SEO-keyword-awareness (already wired via `themesForText()`). Aditya's 20 P0 keywords go here. **This is the Tier-1 PR.**
- **Aditya's proposed standalone `seo_keywords` top-level structure (P1, his doc §5.2) — REJECTED.** Nothing reads it; would be a second parallel keyword tree = two places to update. Where SEO metadata is wanted, it rides per-tag on `india_events` instead (already how Vikram's draft folded it in).
- **Nisha's 6-group hierarchy — ACCEPTED as the *shape*** of `india-events-taxonomy.json`, flattened into a tag-array-with-metadata form (`{id, category, region, activates, priority_scores, seo}`) — Nisha's own doc called the hierarchy conceptual/documentation, implementation may flatten.
- **Naming tension** (`rakhi` vs `raksha-bandhan`, `ganpati` vs `ganesh-chaturthi`, `karva`/`karwa` spelling): resolved architecturally via canonical-id + alias (`seo.variant` field), which removes it as a blocker — it's a content-governance sign-off (Nisha/Aditya/Tejas), not a Priya-level decision, and has zero architectural consequence either way.

---

## 4. Verification Record

| Stage | Verdict | Detail |
|---|---|---|
| **Kavya QA** (`wiki/build/theme-taxonomy-kavya-qa.md`) | **CHANGES-REQUIRED → fixed → re-submit expected fast PASS** | Found 1 critical structural defect: a stray `"_comment_below"` string-valued key sitting *inside* the `keyword_to_theme_mappings` object (between `"award show"` and `"outfit ideas"`), which the draft's own apply-instructions said to copy verbatim. Everything else (57 in-vocab entries, no dupe keys, casing/normalization, byte-identical carryover of the 37 pre-existing entries, real file confirmed untouched) verified correct. |
| **Meera build verify — first pass** (`wiki/build/theme-taxonomy-meera-build.md`) | **FAIL** — 2 blockers found | **Bug 1 (the same stray `_comment_below` key, independently confirmed):** `ThemeMatchService.java` deserializes `keyword_to_theme_mappings` via Jackson as `Map<String, List<String>>`; a string value throws `MismatchedInputException` (an `IOException` subtype), caught by `loadTaxonomy()`'s blanket `catch (IOException)` — which **fail-closes the entire taxonomy**, not just the bad key: both `knownThemes` and `keywordToThemeMappings` reset to empty, silently disabling all theme matching app-wide (brand↔trend, creator↔trend, and nightly caption tagging), with only one ERROR log line as a trace. **Bug 2 (n8n drift, newly found):** the premise that this patch is "CI-independent" was wrong — `tagger-sync.test.js` check B2 explicitly diffs `keyword_to_theme_mappings` (not just `themes[]`), so patching the JSON alone without mirroring `theme-tagger.js` + the inline `trend-pull-workflow.json` node would turn CI red on merge. |
| **Fixes applied** | — | Bug 1: Vikram removed `_comment_below` from inside the map, relocated its text to a new sibling top-level `_notes` field (safely ignored by `TaxonomyFile`'s `@JsonIgnoreProperties`). Bug 2: Dev produced the n8n mirror patch (`wiki/build/theme-taxonomy-n8n-patch.md`) — both edits, verified against the actual `THEMES` closed vocab (no new theme invented, zero `THEMES`-set change needed in either file). |
| **Meera re-verify** (`wiki/build/theme-taxonomy-meera-reverify.md`) | **✅ PASS — 11/11 checks, build-safe to ship as coordinated 3-file PR** | Full scratch-apply dry run in a real relative-path repo-mirror (required because `tagger-sync.test.js` resolves files via `__dirname`-relative requires). Applied all three patches to scratch copies, ran the actual unmodified `node trendspark/n8n/tagger-sync.test.js` → `ALL PASS · ... (11 checks)`, exit 0 — matches the pre-patch baseline check count exactly (A1–A4, B1–B6, C1). Also ran `theme-tagger.js`'s own inline self-test → ALL PASS (6 cases). `themes[]` (40) and `niche_to_theme_mappings` (17) confirmed byte-identical/unchanged (same object reference reused, not reconstructed). Real repo files confirmed untouched by the verification pass itself (git status clean + byte-diff MATCH before/after). |

---

## 5. Outstanding Non-Blockers

These do not gate the Tier-1 merge but should not be lost:

1. **Nisha/Aditya content sign-off on `'outfit ideas' → 'glamour'`.** Aditya's original source mapped `"outfit ideas"` to `["style", "fashion", "confidence"]`; `"fashion"` is not in the closed 40-item `themes[]` vocab (would have been silently dropped by `ThemeMatchService`'s `knownThemes.contains()` fail-closed guard — not a live bug, but wrong as shipped). Vikram substituted `"glamour"` (in-vocab) to preserve 3-theme contribution. The exact word choice is Nisha/Aditya's content call, not a correctness gate — flagged, not blocking.
2. **No Java test exists for `ThemeMatchService`'s loader path.** Flagged independently by both Kavya and Meera (both build-verify passes): there is currently zero test coverage of the taxonomy-loading/deserialization path — including the exact fail-closed behavior that caused the Bug-1 blocker above. Worth a follow-up ticket; not required for this Tier-1 ship, but it means a future regression in this exact class (a malformed key breaking the whole map) would again be silent-in-production with no CI catch.

---

## 6. Branch Reality — Recap

All artifacts referenced in §1 and §2 are **DRAFT files in `wiki/build/`**. Verified at manifest time: `git status --porcelain` on all three real Tier-1 target files (`influora-api/src/main/resources/trendspark/theme-taxonomy.json`, `trendspark/n8n/theme-tagger.js`, `trendspark/n8n/trend-pull-workflow.json`) returns clean — no working-tree modifications. Nothing has been merged. The three-file PR described in §1 has not been opened.

**For Kabir's security pass:** review the DRAFT contents (`wiki/build/theme-taxonomy-keyword-patch-DRAFT.json`, `wiki/build/theme-taxonomy-n8n-patch.md`, `wiki/build/india-events-taxonomy-DRAFT.json`) against the live consumer code (`ThemeMatchService.java`, `trendspark/n8n/theme-tagger.js`, `trendspark/n8n/trend-pull-workflow.json`, `tagger-sync.test.js`) as a **pre-merge gate** on a package that is fully drafted and build-verified but not yet applied. The `india-events-taxonomy.json` file (§2) has no consumer and no code path to review beyond "is this JSON file's presence itself safe to add to the repo" — there is no new endpoint, parser, or data flow introduced by it in this pass.

---

**Source trail (all in `wiki/`):**
- `wiki/ai-review/creator-copilot-india-events-calendar-2026.md` — source research
- `wiki/build/theme-taxonomy-expansion-strategy.md` — Nisha
- `wiki/build/theme-taxonomy-seo-validation.md` + `wiki/build/theme-taxonomy-p0-keywords.json` — Aditya
- `wiki/build/theme-taxonomy-priya-review.md` — Priya's ruling
- `wiki/build/theme-taxonomy-implementation.md` — Vikram
- `wiki/build/theme-taxonomy-keyword-patch-DRAFT.json` — Tier-1 shippable
- `wiki/build/india-events-taxonomy-DRAFT.json` — Tier-2, inert
- `wiki/build/theme-taxonomy-n8n-patch.md` — Dev
- `wiki/build/theme-taxonomy-kavya-qa.md`, `wiki/build/theme-taxonomy-meera-build.md`, `wiki/build/theme-taxonomy-meera-reverify.md` — QA/build
