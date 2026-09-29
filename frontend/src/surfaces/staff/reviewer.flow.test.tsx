import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { CITIZEN } from '../../test/staffFixtures'
import { mockFetch, renderStaff, staffAuth } from '../../test/utils'

const CAND = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const candidate = { id: CAND, citizenId: CITIZEN, departmentCode: 'REVENUE', score: 0.87, status: 'PENDING' }

describe('reviewer: identity review queue', () => {
  it('confirms a candidate link with a note and reloads (paged response)', async () => {
    let rows = [candidate]
    const m = mockFetch([
      { method: 'GET', path: '/api/identity/review-queue?size=50', reply: () => ({ body: { content: rows, totalElements: rows.length } }) },
      { method: 'POST', path: `/api/identity/candidates/${CAND}/confirm`, reply: () => { rows = []; return { body: { id: 'link-1' } } } },
    ])
    renderStaff({ route: '/staff/reviewer/queue', fetchImpl: m.fetchImpl, auth: staffAuth(['reviewer']) })
    expect(await screen.findByText(/Match score 87%/)).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText(/Note/), 'documents checked')
    await userEvent.click(screen.getByRole('button', { name: 'Confirm link' }))
    expect(await screen.findByText(/Confirmed the link for citizen 11111111/)).toBeInTheDocument()
    expect(m.find('POST', `/api/identity/candidates/${CAND}/confirm`)[0]?.body).toEqual({ note: 'documents checked' })
    expect(await screen.findByText('The review queue is empty.')).toBeInTheDocument()
  })

  it('rejects a candidate, and accepts a bare array response', async () => {
    const m = mockFetch([
      { method: 'GET', path: '/api/identity/review-queue?size=50', reply: { body: [candidate] } },
      { method: 'POST', path: `/api/identity/candidates/${CAND}/reject`, reply: { status: 200 } },
    ])
    renderStaff({ route: '/staff/reviewer/queue', fetchImpl: m.fetchImpl, auth: staffAuth(['reviewer']) })
    await userEvent.click(await screen.findByRole('button', { name: 'Reject' }))
    expect(await screen.findByText(/Rejected the candidate/)).toBeInTheDocument()
    expect(m.find('POST', `/api/identity/candidates/${CAND}/reject`)[0]?.body).toEqual({})
  })
})
