import type { ProblemDetail } from './types'

/** A non-2xx response (or a network failure, status 0) from the API. */
export class ApiError extends Error {
  readonly status: number
  readonly problem: ProblemDetail | null

  constructor(status: number, message: string, problem: ProblemDetail | null = null) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.problem = problem
  }

  get reason(): string | undefined {
    return this.problem?.reason
  }
}

/** No usable access token: the caller is signed out or the token has expired. */
export class AuthRequiredError extends Error {
  constructor() {
    super('Sign in required')
    this.name = 'AuthRequiredError'
  }
}

export interface ApiClientOptions {
  /** Resolves the current bearer token, or null when there is none / it has expired. */
  getToken: () => Promise<string | null>
  /** Called when the server answers 401, so the session layer can drop the dead token. */
  onUnauthorized?: () => void
  /** Prefix for every path; '' means same origin (dev proxy or Spring static serving). */
  baseUrl?: string
  /** Injectable for tests. Defaults to the global fetch, looked up at call time. */
  fetchImpl?: typeof fetch
}

export type Query = Record<string, string | number | undefined>

/**
 * Thin fetch wrapper: attaches the bearer token, sends/receives JSON, and turns failures
 * into ApiError carrying the server's RFC 7807 problem body. The server takes the actor
 * only from the token; nothing here sends identity headers.
 */
export class ApiClient {
  private readonly opts: ApiClientOptions

  constructor(opts: ApiClientOptions) {
    this.opts = opts
  }

  get<T>(path: string, query?: Query): Promise<T> {
    return this.request<T>('GET', path, undefined, query)
  }

  post<T>(path: string, body?: unknown): Promise<T> {
    return this.request<T>('POST', path, body)
  }

  private async request<T>(method: string, path: string, body?: unknown, query?: Query): Promise<T> {
    const token = await this.opts.getToken()
    if (!token) throw new AuthRequiredError()

    const headers: Record<string, string> = {
      Accept: 'application/json',
      Authorization: `Bearer ${token}`,
    }
    if (body !== undefined) headers['Content-Type'] = 'application/json'

    const doFetch = this.opts.fetchImpl ?? ((...a: Parameters<typeof fetch>) => globalThis.fetch(...a))
    let res: Response
    try {
      res = await doFetch(buildUrl(this.opts.baseUrl ?? '', path, query), {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
      })
    } catch {
      throw new ApiError(0, 'The service could not be reached.')
    }

    const parsed = await readBody(res)
    if (!res.ok) {
      if (res.status === 401) this.opts.onUnauthorized?.()
      const problem = isProblem(parsed) ? parsed : null
      throw new ApiError(
        res.status,
        problem?.detail || problem?.title || res.statusText || `Request failed (${res.status})`,
        problem,
      )
    }
    return parsed as T
  }
}

export function buildUrl(base: string, path: string, query?: Query): string {
  const params = new URLSearchParams()
  if (query) {
    for (const [k, v] of Object.entries(query)) {
      if (v !== undefined) params.set(k, String(v))
    }
  }
  const qs = params.toString()
  return `${base}${path}${qs ? `?${qs}` : ''}`
}

async function readBody(res: Response): Promise<unknown> {
  const text = await res.text()
  if (!text) return undefined
  try {
    return JSON.parse(text)
  } catch {
    return text
  }
}

function isProblem(v: unknown): v is ProblemDetail {
  return typeof v === 'object' && v !== null && !Array.isArray(v)
}
