import { ApiError, AuthRequiredError } from '../api/client'
import { enT, type TFunction } from '../i18n'

/**
 * Citizen-readable message for anything the API layer can throw. `t` localizes the copy for
 * the citizen surface; it defaults to English so the staff surfaces (no LanguageProvider) and
 * any non-UI caller keep working unchanged. Raw server messages (403/fallback) pass through
 * untranslated.
 */
export function describeError(e: unknown, t: TFunction = enT): string {
  if (e instanceof AuthRequiredError) return t('errors.sessionEnded')
  if (e instanceof ApiError) {
    switch (e.reason) {
      case 'MISSING_DEPARTMENT_LINKS': {
        const missing = e.problem?.missingDepartments ?? []
        const departments = missing.length ? missing.join(', ') : t('errors.departmentWord')
        const accounts = missing.length === 1 ? t('errors.accountOne') : t('errors.accountMany')
        return t('errors.missingDepartments', { departments, accounts })
      }
      case 'LINK_PROOF_INVALID':
        return t('errors.linkProofInvalid')
      case 'DUPLICATE_LOCAL_ID':
        return t('errors.duplicateLocalId')
      case 'NO_PRIOR_AWARD':
        return t('errors.noPriorAward')
      case 'APPLICATION_NOT_APPROVABLE':
        return t('errors.notApprovable')
    }
    switch (e.status) {
      case 0:
        return t('errors.unreachable')
      case 401:
        return t('errors.sessionEnded')
      case 403:
        return e.message && e.message !== 'Forbidden' ? e.message : t('errors.forbidden')
      case 404:
        return t('errors.notFound')
    }
    if (e.status >= 500) return t('errors.serverError')
    return e.message.length > 200 ? t('errors.generic') : e.message
  }
  return e instanceof Error && e.message ? e.message : t('errors.somethingWrong')
}
