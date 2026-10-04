import { createContext, useContext } from 'react'
import type { StaffApi } from './staffApi'

export const StaffApiContext = createContext<StaffApi | null>(null)

/** The typed staff API (officer / admin / reviewer), wired to the signed-in staff session. */
export function useStaffApi(): StaffApi {
  const api = useContext(StaffApiContext)
  if (!api) throw new Error('useStaffApi must be used inside <ApiProvider>')
  return api
}
