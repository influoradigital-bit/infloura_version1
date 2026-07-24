# QA Review: theme-taxonomy-keyword-patch-DRAFT.json (Creator Co-pilot theme-taxonomy expansion, Tier-1)
Date: 2026-07-22
Reviewer: Kavya
Scope: `wiki/build/theme-taxonomy-keyword-patch-DRAFT.json` only (the Tier-1 shippable). `india-events-taxonomy-DRAFT.json` spot-checked for inertness per Priya's Tier-2 parking ruling — not deep-QA'd.
Status: **CHANGES-REQUIRED**

## Issues Found

### CRITICAL (must fix before any merge into the real file)

1. **`_comment_below` is a live member of the `keyword_to_theme_mappings` JSON object, not a file-level comment — this breaks deserialization of the entire taxonomy file.**
   In `wiki/build/theme-taxonomy-keyword-patch-DRAFT.json`, lines 56-57, the key `"_comment_below"` sits *inside* the `keyword_to_theme_mappings` object (opens line 18, closes line 78) — between `"award show"` and `"outfit ideas"` — with a **string** value, not an array of theme strings:
   ```
   "award show": ["glamour", "celebration", "style", "luxury"],

   "_comment_below": "P0 additions per Aditya ...",
   "outfit ideas": ["style", "glamour", "confidence"],
   ```
   Every other comment field in this DRAFT (`_DRAFT_WARNING`, `_status`, `_change_summary`, `_pending_signoff`, etc.) is a **sibling of** `keyword_to_theme_mappings`, outside its braces — correctly excluded from what gets merged. `_comment_below` is the one exception: it is textually *inside* the map that `_apply_instructions` says to copy verbatim into the real file ("Replace the real file's `keyword_to_theme_mappings` object with the `keyword_to_theme_mappings` object below ... verbatim").

   `ThemeMatchService.java` deserializes this file with a plain `new ObjectMapper()` (no coercion config) into a `Map<String, List<String>> keywordToThemeMappings` field (line 37/45-46, `TaxonomyFile` record at line 143-147). Jackson's default `CollectionDeserializer` rejects a JSON string value where a `List<String>` is expected (`DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY` is off by default and not enabled anywhere in this service) and throws `MismatchedInputException` — a subtype of `IOException`. That exception is caught by `loadTaxonomy()`'s `catch (IOException e)` block (lines 47-53), which **fail-closes the entire taxonomy, not just this one key**: both `knownThemes` and `keywordToThemeMappings` get reset to empty. Net effect if this patch is applied as instructed: `score()` always returns 0 (brand↔trend and creator↔trend matching goes dark) and `themesForText()` always returns an empty set (caption tagging via `CreatorThemeTaggingJob` goes dark) — for *all* 37 pre-existing keywords too, not just the 20 new ones. This is silent at startup except for one ERROR log line.

   **Fix:** delete `"_comment_below": "..."` (line 57) from inside the `keyword_to_theme_mappings` object entirely, or move it out to a sibling top-level field the same way `_change_summary`/`_pending_signoff` already are. The real file's existing convention for this kind of note is the top-level `"notes": [...]` array (see `theme-taxonomy.json` lines 104-108) — either drop the comment or fold its content into that array instead, but it must not be a member of the map itself.

   This is the only structural defect found; once `_comment_below` is removed, the object contains exactly 57 well-formed `keyword: [themes...]` entries (verified below).

### Verified correct (no action needed)

1. **Off-vocab theme targets: none found.** Checked all 57 keyword→theme entries (post-fix, i.e. excluding `_comment_below`) against the real file's 40-item `themes[]` array. Zero pairs target a theme outside the closed vocabulary. The "fashion" fix is confirmed correct — `"outfit ideas": ["style", "glamour", "confidence"]` — all three (`style`, `glamour`, `confidence`) are in `themes[]`.
2. **No duplicate keyword keys.** Raw-text scan of the object (not just the JSON-parsed form, which would silently collapse duplicates) found 57 distinct array-valued keys, zero repeats.
3. **Casing/spacing normalization: all 57 keys are lowercase, trimmed, single-spaced, no underscores.** Confirmed against `ThemeMatchService.themesForText()` (lines 98-120): it lowercases both the input caption (`freeText.toLowerCase(Locale.ROOT)`, line 102) and each keyword key at compare time (`keyword.toLowerCase(Locale.ROOT)`, line 106) before the substring check — so keyword casing in the JSON wouldn't actually break matching even if it weren't normalized, but the patch is normalized correctly regardless (matches the file's existing style).
4. **All 20 new keywords from Aditya's P0 batch are present, and all 37 pre-existing entries are carried over byte-for-byte unchanged** (diffed key-by-key against the live `influora-api/src/main/resources/trendspark/theme-taxonomy.json`). Count claim "37 → 57 (+20)" is accurate for the keyword entries themselves.
5. **`india-events-taxonomy-DRAFT.json` is genuinely inert (spot-check only, per scope fence).** Repo-wide grep for `india-events-taxonomy`, `india_events`, `IndiaEvents` outside `wiki/build/` returns zero matches — no Java service, no Python module, no n8n script imports or reads it. The file itself carries `_status: "TIER-2 — REFERENCE-ONLY. No consumer exists; not loaded by any service."` and a `_gate` field naming Priya's sign-off prerequisites, consistent with the implementation doc's claim. Not deep-QA'd beyond this per instructions.
6. **Real `influora-api/src/main/resources/trendspark/theme-taxonomy.json` is untouched.** `git status --porcelain` on that exact path returns nothing — no working-tree modification. (Only unrelated staged file in that resources tree is `db/migration/V20260721160000__meera_interaction_log.sql`, out of scope.)

## Next Steps
Route back to Vikram: remove the misplaced `"_comment_below"` key from inside `keyword_to_theme_mappings` in `wiki/build/theme-taxonomy-keyword-patch-DRAFT.json` (delete it, or relocate its content to a sibling field / the real file's `notes[]` array — anywhere outside the map). Everything else in the patch — all 57 keyword→theme pairs, casing/normalization, no-dup keys, in-vocab targets, untouched real file — is correct and does not need rework. Re-submit for QA once the single structural fix lands; expect a fast PASS since no other content changes are needed.
