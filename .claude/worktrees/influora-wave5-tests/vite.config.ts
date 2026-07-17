import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'

// https://vitejs.dev/config/
export default defineConfig({
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
})
