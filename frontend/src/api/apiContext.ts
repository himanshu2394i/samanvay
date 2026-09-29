import { createContext, useContext } from 'react'
import type { CitizenApi } from './citizenApi'
import type { StaffApi } from './staffApi'

export const CitizenApiContext = createContext<CitizenApi | null>(null)

/** The typed citizen API, already wired to the signed-in session's bearer token. */
export function useCitizenApi(): CitizenApi {
  const api = useContext(CitizenApiContext)
  if (!api) throw new Error('useCitizenApi must be used inside <ApiProvider>')
  return api
}

export const StaffApiContext = createContext<StaffApi | null>(null)

/** The typed staff API (officer / admin / reviewer), wired to the signed-in staff session. */
export function useStaffApi(): StaffApi {
  const api = useContext(StaffApiContext)
  if (!api) throw new Error('useStaffApi must be used inside <ApiProvider>')
  return api
}
