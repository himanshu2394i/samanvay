import type { OpsMetrics } from '../api/staffTypes'

export const INSTANCE_ID = '22222222-2222-4222-8222-222222222222'
export const OTHER_INSTANCE_ID = '99999999-9999-4999-8999-999999999999'
export const REVIEW_ID = '66666666-6666-4666-8666-666666666666'
export const CITIZEN = '11111111-1111-4111-8111-111111111111'

export const exception = {
  id: '77777777-7777-4777-8777-777777777777',
  instanceId: INSTANCE_ID,
  stepCode: 'INCOME_CERTIFICATE',
  reason: 'REVENUE unavailable',
  createdAt: '2026-09-29T08:00:00Z',
}

export const otherException = { ...exception, id: '88888888-8888-4888-8888-888888888888', instanceId: OTHER_INSTANCE_ID, stepCode: 'MARKS' }

export const bankReview = {
  id: REVIEW_ID,
  applicationId: 'SCH-2026-0001',
  accountMasked: 'XXXXXX4312',
  reason: 'NAME_PARTIAL',
  reasonText: 'Only part of the name matched',
  matcherVersion: 'v2',
  status: 'PENDING',
  hasDocument: false,
  createdAt: '2026-09-29T07:00:00Z',
}

export const applications = [
  { referenceNo: 'SCH-2026-0001', citizenId: CITIZEN, journeyCode: 'POST_MATRIC_SCHOLARSHIP', status: 'PARTIALLY_VERIFIED', slaDueAt: '2020-01-01T00:00:00Z', instanceId: INSTANCE_ID },
  { referenceNo: 'SCH-2026-0002', citizenId: CITIZEN, journeyCode: 'POST_MATRIC_SCHOLARSHIP', status: 'VERIFIED', slaDueAt: '2999-01-01T00:00:00Z', instanceId: OTHER_INSTANCE_ID },
  { referenceNo: 'FRM-2026-0001', citizenId: CITIZEN, journeyCode: 'FARMER_SUBSIDY', status: 'REJECTED', slaDueAt: '2020-01-01T00:00:00Z', instanceId: null },
]

export const applicationView = {
  referenceNo: 'SCH-2026-0001',
  citizenId: CITIZEN,
  journeyCode: 'POST_MATRIC_SCHOLARSHIP',
  status: 'PARTIALLY_VERIFIED',
  submittedAt: '2026-09-29T06:00:00Z',
  slaDueAt: '2020-01-01T00:00:00Z',
  instanceId: INSTANCE_ID,
}

export const steps = [
  { stepCode: 'INCOME_CERTIFICATE', departmentCode: 'REVENUE', status: 'PENDING_SOURCE', startedAt: '2026-09-29T06:00:01Z', completedAt: null, slaDueAt: null, source: null, outcome: null, dataAsOf: null },
  { stepCode: 'MARKS', departmentCode: 'EDUCATION', status: 'COMPLETED', startedAt: '2026-09-29T06:00:01Z', completedAt: '2026-09-29T06:00:03Z', slaDueAt: null, source: 'LIVE', outcome: 'OK', dataAsOf: null },
]

export const issuedRecords = [
  {
    stepCode: 'MARKS',
    departmentCode: 'EDUCATION',
    issuer: 'State Education Board',
    liveSystem: 'Board results',
    liveSystemUrl: null,
    title: 'Marks statement',
    documentKind: 'MARKS',
    fields: [{ label: 'Percentage', value: '86.5' }],
    storedInSamanvay: false,
    fetchStatus: 'FETCHED_LIVE',
  },
]

export const metrics: OpsMetrics = {
  generatedAt: '2026-09-29T10:00:00Z',
  connector: {
    latencyWindowMinutes: 15,
    sources: [
      { source: 'revenue-rest-mock', calls: 20, success: 19, failure: 1, unavailable: 0, successRate: 0.95, latency: { count: 19, p50Ms: 120.4, p95Ms: 480.9, meanMs: 150, maxMs: 700 }, latencyByOutcome: {} },
      { source: 'dbt-rest-mock', calls: 0, success: 0, failure: 0, unavailable: 0, successRate: null, latency: null, latencyByOutcome: {} },
    ],
  },
  sla: {
    open: 12,
    breached: 2,
    dueSoon: 3,
    withinSlaPercent: 83.3333,
    byJourney: [{ journeyCode: 'POST_MATRIC_SCHOLARSHIP', open: 12, breached: 2, dueSoon: 3 }],
    watchlist: [
      { referenceNo: 'SCH-2026-0001', journeyCode: 'POST_MATRIC_SCHOLARSHIP', status: 'PARTIALLY_VERIFIED', slaDueAt: '2026-09-28T10:00:00Z', secondsToDue: -86400 },
      { referenceNo: 'SCH-2026-0009', journeyCode: 'POST_MATRIC_SCHOLARSHIP', status: 'SUBMITTED', slaDueAt: '2026-09-30T10:00:00Z', secondsToDue: 7200 },
    ],
  },
  consent: { granted: 40, denied: 10, grantRate: 0.8, denialsByReason: [{ reason: 'CONSENT_EXPIRED', count: 6 }, { reason: 'PURPOSE_MISMATCH', count: 4 }] },
  exceptionQueue: {
    open: 2,
    oldestAgeSeconds: 5400,
    byReason: [{ reason: 'REVENUE unavailable', count: 2 }],
    oldest: [{ ...exception, ageSeconds: 5400 }],
  },
}

export const department = { code: 'REVENUE', name: 'Revenue Department', status: 'ACTIVE' }

export const connector = {
  ref: 'rev-conn-1@1',
  connectorId: 'rev-conn-1',
  version: 1,
  dataSourceCode: 'revenue-rest-mock',
  category: { code: 'INCOME_CERTIFICATE' },
  capabilitiesJson: '{"FETCH":{}}',
  inputsJson: '[]',
  slaMs: 3000,
  status: 'PUBLISHED',
}
