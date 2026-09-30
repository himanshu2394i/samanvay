import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { mockFetch, renderCitizen, SCHOLARSHIP, signedInAuth, signedOutAuth } from '../../test/utils'

// The published journeys the services page lists.
const journeysRoute = { method: 'GET', path: '/api/catalog/journeys', reply: { body: [SCHOLARSHIP] } }
const LANDING_MR = 'कागदपत्रे न बाळगता शासकीय सेवांसाठी अर्ज करा'
const LANDING_EN = 'Apply for government services without carrying papers'

describe('citizen language toggle', () => {
  it('offers an accessible EN / मराठी control that reflects the current choice', () => {
    const { fetchImpl } = mockFetch([])
    renderCitizen({ route: '/', fetchImpl, auth: signedOutAuth() })

    const group = screen.getByRole('group', { name: 'Language' })
    const english = within(group).getByRole('button', { name: 'English' })
    const marathi = within(group).getByRole('button', { name: 'मराठी' })
    // English is the default and is shown as pressed.
    expect(english).toHaveAttribute('aria-pressed', 'true')
    expect(marathi).toHaveAttribute('aria-pressed', 'false')
    // The Marathi button is marked lang="mr" so screen readers pronounce Devanagari.
    expect(marathi).toHaveAttribute('lang', 'mr')
  })

  it('switches the landing page to Marathi and sets <html lang>', async () => {
    const user = userEvent.setup()
    const { fetchImpl } = mockFetch([])
    renderCitizen({ route: '/', fetchImpl, auth: signedOutAuth() })

    // English by default.
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(LANDING_EN)
    expect(document.documentElement.lang).toBe('en')

    await user.click(screen.getByRole('button', { name: 'मराठी' }))

    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(LANDING_MR)
    expect(document.documentElement.lang).toBe('mr')
    // The toggle now reflects Marathi as the pressed choice.
    expect(screen.getByRole('button', { name: 'मराठी' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('renders the services page in Marathi once selected', async () => {
    const user = userEvent.setup()
    const { fetchImpl } = mockFetch([journeysRoute])
    renderCitizen({ route: '/services', fetchImpl, auth: signedInAuth() })

    // Wait for the (English) services page to load.
    expect(await screen.findByRole('heading', { name: 'Services' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'मराठी' }))

    expect(screen.getByRole('heading', { name: 'सेवा' })).toBeInTheDocument()
    expect(
      screen.getByText('सेवेला काय आवश्यक आहे ते पाहण्यासाठी आणि अर्ज करण्यासाठी एक सेवा निवडा.'),
    ).toBeInTheDocument()
  })

  it('persists the choice across a reload', async () => {
    const user = userEvent.setup()
    const first = renderCitizen({ route: '/', fetchImpl: mockFetch([]).fetchImpl, auth: signedOutAuth() })
    await user.click(screen.getByRole('button', { name: 'मराठी' }))
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(LANDING_MR)

    // Simulate a reload: unmount and mount a fresh tree. localStorage carries the choice.
    first.unmount()
    renderCitizen({ route: '/', fetchImpl: mockFetch([]).fetchImpl, auth: signedOutAuth() })

    await waitFor(() => expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(LANDING_MR))
    expect(document.documentElement.lang).toBe('mr')
  })
})
