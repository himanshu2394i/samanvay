import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { CITIZEN_ID, mockFetch, need, PROVIDERS, renderCitizen, SCHOLARSHIP } from '../../test/utils'

// Make the real poll give up after two quick attempts instead of ten seconds.
vi.mock('./lib/applyFlow', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./lib/applyFlow')>()
  return {
    ...actual,
    pollForApplication: (api: Parameters<typeof actual.pollForApplication>[0], citizenId: string, instance: { id: string }) =>
      actual.pollForApplication(api, citizenId, instance, { attempts: 2, sleep: async () => {} }),
  }
})

const INSTANCE_ID = '22222222-2222-4222-8222-222222222222'
const REQUEST_ID = '33333333-3333-4333-8333-333333333333'

describe('submitting when the application number is slow to appear', () => {
  it('reassures the citizen and never files a second application on retry', async () => {
    const { fetchImpl, find } = mockFetch([
      { method: 'GET', path: '/api/catalog/journeys/POST_MATRIC_SCHOLARSHIP', reply: { body: SCHOLARSHIP } },
      {
        method: 'GET',
        path: `/api/identity/citizens/${CITIZEN_ID}/connect-accounts?journeyCode=POST_MATRIC_SCHOLARSHIP`,
        reply: {
          body: {
            journeyCode: 'POST_MATRIC_SCHOLARSHIP',
            departments: [need('REVENUE', 'Revenue Department', true, ['INCOME_CERTIFICATE'])],
            providers: PROVIDERS,
          },
        },
      },
      {
        method: 'POST',
        path: '/api/consent/requests',
        reply: {
          body: {
            id: REQUEST_ID,
            citizenId: CITIZEN_ID,
            requesterId: 'SCHOLARSHIP',
            purposeCode: 'SCHOLARSHIP_ELIGIBILITY',
            purposeText: 'Check eligibility',
            categories: ['INCOME_CERTIFICATE'],
            status: 'PENDING',
          },
        },
      },
      {
        method: 'POST',
        path: `/api/consent/requests/${REQUEST_ID}/grant`,
        reply: { body: { id: 'c1', validUntil: '2026-12-28T00:00:00Z', status: 'ACTIVE' } },
      },
      {
        method: 'POST',
        path: '/api/journeys/POST_MATRIC_SCHOLARSHIP/start',
        reply: { body: { id: INSTANCE_ID, processInstanceId: 'p1', journeyCode: 'POST_MATRIC_SCHOLARSHIP', citizenId: CITIZEN_ID } },
      },
      { method: 'GET', path: `/api/applications?citizenId=${CITIZEN_ID}&size=20`, reply: { body: [] } },
    ])
    renderCitizen({ route: '/services/POST_MATRIC_SCHOLARSHIP/apply', fetchImpl })

    await userEvent.click(await screen.findByRole('button', { name: 'Continue to consent' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Review what will be shared' }))
    await userEvent.click(await screen.findByRole('button', { name: 'I agree: grant consent' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Continue to submit' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Submit application' }))

    expect(await screen.findByText(/its number is not ready yet/)).toBeInTheDocument()
    expect(find('GET', `/api/applications?citizenId=${CITIZEN_ID}&size=20`)).toHaveLength(2)

    await userEvent.click(screen.getByRole('button', { name: 'Check for my application number' }))
    await vi.waitFor(() => expect(find('GET', `/api/applications?citizenId=${CITIZEN_ID}&size=20`)).toHaveLength(4))
    expect(find('POST', '/api/journeys/POST_MATRIC_SCHOLARSHIP/start')).toHaveLength(1)
  })
})
