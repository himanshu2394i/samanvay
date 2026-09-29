import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import {
  CITIZEN_ID,
  DEPARTMENTS,
  DRAFT_JOURNEY,
  mockFetch,
  need,
  PROVIDERS,
  renderCitizen,
  SCHOLARSHIP,
  signedInAuth,
  signedOutAuth,
  SUB,
} from '../../test/utils'
import { REFRESH_MS } from './pages/ApplicationPage'
import { readCitizenId } from './lib/citizenStore'

const INSTANCE_ID = '22222222-2222-4222-8222-222222222222'
const REQUEST_ID = '33333333-3333-4333-8333-333333333333'
const CONNECT = `/api/identity/citizens/${CITIZEN_ID}/connect-accounts?journeyCode=POST_MATRIC_SCHOLARSHIP`
const APPS = `/api/applications?citizenId=${CITIZEN_ID}&size=20`

const consentRequest = {
  id: REQUEST_ID,
  citizenId: CITIZEN_ID,
  requesterId: 'SCHOLARSHIP',
  purposeCode: 'SCHOLARSHIP_ELIGIBILITY',
  purposeText: 'Check eligibility for the post-matric scholarship',
  categories: ['INCOME_CERTIFICATE', 'MARKS'],
  status: 'PENDING',
}

const artifact = {
  id: '44444444-4444-4444-8444-444444444444',
  citizenId: CITIZEN_ID,
  requesterId: 'SCHOLARSHIP',
  purposeCode: 'SCHOLARSHIP_ELIGIBILITY',
  categories: ['INCOME_CERTIFICATE', 'MARKS'],
  granularity: 'PURPOSE',
  validFrom: '2026-09-29T00:00:00Z',
  validUntil: '2026-12-28T00:00:00Z',
  frequencyLimit: null,
  status: 'ACTIVE',
  version: 1,
  dataTypes: [],
  createdAt: '2026-09-29T00:00:00Z',
  revokedAt: null,
  revokedBy: null,
  statusLabel: 'Active',
  frequency: null,
}

const applicationView = {
  referenceNo: 'SCH-2026-0001',
  citizenId: CITIZEN_ID,
  journeyCode: 'POST_MATRIC_SCHOLARSHIP',
  status: 'PARTIALLY_VERIFIED',
  submittedAt: '2026-09-29T09:00:00Z',
  slaDueAt: '2026-10-02T09:00:00Z',
  instanceId: INSTANCE_ID,
}

const steps = [
  {
    stepCode: 'INCOME_CERTIFICATE',
    departmentCode: 'REVENUE',
    status: 'COMPLETED',
    startedAt: '2026-09-29T09:00:01Z',
    completedAt: '2026-09-29T09:00:03Z',
    slaDueAt: null,
    source: 'LIVE',
    outcome: null,
    dataAsOf: null,
  },
  {
    stepCode: 'MARKS',
    departmentCode: 'EDUCATION',
    status: 'PENDING_SOURCE',
    startedAt: '2026-09-29T09:00:01Z',
    completedAt: null,
    slaDueAt: null,
    source: null,
    outcome: null,
    dataAsOf: null,
  },
]

const issuedRecords = [
  {
    stepCode: 'INCOME_CERTIFICATE',
    departmentCode: 'REVENUE',
    issuer: 'Revenue Department',
    liveSystem: 'Aaple Sarkar',
    liveSystemUrl: null,
    title: 'Income certificate',
    documentKind: 'CERTIFICATE',
    fields: [{ label: 'Annual income', value: 'Rs 180000' }],
    storedInSamanvay: false,
    fetchStatus: 'COMPLETED',
  },
]

describe('auth guard', () => {
  it('asks a signed-out visitor to sign in and returns them to the page they wanted', async () => {
    const { fetchImpl, calls } = mockFetch([])
    const auth = signedOutAuth()
    renderCitizen({ route: '/services', fetchImpl, auth })

    expect(screen.getByRole('heading', { name: 'Sign in to continue' })).toBeInTheDocument()
    await userEvent.click(within(screen.getByRole('main')).getByRole('button', { name: 'Sign in' }))
    expect(auth.signIn).toHaveBeenCalledWith('/services')
    expect(calls).toHaveLength(0) // nothing was fetched without a session
  })

  it('shows the landing page and a sign-in button without a session', () => {
    const { fetchImpl } = mockFetch([])
    renderCitizen({ route: '/', fetchImpl, auth: signedOutAuth() })
    expect(screen.getByRole('heading', { level: 1, name: /without carrying papers/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Sign in to start' })).toBeInTheDocument()
    expect(screen.queryByRole('navigation', { name: 'Main' })).not.toBeInTheDocument()
  })

  it('explains an expired session', () => {
    const { fetchImpl } = mockFetch([])
    renderCitizen({
      route: '/services',
      fetchImpl,
      auth: signedOutAuth({ notice: 'Your session has expired. Sign in again to continue.' }),
    })
    expect(screen.getByRole('alert')).toHaveTextContent('Your session has expired')
  })

  it('drops the session when the API answers 401', async () => {
    const { fetchImpl } = mockFetch([{ method: 'GET', path: '/api/catalog/journeys', reply: { status: 401, body: { status: 401 } } }])
    const auth = signedInAuth()
    renderCitizen({ route: '/services', fetchImpl, auth })
    await waitFor(() => expect(auth.expireSession).toHaveBeenCalled())
  })

  it('sends the signed-in user to their details first when no citizen record is known', async () => {
    const { fetchImpl } = mockFetch([])
    renderCitizen({ route: '/consents', fetchImpl, registered: false })
    expect(await screen.findByRole('heading', { name: 'Your details' })).toBeInTheDocument()
  })
})

describe('browse services', () => {
  it('lists published services only, with what each needs', async () => {
    const { fetchImpl } = mockFetch([{ method: 'GET', path: '/api/catalog/journeys', reply: { body: [SCHOLARSHIP, DRAFT_JOURNEY] } }])
    renderCitizen({ route: '/services', fetchImpl })

    expect(await screen.findByRole('link', { name: 'Post-matric scholarship' })).toBeInTheDocument()
    expect(screen.queryByText('Draft service')).not.toBeInTheDocument()
    expect(screen.getByText(/Records needed: Income certificate, Caste certificate, Marks, Bank account/)).toBeInTheDocument()
  })

  it('shows an error with a working retry', async () => {
    let attempts = 0
    const { fetchImpl } = mockFetch([
      {
        method: 'GET',
        path: '/api/catalog/journeys',
        reply: () => (++attempts === 1 ? { status: 500, body: { title: 'boom' } } : { body: [SCHOLARSHIP] }),
      },
    ])
    renderCitizen({ route: '/services', fetchImpl })

    expect(await screen.findByRole('alert')).toHaveTextContent('could not complete that request')
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('link', { name: 'Post-matric scholarship' })).toBeInTheDocument()
  })

  it('shows a service grouped by the department that holds each record', async () => {
    const { fetchImpl } = mockFetch([
      { method: 'GET', path: '/api/catalog/journeys/POST_MATRIC_SCHOLARSHIP', reply: { body: SCHOLARSHIP } },
      { method: 'GET', path: '/api/catalog/departments', reply: { body: DEPARTMENTS } },
    ])
    renderCitizen({ route: '/services/POST_MATRIC_SCHOLARSHIP', fetchImpl })

    expect(await screen.findByRole('heading', { name: 'Post-matric scholarship' })).toBeInTheDocument()
    expect(screen.getByText(/Revenue Department/).closest('li')).toHaveTextContent('Income certificate, Caste certificate')
    expect(screen.getByRole('link', { name: 'Apply for this service' })).toHaveAttribute(
      'href',
      '/services/POST_MATRIC_SCHOLARSHIP/apply',
    )
  })
})

describe('my details', () => {
  it('registers the citizen, remembers the record, and continues to where they were going', async () => {
    const { fetchImpl, find } = mockFetch([
      { method: 'POST', path: '/api/identity/citizens', reply: { body: CITIZEN_ID } },
      { method: 'GET', path: `/api/applications?citizenId=${CITIZEN_ID}&size=50`, reply: { body: [] } },
      { method: 'GET', path: '/api/catalog/journeys', reply: { body: [SCHOLARSHIP] } },
    ])
    renderCitizen({ route: '/applications', fetchImpl, registered: false })

    await userEvent.type(await screen.findByLabelText(/Given name/), 'Asha')
    await userEvent.type(screen.getByLabelText(/Family name/), 'Patil')
    await userEvent.type(screen.getByLabelText(/Father's name/), 'Ramesh')
    fireEvent.change(screen.getByLabelText(/Date of birth/), { target: { value: '2003-04-05' } })
    await userEvent.selectOptions(screen.getByLabelText('Gender'), 'F')
    await userEvent.click(screen.getByRole('button', { name: 'Save and continue' }))

    expect(await screen.findByRole('heading', { name: 'My applications' })).toBeInTheDocument()
    expect(find('POST', '/api/identity/citizens')[0]?.body).toEqual({
      nameLatin: 'Asha Patil',
      givenName: 'Asha',
      familyName: 'Patil',
      fatherName: 'Ramesh',
      dob: '2003-04-05',
      dobPrecision: 'DAY',
      gender: 'F',
    })
    expect(readCitizenId(SUB)).toBe(CITIZEN_ID)
  })

  it('refuses a birth date in the future without calling the API', async () => {
    const { fetchImpl, calls } = mockFetch([])
    renderCitizen({ route: '/profile', fetchImpl, registered: false })

    await userEvent.type(await screen.findByLabelText(/Given name/), 'Asha')
    await userEvent.type(screen.getByLabelText(/Family name/), 'Patil')
    fireEvent.change(screen.getByLabelText(/Date of birth/), { target: { value: '2999-01-01' } })
    await userEvent.click(screen.getByRole('button', { name: 'Save and continue' }))

    expect(await screen.findByText('Date of birth cannot be in the future.')).toBeInTheDocument()
    expect(screen.getByLabelText(/Date of birth/)).toHaveAttribute('aria-invalid', 'true')
    expect(calls).toHaveLength(0)
  })

  it('shows the saved details, and lets the citizen forget a record that no longer exists', async () => {
    const { fetchImpl } = mockFetch([
      {
        method: 'GET',
        path: `/api/identity/citizens/${CITIZEN_ID}`,
        reply: { status: 403, body: { status: 403, detail: 'citizens may only act on their own record' } },
      },
    ])
    renderCitizen({ route: '/profile', fetchImpl })

    expect(await screen.findByRole('alert')).toHaveTextContent('citizens may only act on their own record')
    await userEvent.click(screen.getByRole('button', { name: 'Forget saved record' }))
    expect(await screen.findByRole('heading', { name: 'Your details' })).toBeInTheDocument()
    expect(readCitizenId(SUB)).toBeNull()
  })
})

describe('apply: connect accounts, consent, submit, then track', () => {
  /** A backend whose state moves as the citizen acts, like the real one. */
  function backend(opts: { startReply?: { status?: number; body?: unknown } } = {}) {
    const linked: Record<string, boolean> = { REVENUE: false, EDUCATION: true }
    let started = false
    return mockFetch([
      { method: 'GET', path: '/api/catalog/journeys/POST_MATRIC_SCHOLARSHIP', reply: { body: SCHOLARSHIP } },
      {
        method: 'GET',
        path: CONNECT,
        reply: () => ({
          body: {
            journeyCode: 'POST_MATRIC_SCHOLARSHIP',
            departments: [
              need('REVENUE', 'Revenue Department', linked.REVENUE!, ['INCOME_CERTIFICATE', 'CASTE_CERTIFICATE']),
              need('EDUCATION', 'Education Department', linked.EDUCATION!, ['MARKS']),
            ],
            providers: PROVIDERS,
          },
        }),
      },
      {
        method: 'POST',
        path: '/api/identity/links',
        reply: (call) => {
          const b = call.body as { departmentCode: string }
          linked[b.departmentCode] = true
          return { body: { id: 'l1', citizenId: CITIZEN_ID, departmentCode: b.departmentCode, status: 'ACTIVE' } }
        },
      },
      { method: 'POST', path: '/api/consent/requests', reply: { body: consentRequest } },
      { method: 'POST', path: `/api/consent/requests/${REQUEST_ID}/grant`, reply: { body: artifact } },
      {
        method: 'POST',
        path: '/api/journeys/POST_MATRIC_SCHOLARSHIP/start',
        reply: () => {
          if (opts.startReply) return opts.startReply
          started = true
          return { body: { id: INSTANCE_ID, processInstanceId: 'p1', journeyCode: 'POST_MATRIC_SCHOLARSHIP', citizenId: CITIZEN_ID } }
        },
      },
      {
        method: 'GET',
        path: APPS,
        reply: () => ({
          body: started
            ? [{ referenceNo: 'SCH-2026-0001', citizenId: CITIZEN_ID, journeyCode: 'POST_MATRIC_SCHOLARSHIP', status: 'SUBMITTED', slaDueAt: null, instanceId: INSTANCE_ID }]
            : [],
        }),
      },
      { method: 'GET', path: '/api/applications/SCH-2026-0001', reply: { body: applicationView } },
      { method: 'GET', path: '/api/applications/SCH-2026-0001/steps', reply: { body: steps } },
      { method: 'GET', path: '/api/applications/SCH-2026-0001/issued-records', reply: { body: issuedRecords } },
    ])
  }

  async function connectRevenue() {
    const card = (await screen.findByRole('heading', { name: /Revenue Department/ })).closest('li') as HTMLElement
    await userEvent.selectOptions(within(card).getByLabelText('How do you want to prove it?'), 'LOCAL_ID_OTP')
    await userEvent.type(within(card).getByLabelText(/Your ID with this department/), 'MH-REV-42')
    await userEvent.type(within(card).getByLabelText(/One-time code/), '000000')
    await userEvent.click(within(card).getByRole('button', { name: 'Connect Revenue Department' }))
  }

  it('walks a citizen from linking accounts to a tracked application', async () => {
    const { fetchImpl, find, unhandled } = backend()
    renderCitizen({ route: '/services/POST_MATRIC_SCHOLARSHIP/apply', fetchImpl })

    // 1. connect: continue is locked until every department is linked
    expect(await screen.findByText('Connected 1 of 2')).toBeInTheDocument()
    const proceed = screen.getByRole('button', { name: 'Continue to consent' })
    expect(proceed).toBeDisabled()
    expect(screen.getByRole('list', { name: 'Application steps' }).querySelector('[aria-current="step"]')).toHaveTextContent('Connect accounts')
    // DEPT_IDP is not offered: it needs a separate brokered sign-in this app does not do
    const revenueCard = screen.getByRole('heading', { name: /Revenue Department/ }).closest('li') as HTMLElement
    expect(within(revenueCard).queryByRole('option', { name: /Department sign-in/ })).not.toBeInTheDocument()

    await connectRevenue()

    expect(await screen.findByText('Connected 2 of 2')).toBeInTheDocument()
    expect(find('POST', '/api/identity/links')[0]?.body).toEqual({
      citizenId: CITIZEN_ID,
      departmentCode: 'REVENUE',
      localIdType: 'REVENUE',
      localId: 'MH-REV-42',
      provider: 'LOCAL_ID_OTP',
      proof: '000000',
    })
    await userEvent.click(screen.getByRole('button', { name: 'Continue to consent' }))

    // 2. consent: nothing is granted until the citizen has seen the request and agrees
    expect(find('POST', '/api/consent/requests')).toHaveLength(0)
    await userEvent.click(await screen.findByRole('button', { name: 'Review what will be shared' }))
    expect(await screen.findByText(/Check eligibility for the post-matric scholarship/)).toBeInTheDocument()
    expect(find('POST', '/api/consent/requests')[0]?.body).toEqual({
      citizenId: CITIZEN_ID,
      purposeCode: 'SCHOLARSHIP_ELIGIBILITY', // from the journey's policy, not hard-coded
    })
    expect(find('POST', `/api/consent/requests/${REQUEST_ID}/grant`)).toHaveLength(0)
    expect(screen.getByRole('button', { name: 'Continue to submit' })).toBeDisabled()

    await userEvent.click(screen.getByRole('button', { name: 'I agree: grant consent' }))
    expect(await screen.findByText(/Consent granted/)).toHaveTextContent('28 Dec 2026')
    expect(find('POST', `/api/consent/requests/${REQUEST_ID}/grant`)[0]?.body).toEqual({ citizenId: CITIZEN_ID })
    await userEvent.click(screen.getByRole('button', { name: 'Continue to submit' }))

    // 3. submit -> lands on the tracking page for the new application
    expect(find('POST', '/api/journeys/POST_MATRIC_SCHOLARSHIP/start')).toHaveLength(0)
    await userEvent.click(await screen.findByRole('button', { name: 'Submit application' }))

    expect(await screen.findByRole('heading', { name: /Application SCH-2026-0001/ })).toBeInTheDocument()
    expect(find('POST', '/api/journeys/POST_MATRIC_SCHOLARSHIP/start')[0]?.body).toEqual({
      citizenId: CITIZEN_ID,
      submission: {},
    })
    expect(screen.getByText(/In progress: some department records are still awaited/)).toBeInTheDocument()
    expect(screen.getByText('Received')).toBeInTheDocument()
    expect(screen.getByText('Waiting for the department')).toBeInTheDocument()
    expect(screen.getByText('Annual income')).toBeInTheDocument()
    expect(screen.getByText('Rs 180000')).toBeInTheDocument()
    expect(unhandled).toEqual([])
  })

  it('tells the citizen which department to connect when the server refuses the submit (409)', async () => {
    const { fetchImpl, find } = backend({
      startReply: {
        status: 409,
        body: {
          title: 'MissingDepartmentLinksException',
          status: 409,
          detail: 'Missing department links for POST_MATRIC_SCHOLARSHIP: REVENUE',
          reason: 'MISSING_DEPARTMENT_LINKS',
          missingDepartments: ['REVENUE'],
        },
      },
    })
    renderCitizen({ route: '/services/POST_MATRIC_SCHOLARSHIP/apply', fetchImpl })

    await connectRevenue()
    await userEvent.click(await screen.findByRole('button', { name: 'Continue to consent' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Review what will be shared' }))
    await userEvent.click(await screen.findByRole('button', { name: 'I agree: grant consent' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Continue to submit' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Submit application' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Connect your REVENUE account before submitting.')
    // the citizen can retry; the form is not stuck
    expect(screen.getByRole('button', { name: 'Submit application' })).toBeEnabled()
    expect(find('POST', '/api/journeys/POST_MATRIC_SCHOLARSHIP/start')).toHaveLength(1)
  })

  it('shows a friendly message when the department proof is refused', async () => {
    const { fetchImpl } = mockFetch([
      { method: 'GET', path: '/api/catalog/journeys/POST_MATRIC_SCHOLARSHIP', reply: { body: SCHOLARSHIP } },
      {
        method: 'GET',
        path: CONNECT,
        reply: {
          body: {
            journeyCode: 'POST_MATRIC_SCHOLARSHIP',
            departments: [need('REVENUE', 'Revenue Department', false, ['INCOME_CERTIFICATE'])],
            providers: PROVIDERS,
          },
        },
      },
      {
        method: 'POST',
        path: '/api/identity/links',
        reply: { status: 401, body: { status: 401, reason: 'LINK_PROOF_INVALID', detail: 'Department identity proof is invalid' } },
      },
    ])
    const auth = signedInAuth()
    renderCitizen({ route: '/services/POST_MATRIC_SCHOLARSHIP/apply', fetchImpl, auth })

    await userEvent.type(await screen.findByLabelText(/Your ID with this department/), 'X-1')
    await userEvent.click(screen.getByRole('button', { name: 'Connect Revenue Department' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('That verification was not accepted')
    expect(screen.getByText('Not connected')).toBeInTheDocument()
  })
})

describe('track applications', () => {
  it('lists the citizen\'s applications with service names and statuses', async () => {
    const { fetchImpl } = mockFetch([
      {
        method: 'GET',
        path: `/api/applications?citizenId=${CITIZEN_ID}&size=50`,
        reply: {
          body: [
            { referenceNo: 'SCH-2026-0001', citizenId: CITIZEN_ID, journeyCode: 'POST_MATRIC_SCHOLARSHIP', status: 'VERIFIED', slaDueAt: '2026-10-02T09:00:00Z', instanceId: INSTANCE_ID },
          ],
        },
      },
      { method: 'GET', path: '/api/catalog/journeys', reply: { body: [SCHOLARSHIP] } },
    ])
    renderCitizen({ route: '/applications', fetchImpl })

    const row = (await screen.findByRole('link', { name: 'SCH-2026-0001' })).closest('tr') as HTMLElement
    expect(within(row).getByText('Post-matric scholarship')).toBeInTheDocument()
    expect(within(row).getByText('Verified')).toBeInTheDocument()
    expect(within(row).getByText('2 Oct 2026')).toBeInTheDocument()
  })

  it('invites a citizen with no applications to browse services', async () => {
    const { fetchImpl } = mockFetch([
      { method: 'GET', path: `/api/applications?citizenId=${CITIZEN_ID}&size=50`, reply: { body: [] } },
      { method: 'GET', path: '/api/catalog/journeys', reply: { body: [] } },
    ])
    renderCitizen({ route: '/applications', fetchImpl })
    expect(await screen.findByText(/You have not applied for anything yet/)).toBeInTheDocument()
  })

  it('opens an application by its number', async () => {
    const { fetchImpl } = mockFetch([
      { method: 'GET', path: `/api/applications?citizenId=${CITIZEN_ID}&size=50`, reply: { body: [] } },
      { method: 'GET', path: '/api/catalog/journeys', reply: { body: [] } },
      { method: 'GET', path: '/api/applications/SCH-2026-0001', reply: { body: { ...applicationView, status: 'VERIFIED' } } },
      { method: 'GET', path: '/api/applications/SCH-2026-0001/steps', reply: { body: steps } },
      { method: 'GET', path: '/api/applications/SCH-2026-0001/issued-records', reply: { body: issuedRecords } },
    ])
    renderCitizen({ route: '/applications', fetchImpl })

    await userEvent.type(await screen.findByLabelText(/Track by application number/), ' SCH-2026-0001 ')
    await userEvent.click(screen.getByRole('button', { name: 'Track' }))
    expect(await screen.findByRole('heading', { name: /Application SCH-2026-0001/ })).toBeInTheDocument()
  })

  it('still shows the application when the fetched-records preview fails', async () => {
    const { fetchImpl } = mockFetch([
      { method: 'GET', path: '/api/applications/SCH-2026-0001', reply: { body: applicationView } },
      { method: 'GET', path: '/api/applications/SCH-2026-0001/steps', reply: { body: steps } },
      { method: 'GET', path: '/api/applications/SCH-2026-0001/issued-records', reply: { status: 500, body: { status: 500 } } },
    ])
    renderCitizen({ route: '/applications/SCH-2026-0001', fetchImpl })
    expect(await screen.findByRole('heading', { name: /Application SCH-2026-0001/ })).toBeInTheDocument()
    expect(screen.getByText('Waiting for the department')).toBeInTheDocument()
    expect(screen.queryByText('Records fetched for you')).not.toBeInTheDocument()
  })

  it('says so plainly when the application does not exist', async () => {
    const { fetchImpl } = mockFetch([
      { method: 'GET', path: '/api/applications/NOPE', reply: { status: 404, body: { status: 404 } } },
      { method: 'GET', path: '/api/applications/NOPE/steps', reply: { status: 404, body: { status: 404 } } },
    ])
    renderCitizen({ route: '/applications/NOPE', fetchImpl })
    expect(await screen.findByRole('heading', { name: 'Application not found' })).toBeInTheDocument()
  })

  it('keeps refreshing an in-progress application and stops once it is final', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      let status = 'PARTIALLY_VERIFIED'
      const { fetchImpl, find } = mockFetch([
        { method: 'GET', path: '/api/applications/SCH-2026-0001', reply: () => ({ body: { ...applicationView, status } }) },
        { method: 'GET', path: '/api/applications/SCH-2026-0001/steps', reply: { body: steps } },
        { method: 'GET', path: '/api/applications/SCH-2026-0001/issued-records', reply: { body: issuedRecords } },
      ])
      renderCitizen({ route: '/applications/SCH-2026-0001', fetchImpl })

      expect(await screen.findByText(/some department records are still awaited/)).toBeInTheDocument()
      expect(find('GET', '/api/applications/SCH-2026-0001')).toHaveLength(1)

      status = 'VERIFIED'
      await vi.advanceTimersByTimeAsync(REFRESH_MS + 100)
      expect(await screen.findByText(/Verified: department records were received/)).toBeInTheDocument()
      const afterFinal = find('GET', '/api/applications/SCH-2026-0001').length
      expect(afterFinal).toBe(2)

      await vi.advanceTimersByTimeAsync(REFRESH_MS * 3)
      expect(find('GET', '/api/applications/SCH-2026-0001')).toHaveLength(afterFinal)
    } finally {
      vi.useRealTimers()
    }
  })
})

describe('my consents', () => {
  const ended = { ...artifact, id: '55555555-5555-4555-8555-555555555555', status: 'EXPIRED', statusLabel: 'Ended' }

  it('lists consents and withdraws an active one after confirmation', async () => {
    let revoked = false
    const { fetchImpl, find } = mockFetch([
      {
        method: 'GET',
        path: `/api/consent/citizens/${CITIZEN_ID}`,
        reply: () => ({ body: revoked ? [{ ...artifact, status: 'REVOKED', statusLabel: 'Withdrawn by you' }, ended] : [artifact, ended] }),
      },
      {
        method: 'POST',
        path: `/api/consent/me/${artifact.id}/revoke`,
        reply: () => {
          revoked = true
          return {}
        },
      },
    ])
    renderCitizen({ route: '/consents', fetchImpl })

    expect(await screen.findAllByRole('heading', { level: 2 })).toHaveLength(2)
    expect(screen.getAllByRole('button', { name: 'Withdraw consent' })).toHaveLength(1) // ended one has none

    await userEvent.click(screen.getByRole('button', { name: 'Withdraw consent' }))
    expect(find('POST', `/api/consent/me/${artifact.id}/revoke`)).toHaveLength(0) // needs a second, explicit click
    await userEvent.click(screen.getByRole('button', { name: 'Yes, withdraw this consent' }))

    expect(await screen.findByText('Withdrawn by you')).toBeInTheDocument()
    expect(find('POST', `/api/consent/me/${artifact.id}/revoke`)).toHaveLength(1)
    expect(screen.queryByRole('button', { name: 'Withdraw consent' })).not.toBeInTheDocument()
  })

  it('can back out of a withdrawal', async () => {
    const { fetchImpl, find } = mockFetch([
      { method: 'GET', path: `/api/consent/citizens/${CITIZEN_ID}`, reply: { body: [artifact] } },
    ])
    renderCitizen({ route: '/consents', fetchImpl })
    await userEvent.click(await screen.findByRole('button', { name: 'Withdraw consent' }))
    await userEvent.click(screen.getByRole('button', { name: 'Keep it' }))
    expect(screen.getByRole('button', { name: 'Withdraw consent' })).toBeInTheDocument()
    expect(find('POST', /revoke/)).toHaveLength(0)
  })
})
