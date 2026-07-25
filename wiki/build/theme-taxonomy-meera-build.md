# Meera Build Verification — Creator Co-pilot Theme-Taxonomy Keyword Patch

Date: 2026-07-22
Scope: Tier-1 keyword-mapping JSON patch (`keyword_to_theme_mappings`: 37 → 57). Real repo files untouched — all work done in scratchpad. Confirmed via `git status --porcelain` on `wiki/build/`, `influora-api/src/main/resources/trendspark/`, and `trendspark/` before/after: no diffs introduced by this verification.

## 1. JSON validity — PASS

Custom recursive-descent parser (rejects trailing commas, flags duplicate keys at every object level) + native `JSON.parse` cross-check, run against all 4 files:

| File | Valid JSON | Trailing commas | Duplicate keys |
|---|---|---|---|
| `wiki/build/theme-taxonomy-keyword-patch-DRAFT.json` | ✅ | none | none |
| `wiki/build/india-events-taxonomy-DRAFT.json` | ✅ | none | none |
| `wiki/build/theme-taxonomy-expansion-DRAFT.json` | ✅ | none | none |
| `influora-api/src/main/resources/trendspark/theme-taxonomy.json` (real, baseline) | ✅ | none | none |

## 2. Apply-in-place dry run — PASS, with one required fix to the apply step

Merged scratch copy built at `<scratchpad>/merged-theme-taxonomy.SCRATCH.json` (real `theme-taxonomy.json` + patch's `keyword_to_theme_mappings`, metadata keys stripped). Preservation checks, programmatic diff against the real file:

- `themes[]`: 40 → 40, **byte-identical** (untouched).
- `niche_to_theme_mappings`: 17 keys → 17 keys, **byte-identical** (untouched).
- `keyword_to_theme_mappings`: 37 → 57 entries, valid JSON, no dupes.

**Finding (must-fix before merge, not a blocker for the JSON's own validity):** the patch draft's `keyword_to_theme_mappings` object contains a non-data key, `"_comment_below": "P0 additions per Aditya..."` (a string, not a `["theme", ...]` array), sitting inside the same object the draft's own `_apply_instructions` says to copy **verbatim** ("Replace the real file's `keyword_to_theme_mappings` object with the object below"). Read literally, that copies `_comment_below` into production too — 58 raw keys, not 57.

This is not cosmetic. `ThemeMatchService.java` (`influora-api/src/main/java/com/influora/service/trendspark/ThemeMatchService.java:143-147`) deserializes this field via Jackson as `Map<String, List<String>>`. A string value on one key breaks that single `objectMapper.readValue(in, TaxonomyFile.class)` call, which is caught by the blanket `catch (IOException e)` at `loadTaxonomy()` (lines 47-53) — Jackson's `MismatchedInputException` extends `IOException`. The catch block's fail-closed behavior zeroes **both** `keywordToThemeMappings` **and** `knownThemes` to empty. Net effect if applied literally: not "20 keywords fail to load" but "theme matching silently disables entirely" — `themesForText()` and `score()` both go dark app-wide, with only a log line (`"failed to load {} — theme matching disabled"`) as a trace. No exception surfaces to a caller; nothing crashes; it just stops working.

**Required fix:** whoever performs the merge must strip `_comment_below` (and any other `_`-prefixed key) from `keyword_to_theme_mappings` before writing the real file — not copy the object verbatim as instructed. Recommend updating `wiki/build/theme-taxonomy-keyword-patch-DRAFT.json`'s `_apply_instructions` to say so explicitly, since as written it invites exactly this mistake. Once stripped, the merge is clean (verified above).

## 3. Build impact (Java tests) — PASS, no blocker

Searched `influora-api/src/test` for any test referencing `ThemeMatchService`, `keywordToThemeMappings`, `knownThemes`, or `themesForText`: **no matches**. No `ThemeMatchServiceTest` exists at all — nothing hard-codes the 37-entry count or asserts specific keyword→theme entries. The count change (37→57) does not break any existing Java test.

(Side note, not a blocker for this patch: this also means there is currently zero test coverage of `ThemeMatchService`'s taxonomy-loading path — including the fail-closed behavior described in §2. Worth a follow-up, not required for this Tier-1 ship.)

## 4. n8n drift-check CI scope — FAIL, real blocker found

The task's premise ("this patch does NOT touch `themes[]` \[only `keyword_to_theme_mappings`], so the drift-check scope is unaffected") does **not** hold. Read `trendspark/n8n/tagger-sync.test.js` directly: check **B2** explicitly diffs `keyword_to_theme_mappings`, not just `themes[]`:

```
check('B2 · KEYWORD_TO_THEMES ⊇ taxonomy.keyword_to_theme_mappings (identical shared values)', () =>
  assertMapSuperset('module KEYWORD_TO_THEMES', M.KEYWORD_TO_THEMES,
    'taxonomy.keyword_to_theme_mappings', taxonomy.keyword_to_theme_mappings));
```

`.github/workflows/trendspark-tagger-sync.yml` triggers on any `push` to `feat/**`/`feature/**`/`main` or PR touching `influora-api/src/main/resources/trendspark/theme-taxonomy.json` — this patch's exact target file is in that path list.

Verified programmatically against the actual repo (not simulated): `trendspark/n8n/theme-tagger.js`'s `KEYWORD_TO_THEMES` module currently has **37 entries**, and is missing **all 20** of the patch's new keys:

```
outfit ideas, glowing skin, home workout, bridal makeup, weight loss, styling tips,
korean skincare, saree draping, mehndi design, monsoon outfit, monsoon skincare,
summer dress, summer skincare, winter skincare, wedding guest dress, ipl jersey,
new year outfit, easy recipes, healthy recipes, punjabi suit
```

Ran the check against the current (unpatched) repo as a baseline — passes clean today: `node trendspark/n8n/tagger-sync.test.js` → `ALL PASS · ... (11 checks)`, exit 0. But `assertMapSuperset` (line 109-118 of the test) requires every key in `taxonomy.keyword_to_theme_mappings` to exist in the module's map with an identical value. Once the real JSON is patched to 57 entries and `theme-tagger.js` is not updated in the same change, B2 fails with `module KEYWORD_TO_THEMES is missing key(s)... [outfit ideas, glowing skin, ...]` — 20 missing keys, CI red.

Additionally, check **A2** requires the inline Code node's copy in `trendspark/n8n/trend-pull-workflow.json` to be byte-identical to `theme-tagger.js`'s module — so the same 20 keywords need adding in **two** n8n-side places (module + inline node paste), not one, to keep the drift check green.

**This contradicts the "Tier-1, zero-schema, independent of everything else" framing in Priya's ruling** as it applies to this specific real-file target — the JSON change is schema-independent and code-independent (confirmed: `ThemeMatchService.themesForText()` needs no code change), but it is **not** CI-independent. It cannot land as an isolated single-file PR against `theme-taxonomy.json` without a companion change to `trendspark/n8n/theme-tagger.js` (and the inline node paste) in the same PR, or CI blocks the merge.

## VERDICT: ❌ FAIL — blocked, not ready to ship as currently scoped

Two required fixes before this Tier-1 patch can merge and stay green:

1. **Apply-step fix (data hygiene):** strip the `_comment_below` metadata key from `keyword_to_theme_mappings` before writing to the real file — do not copy the draft's mapping object verbatim as its own `_apply_instructions` say. Update the draft's apply instructions to say so.
2. **n8n companion change (CI gate, `.github/workflows/trendspark-tagger-sync.yml` check B2 + A2):** add the same 20 keyword→theme entries to `trendspark/n8n/theme-tagger.js`'s `KEYWORD_TO_THEMES` module, and re-sync the inline Code node paste in `trendspark/n8n/trend-pull-workflow.json`, in the **same PR** as the `theme-taxonomy.json` change. This is Dev's (n8n owner) file, not Vikram's — needs routing.

Once both are done, re-run `node trendspark/n8n/tagger-sync.test.js` (should report `ALL PASS ... (11 checks)`) and re-verify the Jackson load path is unaffected — no Java test exists to catch a regression there, so this is manual-verify-only, not a safety net.

No `mvn`/`npm` build was run — not needed; the blockers found are both concrete/deterministic (a Jackson map-type mismatch and a CI script's own superset assertion, both confirmed by direct inspection and by running the actual `tagger-sync.test.js`), not something a full build run would have added confidence on beyond what's shown here.

Routing back to Arjun: blocker 1 → Vikram (fix the draft's apply instructions / the actual merge step). Blocker 2 → Dev (n8n owner, per `wiki/processes/` — Meera does not touch n8n workflow files per her own charter).
