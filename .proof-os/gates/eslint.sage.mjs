// gates/eslint.sage.mjs — kavya's promoted rules as ESLint 9+ flat config.
// origin: F-0023 (silent oracle). Degrades RULE-BY-RULE when a plugin is
// absent, instead of failing the whole gate. Core rules always run.
import { createRequire } from 'node:module'
const require = createRequire(import.meta.url)
const tryReq = (n) => { try { return require(n) } catch { return null } }

const cfg = [
  { ignores: ['node_modules/**', '.next/**', 'dist/**', 'build/**', 'out/**', '.proof-os/**'] },
  { // core — zero dependencies, always active
    files: ['**/*.{js,jsx,mjs,cjs}'],
    rules: {
      'no-console': ['error', { allow: ['warn', 'error'] }],
      'no-unused-vars': 'error',
      'no-restricted-syntax': [
        'error',
        { selector: "JSXAttribute[name.name='style']", message: 'kavya: no inline styles — Tailwind only' },
      ],
    },
  },
]

const tsParser = tryReq('@typescript-eslint/parser')
const tsPlugin = tryReq('@typescript-eslint/eslint-plugin')
if (tsParser && tsPlugin) {
  cfg.push({
    files: ['**/*.{ts,tsx}'],
    languageOptions: { parser: tsParser },
    plugins: { '@typescript-eslint': tsPlugin },
    rules: {
      '@typescript-eslint/no-explicit-any': 'error',
      'no-unused-vars': 'off',
      '@typescript-eslint/no-unused-vars': 'error',
      'no-console': ['error', { allow: ['warn', 'error'] }],
    },
  })
} // no parser -> ts files skipped, NOT crashed; tsc still covers them

const a11y = tryReq('eslint-plugin-jsx-a11y')
if (a11y) {
  cfg.push({
    files: ['**/*.{jsx,tsx}'],
    plugins: { 'jsx-a11y': a11y.default ?? a11y },
    rules: { 'jsx-a11y/alt-text': 'error', 'jsx-a11y/anchor-is-valid': 'error' },
  })
}
export default cfg
