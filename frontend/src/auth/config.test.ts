import { describe, expect, it, vi } from 'vitest'
import { loadOidcConfig, parseAuthConfig } from './config'

const BODY = {
  devSignIn: true,
  realms: {
    staff: { issuer: 'http://localhost:8180/realms/samanvay-staff', clientId: 'samanvay-staff-ui' },
    citizen: { issuer: 'http://localhost:8180/realms/samanvay-citizen', clientId: 'samanvay-citizen-ui' },
  },
}

describe('parseAuthConfig', () => {
  it('picks the requested realm', () => {
    expect(parseAuthConfig(BODY, 'citizen')).toEqual({
      authority: 'http://localhost:8180/realms/samanvay-citizen',
      clientId: 'samanvay-citizen-ui',
      devSignIn: true,
    })
    expect(parseAuthConfig(BODY, 'staff').clientId).toBe('samanvay-staff-ui')
  })

  it('defaults devSignIn to false when the API does not send it (prod)', () => {
    const body = { realms: { citizen: { issuer: 'https://idp/realms/c', clientId: 'c-ui' } } }
    expect(parseAuthConfig(body, 'citizen').devSignIn).toBe(false)
  })

  it('rejects a realm without an issuer or client', () => {
    expect(() => parseAuthConfig({ realms: { citizen: { issuer: '', clientId: '' } } }, 'citizen')).toThrow(/not configured/)
    expect(() => parseAuthConfig({}, 'citizen')).toThrow(/not configured/)
    expect(() => parseAuthConfig(null, 'citizen')).toThrow(/not configured/)
  })
})

describe('loadOidcConfig', () => {
  it('reads the API auth-config by default', async () => {
    const fetchImpl = vi.fn(async () => new Response(JSON.stringify(BODY), { status: 200 }))
    const cfg = await loadOidcConfig('citizen', { fetchImpl: fetchImpl as unknown as typeof fetch, env: {} })
    expect(cfg.clientId).toBe('samanvay-citizen-ui')
    expect((fetchImpl.mock.calls[0] as unknown[])[0]).toBe('/ui/auth-config')
  })

  it('lets env vars override, without any request', async () => {
    const fetchImpl = vi.fn()
    const cfg = await loadOidcConfig('citizen', {
      fetchImpl: fetchImpl as unknown as typeof fetch,
      env: { VITE_OIDC_AUTHORITY: 'https://idp.example/realms/r', VITE_OIDC_CLIENT_ID: 'spa' },
    })
    expect(cfg).toEqual({ authority: 'https://idp.example/realms/r', clientId: 'spa', devSignIn: false })
    expect(fetchImpl).not.toHaveBeenCalled()
  })

  it('explains an unreachable API and an HTTP failure', async () => {
    const down = vi.fn(async () => {
      throw new TypeError('fetch failed')
    })
    await expect(loadOidcConfig('citizen', { fetchImpl: down as unknown as typeof fetch, env: {} })).rejects.toThrow(
      /Could not reach the Samanvay API/,
    )
    const bad = vi.fn(async () => new Response('nope', { status: 503 }))
    await expect(loadOidcConfig('citizen', { fetchImpl: bad as unknown as typeof fetch, env: {} })).rejects.toThrow(/HTTP 503/)
  })
})
