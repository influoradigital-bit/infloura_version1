# Trend-Spark n8n Tagger Sync — 20-Keyword Patch (DRAFT, not applied)

**Status:** DRAFT only. No real files touched. Written for Meera's scratch-apply re-verify
(`node trendspark/n8n/tagger-sync.test.js`) ahead of the `theme-taxonomy.json` +20-keyword PR.

**Source of truth for the 20 keywords:** `wiki/build/theme-taxonomy-keyword-patch-DRAFT.json`
`keyword_to_theme_mappings` (57 entries: 37 pre-existing + 20 new). Confirmed clean — the
stray `_comment_below` key Vikram was fixing is already relocated to the top-level `_notes`
field in the copy I read; the `keyword_to_theme_mappings` object itself contains only
string → array-of-strings entries. All 57 are authoritative as-is.

## Why this file must exist

`trendspark/n8n/tagger-sync.test.js` enforces (CI: `.github/workflows/trendspark-tagger-sync.yml`,
triggers on PRs touching `theme-taxonomy.json`):

- **A2** — the inline Code-node paste in `trend-pull-workflow.json` (`code-theme-tagger` node,
  `KEYWORD_TO_THEMES` const) must `assert.deepStrictEqual` the module's `KEYWORD_TO_THEMES`
  in `theme-tagger.js`. Structural equality (via `eval` of the literal), not text-byte
  equality — key order doesn't matter, but every key and its array value (order matters
  for arrays) must match exactly.
- **B2** — `theme-tagger.js`'s `KEYWORD_TO_THEMES` must be a **superset** of
  `theme-taxonomy.json`'s `keyword_to_theme_mappings`, with **identical values for shared
  keys** (`assertMapSuperset`: missing-key check + `deepStrictEqual` per shared key). The
  module is allowed extra keys; it is never allowed to disagree on a value.
- **C1** — every theme referenced by any `KEYWORD_TO_THEMES`/`NICHE_TO_THEMES` value must be
  inside `THEMES` (the 40-item closed vocab already embedded in `theme-tagger.js`, lines 32–40).

So the fix has two edits, both additive, both inserted in the same relative position
(immediately after the existing `'award show'` entry, before the closing brace):

1. `trendspark/n8n/theme-tagger.js` → `KEYWORD_TO_THEMES` object literal (readable/multi-line style)
2. `trendspark/n8n/trend-pull-workflow.json` → `code-theme-tagger` node's `parameters.jsCode`
   string → inline `KEYWORD_TO_THEMES` const (compact/single-line style, matches existing
   paste convention)

## Closed-vocab check (integrity gate C1)

All 20 new entries use only themes already in `THEMES` (`theme-tagger.js` lines 32–40) — no
new theme is invented, so this patch needs zero change to the `THEMES` set in either file:

| keyword | themes | all in-vocab? |
|---|---|---|
| outfit ideas | style, glamour, confidence | yes |
| glowing skin | beauty, glow, radiance | yes |
| home workout | fitness, health, discipline | yes |
| bridal makeup | beauty, glamour, celebration | yes |
| weight loss | health, fitness, discipline | yes |
| styling tips | style, confidence, elegance | yes |
| korean skincare | beauty, innovation, self-care | yes |
| saree draping | tradition, elegance, heritage | yes |
| mehndi design | beauty, celebration, tradition | yes |
| monsoon outfit | style, comfort, innovation | yes |
| monsoon skincare | beauty, self-care, wellness | yes |
| summer dress | style, comfort, youth | yes |
| summer skincare | beauty, self-care, radiance | yes |
| winter skincare | beauty, self-care, wellness | yes |
| wedding guest dress | style, elegance, celebration | yes |
| ipl jersey | pride, energy, style | yes |
| new year outfit | style, celebration, confidence | yes |
| easy recipes | family, comfort, authenticity | yes |
| healthy recipes | health, wellness, authenticity | yes |
| punjabi suit | tradition, style, heritage | yes |

No key collisions with the existing 37 keys either (`new year outfit` is a distinct string
from the existing `new year` key; `monsoon skincare`/`summer skincare`/`winter skincare` are
distinct strings from the existing `skincare` key — the tagger's `lowered.includes(keyword)`
substring match means a trend containing e.g. "monsoon skincare tips" will fire *both* the
new key and the existing `skincare` key and union their themes, which is the tagger's existing
by-design behavior, not something this patch changes or needs to guard against).

Values copied verbatim from `wiki/build/theme-taxonomy-keyword-patch-DRAFT.json` — same
strings, same array order per key — so once that DRAFT lands byte-for-byte as the real
`theme-taxonomy.json`'s `keyword_to_theme_mappings`, B2's `deepStrictEqual` per shared key
passes (order-sensitive array equality matches because I did not re-order anything).

## Edit 1 — `trendspark/n8n/theme-tagger.js`

In the `KEYWORD_TO_THEMES` object literal (currently lines 43–81), insert these 20 lines
immediately after the `'award show': [...]` entry (line 80) and before the closing `};`
(line 81), keeping the file's existing multi-line/spaced style:

```js
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
```

Result: `KEYWORD_TO_THEMES` goes from 37 → 57 entries. No other part of `theme-tagger.js`
changes — `THEMES`, `NICHE_TO_THEMES`, `CAMPAIGN_RULES`, and all functions are untouched.

## Edit 2 — `trendspark/n8n/trend-pull-workflow.json`

The `code-theme-tagger` node (`id: "code-theme-tagger"`, currently at line 227 of the file)
carries the whole tagger as one JSON-escaped string in `parameters.jsCode`. Inside that
string, the `KEYWORD_TO_THEMES` const is a single compact line (no spaces after `:`/`,`,
matching the file's existing minified-paste convention — this is a style difference from
`theme-tagger.js`, not a correctness issue, since A2 checks structural equality via `eval`,
not text bytes).

Current tail of that const (unescaped for readability; the real file has this inside a
JSON string, so every `\n` is a literal backslash-n in the JSON, not a real newline):

```
...'fashion week':['style','glamour','innovation','elegance'],'award show':['glamour','celebration','style','luxury']};
```

New tail — insert the 20 entries between `'award show':[...]` and the closing `};`:

```
...'fashion week':['style','glamour','innovation','elegance'],'award show':['glamour','celebration','style','luxury'],'outfit ideas':['style','glamour','confidence'],'glowing skin':['beauty','glow','radiance'],'home workout':['fitness','health','discipline'],'bridal makeup':['beauty','glamour','celebration'],'weight loss':['health','fitness','discipline'],'styling tips':['style','confidence','elegance'],'korean skincare':['beauty','innovation','self-care'],'saree draping':['tradition','elegance','heritage'],'mehndi design':['beauty','celebration','tradition'],'monsoon outfit':['style','comfort','innovation'],'monsoon skincare':['beauty','self-care','wellness'],'summer dress':['style','comfort','youth'],'summer skincare':['beauty','self-care','radiance'],'winter skincare':['beauty','self-care','wellness'],'wedding guest dress':['style','elegance','celebration'],'ipl jersey':['pride','energy','style'],'new year outfit':['style','celebration','confidence'],'easy recipes':['family','comfort','authenticity'],'healthy recipes':['health','wellness','authenticity'],'punjabi suit':['tradition','style','heritage']};
```

Concretely: in the JSON file, this whole tagger body lives inside one `"jsCode": "..."`
string value. The literal substring to find-and-replace inside that string is:

- find: `'award show':['glamour','celebration','style','luxury']};`
- replace: `'award show':['glamour','celebration','style','luxury'],'outfit ideas':['style','glamour','confidence'],'glowing skin':['beauty','glow','radiance'],'home workout':['fitness','health','discipline'],'bridal makeup':['beauty','glamour','celebration'],'weight loss':['health','fitness','discipline'],'styling tips':['style','confidence','elegance'],'korean skincare':['beauty','innovation','self-care'],'saree draping':['tradition','elegance','heritage'],'mehndi design':['beauty','celebration','tradition'],'monsoon outfit':['style','comfort','innovation'],'monsoon skincare':['beauty','self-care','wellness'],'summer dress':['style','comfort','youth'],'summer skincare':['beauty','self-care','radiance'],'winter skincare':['beauty','self-care','wellness'],'wedding guest dress':['style','elegance','celebration'],'ipl jersey':['pride','energy','style'],'new year outfit':['style','celebration','confidence'],'easy recipes':['family','comfort','authenticity'],'healthy recipes':['health','wellness','authenticity'],'punjabi suit':['tradition','style','heritage']};`

Since `'award show':[...]` occurs exactly once in the `jsCode` string, this substring is
unique — safe for a scripted find-and-replace. No other field in the node (`NICHE_TO_THEMES`,
`CAMPAIGN_RULES`, `THEMES`, or the row-building logic below it) changes.

## Byte-for-byte value confirmation (B2 identical-value assertion)

Both edits above copy every key and array **verbatim, same order**, from
`wiki/build/theme-taxonomy-keyword-patch-DRAFT.json`'s `keyword_to_theme_mappings` (the 20
entries at its lines 57–76). I did not rename, reorder, or reword any theme. Once
`theme-taxonomy.json` is patched to match that DRAFT exactly:

- **B2** passes: for each of the 20 new shared keys, `theme-tagger.js`'s value
  `deepStrictEqual`s `theme-taxonomy.json`'s value (same strings, same array order).
- **A2** passes: `trend-pull-workflow.json`'s inline `KEYWORD_TO_THEMES` (57 entries) is
  structurally identical to `theme-tagger.js`'s `KEYWORD_TO_THEMES` (57 entries) — same
  keys, same values; formatting/whitespace differences between the two files don't matter
  because A2 evals both to real JS objects before comparing.
- **C1** passes: every theme in the 20 new entries is already in the embedded `THEMES` set
  in both files (see table above) — `THEMES` itself is untouched by this patch.

## Does the drift test itself need changes?

**No — just the two data copies (Edit 1 and Edit 2 above).** `tagger-sync.test.js` is
already fully generic over the keyword count and content:

- It reads `taxonomy.keyword_to_theme_mappings` and `M.KEYWORD_TO_THEMES` dynamically
  (`Object.keys`/`Object.entries`) — nothing hardcodes "37" or "57" or enumerates specific
  keywords.
- The `TOTAL = 11` constant on line 182 counts **check labels** (A1–A4, B1–B6, C1 = 11),
  not keyword entries — unaffected by adding keywords.
- `.github/workflows/trendspark-tagger-sync.yml` already lists `theme-taxonomy.json`,
  `theme-tagger.js`, and `trend-pull-workflow.json` in its `paths:` trigger (lines 14–18),
  so a PR touching all three (as this patch will) already runs the check without any
  workflow-file edit.

Nothing else in the repo needs to change for this patch — consistent with the DRAFT's own
`_apply_instructions` #2/#3 (no `ThemeMatchService` code change, no schema change).

## Files this DRAFT describes changes for (not yet touched)

- `trendspark/n8n/theme-tagger.js` — add 20 entries to `KEYWORD_TO_THEMES` (Edit 1)
- `trendspark/n8n/trend-pull-workflow.json` — add the same 20 entries to the inline
  `code-theme-tagger` Code node's `KEYWORD_TO_THEMES` (Edit 2)
- No change needed to `trendspark/n8n/tagger-sync.test.js` or
  `.github/workflows/trendspark-tagger-sync.yml`

## Verification path for Meera

1. Apply Edit 1 to a scratch copy of `theme-tagger.js`.
2. Apply Edit 2 to a scratch copy of `trend-pull-workflow.json`.
3. Apply the real `theme-taxonomy.json` patch from
   `wiki/build/theme-taxonomy-keyword-patch-DRAFT.json` (Vikram's PR).
4. `node trendspark/n8n/tagger-sync.test.js` → expect
   `ALL PASS · ... (11 checks)`.
5. `node trendspark/n8n/theme-tagger.js` → self-test cases should still all PASS (this
   patch doesn't touch any of the 5 existing test cases' matched keywords, so their
   expected output is unaffected).
