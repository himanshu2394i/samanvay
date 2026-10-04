import type { ApiClient } from './client'
import type {
  AuditCheckpoint,
  AuditRecord,
  AuditVerification,
  BankReview,
  CitizenMatch,
  ConnectorDefinition,
  ConnectorDraft,
  ConnectorTestReport,
  DataSourceDefinition,
  DataSourceDraft,
  DataSourceHealth,
  DepartmentDraft,
  IdentityCandidate,
  ImportPreview,
  JourneyException,
  JourneyStatus,
  JourneyState,
  MappingDraft,
  OpenApiImportRequest,
  OpsMetrics,
  OpsOverview,
  Page,
  SchemaDraft,
  SchemaSummary,
  OnboardRequest,
  OnboardingPlan,
  OnboardingResult,
  TrialResult,
} from './staffTypes'
import type { ApplicationSummary, ApplicationView, Department, IssuedRecord, JourneyDefinition, Link, StepView, Uuid } from './types'

/**
 * Every endpoint the officer / admin / reviewer surfaces call. Paths, verbs and bodies are
 * taken from the controllers under src/main/java/**\/internal/web; the role each route needs
 * is in SecurityConfig (kept in step with ApiAccessMatrixIT). Nothing here is invented: a
 * staff action with no endpoint yet is a marked TODO in the page, not a call.
 */
export function createStaffApi(client: ApiClient) {
  const enc = encodeURIComponent
  return {
    // --- officer: exception queue (JourneyController; OFFICER) ---
    listExceptions: () => client.get<JourneyException[]>('/api/journeys/exceptions'),
    getInstance: (instanceId: Uuid) => client.get<JourneyState>(`/api/journeys/instances/${enc(instanceId)}`),
    retryInstance: (instanceId: Uuid) => client.post<void>(`/api/journeys/instances/${enc(instanceId)}/retry`),

    // --- officer: bank-account review (OfficerBankReviewController; OFFICER) ---
    listBankReviews: () => client.get<BankReview[]>('/api/officer/bank-reviews'),
    uploadPassbook: (reviewId: Uuid, file: File) => {
      const form = new FormData()
      form.append('file', file)
      return client.post<void>(`/api/officer/bank-reviews/${enc(reviewId)}/passbook`, form)
    },
    requestDocument: (reviewId: Uuid) => client.post<void>(`/api/officer/bank-reviews/${enc(reviewId)}/request-document`),
    approveBankReview: (reviewId: Uuid, reason?: string) =>
      client.post<void>(`/api/officer/bank-reviews/${enc(reviewId)}/approve`, { reason: reason ?? '' }),
    rejectBankReview: (reviewId: Uuid, reason: string) =>
      client.post<void>(`/api/officer/bank-reviews/${enc(reviewId)}/reject`, { reason }),

    // --- officer: application review (TrackingController, IssuedRecordsWeb; OFFICER) ---
    /** Recent applications across citizens (no citizenId), newest first. */
    listApplications: (size = 50) => client.get<ApplicationSummary[]>('/api/applications', { size }),
    getApplication: (referenceNo: string) => client.get<ApplicationView>(`/api/applications/${enc(referenceNo)}`),
    getApplicationSteps: (referenceNo: string) => client.get<StepView[]>(`/api/applications/${enc(referenceNo)}/steps`),
    getIssuedRecords: (referenceNo: string) =>
      client.get<IssuedRecord[]>(`/api/applications/${enc(referenceNo)}/issued-records`),
    /** Approve a VERIFIED application (VERIFIED→APPROVED, terminal); a 409 otherwise. */
    approveApplication: (instanceId: Uuid) =>
      client.post<{ instanceId: string; status: string }>(`/api/journeys/instances/${enc(instanceId)}/approve`),
    /** Reject a non-terminal application (→REJECTED, terminal) with a reason; a 409 if already terminal. */
    rejectApplication: (instanceId: Uuid, reason: string) =>
      client.post<{ instanceId: string; status: string }>(`/api/journeys/instances/${enc(instanceId)}/reject`, { reason }),

    // --- officer: find a citizen (IdentityController; OFFICER) ---
    /** Name substring (Latin or Devanagari) or exact citizen id; capped, coarse fields only. */
    searchCitizens: (q: string) => client.get<CitizenMatch[]>('/api/identity/citizens/search', { q }),

    // --- officer + admin: ops dashboards (OpsMetricsController; OFFICER, ADMIN) ---
    getMetrics: () => client.get<OpsMetrics>('/api/ops/metrics'),
    /** Onboarded departments with their documents, central-schema mappings and journeys. */
    getOverview: () => client.get<OpsOverview>('/api/ops/overview'),
    /** One journey: is each document source connected and working, its applications and its middle-layer log. */
    getJourneyStatus: (code: string) => client.get<JourneyStatus>(`/api/ops/journeys/${enc(code)}`),

    // --- officer + admin: audit ledger, read only (AuditController; OFFICER, ADMIN) ---
    auditHead: () => client.get<{ seq: number }>('/api/audit/head'),
    auditCheckpoint: () => client.get<AuditCheckpoint | undefined>('/api/audit/checkpoint'),
    auditVerify: (from?: number, to?: number) => client.get<AuditVerification>('/api/audit/verify', { from, to }),
    auditEntries: (opts: { action?: string; page?: number; size?: number } = {}) =>
      client.get<AuditRecord[]>('/api/audit/entries', { action: opts.action, page: opts.page, size: opts.size ?? 40 }),

    // --- admin: catalog view (CatalogController) ---
    listDepartments: () => client.get<Department[]>('/api/catalog/departments'),
    listJourneys: () => client.get<JourneyDefinition[]>('/api/catalog/journeys'),
    /** OFFICER, ADMIN */
    listConnectors: () => client.get<ConnectorDefinition[]>('/api/catalog/connectors'),
    /** OFFICER, ADMIN: the target schema refs the importer can map onto. */
    listSchemas: () => client.get<string[]>('/api/catalog/schemas'),
    /** OFFICER, ADMIN: the central schema with each field's type and whether it is required. */
    listSchemaDetails: () => client.get<SchemaSummary[]>('/api/catalog/schema-details'),
    /** ADMIN: adds a schema or a new version; the server refuses an existing ref. */
    addSchema: (draft: SchemaDraft) => client.post<SchemaSummary>('/api/catalog/schemas', draft),

    // --- admin: onboarding writes (CatalogController; ADMIN) ---
    registerDepartment: (draft: DepartmentDraft) => client.post<Department>('/api/catalog/departments', draft),
    registerDataSource: (draft: DataSourceDraft) => client.post<DataSourceDefinition>('/api/catalog/data-sources', draft),
    createConnectorDraft: (draft: ConnectorDraft) => client.post<ConnectorDefinition>('/api/catalog/connectors', draft),
    /** Preview only: suggests lexical matches and never saves or publishes anything. */
    importOpenApi: (body: OpenApiImportRequest) => client.post<ImportPreview>('/api/catalog/import/openapi', body),
    /** Review what onboarding a department from its manifest would do. Changes nothing. ADMIN. */
    onboardPlan: (baseUrl: string) => client.post<OnboardingPlan>('/api/catalog/onboard/plan', { baseUrl }),
    /** Onboard what was reviewed and ticked (all drafts, one transaction); refused if the manifest changed. ADMIN. */
    onboard: (req: OnboardRequest) => client.post<OnboardingResult>('/api/catalog/onboard', req),
    /** Trial fetch of a connector for the department's fake sample person (or a named one). ADMIN. */
    trialConnector: (ref: string, personId?: string) =>
      client.post<TrialResult>(`/api/connector/trial/${enc(ref)}`, personId ? { personId } : {}),
    /** Registered data sources with last-known connectivity health. OFFICER, ADMIN. */
    listDataSources: () => client.get<DataSourceHealth[]>('/api/catalog/data-sources'),
    /** Live connectivity check for one data source; records and returns GREEN/RED/UNKNOWN. ADMIN. */
    probeDataSource: (code: string) => client.post<DataSourceHealth>(`/api/catalog/data-sources/${enc(code)}/probe`),
    /** Publish a ready DRAFT journey (make it live to citizens); 400 if a required connector is missing. ADMIN. */
    publishJourney: (code: string) => client.post<JourneyDefinition>(`/api/catalog/journeys/${enc(code)}/publish`),
    saveMapping: (draft: MappingDraft) => client.post<MappingDraft>('/api/catalog/mappings', draft),
    testConnector: (ref: string) => client.post<ConnectorTestReport>(`/api/catalog/connectors/${enc(ref)}/test`),
    publishConnector: (ref: string, report: ConnectorTestReport) =>
      client.post<ConnectorDefinition>(`/api/catalog/connectors/${enc(ref)}/publish`, report),

    // --- reviewer: identity review queue (IdentityController; REVIEWER) ---
    reviewQueue: (size = 50) => client.get<Page<IdentityCandidate> | IdentityCandidate[]>('/api/identity/review-queue', { size }),
    confirmCandidate: (id: Uuid, note?: string) =>
      client.post<Link>(`/api/identity/candidates/${enc(id)}/confirm`, note ? { note } : {}),
    rejectCandidate: (id: Uuid, note?: string) =>
      client.post<void>(`/api/identity/candidates/${enc(id)}/reject`, note ? { note } : {}),
  }
}

export type StaffApi = ReturnType<typeof createStaffApi>
