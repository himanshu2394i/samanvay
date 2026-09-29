import { ApiError, AuthRequiredError } from '../api/client'

/** Citizen-readable message for anything the API layer can throw. */
export function describeError(e: unknown): string {
  if (e instanceof AuthRequiredError) return 'Your session has ended. Sign in again to continue.'
  if (e instanceof ApiError) {
    switch (e.reason) {
      case 'MISSING_DEPARTMENT_LINKS': {
        const missing = e.problem?.missingDepartments ?? []
        return `Connect your ${missing.length ? missing.join(', ') : 'department'} account${missing.length === 1 ? '' : 's'} before submitting.`
      }
      case 'LINK_PROOF_INVALID':
        return 'That verification was not accepted. Check the details and try again.'
      case 'DUPLICATE_LOCAL_ID':
        return 'That department ID is already linked to another person.'
      case 'NO_PRIOR_AWARD':
        return 'This service needs an approved award from the previous year, and none was found for you.'
    }
    switch (e.status) {
      case 0:
        return 'The Samanvay service could not be reached. Check that the application is running and try again.'
      case 401:
        return 'Your session has ended. Sign in again to continue.'
      case 403:
        return e.message && e.message !== 'Forbidden' ? e.message : 'You are not allowed to do that.'
      case 404:
        return 'Nothing was found for that request.'
    }
    if (e.status >= 500) return 'The service could not complete that request. Try again in a moment.'
    return e.message.length > 200 ? 'The request could not be completed. Try again.' : e.message
  }
  return e instanceof Error && e.message ? e.message : 'Something went wrong. Try again.'
}
