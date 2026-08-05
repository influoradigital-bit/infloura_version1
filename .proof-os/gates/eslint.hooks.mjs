// gates/eslint.hooks.mjs — GATE-OWNED react-hooks ruleset.
// origin: F-0039 (conditional-hook). gates/frontend.sh runs eslint.sage.mjs,
// which carries NO react-hooks plugin — so the rules-of-hooks class was
// completely undetectable by any gate. Closing F-0039 against frontend.sh
// would have been a false close (the class F-0029 is open for).
//
// LAW: the rule lives HERE, not in the project's eslint.config.js, so a project
// that later downgrades or deletes the rule cannot silently blind this gate.
// Plugin absent => the runner exits 2 (unavailable), never 0 (false green).
//
// SCOPE: rules-of-hooks ONLY. Deliberately narrow. Enabling the full
// react-hooks recommended set here would make the gate permanently red
// (99 set-state-in-effect + 11 purity + 13 refs are tracked separately as
// F-0041 etc.), and a permanently-red gate trains people to ignore it —
// that is F-0015's false-red class. Each class gets its own gate as it closes.
import { createRequire } from 'node:module'
const require = createRequire(import.meta.url)
const tryReq = (n) => { try { return require(n) } catch { return null } }

const reactHooks = tryReq('eslint-plugin-react-hooks')
const tsParser = tryReq('@typescript-eslint/parser')

const cfg = [
  { ignores: ['node_modules/**', 'dist/**', 'build/**', 'out/**', '.next/**', '.claude/**', '.proof-os/**'] },
]

if (reactHooks) {
  const plugin = reactHooks.default ?? reactHooks
  cfg.push({
    files: ['**/*.{jsx,tsx,js,ts}'],
    ...(tsParser ? { languageOptions: { parser: tsParser } } : {}),
    plugins: { 'react-hooks': plugin },
    rules: {
      // The one rule this gate exists to enforce.
      'react-hooks/rules-of-hooks': 'error',
    },
  })
}

export default cfg
