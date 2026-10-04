import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { CONSENT, get, post, readiness, renderPortal, APPLICATION } from './testUtils'

const J = '/journeys/SCHOLARSHIP'

describe('journey page: connect departments', () => {
  it('shows each needed department as connected or not, and Submit is disabled', async () => {
    renderPortal({ route: J, routes: [get(`${J}/readiness`, readiness({ dbt: true }))] })
    expect(await screen.findByRole('heading', { name: 'Post-matric scholarship' })).toBeInTheDocument()
    expect(await screen.findByText('Not connected')).toBeInTheDocument()
    expect(screen.getByText('Connected')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Log in at Revenue' })).toBeEnabled()
    expect(screen.queryByRole('button', { name: /Log in at Direct Benefit/ })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Submit' })).toBeDisabled()
    expect(screen.getByText('Connect all the departments above first.')).toBeInTheDocument()
    expect(screen.queryByLabelText('One-time code')).not.toBeInTheDocument()
  })

  it('posts to the link route and sends the browser to the returned login address', async () => {
    const user = userEvent.setup()
    const goTo = vi.fn()
    const view = renderPortal({
      route: J,
      goTo,
      routes: [get(`${J}/readiness`, readiness()), post(`${J}/links/REVENUE`, { loginUrl: 'https://revenue.example/login?state=abc' })],
    })
    await user.click(await screen.findByRole('button', { name: 'Log in at Revenue' }))
    await waitFor(() => expect(goTo).toHaveBeenCalledWith('https://revenue.example/login?state=abc'))
    expect(view.find('POST', `/portal-api${J}/links/REVENUE`)[0]?.body).toEqual({})
  })

  it('shows a plain error when the link cannot be started', async () => {
    const user = userEvent.setup()
    const goTo = vi.fn()
    renderPortal({
      route: J,
      goTo,
      routes: [get(`${J}/readiness`, readiness()), post(`${J}/links/REVENUE`, { detail: 'Revenue sign in is closed right now.' }, 409)],
    })
    await user.click(await screen.findByRole('button', { name: 'Log in at Revenue' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Revenue sign in is closed right now.')
    expect(goTo).not.toHaveBeenCalled()
  })

  it('explains when a department login is not available', async () => {
    renderPortal({ route: J, routes: [get(`${J}/readiness`, readiness({ dbtLoginAvailable: false }))] })
    expect(await screen.findByRole('button', { name: 'Log in at Direct Benefit Transfer' })).toBeDisabled()
    expect(screen.getByText(/not available right now/)).toBeInTheDocument()
  })

  it('shows a success notice when the department login sends the citizen back (linked=REVENUE)', async () => {
    renderPortal({ route: `${J}?linked=REVENUE`, routes: [get(`${J}/readiness`, readiness({ revenue: true }))] })
    expect(await screen.findByText(/Your account at Revenue is now linked/)).toBeInTheDocument()
    expect(await screen.findByText('Not connected')).toBeInTheDocument() // DBT is still to do
  })

  it('shows a clear error when the link failed (linkError=REVENUE)', async () => {
    renderPortal({ route: `${J}?linkError=REVENUE`, routes: [get(`${J}/readiness`, readiness())] })
    expect(await screen.findByRole('alert')).toHaveTextContent('We could not connect your account at Revenue')
  })

  it('offers Try again when readiness is unavailable', async () => {
    renderPortal({ route: J, routes: [get(`${J}/readiness`, { detail: 'down' }, 503)] })
    expect(await screen.findByText(/temporarily unavailable/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })

  it('says so for a journey that does not exist', async () => {
    renderPortal({ route: '/journeys/NOPE', routes: [get('/journeys/NOPE', { detail: 'No such journey.' }, 404), get('/journeys/NOPE/readiness', {}, 404)] })
    expect(await screen.findByRole('heading', { name: /could not find that service/ })).toBeInTheDocument()
  })
})

describe('journey page: consent', () => {
  const connected = readiness({ revenue: true, dbt: true })

  it('shows the wording as given and posts the request id with the one-time code', async () => {
    const user = userEvent.setup()
    const view = renderPortal({
      route: J,
      routes: [get(`${J}/readiness`, connected), get(`${J}/consent`, CONSENT), post(`${J}/consent`, { granted: true })],
    })
    expect(await screen.findByText(CONSENT.purposeText)).toBeInTheDocument()
    expect(screen.getByText('Revenue, Direct Benefit Transfer')).toBeInTheDocument()
    expect(screen.getByText('Income certificate, Bank account')).toBeInTheDocument()
    expect(screen.getByText('30 days')).toBeInTheDocument()
    expect(screen.getByText(/withdraw this consent at any time/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Submit' })).toBeDisabled()

    await user.type(screen.getByLabelText('One-time code'), '123456')
    await user.click(screen.getByRole('button', { name: 'Confirm consent' }))

    expect(await screen.findByText('Consent given')).toBeInTheDocument()
    expect(view.find('POST', `/portal-api${J}/consent`)[0]?.body).toEqual({ requestId: 'req-1', code: '123456' })
    expect(screen.getByRole('button', { name: 'Submit' })).toBeEnabled()
  })

  it('keeps the person on the form with an inline error for a wrong code, and does not sign them out', async () => {
    const user = userEvent.setup()
    renderPortal({
      route: J,
      routes: [get(`${J}/readiness`, connected), get(`${J}/consent`, CONSENT), post(`${J}/consent`, { detail: 'That code is not right.' }, 401)],
    })
    await user.type(await screen.findByLabelText('One-time code'), '000000')
    await user.click(screen.getByRole('button', { name: 'Confirm consent' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('That code is not right.')
    expect(screen.getByLabelText('One-time code')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Sign in' })).not.toBeInTheDocument()
  })

  it('offers "Review again" when the request has expired, and fetches the wording again', async () => {
    const user = userEvent.setup()
    let reads = 0
    const view = renderPortal({
      route: J,
      routes: [
        get(`${J}/readiness`, connected),
        { method: 'GET', path: `/portal-api${J}/consent`, reply: () => ({ body: { ...CONSENT, requestId: `req-${++reads}` } }) },
        post(`${J}/consent`, { detail: 'Expired.' }, 410),
      ],
    })
    await user.type(await screen.findByLabelText('One-time code'), '123456')
    await user.click(screen.getByRole('button', { name: 'Confirm consent' }))
    expect(await screen.findByText(/has expired/)).toBeInTheDocument()
    expect(screen.queryByLabelText('One-time code')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Review again' }))
    expect(await screen.findByLabelText('One-time code')).toBeInTheDocument()
    expect(view.find('GET', `/portal-api${J}/consent`)).toHaveLength(2)
  })

  it('shows consent as done, without asking again, when it is already active', async () => {
    const view = renderPortal({ route: J, routes: [get(`${J}/readiness`, readiness({ revenue: true, dbt: true, consentActive: true }))] })
    expect(await screen.findByText('Consent given')).toBeInTheDocument()
    expect(screen.queryByLabelText('One-time code')).not.toBeInTheDocument()
    expect(view.find('GET', `/portal-api${J}/consent`)).toHaveLength(0)
    expect(screen.getByRole('button', { name: 'Submit' })).toBeEnabled()
  })
})

describe('journey page: apply', () => {
  const ready = readiness({ revenue: true, dbt: true, consentActive: true })

  it('renders the form from the definition and enforces required fields', async () => {
    const user = userEvent.setup()
    const view = renderPortal({
      route: J,
      routes: [get(`${J}/readiness`, ready), get('/applications', []), post(`${J}/submit`, { id: 'inst-1' })],
    })
    const course = await screen.findByLabelText('Course name')
    expect(screen.getByLabelText('Year of study').tagName).toBe('SELECT')
    expect(screen.getByLabelText('Anything else')).toBeInTheDocument()
    expect(screen.getByText('Optional')).toBeInTheDocument()

    await waitFor(() => expect(screen.getByRole('button', { name: 'Submit' })).toBeEnabled())
    await user.click(screen.getByRole('button', { name: 'Submit' }))
    expect(screen.getAllByText('This field is required.')).toHaveLength(2) // course and year, not the optional note
    expect(course).toHaveAttribute('aria-invalid', 'true')
    expect(view.find('POST', `/portal-api${J}/submit`)).toHaveLength(0)

    await user.type(course, 'BSc Physics')
    await user.selectOptions(screen.getByLabelText('Year of study'), 'Second')
    await user.click(screen.getByRole('button', { name: 'Submit' }))

    await waitFor(() => expect(view.find('POST', `/portal-api${J}/submit`)).toHaveLength(1))
    expect(view.find('POST', `/portal-api${J}/submit`)[0]?.body).toEqual({ submission: { course: 'BSc Physics', year: 'Second', note: '' } })
    // goes to the Track screen
    expect(await screen.findByRole('heading', { name: 'My applications' })).toBeInTheDocument()
  })

  it('shows the server reason inline when the application is refused', async () => {
    const user = userEvent.setup()
    renderPortal({
      route: J,
      routes: [get(`${J}/readiness`, ready), get('/applications', [APPLICATION]), post(`${J}/submit`, { detail: 'You already have an open application.' }, 400)],
    })
    await user.type(await screen.findByLabelText('Course name'), 'BSc')
    await user.selectOptions(screen.getByLabelText('Year of study'), 'First')
    await user.click(screen.getByRole('button', { name: 'Submit' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('You already have an open application.')
    expect(screen.getByRole('heading', { name: 'Post-matric scholarship' })).toBeInTheDocument()
  })

  it('goes back to the steps when the server says something is missing (409)', async () => {
    const user = userEvent.setup()
    let readinessReads = 0
    renderPortal({
      route: J,
      routes: [
        {
          method: 'GET',
          path: `/portal-api${J}/readiness`,
          reply: () => ({ body: ++readinessReads === 1 ? ready : readiness({ revenue: true, dbt: true, consentActive: false }) }),
        },
        get(`${J}/consent`, CONSENT),
        get('/applications', []),
        post(`${J}/submit`, { detail: 'Your consent is no longer active.' }, 409),
      ],
    })
    await user.type(await screen.findByLabelText('Course name'), 'BSc')
    await user.selectOptions(screen.getByLabelText('Year of study'), 'First')
    await user.click(screen.getByRole('button', { name: 'Submit' }))
    expect(await screen.findByText('Your consent is no longer active.')).toBeInTheDocument()
    const step2 = screen.getByRole('heading', { name: /Review and give consent/ }).closest('li')!
    expect(await within(step2).findByLabelText('One-time code')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Submit' })).toBeDisabled()
  })
})
