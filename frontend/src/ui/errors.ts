import { ApiError, AuthRequiredError } from '../api/client'

/**
 * Readable message for anything the API layer can throw. Raw server messages (403 and the fallback) pass
 * through untouched when they are short enough to be useful.
 */
export function describeError(e: unknown): string {
  if (e instanceof AuthRequiredError) return 'Your session has ended. Sign in again to continue.'
  if (e instanceof ApiError) {
    if (e.reason === 'APPLICATION_NOT_APPROVABLE') {
      return "This application can't be approved yet — a department record is still pending."
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
