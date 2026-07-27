/**
 * tagger-sync.check.js — drift guard for the Trend-Spark theme-tagger vocab.
 *
 * NOT a vitest test, and deliberately NOT named `*.test.js`. This is a standalone, dependency-free
 * Node script run directly by .github/workflows/trendspark-tagger-sync.yml
 * (`node trendspark/n8n/tagger-sync.check.js`) — no npm install, no test runner.
 *
 * It was called `tagger-sync.test.js` until 2026-07-26, which meant vitest's default `*.test.js`
 * glob claimed it: the runner executed the script, hit `process.exit(0)` on success, and aborted
 * the entire suite with "process.exit unexpectedly called with 0" — one green script turning the
 * whole run red. Converting it to vitest cases looked like the fix, but broke the workflow above,
 * which needs it to run under plain `node` with no runner in scope. Renaming fixes both: vitest
 * ignores it, and the workflow keeps its fast dependency-free check. If you ever want it inside
 * `npm test` as well, add a thin vitest wrapper that shells out — do not rename this file back.
 *
 * The same closed vocabulary lives in THREE places and used to be hand-synced:
 *   (S)  influora-api/src/main/resources/trendspark/theme-taxonomy.json
 *        influora-api/src/main/resources/trendspark/campaign-rulebook.json   ← source of truth
 *   (M)  trendspark/n8n/theme-tagger.js                                       ← canonical JS module
 *   (N)  the "Theme Tagger + row builder" Code node's inline jsCode inside
 *        trendspark/n8n/trend-pull-workflow.json                             ← runtime copy
 *
 * n8n Code nodes can't require() a repo file, so (N) is a verbatim paste of (M),
 * and (M) embeds the vocab from (S). Nothing enforced that they stayed in sync —
 * a change to Nisha's locked vocab in (S) could silently miss (M) and/or (N),
 * drifting the deterministic tagger from the schema-locked source.
 *
 * This script blocks that drift. It does NOT change tagger behavior — it only
 * asserts the relationships that are supposed to hold:
 *
 *   Drift A  (N) == (M)      inline node vocab is byte-equivalent to the module.
 *   Drift B  (M) vs (S)      module faithfully embeds the source of truth:
 *              · THEMES            EXACTLY equals taxonomy.themes (closed vocab).
 *              · KEYWORD/NICHE     module ⊇ taxonomy maps, identical values for
 *                                  shared keys (module may add runtime-only extras
 *                                  like NewsAPI categories — that's allowed).
 *              · campaign types    set equals rulebook.campaign_types keys.
 *              · peak typicals     each type's `typical` equals the rulebook's.
 *              · campaign keywords module ⊇ rulebook keyword_patterns (lowercased;
 *                                  the module intentionally lowercases + extends).
 *   Integrity  every theme referenced by any map value is inside THEMES.
 *
 * Run: `node trendspark/n8n/tagger-sync.test.js` (exit 0 = in sync, 1 = drift).
 * Wired into CI: .github/workflows/trendspark-tagger-sync.yml.
 */

'use strict';

const fs = require('fs');
const path = require('path');
const assert = require('assert');

// ── (M) canonical module — required directly (trusted, no eval) ──────────────
const M = require('./theme-tagger.js');

// ── (N) inline node — pulled out of the workflow JSON's Code node ────────────
const WORKFLOW_PATH = path.join(__dirname, 'trend-pull-workflow.json');
const TAGGER_NODE_ID = 'code-theme-tagger';

function loadInlineNodeVocab() {
  const wf = JSON.parse(fs.readFileSync(WORKFLOW_PATH, 'utf8'));
  const node = (wf.nodes || []).find((n) => n.id === TAGGER_NODE_ID);
  if (!node || !node.parameters || typeof node.parameters.jsCode !== 'string') {
    throw new Error(`could not find Code node "${TAGGER_NODE_ID}" (.parameters.jsCode) in ${WORKFLOW_PATH}`);
  }
  const src = node.parameters.jsCode;
  return {
    THEMES: extractConst(src, 'THEMES'),
    KEYWORD_TO_THEMES: extractConst(src, 'KEYWORD_TO_THEMES'),
    NICHE_TO_THEMES: extractConst(src, 'NICHE_TO_THEMES'),
    CAMPAIGN_RULES: extractConst(src, 'CAMPAIGN_RULES'),
  };
}

/**
 * Pull the literal RHS of `const <name> = <expr>;` out of a source string and
 * evaluate it. The tagger's vocab consts are pure literals declared before any
 * runtime reference ($env / $input / this.helpers), so this reads only data,
 * never executes workflow logic. Balanced-bracket scan that skips string
 * literals, so commas/braces inside theme names never confuse it.
 */
function extractConst(src, name) {
  const re = new RegExp('const\\s+' + name + '\\s*=\\s*');
  const m = re.exec(src);
  if (!m) throw new Error(`could not find "const ${name} =" in inline node source`);
  let i = m.index + m[0].length;
  const start = i;
  let depth = 0;
  let quote = null;
  for (; i < src.length; i++) {
    const c = src[i];
    if (quote) {
      if (c === '\\') { i++; continue; }       // skip escaped char
      if (c === quote) quote = null;
      continue;
    }
    if (c === "'" || c === '"' || c === '`') { quote = c; continue; }
    if (c === '(' || c === '[' || c === '{') depth++;
    else if (c === ')' || c === ']' || c === '}') depth--;
    else if (c === ';' && depth === 0) break;
  }
  const expr = src.slice(start, i).trim();
  // Trusted repo content; wrap so an object literal isn't parsed as a block.
  // eslint-disable-next-line no-eval
  return eval('(' + expr + ')');
}

// ── (S) source of truth — the locked JSON configs ───────────────────────────
const RES = path.join(__dirname, '..', '..', 'influora-api', 'src', 'main', 'resources', 'trendspark');
const taxonomy = JSON.parse(fs.readFileSync(path.join(RES, 'theme-taxonomy.json'), 'utf8'));
const rulebook = JSON.parse(fs.readFileSync(path.join(RES, 'campaign-rulebook.json'), 'utf8'));

// ── tiny assertion harness: collect every failure, report all at once ────────
const failures = [];
function check(label, fn) {
  try { fn(); }
  catch (e) { failures.push(`${label}\n    ${e.message.replace(/\n/g, '\n    ')}`); }
}
const sortedKeys = (o) => Object.keys(o).sort();

function assertMapSuperset(supName, sup, subName, sub) {
  const missing = Object.keys(sub).filter((k) => !(k in sup));
  assert.ok(missing.length === 0, `${supName} is missing key(s) present in ${subName}: [${missing.join(', ')}]`);
  for (const k of Object.keys(sub)) {
    assert.deepStrictEqual(
      sup[k], sub[k],
      `${supName}["${k}"] differs from ${subName}["${k}"]: ${JSON.stringify(sup[k])} vs ${JSON.stringify(sub[k])}`
    );
  }
}

const N = loadInlineNodeVocab();

// ══ Drift A: inline node (N) must be byte-equivalent to the module (M) ══════
check('A1 · THEMES: inline node == module', () =>
  assert.deepStrictEqual(N.THEMES, M.THEMES, 'inline Set differs from module THEMES'));
check('A2 · KEYWORD_TO_THEMES: inline node == module', () =>
  assert.deepStrictEqual(N.KEYWORD_TO_THEMES, M.KEYWORD_TO_THEMES, 'inline map differs from module'));
check('A3 · NICHE_TO_THEMES: inline node == module', () =>
  assert.deepStrictEqual(N.NICHE_TO_THEMES, M.NICHE_TO_THEMES, 'inline map differs from module'));
check('A4 · CAMPAIGN_RULES: inline node == module', () =>
  assert.deepStrictEqual(N.CAMPAIGN_RULES, M.CAMPAIGN_RULES, 'inline rules differ from module'));

// ══ Drift B: module (M) faithfully embeds the source of truth (S) ══════════
check('B1 · THEMES exactly equals theme-taxonomy.json themes', () =>
  assert.deepStrictEqual([...M.THEMES].sort(), [...taxonomy.themes].sort(),
    'THEMES set is not identical to taxonomy.themes'));

check('B2 · KEYWORD_TO_THEMES ⊇ taxonomy.keyword_to_theme_mappings (identical shared values)', () =>
  assertMapSuperset('module KEYWORD_TO_THEMES', M.KEYWORD_TO_THEMES,
    'taxonomy.keyword_to_theme_mappings', taxonomy.keyword_to_theme_mappings));

check('B3 · NICHE_TO_THEMES ⊇ taxonomy.niche_to_theme_mappings (identical shared values)', () =>
  assertMapSuperset('module NICHE_TO_THEMES', M.NICHE_TO_THEMES,
    'taxonomy.niche_to_theme_mappings', taxonomy.niche_to_theme_mappings));

check('B4 · campaign types set equals campaign-rulebook.json campaign_types', () =>
  assert.deepStrictEqual(sortedKeys(M.CAMPAIGN_RULES), sortedKeys(rulebook.campaign_types),
    'CAMPAIGN_RULES types differ from rulebook campaign_types'));

check('B5 · each campaign type peak_window_days.typical matches the rulebook', () => {
  for (const type of Object.keys(rulebook.campaign_types)) {
    const ruleTypical = rulebook.campaign_types[type].peak_window_days.typical;
    assert.ok(M.CAMPAIGN_RULES[type], `module CAMPAIGN_RULES is missing type "${type}"`);
    assert.strictEqual(M.CAMPAIGN_RULES[type].typical, ruleTypical,
      `${type}.typical: module ${M.CAMPAIGN_RULES[type].typical} vs rulebook ${ruleTypical}`);
  }
});

check('B6 · each campaign keyword set ⊇ rulebook keyword_patterns (lowercased)', () => {
  for (const type of Object.keys(rulebook.campaign_types)) {
    const wanted = rulebook.campaign_types[type].keyword_patterns.map((k) => k.toLowerCase());
    const have = new Set(M.CAMPAIGN_RULES[type].keywords);
    const missing = wanted.filter((k) => !have.has(k));
    assert.ok(missing.length === 0,
      `${type}.keywords is missing rulebook pattern(s): [${missing.join(', ')}]`);
  }
});

// ══ Integrity: no map may reference a theme outside the closed vocab ════════
check('C1 · every KEYWORD/NICHE/campaign theme is inside THEMES', () => {
  const offenders = [];
  const scan = (mapName, map) => {
    for (const [k, themes] of Object.entries(map)) {
      for (const t of themes) if (!M.THEMES.has(t)) offenders.push(`${mapName}["${k}"] → "${t}"`);
    }
  };
  scan('KEYWORD_TO_THEMES', M.KEYWORD_TO_THEMES);
  scan('NICHE_TO_THEMES', M.NICHE_TO_THEMES);
  assert.ok(offenders.length === 0, `off-vocab theme reference(s):\n      ${offenders.join('\n      ')}`);
});

// ── report ───────────────────────────────────────────────────────────────────
const TOTAL = 11;
if (failures.length === 0) {
  console.log(`ALL PASS · Trend-Spark tagger vocab in sync across node ⇄ module ⇄ JSON configs (${TOTAL} checks)`);
  process.exit(0);
}
console.error(`\n${failures.length} of ${TOTAL} DRIFT CHECK(S) FAILED:\n`);
for (const f of failures) console.error(`  ❌ ${f}\n`);
console.error('Fix: re-sync theme-tagger.js, the inline Code node in trend-pull-workflow.json, and/or');
console.error('     the JSON configs so all three agree. Do not change tagger behavior to satisfy a check —');
console.error('     if the vocab genuinely changed, propagate it to every copy.\n');
process.exit(1);
