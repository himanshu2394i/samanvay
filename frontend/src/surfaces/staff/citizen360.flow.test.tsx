import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { CITIZEN_ID, mockFetch, renderStaff, staffAuth } from '../../test/utils'

const officer = () => staffAuth(['officer'])
const C1 = CITIZEN_ID
const C2 = '22222222-2222-4222-8222-222222222222'

/** The value shown in the summary tile with this label (not the section heading of the same name). */
const tileValue = (label: string) => screen.getByText(label, { selector: '.tile-label' }).nextElementSibling

const applications = [
  { referenceNo: 'SCH-2026-0001', citizenId: C1, journeyCode: 'POST_MATRIC_SCHOLARSHIP', status: 'VERIFIED', slaDueAt: null },
  { referenceNo: 'LIC-2026-0007', citizenId: C1, journeyCode: 'BUSINESS_LICENCE', status: 'APPROVED', slaDueAt: null },
  { referenceNo: 'FRM-2026-0002', citizenId: C2, journeyCode: 'FARMER_SUBSIDY', status: 'SUBMITTED', slaDueAt: null },
]
const audit = [
  { seq: 10, ts: '2026-09-29T09:00:00Z', actorId: 'SCHOLARSHIP', action: 'CONSENT_GRANTED', subjectId: C1, departmentId: 'REVENUE', outcome: 'GRANTED', reason: null, consentId: null },
  { seq: 11, ts: '2026-09-29T09:05:00Z', actorId: 'REVENUE', action: 'DATA_ACCESSED', subjectId: C1, departmentId: 'REVENUE', outcome: 'OK', reason: null, consentId: null },
  { seq: 12, ts: '2026-09-29T09:10:00Z', actorId: 'SCHOLARSHIP', action: 'CONSENT_WITHDRAWN', subjectId: C2, departmentId: 'EDUCATION', outcome: 'OK', reason: null, consentId: null },
]

// The backend filters by citizenId, so the reply holds only this citizen's applications.
const APPS_PATH = `/api/applications?size=200&citizenId=${C1}`
const routes = [
  { method: 'GET', path: APPS_PATH, reply: { body: applications.filter((a) => a.citizenId === C1) } },
  { method: 'GET', path: '/api/audit/entries?size=200', reply: { body: audit } },
]

describe('officer: citizen 360 view', () => {
  it("gathers one citizen's applications and consent/data-access trail, and excludes other citizens", async () => {
    const m = mockFetch(routes)
    renderStaff({ route: `/staff/officer/citizens/${C1}`, fetchImpl: m.fetchImpl, auth: officer() })

    expect(await screen.findByRole('heading', { name: /Citizen file/ })).toBeInTheDocument()
    expect(screen.getByText(C1)).toBeInTheDocument()
    expect(screen.queryByText(C2)).not.toBeInTheDocument()

    // applications for this citizen only
    expect(screen.getByRole('link', { name: 'SCH-2026-0001' })).toHaveAttribute('href', '/staff/officer/applications/SCH-2026-0001')
    expect(screen.getByText('LIC-2026-0007')).toBeInTheDocument()
    expect(screen.queryByText('FRM-2026-0002')).not.toBeInTheDocument()

    // consent + data-access trail for this citizen only
    expect(screen.getByText('Consent granted')).toBeInTheDocument()
    expect(screen.getByText('Data accessed')).toBeInTheDocument()
    expect(screen.queryByText('Consent withdrawn')).not.toBeInTheDocument()

    // summary
    expect(tileValue('Applications')).toHaveTextContent('2')
    expect(tileValue('Approved')).toHaveTextContent('1')
    expect(m.unhandled).toEqual([])
  })

  it('asks the server for this citizen only and says the audit trail covers the last 200 ledger entries', async () => {
    const m = mockFetch(routes)
    renderStaff({ route: `/staff/officer/citizens/${C1}`, fetchImpl: m.fetchImpl, auth: officer() })
    await screen.findByText('LIC-2026-0007')
    expect(m.find('GET', APPS_PATH)).toHaveLength(1)
    expect(screen.getByText(/last 200 ledger entries/)).toBeInTheDocument()
  })

  it('shows calm empty states for a citizen with nothing on file', async () => {
    const m = mockFetch([
      { method: 'GET', path: APPS_PATH, reply: { body: [] } },
      { method: 'GET', path: '/api/audit/entries?size=200', reply: { body: [] } },
    ])
    renderStaff({ route: `/staff/officer/citizens/${C1}`, fetchImpl: m.fetchImpl, auth: officer() })
    expect(await screen.findByText(/No applications on file/)).toBeInTheDocument()
    expect(screen.getByText(/None in the last 200 ledger entries/)).toBeInTheDocument()
    expect(screen.queryByText(/No consent or data-access entries/)).not.toBeInTheDocument()
  })

  it('still shows applications when the audit trail cannot be loaded', async () => {
    const m = mockFetch([
      { method: 'GET', path: APPS_PATH, reply: { body: applications } },
      { method: 'GET', path: '/api/audit/entries?size=200', reply: { status: 503, body: { status: 503 } } },
    ])
    renderStaff({ route: `/staff/officer/citizens/${C1}`, fetchImpl: m.fetchImpl, auth: officer() })
    expect(await screen.findByText('LIC-2026-0007')).toBeInTheDocument()
    expect(screen.getByText(/could not be loaded/)).toBeInTheDocument()
  })
})
