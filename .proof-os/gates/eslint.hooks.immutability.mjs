// gates/eslint.hooks.immutability.mjs — GATE-OWNED react-hooks immutability rule.
// origin: F-0040 (use-before-declare). Same law as eslint.hooks.mjs: the rule
// lives HERE so a project that downgrades its own eslint.config.js cannot blind
// this gate; plugin absent => runner exits 2 (unavailable), never 0 (false green).
// Scope is the single rule; the runner (react_hooks_immutability.py) further
// filters to the "accessed before it is declared" message sub-class.
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
      'react-hooks/immutability': 'error',
    },
  })
}

export default cfg
