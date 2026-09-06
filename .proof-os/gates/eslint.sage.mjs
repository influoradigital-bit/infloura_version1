// gates/eslint.sage.mjs — kavya's promoted rules as ESLint 9+ flat config.
// origin: F-0023 (silent oracle). Degrades RULE-BY-RULE when a plugin is
// absent, instead of failing the whole gate. Core rules always run.
//
// 0.3.4 — three ways this config was not the oracle it claimed to be:
//
//   1. kavya's "no inline styles" rule was unreachable on every real React file.
//      On `.tsx` it was absent from the resolved config entirely (the only block
//      carrying it globbed `**/*.{js,jsx,mjs,cjs}`, and the TS block that did match
//      declared its own `rules`, which replace nothing but add nothing either).
//      Verified with `npx eslint --print-config src/a.tsx`.
//   2. On `.jsx` the rule was present and never evaluated: no block set
//      `parserOptions.ecmaFeatures.jsx`, so espree stopped at `Parsing error:
//      Unexpected token <`. That is exit 1 out of frontend.sh — a FALSE RED
//      produced by the config written to stop false reds.
//   3. `tryReq` resolved from this file's directory only. A project with
//      jsx-a11y in its own node_modules silently lost those rules with no message.
//      It now tries the project first and SAYS which blocks it dropped (law 6).
//
// The rule set is defined ONCE, in CORE_RULES, and shared by every block, so a
// rule cannot exist for one extension and not another again.
import { createRequire } from 'node:module'
import { readFileSync } from 'node:fs'
import path from 'node:path'

const here = path.dirname(new URL(import.meta.url).pathname)
const selfRequire = createRequire(import.meta.url)
const projectRequire = createRequire(path.join(process.cwd(), '__eslint_sage__.cjs'))

const skipped = []
const tryReq = (n) => {
  for (const req of [projectRequire, selfRequire]) {
    try { return req(n) } catch { /* try the next resolution root */ }
  }
  return null
}

const JS_GLOBS = ['**/*.{js,jsx,mjs,cjs}']
const TS_GLOBS = ['**/*.{ts,tsx,mts,cts}']

// F-0208: honour the project's `_`-prefix convention for intentionally-unused
// bindings (params kept for signature parity, disabled imports). The project's own
// eslint.config.js declares exactly these ignore patterns; a gate linting with a
// stricter convention than the codebase's produces false reds, not findings.
const UNUSED_VARS_OPTS = {
  argsIgnorePattern: '^_',
  varsIgnorePattern: '^_',
  caughtErrorsIgnorePattern: '^_',
  destructuredArrayIgnorePattern: '^_',
}

// One definition. Every block spreads this; nothing carries a rule alone.
const CORE_RULES = {
  'no-console': ['error', { allow: ['warn', 'error'] }],
  'no-unused-vars': ['error', UNUSED_VARS_OPTS],
  // F-0209 ruling (Swapnil, 2026-08-15): `<motion.*>` elements are EXEMPT — the style
  // prop is framer-motion's only MotionValue binding API (useTransform/useSpring values
  // update the DOM outside React render and bind nowhere else). Plain DOM elements stay
  // banned; so does `style` on any non-motion component.
  'no-restricted-syntax': [
    'error',
    {
      selector: "JSXOpeningElement:not([name.object.name='motion']) > JSXAttribute[name.name='style']",
      message: 'kavya: no inline styles — Tailwind only (motion.* elements exempt: MotionValue binding)',
    },
  ],
}

// JSX must be enabled explicitly for espree, or every .jsx file is a parse error.
const JSX_LANG = {
  ecmaVersion: 'latest',
  sourceType: 'module',
  parserOptions: { ecmaVersion: 'latest', sourceType: 'module', ecmaFeatures: { jsx: true } },
}

const cfg = [
  { ignores: ['node_modules/**', '.next/**', 'dist/**', 'build/**', 'out/**', '.proof-os/**'] },
  { // core — zero dependencies, always active
    files: JS_GLOBS,
    languageOptions: JSX_LANG,
    rules: { ...CORE_RULES },
  },
]

const tsParser = tryReq('@typescript-eslint/parser')
const tsPlugin = tryReq('@typescript-eslint/eslint-plugin')
if (tsParser && tsPlugin) {
  cfg.push({
    files: TS_GLOBS,
    languageOptions: { parser: tsParser, ...JSX_LANG },
    plugins: { '@typescript-eslint': tsPlugin },
    rules: {
      ...CORE_RULES,                       // kavya's rules apply to .tsx too
      '@typescript-eslint/no-explicit-any': 'error',
      'no-unused-vars': 'off',             // superseded, not dropped
      '@typescript-eslint/no-unused-vars': ['error', UNUSED_VARS_OPTS],
    },
  })
} else {
  // No TS parser: espree cannot read TypeScript, and linting it anyway would report
  // every .ts file as a parse ERROR. tsc --noEmit in frontend.sh still covers them.
  cfg.push({ ignores: TS_GLOBS })
  skipped.push('@typescript-eslint (no parser/plugin resolvable) — .ts/.tsx NOT LINTED here')
}

// F-0208: the codebase carries load-bearing `eslint-disable react-hooks/exhaustive-deps`
// comments (the project's own config runs that rule at error severity). A config that
// does not DEFINE the rule turns every such comment into a "Definition for rule was
// not found" ERROR — 22 false reds at comment sites. Loading the project's own plugin
// makes the comments valid AND enforces the classic hooks rules here at the project's
// severity. Degrades rule-by-rule like every other block (F-0023).
const reactHooks = tryReq('eslint-plugin-react-hooks')
if (reactHooks) {
  cfg.push({
    files: [...JS_GLOBS, ...TS_GLOBS],
    plugins: { 'react-hooks': reactHooks.default ?? reactHooks },
    rules: {
      'react-hooks/rules-of-hooks': 'error',
      // 'warn', matching the project's standing react-hooks policy (2026-08-03):
      // exhaustive-deps findings are tracked, not red. At 'error' this gate would
      // out-rule the codebase's own severity and fail on ~deliberately-deferred sites.
      // Defining the rule at ANY severity is what makes the disable comments valid.
      'react-hooks/exhaustive-deps': 'warn',
    },
  })
} else {
  skipped.push('eslint-plugin-react-hooks not resolvable — hooks rules NOT CHECKED, and in-repo eslint-disable comments naming them read as rule-not-found ERRORS (F-0208)')
}

const a11y = tryReq('eslint-plugin-jsx-a11y')
if (a11y) {
  cfg.push({
    files: ['**/*.{jsx,tsx}'],
    languageOptions: JSX_LANG,
    plugins: { 'jsx-a11y': a11y.default ?? a11y },
    rules: { 'jsx-a11y/alt-text': 'error', 'jsx-a11y/anchor-is-valid': 'error' },
  })
} else {
  skipped.push('eslint-plugin-jsx-a11y — alt-text and anchor-is-valid NOT CHECKED')
}

// The eslintrc fallback (eslint.sage.json) duplicates this rule set with no shared
// source. It has already drifted once. Say so rather than let it drift silently.
try {
  const legacy = JSON.parse(readFileSync(path.join(here, 'eslint.sage.json'), 'utf8'))
  const mine = new Set(Object.keys(CORE_RULES))
  const theirs = new Set(Object.keys(legacy.rules || {}))
  const onlyHere = [...mine].filter((r) => !theirs.has(r))
  const onlyThere = [...theirs].filter((r) => !mine.has(r) && !r.startsWith('@typescript-eslint/') && !r.startsWith('jsx-a11y/'))
  if (onlyHere.length || onlyThere.length) {
    skipped.push(`eslint.sage.json has drifted from this file — only here: [${onlyHere}] · only there: [${onlyThere}]`)
  }
} catch {
  skipped.push('eslint.sage.json could not be read, so the eslintrc fallback was not compared with this config')
}

// law 6: a check that did not run says so, on stderr, where frontend.sh captures it.
if (skipped.length) {
  console.error('NOT CHECKED (eslint.sage.mjs): ' + skipped.join(' | '))
}

export default cfg
