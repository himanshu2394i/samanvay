// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * The shared static-portal script that sends a citizen to a department's own login and completes the link when they come
 * back (src/main/resources/static/shared/dept-login.js). It is a classic browser script (an IIFE on `window`), so it is
 * loaded for its side effect and driven through `window.SamanvayDeptLogin`.
 */

interface DeptLogin {
  start(args: { citizenId: string; departmentCode: string; returnPath: string }): Promise<void>
  complete(search: string): Promise<{ returnPath: string }>
  navigate: (url: string) => void
}

declare global {
  interface Window {
    SamanvayAuth?: { fetch: (path: string, opts: RequestInit, realm: string) => Promise<Response> }
    SamanvayDeptLogin: DeptLogin
  }
}

const CITIZEN = '11111111-1111-4111-8111-111111111111'
const KEY = 'samanvay.deptLogin'

function reply(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('department login (static portals)', () => {
  let calls: { path: string; opts: RequestInit; realm: string }[]
  let next: Response[]

  beforeEach(async () => {
    sessionStorage.clear()
    calls = []
    next = []
    window.SamanvayAuth = {
      fetch: vi.fn(async (path: string, opts: RequestInit, realm: string) => {
        calls.push({ path, opts, realm })
        return next.shift() ?? reply(500, { detail: 'no reply queued' })
      }),
    }
    vi.resetModules()
    await import('../../../src/main/resources/static/shared/dept-login.js' as string)
    window.SamanvayDeptLogin.navigate = vi.fn()
  })

  it('asks the server for the department login, remembers what was started, and sends the browser there', async () => {
    next.push(reply(200, { loginUrl: 'https://revenue.example.gov/login?return_to=x&state=s&nonce=n' }))
    await window.SamanvayDeptLogin.start({ citizenId: CITIZEN, departmentCode: 'REVENUE', returnPath: '/scholarship/#apply' })

    expect(calls).toHaveLength(1)
    expect(calls[0]?.path).toBe('/api/identity/department-login')
    expect(calls[0]?.realm).toBe('citizen')
    expect(calls[0]?.opts.method).toBe('POST')
    expect(JSON.parse(String(calls[0]?.opts.body))).toEqual({
      citizenId: CITIZEN,
      departmentCode: 'REVENUE',
      returnTo: `${location.origin}/shared/dept-callback.html`,
    })
    expect(JSON.parse(sessionStorage.getItem(KEY) ?? '{}')).toEqual({
      citizenId: CITIZEN,
      departmentCode: 'REVENUE',
      returnPath: '/scholarship/#apply',
    })
    expect(window.SamanvayDeptLogin.navigate).toHaveBeenCalledWith('https://revenue.example.gov/login?return_to=x&state=s&nonce=n')
  })

  it('does not navigate or remember anything when the server refuses to start a login', async () => {
    next.push(reply(400, { detail: 'This department does not offer a login to link with' }))
    await expect(
      window.SamanvayDeptLogin.start({ citizenId: CITIZEN, departmentCode: 'DBT', returnPath: '/x' }),
    ).rejects.toThrow('This department does not offer a login to link with')
    expect(window.SamanvayDeptLogin.navigate).not.toHaveBeenCalled()
    expect(sessionStorage.getItem(KEY)).toBeNull()
  })

  it('refuses a login address that is not http(s), even if the server returned it', async () => {
    next.push(reply(200, { loginUrl: 'javascript:alert(1)' }))
    await expect(
      window.SamanvayDeptLogin.start({ citizenId: CITIZEN, departmentCode: 'REVENUE', returnPath: '/x' }),
    ).rejects.toThrow(/login address/i)
    expect(window.SamanvayDeptLogin.navigate).not.toHaveBeenCalled()
  })

  it('on return, links the department with the assertion and goes back to where the citizen was', async () => {
    sessionStorage.setItem(KEY, JSON.stringify({ citizenId: CITIZEN, departmentCode: 'REVENUE', returnPath: '/scholarship/#apply' }))
    next.push(reply(200, { id: 'link-1' }))

    const out = await window.SamanvayDeptLogin.complete('?assertion=aaa.bbb.ccc&state=st-1')

    expect(out.returnPath).toBe('/scholarship/#apply')
    expect(calls[0]?.path).toBe('/api/identity/links')
    expect(JSON.parse(String(calls[0]?.opts.body))).toEqual({
      citizenId: CITIZEN,
      departmentCode: 'REVENUE',
      localIdType: '',
      localId: '',
      provider: 'DEPT_ASSERTION',
      proof: 'aaa.bbb.ccc',
    })
    expect(sessionStorage.getItem(KEY)).toBeNull() // used once
  })

  it('refuses to complete a login that was never started in this browser, and sends nothing', async () => {
    await expect(window.SamanvayDeptLogin.complete('?assertion=a.b.c&state=s')).rejects.toThrow(/start again/i)
    expect(calls).toHaveLength(0)
  })

  it('refuses a return with no assertion, or one where the department reported an error, and forgets the pending login', async () => {
    sessionStorage.setItem(KEY, JSON.stringify({ citizenId: CITIZEN, departmentCode: 'REVENUE', returnPath: '/x' }))
    await expect(window.SamanvayDeptLogin.complete('?state=s')).rejects.toThrow(/did not send/i)
    sessionStorage.setItem(KEY, JSON.stringify({ citizenId: CITIZEN, departmentCode: 'REVENUE', returnPath: '/x' }))
    await expect(window.SamanvayDeptLogin.complete('?error=access_denied')).rejects.toThrow(/did not complete/i)
    expect(calls).toHaveLength(0)
    expect(sessionStorage.getItem(KEY)).toBeNull()
  })

  it('shows the servers refusal when the proof is rejected, and still forgets the pending login', async () => {
    sessionStorage.setItem(KEY, JSON.stringify({ citizenId: CITIZEN, departmentCode: 'REVENUE', returnPath: '/x' }))
    next.push(reply(401, { detail: 'Department identity proof is invalid' }))
    await expect(window.SamanvayDeptLogin.complete('?assertion=a.b.c&state=s')).rejects.toThrow('Department identity proof is invalid')
    expect(sessionStorage.getItem(KEY)).toBeNull()
  })

  it('never returns the citizen to another site, whatever was remembered', async () => {
    for (const bad of ['https://evil.example/', '//evil.example/x', 'javascript:alert(1)', '']) {
      sessionStorage.setItem(KEY, JSON.stringify({ citizenId: CITIZEN, departmentCode: 'REVENUE', returnPath: bad }))
      next.push(reply(200, {}))
      const out = await window.SamanvayDeptLogin.complete('?assertion=a.b.c&state=s')
      expect(out.returnPath, `returnPath=${bad}`).toBe('/')
    }
  })

  it('tolerates a corrupted pending entry as a login that was never started', async () => {
    sessionStorage.setItem(KEY, '{not json')
    await expect(window.SamanvayDeptLogin.complete('?assertion=a.b.c&state=s')).rejects.toThrow(/start again/i)
  })
})
