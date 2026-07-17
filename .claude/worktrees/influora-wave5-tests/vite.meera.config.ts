import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'
export default defineConfig({
  cacheDir: '/tmp/vite-cache',
  plugins: [react()],
  resolve: { alias: { '@': path.resolve(__dirname, './src') } },
  server: { port: 3000, host: true, allowedHosts: ['sb-s0hdyco25nig.vercel.run'] },
})
