import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { get, post, renderPortal, SESSION, JOURNEY, BRANDING, APPLICATION } from './testUtils'

const signedOut = get('/me', { detail: 'Not signed in.' }, 401)

describe('sign in', () => {
  it('asks for number and password, then the one-time code, then shows the services', async () => {
    const user = userEvent.setup()
    const view = renderPortal({
      route: '/',
      routes: [signedOut, post('/sign-in', { ticket: 't-1', masked: 'ending 0001' }), post('/verify', SESSION)],
    })

    // not signed in: sent to sign in
    await user.type(await screen.findByLabelText('Mobile number'), '9000000001')
    await user.type(screen.getByLabelText('Password'), 'secret-pw')
    await user.click(screen.getByRole('button', { name: 'Continue' }))

    expect(await screen.findByText(/ending 0001/)).toBeInTheDocument()
    expect(view.find('POST', '/portal-api/sign-in')[0]?.body).toEqual({ mobile: '9000000001', password: 'secret-pw' })

    await user.type(screen.getByLabelText('One-time code'), '123456')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByRole('heading', { name: 'Services' })).toBeInTheDocument()
    expect(view.find('POST', '/portal-api/verify')[0]?.body).toEqual({ ticket: 't-1', code: '123456' })
  })

  it('shows a wrong password in plain words and stays on the first step', async () => {
    const user = userEvent.setup()
    renderPortal({
      route: '/sign-in',
      routes: [signedOut, post('/sign-in', { detail: 'The mobile number or password is not right.' }, 401)],
    })
    await user.type(await screen.findByLabelText('Mobile number'), '9000000001')
    await user.type(screen.getByLabelText('Password'), 'nope')
    await user.click(screen.getByRole('button', { name: 'Continue' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('The mobile number or password is not right.')
    expect(screen.getByLabelText('Password')).toBeInTheDocument()
    expect(screen.queryByLabelText('One-time code')).not.toBeInTheDocument()
  })

  it('keeps the ticket after a wrong code so the person can try again', async () => {
    const user = userEvent.setup()
    let attempts = 0
    const view = renderPortal({
      route: '/sign-in',
      routes: [
        signedOut,
        post('/sign-in', { ticket: 't-9', masked: 'ending 0001' }),
        {
          method: 'POST',
          path: '/portal-api/verify',
          reply: () => (++attempts === 1 ? { status: 401, body: { detail: 'That code is not right.' } } : { body: SESSION }),
        },
      ],
    })
    await user.type(await screen.findByLabelText('Mobile number'), '9000000001')
    await user.type(screen.getByLabelText('Password'), 'pw')
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    await user.type(await screen.findByLabelText('One-time code'), '000000')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('That code is not right.')
    await user.clear(screen.getByLabelText('One-time code'))
    await user.type(screen.getByLabelText('One-time code'), '123456')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByRole('heading', { name: 'Services' })).toBeInTheDocument()
    expect(view.find('POST', '/portal-api/verify').map((c) => (c.body as { ticket: string }).ticket)).toEqual(['t-9', 't-9'])
  })

  it('lets the person go back with "Use a different number"', async () => {
    const user = userEvent.setup()
    renderPortal({ route: '/sign-in', routes: [signedOut, post('/sign-in', { ticket: 't-1', masked: 'ending 0001' })] })
    await user.type(await screen.findByLabelText('Mobile number'), '9000000001')
    await user.type(screen.getByLabelText('Password'), 'pw')
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    await user.click(await screen.findByRole('button', { name: 'Use a different number' }))
    expect(screen.getByLabelText('Mobile number')).toHaveValue('9000000001')
    expect(screen.getByLabelText('Password')).toHaveValue('')
  })

  it('signs out and returns to sign in', async () => {
    const user = userEvent.setup()
    const view = renderPortal({ route: '/', routes: [{ method: 'POST', path: '/portal-api/sign-out', reply: { status: 204 } }] })
    await user.click(await screen.findByRole('button', { name: 'Sign out' }))
    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    expect(view.find('POST', '/portal-api/sign-out')).toHaveLength(1)
  })
})

describe('shell', () => {
  it('applies the department branding and shows its name', async () => {
    renderPortal({ route: '/', routes: [] })
    expect(await screen.findByText(BRANDING.name)).toBeInTheDocument()
    await waitFor(() => expect(document.documentElement.style.getPropertyValue('--accent')).toBe(BRANDING.accent))
    expect(document.documentElement.style.getPropertyValue('--accent-dark')).toBe(BRANDING.accentDark)
    expect(document.documentElement.style.getPropertyValue('--on-accent-dark')).toBe(BRANDING.onAccentDark)
    expect(screen.getByText(SESSION.name)).toBeInTheDocument()
  })

  it('shows a not found screen for an unknown address', async () => {
    renderPortal({ route: '/nowhere', routes: [] })
    expect(await screen.findByRole('heading', { name: /could not find that page/ })).toBeInTheDocument()
  })

  it('returns to sign in when any call answers 401', async () => {
    renderPortal({ route: '/applications', routes: [get('/applications', { detail: 'Your session has ended.' }, 401)] })
    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
  })
})

describe('services', () => {
  it('lists the department journeys from the API with a Start link each', async () => {
    renderPortal({ route: '/', routes: [] })
    expect(await screen.findByText(JOURNEY.name)).toBeInTheDocument()
    expect(screen.getByText(JOURNEY.description)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: `Start ${JOURNEY.name}` })).toHaveAttribute('href', '/journeys/SCHOLARSHIP')
    expect(screen.getAllByRole('link', { name: /^Start / })).toHaveLength(2)
    expect(screen.getAllByRole('link', { name: 'My applications' }).length).toBeGreaterThan(0)
  })

  it('says so when there are no services', async () => {
    renderPortal({ route: '/', routes: [get('/journeys', [])] })
    expect(await screen.findByText(/no services here yet/)).toBeInTheDocument()
  })

  it('says the service is unavailable on a 503 and offers Try again', async () => {
    const user = userEvent.setup()
    let n = 0
    renderPortal({
      route: '/',
      routes: [{ method: 'GET', path: '/portal-api/journeys', reply: () => (++n === 1 ? { status: 503 } : { body: [JOURNEY] }) }],
    })
    expect(await screen.findByRole('alert')).toHaveTextContent('temporarily unavailable')
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByText(JOURNEY.name)).toBeInTheDocument()
  })
})

describe('applications', () => {
  it('lists applications with humanised status badges', async () => {
    renderPortal({
      route: '/applications',
      routes: [get('/applications', [APPLICATION, { ...APPLICATION, referenceNo: 'SCH-2026-0002', status: 'IN_PROGRESS', slaDueAt: null }])],
    })
    expect(await screen.findByRole('link', { name: 'SCH-2026-0001' })).toHaveAttribute('href', '/applications/SCH-2026-0001')
    expect(screen.getByText('Verified')).toBeInTheDocument()
    expect(screen.getByText('In progress')).toBeInTheDocument()
  })

  it('shows an empty state', async () => {
    renderPortal({ route: '/applications', routes: [get('/applications', [])] })
    expect(await screen.findByText(/not applied for anything yet/)).toBeInTheDocument()
  })

  it('polls for a new application after submitting, then shows it', async () => {
    let calls = 0
    renderPortal({
      route: { pathname: '/applications', state: { submittedAfter: 0 } },
      routes: [{ method: 'GET', path: '/portal-api/applications', reply: () => ({ body: ++calls >= 2 ? [APPLICATION] : [] }) }],
    })
    expect(await screen.findByText(/can take a few seconds to appear/)).toBeInTheDocument()
    expect(screen.queryByText(/not applied for anything yet/)).not.toBeInTheDocument()
    expect(await screen.findByRole('link', { name: 'SCH-2026-0001' }, { timeout: 4000 })).toBeInTheDocument()
    expect(calls).toBe(2)
    expect(screen.getByText(/You can follow it below/)).toBeInTheDocument()
  })

  it('shows one application with progress by department and the records received', async () => {
    renderPortal({
      route: '/applications/SCH-2026-0001',
      routes: [
        get('/applications/SCH-2026-0001', APPLICATION),
        get('/applications/SCH-2026-0001/steps', [
          { stepCode: 'FETCH_INCOME', departmentCode: 'REVENUE', status: 'COMPLETED' },
          { stepCode: 'FETCH_BANK', departmentCode: 'DBT', status: 'PENDING' },
        ]),
        get('/applications/SCH-2026-0001/records', [{ category: 'INCOME_CERTIFICATE', annualIncome: 120000, holder: { name: 'Asha' } }]),
      ],
    })
    expect(await screen.findByRole('heading', { name: 'Application SCH-2026-0001' })).toBeInTheDocument()
    expect(await screen.findByText('Fetch income')).toBeInTheDocument()
    expect(screen.getByText('Revenue')).toBeInTheDocument()
    expect(screen.getByText('Completed')).toBeInTheDocument()
    expect(screen.getByText('Pending')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Records received' })).toBeInTheDocument()
    expect(await screen.findByText('Annual income')).toBeInTheDocument()
    expect(screen.getByText('120000')).toBeInTheDocument()
    expect(screen.getByText('{"name":"Asha"}')).toBeInTheDocument()
  })

  it('shows not found for an unknown application', async () => {
    renderPortal({
      route: '/applications/NOPE',
      routes: [get('/applications/NOPE', { detail: 'No such application.' }, 404), get('/applications/NOPE/steps', [], 404), get('/applications/NOPE/records', [], 404)],
    })
    expect(await screen.findByRole('heading', { name: /could not find that application/ })).toBeInTheDocument()
  })
})
