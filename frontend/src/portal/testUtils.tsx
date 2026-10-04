import { render } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { vi } from 'vitest'
import type { ApplicationSummary, Journey, Readiness } from './api'
import { PortalApp } from './PortalApp'

export interface RecordedCall {
  method: string
  path: string
  body: unknown
}

type Reply = { status?: number; body?: unknown } | ((call: RecordedCall) => { status?: number; body?: unknown })

export interface Route {
  method: string
  path: string | RegExp
  reply: Reply
}

/** A fetch stand-in driven by a route table: first match wins; an unmatched call answers 599 and is recorded. */
export function mockFetch(routes: Route[]) {
  const calls: RecordedCall[] = []
  const unhandled: string[] = []
  const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input)
    const method = (init?.method ?? 'GET').toUpperCase()
    const call: RecordedCall = { method, path, body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined }
    calls.push(call)
    const route = routes.find((r) => r.method === method && (typeof r.path === 'string' ? r.path === path : r.path.test(path)))
    if (!route) {
      unhandled.push(`${method} ${path}`)
      return new Response(JSON.stringify({ detail: `unhandled ${method} ${path}` }), { status: 599 })
    }
    const r = typeof route.reply === 'function' ? route.reply(call) : route.reply
    const status = r.status ?? 200
    return new Response(r.body === undefined ? null : JSON.stringify(r.body), { status })
  })
  const find = (method: string, path: string | RegExp) =>
    calls.filter((c) => c.method === method && (typeof path === 'string' ? c.path === path : path.test(c.path)))
  return { fetchImpl: fetchImpl as unknown as typeof fetch, calls, unhandled, find }
}

export const get = (path: string, body: unknown, status = 200): Route => ({ method: 'GET', path: `/portal-api${path}`, reply: { status, body } })
export const post = (path: string, body: unknown, status = 200): Route => ({ method: 'POST', path: `/portal-api${path}`, reply: { status, body } })

export const BRANDING = { code: 'EDUCATION', name: 'Education Department', initial: 'E', accent: '#1d5f4a', accentDark: '#7fcfb3', onAccentDark: '#0b1f18' }
export const SESSION = { personId: 'EDU-0001', name: 'Asha Patil', department: 'EDUCATION' }

export const JOURNEY: Journey = {
  code: 'SCHOLARSHIP',
  name: 'Post-matric scholarship',
  description: 'Apply for a scholarship after class ten.',
  referencePrefix: 'SCH',
  slaHours: 72,
  consentPurpose: 'SCHOLARSHIP_ELIGIBILITY',
  requiredCategories: [
    { category: 'INCOME_CERTIFICATE', department: 'REVENUE' },
    { category: 'BANK_ACCOUNT', department: 'DBT' },
  ],
  form: [
    { name: 'course', label: 'Course name', type: 'text', required: true, options: [] },
    { name: 'year', label: 'Year of study', type: 'select', required: true, options: ['First', 'Second'] },
    { name: 'note', label: 'Anything else', type: 'text', required: false, options: [] },
  ],
}

export const OTHER_JOURNEY: Journey = { ...JOURNEY, code: 'LOAN', name: 'Education loan interest subsidy', description: 'Get help with loan interest.' }

export function readiness(over: { revenue?: boolean; dbt?: boolean; consentActive?: boolean; dbtLoginAvailable?: boolean } = {}): Readiness {
  return {
    journeyCode: 'SCHOLARSHIP',
    departments: [
      { departmentCode: 'REVENUE', departmentName: 'Revenue', categories: ['INCOME_CERTIFICATE'], linked: over.revenue ?? false, departmentLoginAvailable: true },
      { departmentCode: 'DBT', departmentName: 'Direct Benefit Transfer', categories: ['BANK_ACCOUNT'], linked: over.dbt ?? false, departmentLoginAvailable: over.dbtLoginAvailable ?? true },
    ],
    consentActive: over.consentActive ?? false,
  }
}

export const CONSENT = {
  requestId: 'req-1',
  purposeCode: 'SCHOLARSHIP_ELIGIBILITY',
  purposeText: 'I agree that Education may check my income and bank account to decide my scholarship.',
  categories: ['INCOME_CERTIFICATE', 'BANK_ACCOUNT'],
  providers: [
    { code: 'REVENUE', name: 'Revenue' },
    { code: 'DBT', name: 'Direct Benefit Transfer' },
  ],
  validityDays: 30,
}

export const APPLICATION: ApplicationSummary = {
  referenceNo: 'SCH-2026-0001',
  journeyCode: 'SCHOLARSHIP',
  status: 'VERIFIED',
  submittedAt: '2026-10-01T09:00:00Z',
  slaDueAt: '2026-10-04T09:00:00Z',
}

/** Routes every test needs: branding and a signed-in citizen. Put test-specific routes first. */
export const BASE: Route[] = [get('/config', BRANDING), get('/me', SESSION), get('/journeys', [JOURNEY, OTHER_JOURNEY]), get('/journeys/SCHOLARSHIP', JOURNEY)]

export function renderPortal({ route, routes, goTo = vi.fn() }: { route: string | { pathname: string; state?: unknown }; routes: Route[]; goTo?: (url: string) => void }) {
  const fetchMock = mockFetch([...routes, ...BASE])
  const view = render(
    <MemoryRouter initialEntries={[route]}>
      <PortalApp fetchImpl={fetchMock.fetchImpl} goTo={goTo} />
    </MemoryRouter>,
  )
  return { ...fetchMock, goTo, ...view }
}
