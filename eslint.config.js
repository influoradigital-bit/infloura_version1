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
    ignores: ['dist', 'build', 'node_modules', 'app', 'components', 'hooks', 'lib', '.claude'],
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
      'react-refresh/only-export-components': [
        'warn',
        { allowConstantExport: true },
      ],
    },
  },
)
