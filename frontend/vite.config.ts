/// <reference types="vitest/config" />
import { defineConfig, loadEnv, type UserConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Dev server: the SPA runs on :5173 and proxies to the Samanvay API (:8080), so the
// browser sees one origin for /api and /ui and needs no CORS on the Spring app.
//
// Keycloak is NOT proxied: the API only accepts tokens whose `iss` is exactly the
// issuer it is configured with (http://localhost:8180/realms/samanvay-citizen in the
// dev profile) and docker compose pins Keycloak's public hostname to that URL. The
// browser therefore talks to Keycloak directly; the realm client allows this origin
// for redirects and CORS (see keycloak/gen_realms.py).
export default defineConfig(({ mode }): UserConfig => {
  const env = loadEnv(mode, process.cwd(), '')
  const api = env.SAMANVAY_API_URL || 'http://localhost:8080'

  // `vite build --mode portal`: the citizen portal every department serves at /portal/ (its own
  // entry in portal/index.html, output in dist-portal/, copied by scripts/build-portal.sh).
  // `vite --mode portal` serves it in dev, with /portal-api proxied to a department service.
  if (mode === 'portal') {
    const department = env.PORTAL_DEPARTMENT_URL || 'http://localhost:8081'
    return {
      root: 'portal',
      base: '/portal/',
      plugins: [react()],
      server: { port: 5174, strictPort: true, proxy: { '/portal-api': { target: department, changeOrigin: true } } },
      build: { outDir: '../dist-portal', emptyOutDir: true, sourcemap: false },
    }
  }

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
