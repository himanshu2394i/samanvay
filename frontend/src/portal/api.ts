// Client for the department's own /portal-api. Same origin, cookie session set by the server.
// The browser never holds a Samanvay token; it only talks to the department it is on.

export interface Branding {
  code: string
  name: string
  initial: string
  accent: string
  accentDark: string
  onAccentDark: string
}

export interface Session {
  personId: string
  name: string
  department: string
}

export interface FormField {
  name: string
  label: string
  type: 'text' | 'select'
  required: boolean
  options?: string[] | null
}

export interface Journey {
  code: string
  name: string
  description: string
  referencePrefix?: string
  slaHours?: number
  consentPurpose?: string
  requiredCategories: { category: string; department: string }[]
  form: FormField[]
}

export interface ReadinessDepartment {
  departmentCode: string
  departmentName: string
  categories: string[]
  linked: boolean
  departmentLoginAvailable: boolean
}

export interface Readiness {
  journeyCode: string
  departments: ReadinessDepartment[]
  consentActive: boolean
}

export interface ConsentPreview {
  requestId: string
  purposeCode: string
  purposeText: string
  categories: string[]
  providers: { code: string; name: string }[]
  validityDays: number
}

export interface ApplicationSummary {
  referenceNo: string
  journeyCode: string
  status: string
  slaDueAt?: string | null
  submittedAt?: string | null
}

export interface ApplicationStep {
  stepCode: string
  departmentCode?: string | null
  status: string
}

export class PortalError extends Error {
  readonly status: number
  constructor(status: number, message: string) {
    super(message)
    this.name = 'PortalError'
    this.status = status
  }
}

/** The service (or the network to it) is down for now: worth a "Try again". */
export function isUnavailable(e: unknown): boolean {
  return e instanceof PortalError && (e.status === 0 || e.status === 502 || e.status === 503 || e.status === 504)
}

/** Plain words for anything the API layer can throw. */
export function errorText(e: unknown): string {
  if (isUnavailable(e)) {
    return e instanceof PortalError && e.status === 0
      ? e.message
      : 'This service is temporarily unavailable. Please try again in a moment.'
  }
  if (e instanceof PortalError) return e.message
  return 'Something went wrong. Please try again.'
}

function fallbackMessage(status: number): string {
  if (status === 404) return 'We could not find that.'
  if (status === 403) return 'You do not have access to that.'
  if (status >= 500) return 'This service is temporarily unavailable. Please try again in a moment.'
  return 'That did not work. Please check what you entered and try again.'
}

export type PortalApi = ReturnType<typeof createPortalApi>

/**
 * `onUnauthorized` runs on a 401 from any call except the sign-in ones (where 401 just means a wrong
 * password or code and the person stays on the form).
 */
export function createPortalApi(fetchImpl: typeof fetch, onUnauthorized: () => void) {
  async function request<T>(path: string, init: { method?: string; body?: unknown; quiet?: boolean } = {}): Promise<T> {
    const headers: Record<string, string> = { Accept: 'application/json' }
    if (init.body !== undefined) headers['Content-Type'] = 'application/json'
    let res: Response
    try {
      res = await fetchImpl(`/portal-api${path}`, {
        method: init.method ?? 'GET',
        credentials: 'same-origin',
        headers,
        body: init.body === undefined ? undefined : JSON.stringify(init.body),
      })
    } catch {
      throw new PortalError(0, 'We could not reach the service. Check your connection and try again.')
    }
    const text = res.status === 204 ? '' : await res.text()
    if (res.ok) return (text ? JSON.parse(text) : undefined) as T
    let detail = ''
    try {
      const j: unknown = JSON.parse(text)
      if (j && typeof j === 'object' && 'detail' in j && typeof j.detail === 'string') detail = j.detail
    } catch {
      // not JSON: use the fallback wording
    }
    if (res.status === 401 && !init.quiet) onUnauthorized()
    throw new PortalError(res.status, detail || fallbackMessage(res.status))
  }

  const j = (code: string) => `/journeys/${encodeURIComponent(code)}`
  const a = (ref: string) => `/applications/${encodeURIComponent(ref)}`

  return {
    config: () => request<Branding>('/config', { quiet: true }),
    me: () => request<Session>('/me', { quiet: true }),
    signIn: (mobile: string, password: string) =>
      request<{ ticket: string; masked: string }>('/sign-in', { method: 'POST', body: { mobile, password }, quiet: true }),
    verify: (ticket: string, code: string) =>
      request<Session>('/verify', { method: 'POST', body: { ticket, code }, quiet: true }),
    signOut: () => request<void>('/sign-out', { method: 'POST', quiet: true }),
    journeys: () => request<Journey[]>('/journeys'),
    journey: (code: string) => request<Journey>(j(code)),
    readiness: (code: string) => request<Readiness>(`${j(code)}/readiness`),
    startLink: (code: string, dept: string) =>
      request<{ loginUrl: string }>(`${j(code)}/links/${encodeURIComponent(dept)}`, { method: 'POST', body: {} }),
    consent: (code: string) => request<ConsentPreview>(`${j(code)}/consent`),
    // 401 here means a wrong one-time code, not a lost session, so it must not sign the person out.
    confirmConsent: (code: string, requestId: string, otp: string) =>
      request<{ granted: boolean }>(`${j(code)}/consent`, { method: 'POST', body: { requestId, code: otp }, quiet: true }),
    submit: (code: string, submission: Record<string, string>) =>
      request<{ id?: string }>(`${j(code)}/submit`, { method: 'POST', body: { submission } }),
    applications: () => request<ApplicationSummary[]>('/applications'),
    application: (ref: string) => request<ApplicationSummary>(a(ref)),
    steps: (ref: string) => request<ApplicationStep[]>(`${a(ref)}/steps`),
    records: (ref: string) => request<unknown[]>(`${a(ref)}/records`),
  }
}
