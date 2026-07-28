import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'
import { execSync } from 'child_process'

/**
 * CR-11 — `__APP_BUILD_ID__`, a Vite `define` (compile-time text substitution, not a
 * runtime env var). Without it a minified stack trace posted to `/api/v1/client-errors`
 * can't be tied back to the build that produced it, which is most of the value of
 * capturing `stack`/`componentStack` at all (wiki/tech/cr-11-client-error-contract.md).
 *
 * Short git SHA at build time; falls back to a timestamp so a build taken outside a git
 * checkout (a tarball export, a CI cache with `.git` stripped) still produces something
 * rather than failing the build over a non-essential id. It is a build identifier, not a
 * secret — safe to ship in the client bundle.
 */
function getAppBuildId(): string {
  try {
    return execSync('git rev-parse --short HEAD', { stdio: ['ignore', 'pipe', 'ignore'] })
      .toString()
      .trim()
  } catch {
    return `t${Date.now()}`
  }
}

// https://vitejs.dev/config/
export default defineConfig(({ mode, command }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_')

  // W0-2: a production build must never be able to silently ship in mock mode.
  // src/lib/api.ts already fails closed *at runtime* (MockAuthDisabledError on
  // first login attempt), but that still means a broken prod deploy reaches
  // users before anyone notices. Fail the build itself instead: `vite build`
  // defaults to mode=production, and .env.production pins these two vars — if
  // they're missing/misconfigured (deleted file, bad override, still pointing
  // at localhost), stop the build here.
  if (command === 'build' && mode === 'production') {
    if (env.VITE_API_MODE !== 'live') {
      throw new Error(
        `[vite.config] Refusing to build: VITE_API_MODE must be "live" for a ` +
          `production build (got ${JSON.stringify(env.VITE_API_MODE)}). ` +
          `Check .env.production.`
      )
    }
    if (!env.VITE_API_BASE_URL || /localhost|127\.0\.0\.1/.test(env.VITE_API_BASE_URL)) {
      throw new Error(
        `[vite.config] Refusing to build: VITE_API_BASE_URL must be set to the ` +
          `real influora-api URL for a production build (got ` +
          `${JSON.stringify(env.VITE_API_BASE_URL)}). Check .env.production.`
      )
    }
  }

  return {
    plugins: [react()],
    define: {
      __APP_BUILD_ID__: JSON.stringify(getAppBuildId()),
    },
    resolve: {
      alias: {
        '@': path.resolve(__dirname, './src'),
      },
    },
    server: {
      // PORT env override lets a second dev instance (e.g. a preview harness)
      // run alongside the default 3000 without fighting over the port.
      port: Number(process.env.PORT) || 3000,
      host: true,
      allowedHosts: ['sb-s0hdyco25nig.vercel.run'],
      // The admin console (src/admin/services/api-contracts.ts) calls the backend
      // with same-origin RELATIVE paths under `/api/v1/admin`. Without this proxy
      // those hit the Vite dev server (3000) and 404, so the whole admin panel is
      // unreachable in dev. Forward the versioned API prefix to the Spring backend.
      proxy: {
        '/api/v1': {
          target: 'http://localhost:8080',
          changeOrigin: true,
        },
      },
    },
  }
})
