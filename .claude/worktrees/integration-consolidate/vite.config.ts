import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'

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
    resolve: {
      alias: {
        '@': path.resolve(__dirname, './src'),
      },
    },
    server: {
      port: 3000,
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
