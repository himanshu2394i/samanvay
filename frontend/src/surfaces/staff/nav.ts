import type { StaffRole } from '../../auth/roles'

export interface StaffLink {
  to: string
  label: string
  blurb: string
  roles: readonly StaffRole[]
}

/** The single source for both the header nav and the home cards; routes use the same role sets. */
export const OFFICER: readonly StaffRole[] = ['OFFICER']
export const OPS: readonly StaffRole[] = ['OFFICER', 'ADMIN']
export const ADMIN: readonly StaffRole[] = ['ADMIN']
export const REVIEWER: readonly StaffRole[] = ['REVIEWER']

export const STAFF_LINKS: StaffLink[] = [
  { to: '/staff/officer/exceptions', label: 'Exceptions', blurb: 'Journeys that stopped on a department record, with retry.', roles: OFFICER },
  { to: '/staff/officer/bank-reviews', label: 'Bank reviews', blurb: 'Bank-account checks that need a person: passbook, approve or reject.', roles: OFFICER },
  { to: '/staff/officer/applications', label: 'Applications', blurb: 'Review applications, their department checks and the records received.', roles: OFFICER },
  { to: '/staff/ops/metrics', label: 'Metrics', blurb: 'Connector health, SLA, consent decisions and the exception queue.', roles: OPS },
  { to: '/staff/ops/audit', label: 'Audit ledger', blurb: 'Verify the tamper-evident chain and browse entries.', roles: OPS },
  { to: '/staff/admin/catalog', label: 'Catalog', blurb: 'Departments, journeys and connectors.', roles: ADMIN },
  { to: '/staff/admin/onboarding', label: 'Onboarding', blurb: 'Add a department, data source and connector; import an OpenAPI spec.', roles: ADMIN },
  { to: '/staff/reviewer/queue', label: 'Identity review', blurb: 'Confirm or reject candidate account links.', roles: REVIEWER },
]
