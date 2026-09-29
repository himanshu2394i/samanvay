import type { ApiClient } from './client'
import type {
  ApplicationSummary,
  ApplicationView,
  ConnectAccounts,
  ConsentArtifact,
  ConsentRequest,
  Department,
  Disbursement,
  JourneyDefinition,
  IssuedRecord,
  JourneyInstance,
  Link,
  LinkProofProviderInfo,
  LinkRequest,
  Profile,
  ProfileDraft,
  StepView,
  Uuid,
} from './types'

/**
 * Every endpoint the citizen surface calls. Paths, verbs and bodies are taken from the
 * controllers under src/main/java/**\/internal/web; the role rules live in SecurityConfig
 * (a citizen token may act only on the citizen record bound to its own subject).
 */
export function createCitizenApi(client: ApiClient) {
  const enc = encodeURIComponent
  return {
    // --- browse services (CatalogController) ---
    listJourneys: () => client.get<JourneyDefinition[]>('/api/catalog/journeys'),
    getJourney: (code: string) => client.get<JourneyDefinition>(`/api/catalog/journeys/${enc(code)}`),
    listDepartments: () => client.get<Department[]>('/api/catalog/departments'),

    // --- identity (IdentityController) ---
    /** Idempotent for a citizen token: returns the record already bound to the token's subject. */
    registerSelf: (draft: ProfileDraft) => client.post<Uuid>('/api/identity/citizens', draft),
    getProfile: (citizenId: Uuid) => client.get<Profile>(`/api/identity/citizens/${enc(citizenId)}`),
    proofProviders: () => client.get<LinkProofProviderInfo[]>('/api/identity/proof-providers'),
    connectAccounts: (citizenId: Uuid, journeyCode: string) =>
      client.get<ConnectAccounts>(`/api/identity/citizens/${enc(citizenId)}/connect-accounts`, { journeyCode }),
    assertLink: (body: LinkRequest) => client.post<Link>('/api/identity/links', body),

    // --- consent (ConsentController) ---
    requestConsent: (citizenId: Uuid, purposeCode: string) =>
      client.post<ConsentRequest>('/api/consent/requests', { citizenId, purposeCode }),
    grantConsent: (requestId: Uuid, citizenId: Uuid) =>
      client.post<ConsentArtifact>(`/api/consent/requests/${enc(requestId)}/grant`, { citizenId }),
    listConsents: (citizenId: Uuid) => client.get<ConsentArtifact[]>(`/api/consent/citizens/${enc(citizenId)}`),
    revokeConsent: (consentId: Uuid, reason?: string) =>
      client.post<void>(`/api/consent/me/${enc(consentId)}/revoke`, reason ? { reason } : {}),

    // --- submit (JourneyController) ---
    startJourney: (code: string, citizenId: Uuid, submission: Record<string, unknown> = {}) =>
      client.post<JourneyInstance>(`/api/journeys/${enc(code)}/start`, { citizenId, submission }),

    // --- track (TrackingController) ---
    listApplications: (citizenId: Uuid, size = 50) =>
      client.get<ApplicationSummary[]>('/api/applications', { citizenId, size }),
    getApplication: (referenceNo: string) => client.get<ApplicationView>(`/api/applications/${enc(referenceNo)}`),
    getApplicationSteps: (referenceNo: string) => client.get<StepView[]>(`/api/applications/${enc(referenceNo)}/steps`),
    getIssuedRecords: (referenceNo: string) =>
      client.get<IssuedRecord[]>(`/api/applications/${enc(referenceNo)}/issued-records`),
    /** Resolves to undefined (204) until the application is disbursed. */
    getDisbursement: (referenceNo: string) =>
      client.get<Disbursement | undefined>(`/api/applications/${enc(referenceNo)}/disbursement`),
  }
}

export type CitizenApi = ReturnType<typeof createCitizenApi>
