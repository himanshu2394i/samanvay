import type { FieldMapping, MappingSuggestion } from '../../../api/staffTypes'

/** Document types a connector can fetch (the catalog's DataCategory codes the static console offers). */
export const CATEGORIES = [
  { code: 'INCOME_CERTIFICATE', label: 'Income certificate' },
  { code: 'CASTE_CERTIFICATE', label: 'Caste certificate' },
  { code: 'MARKS', label: 'Marks' },
  { code: 'BANK_ACCOUNT', label: 'Bank account' },
] as const

export const PROTOCOLS = ['REST', 'SOAP', 'SFTP_CSV', 'JDBC'] as const

interface CategoryDefaults {
  endpoint: string
  operationId: string
  schemaRef: string
  fields: Record<string, { type: string }>
}

const DEFAULTS: Record<string, CategoryDefaults> = {
  INCOME_CERTIFICATE: {
    endpoint: '/income',
    operationId: 'getIncome',
    schemaRef: 'Credential/IncomeCertificate@1',
    fields: { annual_income: { type: 'number' }, holder_name: { type: 'string' } },
  },
  CASTE_CERTIFICATE: {
    endpoint: '/caste',
    operationId: 'getCaste',
    schemaRef: 'Credential/CasteCertificate@1',
    fields: { caste_category: { type: 'string' } },
  },
  MARKS: {
    endpoint: '/marks',
    operationId: 'getMarks',
    schemaRef: 'Credential/Marks@1',
    fields: { percentage: { type: 'number' } },
  },
  BANK_ACCOUNT: {
    endpoint: '/bank',
    operationId: 'getBank',
    schemaRef: 'Credential/BankAccount@1',
    fields: { account_ref: { type: 'string' } },
  },
}

const FALLBACK: CategoryDefaults = { endpoint: '/x', operationId: 'getRecord', schemaRef: '', fields: {} }

export function categoryDefaults(category: string): CategoryDefaults {
  return DEFAULTS[category] ?? FALLBACK
}

/** The capabilities document a draft connector starts with: one FETCH call bound to the mapping. */
export function defaultCapabilities(category: string, mappingRef: string): string {
  return JSON.stringify({ FETCH: { endpoint: categoryDefaults(category).endpoint, mapping_ref: mappingRef } })
}

/** A minimal OpenAPI document for the category, so the importer has something to suggest from. */
export function defaultSpec(category: string): string {
  const d = categoryDefaults(category)
  return JSON.stringify(
    {
      openapi: '3.0.0',
      paths: {
        [d.endpoint]: {
          get: {
            operationId: d.operationId,
            responses: {
              '200': { content: { 'application/json': { schema: { type: 'object', properties: d.fields } } } },
            },
          },
        },
      },
    },
    null,
    2,
  )
}

/** Only the rows a person ticked become mapping rules; nothing is approved by default. */
export function approvedRules(suggestions: MappingSuggestion[], approved: ReadonlySet<number>): FieldMapping[] {
  return suggestions
    .filter((_, i) => approved.has(i))
    .map((s) => ({ source: s.source, target: s.target, transforms: [] }))
}

/** Parses the SLA text box: blank -> null (server default), otherwise a non-negative whole number of ms. */
export function parseSlaMs(raw: string): { value: number | null; error?: string } {
  const t = raw.trim()
  if (!t) return { value: null }
  if (!/^\d{1,8}$/.test(t)) return { value: null, error: 'Enter a whole number of milliseconds.' }
  return { value: Number(t) }
}

/** Finds a JSON error in a textarea before it is sent, so the server is not the first to say it. */
export function jsonError(text: string): string | null {
  try {
    JSON.parse(text)
    return null
  } catch {
    return 'This is not valid JSON.'
  }
}

/** A short unique-ish tail so demo ids do not collide between runs. */
export function idSuffix(now: number = Date.now()): string {
  return now.toString(36)
}
