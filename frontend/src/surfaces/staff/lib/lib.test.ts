import { describe, expect, it } from 'vitest'
import {
  formatDateTime,
  formatDuration,
  formatMs,
  formatPercent,
  formatRatio,
  shortId,
} from '../../../ui/format'
import {
  approvedRules,
  categoryDefaults,
  defaultCapabilities,
  defaultSpec,
  jsonError,
  parseSlaMs,
} from './onboarding'
import { appStatus, bankReviewTone, isOpenApplication, slaState, stepTone } from './status'

describe('formatting for dashboards', () => {
  it('formats durations, ratios and latencies; unknown values are n/a, never a made-up zero', () => {
    expect(formatDuration(42)).toBe('42s')
    expect(formatDuration(5400)).toBe('1h 30m')
    expect(formatDuration(93784)).toBe('1d 2h')
    expect(formatDuration(-86400)).toBe('1d 0h')
    expect(formatDuration(75)).toBe('1m 15s')
    expect(formatDuration(null)).toBe('n/a')
    expect(formatRatio(0.9375)).toBe('93.8%')
    expect(formatRatio(null)).toBe('n/a')
    expect(formatPercent(83.3333)).toBe('83.3%')
    expect(formatPercent(undefined)).toBe('n/a')
    expect(formatMs(120.4)).toBe('120 ms')
    expect(formatMs(null)).toBe('n/a')
  })

  it('formats an instant in UTC and tolerates junk', () => {
    expect(formatDateTime('2026-09-29T10:05:00Z')).toBe('29 Sep 2026, 10:05 UTC')
    expect(formatDateTime('nope')).toBe('')
    expect(formatDateTime(null)).toBe('')
    expect(shortId('22222222-2222-4222-8222-222222222222')).toBe('22222222')
    expect(shortId('abc')).toBe('abc')
  })
})

describe('status helpers', () => {
  it('words application and step statuses for officers', () => {
    expect(appStatus('PARTIALLY_VERIFIED')).toEqual({ tone: 'warn', label: 'Partially verified' })
    expect(appStatus('REJECTED').tone).toBe('bad')
    expect(appStatus('SOMETHING_NEW').label).toBe('Something new')
    expect(stepTone('FAILED').tone).toBe('bad')
    expect(stepTone('COMPLETED').label).toBe('Received')
    expect(bankReviewTone('APPROVED')).toBe('ok')
    expect(bankReviewTone('REJECTED')).toBe('bad')
    expect(bankReviewTone('PENDING')).toBe('warn')
  })

  it('treats approved, rejected and closed as no longer open', () => {
    for (const s of ['APPROVED', 'REJECTED', 'CLOSED']) expect(isOpenApplication(s)).toBe(false)
    for (const s of ['SUBMITTED', 'PARTIALLY_VERIFIED', 'VERIFIED']) expect(isOpenApplication(s)).toBe(true)
  })

  it('classifies SLA state against a fixed clock, with the server\'s 24h due-soon window', () => {
    const now = Date.parse('2026-09-29T00:00:00Z')
    expect(slaState('2026-09-28T23:59:59Z', true, now)).toBe('breached')
    expect(slaState('2026-09-29T12:00:00Z', true, now)).toBe('due-soon')
    expect(slaState('2026-09-30T00:00:00Z', true, now)).toBe('due-soon')
    expect(slaState('2026-09-30T00:00:01Z', true, now)).toBe('on-track')
    expect(slaState('2026-09-28T00:00:00Z', false, now)).toBe('none')
    expect(slaState(null, true, now)).toBe('none')
    expect(slaState('garbage', true, now)).toBe('none')
  })
})

describe('onboarding helpers', () => {
  it('builds category defaults the importer can work with', () => {
    expect(categoryDefaults('MARKS')).toMatchObject({ endpoint: '/marks', operationId: 'getMarks', schemaRef: 'Credential/Marks@1' })
    expect(categoryDefaults('UNKNOWN').endpoint).toBe('/x')
    expect(JSON.parse(defaultCapabilities('BANK_ACCOUNT', 'map-1@1'))).toEqual({ FETCH: { endpoint: '/bank', mapping_ref: 'map-1@1' } })
    const spec = JSON.parse(defaultSpec('INCOME_CERTIFICATE'))
    expect(spec.paths['/income'].get.operationId).toBe('getIncome')
    expect(Object.keys(spec.paths['/income'].get.responses['200'].content['application/json'].schema.properties)).toEqual(['annual_income', 'holder_name'])
  })

  it('turns only the ticked suggestions into mapping rules', () => {
    const s = [
      { source: 'a', target: 'A', confidence: 1, rationale: '', approved: false },
      { source: 'b', target: 'B', confidence: 1, rationale: '', approved: false },
      { source: 'c', target: 'C', confidence: 1, rationale: '', approved: true }, // the server's own flag is ignored
    ]
    expect(approvedRules(s, new Set([1]))).toEqual([{ source: 'b', target: 'B', transforms: [] }])
    expect(approvedRules(s, new Set())).toEqual([])
  })

  it('validates the SLA box and JSON text', () => {
    expect(parseSlaMs('')).toEqual({ value: null })
    expect(parseSlaMs(' 3000 ')).toEqual({ value: 3000 })
    expect(parseSlaMs('-1').error).toBeTruthy()
    expect(parseSlaMs('1.5').error).toBeTruthy()
    expect(parseSlaMs('123456789').error).toBeTruthy()
    expect(jsonError('{"a":1}')).toBeNull()
    expect(jsonError('{a:1}')).toBeTruthy()
  })
})
