# Theme-Taxonomy Expansion — Security Gate (Kabir / Red-Team)

**Date:** 2026-07-22 | **Verdict: PASS — clean data change. Zero Critical/High/Medium. One Low advisory (pre-existing, not introduced here).**

Reviewed the ACTUAL DRAFT artifacts against live consumer code (not the plan). Scope confirmed correct: this is data-only (JSON keyword→theme string maps + mirrored n8n copies). No new parser, endpoint, schema, migration, or executable path is introduced. The "data-only" framing holds.

## What actually flows where (verified against source)

- **`ThemeMatchService.themesForText()`** (`influora-api/.../trendspark/ThemeMatchService.java:98-120`) — deterministic case-insensitive `lower.contains(keyword)` substring match. Output themes are then re-filtered by `knownThemes.contains(theme)` (line 112) — a keyword can never contribute a theme outside the closed 40-item vocab. No prompt. No exec.
- **n8n `theme-tagger.js`** `KEYWORD_TO_THEMES` — `lowered.includes(keyword)`, output filtered by `THEMES.has(t)` (line 195). Pure function, "No I/O, no secrets."
- **n8n `trend-pull-workflow.json`** `code-theme-tagger` node — same 20 entries pasted into the `jsCode` string literal.
- **Python `trend_tag.py`** — only the closed 40-item `THEMES` tuple is joined into the LLM system prompt (line 100). `themes[]` is UNTOUCHED by this patch, so the 20 new keywords never appear in this prompt.
- **Python `creator_suggestion.py`** — the prompt receives `theme_matched` (closed-vocab, route-validated, neutralized) + `trend_text` (untrusted, `wrap_untrusted`). No keyword string reaches it; no caption text reaches it.

## Security checks

**1. No code-execution risk in the n8n paste — CONFIRMED SAFE.** This check is genuinely load-bearing: `tagger-sync.test.js:93` runs `eval('(' + expr + ')')` on the workflow node's `jsCode` const at CI time, and n8n executes the same `jsCode` at runtime. A keyword that broke out of its single-quoted literal would be a stored-code-injection vector. All 20 new keys and every theme value are pure `[a-z ]`/`[a-z-]` strings — no single/double/back quotes, no backslashes, no `[](){}`, no `<`/`>`, no `${`. They are inert string data inside the object literal; they add no statements. The `extractConst` scanner (lines 78-89) correctly skips string literals with quote/escape tracking, so even the theme name `self-care` (hyphen) is handled. Nothing breaks out.

**2. No secrets — CONFIRMED.** Read all three DRAFT files in full. No API keys, tokens, credentials, connection strings, or secret-bearing URLs. `india-events` contains only public festival names, region ids, search-volume integers, and placeholder priority scores.

**3. Prompt-injection surface — CONFIRMED NONE from this patch.** No keyword string and no new theme value flows into any LLM prompt as instructions or data. The only taxonomy data reaching a prompt is the closed `THEMES` vocab (unchanged) and route-validated `theme_matched`. All 20 keywords map exclusively to themes already in the closed 40-vocab (verified table in `theme-taxonomy-n8n-patch.md` + re-checked against `THEMES` in `theme-tagger.js:32-40`), so nothing new reaches the model even indirectly. The genuinely untrusted field (`trend_text`) is independently wrapped/neutralized and is unaffected by this change.

**4. Fail-close preserved, nothing newly OPENED — CONFIRMED.** The Kavya/Meera bug (stray `_comment_below` string inside the typed map → `MismatchedInputException` → blanket `catch(IOException)` fail-closes the WHOLE taxonomy) is fixed by relocating the note to a top-level sibling `_notes` field. `@JsonIgnoreProperties(ignoreUnknown=true)` on `TaxonomyFile` (line 142) was ALREADY present — the fix relies on pre-existing behavior, not a newly-added leniency. Critically: `ignoreUnknown` only swallows unknown *top-level* keys (metadata/comments the service doesn't consume). The map is still typed `Map<String, List<String>>`, so a malformed entry *inside* `keyword_to_theme_mappings` still throws and still fail-closes exactly as before. The fix does not weaken the malformed-entry guard. Fail-closed direction is security-safe (matching disabled = no nudge, never a leak/injection).

**5. Tier-2 `india-events` inert — CONFIRMED.** Grep for `india_events`/`india-events`/`events_calendar`/`gap_candidates` across the repo returns 10 hits, ALL in `wiki/*.md`, `wiki/build/*.json` (drafts), and `SHARED_CONTEXT.md`. Zero `.java`/`.py`/`.js` runtime consumers. No loader, no endpoint, no DB write path. The 65 unreviewed tags present no injection surface because nothing reads them. Adding the file to the repo is safe.

**6. `outfit ideas → glamour` and other mappings — CONFIRMED no security implication.** Content/relevance only (in-vocab substitution for the off-vocab `fashion`). A content-governance sign-off item (Nisha/Aditya), not a correctness or security gate.

## Advisory (Low — non-blocking, pre-existing)

- **No Java test covers `ThemeMatchService.loadTaxonomy()` fail-closed path.** Already flagged by Kavya and Meera (§5 of the changeset manifest). A future malformed map entry would again silently disable app-wide matching with only one ERROR log. This is an availability/robustness gap in the security-SAFE direction (fails closed), not a vulnerability, and is NOT introduced by this patch. Recommend a follow-up test ticket; does not block Tier-1.

## Bottom line

Clean data change. No exec, no secrets, no injection surface, fail-close intact, Tier-2 truly inert. **Security gate: PASS.** No Critical/High/Medium findings. Cleared to merge the coordinated 3-file Tier-1 PR.
