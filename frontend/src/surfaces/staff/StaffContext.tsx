import { createContext, useContext } from 'react'
import { hasAnyRole, type StaffRole } from '../../auth/roles'

export interface StaffSession {
  roles: StaffRole[]
  department: string | null
  can: (allowed: readonly StaffRole[]) => boolean
}

export const StaffSessionContext = createContext<StaffSession | null>(null)

export function useStaffSession(): StaffSession {
  const s = useContext(StaffSessionContext)
  if (!s) throw new Error('useStaffSession must be used under <RequireStaff>')
  return s
}

export function makeStaffSession(roles: StaffRole[], department: string | null): StaffSession {
  return { roles, department, can: (allowed) => hasAnyRole(roles, allowed) }
}
