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
    ignores: ['dist', 'build', 'node_modules', 'app', 'components', 'hooks', 'lib'],
  },
  {
    files: ['src/**/*.{ts,tsx}'],
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    languageOptions: {
      ecmaVersion: 2020,
      globals: globals.browser,
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
