import type { ApiClient } from './client'
import type {
  AuditCheckpoint,
  AuditRecord,
  AuditVerification,
  BankReview,
  ConnectorDefinition,
  ConnectorDraft,
  ConnectorTestReport,
  DataSourceDefinition,
  DataSourceDraft,
  DepartmentDraft,
  IdentityCandidate,
  ImportPreview,
  JourneyException,
  JourneyState,
  MappingDraft,
  OpenApiImportRequest,
  OpsMetrics,
  Page,
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
    // TODO(approve): no application approval endpoint exists on main yet (only bank-account
    // reviews have approve/reject), so none is called. Open PR #55 proposes
    // POST /api/journeys/instances/{instanceId}/approve (OFFICER; VERIFIED only, else 409).
    // Once it merges, add `approveApplication(instanceId)` here and enable the marked button in
    // ApplicationReviewPage. Not called from anywhere today.

    // --- officer + admin: ops dashboards (OpsMetricsController; OFFICER, ADMIN) ---
    getMetrics: () => client.get<OpsMetrics>('/api/ops/metrics'),

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

    // --- admin: onboarding writes (CatalogController; ADMIN) ---
    registerDepartment: (draft: DepartmentDraft) => client.post<Department>('/api/catalog/departments', draft),
    registerDataSource: (draft: DataSourceDraft) => client.post<DataSourceDefinition>('/api/catalog/data-sources', draft),
    createConnectorDraft: (draft: ConnectorDraft) => client.post<ConnectorDefinition>('/api/catalog/connectors', draft),
    /** Preview only: suggests lexical matches and never saves or publishes anything. */
    importOpenApi: (body: OpenApiImportRequest) => client.post<ImportPreview>('/api/catalog/import/openapi', body),
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
