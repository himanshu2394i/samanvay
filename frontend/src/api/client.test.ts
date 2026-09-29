import { describe, expect, it, vi } from 'vitest'
import { ApiClient, ApiError, AuthRequiredError, buildUrl } from './client'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function client(fetchImpl: typeof fetch, extra: Partial<ConstructorParameters<typeof ApiClient>[0]> = {}) {
  return new ApiClient({ getToken: async () => 'tok-123', fetchImpl, ...extra })
}

describe('ApiClient', () => {
  it('sends the bearer token and parses a JSON response', async () => {
    const fetchImpl = vi.fn(async () => json(200, [{ code: 'X' }]))
    const out = await client(fetchImpl as unknown as typeof fetch).get<{ code: string }[]>('/api/catalog/journeys')

    expect(out).toEqual([{ code: 'X' }])
    const [url, init] = fetchImpl.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toBe('/api/catalog/journeys')
    expect(init.method).toBe('GET')
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer tok-123')
    expect(init.body).toBeUndefined()
  })

  it('posts JSON with a content type', async () => {
    const fetchImpl = vi.fn(async () => json(200, { id: 'c1' }))
    await client(fetchImpl as unknown as typeof fetch).post('/api/consent/requests', { citizenId: 'a', purposeCode: 'P' })

    const [, init] = fetchImpl.mock.calls[0] as unknown as [string, RequestInit]
    expect(init.method).toBe('POST')
    expect(new Headers(init.headers).get('Content-Type')).toBe('application/json')
    expect(JSON.parse(init.body as string)).toEqual({ citizenId: 'a', purposeCode: 'P' })
  })

  it('returns undefined for an empty body (void endpoints)', async () => {
    const fetchImpl = vi.fn(async () => new Response(null, { status: 200 }))
    await expect(client(fetchImpl as unknown as typeof fetch).post('/x')).resolves.toBeUndefined()
  })

  it('does not call the network without a token', async () => {
    const fetchImpl = vi.fn()
    const c = new ApiClient({ getToken: async () => null, fetchImpl: fetchImpl as unknown as typeof fetch })
    await expect(c.get('/api/anything')).rejects.toBeInstanceOf(AuthRequiredError)
    expect(fetchImpl).not.toHaveBeenCalled()
  })

  it('turns a problem+json failure into an ApiError carrying the reason', async () => {
    const problem = {
      title: 'MissingDepartmentLinksException',
      status: 409,
      detail: 'Missing department links for X: REVENUE',
      reason: 'MISSING_DEPARTMENT_LINKS',
      missingDepartments: ['REVENUE'],
    }
    const fetchImpl = vi.fn(async () => json(409, problem))
    const err = await client(fetchImpl as unknown as typeof fetch)
      .post('/api/journeys/X/start', {})
      .catch((e: unknown) => e)

    expect(err).toBeInstanceOf(ApiError)
    const e = err as ApiError
    expect(e.status).toBe(409)
    expect(e.reason).toBe('MISSING_DEPARTMENT_LINKS')
    expect(e.problem?.missingDepartments).toEqual(['REVENUE'])
    expect(e.message).toBe(problem.detail)
  })

  it('reports 401 to the session layer', async () => {
    const onUnauthorized = vi.fn()
    const fetchImpl = vi.fn(async () => json(401, { title: 'Unauthorized', status: 401 }))
    await expect(client(fetchImpl as unknown as typeof fetch, { onUnauthorized }).get('/api/x')).rejects.toMatchObject({
      status: 401,
    })
    expect(onUnauthorized).toHaveBeenCalledOnce()
  })

  it('maps a network failure to status 0', async () => {
    const fetchImpl = vi.fn(async () => {
      throw new TypeError('Failed to fetch')
    })
    await expect(client(fetchImpl as unknown as typeof fetch).get('/api/x')).rejects.toMatchObject({ status: 0 })
  })

  it('tolerates a non-JSON error body', async () => {
    const fetchImpl = vi.fn(async () => new Response('Bad gateway', { status: 502, statusText: 'Bad Gateway' }))
    await expect(client(fetchImpl as unknown as typeof fetch).get('/api/x')).rejects.toMatchObject({
      status: 502,
      message: 'Bad Gateway',
    })
  })
})

describe('buildUrl', () => {
  it('encodes query values and drops undefined ones', () => {
    expect(buildUrl('', '/api/applications', { citizenId: 'a b', size: 5, page: undefined })).toBe(
      '/api/applications?citizenId=a+b&size=5',
    )
  })
})
