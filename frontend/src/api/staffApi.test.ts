import { describe, expect, it } from 'vitest'
import { ApiClient } from './client'
import { createStaffApi } from './staffApi'
import { mockFetch } from '../test/utils'

const ID = '55555555-5555-4555-8555-555555555555'

type Api = ReturnType<typeof createStaffApi>

// Pins every staff endpoint to the controller route it targets (SecurityConfig lists the
// role each one needs), so a rename on the Java side or a typo here fails a test.
describe('staff API endpoints', () => {
  const cases: [string, (a: Api) => Promise<unknown>, string, string, unknown][] = [
    ['listExceptions', (a) => a.listExceptions(), 'GET', '/api/journeys/exceptions', undefined],
    ['getInstance', (a) => a.getInstance(ID), 'GET', `/api/journeys/instances/${ID}`, undefined],
    ['retryInstance', (a) => a.retryInstance(ID), 'POST', `/api/journeys/instances/${ID}/retry`, undefined],
    ['approveApplication', (a) => a.approveApplication(ID), 'POST', `/api/journeys/instances/${ID}/approve`, undefined],
    ['listBankReviews', (a) => a.listBankReviews(), 'GET', '/api/officer/bank-reviews', undefined],
    ['requestDocument', (a) => a.requestDocument(ID), 'POST', `/api/officer/bank-reviews/${ID}/request-document`, undefined],
    ['approveBankReview', (a) => a.approveBankReview(ID, 'looks right'), 'POST', `/api/officer/bank-reviews/${ID}/approve`, { reason: 'looks right' }],
    ['approveBankReview without a reason', (a) => a.approveBankReview(ID), 'POST', `/api/officer/bank-reviews/${ID}/approve`, { reason: '' }],
    ['rejectBankReview', (a) => a.rejectBankReview(ID, 'wrong holder'), 'POST', `/api/officer/bank-reviews/${ID}/reject`, { reason: 'wrong holder' }],
    ['listApplications', (a) => a.listApplications(50), 'GET', '/api/applications?size=50', undefined],
    ['getApplication', (a) => a.getApplication('SCH 1'), 'GET', '/api/applications/SCH%201', undefined],
    ['getApplicationSteps', (a) => a.getApplicationSteps('SCH-1'), 'GET', '/api/applications/SCH-1/steps', undefined],
    ['getIssuedRecords', (a) => a.getIssuedRecords('SCH-1'), 'GET', '/api/applications/SCH-1/issued-records', undefined],
    ['getMetrics', (a) => a.getMetrics(), 'GET', '/api/ops/metrics', undefined],
    ['auditHead', (a) => a.auditHead(), 'GET', '/api/audit/head', undefined],
    ['auditCheckpoint', (a) => a.auditCheckpoint(), 'GET', '/api/audit/checkpoint', undefined],
    ['auditVerify', (a) => a.auditVerify(), 'GET', '/api/audit/verify', undefined],
    ['auditVerify range', (a) => a.auditVerify(3, 9), 'GET', '/api/audit/verify?from=3&to=9', undefined],
    ['auditEntries', (a) => a.auditEntries({ action: 'GRANT_DENIED' }), 'GET', '/api/audit/entries?action=GRANT_DENIED&size=40', undefined],
    ['listDepartments', (a) => a.listDepartments(), 'GET', '/api/catalog/departments', undefined],
    ['listJourneys', (a) => a.listJourneys(), 'GET', '/api/catalog/journeys', undefined],
    ['listConnectors', (a) => a.listConnectors(), 'GET', '/api/catalog/connectors', undefined],
    ['listSchemas', (a) => a.listSchemas(), 'GET', '/api/catalog/schemas', undefined],
    [
      'registerDepartment',
      (a) => a.registerDepartment({ code: 'FIRE', name: 'Fire', idpRealm: 'fire', contactEmail: 'f@x.gov', defaultSlaMs: 3000 }),
      'POST',
      '/api/catalog/departments',
      { code: 'FIRE', name: 'Fire', idpRealm: 'fire', contactEmail: 'f@x.gov', defaultSlaMs: 3000 },
    ],
    [
      'registerDataSource',
      (a) => a.registerDataSource({ code: 'ds', departmentCode: 'FIRE', protocol: 'REST', baseHost: 'h.gov', authType: 'NONE', authConfigRef: 'secret:none' }),
      'POST',
      '/api/catalog/data-sources',
      { code: 'ds', departmentCode: 'FIRE', protocol: 'REST', baseHost: 'h.gov', authType: 'NONE', authConfigRef: 'secret:none' },
    ],
    [
      'createConnectorDraft',
      (a) => a.createConnectorDraft({ connectorId: 'c', dataSourceCode: 'ds', category: { code: 'MARKS' }, capabilitiesJson: '{}', inputsJson: '[]', slaMs: null }),
      'POST',
      '/api/catalog/connectors',
      { connectorId: 'c', dataSourceCode: 'ds', category: { code: 'MARKS' }, capabilitiesJson: '{}', inputsJson: '[]', slaMs: null },
    ],
    [
      'importOpenApi',
      (a) => a.importOpenApi({ spec: '{}', operationId: 'getMarks', targetSchemaRef: 'Credential/Marks@1' }),
      'POST',
      '/api/catalog/import/openapi',
      { spec: '{}', operationId: 'getMarks', targetSchemaRef: 'Credential/Marks@1' },
    ],
    [
      'saveMapping',
      (a) => a.saveMapping({ ref: 'm@1', connectorRef: 'c@1', rules: [{ source: 'a', target: 'b', transforms: [] }] }),
      'POST',
      '/api/catalog/mappings',
      { ref: 'm@1', connectorRef: 'c@1', rules: [{ source: 'a', target: 'b', transforms: [] }] },
    ],
    ['testConnector', (a) => a.testConnector('c@1'), 'POST', '/api/catalog/connectors/c%401/test', undefined],
    [
      'publishConnector',
      (a) => a.publishConnector('c@1', { passed: true, failures: [] }),
      'POST',
      '/api/catalog/connectors/c%401/publish',
      { passed: true, failures: [] },
    ],
    ['reviewQueue', (a) => a.reviewQueue(20), 'GET', '/api/identity/review-queue?size=20', undefined],
    ['confirmCandidate', (a) => a.confirmCandidate(ID, 'checked'), 'POST', `/api/identity/candidates/${ID}/confirm`, { note: 'checked' }],
    ['confirmCandidate no note', (a) => a.confirmCandidate(ID), 'POST', `/api/identity/candidates/${ID}/confirm`, {}],
    ['rejectCandidate', (a) => a.rejectCandidate(ID, 'no'), 'POST', `/api/identity/candidates/${ID}/reject`, { note: 'no' }],
  ]

  it.each(cases)('%s', async (_name, call, method, path, body) => {
    const { fetchImpl, calls } = mockFetch([{ method, path, reply: { body: {} } }])
    await call(createStaffApi(new ApiClient({ getToken: async () => 't', fetchImpl })))
    expect(calls).toHaveLength(1)
    expect(calls[0]?.method).toBe(method)
    expect(calls[0]?.path).toBe(path)
    expect(calls[0]?.body).toEqual(body)
    expect(calls[0]?.headers.get('Authorization')).toBe('Bearer t')
  })

  it('uploads a passbook as multipart form data, letting the browser set the boundary', async () => {
    let seen: { body: unknown; contentType: string | null } | null = null
    const fetchImpl = (async (_url: RequestInfo | URL, init?: RequestInit) => {
      seen = { body: init?.body, contentType: new Headers(init?.headers).get('Content-Type') }
      return new Response(null, { status: 200 })
    }) as typeof fetch
    const file = new File([new Uint8Array([0x25, 0x50, 0x44, 0x46])], 'passbook.pdf', { type: 'application/pdf' })
    await createStaffApi(new ApiClient({ getToken: async () => 't', fetchImpl })).uploadPassbook(ID, file)
    const s = seen as unknown as { body: FormData; contentType: string | null }
    expect(s.body).toBeInstanceOf(FormData)
    expect((s.body.get('file') as File).name).toBe('passbook.pdf')
    expect(s.contentType).toBeNull()
  })

  it('exposes both approve calls: the bank-account review and the application', () => {
    const api = createStaffApi(new ApiClient({ getToken: async () => 't', fetchImpl: mockFetch([]).fetchImpl }))
    expect(Object.keys(api).filter((k) => /approve/i.test(k)).sort()).toEqual(['approveApplication', 'approveBankReview'])
  })
})
