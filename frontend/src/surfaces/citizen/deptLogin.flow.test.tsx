import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { CITIZEN_ID, mockFetch, need, PROVIDERS, renderCitizen, SCHOLARSHIP } from '../../test/utils'
import { navigation } from './lib/deptLogin'

const CONNECT = `/api/identity/citizens/${CITIZEN_ID}/connect-accounts?journeyCode=POST_MATRIC_SCHOLARSHIP`
const LOGIN_URL = 'https://revenue.example.gov/login?return_to=x&state=s1&nonce=n1'

/** A department that publishes its own login (Revenue) next to one that does not (Education). */
function backend(extra: Parameters<typeof mockFetch>[0] = []) {
  return mockFetch([
    { method: 'GET', path: '/api/catalog/journeys/POST_MATRIC_SCHOLARSHIP', reply: { body: SCHOLARSHIP } },
    {
      method: 'GET',
      path: CONNECT,
      reply: {
        body: {
          journeyCode: 'POST_MATRIC_SCHOLARSHIP',
          departments: [
            { ...need('REVENUE', 'Revenue Department', false, ['INCOME_CERTIFICATE']), departmentLoginAvailable: true },
            need('EDUCATION', 'Education Department', false, ['MARKS']),
          ],
          providers: PROVIDERS,
        },
      },
    },
    ...extra,
  ])
}

describe('connect accounts: log in at the department', () => {
  let to: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    sessionStorage.clear()
    to = vi.spyOn(navigation, 'to').mockImplementation(() => {})
  })
  afterEach(() => to.mockRestore())

  it('offers a department that has its own login a "Log in at" button instead of asking for an ID', async () => {
    const m = backend()
    renderCitizen({ route: '/services/POST_MATRIC_SCHOLARSHIP/apply', fetchImpl: m.fetchImpl })
    const card = (await screen.findByRole('heading', { name: /Revenue Department/ })).closest('li') as HTMLElement
    expect(within(card).getByRole('button', { name: 'Log in at Revenue Department' })).toBeInTheDocument()
    expect(within(card).queryByLabelText(/Your ID with this department/)).not.toBeInTheDocument()
    // a department without a login keeps the labelled demo form
    const other = screen.getByRole('heading', { name: /Education Department/ }).closest('li') as HTMLElement
    expect(within(other).getByLabelText(/Your ID with this department/)).toBeInTheDocument()
    expect(within(other).queryByRole('button', { name: /Log in at/ })).not.toBeInTheDocument()
  })

  it('asks Samanvay for the department login address, remembers where to come back to, and goes there', async () => {
    const m = backend([{ method: 'POST', path: '/api/identity/department-login', reply: { body: { loginUrl: LOGIN_URL } } }])
    renderCitizen({ route: '/services/POST_MATRIC_SCHOLARSHIP/apply', fetchImpl: m.fetchImpl })
    const card = (await screen.findByRole('heading', { name: /Revenue Department/ })).closest('li') as HTMLElement
    await userEvent.click(within(card).getByRole('button', { name: 'Log in at Revenue Department' }))

    await waitFor(() => expect(to).toHaveBeenCalledWith(LOGIN_URL))
    expect(m.find('POST', '/api/identity/department-login')[0]?.body).toEqual({
      citizenId: CITIZEN_ID,
      departmentCode: 'REVENUE',
      returnTo: `${window.location.origin}${window.location.pathname}#/dept-callback`,
    })
    expect(JSON.parse(sessionStorage.getItem('samanvay.deptLogin') ?? 'null')).toEqual({
      citizenId: CITIZEN_ID,
      departmentCode: 'REVENUE',
      returnPath: '/services/POST_MATRIC_SCHOLARSHIP/apply',
    })
  })

  it('never follows a login address that is not http(s), and says so', async () => {
    const m = backend([{ method: 'POST', path: '/api/identity/department-login', reply: { body: { loginUrl: 'javascript:alert(1)' } } }])
    renderCitizen({ route: '/services/POST_MATRIC_SCHOLARSHIP/apply', fetchImpl: m.fetchImpl })
    const card = (await screen.findByRole('heading', { name: /Revenue Department/ })).closest('li') as HTMLElement
    await userEvent.click(within(card).getByRole('button', { name: 'Log in at Revenue Department' }))
    expect(await within(card).findByRole('alert')).toHaveTextContent(/login address is not usable/i)
    expect(to).not.toHaveBeenCalled()
    expect(sessionStorage.getItem('samanvay.deptLogin')).toBeNull()
  })

  it('shows the server refusal when it will not start a department login', async () => {
    const m = backend([
      {
        method: 'POST',
        path: '/api/identity/department-login',
        reply: { status: 400, body: { title: 'Invalid request', detail: 'returnTo is not an allowed return address' } },
      },
    ])
    renderCitizen({ route: '/services/POST_MATRIC_SCHOLARSHIP/apply', fetchImpl: m.fetchImpl })
    const card = (await screen.findByRole('heading', { name: /Revenue Department/ })).closest('li') as HTMLElement
    await userEvent.click(within(card).getByRole('button', { name: 'Log in at Revenue Department' }))
    expect(await within(card).findByRole('alert')).toHaveTextContent('returnTo is not an allowed return address')
    expect(to).not.toHaveBeenCalled()
  })
})

describe('coming back from the department', () => {
  beforeEach(() => {
    sessionStorage.clear()
    sessionStorage.setItem('samanvay.deptLogin', JSON.stringify({ citizenId: CITIZEN_ID, departmentCode: 'REVENUE', returnPath: '/services/POST_MATRIC_SCHOLARSHIP/apply' }))
  })

  it('hands the signed assertion to Samanvay, which saves the link, then returns the citizen to where they were', async () => {
    const m = backend([
      { method: 'POST', path: '/api/identity/links', reply: { body: { id: 'l1', citizenId: CITIZEN_ID, departmentCode: 'REVENUE', status: 'ACTIVE' } } },
    ])
    renderCitizen({ route: '/dept-callback?assertion=aa.bb.cc&state=s1', fetchImpl: m.fetchImpl })

    // back on the apply page, connect step
    expect(await screen.findByRole('heading', { name: 'Connect your department accounts' })).toBeInTheDocument()
    expect(m.find('POST', '/api/identity/links')[0]?.body).toEqual({
      citizenId: CITIZEN_ID,
      departmentCode: 'REVENUE',
      localIdType: '',
      localId: '',
      provider: 'DEPT_ASSERTION',
      proof: 'aa.bb.cc',
    })
    expect(sessionStorage.getItem('samanvay.deptLogin')).toBeNull() // used once
  })

  it('says plainly when the department login was not started here, and does not call Samanvay', async () => {
    sessionStorage.clear()
    const m = backend()
    renderCitizen({ route: '/dept-callback?assertion=aa.bb.cc&state=s1', fetchImpl: m.fetchImpl })
    expect(await screen.findByRole('alert')).toHaveTextContent(/was not started here/i)
    expect(m.find('POST', '/api/identity/links')).toHaveLength(0)
    expect(screen.getByRole('link', { name: /Back to services/ })).toBeInTheDocument()
  })

  it('says so when the department sent back no login result or an error', async () => {
    const m = backend()
    renderCitizen({ route: '/dept-callback?error=access_denied', fetchImpl: m.fetchImpl })
    expect(await screen.findByRole('alert')).toHaveTextContent('The department login did not complete. Please try again.')
    expect(m.find('POST', '/api/identity/links')).toHaveLength(0)
  })

  it('shows why Samanvay refused the assertion and does not pretend the account is linked', async () => {
    const m = backend([
      {
        method: 'POST',
        path: '/api/identity/links',
        reply: { status: 400, body: { title: 'Invalid request', detail: 'The department login could not be verified. Start again.' } },
      },
    ])
    renderCitizen({ route: '/dept-callback?assertion=aa.bb.cc&state=s1', fetchImpl: m.fetchImpl })
    expect(await screen.findByRole('alert')).toHaveTextContent('The department login could not be verified. Start again.')
    expect(screen.getByRole('link', { name: /Back to services/ })).toBeInTheDocument()
  })
})
