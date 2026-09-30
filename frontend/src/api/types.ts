// Wire types for the Samanvay REST API. Each mirrors a Java record in src/main/java/**/api
// (Jackson 3 defaults: camelCase, ISO-8601 strings for Instant / LocalDate, UUIDs as strings).

export type Uuid = string
/** ISO-8601 instant, e.g. 2026-09-29T10:15:30Z */
export type IsoInstant = string

/** RFC 7807 body written by ApiExceptionHandler / ProblemWriter. */
export interface ProblemDetail {
  type?: string
  title?: string
  status?: number
  detail?: string
  instance?: string
  /** Stable machine reason, e.g. MISSING_DEPARTMENT_LINKS, LINK_PROOF_INVALID. */
  reason?: string
  /** Present on MISSING_DEPARTMENT_LINKS (409). */
  missingDepartments?: string[]
}

// --- catalog (GET /api/catalog/...) -----------------------------------------------------

export interface Department {
  code: string
  name: string
  status: string
}

export interface JourneyPolicy {
  acceptStale: boolean
  slaHours: number
  requester: string
  /** Catalog purpose code the citizen consents under, e.g. SCHOLARSHIP_ELIGIBILITY. */
  purpose: string
  referencePrefix: string
  /** data category -> department code that holds it */
  sources: Record<string, string>
}

export interface JourneyDefinition {
  code: string
  name: string
  bpmnRef: string
  requiredCategories: string[]
  policy: JourneyPolicy
  status: string
  academicYearStartMonth: number | null
}

// --- identity (/api/identity/...) -------------------------------------------------------

export type DobPrecision = 'DAY' | 'MONTH' | 'YEAR'

export interface ProfileDraft {
  nameLatin: string
  nameDevanagari?: string
  givenName?: string
  familyName?: string
  fatherName?: string
  /** yyyy-MM-dd */
  dob: string
  dobPrecision: DobPrecision
  gender?: string
  contactMasked?: string
}

export interface Profile {
  citizenId: Uuid
  nameLatin: string
  nameDevanagari: string | null
  familyName: string | null
  fatherName: string | null
  dob: string
  dobPrecision: DobPrecision
}

export type LinkProofKind = 'DIGILOCKER' | 'LOCAL_ID_OTP' | 'DEPT_IDP'

export interface LinkProofProviderInfo {
  kind: LinkProofKind
  label: string
}

export interface DepartmentLinkNeed {
  departmentCode: string
  departmentName: string
  categories: string[]
  linked: boolean
  localIdType: string | null
  localIdToken: string | null
}

export interface ConnectAccounts {
  journeyCode: string
  departments: DepartmentLinkNeed[]
  providers: LinkProofProviderInfo[]
}

export interface LinkRequest {
  citizenId: Uuid
  departmentCode: string
  localIdType: string
  localId: string
  provider: LinkProofKind
  proof: string
}

export interface Link {
  id: Uuid
  citizenId: Uuid
  departmentCode: string
  localIdType: string
  localIdToken: string
  provenance: string
  status: string
}

// --- consent (/api/consent/...) ---------------------------------------------------------

export interface ConsentRequest {
  id: Uuid
  citizenId: Uuid
  requesterId: string
  purposeCode: string
  purposeText: string
  categories: string[]
  status: string
}

export interface ConsentArtifact {
  id: Uuid
  citizenId: Uuid
  requesterId: string
  purposeCode: string
  categories: string[]
  granularity: string
  validFrom: IsoInstant
  validUntil: IsoInstant
  frequencyLimit: number | null
  /** ACTIVE | EXPIRED | REVOKED (computed at read time) */
  status: string
  version: number
  dataTypes: string[]
  createdAt: IsoInstant
  revokedAt: IsoInstant | null
  revokedBy: string | null
  /** Citizen-facing label: "Active", "Ended", "Withdrawn by you" */
  statusLabel: string
  frequency: string | null
}

// --- orchestration (/api/journeys/...) and tracking (/api/applications/...) ------------

export interface JourneyInstance {
  id: Uuid
  processInstanceId: string
  journeyCode: string
  citizenId: Uuid
}

export interface ApplicationSummary {
  referenceNo: string
  citizenId: Uuid
  journeyCode: string
  status: string
  slaDueAt: IsoInstant | null
  instanceId: Uuid | null
}

export interface ApplicationView {
  referenceNo: string
  citizenId: Uuid
  journeyCode: string
  status: string
  submittedAt: IsoInstant | null
  slaDueAt: IsoInstant | null
  instanceId: Uuid | null
}

export interface StepView {
  stepCode: string
  departmentCode: string
  /** COMPLETED | PENDING_SOURCE | FAILED | ... */
  status: string
  startedAt: IsoInstant | null
  completedAt: IsoInstant | null
  slaDueAt: IsoInstant | null
  source: string | null
  outcome: string | null
  dataAsOf: IsoInstant | null
}

export interface IssuedField {
  label: string
  value: string
}

/** One instalment of a disbursement schedule. */
export interface DisbursementInstalment {
  sequence: number
  status: string
}

/**
 * A sanctioned application's disbursement (GET /api/applications/{ref}/disbursement). Absent
 * (204) until the application is disbursed. Carries no money amount: the mock DBT holds none.
 */
export interface Disbursement {
  status: string
  createdAt: string
  instalmentCount: number
  instalments: DisbursementInstalment[]
}

/** A department record as it looks right now (GET /api/applications/{ref}/issued-records). Never stored by Samanvay. */
export interface IssuedRecord {
  stepCode: string
  departmentCode: string
  issuer: string
  liveSystem: string
  liveSystemUrl: string | null
  title: string
  documentKind: string
  fields: IssuedField[]
  storedInSamanvay: boolean
  fetchStatus: string
}
