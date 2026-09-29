/// <reference types="vitest/config" />
import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

// Dev server: the SPA runs on :5173 and proxies to the Samanvay API (:8080), so the
// browser sees one origin for /api and /ui and needs no CORS on the Spring app.
//
// Keycloak is NOT proxied: the API only accepts tokens whose `iss` is exactly the
// issuer it is configured with (http://localhost:8180/realms/samanvay-citizen in the
// dev profile) and docker compose pins Keycloak's public hostname to that URL. The
// browser therefore talks to Keycloak directly; the realm client allows this origin
// for redirects and CORS (see keycloak/gen_realms.py).
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const api = env.SAMANVAY_API_URL || 'http://localhost:8080'
  return {
    // Relative asset URLs so the same build works at /, or copied to
    // src/main/resources/static/app/ and served by Spring at /app/.
    base: './',
    plugins: [react()],
    server: {
      port: 5173,
      strictPort: true,
      proxy: {
        '/api': { target: api, changeOrigin: true },
        '/ui': { target: api, changeOrigin: true },
      },
    },
    build: { sourcemap: true },
    test: {
      environment: 'jsdom',
      globals: true,
      setupFiles: ['./src/test/setup.ts'],
      css: false,
      restoreMocks: true,
    },
  }
})
