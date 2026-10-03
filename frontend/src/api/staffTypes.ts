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
  /** Notification delivery/retry health. Optional: absent on an older backend without the field. */
  notifications?: {
    sent: number
    failed: number
    retriedSent: number
    retriedFailed: number
    /** First-attempt success rate (0..1), or null before any delivery. */
    sentRate: number | null
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

/** GET /api/identity/citizens/search — one officer-search hit; coarse fields only (birth year, not DOB). */
export interface CitizenMatch {
  citizenId: Uuid
  nameLatin: string
  nameDevanagari: string | null
  birthYear: number | null
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

// --- department discovery manifest (GET {baseUrl}/.well-known/samanvay/manifest) ---
export interface ManifestInput {
  name: string
  in: string
  required: boolean
  description: string | null
}
export interface ManifestField {
  name: string
  type: string
  sensitive: boolean
}
export interface ManifestDocument {
  category: string
  title: string
  protocol: string
  method: string
  path: string
  inputs: ManifestInput[]
  fields: ManifestField[]
}
/** A document category a journey needs, and the department that provides it. */
export interface ManifestRequiredCategory {
  category: string
  department: string
}
export interface ManifestJourney {
  code: string
  name: string
  description: string | null
  referencePrefix: string
  slaHours: number
  consentPurpose: string
  requester: string
  requiredCategories: ManifestRequiredCategory[]
}

/** Body for POST /api/catalog/journeys (mirrors the Java JourneyDraft record). */
export interface JourneyDraft {
  code: string
  name: string
  referencePrefix: string
  slaHours: number
  consentPurpose: string
  requester: string
  requiredCategories: string[]
  /** category -> department that provides it */
  sources: Record<string, string>
}
export interface DepartmentManifest {
  manifestVersion: number
  department: { code: string; name: string; description: string | null }
  documents: ManifestDocument[]
  journeys: ManifestJourney[]
}

// --- data source health (live connectivity probe / monitoring) ---
export interface DataSourceHealth {
  code: string
  departmentCode: string
  protocol: string
  baseHost: string
  healthStatus: string
  detail: string | null
}

// --- one-go onboarding from a manifest (docs/FINAL-CHANGES.md section 10) ---

/** Something an operator must do that onboarding cannot: provision a secret, configure SFTP/JDBC, serve HTTPS. */
export interface PendingStep {
  kind: string
  subject: string
  detail: string
  data: Record<string, string>
}

export interface DocumentPlan {
  category: string
  title: string
  protocol: string
  dataSourceCode: string
  connectorId: string
  newVersionOfExisting: boolean
  /** null when no central schema is seeded for the category (seed it first). */
  centralSchemaRef: string | null
  suggestions: MappingSuggestion[]
  unmappedRequired: string[]
  problems: string[]
  ready: boolean
}

export interface JourneyPlan {
  code: string
  name: string
  exists: boolean
  requiredCategories: string[]
}

/** What onboarding a department WOULD do; nothing is changed. The digest identifies the manifest that was reviewed. */
export interface OnboardingPlan {
  departmentCode: string
  departmentName: string
  manifestDigest: string
  departmentExists: boolean
  /** Onboarded from a manifest before (a seeded department that never was is false). */
  onboardedFromManifest?: boolean
  changedSinceOnboarding: boolean
  documents: DocumentPlan[]
  journeys: JourneyPlan[]
  pendingSteps: PendingStep[]
  /** Thumbprint of the key that signed the manifest just read; null/absent = the manifest is not signed. */
  manifestKeyThumbprint?: string | null
  /** The key an admin approved earlier for this department; null/absent = none yet. */
  pinnedKeyThumbprint?: string | null
}

export interface OnboardRequest {
  baseUrl: string
  manifestDigest: string
  categories: string[]
  acceptSuggestedMappings: boolean
  mappings: Record<string, FieldMapping[]>
  /** The signing-key thumbprint the admin confirmed with the department (needed for a key that is new or changed). */
  approvedManifestKey?: string
}

/** What was created; all connectors and journeys are DRAFT until tested and published. */
export interface OnboardingResult {
  departmentCode: string
  dataSources: string[]
  connectorRefs: string[]
  mappingRefs: string[]
  journeysCreated: string[]
  skipped: string[]
  pendingSteps: PendingStep[]
}

/** The result of a trial fetch of a connector for the department's published FAKE sample person. */
export interface TrialResult {
  ok: boolean
  /** SUCCESS, NOT_FOUND, UNAVAILABLE, INVALID, or ERROR (the call itself failed: refused, misconfigured...). */
  outcome: string
  personId: string
  /** The fields that came back (after mapping) when ok; null otherwise. */
  fields: Record<string, unknown> | null
  detail: string | null
}

/** One field of a central schema (type is string, integer, number or boolean; older schemas may say "unspecified"). */
export interface SchemaField {
  name: string
  type: string
  required: boolean
}

export interface SchemaSummary {
  ref: string
  name: string
  version: number
  /** The document category the schema describes; null for an older schema that has none. */
  category: string | null
  fields: SchemaField[]
}

export interface SchemaDraft {
  ref: string
  category: string
  fields: SchemaField[]
}
