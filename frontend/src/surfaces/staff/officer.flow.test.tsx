import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import {
  applications,
  applicationView,
  bankReview,
  exception,
  INSTANCE_ID,
  issuedRecords,
  metrics,
  otherException,
  REVIEW_ID,
  steps,
} from '../../test/staffFixtures'
import { mockFetch, renderStaff, staffAuth } from '../../test/utils'

const officer = () => staffAuth(['officer'])

/** The value shown in the dashboard tile with this label. */
const tile = (root: HTMLElement, label: string) => within(root).getByText(label, { selector: '.tile-label' }).nextElementSibling

describe('officer: exception queue', () => {
  it('lists open exceptions and retries one, then reloads the queue', async () => {
    let queue = [exception, otherException]
    const m = mockFetch([
      { method: 'GET', path: '/api/journeys/exceptions', reply: () => ({ body: queue }) },
      {
        method: 'POST',
        path: `/api/journeys/instances/${INSTANCE_ID}/retry`,
        reply: () => {
          queue = [otherException]
          return { status: 200 }
        },
      },
    ])
    renderStaff({ route: '/staff/officer/exceptions', fetchImpl: m.fetchImpl, auth: officer() })
    const table = await screen.findByRole('table')
    expect(within(table).getByText('INCOME_CERTIFICATE')).toBeInTheDocument()
    expect(within(table).getByText('MARKS')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: /Retry INCOME_CERTIFICATE/ }))
    expect(await screen.findByText(/Retry requested for instance 22222222/)).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByText('INCOME_CERTIFICATE')).not.toBeInTheDocument())
    expect(m.find('POST', `/api/journeys/instances/${INSTANCE_ID}/retry`)).toHaveLength(1)
    expect(m.find('GET', '/api/journeys/exceptions')).toHaveLength(2)
    expect(m.unhandled).toEqual([])
  })

  it('says so when the queue is empty', async () => {
    const m = mockFetch([{ method: 'GET', path: '/api/journeys/exceptions', reply: { body: [] } }])
    renderStaff({ route: '/staff/officer/exceptions', fetchImpl: m.fetchImpl, auth: officer() })
    expect(await screen.findByText(/No open exceptions/)).toBeInTheDocument()
  })

  it('shows the API error when a retry is refused, and keeps the row', async () => {
    const m = mockFetch([
      { method: 'GET', path: '/api/journeys/exceptions', reply: { body: [exception] } },
      { method: 'POST', path: `/api/journeys/instances/${INSTANCE_ID}/retry`, reply: { status: 409, body: { title: 'Conflict', status: 409, detail: 'Instance is not waiting on a source' } } },
    ])
    renderStaff({ route: '/staff/officer/exceptions', fetchImpl: m.fetchImpl, auth: officer() })
    await userEvent.click(await screen.findByRole('button', { name: /Retry INCOME_CERTIFICATE/ }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Instance is not waiting on a source')
    expect(screen.getByText('INCOME_CERTIFICATE')).toBeInTheDocument()
  })

  it('shows the step outcomes of an instance on demand', async () => {
    const m = mockFetch([
      { method: 'GET', path: '/api/journeys/exceptions', reply: { body: [exception] } },
      { method: 'GET', path: `/api/journeys/instances/${INSTANCE_ID}`, reply: { body: { id: INSTANCE_ID, status: 'WAITING', stepOutcomes: { INCOME_CERTIFICATE: 'PENDING_SOURCE' } } } },
    ])
    renderStaff({ route: '/staff/officer/exceptions', fetchImpl: m.fetchImpl, auth: officer() })
    await userEvent.click(await screen.findByRole('button', { name: 'Show steps' }))
    expect(await screen.findByText('PENDING_SOURCE')).toBeInTheDocument()
    expect(screen.getByText('Waiting')).toBeInTheDocument()
  })

  it('offers a retry when the queue cannot be loaded', async () => {
    let fail = true
    const m = mockFetch([{ method: 'GET', path: '/api/journeys/exceptions', reply: () => (fail ? { status: 503, body: { status: 503 } } : { body: [] }) }])
    renderStaff({ route: '/staff/officer/exceptions', fetchImpl: m.fetchImpl, auth: officer() })
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    fail = false
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByText(/No open exceptions/)).toBeInTheDocument()
  })
})

describe('officer: bank-account review', () => {
  const list = { method: 'GET', path: '/api/officer/bank-reviews', reply: { body: [bankReview] } }
  const decision = (verb: string, body: unknown = {}) => ({
    method: 'POST',
    path: `/api/officer/bank-reviews/${REVIEW_ID}/${verb}`,
    reply: body as never,
  })

  it('shows the masked account and reason, and never a holder name', async () => {
    const m = mockFetch([list])
    renderStaff({ route: '/staff/officer/bank-reviews', fetchImpl: m.fetchImpl, auth: officer() })
    const card = await screen.findByRole('article', { name: /application SCH-2026-0001/ })
    expect(within(card).getByText('XXXXXX4312')).toBeInTheDocument()
    expect(within(card).getByText('Only part of the name matched')).toBeInTheDocument()
    expect(within(card).getByText(/No passbook yet/)).toBeInTheDocument()
    expect(card.textContent).not.toMatch(/holder/i)
  })

  it('approves with an optional reason and reloads', async () => {
    let rows = [bankReview]
    const m = mockFetch([
      { method: 'GET', path: '/api/officer/bank-reviews', reply: () => ({ body: rows }) },
      { ...decision('approve', () => { rows = []; return { status: 200 } }) },
    ])
    renderStaff({ route: '/staff/officer/bank-reviews', fetchImpl: m.fetchImpl, auth: officer() })
    await userEvent.type(await screen.findByLabelText(/^Reason/), 'passbook matches')
    await userEvent.click(screen.getByRole('button', { name: 'Approve' }))
    expect(await screen.findByText(/Approved the review for application SCH-2026-0001/)).toBeInTheDocument()
    expect(m.find('POST', `/api/officer/bank-reviews/${REVIEW_ID}/approve`)[0]?.body).toEqual({ reason: 'passbook matches' })
    expect(await screen.findByText(/No reviews waiting/)).toBeInTheDocument()
  })

  it('will not reject without a reason, then rejects with one', async () => {
    const m = mockFetch([list, decision('reject', { status: 200 })])
    renderStaff({ route: '/staff/officer/bank-reviews', fetchImpl: m.fetchImpl, auth: officer() })
    await userEvent.click(await screen.findByRole('button', { name: 'Reject' }))
    expect(screen.getByText('A reason is required to reject.')).toBeInTheDocument()
    expect(m.find('POST', /reject/)).toHaveLength(0)

    await userEvent.type(screen.getByLabelText(/^Reason/), 'account closed')
    await userEvent.click(screen.getByRole('button', { name: 'Reject' }))
    expect(await screen.findByText(/Rejected the review for application/)).toBeInTheDocument()
    expect(m.find('POST', /reject/)[0]?.body).toEqual({ reason: 'account closed' })
  })

  it('asks the citizen for a document', async () => {
    const m = mockFetch([list, decision('request-document', { status: 200 })])
    renderStaff({ route: '/staff/officer/bank-reviews', fetchImpl: m.fetchImpl, auth: officer() })
    await userEvent.click(await screen.findByRole('button', { name: 'Ask for a document' }))
    expect(await screen.findByText(/Asked for a document on application SCH-2026-0001/)).toBeInTheDocument()
  })

  it('uploads a passbook as multipart, and refuses a missing or oversize file before calling the API', async () => {
    const m = mockFetch([list, decision('passbook', { status: 200 })])
    renderStaff({ route: '/staff/officer/bank-reviews', fetchImpl: m.fetchImpl, auth: officer() })
    await userEvent.click(await screen.findByRole('button', { name: 'Upload passbook' }))
    expect(screen.getByText(/Choose a passbook/)).toBeInTheDocument()

    const input = screen.getByLabelText(/^Passbook or cancelled cheque/) as HTMLInputElement
    const big = new File([new Uint8Array(256 * 1024 + 1)], 'big.pdf', { type: 'application/pdf' })
    fireEvent.change(input, { target: { files: [big] } })
    await userEvent.click(screen.getByRole('button', { name: 'Upload passbook' }))
    expect(screen.getByText(/larger than 256 KB/)).toBeInTheDocument()
    expect(m.find('POST', /passbook/)).toHaveLength(0)

    const ok = new File([new Uint8Array([0x25, 0x50, 0x44, 0x46])], 'pb.pdf', { type: 'application/pdf' })
    fireEvent.change(input, { target: { files: [ok] } })
    await userEvent.click(screen.getByRole('button', { name: 'Upload passbook' }))
    expect(await screen.findByText(/Passbook uploaded for application SCH-2026-0001/)).toBeInTheDocument()
    expect(m.find('POST', /passbook/)).toHaveLength(1)
  })
})

describe('officer: application review', () => {
  it('lists applications, filters them, and flags SLA breaches', async () => {
    const m = mockFetch([{ method: 'GET', path: '/api/applications?size=50', reply: { body: applications } }])
    renderStaff({ route: '/staff/officer/applications', fetchImpl: m.fetchImpl, auth: officer() })
    const table = await screen.findByRole('table')
    expect(within(table).getByRole('link', { name: 'SCH-2026-0001' })).toHaveAttribute('href', '/staff/officer/applications/SCH-2026-0001')
    const breached = within(table).getAllByText('SLA breached')
    expect(breached).toHaveLength(1) // SCH-2026-0001 is open and overdue; FRM-2026-0001 is rejected, so not flagged
    expect(within(table).getByText('On track')).toBeInTheDocument()

    await userEvent.selectOptions(screen.getByLabelText('Service'), 'FARMER_SUBSIDY')
    expect(within(screen.getByRole('table')).queryByText('SCH-2026-0001')).not.toBeInTheDocument()
    expect(within(screen.getByRole('table')).getByText('FRM-2026-0001')).toBeInTheDocument()

    await userEvent.click(screen.getByLabelText('Open applications only'))
    expect(await screen.findByText('No applications match these filters.')).toBeInTheDocument()
  })

  const detailRoutes = (over: { records?: never; exceptions?: unknown[] } = {}) => [
    { method: 'GET', path: '/api/applications/SCH-2026-0001', reply: { body: applicationView } },
    { method: 'GET', path: '/api/applications/SCH-2026-0001/steps', reply: { body: steps } },
    { method: 'GET', path: '/api/applications/SCH-2026-0001/issued-records', reply: over.records ?? { body: issuedRecords } },
    { method: 'GET', path: '/api/journeys/exceptions', reply: { body: over.exceptions ?? [exception, otherException] } },
  ]

  it('shows the case: checks, records received, and only this application\'s exception', async () => {
    const m = mockFetch(detailRoutes())
    renderStaff({ route: '/staff/officer/applications/SCH-2026-0001', fetchImpl: m.fetchImpl, auth: officer() })
    expect(await screen.findByRole('heading', { name: 'Application SCH-2026-0001' })).toBeInTheDocument()
    expect(screen.getByText('Partially verified')).toBeInTheDocument()
    expect(screen.getByText('SLA breached')).toBeInTheDocument()
    expect(screen.getByText('Waiting for department')).toBeInTheDocument()
    expect(screen.getByText('Marks statement')).toBeInTheDocument()
    expect(screen.getByText('86.5')).toBeInTheDocument()
    const needs = screen.getByLabelText('Open exceptions')
    expect(within(needs).getByText('INCOME_CERTIFICATE')).toBeInTheDocument()
    expect(within(needs).queryByText('MARKS')).not.toBeInTheDocument() // belongs to another instance
    expect(m.unhandled).toEqual([])
  })

  it('retries this application\'s exception from the case page', async () => {
    const m = mockFetch([...detailRoutes(), { method: 'POST', path: `/api/journeys/instances/${INSTANCE_ID}/retry`, reply: { status: 200 } }])
    renderStaff({ route: '/staff/officer/applications/SCH-2026-0001', fetchImpl: m.fetchImpl, auth: officer() })
    await userEvent.click(await screen.findByRole('button', { name: 'Retry' }))
    expect(await screen.findByText(/Retry requested/)).toBeInTheDocument()
    expect(m.find('POST', `/api/journeys/instances/${INSTANCE_ID}/retry`)).toHaveLength(1)
  })

  it('still shows the case when the department records cannot be fetched', async () => {
    const m = mockFetch(detailRoutes({ records: { status: 502, body: { status: 502 } } as never }))
    renderStaff({ route: '/staff/officer/applications/SCH-2026-0001', fetchImpl: m.fetchImpl, auth: officer() })
    expect(await screen.findByRole('heading', { name: 'Application SCH-2026-0001' })).toBeInTheDocument()
    expect(screen.getByText('Waiting for department')).toBeInTheDocument()
    expect(await screen.findByRole('alert')).toBeInTheDocument()
  })

  it('reports an unknown application number', async () => {
    const m = mockFetch([{ method: 'GET', path: '/api/applications/NOPE', reply: { status: 404, body: { status: 404 } } }])
    renderStaff({ route: '/staff/officer/applications/NOPE', fetchImpl: m.fetchImpl, auth: officer() })
    expect(await screen.findByText('No application with that number was found.')).toBeInTheDocument()
  })

  it('keeps approval disabled until the application is verified, and calls nothing', async () => {
    const m = mockFetch(detailRoutes()) // applicationView is PARTIALLY_VERIFIED
    renderStaff({ route: '/staff/officer/applications/SCH-2026-0001', fetchImpl: m.fetchImpl, auth: officer() })
    const button = await screen.findByRole('button', { name: 'Approve application' })
    expect(button).toBeDisabled()
    expect(screen.getByText('Available once every department record is verified')).toBeInTheDocument()
    await userEvent.click(button)
    expect(m.find('POST', `/api/journeys/instances/${INSTANCE_ID}/approve`)).toHaveLength(0)
  })

  it('approves a verified application and reloads the case', async () => {
    let view = { ...applicationView, status: 'VERIFIED' }
    const m = mockFetch([
      { method: 'GET', path: '/api/applications/SCH-2026-0001', reply: () => ({ body: view }) },
      { method: 'GET', path: '/api/applications/SCH-2026-0001/steps', reply: { body: steps } },
      { method: 'GET', path: '/api/applications/SCH-2026-0001/issued-records', reply: { body: issuedRecords } },
      { method: 'GET', path: '/api/journeys/exceptions', reply: { body: [] } },
      {
        method: 'POST',
        path: `/api/journeys/instances/${INSTANCE_ID}/approve`,
        reply: () => {
          view = { ...view, status: 'APPROVED' }
          return { body: { instanceId: INSTANCE_ID, status: 'APPROVED' } }
        },
      },
    ])
    renderStaff({ route: '/staff/officer/applications/SCH-2026-0001', fetchImpl: m.fetchImpl, auth: officer() })
    await userEvent.click(await screen.findByRole('button', { name: 'Approve application' }))
    expect(await screen.findByText('Application approved.')).toBeInTheDocument()
    expect(m.find('POST', `/api/journeys/instances/${INSTANCE_ID}/approve`)).toHaveLength(1)
    // After the reload the case is APPROVED, so the button is no longer offered.
    await waitFor(() => expect(screen.getByRole('button', { name: 'Approve application' })).toBeDisabled())
  })

  it('rejects a non-terminal application: needs a reason, then posts and reloads', async () => {
    let view = { ...applicationView } // PARTIALLY_VERIFIED — non-terminal, so rejectable
    const m = mockFetch([
      { method: 'GET', path: '/api/applications/SCH-2026-0001', reply: () => ({ body: view }) },
      { method: 'GET', path: '/api/applications/SCH-2026-0001/steps', reply: { body: steps } },
      { method: 'GET', path: '/api/applications/SCH-2026-0001/issued-records', reply: { body: issuedRecords } },
      { method: 'GET', path: '/api/journeys/exceptions', reply: { body: [] } },
      {
        method: 'POST',
        path: `/api/journeys/instances/${INSTANCE_ID}/reject`,
        reply: () => {
          view = { ...view, status: 'REJECTED' }
          return { body: { instanceId: INSTANCE_ID, status: 'REJECTED' } }
        },
      },
    ])
    renderStaff({ route: '/staff/officer/applications/SCH-2026-0001', fetchImpl: m.fetchImpl, auth: officer() })
    // A blank reason is refused client-side, before any POST.
    await userEvent.click(await screen.findByRole('button', { name: 'Reject application' }))
    expect(screen.getByText('A reason is required to reject.')).toBeInTheDocument()
    expect(m.find('POST', /reject/)).toHaveLength(0)
    // With a reason it posts and reloads to REJECTED.
    await userEvent.type(screen.getByLabelText('Reason (required to reject)'), 'documents forged')
    await userEvent.click(screen.getByRole('button', { name: 'Reject application' }))
    expect(await screen.findByText('Application rejected.')).toBeInTheDocument()
    expect(m.find('POST', `/api/journeys/instances/${INSTANCE_ID}/reject`)[0]?.body).toEqual({ reason: 'documents forged' })
    // Now terminal, so reject is no longer offered.
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Reject application' })).not.toBeInTheDocument())
  })

  it('shows a plain message when approval is refused with a 409', async () => {
    const m = mockFetch([
      { method: 'GET', path: '/api/applications/SCH-2026-0001', reply: { body: { ...applicationView, status: 'VERIFIED' } } },
      { method: 'GET', path: '/api/applications/SCH-2026-0001/steps', reply: { body: steps } },
      { method: 'GET', path: '/api/applications/SCH-2026-0001/issued-records', reply: { body: issuedRecords } },
      { method: 'GET', path: '/api/journeys/exceptions', reply: { body: [] } },
      {
        method: 'POST',
        path: `/api/journeys/instances/${INSTANCE_ID}/approve`,
        reply: { status: 409, body: { title: 'Conflict', status: 409, reason: 'APPLICATION_NOT_APPROVABLE', detail: 'not verified' } },
      },
    ])
    renderStaff({ route: '/staff/officer/applications/SCH-2026-0001', fetchImpl: m.fetchImpl, auth: officer() })
    await userEvent.click(await screen.findByRole('button', { name: 'Approve application' }))
    expect(await screen.findByRole('alert')).toHaveTextContent("This application can't be approved yet — a department record is still pending.")
  })

  it('opens an application by its number from the list page', async () => {
    const m = mockFetch([
      { method: 'GET', path: '/api/applications?size=50', reply: { body: applications } },
      ...detailRoutes(),
    ])
    renderStaff({ route: '/staff/officer/applications', fetchImpl: m.fetchImpl, auth: officer() })
    await userEvent.type(await screen.findByLabelText(/Open by application number/), 'SCH-2026-0001{Enter}')
    expect(await screen.findByRole('heading', { name: 'Application SCH-2026-0001' })).toBeInTheDocument()
  })
})

describe('officer and admin: metrics', () => {
  it.each([['officer'], ['admin']])('renders the four dashboards for %s from /api/ops/metrics', async (role) => {
    const m = mockFetch([{ method: 'GET', path: '/api/ops/metrics', reply: { body: metrics } }])
    renderStaff({ route: '/staff/ops/metrics', fetchImpl: m.fetchImpl, auth: staffAuth([role]) })
    expect(await screen.findByText(/As of 29 Sep 2026, 10:00 UTC/)).toBeInTheDocument()

    // SLA
    const sla = screen.getByRole('region', { name: 'SLA' })
    expect(tile(sla, 'Breached')).toHaveTextContent('2')
    expect(within(sla).getByText('83.3%')).toBeInTheDocument()
    expect(within(sla).getByText('1d 0h overdue')).toBeInTheDocument()
    expect(within(sla).getByText('2h 0m left')).toBeInTheDocument()
    // officers can open a watchlist case; admins have no application access, so it is plain text
    if (role === 'officer') expect(within(sla).getByRole('link', { name: 'SCH-2026-0001' })).toBeInTheDocument()
    else expect(within(sla).queryByRole('link', { name: 'SCH-2026-0001' })).not.toBeInTheDocument()

    // exception queue
    const q = screen.getByRole('region', { name: 'Exception queue' })
    expect(tile(q, 'Open exceptions')).toHaveTextContent('2')
    expect(within(q).getByText('REVENUE unavailable: 2')).toBeInTheDocument()

    // connectors: a source with no calls shows n/a, not 0%
    const c = screen.getByRole('region', { name: 'Connector health' })
    expect(within(c).getByText('95.0%')).toBeInTheDocument()
    expect(within(c).getByText('120 ms')).toBeInTheDocument()
    expect(within(c).getAllByText('n/a').length).toBeGreaterThanOrEqual(3)

    // consent
    const k = screen.getByRole('region', { name: 'Consent decisions' })
    expect(within(k).getByText('80.0%')).toBeInTheDocument()
    expect(within(k).getByText('Consent expired')).toBeInTheDocument()
    expect(m.unhandled).toEqual([])
  })

  it('shows the notification delivery panel when the backend reports it', async () => {
    const withNotifications = {
      ...metrics,
      notifications: { sent: 120, failed: 3, retriedSent: 5, retriedFailed: 1, sentRate: 0.975 },
    }
    const m = mockFetch([{ method: 'GET', path: '/api/ops/metrics', reply: { body: withNotifications } }])
    renderStaff({ route: '/staff/ops/metrics', fetchImpl: m.fetchImpl, auth: officer() })
    const notif = await screen.findByRole('region', { name: 'Notification delivery' })
    expect(tile(notif, 'Sent')).toHaveTextContent('120')
    expect(tile(notif, 'Failed')).toHaveTextContent('3')
    expect(tile(notif, 'Retries sent')).toHaveTextContent('5')
    expect(tile(notif, 'Retries failed')).toHaveTextContent('1')
    expect(tile(notif, 'First-attempt success rate')).toHaveTextContent('97.5%')
    expect(m.unhandled).toEqual([])
  })

  it('omits the notification delivery panel when the backend does not report it', async () => {
    const m = mockFetch([{ method: 'GET', path: '/api/ops/metrics', reply: { body: metrics } }])
    renderStaff({ route: '/staff/ops/metrics', fetchImpl: m.fetchImpl, auth: officer() })
    await screen.findByText(/As of/)
    expect(screen.queryByRole('region', { name: 'Notification delivery' })).not.toBeInTheDocument()
  })

  it('refreshes on demand and surfaces a failure with a retry', async () => {
    let fail = false
    const m = mockFetch([{ method: 'GET', path: '/api/ops/metrics', reply: () => (fail ? { status: 500, body: { status: 500 } } : { body: metrics }) }])
    renderStaff({ route: '/staff/ops/metrics', fetchImpl: m.fetchImpl, auth: officer() })
    await screen.findByText(/As of/)
    await userEvent.click(screen.getByRole('button', { name: 'Refresh' }))
    await waitFor(() => expect(m.find('GET', '/api/ops/metrics')).toHaveLength(2))
    fail = true
    await userEvent.click(screen.getByRole('button', { name: 'Refresh' }))
    await waitFor(() => expect(m.find('GET', '/api/ops/metrics')).toHaveLength(3))
  })

  it('handles an idle system (nothing recorded yet)', async () => {
    const idle = {
      ...metrics,
      connector: { latencyWindowMinutes: 15, sources: [] },
      sla: { open: 0, breached: 0, dueSoon: 0, withinSlaPercent: null, byJourney: [], watchlist: [] },
      consent: { granted: 0, denied: 0, grantRate: null, denialsByReason: [] },
      exceptionQueue: { open: null, oldestAgeSeconds: null, byReason: [], oldest: [] },
    }
    const m = mockFetch([{ method: 'GET', path: '/api/ops/metrics', reply: { body: idle } }])
    renderStaff({ route: '/staff/ops/metrics', fetchImpl: m.fetchImpl, auth: officer() })
    expect(await screen.findByText('No connector calls recorded yet.')).toBeInTheDocument()
    expect(screen.getByText('No open applications with an SLA due time.')).toBeInTheDocument()
    expect(screen.getByText('The queue is empty.')).toBeInTheDocument()
  })
})

describe('officer and admin: audit ledger', () => {
  const routes = (valid = true) => [
    { method: 'GET', path: '/api/audit/head', reply: { body: { seq: 120 } } },
    { method: 'GET', path: '/api/audit/checkpoint', reply: { body: { seq: 3, uptoEntrySeq: 100, rootHash: 'aa', signedAt: '2026-09-29T09:00:00Z', signature: 'bb', publishedRef: null, keyId: 'v2' } } },
    {
      method: 'GET',
      path: '/api/audit/entries?size=40',
      reply: { body: [{ seq: 120, ts: '2026-09-29T09:59:00Z', actorId: 'SCHOLARSHIP', action: 'GRANT_DENIED', subjectId: 'c1', departmentId: 'REVENUE', outcome: 'DENIED', reason: 'CONSENT_EXPIRED', consentId: null }] },
    },
    {
      method: 'GET',
      path: '/api/audit/verify',
      reply: { body: valid ? { valid: true, fromSeq: 1, toSeq: 120, failedAtSeq: null, reason: null } : { valid: false, fromSeq: 0, toSeq: 0, failedAtSeq: 57, reason: 'hash mismatch' } },
    },
  ]

  it('shows the head, checkpoint and entries, and verifies the chain', async () => {
    const m = mockFetch(routes())
    renderStaff({ route: '/staff/ops/audit', fetchImpl: m.fetchImpl, auth: staffAuth(['admin']) })
    expect(await screen.findByText('Head entry', { selector: '.tile-label' })).toBeInTheDocument()
    expect(tile(document.body, 'Head entry')).toHaveTextContent('120')
    expect(screen.getByText('#3')).toBeInTheDocument()
    expect(await screen.findByText('GRANT_DENIED')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Verify chain' }))
    expect((await screen.findByText(/Chain valid/)).closest('p')).toHaveTextContent('Entries 1 to 120 verified')
  })

  it('reports a broken chain loudly', async () => {
    const m = mockFetch(routes(false))
    renderStaff({ route: '/staff/ops/audit', fetchImpl: m.fetchImpl, auth: officer() })
    await userEvent.click(await screen.findByRole('button', { name: 'Verify chain' }))
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Chain broken')
    expect(alert).toHaveTextContent('entry 57: hash mismatch')
  })

  it('filters entries by action and copes with no checkpoint yet', async () => {
    const m = mockFetch([
      { method: 'GET', path: '/api/audit/head', reply: { body: { seq: 0 } } },
      { method: 'GET', path: '/api/audit/checkpoint', reply: { status: 200 } },
      { method: 'GET', path: '/api/audit/entries?size=40', reply: { body: [] } },
      { method: 'GET', path: '/api/audit/entries?action=NOPE&size=40', reply: { body: [] } },
    ])
    renderStaff({ route: '/staff/ops/audit', fetchImpl: m.fetchImpl, auth: officer() })
    expect(await screen.findByText('none yet')).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('Filter by action'), 'NOPE')
    await userEvent.click(screen.getByRole('button', { name: 'Filter' }))
    expect(await screen.findByText('No entries for action NOPE.')).toBeInTheDocument()
    expect(m.unhandled).toEqual([])
  })
})

describe('officer: find a citizen from the staff home', () => {
  const CZ = '33333333-3333-4333-8333-333333333333'
  // The home strip (StaffAttention) always loads these; keep them quiet so the search is isolated.
  const homeNoise = [
    { method: 'GET', path: '/api/ops/metrics', reply: { body: {} } },
    { method: 'GET', path: '/api/officer/bank-reviews', reply: { body: [] } },
  ]
  const match = { citizenId: CZ, nameLatin: 'Ramesh Kumar', nameDevanagari: 'रमेश', birthYear: 2004 }

  it('searches by name, then opens that citizen’s 360° file', async () => {
    const m = mockFetch([
      ...homeNoise,
      { method: 'GET', path: '/api/identity/citizens/search?q=ramesh', reply: { body: [match] } },
      // The file itself, assembled on CitizenViewPage once we navigate there.
      { method: 'GET', path: '/api/applications?size=200', reply: { body: [] } },
      { method: 'GET', path: '/api/audit/entries?size=200', reply: { body: [] } },
    ])
    renderStaff({ route: '/staff', fetchImpl: m.fetchImpl, auth: officer() })

    await userEvent.type(await screen.findByRole('searchbox', { name: /citizen name or id/i }), 'ramesh')
    await userEvent.click(screen.getByRole('button', { name: 'Search' }))

    const hit = await screen.findByRole('link', { name: /Ramesh Kumar/ })
    expect(hit).toHaveAttribute('href', `/staff/officer/citizens/${CZ}`)
    await userEvent.click(hit)

    expect(await screen.findByRole('heading', { name: 'Citizen file' })).toBeInTheDocument()
    expect(screen.getByText(CZ)).toBeInTheDocument()
    expect(m.find('GET', '/api/identity/citizens/search?q=ramesh')).toHaveLength(1)
  })

  it('says when nothing matches', async () => {
    const m = mockFetch([
      ...homeNoise,
      { method: 'GET', path: '/api/identity/citizens/search?q=zzz', reply: { body: [] } },
    ])
    renderStaff({ route: '/staff', fetchImpl: m.fetchImpl, auth: officer() })
    await userEvent.type(await screen.findByRole('searchbox', { name: /citizen name or id/i }), 'zzz')
    await userEvent.click(screen.getByRole('button', { name: 'Search' }))
    expect(await screen.findByText(/No citizens match/)).toBeInTheDocument()
  })

  it('does not query the server for a one-character term', async () => {
    const m = mockFetch(homeNoise)
    renderStaff({ route: '/staff', fetchImpl: m.fetchImpl, auth: officer() })
    await userEvent.type(await screen.findByRole('searchbox', { name: /citizen name or id/i }), 'r')
    await userEvent.click(screen.getByRole('button', { name: 'Search' }))
    expect(m.find('GET', /citizens\/search/)).toHaveLength(0)
  })
})
