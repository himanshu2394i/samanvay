import { render } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { vi } from 'vitest'
import { ApiProvider } from '../api/ApiProvider'
import { AuthContext, type AuthContextValue } from '../auth/authContext'
import { CitizenApp } from '../surfaces/citizen/CitizenApp'
import { StaffApp } from '../surfaces/staff/StaffApp'
import { writeCitizenId } from '../surfaces/citizen/lib/citizenStore'

export const SUB = 'sub-citizen-1'
export const CITIZEN_ID = '11111111-1111-4111-8111-111111111111'

export interface RecordedCall {
  method: string
  path: string
  headers: Headers
  body: unknown
}

type Reply = { status?: number; body?: unknown } | ((call: RecordedCall) => { status?: number; body?: unknown })

export interface Route {
  method: string
  /** Exact path including query string, or a RegExp tested against it. */
  path: string | RegExp
  reply: Reply
}

/**
 * A fetch stand-in driven by a route table. A request that matches no route fails the
 * test's assertions loudly (status 599 + recorded in `unhandled`) instead of hanging.
 */
export function mockFetch(routes: Route[]) {
  const calls: RecordedCall[] = []
  const unhandled: string[] = []
  const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const method = (init?.method ?? 'GET').toUpperCase()
    const call: RecordedCall = {
      method,
      path: url,
      headers: new Headers(init?.headers),
      body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined,
    }
    calls.push(call)
    const route = routes.find(
      (r) => r.method === method && (typeof r.path === 'string' ? r.path === url : r.path.test(url)),
    )
    if (!route) {
      unhandled.push(`${method} ${url}`)
      return new Response(JSON.stringify({ detail: `unhandled ${method} ${url}` }), { status: 599 })
    }
    const r = typeof route.reply === 'function' ? route.reply(call) : route.reply
    const status = r.status ?? 200
    return new Response(r.body === undefined ? null : JSON.stringify(r.body), {
      status,
      headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
    })
  })
  const find = (method: string, path: string | RegExp) =>
    calls.filter((c) => c.method === method && (typeof path === 'string' ? c.path === path : path.test(c.path)))
  return { fetchImpl: fetchImpl as unknown as typeof fetch, calls, unhandled, find }
}

export function signedInAuth(overrides: Partial<AuthContextValue> = {}): AuthContextValue {
  return {
    status: 'authenticated',
    user: { sub: SUB, name: 'Asha Patil', roles: ['CITIZEN'], department: null },
    notice: null,
    signIn: vi.fn(async () => {}),
    signOut: vi.fn(async () => {}),
    getAccessToken: vi.fn(async () => 'test-token'),
    expireSession: vi.fn(),
    realm: 'citizen',
    ...overrides,
  }
}

export function signedOutAuth(overrides: Partial<AuthContextValue> = {}): AuthContextValue {
  return signedInAuth({
    status: 'unauthenticated',
    user: null,
    getAccessToken: vi.fn(async () => null),
    ...overrides,
  })
}

interface RenderOptions {
  route: string
  fetchImpl: typeof fetch
  auth?: AuthContextValue
  /** Pre-register the citizen record id for the signed-in subject. */
  registered?: boolean
}

export function renderCitizen({ route, fetchImpl, auth = signedInAuth(), registered = true }: RenderOptions) {
  if (registered && auth.user) writeCitizenId(auth.user.sub, CITIZEN_ID)
  return {
    auth,
    ...render(
      <AuthContext.Provider value={auth}>
        <ApiProvider fetchImpl={fetchImpl}>
          <MemoryRouter initialEntries={[route]}>
            <CitizenApp />
          </MemoryRouter>
        </ApiProvider>
      </AuthContext.Provider>,
    ),
  }
}

/** A signed-in staff session whose token carries `roles` (lower-case, as Keycloak names them, or any case). */
export function staffAuth(roles: string[], overrides: Partial<AuthContextValue> = {}): AuthContextValue {
  return signedInAuth({
    realm: 'staff',
    user: { sub: 'sub-staff-1', name: 'Om Kulkarni', roles: roles.map((r) => r.toUpperCase()), department: 'SCHOLARSHIP' },
    ...overrides,
  })
}

export function renderStaff({ route, fetchImpl, auth }: { route: string; fetchImpl: typeof fetch; auth: AuthContextValue }) {
  return {
    auth,
    ...render(
      <AuthContext.Provider value={auth}>
        <ApiProvider fetchImpl={fetchImpl}>
          <MemoryRouter initialEntries={[route]}>
            <StaffApp />
          </MemoryRouter>
        </ApiProvider>
      </AuthContext.Provider>,
    ),
  }
}

// --- fixtures -----------------------------------------------------------------------------

export const SCHOLARSHIP = {
  code: 'POST_MATRIC_SCHOLARSHIP',
  name: 'Post-matric scholarship',
  bpmnRef: 'scholarship',
  requiredCategories: ['INCOME_CERTIFICATE', 'CASTE_CERTIFICATE', 'MARKS', 'BANK_ACCOUNT'],
  policy: {
    acceptStale: false,
    slaHours: 72,
    requester: 'SCHOLARSHIP',
    purpose: 'SCHOLARSHIP_ELIGIBILITY',
    referencePrefix: 'SCH',
    sources: {
      INCOME_CERTIFICATE: 'REVENUE',
      CASTE_CERTIFICATE: 'REVENUE',
      MARKS: 'EDUCATION',
      BANK_ACCOUNT: 'DBT',
    },
  },
  status: 'PUBLISHED',
  academicYearStartMonth: 6,
}

export const DRAFT_JOURNEY = { ...SCHOLARSHIP, code: 'DRAFT_ONE', name: 'Draft service', status: 'DRAFT' }

export const DEPARTMENTS = [
  { code: 'REVENUE', name: 'Revenue Department', status: 'ACTIVE' },
  { code: 'EDUCATION', name: 'Education Department', status: 'ACTIVE' },
  { code: 'DBT', name: 'Direct Benefit Transfer', status: 'ACTIVE' },
]

export const PROVIDERS = [
  { kind: 'LOCAL_ID_OTP', label: 'Local ID + OTP (demo)' },
  { kind: 'DEPT_IDP', label: 'Department sign-in (mock department IdP)' },
]

export function need(code: string, name: string, linked: boolean, categories: string[]) {
  return {
    departmentCode: code,
    departmentName: name,
    categories,
    linked,
    localIdType: linked ? code : null,
    localIdToken: linked ? 'tok-' + code : null,
  }
}
