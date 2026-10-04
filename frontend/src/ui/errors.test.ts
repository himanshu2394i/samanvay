import { describe, expect, it } from 'vitest'
import { ApiError, AuthRequiredError } from '../api/client'
import { describeError } from './errors'

describe('describeError', () => {
  it('says what happened in plain words for each kind of failure', () => {
    expect(describeError(new AuthRequiredError())).toBe('Your session has ended. Sign in again to continue.')
    expect(describeError(new ApiError(401, 'x'))).toBe('Your session has ended. Sign in again to continue.')
    expect(describeError(new ApiError(0, 'x'))).toMatch(/could not be reached/)
    expect(describeError(new ApiError(404, 'x'))).toBe('Nothing was found for that request.')
    expect(describeError(new ApiError(503, 'x'))).toMatch(/Try again in a moment/)
    expect(describeError(new ApiError(409, 'x', { reason: 'APPLICATION_NOT_APPROVABLE' }))).toMatch(/can't be approved yet/)
  })

  it('passes a short server message through, but not a long one, and hides a bare Forbidden', () => {
    expect(describeError(new ApiError(400, 'Reason is required'))).toBe('Reason is required')
    expect(describeError(new ApiError(400, 'x'.repeat(201)))).toBe('The request could not be completed. Try again.')
    expect(describeError(new ApiError(403, 'Forbidden'))).toBe('You are not allowed to do that.')
    expect(describeError(new ApiError(403, 'Not your department'))).toBe('Not your department')
  })

  it('falls back for something that is not an API error', () => {
    expect(describeError(new Error('boom'))).toBe('boom')
    expect(describeError('weird')).toBe('Something went wrong. Try again.')
  })
})
