import { describe, expect, it, vi } from 'vitest'
import type { ApplicationSummary } from '../../../api/types'
import { need, PROVIDERS } from '../../../test/utils'
import {
  allLinked,
  buildLinkRequest,
  pollForApplication,
  supportedProviders,
  unsupportedProviders,
} from './applyFlow'
import { formatDate, humanize } from './format'
import { applicationStatus, stepStatus } from './status'
import { readCitizenId, writeCitizenId } from './citizenStore'
import type { LinkProofProviderInfo } from '../../../api/types'

const app = (over: Partial<ApplicationSummary>): ApplicationSummary => ({
  referenceNo: 'SCH-1',
  citizenId: 'c',
  journeyCode: 'J',
  status: 'SUBMITTED',
  slaDueAt: null,
  instanceId: 'i-1',
  ...over,
})

describe('connect step logic', () => {
  it('needs every department linked, and at least one department', () => {
    expect(allLinked([])).toBe(false)
    expect(allLinked([need('A', 'A', true, []), need('B', 'B', false, [])])).toBe(false)
    expect(allLinked([need('A', 'A', true, []), need('B', 'B', true, [])])).toBe(true)
  })

  it('offers only the proof kinds the app can complete', () => {
    const providers = PROVIDERS as LinkProofProviderInfo[]
    // the form handles the OTP demo only; DEPT_IDP needs a separate brokered sign-in
    expect(supportedProviders(providers).map((p) => p.kind)).toEqual(['LOCAL_ID_OTP'])
    expect(unsupportedProviders(providers).map((p) => p.kind)).toEqual(['DEPT_IDP'])
  })

  it('builds the link body: the typed one-time code is the proof', () => {
    const base = { citizenId: 'c', departmentCode: 'REVENUE', localIdType: ' RATION ', localId: ' R-1 ', otp: ' 000000 ' }
    expect(buildLinkRequest({ ...base, provider: 'LOCAL_ID_OTP' })).toEqual({
      citizenId: 'c',
      departmentCode: 'REVENUE',
      localIdType: 'RATION',
      localId: 'R-1',
      provider: 'LOCAL_ID_OTP',
      proof: '000000',
    })
  })
})

describe('pollForApplication', () => {
  it('finds the application for this journey instance, ignoring the others', async () => {
    const listApplications = vi
      .fn()
      .mockResolvedValueOnce([app({ referenceNo: 'OLD-1', instanceId: 'other' })])
      .mockResolvedValueOnce([app({ referenceNo: 'OLD-1', instanceId: 'other' })])
      .mockResolvedValueOnce([app({ referenceNo: 'OLD-1', instanceId: 'other' }), app({ referenceNo: 'SCH-2', instanceId: 'i-1' })])
    const sleep = vi.fn(async () => {})
    const found = await pollForApplication({ listApplications }, 'c', { id: 'i-1' }, { sleep, attempts: 5 })
    expect(found?.referenceNo).toBe('SCH-2')
    expect(listApplications).toHaveBeenCalledTimes(3)
    expect(sleep).toHaveBeenCalledTimes(2)
  })

  it('gives up with null after the attempts, without sleeping after the last one', async () => {
    const listApplications = vi.fn().mockResolvedValue([])
    const sleep = vi.fn(async () => {})
    const found = await pollForApplication({ listApplications }, 'c', { id: 'i-1' }, { sleep, attempts: 3, intervalMs: 10 })
    expect(found).toBeNull()
    expect(listApplications).toHaveBeenCalledTimes(3)
    expect(sleep).toHaveBeenCalledTimes(2)
  })
})

describe('status wording', () => {
  it('maps application statuses to tone and finality', () => {
    expect(applicationStatus('SUBMITTED')).toMatchObject({ tone: 'warn', final: false })
    expect(applicationStatus('PARTIALLY_VERIFIED')).toMatchObject({ tone: 'warn', final: false })
    expect(applicationStatus('VERIFIED')).toMatchObject({ tone: 'ok', final: false })
    expect(applicationStatus('APPROVED')).toMatchObject({ tone: 'ok', final: true })
    expect(applicationStatus('REJECTED')).toMatchObject({ tone: 'bad', final: true })
    expect(applicationStatus('SOMETHING_NEW')).toMatchObject({ tone: 'warn', final: false })
  })

  it('maps step statuses', () => {
    expect(stepStatus('COMPLETED').tone).toBe('ok')
    expect(stepStatus('PENDING_SOURCE').tone).toBe('warn')
    expect(stepStatus('FAILED').tone).toBe('bad')
  })
})

describe('formatting and storage', () => {
  it('humanizes codes and formats dates', () => {
    expect(humanize('INCOME_CERTIFICATE')).toBe('Income certificate')
    expect(formatDate('2026-09-29T10:00:00Z')).toBe('29 Sep 2026')
    expect(formatDate(null)).toBe('')
    expect(formatDate('garbage')).toBe('')
  })

  it('remembers the citizen id per token subject', () => {
    writeCitizenId('sub-a', 'id-a')
    expect(readCitizenId('sub-a')).toBe('id-a')
    expect(readCitizenId('sub-b')).toBeNull()
    writeCitizenId('sub-a', null)
    expect(readCitizenId('sub-a')).toBeNull()
  })

  it('survives blocked storage', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    expect(readCitizenId('x')).toBeNull()
    expect(() => writeCitizenId('x', 'id')).not.toThrow()
  })
})
