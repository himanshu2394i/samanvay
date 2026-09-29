import { describe, expect, it } from 'vitest'
import { createCitizenApi } from './citizenApi'
import { ApiClient } from './client'
import { mockFetch } from '../test/utils'

const CID = '11111111-1111-4111-8111-111111111111'

// Pins every citizen endpoint to the controller route it targets, so a rename on the
// Java side (or a typo here) fails a test rather than a citizen.
describe('citizen API endpoints', () => {
  const cases: [string, (api: ReturnType<typeof createCitizenApi>) => Promise<unknown>, string, string, unknown][] = [
    ['listJourneys', (a) => a.listJourneys(), 'GET', '/api/catalog/journeys', undefined],
    ['getJourney', (a) => a.getJourney('A B'), 'GET', '/api/catalog/journeys/A%20B', undefined],
    ['listDepartments', (a) => a.listDepartments(), 'GET', '/api/catalog/departments', undefined],
    [
      'registerSelf',
      (a) => a.registerSelf({ nameLatin: 'A B', dob: '2000-01-02', dobPrecision: 'DAY' }),
      'POST',
      '/api/identity/citizens',
      { nameLatin: 'A B', dob: '2000-01-02', dobPrecision: 'DAY' },
    ],
    ['getProfile', (a) => a.getProfile(CID), 'GET', `/api/identity/citizens/${CID}`, undefined],
    ['proofProviders', (a) => a.proofProviders(), 'GET', '/api/identity/proof-providers', undefined],
    [
      'connectAccounts',
      (a) => a.connectAccounts(CID, 'POST_MATRIC_SCHOLARSHIP'),
      'GET',
      `/api/identity/citizens/${CID}/connect-accounts?journeyCode=POST_MATRIC_SCHOLARSHIP`,
      undefined,
    ],
    [
      'assertLink',
      (a) =>
        a.assertLink({
          citizenId: CID,
          departmentCode: 'REVENUE',
          localIdType: 'RATION',
          localId: 'R-1',
          provider: 'LOCAL_ID_OTP',
          proof: '000000',
        }),
      'POST',
      '/api/identity/links',
      { citizenId: CID, departmentCode: 'REVENUE', localIdType: 'RATION', localId: 'R-1', provider: 'LOCAL_ID_OTP', proof: '000000' },
    ],
    [
      'requestConsent',
      (a) => a.requestConsent(CID, 'SCHOLARSHIP_ELIGIBILITY'),
      'POST',
      '/api/consent/requests',
      { citizenId: CID, purposeCode: 'SCHOLARSHIP_ELIGIBILITY' },
    ],
    ['grantConsent', (a) => a.grantConsent('req-1', CID), 'POST', '/api/consent/requests/req-1/grant', { citizenId: CID }],
    ['listConsents', (a) => a.listConsents(CID), 'GET', `/api/consent/citizens/${CID}`, undefined],
    ['revokeConsent', (a) => a.revokeConsent('c-1'), 'POST', '/api/consent/me/c-1/revoke', {}],
    ['revokeConsent with reason', (a) => a.revokeConsent('c-1', 'changed my mind'), 'POST', '/api/consent/me/c-1/revoke', { reason: 'changed my mind' }],
    [
      'startJourney',
      (a) => a.startJourney('POST_MATRIC_SCHOLARSHIP', CID),
      'POST',
      '/api/journeys/POST_MATRIC_SCHOLARSHIP/start',
      { citizenId: CID, submission: {} },
    ],
    ['listApplications', (a) => a.listApplications(CID, 20), 'GET', `/api/applications?citizenId=${CID}&size=20`, undefined],
    ['getApplication', (a) => a.getApplication('SCH-1'), 'GET', '/api/applications/SCH-1', undefined],
    ['getApplicationSteps', (a) => a.getApplicationSteps('SCH-1'), 'GET', '/api/applications/SCH-1/steps', undefined],
    ['getIssuedRecords', (a) => a.getIssuedRecords('SCH-1'), 'GET', '/api/applications/SCH-1/issued-records', undefined],
  ]

  it.each(cases)('%s', async (_name, call, method, path, body) => {
    const { fetchImpl, calls } = mockFetch([{ method, path, reply: { body: {} } }])
    const api = createCitizenApi(new ApiClient({ getToken: async () => 't', fetchImpl }))
    await call(api)
    expect(calls).toHaveLength(1)
    expect(calls[0]?.method).toBe(method)
    expect(calls[0]?.path).toBe(path)
    expect(calls[0]?.body).toEqual(body)
    expect(calls[0]?.headers.get('Authorization')).toBe('Bearer t')
  })
})
