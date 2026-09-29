// Wire types for the staff-facing endpoints (officer desk, ops metrics, audit, catalog
// onboarding, identity review). Each mirrors a Java record in src/main/java/**; Instant is
// an ISO-8601 string, UUID a string, `null` where the Java field may be null.
import type { IsoInstant, Uuid } from './types'

// --- orchestration: exception queue (GET /api/journeys/exceptions) ------------------------
export interface JourneyException {
  id: Uuid
  instanceId: Uuid
  stepCode: string
  reason: string
  createdAt: IsoInstant
}

/** GET /api/journeys/instances/{id} */
export interface JourneyState {
  id: Uuid
  status: string
  stepOutcomes: Record<string, string>
}

// --- officer bank-account review (/api/officer/bank-reviews) ------------------------------
/** No holder name is ever returned; only the masked account and the matcher's verdict. */
export interface BankReview {
  id: Uuid
  applicationId: string
  accountMasked: string
  reason: string
  reasonText: string
  matcherVersion: string | null
  status: string
  hasDocument: boolean
  createdAt: IsoInstant
}

// --- ops metrics (GET /api/ops/metrics) ---------------------------------------------------
export interface Latency {
  count: number
  p50Ms: number | null
  p95Ms: number | null
  meanMs: number | null
  maxMs: number | null
}

export interface ConnectorSource {
  source: string
  calls: number
  success: number
  failure: number
  unavailable: number
  successRate: number | null
  latency: Latency | null
  latencyByOutcome: Record<string, Latency>
}

export interface JourneySla {
  journeyCode: string
  open: number
  breached: number
  dueSoon: number
}

export interface SlaCase {
  referenceNo: string
  journeyCode: string
  status: string
  slaDueAt: IsoInstant | null
  /** Negative when overdue. */
  secondsToDue: number
}

export interface ReasonCount {
  reason: string
  count: number
}

export interface ExceptionRow {
  id: Uuid
  instanceId: Uuid
  stepCode: string
  reason: string
  createdAt: IsoInstant
  ageSeconds: number
}

export interface OpsMetrics {
  generatedAt: IsoInstant
  connector: { latencyWindowMinutes: number; sources: ConnectorSource[] }
  sla: {
    open: number
    breached: number
    dueSoon: number
    withinSlaPercent: number | null
    byJourney: JourneySla[]
    watchlist: SlaCase[]
  }
  consent: { granted: number; denied: number; grantRate: number | null; denialsByReason: ReasonCount[] }
  exceptionQueue: {
    open: number | null
    oldestAgeSeconds: number | null
    byReason: ReasonCount[]
    oldest: ExceptionRow[]
  }
}

// --- audit ledger (GET /api/audit/...) ----------------------------------------------------
export interface AuditRecord {
  seq: number
  ts: IsoInstant
  actorId: string
  action: string
  subjectId: string | null
  departmentId: string | null
  outcome: string
  reason: string | null
  consentId: Uuid | null
}

export interface AuditVerification {
  valid: boolean
  fromSeq: number
  toSeq: number
  failedAtSeq: number | null
  reason: string | null
}

/** The latest signed checkpoint; byte[] fields arrive base64 encoded. Null before the first one. */
export interface AuditCheckpoint {
  seq: number
  uptoEntrySeq: number
  rootHash: string
  signedAt: IsoInstant
  signature: string
  publishedRef: string | null
  keyId: string | null
}

// --- identity review queue (reviewer role) ------------------------------------------------
export interface IdentityCandidate {
  id: Uuid
  citizenId: Uuid
  departmentCode: string
  score: number
  status: string
}

/** Spring Data page as serialised by the API; only `content` is relied on. */
export interface Page<T> {
  content: T[]
  totalElements?: number
}

// --- catalog: read (GET /api/catalog/...) -------------------------------------------------
export interface DataCategory {
  code: string
}

export interface ConnectorDefinition {
  ref: string
  connectorId: string
  version: number
  dataSourceCode: string
  category: DataCategory
  capabilitiesJson: string
  inputsJson: string | null
  slaMs: number | null
  /** DRAFT | PUBLISHED | DEPRECATED | RETIRED */
  status: string
}

// --- catalog: onboarding writes (ADMIN) ---------------------------------------------------
export interface DepartmentDraft {
  code: string
  name: string
  idpRealm: string
  contactEmail: string
  defaultSlaMs: number | null
}

export interface DataSourceDraft {
  code: string
  departmentCode: string
  protocol: string
  baseHost: string
  authType: string
  authConfigRef: string
}

export interface DataSourceDefinition {
  code: string
  departmentCode: string
  protocol: string
  baseHost: string
  authType: string
  authConfigRef: string
  retryConfig?: string | null
  breakerConfig?: string | null
}

export interface ConnectorDraft {
  connectorId: string
  dataSourceCode: string
  category: DataCategory
  capabilitiesJson: string
  inputsJson: string
  slaMs: number | null
}

export interface OpenApiImportRequest {
  spec: string
  operationId: string
  targetSchemaRef: string
}

export interface MappingSuggestion {
  source: string
  target: string
  confidence: number
  rationale: string
  approved: boolean
}

export interface ImportPreview {
  sourceFields: string[]
  targetFields: string[]
  suggestions: MappingSuggestion[]
}

export interface FieldMapping {
  source: string
  target: string
  transforms: { fn: string; args: string[] }[]
}

export interface MappingDraft {
  ref: string
  connectorRef: string
  rules: FieldMapping[]
}

export interface ConnectorTestReport {
  passed: boolean
  failures: string[]
}
