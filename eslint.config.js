import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'

// Flat config for a React 19 + TypeScript + Vite project.
// The Vite app lives in `src/`; the root-level `app/`, `components/`, `hooks/`
// and `lib/` dirs are legacy and excluded from tsconfig, so we skip them here too.
export default tseslint.config(
  {
    // `.claude` holds ~10 full worktree copies of this repo (each with its own
    // src/ + tsconfig); `eslint .` would otherwise descend into all of them,
    // producing thousands of duplicate/parse errors. Ignore it outright.
    // `**/.venv/**` keeps `eslint .` out of vendored Python virtualenvs (e.g.
    // influora-ai/.venv holds Playwright's bundled JS, which ships its own eslint
    // rules — `notice/notice`, `internal-playwright/*` — and produced 34 phantom
    // problems that are not this app's code.
    ignores: ['dist', 'build', 'node_modules', 'app', 'components', 'hooks', 'lib', '.claude', '**/.venv/**'],
  },
  {
    files: ['src/**/*.{ts,tsx}'],
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    languageOptions: {
      ecmaVersion: 2020,
      globals: globals.browser,
      // Pin the TS project root to this repo. Without it, typescript-eslint 8's
      // parser walks ancestors and finds multiple candidate tsconfig roots (the
      // sibling `.claude/worktrees/*` copies each carry a tsconfig), which it
      // now reports as a parsing error on every file. Pinning resolves it.
      parserOptions: {
        tsconfigRootDir: import.meta.dirname,
      },
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      // react-hooks v7's `recommended` ships the entire React Compiler ruleset
      // at `error`. On inspection every one of these fired on idiomatic,
      // correct code for THIS (non-React-Compiler) codebase, not on bugs:
      //   set-state-in-effect  -> standard fetch-in-effect (setLoading/setData)
      //   purity               -> Date.now()/new Date() for display, Math.random
      //                           for decorative widths
      //   immutability         -> @react-three/fiber useFrame camera mutation
      //                           (the documented R3F animation pattern)
      //   refs                 -> the latest-ref idiom (ref.current = handler)
      //                           and detection-ref reads
      //   static-components    -> icon-map lookups (const Icon = resolve(...))
      //   preserve-manual-memoization / set-state-in-render -> same class
      // Per React's own incremental-adoption guidance these are warnings until
      // React Compiler is actually adopted. The classic always-real rules
      // (rules-of-hooks, exhaustive-deps, use-memo, error-boundaries, globals,
      // gating, config) are left at their recommended severity.
      'react-hooks/set-state-in-effect': 'warn',
      'react-hooks/set-state-in-render': 'warn',
      'react-hooks/purity': 'warn',
      'react-hooks/immutability': 'warn',
      'react-hooks/refs': 'warn',
      'react-hooks/static-components': 'warn',
      'react-hooks/preserve-manual-memoization': 'warn',
      'react-refresh/only-export-components': [
        'warn',
        { allowConstantExport: true },
      ],
      // Honour the `_`-prefix convention for intentionally-unused bindings
      // (params kept for signature/shape, disabled imports). Author already
      // marks these with a leading underscore; this makes the linter agree.
      '@typescript-eslint/no-unused-vars': [
        'error',
        {
          argsIgnorePattern: '^_',
          varsIgnorePattern: '^_',
          caughtErrorsIgnorePattern: '^_',
          destructuredArrayIgnorePattern: '^_',
        },
      ],
    },
  },
)
